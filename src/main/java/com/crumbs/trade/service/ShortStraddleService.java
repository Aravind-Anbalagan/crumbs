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
    
    private static final LocalTime NIFTY_SQUARE_OFF = LocalTime.of(15, 20);
    private static final LocalTime CRUDE_SQUARE_OFF = LocalTime.of(23, 20);
    
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_INACTIVE = 0;
    private static final String SIDE_SELL = "SELL";
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
        List<Orders> activeOrders = ordersRepository.findByNameAndSignalAndActive(symbol, STRATEGY_NAME, STATUS_ACTIVE);

        // 1. EOD Square-off Check
        if (!activeOrders.isEmpty() && isSquareOffTime(symbol, now)) {
            straddleRepository.findLatestByName(symbol).ifPresent(tick -> {
                log.info("⏰ [{}][EOD] Square-off time reached ({}). Closing position.", symbol, now);
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
            if (hitCounters.getOrDefault(symbol, 0) > 0) {
                log.debug("[{}][SCAN] Condition broken (CP > CV). Resetting counter.", symbol);
                hitCounters.put(symbol, 0);
            }
        }
    }

    @Transactional
    protected void executeShortStraddle(String symbol, StraddleIntraday tick, BigDecimal entryGap) {
        String cycleId = UUID.randomUUID().toString();
        BigDecimal targetGap = entryGap.add(POINTS_TO_CAPTURE);

        log.info("🚀 [{}][ENTRY] Straddle Sold @ {} | CP: {} | Initial Gap: {} | Target Gap: {}", 
                 symbol, tick.getStrike(), tick.getCombinedPremium(), entryGap, targetGap);

        saveOrder(symbol, "CE", tick.getCePrice(), tick.getStrike(), cycleId, entryGap);
        saveOrder(symbol, "PE", tick.getPePrice(), tick.getStrike(), cycleId, entryGap);
    }

    private void processExitSequence(String symbol, StraddleIntraday tick, List<Orders> activeOrders) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        BigDecimal currentGap = cv.subtract(cp);
        
        BigDecimal initialGap = activeOrders.get(0).getBreakeven();
        if (initialGap == null) initialGap = currentGap; // Fallback for legacy
        
        BigDecimal targetGap = initialGap.add(POINTS_TO_CAPTURE);

        log.info("[{}][LIVE] Strike: {} | CP: {} | CV: {} | Current Gap: {} | Target: {} | SL: CP > CV", 
                 symbol, tick.getStrike(), cp, cv, currentGap, targetGap);

        if (cp.compareTo(cv) > 0) {
            closeAll(activeOrders, tick, "STOP_LOSS (CP > CV)");
        } else if (currentGap.compareTo(targetGap) >= 0) {
            closeAll(activeOrders, tick, "TARGET_GAP_REACHED");
        }
    }

    @Transactional
    protected void closeAll(List<Orders> orders, StraddleIntraday tick, String reason) {
        BigDecimal entryCP = orders.stream().map(Orders::getAskPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal exitCP = tick.getCombinedPremium();
        BigDecimal pnlPoints = entryCP.subtract(exitCP);

        log.info("🏁 [{}][EXIT] Reason: {} | Entry CP: {} | Exit CP: {} | Total PnL: {}", 
                 tick.getName(), reason, entryCP, exitCP, pnlPoints);

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
        order.setName(symbol);
        order.setSymbol(symbol);
        order.setSignal(STRATEGY_NAME);
        order.setCreatedOn(LocalDateTime.now());
        order.setOptionType(type);
        order.setSide(SIDE_SELL);
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