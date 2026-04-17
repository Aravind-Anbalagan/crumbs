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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShortStraddleService {

    private static final String STRATEGY_SIGNAL = "SHORT_STRADDLE"; // Matches DB Row 18
    private static final String NAME_PREFIX = "SHORT_STRADDLE_"; 
    
    // --- Rule 1: Strict Timeframes ---
    private static final LocalTime NIFTY_START = LocalTime.of(9, 20);
    private static final LocalTime NIFTY_ENTRY_CUTOFF = LocalTime.of(15, 0);
    private static final LocalTime NIFTY_SQUARE_OFF = LocalTime.of(15, 20);
    private static final LocalTime CRUDE_START = LocalTime.of(16, 0);
    private static final LocalTime CRUDE_ENTRY_CUTOFF = LocalTime.of(23, 0); // 🛑 NEW: No Crude entries after 11:00 PM
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
        
        String baseSymbol = symbol.toUpperCase().replace(NAME_PREFIX, ""); // e.g., "NIFTY"
        String tradeName = NAME_PREFIX + baseSymbol; // e.g., "SHORT_STRADDLE_NIFTY"

        // =========================================================
        // 🔗 DUAL-LOOKUP ARCHITECTURE (SOURCE + STRATEGY)
        // =========================================================
        Strategy strategyConfig = strategyRepo.findByName(STRATEGY_SIGNAL); // Row 18
        Strategy sourceConfig = strategyRepo.findByName(baseSymbol);        // Row 3 / Row 4
        
        if (strategyConfig == null || sourceConfig == null) {
            log.error("❌ DB Config Missing! Strategy Present: {}, Source Present: {}", 
                      strategyConfig != null, sourceConfig != null);
            return;
        }

        // Daily Limits pulled from STRATEGY
        LocalDateTime startOfDay = LocalDateTime.now().with(LocalTime.MIN);
        long totalLegs = ordersRepository.countLegsToday(tradeName, STRATEGY_SIGNAL, startOfDay);
        long straddlesUsed = totalLegs / 2; 
        int maxAllowed = sourceConfig.getMaxDailyTrades() > 0 ? sourceConfig.getMaxDailyTrades() : 3;

        // Time Window Checks
        if ("NIFTY".equalsIgnoreCase(baseSymbol) && now.isBefore(NIFTY_START)) return;
        if (tradeName.contains("CRUDE") && now.isBefore(CRUDE_START)) return;

        List<Orders> activeOrders = ordersRepository.findByNameAndSignalAndActive(tradeName, STRATEGY_SIGNAL, STATUS_ACTIVE);

        if (!activeOrders.isEmpty()) {
            
            // Safety Orphan Cleanup
            if (activeOrders.size() != 2) {
                log.error("🚨 [{}][SAFETY] Imbalance! Found {} legs. Cleaning up orphan.", tradeName, activeOrders.size());
                BigDecimal tradedStrike = activeOrders.get(0).getStrike();
                straddleRepository.findLatestBySymbolAndStrike(baseSymbol, tradedStrike).ifPresent(tick -> 
                    closeAll(activeOrders, tick, "ORPHAN_LEG_CLEANUP", sourceConfig));
                return;
            }

            // EOD Square Off
            if (isSquareOffTime(baseSymbol, now)) {
                log.info("🕒 [{}][EXIT] Square-off time reached.", tradeName);
                BigDecimal tradedStrike = activeOrders.get(0).getStrike();
                straddleRepository.findLatestBySymbolAndStrike(baseSymbol, tradedStrike).ifPresent(tick -> 
                    closeAll(activeOrders, tick, "EOD_SQUARE_OFF", sourceConfig));
                return;
            }

            BigDecimal tradedStrike = activeOrders.get(0).getStrike();
            straddleRepository.findLatestBySymbolAndStrike(baseSymbol, tradedStrike).ifPresentOrElse(
                tick -> processExitSequence(tradeName, tick, activeOrders, sourceConfig),
                () -> log.error("❌ [{}][MONITOR] Missing price data for strike: {}", tradeName, tradedStrike)
            );
            
        } else {
            if (straddlesUsed >= maxAllowed) {
                log.info("🛑 [{}] Max daily trades reached ({}/{}). Stopping scans.", tradeName, straddlesUsed, maxAllowed);
                return;
            }
            
         // 🛑 NEW CUTOFF LOGIC: Block new entries if past the cutoff time
            if (!isWithinEntryWindow(baseSymbol, now)) {
                // We use debug/trace level logging here so we don't spam the console every second until square-off
                log.debug("⏳ [{}] Entry window closed for today. Waiting for EOD.", tradeName);
                return;
            }
         // If time is valid, scan for entry
            straddleRepository.findATMBySymbol(baseSymbol).ifPresent(tick -> 
                processEntrySequence(tradeName, tick, strategyConfig, sourceConfig)
            );
        }
    }

    private void processEntrySequence(String tradeName, StraddleIntraday tick, Strategy strategyConfig, Strategy sourceConfig) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        BigDecimal currentGap = cv.subtract(cp); 
        
        // Treat the config as the Minimum Safe Distance (Default to 0 if null)
     // 🟢 PULL FROM SOURCE
        BigDecimal safeDistance = sourceConfig.getMaxEntryRisk() != null ? sourceConfig.getMaxEntryRisk() : BigDecimal.ZERO; 
        int reqHits = sourceConfig.getEntryHitsRequired() > 0 ? sourceConfig.getEntryHitsRequired() : 3;

        // 1. Break down the conditions independently
        boolean isCpBelowCv = cp.compareTo(cv) < 0;
        boolean isGapAcceptable = currentGap.compareTo(safeDistance) <= 0;

        // 2. Generate explicit statuses for BOTH rules
        String trendStatus = isCpBelowCv ? "✅ VALID (CP < CV)" : "⏳ WAITING (CP > CV)";
        String distStatus = isGapAcceptable ? String.format("✅ SAFE (Gap %.2f <= Max Risk %.2f)", currentGap, safeDistance) 
                : String.format("🚫 UNSAFE (Gap %.2f > Max Risk %.2f)", currentGap, safeDistance);

        // 3. Print the explicit Dual-Status SCANNING LOG
        log.info("🔍 [{}] SCANNING | Strike: {} | CP: {} | CV: {} | Gap: {}", 
                tradeName, tick.getStrike(), cp, cv, currentGap.setScale(2, RoundingMode.HALF_UP));
        log.info("🚦 ENTRY RULES -> [Trend: {}] | [Distance: {}]", trendStatus, distStatus);

        // 4. Hit Tracker & Execution Logic
        if (isCpBelowCv && isGapAcceptable) {
            int count = hitCounters.merge(tradeName + "_ENTRY", 1, Integer::sum);
            
            log.info("🎯 [{}] ENTRY HIT TRACKER: ({}/{}) consecutive hits.", tradeName, count, reqHits);
            
            if (count >= reqHits) {
                log.info("⚡ [{}] ALL HITS MET! Triggering execution...", tradeName);
                executeShortStraddle(tradeName, tick, currentGap, strategyConfig, sourceConfig);
                hitCounters.put(tradeName + "_ENTRY", 0); // Reset after entry
            }
        } else {
            int previousCount = hitCounters.getOrDefault(tradeName + "_ENTRY", 0);
            if (previousCount > 0) {
                log.info("🔄 [{}] STREAK BROKEN! Hit counter reset from ({}/{}) back to 0.", tradeName, previousCount, reqHits);
            }
            hitCounters.put(tradeName + "_ENTRY", 0); // Reset if any condition breaks
        }
    }

    private void processExitSequence(String tradeName, StraddleIntraday tick, List<Orders> activeOrders, Strategy sourceConfig) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        BigDecimal currentGap = cv.subtract(cp);
        BigDecimal entryGap = activeOrders.get(0).getBreakeven();
        
        // Target definitions
        BigDecimal targetGap = entryGap.add(sourceConfig.getTargetPoints());
        BigDecimal distToTarget = targetGap.subtract(currentGap);

        // Shield 2: Price SL definitions
        BigDecimal slPoints = sourceConfig.getSlPoints();
        boolean isPointSlConfigured = slPoints != null && slPoints.compareTo(BigDecimal.ZERO) > 0;
        boolean isPointSlBreached = isPointSlConfigured && (currentGap.compareTo(entryGap.subtract(slPoints)) <= 0);

        // Shield 1: Trend SL definitions (VWAP Crossover)
        boolean isVwapCrossover = cp.compareTo(cv) > 0;
        int reqSlHits = sourceConfig.getExitHitsRequired() > 0 ? sourceConfig.getExitHitsRequired() : 3;
        
        if (isVwapCrossover) {
            hitCounters.merge(tradeName + "_EXIT", 1, Integer::sum);
        } else {
            hitCounters.put(tradeName + "_EXIT", 0); 
        }
        
        int currentSlHits = hitCounters.getOrDefault(tradeName + "_EXIT", 0);
        boolean isHitsMet = currentSlHits >= reqSlHits;

     // =========================================================================
        // 📊 USER-FRIENDLY MONITORING DASHBOARD
        // =========================================================================
        BigDecimal tradedStrike = activeOrders.get(0).getStrike();
        String tradeStatus = currentGap.compareTo(entryGap) >= 0 ? "🟢 PROFIT" : "🔴 LOSS";
        String cushionSign = currentGap.compareTo(entryGap) >= 0 ? "+" : "";
        BigDecimal currentPnL = currentGap.subtract(entryGap);
        
        String defenseMode = isPointSlConfigured ? "🛡️ [TWO SHIELDS: Trend + Price]" : "🛡️ [SINGLE SHIELD: Trend Only]";
        String vStatus = isVwapCrossover ? "⚠️ CROSSOVER (" + currentSlHits + "/" + reqSlHits + ")" : "✅ STABLE";
        
        // 🛑 NEW: Detailed Price Shield Status
        String pStatus;
        if (!isPointSlConfigured) {
            pStatus = "⚪ DISABLED";
        } else {
            BigDecimal slFloor = entryGap.subtract(slPoints);
            pStatus = isPointSlBreached 
                    ? String.format("🚨 BREACHED (Gap %.2f <= Floor %.2f)", currentGap, slFloor) 
                    : String.format("✅ SECURE (Gap %.2f > Floor %.2f | Configured SL: %s pts)", currentGap, slFloor, slPoints);
        }

        log.info("================================================================================");
        // 🛑 NEW: Added Strike to the main status line
        log.info("📊 [{}] Strike: {} | Status: {} | Floating PnL: {}{} pts", tradeName, tradedStrike, tradeStatus, cushionSign, currentPnL.setScale(2, RoundingMode.HALF_UP));
        log.info("🎯 GOAL: {} pts gap | Distance: {} pts to go", targetGap.setScale(2, RoundingMode.HALF_UP), distToTarget.setScale(2, RoundingMode.HALF_UP));
        log.info("{}", defenseMode);
        log.info("🛡️ SHIELD STATUS -> [Trend: {}] | [Price: {}]", vStatus, pStatus);
        log.info("================================================================================");

        // =========================================================================
        // EXIT TRIGGER LOGIC
        // =========================================================================
        
        // 1. Target Hit
        if (currentGap.compareTo(targetGap) >= 0) {
            log.info("💰 [{}][EXIT] TARGET REACHED!", tradeName);
            closeAll(activeOrders, tick, "TARGET_REACHED", sourceConfig);
            hitCounters.put(tradeName + "_EXIT", 0);
            return;
        }

        // 2. Dual-Shield SL Logic
        // If point SL is configured, BOTH shields must break. If not configured, only Trend shield must break.
        if ((!isPointSlConfigured || isPointSlBreached) && isHitsMet) {
            String reason = isPointSlConfigured ? "DOUBLE_SHIELD_SL_BREACHED" : "VWAP_TREND_SL_BREACHED";
            log.warn("🚨 [{}][EXIT] STOP LOSS TRIGGERED! Reason: {}", tradeName, reason);
            closeAll(activeOrders, tick, reason, sourceConfig);
            hitCounters.put(tradeName + "_EXIT", 0);
        }
    }

    // Notice: NO @Transactional here to prevent DB connection locks during live network calls
    protected void executeShortStraddle(String tradeName, StraddleIntraday tick, BigDecimal entryGap, Strategy strategyConfig, Strategy sourceConfig) {
    	log.info("🚀 [{}][EXECUTE] Opening positions for Strike: {}", tradeName, tick.getStrike());
        String cycleId = UUID.randomUUID().toString();
        BigDecimal targetValue = entryGap.add(sourceConfig.getTargetPoints());

        // Process CE Leg
        Orders ceOrder = processLeg(tick.getCeToken(), tick.getCeSymbol(), sourceConfig, sourceConfig, tick.getCePrice(), 
                   tick.getStrike(), tradeName, "CE", cycleId, entryGap, targetValue);

        // Process PE Leg
        Orders peOrder = processLeg(tick.getPeToken(), tick.getPeSymbol(), sourceConfig, sourceConfig, tick.getPePrice(), 
                   tick.getStrike(), tradeName, "PE", cycleId, entryGap, targetValue);

        boolean ceSuccess = ceOrder != null;
        boolean peSuccess = peOrder != null;

        // --- Rule 6: Partial Fill Rollback (Live and Paper) ---
        if (ceSuccess != peSuccess) {
            log.error("🚨 [{}][EXECUTION] Partial entry detected! CE: {}, PE: {}. Rolling back placed leg.", tradeName, ceSuccess, peSuccess);
            
            // Gather the successfully placed leg(s)
            List<Orders> partialOrdersToClose = new ArrayList<>();
            if (ceSuccess) partialOrdersToClose.add(ceOrder);
            if (peSuccess) partialOrdersToClose.add(peOrder);
            
            // Roll it back immediately
            closeAll(partialOrdersToClose, tick, "PARTIAL_FILL_ROLLBACK", sourceConfig);
            
        } else if (ceSuccess) {
            // --- Rule 9: Telegram Logs ---
            String mode = "Y".equalsIgnoreCase(sourceConfig.getLive()) ? "LIVE" : "PAPER";
            telegramService.sendMessage(String.format("🚀 **ENTRY [%s]: %s**\nStrike: %s\nGap: %.2f\nTarget: +%.2f", 
                    mode, tradeName, tick.getStrike(), entryGap, sourceConfig.getTargetPoints()));
        } else {
            log.error("❌ [{}][EXECUTION] Both legs failed to execute. No positions opened.", tradeName);
        }
    }

    // --- Rule 7 & 8: Live vs Paper DB Tracking ---
    private Orders processLeg(String tokenStr, String symbol, Strategy strategyConfig, Strategy sourceConfig, BigDecimal price, 
                                BigDecimal strike, String tradeName, String type, String cycleId, 
                                BigDecimal gap, BigDecimal targetValue) {
        try {
            Token t = new Token();
            t.setToken(tokenStr); 
            t.setSymbol(symbol); 
            t.setStrike(strike); 
            t.setName(tradeName); 
            
            // --- ARCHITECTURE MAPPING ---
            t.setExch_seg(sourceConfig.getExchange()); // Exchange strictly from SOURCE
            t.setQuantity(sourceConfig.getQuantity()); // Quantity strictly from SOURCE

            // Mandatory logging of attributes
            log.info("📝 [{}][{}] Preparing -> Strike: {}, Symbol: {}, Token: {}, Exchange: {}, Qty: {}", 
                    tradeName, type, strike, t.getSymbol(), t.getToken(), t.getExch_seg(), t.getQuantity());

            if ("Y".equalsIgnoreCase(sourceConfig.getLive())) {
                try {
                    log.info("🌐 [{}][{}] LIVE MODE: Sending to broker...", tradeName, type);
                    // Pass strategyConfig.getName() ("SHORT_STRADDLE") so Angel API doesn't crash
                    orderService.orderPlaceWithToken(t, strategyConfig.getName(), "SELL", true);
                } catch (Exception | SmartAPIException e) {
                    log.error("⚠️ [{}][LEG] Broker failed for {}. Reason: {}", tradeName, type, e.getMessage());
                    return null; // Return null so executeShortStraddle triggers partial rollback
                }
            } else {
                log.info("📄 [{}][{}] PAPER MODE: Monitor via DB.", tradeName, type);
            }

            // Save to DB regardless of live/paper (as long as it didn't fail the live broker check)
            Orders order = ordersRepository.findByTokenAndActive(tokenStr, STATUS_ACTIVE);
            if (order == null) {
                order = new Orders();
                order.setToken(tokenStr);
                order.setSymbol(symbol);
               
            }
            order.setActive(STATUS_ACTIVE); order.setName(tradeName);
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
            
            return ordersRepository.save(order);

        } catch (Exception e) {
            log.error("❌ [{}][LEG] Database/System error for {}: {}", tradeName, type, e.getMessage());
            return null;
        }
    }

    // --- Rule 6, 8, 9: DB tracking, Exits, and Telegram ---
    
    protected void closeAll(List<Orders> activeOrders, StraddleIntraday tick, String reason, Strategy sourceConfig) {
        BigDecimal totalEntry = BigDecimal.ZERO;
        BigDecimal totalExit = BigDecimal.ZERO;
        boolean allSuccess = true;

        for (Orders order : activeOrders) {
            if (order.getActive() == STATUS_INACTIVE) continue;
            try {
                if ("Y".equalsIgnoreCase(sourceConfig.getLive())) {
                    try {
                        // Pass "SHORT_STRADDLE" to the exit service
                    	orderService.exitActiveTradeByToken(order.getToken(), sourceConfig.getName());
                    } catch (Exception | SmartAPIException e) {
                        allSuccess = false;
                        log.error("❌ [{}][EXIT] Broker failed to close {}: {}", sourceConfig.getName(), order.getOptionType(), e.getMessage());
                    }
                }

                BigDecimal exitPrice = "CE".equals(order.getOptionType()) ? tick.getCePrice() : tick.getPePrice();
                BigDecimal entryPrice = order.getAskPrice() != null ? order.getAskPrice() : BigDecimal.ZERO;
                
                totalEntry = totalEntry.add(entryPrice);
                totalExit = totalExit.add(exitPrice);

                order.setExitPrice(exitPrice);
                order.setPl(entryPrice.subtract(exitPrice)); // Short strategy: Entry - Exit
                order.setClosedOn(LocalDateTime.now());
                order.setTradePhase(PHASE_EXIT);
                order.setStatus(STATUS_CLOSED);
                order.setActive(STATUS_INACTIVE);
                order.setExitReason(reason);
                ordersRepository.save(order); // DB tracks all activity
                
            } catch (Exception e) {
                log.error("❌ [{}][EXIT] DB error closing leg: {}", sourceConfig.getName(), e.getMessage());
            }
        }
        
        // --- Rule 9: Telegram message on action ---
        if (allSuccess && !activeOrders.isEmpty()) {
            BigDecimal finalPnL = totalEntry.subtract(totalExit); 
            String emoji = finalPnL.signum() >= 0 ? "✅" : "❌";
            String mode = "Y".equalsIgnoreCase(sourceConfig.getLive()) ? "LIVE" : "PAPER";
            
            // Name format reflects the specific asset closed out: e.g. "SHORT_STRADDLE_NIFTY"
            String tradeName = activeOrders.get(0).getName(); 

            telegramService.sendMessage(String.format(
                "%s **EXIT [%s]: %s**\nReason: %s\nEntry Total: %.2f\nExit Total: %.2f\nPnL: **%.2f pts**", 
                emoji, mode, tradeName, reason, totalEntry, totalExit, finalPnL
            ));
        }
    }

    private boolean isSquareOffTime(String symbol, LocalTime now) {
        if ("NIFTY".equalsIgnoreCase(symbol)) return !now.isBefore(NIFTY_SQUARE_OFF);
        if (symbol.contains("CRUDE")) return !now.isBefore(CRUDE_SQUARE_OFF);
        return false;
    }
    
    private boolean isWithinEntryWindow(String symbol, LocalTime now) {
        if ("NIFTY".equalsIgnoreCase(symbol)) return now.isBefore(NIFTY_ENTRY_CUTOFF);
        if (symbol.contains("CRUDE")) return now.isBefore(CRUDE_ENTRY_CUTOFF);
        return true; 
    }
}