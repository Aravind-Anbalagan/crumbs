package com.crumbs.trade.service;

import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.StraddleIntraday;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.ShortStraddleRepository;
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
    // STRATEGY CONFIGURATION (Modify Here)
    // ==========================================
    private static final String STRATEGY_NAME = "SHORT_STRADDLE_VWAP";
    private static final int REQUIRED_CONSECUTIVE_HITS = 5;

    // Define Target Points per Symbol
    private static final Map<String, BigDecimal> TARGET_POINTS = Map.of(
        "NIFTY", new BigDecimal("15"),
        "CRUDEOIL", new BigDecimal("50")
    );

    // Intraday Square-off Times
    private static final LocalTime NIFTY_SQUARE_OFF = LocalTime.of(15, 20);
    private static final LocalTime CRUDE_SQUARE_OFF = LocalTime.of(23, 20);
    
    // Status Constants
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_INACTIVE = 0;
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String PHASE_ENTRY = "ENTRY";
    private static final String PHASE_EXIT = "EXIT";
    // ==========================================

    private final ShortStraddleRepository straddleRepository;
    private final OrderRepository ordersRepository;
    private final ConcurrentHashMap<String, Integer> hitCounters = new ConcurrentHashMap<>();

    public void evaluate(String symbol) {
        LocalTime now = LocalTime.now();
     // Update the query to look for the unique name (e.g., SHORT_STRADDLE_NIFTY)
        String uniqueName = "SHORT_STRADDLE_" + symbol.toUpperCase();
        List<Orders> activeOrders = ordersRepository.findByNameAndSignalAndActive(
                uniqueName, STRATEGY_NAME, STATUS_ACTIVE);

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
        BigDecimal gap = cv.subtract(cp);

        if (cp.compareTo(cv) < 0) {
            int count = hitCounters.merge(symbol, 1, Integer::sum);
            log.info("[{}][SCAN] Strike: {} | CP: {} | CV: {} | Gap: {} | Count: {}/{}", 
                     symbol, tick.getStrike(), cp, cv, gap, count, REQUIRED_CONSECUTIVE_HITS);

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
        BigDecimal ptsToCapture = TARGET_POINTS.getOrDefault(symbol.toUpperCase(), new BigDecimal("15"));
        BigDecimal targetGap = entryGap.add(ptsToCapture);

        log.info("🚀 [{}][ENTRY] Straddle Sold @ {} | Initial Gap: {} | Target Gap: {}", 
                 symbol, tick.getStrike(), entryGap, targetGap);

        saveOrder(symbol, "CE", tick.getCePrice(), tick.getStrike(), cycleId, entryGap);
        saveOrder(symbol, "PE", tick.getPePrice(), tick.getStrike(), cycleId, entryGap);
    }

    private void processExitSequence(String symbol, StraddleIntraday tick, List<Orders> activeOrders) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        BigDecimal currentGap = cv.subtract(cp);
        
        // Retrieve the entry gap (breakeven)
        BigDecimal initialGap = activeOrders.get(0).getBreakeven();
        if (initialGap == null) initialGap = currentGap;
        
        // Fetch dynamic target points (15 or 50)
        BigDecimal ptsToCapture = TARGET_POINTS.getOrDefault(symbol.toUpperCase(), new BigDecimal("15"));
        BigDecimal targetGap = initialGap.add(ptsToCapture);

        // RESTORED CLEAR LOG FORMAT
        log.info("[{}][LIVE] Strike: {} | CP: {} | CV: {} | Current Gap: {} | Target: {} | SL: CP > CV", 
                 symbol, tick.getStrike(), cp, cv, currentGap, targetGap);

        // EXIT Logic
        if (cp.compareTo(cv) > 0) {
            closeAll(activeOrders, tick, "STOP_LOSS (CP > CV)");
        } else if (currentGap.compareTo(targetGap) >= 0) {
            closeAll(activeOrders, tick, "TARGET_GAP_REACHED (" + ptsToCapture + " pts)");
        }
    }

    @Transactional
    protected void closeAll(List<Orders> orders, StraddleIntraday tick, String reason) {
        BigDecimal entryCP = orders.stream().map(Orders::getAskPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal exitCP = tick.getCombinedPremium();
        BigDecimal pnl = entryCP.subtract(exitCP);

        log.info("🏁 [{}][EXIT] Reason: {} | Total PnL: {}", tick.getName(), reason, pnl);

        for (Orders order : orders) {
            order.setActive(STATUS_INACTIVE); 
            order.setStatus(STATUS_CLOSED);
            order.setTradePhase(PHASE_EXIT);
            order.setClosedOn(LocalDateTime.now());
            BigDecimal exitPrice = "CE".equals(order.getOptionType()) ? tick.getCePrice() : tick.getPePrice();
            order.setExitPrice(exitPrice);
            order.setPl(order.getAskPrice().subtract(exitPrice));
            ordersRepository.save(order);
        }
    }

    private boolean isSquareOffTime(String symbol, LocalTime now) {
        if ("NIFTY".equalsIgnoreCase(symbol)) return !now.isBefore(NIFTY_SQUARE_OFF);
        if ("CRUDEOIL".equalsIgnoreCase(symbol)) return !now.isBefore(CRUDE_SQUARE_OFF);
        return false;
    }

    private void saveOrder(String symbol, String type, BigDecimal price, BigDecimal strike, String cycleId, BigDecimal gap) {
        Orders order = new Orders();
        String uniqueName = "SHORT_STRADDLE_" + symbol.toUpperCase();
        order.setName(uniqueName);
        order.setSignal(STRATEGY_NAME);
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
}