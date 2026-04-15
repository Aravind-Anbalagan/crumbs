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
    private static final String NAME_PREFIX = "SHORT_STRADDLE_"; // For NIFTY -> SHORT_STRADDLE_NIFTY
    
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
     * Standardized Evaluation Pulse
     */
    public void evaluate(String symbol) {
        LocalTime now = LocalTime.now();
        
        // 1. Name Standardization
        String baseSymbol = symbol.toUpperCase().replace(NAME_PREFIX, "");
        String tradeName = NAME_PREFIX + baseSymbol;

        // 2. Fetch Configuration (Retaining effectively final for Lambda safety)
        Strategy tempStrategy = strategyRepo.findByName(tradeName);
        if (tempStrategy == null) {
            tempStrategy = strategyRepo.findByName(baseSymbol); 
            if (tempStrategy == null) return;
        }
        final Strategy strategy = tempStrategy;

        // 3. Daily Quota Check
        LocalDateTime startOfDay = LocalDateTime.now().with(LocalTime.MIN);
        long totalLegs = ordersRepository.countLegsToday(tradeName, STRATEGY_SIGNAL, startOfDay);
        long straddlesUsed = totalLegs / 2; 
        int maxAllowed = strategy.getMaxDailyTrades() > 0 ? strategy.getMaxDailyTrades() : 3;

        log.info("⏱️ [{}][EVAL] Pulse @ {} | Straddles Today: {}/{}", 
                tradeName, now.format(DateTimeFormatter.ofPattern("HH:mm:ss")), straddlesUsed, maxAllowed);

        // Crude Start Window check
        if (tradeName.contains("CRUDE") && now.isBefore(CRUDE_START)) return;

        // 4. Trade State Management
        List<Orders> activeOrders = ordersRepository.findByNameAndSignalAndActive(tradeName, STRATEGY_SIGNAL, STATUS_ACTIVE);

        if (!activeOrders.isEmpty()) {
            
            // --- NEW: SAFETY INTEGRITY CHECK ---
            // If DB shows 1 leg (like orphaned 2452 or 2453), close it immediately.
            if (activeOrders.size() != 2) {
                log.error("🚨 [{}][SAFETY] Imbalance! Found {} legs. Cleaning up orphan.", tradeName, activeOrders.size());
                straddleRepository.findLatestByName(baseSymbol).ifPresent(tick -> 
                    closeAll(activeOrders, tick, "ORPHAN_LEG_CLEANUP", strategy));
                return;
            }

            // --- MONITORING MODE ---
            if (isSquareOffTime(baseSymbol, now)) {
                log.info("🕒 [{}][EXIT] Square-off reached.", tradeName);
                straddleRepository.findLatestByName(baseSymbol).ifPresent(tick -> 
                    closeAll(activeOrders, tick, "EOD_SQUARE_OFF", strategy));
                return;
            }

            BigDecimal tradedStrike = activeOrders.get(0).getStrike();
            straddleRepository.findLatestBySymbolAndStrike(baseSymbol, tradedStrike).ifPresentOrElse(
                tick -> processExitSequence(tradeName, tick, activeOrders, strategy),
                () -> log.error("❌ [{}][MONITOR] Missing price data for strike: {}", tradeName, tradedStrike)
            );
            
        } else {
            // --- SCANNING MODE ---
            if (straddlesUsed >= maxAllowed) return;

            straddleRepository.findATMBySymbol(baseSymbol).ifPresent(tick -> 
                processEntrySequence(tradeName, tick, strategy)
            );
        }
    }

    private void processEntrySequence(String tradeName, StraddleIntraday tick, Strategy strategy) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        BigDecimal currentGap = cv.subtract(cp);
        BigDecimal maxAllowed = strategy.getMaxEntryRisk(); 
        int reqHits = strategy.getEntryHitsRequired() > 0 ? strategy.getEntryHitsRequired() : 3;

        // Original Entry Logic: CP must be below VWAP + Gap must be within risk limit
        if (cp.compareTo(cv) < 0 && (maxAllowed == null || currentGap.compareTo(maxAllowed) <= 0)) {
            int count = hitCounters.merge(tradeName + "_ENTRY", 1, Integer::sum);
            if (count >= reqHits) {
                executeShortStraddle(tradeName, tick, currentGap, strategy);
                hitCounters.put(tradeName + "_ENTRY", 0); 
            }
        } else {
            hitCounters.put(tradeName + "_ENTRY", 0);
        }
    }

    private void processExitSequence(String tradeName, StraddleIntraday tick, List<Orders> activeOrders, Strategy strategy) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        BigDecimal currentGap = cv.subtract(cp);
        BigDecimal entryGap = activeOrders.get(0).getBreakeven();
        
        // Target and SL definitions
        BigDecimal targetGap = entryGap.add(strategy.getTargetPoints());
        BigDecimal distToTarget = targetGap.subtract(currentGap);

        BigDecimal slPoints = strategy.getSlPoints();
        boolean isPointSlConfigured = slPoints != null && slPoints.compareTo(BigDecimal.ZERO) > 0;
        boolean isPointSlBreached = isPointSlConfigured && (currentGap.compareTo(entryGap.subtract(slPoints)) <= 0);

        // VWAP Shield Check
        boolean isVwapCrossover = cp.compareTo(cv) >= 0;
        int reqSlHits = strategy.getExitHitsRequired() > 0 ? strategy.getExitHitsRequired() : 3;
        
        if (isVwapCrossover) {
            hitCounters.merge(tradeName + "_EXIT", 1, Integer::sum);
        } else {
            hitCounters.put(tradeName + "_EXIT", 0); // "Healing" reset
        }
        
        int currentSlHits = hitCounters.getOrDefault(tradeName + "_EXIT", 0);
        boolean isHitsMet = currentSlHits >= reqSlHits;

        // --- DASHBOARD LOGS (RESTORED EXACTLY FROM ORIGINAL) ---
        String tradeStatus = currentGap.signum() >= 0 ? "🟢 PROFIT" : "🔴 LOSS";
        String cushionSign = currentGap.signum() >= 0 ? "+" : "";
        String defenseMode = isPointSlConfigured ? "🛡️ [TWO SHIELDS: Points + VWAP]" : "🛡️ [SINGLE SHIELD: VWAP Only]";
        String pStatus = !isPointSlConfigured ? "⚪ DISABLED" : (isPointSlBreached ? "🚨 BREACHED" : "🛡️ SECURE");
        String vStatus = isVwapCrossover ? "🚨 CROSSOVER (" + currentSlHits + "/" + reqSlHits + ")" : "🛡️ STABLE";

        log.info("================================================================================");
        log.info("📊 [{}] Status: {} | PnL: {}{} pts", tradeName, tradeStatus, cushionSign, currentGap.setScale(2, RoundingMode.HALF_UP));
        log.info("🎯 GOAL: {} pts | Distance: {} to go", targetGap.setScale(2, RoundingMode.HALF_UP), distToTarget.setScale(2, RoundingMode.HALF_UP));
        log.info("{}", defenseMode);
        log.info("🛡️ SHIELDS: [Price Shield: {}] AND [Trend Shield: {}]", pStatus, vStatus);
        log.info("================================================================================");

        // --- EXIT LOGIC (RESTORED EXACTLY) ---
        // A. Instant Target
        if (currentGap.compareTo(targetGap) >= 0) {
            log.info("💰 [{}][EXIT] TARGET REACHED!", tradeName);
            closeAll(activeOrders, tick, "TARGET_REACHED", strategy);
            hitCounters.put(tradeName + "_EXIT", 0);
            return;
        }

        // B. Smart SL (Both conditions must be met if points are configured)
        if ((!isPointSlConfigured || isPointSlBreached) && isHitsMet) {
            String reason = isPointSlConfigured ? "DOUBLE_CONFIRMATION_SL" : "VWAP_HITS_ONLY_SL";
            log.warn("🚨 [{}][EXIT] SL TRIGGERED! Reason: {}", tradeName, reason);
            closeAll(activeOrders, tick, reason, strategy);
            hitCounters.put(tradeName + "_EXIT", 0);
        }
    }

    @Transactional
    protected void executeShortStraddle(String tradeName, StraddleIntraday tick, BigDecimal entryGap, Strategy strategy) {
        log.info("🚀 [{}][EXECUTE] Opening positions.", tradeName);
        String cycleId = UUID.randomUUID().toString();
        BigDecimal targetValue = entryGap.add(strategy.getTargetPoints());

        boolean ceSuccess = processLeg(tick.getCeToken(), tick.getCeSymbol(), strategy, tick.getCePrice(), 
                   tick.getStrike(), tradeName, "CE", cycleId, entryGap, targetValue);

        boolean peSuccess = processLeg(tick.getPeToken(), tick.getPeSymbol(), strategy, tick.getPePrice(), 
                   tick.getStrike(), tradeName, "PE", cycleId, entryGap, targetValue);

        // --- NEW: PARTIAL FILL ROLLBACK ---
        if (ceSuccess != peSuccess) {
            log.error("🚨 [{}][EXECUTION] Partial entry detected. Rolling back orphan leg.", tradeName);
            List<Orders> partial = ordersRepository.findByNameAndSignalAndActive(tradeName, STRATEGY_SIGNAL, STATUS_ACTIVE);
            closeAll(partial, tick, "PARTIAL_FILL_ROLLBACK", strategy);
        } else if (ceSuccess) {
            telegramService.sendMessage(String.format("🚀 **ENTRY: %s**\nStrike: %s\nGap: %.2f\nTarget: +%.2f", 
                    tradeName, tick.getStrike(), entryGap, strategy.getTargetPoints()));
        }
    }

    private boolean processLeg(String tokenStr, String symbol, Strategy strategy, BigDecimal price, 
                            BigDecimal strike, String tradeName, String type, String cycleId, 
                            BigDecimal gap, BigDecimal targetValue) {
        try {
            Token t = new Token();
            t.setToken(tokenStr); t.setSymbol(symbol); t.setExch_seg(strategy.getExchange());
            t.setStrike(strike); t.setName(tradeName); t.setQuantity(strategy.getQuantity());

            // Handle "Y" vs "N" for Live Execution
            if ("Y".equalsIgnoreCase(strategy.getLive())) {
                try {
                    orderService.orderPlaceWithToken(t, tradeName, "SELL", true);
                } catch (Exception | SmartAPIException e) {
                    log.warn("⚠️ [{}][LEG] Broker failed for {}.", tradeName, type);
                    return false; // Return false so executeShortStraddle can rollback the other leg
                }
            }

            Orders order = ordersRepository.findByTokenAndActive(tokenStr, STATUS_ACTIVE);
            if (order == null) {
                order = new Orders();
                order.setToken(tokenStr); order.setSymbol(symbol);
                order.setActive(STATUS_ACTIVE); order.setName(tradeName);
            }

            order.setQuantity(t.getQuantity()); order.setSignal(STRATEGY_SIGNAL);
            order.setOptionType(type); order.setTradeCycleId(cycleId);
            order.setBreakeven(gap); order.setTarget(targetValue); 
            order.setAskPrice(price); order.setStrike(strike);
            order.setStatus(STATUS_OPEN); order.setTradePhase(PHASE_ENTRY);
            order.setCreatedOn(LocalDateTime.now());
            
            ordersRepository.save(order);
            return true;
        } catch (Exception e) {
            log.error("❌ [{}][LEG] Failure: {}", tradeName, e.getMessage());
            return false;
        }
    }

    @Transactional
    protected void closeAll(List<Orders> activeOrders, StraddleIntraday tick, String reason, Strategy strategy) {
        String tradeName = strategy.getName();
        BigDecimal totalEntry = BigDecimal.ZERO;
        BigDecimal totalExit = BigDecimal.ZERO;
        boolean allSuccess = true;

        for (Orders order : activeOrders) {
            if (order.getActive() == STATUS_INACTIVE) continue;
            try {
                if ("Y".equalsIgnoreCase(strategy.getLive())) {
                    try {
                        orderService.exitActiveTradeByToken(order.getToken(), tradeName);
                    } catch (Exception | SmartAPIException e) {
                        allSuccess = false;
                        log.error("❌ [{}][EXIT] Broker failed for {}: {}", tradeName, order.getOptionType(), e.getMessage());
                    }
                }

                BigDecimal exitPrice = "CE".equals(order.getOptionType()) ? tick.getCePrice() : tick.getPePrice();
                totalEntry = totalEntry.add(order.getAskPrice() != null ? order.getAskPrice() : BigDecimal.ZERO);
                totalExit = totalExit.add(exitPrice);

                order.setExitPrice(exitPrice);
                order.setPl(order.getAskPrice() != null ? order.getAskPrice().subtract(exitPrice) : BigDecimal.ZERO);
                order.setClosedOn(LocalDateTime.now());
                order.setTradePhase(PHASE_EXIT);
                order.setStatus(STATUS_CLOSED);
                order.setActive(STATUS_INACTIVE);
                ordersRepository.save(order);
            } catch (Exception e) {
                log.error("❌ [{}][EXIT] Internal error: {}", tradeName, e.getMessage());
            }
        }
        
        if (allSuccess && activeOrders.size() == 2) {
            BigDecimal pnl = totalEntry.subtract(totalExit);
            telegramService.sendMessage(String.format("%s **EXIT: %s**\nReason: %s\nPnL: **%.2f pts**", 
                    pnl.signum() >= 0 ? "✅" : "❌", tradeName, reason, pnl));
        }
    }

    private boolean isSquareOffTime(String symbol, LocalTime now) {
        if ("NIFTY".equalsIgnoreCase(symbol)) return !now.isBefore(NIFTY_SQUARE_OFF);
        if (symbol.contains("CRUDE")) return !now.isBefore(CRUDE_SQUARE_OFF);
        return false;
    }
}