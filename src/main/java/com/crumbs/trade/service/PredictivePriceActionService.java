package com.crumbs.trade.service;

import com.crumbs.trade.dto.FibonacciLevel;
import com.crumbs.trade.dto.PriceActionResult;
import com.crumbs.trade.entity.PricesIndex;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PredictivePriceActionService {

    // ==================== ENUMS ====================
    public enum Signal { BUY, SELL, HOLD }
    public enum Trend { STRONG_UPTREND, UPTREND, SIDEWAYS, DOWNTREND, STRONG_DOWNTREND }
    public enum MarketPhase { ACCUMULATION, MARKUP, DISTRIBUTION, MARKDOWN, CONSOLIDATION }
    public enum PricePosition { NEAR_SUPPORT, NEAR_RESISTANCE, BETWEEN_LEVELS, AT_BREAKOUT, IN_NO_MANS_LAND }

    // ==================== INNER CLASSES ====================
    private static class MarketContext {
        Trend trend;
        MarketPhase phase;
        PricePosition position;
        BigDecimal momentum; // -100 to +100
        BigDecimal volatility;
        List<LevelContext> supportLevels = new ArrayList<>();
        List<LevelContext> resistanceLevels = new ArrayList<>();
        boolean volumeIncreasing;
        int consecutiveBullishCandles;
        int consecutiveBearishCandles;
        boolean recentBreakout;
        String marketStructure; // "HH/HL", "LL/LH", "Range"
    }

    private static class LevelContext {
        BigDecimal level;
        int touches;
        int rejections;
        int successfulBreakouts;
        BigDecimal lastTouchPrice;
        int candlesSinceLastTouch = Integer.MAX_VALUE;
        boolean volumeConfirmed;
        String strength; // "WEAK", "MODERATE", "STRONG", "CRITICAL"
        boolean recentlyTested;
        boolean freshLevel;
    }

    private static class PriceBehavior {
        String approaching;
        int candlesSinceApproach;
        boolean slowingDown;
        boolean accelerating;
        boolean showingRejection;
        boolean showingAcceptance;
        BigDecimal recentStrength;
        String candlePattern;
    }

    private static class PredictiveSignal {
        Signal signal;
        String confidence;
        String reason;
        BigDecimal projectedTarget;
        BigDecimal stopLoss;
        String scenario;
    }

    // ==================== MAIN ANALYSIS ====================
    public PriceActionResult analyzePredictive(BigDecimal currentPrice, List<PricesIndex> candles, String timeframe) {
        log.info("🧠 Starting PREDICTIVE analysis for price: {}", currentPrice);
        
        PriceActionResult result = new PriceActionResult();
        result.setCurrentPrice(currentPrice);

        if (candles == null || candles.size() < 30) {
            return buildInsufficientDataResult(currentPrice);
        }

        // STEP 1: Build Market Context
        MarketContext context = buildMarketContext(currentPrice, candles);
        
        // STEP 2: Analyze Recent Price Behavior
        PriceBehavior behavior = analyzePriceBehavior(currentPrice, candles, context);
        
        // STEP 3: Generate Predictive Signal
        PredictiveSignal signal = generatePredictiveSignal(currentPrice, context, behavior);
        
        // STEP 4: Populate Result
        populateResult(result, context, behavior, signal);
        
        log.info("📊 Final Prediction: {} (Confidence: {})", signal.signal, signal.confidence);
        log.info("📈 Reason: {}", signal.reason);
        
        return result;
    }

    // ==================== BUILD MARKET CONTEXT ====================
    private MarketContext buildMarketContext(BigDecimal currentPrice, List<PricesIndex> candles) {
        MarketContext ctx = new MarketContext();
        
        // Detect trend with strength
        ctx.trend = detectTrendWithStrength(candles);
        
        // Detect market phase
        ctx.phase = detectMarketPhase(candles);
        
        // Build intelligent support/resistance levels
        ctx.supportLevels = buildIntelligentLevels(candles, currentPrice, true);
        ctx.resistanceLevels = buildIntelligentLevels(candles, currentPrice, false);
        
        // Determine price position
        ctx.position = determinePricePosition(currentPrice, ctx.supportLevels, ctx.resistanceLevels);
        
        // Calculate momentum (-100 to +100)
        ctx.momentum = calculateMomentum(candles);
        
        // Calculate volatility
        ctx.volatility = calculateVolatility(candles);
        
        // Volume analysis
        ctx.volumeIncreasing = isVolumeIncreasing(candles);
        
        // Consecutive candles
        ctx.consecutiveBullishCandles = countConsecutiveBullish(candles);
        ctx.consecutiveBearishCandles = countConsecutiveBearish(candles);
        
        // Breakout detection
        ctx.recentBreakout = detectRecentBreakout(candles, ctx.supportLevels, ctx.resistanceLevels);
        
        // Market structure
        ctx.marketStructure = analyzeMarketStructure(candles);
        
        return ctx;
    }

    // ==================== BUILD INTELLIGENT LEVELS ====================
    private List<LevelContext> buildIntelligentLevels(List<PricesIndex> candles, BigDecimal currentPrice, boolean isSupport) {
        Map<BigDecimal, LevelContext> levelMap = new HashMap<>();
        BigDecimal tolerance = currentPrice.multiply(BigDecimal.valueOf(0.002));
        
        for (int i = 0; i < candles.size(); i++) {
            PricesIndex candle = candles.get(i);
            BigDecimal level = isSupport ? candle.getLow() : candle.getHigh();
            int candlesAgo = candles.size() - 1 - i;
            
            // Find or create level
            LevelContext levelCtx = findOrCreateLevel(levelMap, level, tolerance);
            levelCtx.touches++;
            levelCtx.candlesSinceLastTouch = Math.min(levelCtx.candlesSinceLastTouch, candlesAgo);
            
            // Check if price rejected this level
            if (i < candles.size() - 1) {
                PricesIndex nextCandle = candles.get(i + 1);
                boolean rejected = isSupport ? 
                    (nextCandle.getClose().compareTo(level) > 0) : 
                    (nextCandle.getClose().compareTo(level) < 0);
                
                if (rejected) {
                    levelCtx.rejections++;
                }
            }
            
            // Check for breakouts
            if (i > 0) {
                PricesIndex prevCandle = candles.get(i - 1);
                boolean brokeThrough = isSupport ?
                    (prevCandle.getLow().compareTo(level) > 0 && candle.getLow().compareTo(level) < 0) :
                    (prevCandle.getHigh().compareTo(level) < 0 && candle.getHigh().compareTo(level) > 0);
                
                if (brokeThrough) {
                    levelCtx.successfulBreakouts++;
                }
            }
            
            // Volume confirmation
            if (candle.getVolume() != null) {
                BigDecimal avgVol = calculateAverageVolume(candles);
                if (candle.getVolume().compareTo(avgVol.multiply(BigDecimal.valueOf(1.2))) > 0) {
                    levelCtx.volumeConfirmed = true;
                }
            }
        }
        
        // Calculate level strength
        for (LevelContext level : levelMap.values()) {
            level.strength = calculateLevelStrength(level);
            level.recentlyTested = level.candlesSinceLastTouch < 10;
            level.freshLevel = level.successfulBreakouts == 0;
        }
        
        // Sort by distance from current price
        return levelMap.values().stream()
            .filter(l -> l.touches >= 2)
            .sorted(Comparator.comparing(l -> currentPrice.subtract(l.level).abs()))
            .limit(5)
            .collect(Collectors.toList());
    }

    private LevelContext findOrCreateLevel(Map<BigDecimal, LevelContext> map, BigDecimal level, BigDecimal tolerance) {
        for (Map.Entry<BigDecimal, LevelContext> entry : map.entrySet()) {
            if (entry.getKey().subtract(level).abs().compareTo(tolerance) <= 0) {
                return entry.getValue();
            }
        }
        
        LevelContext newLevel = new LevelContext();
        newLevel.level = level;
        newLevel.touches = 0;
        newLevel.rejections = 0;
        newLevel.successfulBreakouts = 0;
        newLevel.candlesSinceLastTouch = Integer.MAX_VALUE;
        newLevel.volumeConfirmed = false;
        map.put(level, newLevel);
        return newLevel;
    }

    private String calculateLevelStrength(LevelContext level) {
        int score = 0;
        
        // More touches = stronger
        score += level.touches * 10;
        
        // More rejections = stronger
        score += level.rejections * 15;
        
        // Fewer breakouts = stronger
        score -= level.successfulBreakouts * 20;
        
        // Volume confirmed = stronger
        if (level.volumeConfirmed) score += 20;
        
        // Recently tested = more relevant
        if (level.recentlyTested) score += 10;
        
        // Fresh level = potentially stronger
        if (level.freshLevel) score += 15;
        
        if (score >= 60) return "CRITICAL";
        if (score >= 40) return "STRONG";
        if (score >= 20) return "MODERATE";
        return "WEAK";
    }

    // ==================== PRICE BEHAVIOR ANALYSIS ====================
    private PriceBehavior analyzePriceBehavior(BigDecimal currentPrice, List<PricesIndex> candles, MarketContext ctx) {
        PriceBehavior behavior = new PriceBehavior();
        
        // Get last 10 candles for recent behavior
        List<PricesIndex> recent = candles.subList(Math.max(0, candles.size() - 10), candles.size());
        
        // Determine what price is approaching
        if (ctx.position == PricePosition.NEAR_SUPPORT && !ctx.supportLevels.isEmpty()) {
            LevelContext nearest = ctx.supportLevels.get(0);
            boolean comingFromAbove = recent.get(0).getClose().compareTo(nearest.level) > 0;
            behavior.approaching = comingFromAbove ? "SUPPORT_FROM_ABOVE" : "BOUNCING_OFF_SUPPORT";
        } else if (ctx.position == PricePosition.NEAR_RESISTANCE && !ctx.resistanceLevels.isEmpty()) {
            LevelContext nearest = ctx.resistanceLevels.get(0);
            boolean comingFromBelow = recent.get(0).getClose().compareTo(nearest.level) < 0;
            behavior.approaching = comingFromBelow ? "RESISTANCE_FROM_BELOW" : "REJECTING_AT_RESISTANCE";
        } else {
            behavior.approaching = "BETWEEN_LEVELS";
        }
        
        // Check if slowing down (decreasing candle bodies)
        List<BigDecimal> bodySizes = recent.stream()
            .map(c -> c.getClose().subtract(c.getOpen()).abs())
            .collect(Collectors.toList());
        
        if (bodySizes.size() >= 3) {
            BigDecimal recent3Avg = bodySizes.subList(bodySizes.size() - 3, bodySizes.size())
                .stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(3), RoundingMode.HALF_UP);
            
            BigDecimal earlier3Avg = bodySizes.subList(0, Math.min(3, bodySizes.size()))
                .stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(3), RoundingMode.HALF_UP);
            
            behavior.slowingDown = recent3Avg.compareTo(earlier3Avg.multiply(BigDecimal.valueOf(0.7))) < 0;
            behavior.accelerating = recent3Avg.compareTo(earlier3Avg.multiply(BigDecimal.valueOf(1.3))) > 0;
        }
        
        // Check for rejection (long wicks)
        PricesIndex lastCandle = recent.get(recent.size() - 1);
        BigDecimal body = lastCandle.getClose().subtract(lastCandle.getOpen()).abs();
        BigDecimal upperWick = lastCandle.getHigh().subtract(lastCandle.getClose().max(lastCandle.getOpen()));
        BigDecimal lowerWick = lastCandle.getClose().min(lastCandle.getOpen()).subtract(lastCandle.getLow());
        
        behavior.showingRejection = (upperWick.compareTo(body.multiply(BigDecimal.valueOf(2))) > 0) ||
                                    (lowerWick.compareTo(body.multiply(BigDecimal.valueOf(2))) > 0);
        
        // Calculate recent strength
        behavior.recentStrength = bodySizes.stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(bodySizes.size()), RoundingMode.HALF_UP);
        
        // Identify candle patterns
        behavior.candlePattern = identifyCandlePattern(lastCandle);
        
        return behavior;
    }

    private String identifyCandlePattern(PricesIndex candle) {
        BigDecimal body = candle.getClose().subtract(candle.getOpen()).abs();
        BigDecimal range = candle.getHigh().subtract(candle.getLow());
        
        if (range.compareTo(BigDecimal.ZERO) == 0) return "NORMAL";
        
        BigDecimal upperWick = candle.getHigh().subtract(candle.getClose().max(candle.getOpen()));
        BigDecimal lowerWick = candle.getClose().min(candle.getOpen()).subtract(candle.getLow());
        
        // Doji
        if (body.compareTo(range.multiply(BigDecimal.valueOf(0.1))) < 0) {
            return "DOJI";
        }
        
        // Hammer (bullish)
        if (lowerWick.compareTo(body.multiply(BigDecimal.valueOf(2))) > 0 &&
            upperWick.compareTo(body.multiply(BigDecimal.valueOf(0.3))) < 0 &&
            candle.getClose().compareTo(candle.getOpen()) > 0) {
            return "HAMMER";
        }
        
        // Shooting Star (bearish)
        if (upperWick.compareTo(body.multiply(BigDecimal.valueOf(2))) > 0 &&
            lowerWick.compareTo(body.multiply(BigDecimal.valueOf(0.3))) < 0 &&
            candle.getClose().compareTo(candle.getOpen()) < 0) {
            return "SHOOTING_STAR";
        }
        
        return "NORMAL";
    }

    // ==================== PREDICTIVE SIGNAL GENERATION ====================
    private PredictiveSignal generatePredictiveSignal(BigDecimal currentPrice, MarketContext ctx, PriceBehavior behavior) {
        PredictiveSignal signal = new PredictiveSignal();
        signal.signal = Signal.HOLD;
        signal.confidence = "LOW";
        signal.reason = "";
        signal.scenario = "";
        
        // SCENARIO 1: Price approaching strong support with momentum slowing
        if (behavior.approaching.equals("SUPPORT_FROM_ABOVE") && 
            !ctx.supportLevels.isEmpty() &&
            (ctx.supportLevels.get(0).strength.equals("STRONG") || ctx.supportLevels.get(0).strength.equals("CRITICAL")) &&
            behavior.slowingDown) {
            
            signal.signal = Signal.BUY;
            signal.confidence = "HIGH";
            signal.reason = String.format(
                "Price approaching tested support (%.2f) with %d rejections. Momentum slowing = likely bounce. %s",
                ctx.supportLevels.get(0).level,
                ctx.supportLevels.get(0).rejections,
                ctx.trend.name().contains("UP") ? "Uptrend supports bounce." : ""
            );
            signal.stopLoss = ctx.supportLevels.get(0).level.multiply(BigDecimal.valueOf(0.998));
            signal.projectedTarget = !ctx.resistanceLevels.isEmpty() ? ctx.resistanceLevels.get(0).level : null;
            signal.scenario = "Expect bounce from support → rally to resistance";
        }
        
        // SCENARIO 2: Price at support showing rejection (hammer/long wick)
        else if (ctx.position == PricePosition.NEAR_SUPPORT &&
                 behavior.showingRejection &&
                 (behavior.candlePattern.equals("HAMMER") || behavior.candlePattern.equals("DOJI")) &&
                 ctx.momentum.compareTo(BigDecimal.valueOf(-30)) > 0) {
            
            signal.signal = Signal.BUY;
            signal.confidence = "VERY_HIGH";
            signal.reason = String.format(
                "Strong rejection at support! %s pattern + rejection wick. Buyers defending level.",
                behavior.candlePattern
            );
            signal.stopLoss = ctx.supportLevels.get(0).level.multiply(BigDecimal.valueOf(0.997));
            signal.projectedTarget = !ctx.resistanceLevels.isEmpty() ? ctx.resistanceLevels.get(0).level : null;
            signal.scenario = "Strong bounce expected → targets next resistance";
        }
        
        // SCENARIO 3: Price breaking resistance with volume and momentum
        else if (ctx.position == PricePosition.AT_BREAKOUT &&
                 !ctx.resistanceLevels.isEmpty() &&
                 ctx.resistanceLevels.get(0).level.compareTo(currentPrice) < 0 &&
                 ctx.volumeIncreasing &&
                 ctx.momentum.compareTo(BigDecimal.valueOf(40)) > 0 &&
                 behavior.accelerating) {
            
            signal.signal = Signal.BUY;
            signal.confidence = "HIGH";
            signal.reason = String.format(
                "Breakout above resistance (%.2f) with strong volume and momentum (+%.0f). Trend continuation likely.",
                ctx.resistanceLevels.get(0).level,
                ctx.momentum
            );
            signal.stopLoss = ctx.resistanceLevels.get(0).level;
            signal.projectedTarget = ctx.resistanceLevels.size() > 1 ? ctx.resistanceLevels.get(1).level : null;
            signal.scenario = "Breakout → retest → continuation higher";
        }
        
        // SCENARIO 4: Price approaching resistance, losing momentum
        else if (behavior.approaching.equals("RESISTANCE_FROM_BELOW") &&
                 !ctx.resistanceLevels.isEmpty() &&
                 (ctx.resistanceLevels.get(0).strength.equals("STRONG") || ctx.resistanceLevels.get(0).strength.equals("CRITICAL")) &&
                 (behavior.slowingDown || ctx.momentum.compareTo(BigDecimal.valueOf(20)) < 0)) {
            
            signal.signal = Signal.SELL;
            signal.confidence = "MEDIUM";
            signal.reason = String.format(
                "Price approaching strong resistance (%.2f) with weakening momentum. %d previous rejections suggest reversal.",
                ctx.resistanceLevels.get(0).level,
                ctx.resistanceLevels.get(0).rejections
            );
            signal.stopLoss = ctx.resistanceLevels.get(0).level.multiply(BigDecimal.valueOf(1.002));
            signal.projectedTarget = !ctx.supportLevels.isEmpty() ? ctx.supportLevels.get(0).level : null;
            signal.scenario = "Expect rejection → pullback to support";
        }
        
        // SCENARIO 5: Price at resistance showing rejection
        else if (ctx.position == PricePosition.NEAR_RESISTANCE &&
                 behavior.showingRejection &&
                 behavior.candlePattern.equals("SHOOTING_STAR")) {
            
            signal.signal = Signal.SELL;
            signal.confidence = "HIGH";
            signal.reason = "Strong rejection at resistance! SHOOTING_STAR pattern + rejection wick. Sellers defending level.";
            signal.stopLoss = ctx.resistanceLevels.get(0).level.multiply(BigDecimal.valueOf(1.003));
            signal.projectedTarget = !ctx.supportLevels.isEmpty() ? ctx.supportLevels.get(0).level : null;
            signal.scenario = "Rejection confirmed → expect pullback to support";
        }
        
        // SCENARIO 6: Consolidation breakout detection
        else if (ctx.phase == MarketPhase.CONSOLIDATION &&
                 ctx.volatility.compareTo(BigDecimal.valueOf(0.5)) < 0 &&
                 (behavior.accelerating || ctx.volumeIncreasing)) {
            
            if (ctx.momentum.compareTo(BigDecimal.ZERO) > 0) {
                signal.signal = Signal.BUY;
                signal.reason = "Breaking out of consolidation with increasing volume. Momentum turning positive.";
                signal.confidence = "MEDIUM";
                signal.scenario = "Consolidation breakout → expect trending move";
            } else {
                signal.signal = Signal.SELL;
                signal.reason = "Breaking down from consolidation with increasing volume. Momentum turning negative.";
                signal.confidence = "MEDIUM";
                signal.scenario = "Consolidation breakdown → expect trending move lower";
            }
        }
        
        // SCENARIO 7: Trend continuation in between levels
        else if (ctx.position == PricePosition.BETWEEN_LEVELS) {
            if (ctx.trend == Trend.STRONG_UPTREND && ctx.momentum.compareTo(BigDecimal.valueOf(30)) > 0) {
                signal.signal = Signal.BUY;
                signal.confidence = "MEDIUM";
                signal.reason = "Strong uptrend continuation. No immediate obstacles. Momentum positive (+"+ctx.momentum.intValue()+").";
                signal.scenario = "Trend following → targets next resistance";
                signal.projectedTarget = !ctx.resistanceLevels.isEmpty() ? ctx.resistanceLevels.get(0).level : null;
            } else if (ctx.trend == Trend.STRONG_DOWNTREND && ctx.momentum.compareTo(BigDecimal.valueOf(-30)) < 0) {
                signal.signal = Signal.SELL;
                signal.confidence = "MEDIUM";
                signal.reason = "Strong downtrend continuation. No immediate support. Momentum negative ("+ctx.momentum.intValue()+").";
                signal.scenario = "Trend following → targets next support";
                signal.projectedTarget = !ctx.supportLevels.isEmpty() ? ctx.supportLevels.get(0).level : null;
            } else {
                signal.signal = Signal.HOLD;
                signal.reason = "Price between levels with unclear momentum. Wait for better setup.";
                signal.scenario = "Wait for price to reach a key level or show clear direction";
            }
        }
        
        // Default HOLD
        else {
            signal.signal = Signal.HOLD;
            signal.confidence = "LOW";
            signal.reason = "No clear setup. Price action not compelling at current levels.";
            signal.scenario = "Wait for: approach to key level OR momentum shift OR volume spike";
        }
        
        return signal;
    }

    // ==================== HELPER METHODS ====================
    
    private Trend detectTrendWithStrength(List<PricesIndex> candles) {
        if (candles.size() < 20) return Trend.SIDEWAYS;
        
        BigDecimal sma5 = calculateSMA(candles, 5);
        BigDecimal sma20 = calculateSMA(candles, 20);
        BigDecimal sma50 = calculateSMA(candles, Math.min(50, candles.size()));
        
        if (sma20.compareTo(BigDecimal.ZERO) == 0) return Trend.SIDEWAYS;
        
        BigDecimal diff = sma5.subtract(sma20);
        BigDecimal percentDiff = diff.divide(sma20, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        
        if (percentDiff.compareTo(BigDecimal.valueOf(2)) > 0 && sma20.compareTo(sma50) > 0) {
            return Trend.STRONG_UPTREND;
        } else if (percentDiff.compareTo(BigDecimal.valueOf(0.5)) > 0) {
            return Trend.UPTREND;
        } else if (percentDiff.compareTo(BigDecimal.valueOf(-2)) < 0 && sma20.compareTo(sma50) < 0) {
            return Trend.STRONG_DOWNTREND;
        } else if (percentDiff.compareTo(BigDecimal.valueOf(-0.5)) < 0) {
            return Trend.DOWNTREND;
        }
        
        return Trend.SIDEWAYS;
    }

    private MarketPhase detectMarketPhase(List<PricesIndex> candles) {
        Trend trend = detectTrendWithStrength(candles);
        
        if (trend == Trend.STRONG_UPTREND) return MarketPhase.MARKUP;
        if (trend == Trend.STRONG_DOWNTREND) return MarketPhase.MARKDOWN;
        if (trend == Trend.SIDEWAYS) {
            BigDecimal volatility = calculateVolatility(candles);
            return volatility.compareTo(BigDecimal.valueOf(1)) < 0 ? 
                MarketPhase.CONSOLIDATION : MarketPhase.DISTRIBUTION;
        }
        
        return MarketPhase.CONSOLIDATION;
    }

    private PricePosition determinePricePosition(BigDecimal currentPrice, 
                                                  List<LevelContext> supports, 
                                                  List<LevelContext> resistances) {
        if (supports.isEmpty() && resistances.isEmpty()) {
            return PricePosition.IN_NO_MANS_LAND;
        }
        
        BigDecimal tolerance = currentPrice.multiply(BigDecimal.valueOf(0.003));
        
        if (!supports.isEmpty() && currentPrice.subtract(supports.get(0).level).abs().compareTo(tolerance) <= 0) {
            return PricePosition.NEAR_SUPPORT;
        }
        
        if (!resistances.isEmpty() && resistances.get(0).level.subtract(currentPrice).abs().compareTo(tolerance) <= 0) {
            return PricePosition.NEAR_RESISTANCE;
        }
        
        // Check if recently broke a level
        if (!resistances.isEmpty() && currentPrice.compareTo(resistances.get(0).level) > 0 &&
            resistances.get(0).candlesSinceLastTouch < 5) {
            return PricePosition.AT_BREAKOUT;
        }
        
        return PricePosition.BETWEEN_LEVELS;
    }

    private BigDecimal calculateMomentum(List<PricesIndex> candles) {
        if (candles.size() < 10) return BigDecimal.ZERO;
        
        List<PricesIndex> recent = candles.subList(candles.size() - 10, candles.size());
        BigDecimal firstPrice = recent.get(0).getClose();
        BigDecimal lastPrice = recent.get(recent.size() - 1).getClose();
        
        if (firstPrice.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        
        BigDecimal change = lastPrice.subtract(firstPrice)
            .divide(firstPrice, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
        
        return change.multiply(BigDecimal.valueOf(10));
    }

    private BigDecimal calculateVolatility(List<PricesIndex> candles) {
        if (candles.size() < 10) return BigDecimal.ZERO;
        
        List<BigDecimal> changes = new ArrayList<>();
        for (int i = 1; i < candles.size(); i++) {
            BigDecimal change = candles.get(i).getClose()
                .subtract(candles.get(i-1).getClose())
                .abs();
            changes.add(change);
        }
        
        return changes.stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(changes.size()), RoundingMode.HALF_UP);
    }

    private boolean isVolumeIncreasing(List<PricesIndex> candles) {
        if (candles.size() < 10) return false;
        
        List<PricesIndex> recent = candles.subList(candles.size() - 5, candles.size());
        List<PricesIndex> earlier = candles.subList(candles.size() - 10, candles.size() - 5);
        
        BigDecimal recentAvg = recent.stream()
            .map(PricesIndex::getVolume)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(5), RoundingMode.HALF_UP);
        
        BigDecimal earlierAvg = earlier.stream()
            .map(PricesIndex::getVolume)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(5), RoundingMode.HALF_UP);
        
        return recentAvg.compareTo(earlierAvg.multiply(BigDecimal.valueOf(1.2))) > 0;
    }

    private int countConsecutiveBullish(List<PricesIndex> candles) {
        int count = 0;
        for (int i = candles.size() - 1; i >= 0; i--) {
            if (candles.get(i).getClose().compareTo(candles.get(i).getOpen()) > 0) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    private int countConsecutiveBearish(List<PricesIndex> candles) {
        int count = 0;
        for (int i = candles.size() - 1; i >= 0; i--) {
            if (candles.get(i).getClose().compareTo(candles.get(i).getOpen()) < 0) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    private boolean detectRecentBreakout(List<PricesIndex> candles, 
                                        List<LevelContext> supports, 
                                        List<LevelContext> resistances) {
        if (candles.size() < 5) return false;
        
        List<PricesIndex> recent = candles.subList(candles.size() - 5, candles.size());
        
        for (LevelContext resistance : resistances) {
            for (PricesIndex candle : recent) {
                if (candle.getClose().compareTo(resistance.level) > 0 &&
                    candle.getOpen().compareTo(resistance.level) < 0) {
                    return true;
                }
            }
        }
        
        for (LevelContext support : supports) {
            for (PricesIndex candle : recent) {
                if (candle.getClose().compareTo(support.level) < 0 &&
                    candle.getOpen().compareTo(support.level) > 0) {
                    return true;
                }
            }
        }
        
        return false;
    }

    private String analyzeMarketStructure(List<PricesIndex> candles) {
        if (candles.size() < 20) return "INSUFFICIENT_DATA";
        
        List<BigDecimal> highs = candles.stream().map(PricesIndex::getHigh).collect(Collectors.toList());
        List<BigDecimal> lows = candles.stream().map(PricesIndex::getLow).collect(Collectors.toList());
        
        // Find swing highs and lows
        List<Integer> swingHighIndices = new ArrayList<>();
        List<Integer> swingLowIndices = new ArrayList<>();
        
        for (int i = 2; i < candles.size() - 2; i++) {
            // Swing high
            if (highs.get(i).compareTo(highs.get(i-1)) > 0 &&
                highs.get(i).compareTo(highs.get(i-2)) > 0 &&
                highs.get(i).compareTo(highs.get(i+1)) > 0 &&
                highs.get(i).compareTo(highs.get(i+2)) > 0) {
                swingHighIndices.add(i);
            }
            
            // Swing low
            if (lows.get(i).compareTo(lows.get(i-1)) < 0 &&
                lows.get(i).compareTo(lows.get(i-2)) < 0 &&
                lows.get(i).compareTo(lows.get(i+1)) < 0 &&
                lows.get(i).compareTo(lows.get(i+2)) < 0) {
                swingLowIndices.add(i);
            }
        }
        
        if (swingHighIndices.size() < 2 || swingLowIndices.size() < 2) {
            return "RANGE";
        }
        
        // Check for HH/HL (Higher Highs, Higher Lows - Uptrend)
        boolean higherHighs = true;
        for (int i = 1; i < swingHighIndices.size(); i++) {
            if (highs.get(swingHighIndices.get(i)).compareTo(highs.get(swingHighIndices.get(i-1))) <= 0) {
                higherHighs = false;
                break;
            }
        }
        
        boolean higherLows = true;
        for (int i = 1; i < swingLowIndices.size(); i++) {
            if (lows.get(swingLowIndices.get(i)).compareTo(lows.get(swingLowIndices.get(i-1))) <= 0) {
                higherLows = false;
                break;
            }
        }
        
        if (higherHighs && higherLows) return "HH/HL_UPTREND";
        
        // Check for LL/LH (Lower Lows, Lower Highs - Downtrend)
        boolean lowerLows = true;
        for (int i = 1; i < swingLowIndices.size(); i++) {
            if (lows.get(swingLowIndices.get(i)).compareTo(lows.get(swingLowIndices.get(i-1))) >= 0) {
                lowerLows = false;
                break;
            }
        }
        
        boolean lowerHighs = true;
        for (int i = 1; i < swingHighIndices.size(); i++) {
            if (highs.get(swingHighIndices.get(i)).compareTo(highs.get(swingHighIndices.get(i-1))) >= 0) {
                lowerHighs = false;
                break;
            }
        }
        
        if (lowerLows && lowerHighs) return "LL/LH_DOWNTREND";
        
        return "RANGE";
    }

    private BigDecimal calculateSMA(List<PricesIndex> candles, int period) {
        if (candles.size() < period) period = candles.size();
        
        List<PricesIndex> recent = candles.subList(candles.size() - period, candles.size());
        BigDecimal sum = recent.stream()
            .map(PricesIndex::getClose)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        long count = recent.stream()
            .map(PricesIndex::getClose)
            .filter(Objects::nonNull)
            .count();
        
        return count > 0 ? sum.divide(BigDecimal.valueOf(count), 8, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    private BigDecimal calculateAverageVolume(List<PricesIndex> candles) {
        BigDecimal sum = candles.stream()
            .map(PricesIndex::getVolume)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        long count = candles.stream()
            .map(PricesIndex::getVolume)
            .filter(Objects::nonNull)
            .count();
        
        return count > 0 ? sum.divide(BigDecimal.valueOf(count), RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    // ==================== POPULATE RESULT ====================
    private void populateResult(PriceActionResult result, MarketContext ctx, 
                               PriceBehavior behavior, PredictiveSignal signal) {
        // Final signal
        result.setFinal_signal(signal.signal.name());
        result.setFinal_reason(signal.reason);
        result.setFinal_confidence(signal.confidence);
        result.setConsolidatedDecision(signal.signal == Signal.HOLD ? "NO_TRADE" : signal.signal.name());
        
        // SR data
        result.setSr_signal(signal.signal.name());
        result.setSr_reason(signal.reason + " | Scenario: " + signal.scenario);
        result.setSr_confidence(signal.confidence);
        result.setSr_trend(ctx.trend.name());
        result.setSr_stopLoss(signal.stopLoss);
        result.setSr_projectedTarget(signal.projectedTarget);
        
        if (!ctx.supportLevels.isEmpty()) {
            result.setSr_nearestSupports(ctx.supportLevels.stream()
                .map(l -> l.level)
                .collect(Collectors.toList()));
        }
        
        if (!ctx.resistanceLevels.isEmpty()) {
            result.setSr_nearestResistances(ctx.resistanceLevels.stream()
                .map(l -> l.level)
                .collect(Collectors.toList()));
        }
        
        // Market context
        result.setBias(ctx.trend.name().contains("UP") ? "Bullish" : 
                      ctx.trend.name().contains("DOWN") ? "Bearish" : "Neutral");
        
        // Confidence score (0-100)
        int confidenceScore = calculateConfidenceScore(signal.confidence, ctx, behavior);
        result.setConfidenceScore(confidenceScore);
        
        // Volume confirmation
        result.setVolumeConfirmed(ctx.volumeIncreasing);
        
        // Price action triggered
        result.setSr_priceActionTriggered(signal.signal != Signal.HOLD);
        
        // Confluence description
        result.setConfluence(buildConfluenceDescription(ctx, behavior, signal));
        
        // Between levels description
        if (!ctx.supportLevels.isEmpty() && !ctx.resistanceLevels.isEmpty()) {
            result.setBetweenLevels(String.format("%.2f - %.2f", 
                ctx.supportLevels.get(0).level,
                ctx.resistanceLevels.get(0).level));
        }
        
        // Additional context fields
        result.setExchange(ctx.phase.name());
        
        // Fibonacci levels (simple implementation based on support/resistance)
        if (!ctx.supportLevels.isEmpty() && !ctx.resistanceLevels.isEmpty()) {
            List<FibonacciLevel> fiboSupports = new ArrayList<>();
            List<FibonacciLevel> fiboResistances = new ArrayList<>();
            
            for (int i = 0; i < Math.min(3, ctx.supportLevels.size()); i++) {
                LevelContext level = ctx.supportLevels.get(i);
                fiboSupports.add(new FibonacciLevel(level.level, 
                    String.format("Support #%d (%s, %d touches)", i+1, level.strength, level.touches)));
            }
            
            for (int i = 0; i < Math.min(3, ctx.resistanceLevels.size()); i++) {
                LevelContext level = ctx.resistanceLevels.get(i);
                fiboResistances.add(new FibonacciLevel(level.level, 
                    String.format("Resistance #%d (%s, %d touches)", i+1, level.strength, level.touches)));
            }
            
            result.setFibo_supports(fiboSupports);
            result.setFibo_resistances(fiboResistances);
            result.setFibo_signal(signal.signal.name());
            result.setFibo_reason(signal.reason);
            result.setFibo_confidence(signal.confidence);
            result.setFibo_triggered(signal.signal != Signal.HOLD);
        }
    }

    private int calculateConfidenceScore(String confidence, MarketContext ctx, PriceBehavior behavior) {
        int score = switch (confidence) {
            case "VERY_HIGH" -> 85;
            case "HIGH" -> 70;
            case "MEDIUM" -> 50;
            case "LOW" -> 30;
            default -> 20;
        };
        
        // Boost for trend alignment
        if (ctx.trend.name().contains("STRONG")) score += 5;
        
        // Boost for volume confirmation
        if (ctx.volumeIncreasing) score += 5;
        
        // Boost for clear patterns
        if (behavior.candlePattern.equals("HAMMER") || behavior.candlePattern.equals("SHOOTING_STAR")) {
            score += 5;
        }
        
        // Boost for market structure
        if (ctx.marketStructure.contains("HH/HL") || ctx.marketStructure.contains("LL/LH")) {
            score += 5;
        }
        
        // Cap at 100
        return Math.min(100, score);
    }

    private String buildConfluenceDescription(MarketContext ctx, PriceBehavior behavior, PredictiveSignal signal) {
        List<String> factors = new ArrayList<>();
        
        if (ctx.trend.name().contains("STRONG")) {
            factors.add("Strong Trend");
        } else if (!ctx.trend.name().contains("SIDEWAYS")) {
            factors.add("Trending Market");
        }
        
        if (ctx.volumeIncreasing) {
            factors.add("Volume Surge");
        }
        
        if (ctx.position == PricePosition.NEAR_SUPPORT || ctx.position == PricePosition.NEAR_RESISTANCE) {
            factors.add("Key Level");
        }
        
        if (behavior.showingRejection) {
            factors.add("Rejection Wick");
        }
        
        if (!behavior.candlePattern.equals("NORMAL")) {
            factors.add(behavior.candlePattern + " Pattern");
        }
        
        if (ctx.marketStructure.contains("HH/HL") || ctx.marketStructure.contains("LL/LH")) {
            factors.add("Clear Structure");
        }
        
        if (ctx.momentum.abs().compareTo(BigDecimal.valueOf(40)) > 0) {
            factors.add("Strong Momentum");
        }
        
        return factors.isEmpty() ? "Limited confluence" : String.join(" + ", factors);
    }

    private PriceActionResult buildInsufficientDataResult(BigDecimal currentPrice) {
        PriceActionResult result = new PriceActionResult();
        result.setCurrentPrice(currentPrice);
        result.setSr_signal(Signal.HOLD.name());
        result.setSr_trend(Trend.SIDEWAYS.name());
        result.setSr_reason("Insufficient candle data for analysis");
        result.setFinal_signal(Signal.HOLD.name());
        result.setFinal_reason("Insufficient data - need at least 30 candles for predictive analysis");
        result.setFinal_confidence("LOW");
        result.setConsolidatedDecision("NO_TRADE");
        result.setConfidenceScore(0);
        result.setVolumeConfirmed(false);
        result.setSr_priceActionTriggered(false);
        result.setFibo_triggered(false);
        return result;
    }
}