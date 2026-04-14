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
    
    // Square-off and Window Constants
    private static final LocalTime CRUDE_START = LocalTime.of(16, 0);
    private static final LocalTime NIFTY_SQUARE_OFF = LocalTime.of(15, 20);
    private static final LocalTime CRUDE_SQUARE_OFF = LocalTime.of(23, 20);

    // Status Constants
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

    // Volatile hit counters; Primary state remains in DB
    private final ConcurrentHashMap<String, Integer> hitCounters = new ConcurrentHashMap<>();

    /**
     * Main Scheduler Pulse (1-minute interval)
     */
    public void evaluate(String symbol) {
        LocalTime now = LocalTime.now();
        String symUpper = symbol.toUpperCase();
        String uniqueName = "SHORT_STRADDLE_" + symUpper;

        // 1. Fetch Config & Daily Stats (Source of Truth)
        Strategy strategy = strategyRepo.findByName(symUpper);
        if (strategy == null) return;

        LocalDateTime startOfDay = LocalDateTime.now().with(LocalTime.MIN);
        // 1. Fetch total legs from DB
        long totalLegs = ordersRepository.countLegsToday(uniqueName, STRATEGY_SIGNAL, startOfDay);

        // 2. Convert legs to Straddle units
        long straddlesUsed = totalLegs / 2; 

        // 3. Compare against DB-configured limit
        int maxAllowed = strategy.getMaxDailyTrades() > 0 ? strategy.getMaxDailyTrades() : 3;

        // 2. Scoreboard Pulse Log: Visible daily progress
        log.info("⏱️ [{}][EVAL] Pulse @ {} | Straddles Today: {}/{}", 
                symUpper, now.format(DateTimeFormatter.ofPattern("HH:mm:ss")), straddlesUsed, maxAllowed);

        if (symUpper.contains("CRUDE") && now.isBefore(CRUDE_START)) {
            log.info("⏳ [{}][WAIT] Window opens at 16:00.", symUpper);
            return;
        }

        // 3. Recovery: Check DB for active trades (resumes automatically after restart)
        List<Orders> activeOrders = ordersRepository.findByNameAndSignalAndActive(uniqueName, STRATEGY_SIGNAL, STATUS_ACTIVE);

        if (!activeOrders.isEmpty()) {
            // --- MONITORING MODE ---

            // Priority 1: Time-based Square-off
            if (isSquareOffTime(symUpper, now)) {
                log.info("🕒 [{}][EXIT] Square-off reached. Closing positions.", symUpper);
                straddleRepository.findLatestByName(symbol).ifPresent(tick -> 
                    closeAll(activeOrders, tick, "EOD_SQUARE_OFF", strategy));
                return;
            }

            // Priority 2: Target & SL Logic
            BigDecimal tradedStrike = activeOrders.get(0).getStrike();
            straddleRepository.findLatestBySymbolAndStrike(symbol, tradedStrike).ifPresentOrElse(
                tick -> processExitSequence(symUpper, tick, activeOrders, strategy),
                () -> log.error("❌ [{}][MONITOR] Missing price data for strike: {}", symUpper, tradedStrike)
            );
            
        } else {
            // --- SCANNING MODE ---

            // Gatekeeper: Daily Limit
            if (straddlesUsed >= maxAllowed) {
                log.info("🚫 [{}][LIMIT] Daily quota reached ({}/{}). Locked.", symUpper, straddlesUsed, maxAllowed);
                return;
            }

            log.info("📡 [{}][SCAN] Available: {} | Searching for ATM Entry...", symUpper, (maxAllowed - straddlesUsed));
            straddleRepository.findATMBySymbol(symbol).ifPresent(tick -> 
                processEntrySequence(symUpper, tick, strategy)
            );
        }
    }

    private void processEntrySequence(String symbol, StraddleIntraday tick, Strategy strategy) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        BigDecimal currentGap = cv.subtract(cp);
        BigDecimal maxAllowed = strategy.getMaxEntryRisk(); 

        int reqHits = strategy.getEntryHitsRequired() > 0 ? strategy.getEntryHitsRequired() : 3;

        log.info("📡 [{}][SCAN] Strike: {} | Gap: {} (Max: {})", 
                symbol, tick.getStrike(), currentGap.setScale(2, RoundingMode.HALF_UP), maxAllowed);

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
                log.info("⏩ [{}][SCAN] Gap too wide. Counter reset.", symbol);
            }
        } else {
            hitCounters.put(symbol + "_ENTRY", 0);
            log.info("⏳ [{}][SCAN] Waiting for Crossover (Distance: {} pts).", 
                    symbol, cp.subtract(cv).setScale(2, RoundingMode.HALF_UP));
        }
    }

    private void processExitSequence(String symbol, StraddleIntraday tick, List<Orders> activeOrders, Strategy strategy) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        BigDecimal currentGap = cv.subtract(cp);
        BigDecimal entryGap = activeOrders.get(0).getBreakeven();
        
        BigDecimal ptsToCapture = strategy.getTargetPoints();
        BigDecimal targetGap = entryGap.add(ptsToCapture);

        BigDecimal slPointLimit = strategy.getSlPoints();
        boolean isPointSlActive = slPointLimit != null && slPointLimit.compareTo(BigDecimal.ZERO) > 0;
        BigDecimal slGapThreshold = isPointSlActive ? entryGap.subtract(slPointLimit) : null;

        int reqSlHits = strategy.getExitHitsRequired() > 0 ? strategy.getExitHitsRequired() : 3;
        int currentSlHits = hitCounters.getOrDefault(symbol + "_EXIT", 0);

        BigDecimal distToTarget = targetGap.subtract(currentGap);
        String vwapTag = cp.compareTo(cv) >= 0 ? "🚨 CROSSOVER" : "✅ SAFE";
        String cushionSign = currentGap.signum() >= 0 ? "+" : "";

        log.info("📊 [{}][PROGRESS] Gap: {} | TARGET: {} ({} pts left) | VWAP-SL: {} ({}/{}) Cushion: {}{} pts", 
                symbol, currentGap.setScale(2, RoundingMode.HALF_UP), targetGap.setScale(2, RoundingMode.HALF_UP), 
                distToTarget.setScale(2, RoundingMode.HALF_UP), vwapTag, currentSlHits, reqSlHits, 
                cushionSign, currentGap.setScale(2, RoundingMode.HALF_UP));

        if (currentGap.compareTo(targetGap) >= 0) {
            log.info("💰 [{}][EXIT] Target Reached!", symbol);
            closeAll(activeOrders, tick, "TARGET_REACHED", strategy);
            hitCounters.put(symbol + "_EXIT", 0);
            return;
        }

        if (isPointSlActive && currentGap.compareTo(slGapThreshold) <= 0) {
            log.warn("🚨 [{}][EXIT] Fixed Point SL Triggered! Gap {} <= {}", symbol, currentGap, slGapThreshold);
            closeAll(activeOrders, tick, "FIXED_POINT_SL", strategy);
            hitCounters.put(symbol + "_EXIT", 0);
            return;
        }

        if (cp.compareTo(cv) >= 0) {
            int count = hitCounters.merge(symbol + "_EXIT", 1, Integer::sum);
            log.warn("🚨 [{}][SL_HIT] SL Crossover! Counter: {}/{}", symbol, count, reqSlHits);
            if (count >= reqSlHits) {
                closeAll(activeOrders, tick, "VWAP_CROSSOVER_SL", strategy);
                hitCounters.put(symbol + "_EXIT", 0);
            }
        } else {
            hitCounters.put(symbol + "_EXIT", 0);
        }
    }

    @Transactional
    protected void executeShortStraddle(String symbol, StraddleIntraday tick, BigDecimal entryGap, Strategy strategy) {
        log.info("🚀 [{}][EXECUTE] Opening positions for Strike: {}", symbol, tick.getStrike());
        String cycleId = UUID.randomUUID().toString();
        String uniqueName = "SHORT_STRADDLE_" + symbol.toUpperCase();

        BigDecimal targetValue = entryGap.add(strategy.getTargetPoints());

        processLeg(tick.getCeToken(), tick.getCeSymbol(), strategy, tick.getCePrice(), 
                   tick.getStrike(), uniqueName, "CE", cycleId, entryGap, targetValue, symbol);

        processLeg(tick.getPeToken(), tick.getPeSymbol(), strategy, tick.getPePrice(), 
                   tick.getStrike(), uniqueName, "PE", cycleId, entryGap, targetValue, symbol);

        telegramService.sendMessage(buildEntryMsg(symbol, tick.getStrike(), tick.getCePrice(), tick.getPePrice(), entryGap, strategy.getTargetPoints(), strategy));
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
            t.setQuantity(strategy.getQuantity());

            try {
                orderService.orderPlaceWithToken(t, uniqueName, "SELL", true);
            } catch (Exception | SmartAPIException e) {
                log.warn("⚠️ [{}][LEG] Broker failed for {}. DB record created as Paper.", name, type);
            }

            Orders order = ordersRepository.findByTokenAndActive(tokenStr, STATUS_ACTIVE);
            if (order == null) {
                order = new Orders();
                order.setToken(tokenStr);
                order.setSymbol(symbol);
                order.setActive(STATUS_ACTIVE);
                order.setName(uniqueName);
            }

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
            log.error("❌ [{}][LEG] Failure: {}", name, e.getMessage());
        }
    }

    @Transactional
    protected void closeAll(List<Orders> activeOrders, StraddleIntraday tick, String reason, Strategy strategy) {
        String symbol = tick.getName().toUpperCase();
        BigDecimal totalEntry = BigDecimal.ZERO;
        BigDecimal totalExit = BigDecimal.ZERO;
        boolean allSuccess = true;

        for (Orders order : activeOrders) {
            if (order.getActive() == STATUS_INACTIVE) continue;
            try {
                try {
                    orderService.exitActiveTradeByToken(order.getToken(), order.getName());
                    log.info("✅ [{}][EXIT] Broker confirmed for {}.", symbol, order.getOptionType());
                } catch (Exception | SmartAPIException e) {
                    allSuccess = false;
                    log.error("❌ [{}][EXIT] Broker failed for {}: {}. DB remains Active.", symbol, order.getOptionType(), e.getMessage());
                    continue; // Skip DB closure to allow retry
                }

                BigDecimal exitPrice = "CE".equals(order.getOptionType()) ? tick.getCePrice() : tick.getPePrice();
                totalEntry = totalEntry.add(order.getAskPrice() != null ? order.getAskPrice() : BigDecimal.ZERO);
                totalExit = totalExit.add(exitPrice);

                order.setExitPrice(exitPrice);
                order.setPl(order.getAskPrice() != null ? order.getAskPrice().subtract(exitPrice) : BigDecimal.ZERO);
                order.setClosedOn(LocalDateTime.now());
                order.setTradePhase(PHASE_EXIT);
                order.setStatus(STATUS_CLOSED);
                order.setActive(STATUS_INACTIVE); // Mark inactive ONLY if broker succeeded
                ordersRepository.save(order);
            } catch (Exception e) {
                log.error("❌ [{}][EXIT] Internal error finalized leg: {}", symbol, e.getMessage());
            }
        }
        
        if (allSuccess) {
            telegramService.sendMessage(buildExitMsg(symbol, tick.getStrike(), totalEntry, totalExit, reason));
        } else {
            telegramService.sendMessage("⚠️ **STRADDLE EXIT FAILED: " + symbol + "**\nBroker rejected the exit order. Bot will retry. Check margin/holdings.");
        }
    }

    private boolean isSquareOffTime(String symbol, LocalTime now) {
        if ("NIFTY".equalsIgnoreCase(symbol)) return !now.isBefore(NIFTY_SQUARE_OFF);
        if (symbol.contains("CRUDE")) return !now.isBefore(CRUDE_SQUARE_OFF);
        return false;
    }

    private String buildEntryMsg(String sym, BigDecimal strike, BigDecimal ce, BigDecimal pe, BigDecimal gap, BigDecimal tgt, Strategy strategy) {
        BigDecimal slPointLimit = strategy.getSlPoints();
        String slText = (slPointLimit != null && slPointLimit.compareTo(BigDecimal.ZERO) > 0) ? 
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