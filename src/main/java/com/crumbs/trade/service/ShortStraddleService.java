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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShortStraddleService {

    // ==========================================
    // STRATEGY CONFIGURATION
    // ==========================================
    private static final String STRATEGY_NAME = "SHORT_STRADDLE_VWAP";
    private static final int REQUIRED_CONSECUTIVE_HITS = 5;
    private static final BigDecimal POINTS_TO_CAPTURE = new BigDecimal("15");
    
    // Intraday Square-off Times
    private static final LocalTime NIFTY_SQUARE_OFF = LocalTime.of(15, 20);
    private static final LocalTime CRUDE_SQUARE_OFF = LocalTime.of(23, 20);
    
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_INACTIVE = 0;
    // ==========================================
    private static final String SIDE_SELL = "SELL";
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_CLOSED = "CLOSED"; // Added this
    private static final String PHASE_ENTRY = "ENTRY";
    private static final String PHASE_EXIT = "EXIT";     // Added this
    private final ShortStraddleRepository straddleRepository;
    private final OrderRepository ordersRepository;
    private final ConcurrentHashMap<String, Integer> hitCounters = new ConcurrentHashMap<>();

    public void evaluate(String symbol) {
        LocalTime now = LocalTime.now();
        List<Orders> activeOrders = ordersRepository.findByNameAndSignalAndActive(symbol, STRATEGY_NAME, STATUS_ACTIVE);

        // 1. Check for Forced EOD Square-off
        if (!activeOrders.isEmpty() && isSquareOffTime(symbol, now)) {
            straddleRepository.findLatestByName(symbol).ifPresent(tick -> {
                log.info("⏰ [EOD SQUARE-OFF] {} time reached. Closing position.", symbol);
                closeAll(activeOrders, tick, "INTRADAY_EOD_EXIT");
            });
            return; // Exit evaluation after square-off
        }

        // 2. Normal Entry/Exit Logic
        if (activeOrders.isEmpty()) {
            straddleRepository.findATMBySymbol(symbol).ifPresent(tick -> processEntrySequence(symbol, tick));
        } else {
            BigDecimal tradedStrike = activeOrders.get(0).getStrike();
            straddleRepository.findLatestBySymbolAndStrike(symbol, tradedStrike).ifPresent(tick -> {
                processExitSequence(symbol, tick, activeOrders);
            });
        }
    }

    /**
     * Helper to determine if the current market has reached its intraday limit.
     */
    private boolean isSquareOffTime(String symbol, LocalTime now) {
        if ("NIFTY".equalsIgnoreCase(symbol)) {
            return !now.isBefore(NIFTY_SQUARE_OFF);
        } else if ("CRUDEOIL".equalsIgnoreCase(symbol)) {
            return !now.isBefore(CRUDE_SQUARE_OFF);
        }
        return false;
    }

    private void processEntrySequence(String symbol, StraddleIntraday tick) {
        // ... (Same as previous: checks CP < CV for 5 hits) ...
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();

        if (cp.compareTo(cv) < 0) {
            int count = hitCounters.merge(symbol, 1, Integer::sum);
            if (count >= REQUIRED_CONSECUTIVE_HITS) {
                executeShortStraddle(symbol, tick);
                hitCounters.put(symbol, 0); 
            }
        } else {
            hitCounters.put(symbol, 0);
        }
    }

    @Transactional
    protected void executeShortStraddle(String symbol, StraddleIntraday tick) {
        String cycleId = UUID.randomUUID().toString();
        BigDecimal entryGap = tick.getCombinedVwap().subtract(tick.getCombinedPremium());
        
        log.info("🚀 [ENTRY] {} | Strike: {} | Gap: {}", symbol, tick.getStrike(), entryGap);

        saveOrder(symbol, "CE", tick.getCePrice(), tick.getStrike(), cycleId, entryGap);
        saveOrder(symbol, "PE", tick.getPePrice(), tick.getStrike(), cycleId, entryGap);
    }

    private void saveOrder(String symbol, String type, BigDecimal price, BigDecimal strike, String cycleId, BigDecimal entryGap) {
        Orders order = new Orders();
        order.setName(symbol);
        order.setSignal(STRATEGY_NAME);
        order.setCreatedOn(LocalDateTime.now());
        order.setOptionType(type);
        order.setSide("SELL");
        order.setAskPrice(price);
        order.setStrike(strike);
        order.setActive(STATUS_ACTIVE);
        order.setStatus("OPEN");
        order.setTradeCycleId(cycleId);
        order.setBreakeven(entryGap); 
        ordersRepository.save(order);
    }

    private void processExitSequence(String symbol, StraddleIntraday tick, List<Orders> activeOrders) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        
        Orders ref = activeOrders.get(0);
        BigDecimal initialGap = ref.getBreakeven() != null ? ref.getBreakeven() : cv.subtract(cp);
        BigDecimal currentGap = cv.subtract(cp);
        BigDecimal targetGap = initialGap.add(POINTS_TO_CAPTURE);

        if (cp.compareTo(cv) > 0) {
            closeAll(activeOrders, tick, "STOP_LOSS (CP > CV)");
        } else if (currentGap.compareTo(targetGap) >= 0) {
            closeAll(activeOrders, tick, "TARGET_GAP_REACHED");
        }
    }

    @Transactional
    protected void closeAll(List<Orders> orders, StraddleIntraday tick, String reason) {
        log.info("🏁 [CLOSING ALL] Symbol: {} | Reason: {}", tick.getName(), reason);
        for (Orders order : orders) {
            order.setActive(STATUS_INACTIVE); 
            order.setStatus(STATUS_CLOSED);    // Uses the constant above
            order.setTradePhase(PHASE_EXIT);    // Uses the constant above
            order.setClosedOn(LocalDateTime.now());
            
            BigDecimal exitPrice = "CE".equals(order.getOptionType()) ? tick.getCePrice() : tick.getPePrice();
            order.setExitPrice(exitPrice);
            
            if (order.getAskPrice() != null && exitPrice != null) {
                order.setPl(order.getAskPrice().subtract(exitPrice));
            }
            ordersRepository.save(order);
        }
    }
}