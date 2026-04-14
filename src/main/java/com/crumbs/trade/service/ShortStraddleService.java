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
    private static final int REQUIRED_CONSECUTIVE_HITS = 3;

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

    private final ConcurrentHashMap<String, Integer> hitCounters = new ConcurrentHashMap<>();

    /**
     * Main Entry Point - Runs every minute via Scheduler
     */
    public void evaluate(String symbol) {
        LocalTime now = LocalTime.now();
        String symUpper = symbol.toUpperCase();

        // HEARTBEAT LOG: Confirmation that the scheduler is alive and running
        log.info("⏱️ [{}][EVAL] Scheduler pulse at {}. Checking state...", symUpper, now.format(DateTimeFormatter.ofPattern("HH:mm:ss")));

        if (symUpper.contains("CRUDE") && now.isBefore(CRUDE_START)) {
            log.info("⏳ [{}][WAIT] Outside trading window. CRUDE starts at 16:00.", symUpper);
            return;
        }

        Strategy strategy = strategyRepo.findByName(symUpper);
        if (strategy == null) {
            log.error("❌ [{}][CONFIG] Strategy record not found in DB!", symUpper);
            return;
        }

        String uniqueName = "SHORT_STRADDLE_" + symUpper;
        List<Orders> activeOrders = ordersRepository.findByNameAndSignalAndActive(uniqueName, STRATEGY_SIGNAL, STATUS_ACTIVE);

        if (!activeOrders.isEmpty()) {
            // MODE LOG: Monitoring
            log.info("🔍 [{}][MODE] Active trade detected. Monitoring for Exit.", symUpper);
            
            if (isSquareOffTime(symUpper, now)) {
                straddleRepository.findLatestByName(symbol).ifPresent(tick -> 
                    closeAll(activeOrders, tick, "EOD_SQUARE_OFF", strategy));
                return;
            }

            BigDecimal tradedStrike = activeOrders.get(0).getStrike();
            straddleRepository.findLatestBySymbolAndStrike(symbol, tradedStrike).ifPresentOrElse(
                tick -> processExitSequence(symUpper, tick, activeOrders, strategy),
                () -> log.error("❌ [{}][MONITOR] Price data missing for active strike: {}", symUpper, tradedStrike)
            );
        } else {
            // MODE LOG: Scanning
            log.info("📡 [{}][MODE] No active trade. Scanning for Entry conditions...", symUpper);
            
            straddleRepository.findATMBySymbol(symbol).ifPresentOrElse(
                tick -> processEntrySequence(symUpper, tick, strategy),
                () -> log.warn("⚠️ [{}][SCAN] No ATM data found in DB. Check your data feeder!", symUpper)
            );
        }
    }

    private void processEntrySequence(String symbol, StraddleIntraday tick, Strategy strategy) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        BigDecimal currentGap = cv.subtract(cp);
        
        BigDecimal maxAllowed = strategy.getMaxEntryRisk() != null ? strategy.getMaxEntryRisk() : 
                               (symbol.contains("NIFTY") ? DEFAULT_NIFTY_RISK : DEFAULT_CRUDE_RISK);

        // DATA LOG: Every minute visibility of CP, CV, and the Gap
        log.info("📊 [{}][SCAN] Strike: {} | Premium: {} | VWAP: {} | Gap: {} | Limit: {}", 
                symbol, tick.getStrike(), 
                cp.setScale(2, RoundingMode.HALF_UP), 
                cv.setScale(2, RoundingMode.HALF_UP),
                currentGap.setScale(2, RoundingMode.HALF_UP),
                maxAllowed);

        if (cp.compareTo(cv) < 0) {
            if (currentGap.compareTo(maxAllowed) <= 0) {
                int count = hitCounters.merge(symbol, 1, Integer::sum);
                log.info("🎯 [{}][HIT] VALID CROSSOVER! Consecutive Hit: {}/{}", symbol, count, REQUIRED_CONSECUTIVE_HITS);

                if (count >= REQUIRED_CONSECUTIVE_HITS) {
                    executeShortStraddle(symbol, tick, currentGap, strategy);
                    hitCounters.put(symbol, 0); 
                }
            } else {
                hitCounters.put(symbol, 0);
                log.info("⏩ [{}][SCAN] Crossover active but Gap is too wide (Risk Cap). Counter Reset.", symbol);
            }
        } else {
            hitCounters.put(symbol, 0);
            log.info("⏳ [{}][SCAN] Premium still above VWAP. Waiting for Crossover...", symbol);
        }
    }

    private void processExitSequence(String symbol, StraddleIntraday tick, List<Orders> activeOrders, Strategy strategy) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        BigDecimal currentGap = cv.subtract(cp);
        BigDecimal entryGap = activeOrders.get(0).getBreakeven();
        
        BigDecimal ptsToCapture = strategy.getTargetPoints() != null ? strategy.getTargetPoints() : 
                                 (symbol.contains("NIFTY") ? DEFAULT_NIFTY_TARGET : DEFAULT_CRUDE_TARGET);
        
        BigDecimal targetGap = (entryGap != null ? entryGap : BigDecimal.ZERO).add(ptsToCapture);

        log.info("📊 [{}][MONITOR] Strike: {} | CP: {} | CV: {} | Gap: {} | Target: {}",
                symbol, tick.getStrike(), cp.setScale(2, RoundingMode.HALF_UP), cv.setScale(2, RoundingMode.HALF_UP),
                currentGap.setScale(2, RoundingMode.HALF_UP), targetGap.setScale(2, RoundingMode.HALF_UP));

        if (cp.compareTo(cv) >= 0) {
            log.info("🚨 [{}][EXIT] Stop Loss Triggered! CP ({}) crossed back above CV ({}).", symbol, cp, cv);
            closeAll(activeOrders, tick, "STOP_LOSS_VWAP_CROSS", strategy);
        } else if (currentGap.compareTo(targetGap) >= 0) {
            log.info("💰 [{}][EXIT] Target Reached! Current Gap ({}) >= Target Gap ({}).", symbol, currentGap, targetGap);
            closeAll(activeOrders, tick, "TARGET_REACHED", strategy);
        }
    }

    @Transactional
    protected void executeShortStraddle(String symbol, StraddleIntraday tick, BigDecimal entryGap, Strategy strategy) {
        log.info("🚀 [{}][EXECUTE] Initializing trade cycle for Strike: {}", symbol, tick.getStrike());
        String cycleId = UUID.randomUUID().toString();
        String uniqueName = "SHORT_STRADDLE_" + symbol.toUpperCase();

        BigDecimal ptsToCapture = strategy.getTargetPoints() != null ? strategy.getTargetPoints() : 
                                 (symbol.contains("NIFTY") ? DEFAULT_NIFTY_TARGET : DEFAULT_CRUDE_TARGET);
        BigDecimal targetValue = entryGap.add(ptsToCapture);

        processLeg(tick.getCeToken(), tick.getCeSymbol(), strategy, tick.getCePrice(), 
                   tick.getStrike(), uniqueName, "CE", cycleId, entryGap, targetValue, symbol);

        processLeg(tick.getPeToken(), tick.getPeSymbol(), strategy, tick.getPePrice(), 
                   tick.getStrike(), uniqueName, "PE", cycleId, entryGap, targetValue, symbol);

        telegramService.sendMessage(buildEntryMsg(symbol, tick.getStrike(), tick.getCePrice(), tick.getPePrice(), entryGap, ptsToCapture));
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

            // 1. Broker Execution attempt (Via Generic Service)
            try {
                orderService.orderPlaceWithToken(t, uniqueName, "SELL", true);
            } catch (Exception | SmartAPIException e) {
                log.warn("⚠️ [{}][LEG] Broker execution failed for {}. Continuing for Paper Tracking.", name, type);
            }

            // 2. Find-and-Update Enrichment Logic
            Orders order = ordersRepository.findByTokenAndActive(tokenStr, STATUS_ACTIVE);
            
            if (order == null) {
                log.info("ℹ️ [{}][LEG] Creating manual record for tracking: {}", name, type);
                order = new Orders();
                order.setToken(tokenStr);
                order.setSymbol(symbol);
                order.setActive(STATUS_ACTIVE);
                order.setName(uniqueName);
            }

            // Update Metadata - ensures correct columns are filled even if Generic service missed them
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
                // 1. Broker Exit attempt
                try {
                    orderService.exitActiveTradeByToken(order.getToken(), order.getName());
                } catch (Exception | SmartAPIException e) {
                    log.warn("⚠️ [{}][EXIT] Broker exit failed for {}. Closing DB record anyway.", symbol, order.getOptionType());
                }

                // 2. Finalize Record
                BigDecimal exitPrice = "CE".equals(order.getOptionType()) ? tick.getCePrice() : tick.getPePrice();
                totalEntry = totalEntry.add(order.getAskPrice() != null ? order.getAskPrice() : BigDecimal.ZERO);
                totalExit = totalExit.add(exitPrice);

                order.setExitPrice(exitPrice);
                order.setPl(order.getAskPrice() != null ? order.getAskPrice().subtract(exitPrice) : BigDecimal.ZERO);
                order.setClosedOn(LocalDateTime.now());
                order.setTradePhase(PHASE_EXIT);
                order.setStatus(STATUS_CLOSED);
                order.setActive(STATUS_INACTIVE); // End tracking
                
                ordersRepository.save(order);

            } catch (Exception e) {
                log.error("❌ [{}][EXIT] Error closing order record: {}", symbol, e.getMessage());
            }
        }
        telegramService.sendMessage(buildExitMsg(symbol, tick.getStrike(), totalEntry, totalExit, reason));
    }

    private boolean isSquareOffTime(String symbol, LocalTime now) {
        if ("NIFTY".equalsIgnoreCase(symbol)) return !now.isBefore(NIFTY_SQUARE_OFF);
        if (symbol.contains("CRUDE")) return !now.isBefore(CRUDE_SQUARE_OFF);
        return false;
    }

    private String buildEntryMsg(String sym, BigDecimal strike, BigDecimal ce, BigDecimal pe, BigDecimal gap, BigDecimal tgt) {
        String t = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        return String.format("""
            🚀 **STRADDLE ENTRY: %s**
            📌 **Strike** : %s
            💰 **CE / PE** : %.2f | %.2f
            📊 **Combined** : **%.2f**
            📉 **Gap to VWAP**: %.2f
            🎯 **Target** : +%.2f Points
            ⏰ **Time** : %s
            🟢 *Monitoring for VWAP crossover SL...*
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
}