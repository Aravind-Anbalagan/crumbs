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
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShortStraddleService {

    private static final String STRATEGY_SIGNAL = "SHORT_STRADDLE_VWAP";
    
    // Default Fallbacks
    private static final BigDecimal DEFAULT_NIFTY_TARGET = new BigDecimal("25");
    private static final BigDecimal DEFAULT_CRUDE_TARGET = new BigDecimal("50");
    private static final BigDecimal DEFAULT_NIFTY_RISK = new BigDecimal("10");
    private static final BigDecimal DEFAULT_CRUDE_RISK = new BigDecimal("30");

    private static final LocalTime CRUDE_START = LocalTime.of(16, 0);
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

    // Independent thread-safe hit counters for [SYMBOL]_ENTRY and [SYMBOL]_EXIT
    private final ConcurrentHashMap<String, Integer> hitCounters = new ConcurrentHashMap<>();

    /**
     * Main scheduler entry point. Evaluates strategy logic once per minute.
     */
    public void evaluate(String symbol) {
        LocalTime now = LocalTime.now();
        String symUpper = symbol.toUpperCase();

        // 1. HEARTBEAT LOG: Confirmation that the scheduler pulse is alive
        log.info("⏱️ [{}][EVAL] Pulse @ {}", symUpper, now.format(DateTimeFormatter.ofPattern("HH:mm:ss")));

        if (symUpper.contains("CRUDE") && now.isBefore(CRUDE_START)) {
            log.info("⏳ [{}][WAIT] Trading window opens at 16:00.", symUpper);
            return;
        }

        Strategy strategy = strategyRepo.findByName(symUpper);
        if (strategy == null) {
            log.error("❌ [{}][CONFIG] Strategy configuration not found in DB.", symUpper);
            return;
        }

        String uniqueName = "SHORT_STRADDLE_" + symUpper;
        List<Orders> activeOrders = ordersRepository.findByNameAndSignalAndActive(uniqueName, STRATEGY_SIGNAL, STATUS_ACTIVE);

        if (!activeOrders.isEmpty()) {
            // MODE: MONITORING ACTIVE TRADE
            if (isSquareOffTime(symUpper, now)) {
                straddleRepository.findLatestByName(symbol).ifPresent(tick -> 
                    closeAll(activeOrders, tick, "EOD_SQUARE_OFF", strategy));
                return;
            }
            BigDecimal tradedStrike = activeOrders.get(0).getStrike();
            straddleRepository.findLatestBySymbolAndStrike(symbol, tradedStrike).ifPresentOrElse(
                tick -> processExitSequence(symUpper, tick, activeOrders, strategy),
                () -> log.error("❌ [{}][MONITOR] No price data found for active strike: {}", symUpper, tradedStrike)
            );
        } else {
            // MODE: SCANNING FOR ENTRY
            straddleRepository.findATMBySymbol(symbol).ifPresentOrElse(
                tick -> processEntrySequence(symUpper, tick, strategy),
                () -> log.warn("⚠️ [{}][SCAN] No ATM tick data found in DB.", symUpper)
            );
        }
    }

    private void processEntrySequence(String symbol, StraddleIntraday tick, Strategy strategy) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        BigDecimal currentGap = cv.subtract(cp);
        BigDecimal maxAllowed = strategy.getMaxEntryRisk() != null ? strategy.getMaxEntryRisk() : 
                               (symbol.contains("NIFTY") ? DEFAULT_NIFTY_RISK : DEFAULT_CRUDE_RISK);

        int reqHits = strategy.getEntryHitsRequired() > 0 ? strategy.getEntryHitsRequired() : 3;

        // RADAR LOG: Visibility into the scanning process
        log.info("📡 [{}][SCAN] Strike: {} | CP: {} | CV: {} | Gap: {} (Max: {})", 
                symbol, tick.getStrike(), cp, cv, currentGap.setScale(2, RoundingMode.HALF_UP), maxAllowed);

        if (cp.compareTo(cv) < 0) {
            if (currentGap.compareTo(maxAllowed) <= 0) {
                int count = hitCounters.merge(symbol + "_ENTRY", 1, Integer::sum);
                log.info("🎯 [{}][HIT] ENTRY CROSSOVER! Hits: {}/{}", symbol, count, reqHits);
                if (count >= reqHits) {
                    executeShortStraddle(symbol, tick, currentGap, strategy);
                    hitCounters.put(symbol + "_ENTRY", 0); 
                }
            } else {
                hitCounters.put(symbol + "_ENTRY", 0);
                log.info("⏩ [{}][SCAN] Gap too wide ({}). Counter reset.", symbol, currentGap);
            }
        } else {
            hitCounters.put(symbol + "_ENTRY", 0);
            log.info("⏳ [{}][SCAN] Premium still above VWAP (Cushion: {} pts).", 
                    symbol, cp.subtract(cv).setScale(2, RoundingMode.HALF_UP));
        }
    }

    private void processExitSequence(String symbol, StraddleIntraday tick, List<Orders> activeOrders, Strategy strategy) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        BigDecimal currentGap = cv.subtract(cp);
        BigDecimal entryGap = activeOrders.get(0).getBreakeven();
        
        // Target Threshold
        BigDecimal ptsToCapture = strategy.getTargetPoints() != null ? strategy.getTargetPoints() : 
                                 (symbol.contains("NIFTY") ? DEFAULT_NIFTY_TARGET : DEFAULT_CRUDE_TARGET);
        BigDecimal targetGap = (entryGap != null ? entryGap : BigDecimal.ZERO).add(ptsToCapture);

        // Fixed-Point SL Threshold (Zero/Null = Disabled)
        BigDecimal slPointLimit = strategy.getSlPoints() != null ? strategy.getSlPoints() : BigDecimal.ZERO;
        boolean isPointSlActive = slPointLimit.compareTo(BigDecimal.ZERO) > 0;
        BigDecimal slGapThreshold = isPointSlActive ? entryGap.subtract(slPointLimit) : null;

        // VWAP-SL Hit Setup
        int reqSlHits = strategy.getExitHitsRequired() > 0 ? strategy.getExitHitsRequired() : 3;
        int currentSlHits = hitCounters.getOrDefault(symbol + "_EXIT", 0);

        // Progress Calculations
        BigDecimal distToTarget = targetGap.subtract(currentGap);
        String vwapTag = cp.compareTo(cv) >= 0 ? "🚨 CROSSOVER" : "✅ SAFE";
        String pointSlText = isPointSlActive ? String.format("%.2f", slGapThreshold) : "DISABLED";
        String cushionSign = currentGap.signum() >= 0 ? "+" : "";

        // HIGH-VISIBILITY PROGRESS RADAR
        log.info("📊 [{}][PROGRESS] Gap: {} | TARGET: {} ({} pts left) | Fixed-SL: {} | VWAP-SL: {} ({}/{}) Cushion: {}{} pts", 
                symbol, currentGap.setScale(2, RoundingMode.HALF_UP), targetGap.setScale(2, RoundingMode.HALF_UP), 
                distToTarget.setScale(2, RoundingMode.HALF_UP), pointSlText, vwapTag, currentSlHits, reqSlHits, 
                cushionSign, currentGap.setScale(2, RoundingMode.HALF_UP));

        // 1. EXIT Logic: Target Reached (Immediate)
        if (currentGap.compareTo(targetGap) >= 0) {
            log.info("💰 [{}][EXIT] Target Reached! Closing for Profit.", symbol);
            closeAll(activeOrders, tick, "TARGET_REACHED", strategy);
            hitCounters.put(symbol + "_EXIT", 0);
            return;
        }

        // 2. EXIT Logic: Fixed-Point Stop Loss (Immediate)
        if (isPointSlActive && currentGap.compareTo(slGapThreshold) <= 0) {
            log.warn("🚨 [{}][EXIT] Fixed Point Stop Loss Triggered!", symbol);
            closeAll(activeOrders, tick, "FIXED_POINT_SL", strategy);
            hitCounters.put(symbol + "_EXIT", 0);
            return;
        }

        // 3. EXIT Logic: VWAP Crossover Stop Loss (Consecutive Hits)
        if (cp.compareTo(cv) >= 0) {
            int count = hitCounters.merge(symbol + "_EXIT", 1, Integer::sum);
            log.warn("🚨 [{}][SL_HIT] SL Crossover detected! Counter: {}/{}", symbol, count, reqSlHits);
            if (count >= reqSlHits) {
                log.info("🚨 [{}][EXIT] VWAP Stop Loss confirmed after {} hits.", symbol, count);
                closeAll(activeOrders, tick, "VWAP_CROSSOVER_SL", strategy);
                hitCounters.put(symbol + "_EXIT", 0);
            }
        } else {
            // Price is back in our favor, reset hits
            hitCounters.put(symbol + "_EXIT", 0);
        }
    }

    @Transactional
    protected void executeShortStraddle(String symbol, StraddleIntraday tick, BigDecimal entryGap, Strategy strategy) {
        log.info("🚀 [{}][EXECUTE] Initializing straddle entry for Strike: {}", symbol, tick.getStrike());
        String cycleId = UUID.randomUUID().toString();
        String uniqueName = "SHORT_STRADDLE_" + symbol.toUpperCase();

        BigDecimal ptsToCapture = strategy.getTargetPoints() != null ? strategy.getTargetPoints() : 
                                 (symbol.contains("NIFTY") ? DEFAULT_NIFTY_TARGET : DEFAULT_CRUDE_TARGET);
        BigDecimal targetValue = entryGap.add(ptsToCapture);

        processLeg(tick.getCeToken(), tick.getCeSymbol(), strategy, tick.getCePrice(), 
                   tick.getStrike(), uniqueName, "CE", cycleId, entryGap, targetValue, symbol);

        processLeg(tick.getPeToken(), tick.getPeSymbol(), strategy, tick.getPePrice(), 
                   tick.getStrike(), uniqueName, "PE", cycleId, entryGap, targetValue, symbol);

        telegramService.sendMessage(buildEntryMsg(symbol, tick.getStrike(), tick.getCePrice(), tick.getPePrice(), entryGap, ptsToCapture, strategy));
    }

    private void processLeg(String tokenStr, String symbol, Strategy strategy, BigDecimal price, 
                            BigDecimal strike, String uniqueName, String type, String cycleId, 
                            BigDecimal gap, BigDecimal targetValue, String name) {
        try {
            Token t = new Token();
            t.setToken(tokenStr);
            t.setSymbol(symbol);
            t.setExch_seg(strategy.getExchange());
            t.setStrike(strike);
            t.setName(name);
            
            int qty = strategy.getQuantity() > 0 ? strategy.getQuantity() : 1;
            t.setQuantity(qty);

            // 1. Trigger Generic Order Placement
            try {
                orderService.orderPlaceWithToken(t, uniqueName, "SELL", true);
            } catch (Exception | SmartAPIException e) {
                log.warn("⚠️ [{}][LEG] Broker execution failed for {}. Paper tracking active.", name, type);
            }

            // 2. Find-and-Enrich Pattern: Prevents Dual Entries
            Orders order = ordersRepository.findByTokenAndActive(tokenStr, STATUS_ACTIVE);
            if (order == null) {
                log.info("ℹ️ [{}][LEG] Creating manual tracking record for {}", name, type);
                order = new Orders();
                order.setToken(tokenStr);
                order.setSymbol(symbol);
                order.setActive(STATUS_ACTIVE);
                order.setName(uniqueName);
            }

            // Update Metadata for monitoring logic consistency
            order.setQuantity(t.getQuantity());
            order.setSignal(STRATEGY_SIGNAL);
            order.setOptionType(type);
            order.setTradeCycleId(cycleId);
            order.setBreakeven(gap); 
            order.setTarget(targetValue); 
            order.setAskPrice(price);
            order.setStrike(strike);
            order.setStatus(STATUS_OPEN);
            order.setTradePhase(PHASE_ENTRY);
            order.setCreatedOn(LocalDateTime.now());
            
            ordersRepository.save(order);
        } catch (Exception e) {
            log.error("❌ [{}][LEG] Critical internal failure: {}", name, e.getMessage());
        }
    }

    @Transactional
    protected void closeAll(List<Orders> activeOrders, StraddleIntraday tick, String reason, Strategy strategy) {
        String symbol = tick.getName().toUpperCase();
        BigDecimal totalEntry = BigDecimal.ZERO;
        BigDecimal totalExit = BigDecimal.ZERO;

        for (Orders order : activeOrders) {
            if (order.getActive() == STATUS_INACTIVE) continue;
            try {
                // 1. Trigger Generic Order Exit
                try {
                    orderService.exitActiveTradeByToken(order.getToken(), order.getName());
                } catch (Exception | SmartAPIException e) {
                    log.warn("⚠️ [{}][EXIT] Broker exit failed for {}. Finalizing DB as Paper.", symbol, order.getOptionType());
                }

                // 2. Calculate PnL and Mark Inactive
                BigDecimal exitPrice = "CE".equals(order.getOptionType()) ? tick.getCePrice() : tick.getPePrice();
                totalEntry = totalEntry.add(order.getAskPrice() != null ? order.getAskPrice() : BigDecimal.ZERO);
                totalExit = totalExit.add(exitPrice);

                order.setExitPrice(exitPrice);
                order.setPl(order.getAskPrice() != null ? order.getAskPrice().subtract(exitPrice) : BigDecimal.ZERO);
                order.setClosedOn(LocalDateTime.now());
                order.setTradePhase(PHASE_EXIT);
                order.setStatus(STATUS_CLOSED);
                order.setActive(STATUS_INACTIVE); // Stops evaluate() from tracking this trade
                
                ordersRepository.save(order);
            } catch (Exception e) {
                log.error("❌ [{}][EXIT] Finalization error: {}", symbol, e.getMessage());
            }
        }
        telegramService.sendMessage(buildExitMsg(symbol, tick.getStrike(), totalEntry, totalExit, reason));
    }

    private boolean isSquareOffTime(String symbol, LocalTime now) {
        if ("NIFTY".equalsIgnoreCase(symbol)) return !now.isBefore(NIFTY_SQUARE_OFF);
        if (symbol.contains("CRUDE")) return !now.isBefore(CRUDE_SQUARE_OFF);
        return false;
    }

    private String buildEntryMsg(String sym, BigDecimal strike, BigDecimal ce, BigDecimal pe, BigDecimal gap, BigDecimal tgt, Strategy strategy) {
        BigDecimal slPointLimit = strategy.getSlPoints() != null ? strategy.getSlPoints() : BigDecimal.ZERO;
        String slText = (slPointLimit.compareTo(BigDecimal.ZERO) > 0) ? 
                String.format("%.2f (Fixed) | %d Hits (VWAP)", gap.subtract(slPointLimit), strategy.getExitHitsRequired()) :
                String.format("%d Hits (VWAP Crossover)", strategy.getExitHitsRequired());

        return String.format("""
            🚀 **STRADDLE ENTRY: %s**
            **Strike** : %s
            **Gap** : %.2f
            **Target** : +%.2f pts
            **SL** : %s""", 
            sym, strike, gap, tgt, slText);
    }

    private String buildExitMsg(String sym, BigDecimal strike, BigDecimal ent, BigDecimal ex, String reason) {
        BigDecimal pnl = ent.subtract(ex);
        return String.format("%s **STRADDLE EXIT: %s**\nReason: %s\nPnL: **%.2f pts**", 
                pnl.signum() >= 0 ? "✅" : "❌", sym, reason, pnl);
    }
}