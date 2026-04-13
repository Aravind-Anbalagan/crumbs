package com.crumbs.trade.service;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.entity.StraddleIntraday;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.ShortStraddleRepository;
import com.crumbs.trade.repo.StrategyRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShortStraddleService {

    // ==========================================
    // CONFIGURATION
    // ==========================================
    private static final String STRATEGY_SIGNAL = "SHORT_STRADDLE_VWAP";
    private static final int REQUIRED_CONSECUTIVE_HITS = 5;

    private static final Map<String, BigDecimal> TARGET_POINTS = Map.of(
            "NIFTY", new BigDecimal("15"),
            "CRUDEOIL", new BigDecimal("50")
    );

    private static final LocalTime NIFTY_SQUARE_OFF = LocalTime.of(15, 20);
    private static final LocalTime CRUDE_SQUARE_OFF = LocalTime.of(23, 20);

    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_INACTIVE = 0;
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String PHASE_ENTRY = "ENTRY";
    private static final String PHASE_EXIT = "EXIT";

    // ==========================================
    // DEPENDENCIES
    // ==========================================
    private final ShortStraddleRepository straddleRepository;
    private final OrderRepository ordersRepository;
    private final StrategyRepo strategyRepo;
    private final OrderService orderService;

    private final ConcurrentHashMap<String, Integer> hitCounters = new ConcurrentHashMap<>();

    /**
     * Main entry point called by the Scheduler
     */
    public void evaluate(String symbol) {
        LocalTime now = LocalTime.now();
        String uniqueName = "SHORT_STRADDLE_" + symbol.toUpperCase();
        
        List<Orders> activeOrders = ordersRepository.findByNameAndSignalAndActive(
                uniqueName, STRATEGY_SIGNAL, STATUS_ACTIVE);

        // 1. EOD Square-off Check
        if (!activeOrders.isEmpty() && isSquareOffTime(symbol, now)) {
            straddleRepository.findLatestByName(symbol).ifPresent(tick -> {
                log.info("⏰ [{}][EOD] Square-off reached. Closing position.", symbol);
                closeAll(activeOrders, tick, "INTRADAY_EOD_EXIT");
            });
            return;
        }

        // 2. Core Logic Flow
        if (activeOrders.isEmpty()) {
            // No active trade: Scan for Entry
            straddleRepository.findATMBySymbol(symbol).ifPresent(tick -> processEntrySequence(symbol, tick));
        } else {
            // Trade is active: Scan for Exit
            BigDecimal tradedStrike = activeOrders.get(0).getStrike();
            straddleRepository.findLatestBySymbolAndStrike(symbol, tradedStrike).ifPresent(tick -> {
                processExitSequence(symbol, tick, activeOrders);
            });
        }
    }

    private void processEntrySequence(String symbol, StraddleIntraday tick) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        BigDecimal gap = cv.subtract(cp);

        // Entry Condition: Combined Premium < Combined VWAP
        if (cp.compareTo(cv) < 0) {
            int count = hitCounters.merge(symbol, 1, Integer::sum);
            log.info("[{}][SCAN] Strike: {} | CP: {} | CV: {} | Count: {}/{}", 
                    symbol, tick.getStrike(), cp, cv, count, REQUIRED_CONSECUTIVE_HITS);

            if (count >= REQUIRED_CONSECUTIVE_HITS) {
                executeShortStraddle(symbol, tick, gap);
                hitCounters.put(symbol, 0); 
            }
        } else {
            hitCounters.put(symbol, 0);
        }
    }

    @Transactional
    protected void executeShortStraddle(String symbol, StraddleIntraday tick, BigDecimal entryGap) {
        String cycleId = UUID.randomUUID().toString();
        String uniqueName = "SHORT_STRADDLE_" + symbol.toUpperCase();
        
        // Fetch Strategy config (Contains Live/Paper flag and Exchange)
        Strategy strategy = strategyRepo.findByName(symbol.toUpperCase());
        if (strategy == null) {
            log.error("Strategy config for {} not found. Aborting execution.", symbol);
            return;
        }

        boolean isLive = "Y".equalsIgnoreCase(strategy.getLive());
        log.info("🚀 [{}][ENTRY] Starting Execution | Mode: {} | Strike: {}", 
                symbol, isLive ? "LIVE" : "PAPER", tick.getStrike());

        // Process CE Leg
        processLeg(tick.getCeToken(), tick.getCeSymbol(), strategy, tick.getCePrice(), 
                   tick.getStrike(), uniqueName, "CE", cycleId, entryGap, isLive);

        // Process PE Leg
        processLeg(tick.getPeToken(), tick.getPeSymbol(), strategy, tick.getPePrice(), 
                   tick.getStrike(), uniqueName, "PE", cycleId, entryGap, isLive);
    }

    private void processLeg(String tokenStr, String symbol, Strategy strategy, BigDecimal price, 
                            BigDecimal strike, String uniqueName, String type, String cycleId, 
                            BigDecimal gap, boolean isLive) {
        if (isLive) {
            try {
                Token t = new Token();
                t.setToken(tokenStr);
                t.setSymbol(symbol);
                t.setExch_seg(strategy.getExchange());
                t.setStrike(strike);

                orderService.orderPlaceWithToken(t, uniqueName, "SELL", true);
                log.info("✅ LIVE {} order placed successfully for {}", type, symbol);
            } catch (Exception | SmartAPIException e) {
                log.error("❌ Failed to place live {} order for {}: {}", type, symbol, e.getMessage());
                return; // Stop and don't save to DB if live placement fails
            }
        } else {
            log.info("📝 PAPER {} order logged for {}", type, symbol);
        }

        saveOrder(uniqueName, type, price, strike, cycleId, gap);
    }

    private void processExitSequence(String symbol, StraddleIntraday tick, List<Orders> activeOrders) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        BigDecimal currentGap = cv.subtract(cp);
        
        BigDecimal initialGap = activeOrders.get(0).getBreakeven();
        if (initialGap == null) initialGap = currentGap;
        
        BigDecimal ptsToCapture = TARGET_POINTS.getOrDefault(symbol.toUpperCase(), new BigDecimal("15"));
        BigDecimal targetGap = initialGap.add(ptsToCapture);

        log.info("[{}][LIVE] CP: {} | CV: {} | Current Gap: {} | Target: {}", 
                symbol, cp, cv, currentGap, targetGap);

        // Exit Logic: 
        // 1. Stop Loss: CP goes above CV
        // 2. Target: Target Gap reached
        if (cp.compareTo(cv) > 0) {
            closeAll(activeOrders, tick, "STOP_LOSS (CP > CV)");
        } else if (currentGap.compareTo(targetGap) >= 0) {
            closeAll(activeOrders, tick, "TARGET_REACHED (" + ptsToCapture + " pts)");
        }
    }

    @Transactional
    protected void closeAll(List<Orders> activeOrders, StraddleIntraday tick, String reason) {
        // Check strategy for the symbol to see if we should call the broker for exit
        String symbol = tick.getName().toUpperCase();
        Strategy strategy = strategyRepo.findByName(symbol);
        boolean isLive = strategy != null && "Y".equalsIgnoreCase(strategy.getLive());

        log.info("🏁 [{}][EXIT] Reason: {} | Mode: {}", symbol, reason, isLive ? "LIVE" : "PAPER");

        for (Orders order : activeOrders) {
            try {
                if (isLive) {
                    String tokenToExit = "CE".equals(order.getOptionType()) ? tick.getCeToken() : tick.getPeToken();
                    orderService.exitActiveTradeByToken(tokenToExit, order.getName());
                    log.info("✅ LIVE exit order executed for {}", order.getOptionType());
                }

                // Update DB state
                order.setActive(STATUS_INACTIVE); 
                order.setStatus(STATUS_CLOSED);
                order.setTradePhase(PHASE_EXIT);
                order.setClosedOn(LocalDateTime.now());
                
                BigDecimal exitPrice = "CE".equals(order.getOptionType()) ? tick.getCePrice() : tick.getPePrice();
                order.setExitPrice(exitPrice);
                order.setPl(order.getAskPrice().subtract(exitPrice));
                
                ordersRepository.save(order);
            } catch (Exception | SmartAPIException e) {
                log.error("❌ Error exiting leg {}: {}", order.getOptionType(), e.getMessage());
            }
        }
    }

    private void saveOrder(String uniqueName, String type, BigDecimal price, BigDecimal strike, String cycleId, BigDecimal gap) {
        Orders order = new Orders();
        order.setName(uniqueName);
        order.setSignal(STRATEGY_SIGNAL);
        order.setCreatedOn(LocalDateTime.now());
        order.setOptionType(type);
        order.setSide("SELL");
        order.setAskPrice(price);
        order.setStrike(strike);
        order.setBreakeven(gap);
        order.setActive(STATUS_ACTIVE);
        order.setStatus(STATUS_OPEN);
        order.setTradePhase(PHASE_ENTRY);
        order.setTradeCycleId(cycleId);
        ordersRepository.save(order);
    }

    private boolean isSquareOffTime(String symbol, LocalTime now) {
        if ("NIFTY".equalsIgnoreCase(symbol)) return !now.isBefore(NIFTY_SQUARE_OFF);
        if ("CRUDEOIL".equalsIgnoreCase(symbol)) return !now.isBefore(CRUDE_SQUARE_OFF);
        return false;
    }
}