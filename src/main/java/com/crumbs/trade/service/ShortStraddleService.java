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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShortStraddleService {

    private static final String STRATEGY_SIGNAL = "SHORT_STRADDLE_VWAP";
    private static final int REQUIRED_CONSECUTIVE_HITS = 5;

    private static final Map<String, BigDecimal> TARGET_POINTS = Map.of(
            "NIFTY", new BigDecimal("15"),
            "CRUDEOILM", new BigDecimal("50")
    );

    private static final LocalTime NIFTY_SQUARE_OFF = LocalTime.of(15, 20);
    private static final LocalTime CRUDE_SQUARE_OFF = LocalTime.of(23, 20);

    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_INACTIVE = 0;
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String PHASE_ENTRY = "ENTRY";
    private static final String PHASE_EXIT = "EXIT";

    private final ShortStraddleRepository straddleRepository;
    private final OrderRepository ordersRepository;
    private final StrategyRepo strategyRepo;
    private final OrderService orderService;
    private final TelegramService telegramService;

    private final ConcurrentHashMap<String, Integer> hitCounters = new ConcurrentHashMap<>();

    public void evaluate(String symbol) {
        LocalTime now = LocalTime.now();
        String uniqueName = "SHORT_STRADDLE_" + symbol.toUpperCase();
        
        List<Orders> activeOrders = ordersRepository.findByNameAndSignalAndActive(
                uniqueName, STRATEGY_SIGNAL, STATUS_ACTIVE);

        if (!activeOrders.isEmpty() && isSquareOffTime(symbol, now)) {
            straddleRepository.findLatestByName(symbol).ifPresent(tick -> {
                log.info("⏰ [{}][EOD] Square-off reached.", symbol);
                closeAll(activeOrders, tick, "EOD_SQUARE_OFF");
            });
            return;
        }
     
        if (activeOrders.isEmpty()) {
            straddleRepository.findATMBySymbol(symbol).ifPresent(tick -> processEntrySequence(symbol, tick));
        } else {
            BigDecimal tradedStrike = activeOrders.get(0).getStrike();
            straddleRepository.findLatestBySymbolAndStrike(symbol, tradedStrike).ifPresent(tick -> {
                processExitSequence(symbol, tick, activeOrders);
            });
        }
    }

    private void processEntrySequence(String symbol, StraddleIntraday tick) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();

        if (cp.compareTo(cv) < 0) {
            int count = hitCounters.merge(symbol, 1, Integer::sum);
            log.info("🎯 [{}][SCAN] Hit {}/{} | Strike: {} | Gap: {}", 
                    symbol, count, REQUIRED_CONSECUTIVE_HITS, tick.getStrike(), cv.subtract(cp));

            if (count >= REQUIRED_CONSECUTIVE_HITS) {
                executeShortStraddle(symbol, tick, cv.subtract(cp));
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
        Strategy strategy = strategyRepo.findByName(symbol.toUpperCase());
        if (strategy == null) return;

        boolean isLive = "Y".equalsIgnoreCase(strategy.getLive());
        
        // Calculate the specific Target Gap (Initial Gap + Profit Points)
        BigDecimal ptsToCapture = TARGET_POINTS.getOrDefault(symbol.toUpperCase(), new BigDecimal("15"));
        BigDecimal targetValue = entryGap.add(ptsToCapture);

        // Process CE Leg
        processLeg(tick.getCeToken(), tick.getCeSymbol(), strategy, tick.getCePrice(), 
                   tick.getStrike(), uniqueName, "CE", cycleId, entryGap, targetValue, isLive);

        // Process PE Leg
        processLeg(tick.getPeToken(), tick.getPeSymbol(), strategy, tick.getPePrice(), 
                   tick.getStrike(), uniqueName, "PE", cycleId, entryGap, targetValue, isLive);

        telegramService.sendMessage(buildEntryMsg(symbol, tick.getStrike(), tick.getCePrice(), tick.getPePrice(), entryGap, ptsToCapture));
    }

    private void processLeg(String tokenStr, String symbol, Strategy strategy, BigDecimal price, 
                            BigDecimal strike, String uniqueName, String type, String cycleId, 
                            BigDecimal gap, BigDecimal targetValue, boolean isLive) {
        if (isLive) {
            try {
                Token t = new Token();
                t.setToken(tokenStr);
                t.setSymbol(symbol);
                t.setExch_seg(strategy.getExchange());
                t.setStrike(strike);
                orderService.orderPlaceWithToken(t, uniqueName, "SELL", true);
            } catch (Exception | SmartAPIException e) {
                log.error("❌ Failed live order: {}", e.getMessage());
                return; 
            }
        }
        // Updated to pass symbol, token, and target to DB
        saveOrder(uniqueName, type, price, strike, cycleId, gap, symbol, tokenStr, targetValue);
    }

    private void processExitSequence(String symbol, StraddleIntraday tick, List<Orders> activeOrders) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        BigDecimal currentGap = cv.subtract(cp);
        BigDecimal initialGap = activeOrders.get(0).getBreakeven();
        if (initialGap == null) initialGap = currentGap;
        
        BigDecimal ptsToCapture = TARGET_POINTS.getOrDefault(symbol.toUpperCase(), new BigDecimal("15"));
        BigDecimal targetGap = initialGap.add(ptsToCapture);

        if (cp.compareTo(cv) > 0) {
            closeAll(activeOrders, tick, "STOP_LOSS_VWAP");
        } else if (currentGap.compareTo(targetGap) >= 0) {
            closeAll(activeOrders, tick, "TARGET_REACHED");
        }
    }

    @Transactional
    protected void closeAll(List<Orders> activeOrders, StraddleIntraday tick, String reason) {
        String symbol = tick.getName().toUpperCase();
        Strategy strategy = strategyRepo.findByName(symbol);
        boolean isLive = strategy != null && "Y".equalsIgnoreCase(strategy.getLive());

        BigDecimal totalEntry = BigDecimal.ZERO;
        BigDecimal totalExit = BigDecimal.ZERO;

        for (Orders order : activeOrders) {
            try {
                if (isLive) {
                    String tokenToExit = "CE".equals(order.getOptionType()) ? tick.getCeToken() : tick.getPeToken();
                    orderService.exitActiveTradeByToken(tokenToExit, order.getName());
                }

                BigDecimal exitPrice = "CE".equals(order.getOptionType()) ? tick.getCePrice() : tick.getPePrice();
                totalEntry = totalEntry.add(order.getAskPrice());
                totalExit = totalExit.add(exitPrice);

                order.setActive(STATUS_INACTIVE); 
                order.setStatus(STATUS_CLOSED);
                order.setTradePhase(PHASE_EXIT);
                order.setClosedOn(LocalDateTime.now());
                order.setExitPrice(exitPrice);
                order.setPl(order.getAskPrice().subtract(exitPrice));
                ordersRepository.save(order);
            } catch (Exception | SmartAPIException e) {
                log.error("❌ Exit error: {}", e.getMessage());
            }
        }
        telegramService.sendMessage(buildExitMsg(symbol, tick.getStrike(), totalEntry, totalExit, reason));
    }

    // ==================== MESSAGING ====================

    private String buildEntryMsg(String sym, BigDecimal strike, BigDecimal ce, BigDecimal pe, BigDecimal gap, BigDecimal tgt) {
        String t = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        return String.format("""
            🚀 **STRADDLE ENTRY: %s**
            
            📌 **Strike** : %s
            💰 **CE / PE** : %.2f | %.2f
            📊 **Combined** : **%.2f**
            📉 **Gap to VWAP**: %.2f
            
            🎯 **Target** : +%s Points
            ⏰ **Time** : %s
            🟢 *Monitoring trade cycle...*
            """, sym, strike, ce, pe, ce.add(pe), gap, tgt, t);
    }

    private String buildExitMsg(String sym, BigDecimal strike, BigDecimal ent, BigDecimal ex, String reason) {
        BigDecimal pnl = ent.subtract(ex);
        String icon = pnl.signum() >= 0 ? "✅" : "❌";
        String t = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        return String.format("""
            %s **STRADDLE EXIT: %s**
            
            📌 **Strike** : %s
            🚪 **Reason** : **%s**
            
            📥 **Entry** : %.2f
            📤 **Exit** : %.2f
            💰 **PnL** : **%.2f Points**
            
            ⏰ **Time** : %s
            🏁 *Cycle complete.*
            """, icon, sym, strike, reason.replace("_", " "), ent, ex, pnl, t);
    }

    // ==================== UPDATED DB PERSISTENCE ====================

    private void saveOrder(String uniqueName, String type, BigDecimal price, BigDecimal strike, 
                           String cycleId, BigDecimal gap, String symbol, String token, BigDecimal target) {
        Orders order = new Orders();
        order.setName(uniqueName);
        order.setSignal(STRATEGY_SIGNAL);
        order.setCreatedOn(LocalDateTime.now());
        order.setOptionType(type);
        order.setSide("SELL");
        order.setAskPrice(price);
        order.setStrike(strike);
        order.setBreakeven(gap);
        
        // Fix: Mapping missing fields
        order.setSymbol(symbol); 
        order.setToken(token);
        order.setTarget(target); 
        
        order.setActive(STATUS_ACTIVE);
        order.setStatus(STATUS_OPEN);
        order.setTradePhase(PHASE_ENTRY);
        order.setTradeCycleId(cycleId);
        ordersRepository.save(order);
    }

    private boolean isSquareOffTime(String symbol, LocalTime now) {
        if ("NIFTY".equalsIgnoreCase(symbol)) return !now.isBefore(NIFTY_SQUARE_OFF);
        if ("CRUDEOIL".equalsIgnoreCase(symbol) || "CRUDEOILM".equalsIgnoreCase(symbol)) 
            return !now.isBefore(CRUDE_SQUARE_OFF);
        return false;
    }
}