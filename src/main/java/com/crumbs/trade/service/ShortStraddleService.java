package com.crumbs.trade.service;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.smartstream.models.ExchangeType;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShortStraddleService {

    private static final String STRATEGY_SIGNAL = "SHORT_STRADDLE";
    private static final String NAME_PREFIX = "SHORT_STRADDLE_";

    // --- Rule 1: Strict Timeframes ---
    private static final LocalTime NIFTY_START = LocalTime.of(9, 20);
    private static final LocalTime NIFTY_ENTRY_CUTOFF = LocalTime.of(15, 0);
    private static final LocalTime NIFTY_SQUARE_OFF = LocalTime.of(15, 20);

    private static final LocalTime SENSEX_START = LocalTime.of(9, 20);
    private static final LocalTime SENSEX_ENTRY_CUTOFF = LocalTime.of(15, 0);
    private static final LocalTime SENSEX_SQUARE_OFF = LocalTime.of(15, 20);

    private static final LocalTime CRUDE_START = LocalTime.of(16, 0);
    private static final LocalTime CRUDE_ENTRY_CUTOFF = LocalTime.of(23, 0);
    private static final LocalTime CRUDE_SQUARE_OFF = LocalTime.of(23, 20);

    private static final LocalTime NATURALGAS_START = LocalTime.of(16, 0);
    private static final LocalTime NATURALGAS_ENTRY_CUTOFF = LocalTime.of(23, 0);
    private static final LocalTime NATURALGAS_SQUARE_OFF = LocalTime.of(23, 20);

    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_INACTIVE = 0;
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String PHASE_ENTRY = "ENTRY";
    private static final String PHASE_EXIT = "EXIT";

    // Preserves the original ~1-minute confirmation cadence for entry/exit hit-counters,
    // even though the scheduler tick itself now runs every ~1s to feed the risk layer.
    private static final long HIT_DEBOUNCE_MS = 60_000;

    private final ShortStraddleRepository straddleRepository;
    private final OrderRepository ordersRepository;
    private final StrategyRepo strategyRepo;
    private final OrderService orderService;
    private final TelegramService telegramService;
    private final AngelWebSocketService angelWebSocketService;
    private final MonitorOrderService monitorOrderService; // ✅ EXITS FUNNELED HERE

    private final ConcurrentHashMap<String, Integer> hitCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BigDecimal> lastSeenStrikes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastHitTimestamps = new ConcurrentHashMap<>();

    public void evaluate(String symbol) {
        LocalTime now = LocalTime.now();

        String baseSymbol = symbol.toUpperCase().replace(NAME_PREFIX, "");
        String tradeName = NAME_PREFIX + baseSymbol;

        Strategy strategyConfig = strategyRepo.findByName(STRATEGY_SIGNAL);
        Strategy sourceConfig = strategyRepo.findByName(baseSymbol);

        if (strategyConfig == null || sourceConfig == null) {
            log.error("❌ DB Config Missing! Strategy Present: {}, Source Present: {}",
                    strategyConfig != null, sourceConfig != null);
            return;
        }

        LocalDateTime startOfDay = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata"))
                .toLocalDate()
                .atStartOfDay();
        long totalLegs = ordersRepository.countLegsToday(tradeName, STRATEGY_SIGNAL, startOfDay);
        long straddlesUsed = totalLegs / 2;
        int maxAllowed = strategyConfig.getMaxDailyTrades() > 0 ? strategyConfig.getMaxDailyTrades() : 3;

        if ("NIFTY".equalsIgnoreCase(baseSymbol) && now.isBefore(NIFTY_START)) return;
        if ("SENSEX".equalsIgnoreCase(baseSymbol) && now.isBefore(SENSEX_START)) return;
        if (tradeName.contains("CRUDE") && now.isBefore(CRUDE_START)) return;
        if (tradeName.contains("NATURALGAS") && now.isBefore(NATURALGAS_START)) return;

        List<Orders> allActiveOrders = ordersRepository.findByNameAndSignalAndActive(tradeName, STRATEGY_SIGNAL, STATUS_ACTIVE);

        if (!allActiveOrders.isEmpty()) {

            // Group by Cycle ID to isolate concurrent straddles internally
            Map<String, List<Orders>> ordersByCycle = allActiveOrders.stream()
                    .collect(Collectors.groupingBy(o -> o.getTradeCycleId() != null ? o.getTradeCycleId() : "LEGACY_" + o.getId()));

            for (Map.Entry<String, List<Orders>> entry : ordersByCycle.entrySet()) {
                String cycleId = entry.getKey();
                List<Orders> cycleOrders = entry.getValue();
                BigDecimal tradedStrike = cycleOrders.get(0).getStrike();

                if (cycleOrders.size() != 2) {
                    log.error("🚨 [{}][SAFETY] Imbalance in Cycle {}! Found {} legs. Cleaning up.", tradeName, cycleId, cycleOrders.size());
                    monitorOrderService.forceExit(cycleOrders, tradeName, "ORPHAN_LEG_CLEANUP");
                    continue;
                }

                // 🛡️ RISK LAYER FIRST: hard max-loss/target, velocity panic drop, HWM/milestone/trailing.
                if (monitorOrderService.evaluateAndClose(cycleOrders, tradeName, null)) {
                    log.info("🛡️ [{}][RISK] Cycle {} closed by MonitorOrderService.", tradeName, cycleId);
                    hitCounters.remove(tradeName + "_" + cycleId + "_EXIT");
                    lastHitTimestamps.remove(tradeName + "_" + cycleId + "_EXIT");
                    continue;
                }

                // 🕒 TIME SQUARE OFF -> Safely forces exit via Master Engine!
                if (isSquareOffTime(baseSymbol, now)) {
                    log.info("🕒 [{}][EXIT] Square-off time reached for Cycle {}.", tradeName, cycleId);
                    monitorOrderService.forceExit(cycleOrders, tradeName, "EOD_SQUARE_OFF");
                    continue;
                }

                straddleRepository.findLatestBySymbolAndStrike(baseSymbol, tradedStrike).ifPresentOrElse(
                        tick -> processExitSequence(tradeName, tick, cycleOrders, strategyConfig, sourceConfig, cycleId),
                        () -> log.error("❌ [{}][MONITOR] Missing price data for strike: {}", tradeName, tradedStrike)
                );
            }

        } else {
            if (straddlesUsed >= maxAllowed) {
                log.info("🛑 [{}] Max daily trades reached ({}/{}). Stopping scans.", tradeName, straddlesUsed, maxAllowed);
                return;
            }

            if (!isWithinEntryWindow(baseSymbol, now)) {
                log.debug("⏳ [{}] Entry window closed for today. Waiting for EOD.", tradeName);
                return;
            }

            if ("NIFTY".equalsIgnoreCase(baseSymbol)) {
                getNiftyIndexAtm(tradeName).ifPresent(atmStrike ->
                        straddleRepository.findLatestBySymbolAndStrike(baseSymbol, atmStrike).ifPresent(tick ->
                                processEntrySequence(tradeName, tick, strategyConfig, sourceConfig)
                        )
                );
            } else if ("SENSEX".equalsIgnoreCase(baseSymbol)) {
                getSensexIndexAtm(tradeName).ifPresent(atmStrike ->
                        straddleRepository.findLatestBySymbolAndStrike(baseSymbol, atmStrike).ifPresent(tick ->
                                processEntrySequence(tradeName, tick, strategyConfig, sourceConfig)
                        )
                );
            } else {
                straddleRepository.findATMBySymbol(baseSymbol).ifPresent(tick ->
                        processEntrySequence(tradeName, tick, strategyConfig, sourceConfig)
                );
            }
        }
    }

    private boolean canRegisterHit(String key) {
        long now = System.currentTimeMillis();
        Long last = lastHitTimestamps.get(key);
        if (last == null || now - last >= HIT_DEBOUNCE_MS) {
            lastHitTimestamps.put(key, now);
            return true;
        }
        return false;
    }

    private void processEntrySequence(String tradeName, StraddleIntraday tick, Strategy strategyConfig, Strategy sourceConfig) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        BigDecimal currentGap = cv.subtract(cp);
        BigDecimal currentStrike = tick.getStrike();

        BigDecimal safeDistance = sourceConfig.getMaxEntryRisk() != null ? sourceConfig.getMaxEntryRisk() : BigDecimal.ZERO;
        int reqHits = sourceConfig.getEntryHitsRequired() > 0 ? sourceConfig.getEntryHitsRequired() : 3;

        boolean isCpBelowCv = cp.compareTo(cv) < 0;
        boolean isGapAcceptable = currentGap.compareTo(safeDistance) <= 0;

        log.info("🔍 [{}] SCANNING | Strike: {} | CP: {} | CV: {} | Gap: {}",
                tradeName, currentStrike, cp, cv, currentGap.setScale(2, RoundingMode.HALF_UP));

        if (isCpBelowCv && isGapAcceptable) {
            String strikeKey = tradeName + "_STRIKE";
            String entryKey = tradeName + "_ENTRY";
            BigDecimal lastStrike = lastSeenStrikes.get(strikeKey);

            if (lastStrike != null && lastStrike.compareTo(currentStrike) != 0) {
                log.warn("🔄 [{}] STRIKE MOVED: {} -> {}. Resetting hits to 1.", tradeName, lastStrike, currentStrike);
                hitCounters.put(entryKey, 1);
                lastHitTimestamps.put(entryKey, System.currentTimeMillis());
            } else if (canRegisterHit(entryKey)) {
                hitCounters.merge(entryKey, 1, Integer::sum);
            }

            lastSeenStrikes.put(strikeKey, currentStrike);
            int count = hitCounters.getOrDefault(entryKey, 0);

            log.info("🎯 [{}] ENTRY HIT TRACKER: ({}/{}) on Strike: {}", tradeName, count, reqHits, currentStrike);

            if (count >= reqHits) {
                log.info("⚡ [{}] ALL HITS MET! Triggering execution at {}", tradeName, currentStrike);
                executeShortStraddle(tradeName, tick, currentGap, strategyConfig, sourceConfig);

                hitCounters.put(entryKey, 0);
                lastSeenStrikes.remove(strikeKey);
                lastHitTimestamps.remove(entryKey);
            }
        } else {
            String entryKey = tradeName + "_ENTRY";
            if (hitCounters.getOrDefault(entryKey, 0) > 0) {
                log.info("🔄 [{}] CONDITIONS LOST. Resetting hit counter.", tradeName);
            }
            hitCounters.put(entryKey, 0);
            lastSeenStrikes.remove(tradeName + "_STRIKE");
            lastHitTimestamps.remove(entryKey);
        }
    }

    private void processExitSequence(String tradeName, StraddleIntraday tick, List<Orders> activeOrders, Strategy strategyConfig, Strategy sourceConfig, String cycleId) {
        String exitKey = tradeName + "_" + cycleId + "_EXIT";

        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        BigDecimal currentGap = cv.subtract(cp);
        BigDecimal entryGap = activeOrders.get(0).getBreakeven();

        if (entryGap == null) {
            log.error("❌ [{}][EXIT] Missing entryGap (breakeven) for Cycle {}! Forcing safety exit.", tradeName, cycleId);
            monitorOrderService.forceExit(activeOrders, tradeName, "MISSING_ENTRY_GAP_SAFETY_EXIT");
            return;
        }
        BigDecimal targetPoints = sourceConfig.getTargetPoints() != null
                ? sourceConfig.getTargetPoints()
                : BigDecimal.ZERO;
        BigDecimal targetGap = entryGap.add(targetPoints);
        BigDecimal distToTarget = targetGap.subtract(currentGap);

        BigDecimal slPoints = sourceConfig.getSlPoints();
        boolean isPointSlConfigured = slPoints != null && slPoints.compareTo(BigDecimal.ZERO) > 0;

        // P&L-based SL: loss measured from entryGap, not live CP-CV
        BigDecimal pnlLoss = entryGap.subtract(currentGap); // positive = losing
        boolean isPointSlBreached = isPointSlConfigured && (pnlLoss.compareTo(slPoints) >= 0);

        boolean isVwapCrossover = cp.compareTo(cv) > 0;
        int reqSlHits = sourceConfig.getExitHitsRequired() > 0 ? sourceConfig.getExitHitsRequired() : 3;

        if (isVwapCrossover) {
            if (canRegisterHit(exitKey)) {
                hitCounters.merge(exitKey, 1, Integer::sum);
            }
        } else {
            hitCounters.put(exitKey, 0);
            lastHitTimestamps.remove(exitKey);
        }

        int currentSlHits = hitCounters.getOrDefault(exitKey, 0);
        boolean isHitsMet = currentSlHits >= reqSlHits;

        BigDecimal tradedStrike = activeOrders.get(0).getStrike();
        String tradeStatus = currentGap.compareTo(entryGap) >= 0 ? "🟢 PROFIT" : "🔴 LOSS";
        String cushionSign = currentGap.compareTo(entryGap) >= 0 ? "+" : "";
        BigDecimal currentPnL = currentGap.subtract(entryGap);

        String vStatus = isVwapCrossover ? "⚠️ CROSSOVER (" + currentSlHits + "/" + reqSlHits + ")" : "✅ STABLE";

        String defenseMode = isPointSlConfigured
                ? "🛡️ [MODE: MASTER PRICE SL (Trend hits tracked but not required)]"
                : "🛡️ [MODE: SINGLE SHIELD (Trend-Only Fallback)]";

        String pStatus;
        if (!isPointSlConfigured) {
            pStatus = "⚪ DISABLED";
        } else {
            pStatus = isPointSlBreached
                    ? String.format("🚨 BREACHED (Loss %.2f >= SL %.2f | EntryGap %.2f, CurGap %.2f)", pnlLoss, slPoints, entryGap, currentGap)
                    : String.format("✅ SECURE (Loss %.2f < SL %.2f | EntryGap %.2f, CurGap %.2f)", pnlLoss, slPoints, entryGap, currentGap);
        }

        log.info("================================================================================");
        log.info("📊 [{}] Cycle: {} | Strike: {} | Status: {} | Floating PnL: {}{} pts", tradeName, cycleId, tradedStrike, tradeStatus, cushionSign, currentPnL.setScale(2, RoundingMode.HALF_UP));
        log.info("🎯 GOAL: {} pts gap | Distance: {} pts to go", targetGap.setScale(2, RoundingMode.HALF_UP), distToTarget.setScale(2, RoundingMode.HALF_UP));
        log.info("{}", defenseMode);
        log.info("🛡️ SHIELD STATUS -> [Trend: {}] && [Price: {}]", vStatus, pStatus);
        log.info("================================================================================");

        if (currentGap.compareTo(targetGap) >= 0) {
            log.info("💰 [{}][EXIT] TARGET REACHED!", tradeName);
            monitorOrderService.forceExit(activeOrders, tradeName, "TARGET_REACHED");
            hitCounters.remove(exitKey);
            lastHitTimestamps.remove(exitKey);
            return;
        }

        boolean shouldExit = false;
        String reason = "";

        if (isPointSlConfigured) {
            if (isPointSlBreached) {
                shouldExit = true;
                reason = isHitsMet ? "TREND_AND_PRICE_SL_HIT" : "PRICE_SL_HARD_STOP";
            }
        } else {
            if (isHitsMet) {
                shouldExit = true;
                reason = "TREND_SL_MET_(NO_PRICE_SL_CONFIGURED)";
            }
        }

        if (shouldExit) {
            log.warn("🚨 [{}][EXIT] Triggering exit: {}", tradeName, reason);
            monitorOrderService.forceExit(activeOrders, tradeName, reason);
            hitCounters.remove(exitKey);
            lastHitTimestamps.remove(exitKey);
        }
    }

    protected void executeShortStraddle(String tradeName, StraddleIntraday tick, BigDecimal entryGap, Strategy strategyConfig, Strategy sourceConfig) {
        // 🛑 BULLETPROOF GUARD: Never allow order placement if we are past the entry cutoff time!
        String baseSymbol = tradeName.replace(NAME_PREFIX, "");
        if (!isWithinEntryWindow(baseSymbol, LocalTime.now())) {
            log.warn("🛑 [{}][BLOCKED] Attempted to execute entry past the cutoff time! Aborting order.", tradeName);
            return;
        }
        log.info("🚀 [{}][EXECUTE] Opening positions for Strike: {}", tradeName, tick.getStrike());
        String cycleId = UUID.randomUUID().toString();
        BigDecimal userTarget = sourceConfig.getTargetPoints();
        BigDecimal finalTarget = (userTarget == null || userTarget.compareTo(BigDecimal.ZERO) <= 0)
                ? new BigDecimal("50.00")
                : userTarget;

        log.info("🎯 [{}][TARGET] Setting target to {} pts (User input: {})", tradeName, finalTarget, userTarget);

        BigDecimal targetValue = entryGap.add(finalTarget);

        Orders ceOrder = processLeg(tick.getCeToken(), tick.getCeSymbol(), strategyConfig, sourceConfig, tick.getCePrice(),
                tick.getStrike(), tradeName, "CE", cycleId, entryGap, targetValue);

        Orders peOrder = processLeg(tick.getPeToken(), tick.getPeSymbol(), strategyConfig, sourceConfig, tick.getPePrice(),
                tick.getStrike(), tradeName, "PE", cycleId, entryGap, targetValue);

        boolean ceSuccess = ceOrder != null;
        boolean peSuccess = peOrder != null;

        if (ceSuccess != peSuccess) {
            log.error("🚨 [{}][EXECUTION] Partial entry detected! CE: {}, PE: {}. Rolling back placed leg.", tradeName, ceSuccess, peSuccess);
            List<Orders> partialOrdersToClose = new ArrayList<>();
            if (ceSuccess) partialOrdersToClose.add(ceOrder);
            if (peSuccess) partialOrdersToClose.add(peOrder);

            // ✅ Safely hands rollback to Master Engine
            monitorOrderService.forceExit(partialOrdersToClose, tradeName, "PARTIAL_FILL_ROLLBACK");

            // 🛑 Prevent broker glitches from wasting your max_daily_trades!
            for (Orders abortedOrder : partialOrdersToClose) {
                abortedOrder.setSignal("ABORTED_PARTIAL_FILL");
                ordersRepository.save(abortedOrder);
            }

        } else if (ceSuccess) {
            String mode = "Y".equalsIgnoreCase(strategyConfig.getLive()) ? "LIVE" : "PAPER";
            telegramService.sendMessage(String.format("🚀 **ENTRY [%s]: %s**\nStrike: %s\nGap: %.2f\nTarget: +%.2f",
                    mode, tradeName, tick.getStrike(), entryGap, sourceConfig.getTargetPoints()));
        } else {
            log.error("❌ [{}][EXECUTION] Both legs failed to execute. No positions opened.", tradeName);
        }
    }

    private Orders processLeg(String tokenStr, String symbol,
                              Strategy strategyConfig, Strategy sourceConfig, BigDecimal price,
                              BigDecimal strike, String tradeName, String type, String cycleId,
                              BigDecimal gap, BigDecimal targetValue) {
        try {
            Token t = new Token();
            t.setToken(tokenStr);
            t.setSymbol(symbol);
            t.setStrike(strike);

            // RESTORED: Must use base name for OrderService
            t.setName(sourceConfig.getName());
            t.setExch_seg(sourceConfig.getExchange());
            t.setQuantity(sourceConfig.getQuantity());

            log.info("📝 [{}][{}] Preparing -> Source: {}, Symbol: {}, Token: {}, Strike: {}, Qty: {}",
                    tradeName, type, sourceConfig.getName(), symbol, tokenStr, strike, sourceConfig.getQuantity());

            Orders order;

            if ("Y".equalsIgnoreCase(strategyConfig.getLive())) {
                log.info("🌐 [{}][{}] LIVE MODE: Sending to broker...", tradeName, type);
                try {
                    // RESTORED: Pass sourceConfig.getName() to OrderService
                    orderService.orderPlaceWithToken(t, sourceConfig.getName(), "SELL", true);
                } catch (Exception | SmartAPIException e) {
                    log.error("⚠️ [{}][LEG] Broker execution failed for {}. Reason: {}", tradeName, type, e.getMessage());
                    return null;
                }

                // RESTORED: Fetch the global row created by OrderService to get the orderid
                order = ordersRepository.findByNameAndTokenAndActive(
                        sourceConfig.getName(), tokenStr, STATUS_ACTIVE).orElse(null);

                if (order == null) {
                    log.error("❌ [{}][LEG] Critical Error: Broker row not found in DB after insertion for Token: {}", tradeName, tokenStr);
                    return null;
                }
            } else {
                log.info("📄 [{}][{}] PAPER MODE: Skipping broker execution, tracking via DB...", tradeName, type);
                order = new Orders();
                order.setOrderid("1"); // ✅ PAPER MARKER FOR MONITOR_ORDER_SERVICE
                order.setToken(tokenStr);
                order.setSymbol(symbol);
                order.setQuantity(sourceConfig.getQuantity());
                order.setExchange(sourceConfig.getExchange());
                order.setActive(STATUS_ACTIVE);
            }

            // Apply strategy fields to the fetched (or new) order
            // This updates the name to SHORT_STRADDLE_CRUDEOIL so it belongs to this strategy
            order.setName(tradeName);
            order.setType("SELL"); // ✅ STRICT TYPE ENFORCEMENT
            order.setSignal(STRATEGY_SIGNAL);
            order.setOptionType(type);
            order.setTradeCycleId(cycleId);
            order.setBreakeven(gap);
            order.setTarget(targetValue);
            order.setAskPrice(price);
            order.setStrike(strike);
            order.setStatus(STATUS_OPEN);
            order.setTradePhase(PHASE_ENTRY);

            return ordersRepository.save(order);

        } catch (Exception e) {
            log.error("❌ [{}][LEG] System error during DB insert for {}: {}", tradeName, type, e.getMessage());
            return null;
        }
    }

    private boolean isSquareOffTime(String symbol, LocalTime now) {
        if ("NIFTY".equalsIgnoreCase(symbol)) return !now.isBefore(NIFTY_SQUARE_OFF);
        if ("SENSEX".equalsIgnoreCase(symbol)) return !now.isBefore(SENSEX_SQUARE_OFF);
        if (symbol.contains("CRUDE")) return !now.isBefore(CRUDE_SQUARE_OFF);
        if (symbol.contains("NATURALGAS")) return !now.isBefore(NATURALGAS_SQUARE_OFF);
        return false;
    }

    private boolean isWithinEntryWindow(String symbol, LocalTime now) {
        if ("NIFTY".equalsIgnoreCase(symbol)) return now.isBefore(NIFTY_ENTRY_CUTOFF);
        if ("SENSEX".equalsIgnoreCase(symbol)) return now.isBefore(SENSEX_ENTRY_CUTOFF);
        if (symbol.contains("CRUDE")) return now.isBefore(CRUDE_ENTRY_CUTOFF);
        if (symbol.contains("NATURALGAS")) return now.isBefore(NATURALGAS_ENTRY_CUTOFF);
        return true;
    }

    private Optional<BigDecimal> getNiftyIndexAtm(String tradeName) {
        Strategy indexConfig = strategyRepo.findByName("NIFTY_INDEX");

        if (indexConfig == null || indexConfig.getToken() == null) {
            log.error("❌ [{}] NIFTY_INDEX config missing in DB. Cannot determine Index ATM.", tradeName);
            return Optional.empty();
        }

        BigDecimal indexLtp = angelWebSocketService.getLatestLTP(ExchangeType.NSE_CM, indexConfig.getToken());

        if (indexLtp == null || indexLtp.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("⚠️ [{}] NIFTY Index LTP unavailable from WebSocket. Skipping entry scan.", tradeName);
            return Optional.empty();
        }

        BigDecimal atmStrike = indexLtp.divide(new BigDecimal("50"), 0, RoundingMode.HALF_UP).multiply(new BigDecimal("50"));
        log.debug("📉 [{}] NIFTY INDEX Live LTP: {} -> Calculated ATM Strike: {}", tradeName, indexLtp, atmStrike);

        return Optional.of(atmStrike);
    }

    private Optional<BigDecimal> getSensexIndexAtm(String tradeName) {
        Strategy indexConfig = strategyRepo.findByName("SENSEX_INDEX");

        if (indexConfig == null || indexConfig.getToken() == null) {
            log.error("❌ [{}] SENSEX_INDEX config missing in DB. Cannot determine Index ATM.", tradeName);
            return Optional.empty();
        }

        BigDecimal indexLtp = angelWebSocketService.getLatestLTP(ExchangeType.BSE_CM, indexConfig.getToken());

        if (indexLtp == null || indexLtp.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("⚠️ [{}] SENSEX Index LTP unavailable from WebSocket. Skipping entry scan.", tradeName);
            return Optional.empty();
        }

        BigDecimal strikeStep = new BigDecimal("100");
        BigDecimal atmStrike = indexLtp.divide(strikeStep, 0, RoundingMode.HALF_UP).multiply(strikeStep);
        log.debug("📉 [{}] SENSEX INDEX Live LTP: {} -> Calculated ATM Strike: {}", tradeName, indexLtp, atmStrike);

        return Optional.of(atmStrike);
    }
}