package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crumbs.trade.dto.LevelAnalysisResult;
import com.crumbs.trade.entity.Level;
import com.crumbs.trade.entity.TradeExecution;
import com.crumbs.trade.repo.LevelRepository;
import com.crumbs.trade.repo.TradeExecutionRepo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeManagerService {

    /* ==================================================
       CONSTANTS (CHANGE VALUES ONLY HERE)
       ================================================== */

    // --- Strategy toggles
    private static final boolean ENABLE_PRICE_ACTION = false;
    private static final boolean ENABLE_FIBO = true;
    private static final boolean ENABLE_TRAILING_SL = false;
    
    // --- Level check toggles
    private static final boolean ENABLE_IMMEDIATE_LEVEL_CHECK = true;
    
    // 🔥 NEW: Profitability improvement toggles
    private static final boolean ENABLE_STRENGTH_FILTER = true;
    private static final boolean ENABLE_TOUCH_COUNT_FILTER = true;
    private static final boolean ENABLE_DISTANCE_FROM_LEVEL_FILTER = true;
    private static final boolean ENABLE_RISK_REWARD_FILTER = true;
    private static final boolean ENABLE_HISTORICAL_WIN_RATE_FILTER = true;
    private static final boolean ENABLE_CONFLUENCE_FILTER = true;
    private static final boolean ENABLE_TIME_FILTER = true;
    private static final boolean ENABLE_LOSING_STREAK_PROTECTION = true;
    private static final boolean ENABLE_DAILY_LIMITS = true;
    private static final boolean ENABLE_PARTIAL_PROFIT = true;

    // 🔥 NEW: Profitability parameters
    private static final String REQUIRED_STRENGTH = "CRITICAL"; // Only CRITICAL levels
    private static final int MIN_TOUCH_COUNT = 100; // Minimum touches required
    private static final BigDecimal MAX_DISTANCE_FROM_LEVEL_PERCENT = new BigDecimal("0.1"); // 0.1% max distance
    private static final BigDecimal MIN_RISK_REWARD_RATIO = new BigDecimal("2.0"); // 2:1 minimum RR
    private static final BigDecimal MIN_HISTORICAL_WIN_RATE = new BigDecimal("50"); // 50% min win rate
    private static final int MIN_HISTORICAL_TRADES_REQUIRED = 5; // Need 5+ past trades for stats
    private static final BigDecimal CONFLUENCE_PROXIMITY_PERCENT = new BigDecimal("0.2"); // 0.2% for confluence
    private static final int MIN_CONFLUENCE_LEVELS = 2; // Need 2+ levels nearby
    private static final int MAX_CONSECUTIVE_LOSSES = 3; // Stop after 3 losses
    
    // 🔥 UPDATED: Symbol-specific daily limits (in POINTS, not rupees)
    private static final BigDecimal NIFTY_DAILY_PROFIT_TARGET = new BigDecimal("100"); // +100 points
    private static final BigDecimal NIFTY_DAILY_LOSS_LIMIT = new BigDecimal("-50"); // -50 points
    
    private static final BigDecimal SILVER_DAILY_PROFIT_TARGET = new BigDecimal("2500"); // +2500 points
    private static final BigDecimal SILVER_DAILY_LOSS_LIMIT = new BigDecimal("-1250"); // -1250 points

    // --- Cooldown
    private static final int COOLDOWN_MINUTES = 5;

    // --- Symbols
    private static final String SYMBOL_NIFTY = "NIFTY";
    private static final String SYMBOL_SILVERM = "SILVERM";

    // --- Risk config : NIFTY
    private static final BigDecimal NIFTY_TARGET = new BigDecimal("30");
    private static final BigDecimal NIFTY_SL = new BigDecimal("10");
    private static final BigDecimal NIFTY_TRAIL_SL = new BigDecimal("10");

    // --- Risk config : SILVERM
    private static final BigDecimal SILVER_TARGET = new BigDecimal("750");
    private static final BigDecimal SILVER_SL = new BigDecimal("250");
    private static final BigDecimal SILVER_TRAIL_SL = new BigDecimal("250");

    /* ==================================================
       DEPENDENCY
       ================================================== */

    private final TradeExecutionRepo repo;
    private final LevelRepository levelRepo;

    /* ==================================================
       ENTRY LOGIC - ENHANCED WITH FILTERS
       ================================================== */

    @Transactional
    public void handleSignal(
            String symbol,
            String timeframe,
            LevelAnalysisResult analysis) {

        if (analysis == null) return;

        if (!"BUY_ZONE".equals(analysis.getZone())
                && !"SELL_ZONE".equals(analysis.getZone())) return;

        // Method filter
        Level ref = "BUY_ZONE".equals(analysis.getZone())
                ? analysis.getNearestSupport()
                : analysis.getNearestResistance();

        if (ref == null) return;

        if (!isMethodAllowed(ref.getMethod())) return;

        String tradeType = "BUY_ZONE".equals(analysis.getZone()) ? "BUY" : "SELL";
        BigDecimal currentPrice = analysis.getCurrentPrice();

        // 🔥 IMPROVEMENT #1: Level Strength Filter
        if (ENABLE_STRENGTH_FILTER && !checkLevelStrength(ref, symbol, timeframe, tradeType, analysis)) {
            return;
        }

        // 🔥 IMPROVEMENT #2: Touch Count Filter
        if (ENABLE_TOUCH_COUNT_FILTER && !checkTouchCount(ref, symbol, timeframe, tradeType, analysis)) {
            return;
        }

        // 🔥 IMPROVEMENT #3: Distance from Level Filter
        if (ENABLE_DISTANCE_FROM_LEVEL_FILTER && !checkDistanceFromLevel(ref, currentPrice, symbol, timeframe, tradeType, analysis)) {
            return;
        }

        // 🔥 IMPROVEMENT #4: Risk-Reward Filter
        if (ENABLE_RISK_REWARD_FILTER && !checkRiskReward(symbol, timeframe, currentPrice, tradeType, analysis, ref)) {
            return;
        }

        // 🔥 IMPROVEMENT #5: Historical Win Rate Filter
        if (ENABLE_HISTORICAL_WIN_RATE_FILTER && !checkHistoricalWinRate(ref, symbol, timeframe, tradeType, analysis)) {
            return;
        }

        // 🔥 IMPROVEMENT #6: Confluence Filter
        if (ENABLE_CONFLUENCE_FILTER && !checkConfluence(ref, symbol, timeframe, tradeType, analysis)) {
            return;
        }

        // 🔥 IMPROVEMENT #7: Time-Based Filter
        if (ENABLE_TIME_FILTER && !checkTimeFilter(symbol, timeframe, tradeType, analysis, ref)) {
            return;
        }

        // 🔥 IMPROVEMENT #8: Losing Streak Protection
        if (ENABLE_LOSING_STREAK_PROTECTION && !checkLosingStreak(symbol, timeframe, tradeType, analysis, ref)) {
            return;
        }

        // 🔥 IMPROVEMENT #9: Daily Profit/Loss Limits (Symbol-Specific)
        if (ENABLE_DAILY_LIMITS && !checkDailyLimits(symbol, timeframe, tradeType, analysis, ref)) {
            return;
        }

        // Immediate resistance/support check
        if (ENABLE_IMMEDIATE_LEVEL_CHECK) {
            BigDecimal targetPrice = calcTarget(currentPrice, tradeType, symbol);
            String rejectionReason = null;
            
            if ("BUY_ZONE".equals(analysis.getZone())) {
                if (hasImmediateResistance(symbol, timeframe, currentPrice, targetPrice)) {
                    rejectionReason = "Immediate resistance between " + currentPrice + " and " + targetPrice;
                    log.info("BUY rejected for {}/{} - {}", symbol, timeframe, rejectionReason);
                }
            } else {
                if (hasImmediateSupport(symbol, timeframe, currentPrice, targetPrice)) {
                    rejectionReason = "Immediate support between " + currentPrice + " and " + targetPrice;
                    log.info("SELL rejected for {}/{} - {}", symbol, timeframe, rejectionReason);
                }
            }
            
            if (rejectionReason != null) {
                saveRejectedTrade(symbol, timeframe, tradeType, analysis, ref, rejectionReason);
                return;
            }
        }

        // No open trade
        Optional<TradeExecution> open =
                repo.findFirstBySymbolAndTimeframeAndStatus(
                        symbol, timeframe, "OPEN");

        if (open.isPresent()) return;

        // Cooldown
        Optional<TradeExecution> last =
                repo.findFirstBySymbolAndTimeframeOrderByEntryTimeDesc(
                        symbol, timeframe);

        if (last.isPresent()
                && last.get().getExitTime() != null
                && last.get().getExitTime()
                .isAfter(LocalDateTime.now()
                        .minusMinutes(COOLDOWN_MINUTES))) {
            return;
        }

        TradeExecution t = new TradeExecution();
        t.setSymbol(symbol);
        t.setTimeframe(timeframe);
        t.setTradeType(tradeType);
        t.setStatus("OPEN");

        t.setEntryPrice(analysis.getCurrentPrice());
        t.setEntryTime(LocalDateTime.now());
        t.setLastSignalTime(LocalDateTime.now());

        t.setTargetPrice(calcTarget(
                analysis.getCurrentPrice(), tradeType, symbol));

        t.setSlPrice(calcSL(
                analysis.getCurrentPrice(), tradeType, symbol));

        t.setLevelValue(ref.getLevelValue());
        t.setMethod(ref.getMethod());
        t.setStrength(ref.getStrength());
        t.setExplanation(analysis.getExplanation());

        repo.save(t);
        
        log.info("✅ Trade APPROVED: {} {} at {} | Target: {} | SL: {} | Strength: {} | Touches: {}", 
                tradeType, symbol, t.getEntryPrice(), t.getTargetPrice(), t.getSlPrice(), 
                ref.getStrength(), ref.getTouches());
    }

    /* ==================================================
       🔥 NEW: PROFITABILITY FILTER METHODS
       ================================================== */

    /**
     * IMPROVEMENT #1: Check if level strength is CRITICAL
     */
    private boolean checkLevelStrength(Level ref, String symbol, String timeframe, 
                                      String tradeType, LevelAnalysisResult analysis) {
        if (ref.getStrength() == null || !REQUIRED_STRENGTH.equals(ref.getStrength())) {
            String reason = "Level strength not " + REQUIRED_STRENGTH + " (actual: " + ref.getStrength() + ")";
            log.info("❌ {} rejected for {}/{} - {}", tradeType, symbol, timeframe, reason);
            saveRejectedTrade(symbol, timeframe, tradeType, analysis, ref, reason);
            return false;
        }
        return true;
    }

    /**
     * IMPROVEMENT #2: Check if level has sufficient touch count
     */
    private boolean checkTouchCount(Level ref, String symbol, String timeframe, 
                                    String tradeType, LevelAnalysisResult analysis) {
        if (ref.getTouches() == null || ref.getTouches() < MIN_TOUCH_COUNT) {
            String reason = "Insufficient touches: " + ref.getTouches() + " (min: " + MIN_TOUCH_COUNT + ")";
            log.info("❌ {} rejected for {}/{} - {}", tradeType, symbol, timeframe, reason);
            saveRejectedTrade(symbol, timeframe, tradeType, analysis, ref, reason);
            return false;
        }
        return true;
    }

    /**
     * IMPROVEMENT #3: Check distance from level
     */
    private boolean checkDistanceFromLevel(Level ref, BigDecimal currentPrice, 
                                          String symbol, String timeframe, String tradeType, 
                                          LevelAnalysisResult analysis) {
        BigDecimal distance = currentPrice.subtract(ref.getLevelValue()).abs();
        BigDecimal distancePercent = distance.divide(ref.getLevelValue(), 4, RoundingMode.HALF_UP)
                                            .multiply(new BigDecimal("100"));
        
        if (distancePercent.compareTo(MAX_DISTANCE_FROM_LEVEL_PERCENT) > 0) {
            String reason = "Too far from level: " + distancePercent + "% (max: " + MAX_DISTANCE_FROM_LEVEL_PERCENT + "%)";
            log.info("❌ {} rejected for {}/{} - {}", tradeType, symbol, timeframe, reason);
            saveRejectedTrade(symbol, timeframe, tradeType, analysis, ref, reason);
            return false;
        }
        return true;
    }

    /**
     * IMPROVEMENT #4: Check risk-reward ratio
     */
    private boolean checkRiskReward(String symbol, String timeframe, BigDecimal currentPrice, 
                                   String tradeType, LevelAnalysisResult analysis, Level ref) {
        BigDecimal target = SYMBOL_SILVERM.equals(symbol) ? SILVER_TARGET : NIFTY_TARGET;
        BigDecimal sl = SYMBOL_SILVERM.equals(symbol) ? SILVER_SL : NIFTY_SL;
        
        BigDecimal riskRewardRatio = target.divide(sl, 2, RoundingMode.HALF_UP);
        
        if (riskRewardRatio.compareTo(MIN_RISK_REWARD_RATIO) < 0) {
            String reason = "Risk-reward too low: " + riskRewardRatio + ":1 (min: " + MIN_RISK_REWARD_RATIO + ":1)";
            log.info("❌ {} rejected for {}/{} - {}", tradeType, symbol, timeframe, reason);
            saveRejectedTrade(symbol, timeframe, tradeType, analysis, ref, reason);
            return false;
        }
        return true;
    }

    /**
     * IMPROVEMENT #5: Check historical win rate for this level
     */
    private boolean checkHistoricalWinRate(Level ref, String symbol, String timeframe, 
                                          String tradeType, LevelAnalysisResult analysis) {
        try {
            // Find past trades at this specific level (within 0.1% tolerance)
            BigDecimal levelValue = ref.getLevelValue();
            BigDecimal tolerance = levelValue.multiply(new BigDecimal("0.001")); // 0.1%
            BigDecimal lowerBound = levelValue.subtract(tolerance);
            BigDecimal upperBound = levelValue.add(tolerance);
            
            List<TradeExecution> pastTrades = repo.findBySymbolAndStatusAndLevelValueBetween(
                    symbol, "CLOSED", lowerBound, upperBound);
            
            if (pastTrades.size() < MIN_HISTORICAL_TRADES_REQUIRED) {
                // Not enough data, allow trade
                return true;
            }
            
            long wins = pastTrades.stream()
                    .filter(t -> t.getPnl() != null && t.getPnl().compareTo(BigDecimal.ZERO) > 0)
                    .count();
            
            BigDecimal winRate = BigDecimal.valueOf(wins * 100.0 / pastTrades.size())
                    .setScale(2, RoundingMode.HALF_UP);
            
            if (winRate.compareTo(MIN_HISTORICAL_WIN_RATE) < 0) {
                String reason = "Low historical win rate: " + winRate + "% at level " + levelValue + 
                              " (trades: " + pastTrades.size() + ", wins: " + wins + ")";
                log.info("❌ {} rejected for {}/{} - {}", tradeType, symbol, timeframe, reason);
                saveRejectedTrade(symbol, timeframe, tradeType, analysis, ref, reason);
                return false;
            }
            
            log.info("✅ Level {} has good history: {}% win rate ({} trades)", 
                    levelValue, winRate, pastTrades.size());
            
        } catch (Exception e) {
            log.error("Error checking historical win rate: {}", e.getMessage());
            // On error, allow trade
        }
        
        return true;
    }

    /**
     * IMPROVEMENT #6: Check for multiple level confluence
     */
    private boolean checkConfluence(Level ref, String symbol, String timeframe, 
                                   String tradeType, LevelAnalysisResult analysis) {
        try {
            // Get all levels of same type (support for BUY, resistance for SELL)
            List<Level> levels;
            if ("BUY".equals(tradeType)) {
                levels = levelRepo.findBySymbolAndTimeframeAndSeqGreaterThan(symbol, timeframe, 0);
            } else {
                levels = levelRepo.findBySymbolAndTimeframeAndSeqLessThan(symbol, timeframe, 0);
            }
            
            BigDecimal refValue = ref.getLevelValue();
            long nearbyLevels = levels.stream()
                    .filter(l -> {
                        BigDecimal diff = l.getLevelValue().subtract(refValue).abs();
                        BigDecimal percentDiff = diff.divide(refValue, 4, RoundingMode.HALF_UP)
                                                     .multiply(new BigDecimal("100"));
                        return percentDiff.compareTo(CONFLUENCE_PROXIMITY_PERCENT) <= 0;
                    })
                    .count();
            
            if (nearbyLevels < MIN_CONFLUENCE_LEVELS) {
                String reason = "No confluence: only " + nearbyLevels + " level(s) nearby (min: " + MIN_CONFLUENCE_LEVELS + ")";
                log.info("❌ {} rejected for {}/{} - {}", tradeType, symbol, timeframe, reason);
                saveRejectedTrade(symbol, timeframe, tradeType, analysis, ref, reason);
                return false;
            }
            
            log.info("✅ Good confluence: {} levels within {}% of {}", 
                    nearbyLevels, CONFLUENCE_PROXIMITY_PERCENT, refValue);
            
        } catch (Exception e) {
            log.error("Error checking confluence: {}", e.getMessage());
            // On error, allow trade
        }
        
        return true;
    }

    /**
     * IMPROVEMENT #7: Time-based filtering
     */
    private boolean checkTimeFilter(String symbol, String timeframe, String tradeType, 
                                    LevelAnalysisResult analysis, Level ref) {
        LocalTime now = LocalTime.now();
        
        // Avoid first 15 minutes (9:15 - 9:30)
        if (now.isAfter(LocalTime.of(9, 15)) && now.isBefore(LocalTime.of(9, 30))) {
            String reason = "Opening range period (9:15-9:30) - high volatility";
            log.info("❌ {} rejected for {}/{} - {}", tradeType, symbol, timeframe, reason);
            saveRejectedTrade(symbol, timeframe, tradeType, analysis, ref, reason);
            return false;
        }
        
        // Avoid last 30 minutes (3:00 PM - 3:30 PM)
        if (now.isAfter(LocalTime.of(15, 0))) {
            String reason = "Near market close (after 3:00 PM) - erratic moves";
            log.info("❌ {} rejected for {}/{} - {}", tradeType, symbol, timeframe, reason);
            saveRejectedTrade(symbol, timeframe, tradeType, analysis, ref, reason);
            return false;
        }
        
        return true;
    }

    /**
     * IMPROVEMENT #8: Losing streak protection
     */
    private boolean checkLosingStreak(String symbol, String timeframe, String tradeType, 
                                     LevelAnalysisResult analysis, Level ref) {
        try {
            List<TradeExecution> recentTrades = repo.findTop5BySymbolAndStatusOrderByExitTimeDesc(
                    symbol, "CLOSED");
            
            long consecutiveLosses = recentTrades.stream()
                    .takeWhile(t -> t.getPnl() != null && t.getPnl().compareTo(BigDecimal.ZERO) < 0)
                    .count();
            
            if (consecutiveLosses >= MAX_CONSECUTIVE_LOSSES) {
                String reason = "Losing streak: " + consecutiveLosses + " consecutive losses (max: " + MAX_CONSECUTIVE_LOSSES + ")";
                log.info("❌ {} rejected for {}/{} - {}", tradeType, symbol, timeframe, reason);
                saveRejectedTrade(symbol, timeframe, tradeType, analysis, ref, reason);
                return false;
            }
            
        } catch (Exception e) {
            log.error("Error checking losing streak: {}", e.getMessage());
            // On error, allow trade
        }
        
        return true;
    }

    /**
     * IMPROVEMENT #9: Daily profit/loss limits (SYMBOL-SPECIFIC)
     */
    private boolean checkDailyLimits(String symbol, String timeframe, String tradeType, 
                                    LevelAnalysisResult analysis, Level ref) {
        try {
            LocalDateTime todayStart = LocalDate.now().atStartOfDay();
            List<TradeExecution> todayTrades = repo.findBySymbolAndStatusAndEntryTimeBetween(
                    symbol, "CLOSED", todayStart, LocalDateTime.now());
            
            BigDecimal todayPnL = todayTrades.stream()
                    .filter(t -> t.getPnl() != null)
                    .map(TradeExecution::getPnl)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            // 🔥 Get symbol-specific limits
            BigDecimal profitTarget = SYMBOL_SILVERM.equals(symbol) 
                    ? SILVER_DAILY_PROFIT_TARGET 
                    : NIFTY_DAILY_PROFIT_TARGET;
            
            BigDecimal lossLimit = SYMBOL_SILVERM.equals(symbol) 
                    ? SILVER_DAILY_LOSS_LIMIT 
                    : NIFTY_DAILY_LOSS_LIMIT;
            
            // Check profit target
            if (todayPnL.compareTo(profitTarget) >= 0) {
                String reason = "Daily profit target reached: " + todayPnL + " points (target: " + profitTarget + " for " + symbol + ")";
                log.info("❌ {} rejected for {}/{} - {}", tradeType, symbol, timeframe, reason);
                saveRejectedTrade(symbol, timeframe, tradeType, analysis, ref, reason);
                return false;
            }
            
            // Check loss limit
            if (todayPnL.compareTo(lossLimit) <= 0) {
                String reason = "Daily loss limit reached: " + todayPnL + " points (limit: " + lossLimit + " for " + symbol + ")";
                log.info("❌ {} rejected for {}/{} - {}", tradeType, symbol, timeframe, reason);
                saveRejectedTrade(symbol, timeframe, tradeType, analysis, ref, reason);
                return false;
            }
            
            log.debug("Today's PnL for {}: {} points (within limits: {} to {})", 
                    symbol, todayPnL, lossLimit, profitTarget);
            
        } catch (Exception e) {
            log.error("Error checking daily limits: {}", e.getMessage());
            // On error, allow trade
        }
        
        return true;
    }

    /* ==================================================
       IMMEDIATE LEVEL CHECK (EXISTING)
       ================================================== */

    private boolean hasImmediateResistance(
            String symbol, 
            String timeframe, 
            BigDecimal currentPrice, 
            BigDecimal targetPrice) {
        
        try {
            List<Level> resistances = levelRepo.findBySymbolAndTimeframeAndSeqLessThan(
                    symbol, timeframe, 0);
            
            if (resistances == null || resistances.isEmpty()) {
                return false;
            }
            
            for (Level res : resistances) {
                BigDecimal resValue = res.getLevelValue();
                
                if (resValue.compareTo(currentPrice) > 0 
                        && resValue.compareTo(targetPrice) <= 0) {
                    
                    log.info("Resistance at {} (seq={}, method={}) blocks BUY from {} to {}", 
                            resValue, res.getSeq(), res.getMethod(), currentPrice, targetPrice);
                    return true;
                }
            }
            
            return false;
            
        } catch (Exception e) {
            log.error("Error checking resistance: {}", e.getMessage());
            return false;
        }
    }

    private boolean hasImmediateSupport(
            String symbol, 
            String timeframe, 
            BigDecimal currentPrice, 
            BigDecimal targetPrice) {
        
        try {
            List<Level> supports = levelRepo.findBySymbolAndTimeframeAndSeqGreaterThan(
                    symbol, timeframe, 0);
            
            if (supports == null || supports.isEmpty()) {
                return false;
            }
            
            for (Level sup : supports) {
                BigDecimal supValue = sup.getLevelValue();
                
                if (supValue.compareTo(currentPrice) < 0 
                        && supValue.compareTo(targetPrice) >= 0) {
                    
                    log.info("Support at {} (seq={}, method={}) blocks SELL from {} to {}", 
                            supValue, sup.getSeq(), sup.getMethod(), currentPrice, targetPrice);
                    return true;
                }
            }
            
            return false;
            
        } catch (Exception e) {
            log.error("Error checking support: {}", e.getMessage());
            return false;
        }
    }

    private void saveRejectedTrade(
            String symbol,
            String timeframe,
            String tradeType,
            LevelAnalysisResult analysis,
            Level ref,
            String rejectionReason) {
        
        try {
            TradeExecution t = new TradeExecution();
            t.setSymbol(symbol);
            t.setTimeframe(timeframe);
            t.setTradeType(tradeType);
            t.setStatus("REJECTED");
            
            t.setEntryPrice(analysis.getCurrentPrice());
            t.setEntryTime(LocalDateTime.now());
            t.setLastSignalTime(LocalDateTime.now());
            
            t.setTargetPrice(calcTarget(analysis.getCurrentPrice(), tradeType, symbol));
            t.setSlPrice(calcSL(analysis.getCurrentPrice(), tradeType, symbol));
            
            t.setLevelValue(ref.getLevelValue());
            t.setMethod(ref.getMethod());
            t.setStrength(ref.getStrength());
            t.setExplanation(analysis.getExplanation());
            
            t.setExitReason(rejectionReason);
            
            repo.save(t);
            
            log.debug("Rejected trade saved to DB: {} {} at {} - Reason: {}", 
                    tradeType, symbol, t.getEntryPrice(), rejectionReason);
            
        } catch (Exception e) {
            log.error("Error saving rejected trade: {}", e.getMessage(), e);
        }
    }

    /* ==================================================
       EXIT + TRAILING SL - ENHANCED WITH PARTIAL PROFIT
       ================================================== */

    @Transactional
    public void monitorTrade(
            String symbol,
            String timeframe,
            BigDecimal ltp) {

        Optional<TradeExecution> open =
                repo.findFirstBySymbolAndTimeframeAndStatus(
                        symbol, timeframe, "OPEN");

        if (open.isEmpty()) return;

        TradeExecution t = open.get();
        
        if ("REJECTED".equals(t.getStatus())) {
            return;
        }

        // 🔥 IMPROVEMENT #10: Partial Profit Booking
        if (ENABLE_PARTIAL_PROFIT) {
            applyPartialProfit(t, ltp);
        }

        if (ENABLE_TRAILING_SL) {
            applyTrailingSL(t, ltp);
        }

        if ("BUY".equals(t.getTradeType())) {
            if (ltp.compareTo(t.getTargetPrice()) >= 0)
                close(t, ltp, "TARGET");
            else if (ltp.compareTo(t.getSlPrice()) <= 0)
                close(t, ltp, "SL");
        }

        if ("SELL".equals(t.getTradeType())) {
            if (ltp.compareTo(t.getTargetPrice()) <= 0)
                close(t, ltp, "TARGET");
            else if (ltp.compareTo(t.getSlPrice()) >= 0)
                close(t, ltp, "SL");
        }
    }

    /**
     * 🔥 IMPROVEMENT #10: Partial profit booking at 50% target
     */
    private void applyPartialProfit(TradeExecution t, BigDecimal ltp) {
        try {
            // Check if we already booked partial profit
            if (t.getExplanation() != null && t.getExplanation().contains("PARTIAL_PROFIT")) {
                return;
            }
            
            BigDecimal target = SYMBOL_SILVERM.equals(t.getSymbol()) ? SILVER_TARGET : NIFTY_TARGET;
            BigDecimal halfTarget = target.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
            
            BigDecimal partialTargetPrice;
            boolean partialTargetHit = false;
            
            if ("BUY".equals(t.getTradeType())) {
                partialTargetPrice = t.getEntryPrice().add(halfTarget);
                partialTargetHit = ltp.compareTo(partialTargetPrice) >= 0;
            } else {
                partialTargetPrice = t.getEntryPrice().subtract(halfTarget);
                partialTargetHit = ltp.compareTo(partialTargetPrice) <= 0;
            }
            
            if (partialTargetHit) {
                // Move SL to breakeven
                t.setSlPrice(t.getEntryPrice());
                
                // Mark partial profit taken
                String explanation = (t.getExplanation() != null ? t.getExplanation() : "") 
                                   + " | PARTIAL_PROFIT at " + ltp;
                t.setExplanation(explanation);
                
                repo.save(t);
                
                log.info("💰 Partial profit booked for {} {} at {} | SL moved to breakeven {}", 
                        t.getTradeType(), t.getSymbol(), ltp, t.getEntryPrice());
            }
            
        } catch (Exception e) {
            log.error("Error applying partial profit: {}", e.getMessage());
        }
    }

    /* ==================================================
       CLOSE + PnL
       ================================================== */

    private void close(TradeExecution t, BigDecimal exitPrice, String reason) {

        t.setStatus("CLOSED");
        t.setExitPrice(exitPrice);
        t.setExitTime(LocalDateTime.now());
        t.setExitReason(reason);

        BigDecimal pnl =
                "BUY".equals(t.getTradeType())
                        ? exitPrice.subtract(t.getEntryPrice())
                        : t.getEntryPrice().subtract(exitPrice);

        t.setPnl(pnl);

        repo.save(t);
        
        log.info("🔔 Trade closed: {} {} | Entry: {} | Exit: {} | PnL: {} | Reason: {}", 
                t.getTradeType(), t.getSymbol(), t.getEntryPrice(), exitPrice, pnl, reason);
    }

    /* ==================================================
       HELPERS
       ================================================== */

    public boolean isMethodAllowed(String method) {
        return ("PRICE_ACTION".equals(method) && ENABLE_PRICE_ACTION)
                || ("FIBO".equals(method) && ENABLE_FIBO);
    }

    private BigDecimal calcTarget(
            BigDecimal price,
            String tradeType,
            String symbol) {

        BigDecimal target =
                SYMBOL_SILVERM.equals(symbol)
                        ? SILVER_TARGET
                        : NIFTY_TARGET;

        return "BUY".equals(tradeType)
                ? price.add(target)
                : price.subtract(target);
    }

    private BigDecimal calcSL(
            BigDecimal price,
            String tradeType,
            String symbol) {

        BigDecimal sl =
                SYMBOL_SILVERM.equals(symbol)
                        ? SILVER_SL
                        : NIFTY_SL;

        return "BUY".equals(tradeType)
                ? price.subtract(sl)
                : price.add(sl);
    }

    private void applyTrailingSL(TradeExecution t, BigDecimal ltp) {

        BigDecimal step =
                SYMBOL_SILVERM.equals(t.getSymbol())
                        ? SILVER_TRAIL_SL
                        : NIFTY_TRAIL_SL;

        if ("BUY".equals(t.getTradeType())) {
            BigDecimal newSl = ltp.subtract(step);
            if (newSl.compareTo(t.getSlPrice()) > 0) {
                t.setSlPrice(newSl);
                repo.save(t);
            }
        }

        if ("SELL".equals(t.getTradeType())) {
            BigDecimal newSl = ltp.add(step);
            if (newSl.compareTo(t.getSlPrice()) < 0) {
                t.setSlPrice(newSl);
                repo.save(t);
            }
        }
    }
}