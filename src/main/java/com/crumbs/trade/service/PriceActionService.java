package com.crumbs.trade.service;

import com.crumbs.trade.dto.FibonacciLevel;
import com.crumbs.trade.dto.PriceActionResult;
import com.crumbs.trade.dto.SupportResistanceZone;
import com.crumbs.trade.entity.PricesIndex;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PriceActionService {

    public enum Signal { BUY, SELL, HOLD }
    public enum Trend { UPTREND, DOWNTREND, SIDEWAYS, UNKNOWN }

    // Configuration Constants
    private static final int MAX_SR_ZONES_INTRADAY = 5;
    private static final int MAX_SR_ZONES_POSITIONAL = 5;
    private static final int MIN_TOUCHES_INTRADAY = 2;
    private static final int MIN_TOUCHES_POSITIONAL = 3;
    private static final int MIN_CANDLES_REQUIRED = 10;
    private static final int TREND_SHORT_MA_PERIOD = 5;
    private static final int TREND_LONG_MA_PERIOD = 20;
    
    private static final BigDecimal TOLERANCE_PERCENTAGE = BigDecimal.valueOf(0.0015);
    private static final BigDecimal MCX_MAX_DISTANCE_PERCENTAGE = BigDecimal.valueOf(0.005);
    private static final BigDecimal INTRADAY_MAX_DISTANCE_PERCENTAGE = BigDecimal.valueOf(0.005);
    private static final BigDecimal POSITIONAL_MAX_DISTANCE_PERCENTAGE = BigDecimal.valueOf(0.1);
    private static final BigDecimal FIBO_TOLERANCE_PERCENTAGE = BigDecimal.valueOf(0.02);
    private static final BigDecimal TREND_THRESHOLD_PERCENTAGE = BigDecimal.valueOf(0.01);
    
    private static final Set<String> INTRADAY_FRAMES = Set.of("ONE_MINUTE", "FIVE_MINUTE", "THIRTY_MINUTE", "ONE_HOUR");
    private static final Set<String> VOLUME_IGNORED_EXCHANGES = Set.of("MCX");

    // ---------------------- PUBLIC ANALYZE METHOD ----------------------
    public PriceActionResult analyze(BigDecimal currentPrice, List<PricesIndex> candles, String timeframe) {
        if (log.isDebugEnabled()) {
            log.debug("Starting price action analysis: price={}, candles={}, timeframe={}", 
                currentPrice, candles != null ? candles.size() : 0, timeframe);
        }

        PriceActionResult result = new PriceActionResult();
        result.setCurrentPrice(currentPrice);

        // Validate input
        if (candles == null || candles.isEmpty()) {
            return buildInsufficientDataResult(currentPrice, "No candle data available");
        }

        if (candles.size() < MIN_CANDLES_REQUIRED) {
            return buildInsufficientDataResult(currentPrice, 
                "Insufficient candles: " + candles.size() + " (minimum " + MIN_CANDLES_REQUIRED + " required)");
        }

        // Calculate min/max from candles
        calculateMinMaxData(candles, result);

        // Price Action SR
        analyzePriceActionSR(currentPrice, candles, timeframe, result);

        // Fibonacci SR
        analyzeFiboSR(currentPrice, candles, result, timeframe);

        // Final merged signal
        mergeFinalSignal(result);

        if (log.isDebugEnabled()) {
            log.debug("Analysis complete: signal={}, confidence={}", 
                result.getFinal_signal(), result.getFinal_confidence());
        }

        return result;
    }

    // ---------------------- MIN/MAX DATA CALCULATION ----------------------
    private void calculateMinMaxData(List<PricesIndex> candles, PriceActionResult result) {
        if (candles == null || candles.isEmpty()) return;

        BigDecimal maxHigh = candles.get(0).getHigh();
        BigDecimal minLow = candles.get(0).getLow();
        BigDecimal maxVolume = candles.get(0).getVolume();
        BigDecimal minVolume = candles.get(0).getVolume();

        for (PricesIndex candle : candles) {
            if (candle.getHigh() != null && candle.getHigh().compareTo(maxHigh) > 0) {
                maxHigh = candle.getHigh();
            }
            if (candle.getLow() != null && candle.getLow().compareTo(minLow) < 0) {
                minLow = candle.getLow();
            }
            if (candle.getVolume() != null) {
                if (maxVolume == null || candle.getVolume().compareTo(maxVolume) > 0) {
                    maxVolume = candle.getVolume();
                }
                if (minVolume == null || candle.getVolume().compareTo(minVolume) < 0) {
                    minVolume = candle.getVolume();
                }
            }
        }

        result.setMaxHigh(maxHigh);
        result.setMinLow(minLow);
        result.setMaxVolume(maxVolume);
        result.setMinVolume(minVolume);
        result.setPriceRange(maxHigh.subtract(minLow));
    }

    // ---------------------- INSUFFICIENT DATA HELPER ----------------------
    private PriceActionResult buildInsufficientDataResult(BigDecimal currentPrice, String reason) {
        PriceActionResult result = new PriceActionResult();
        result.setCurrentPrice(currentPrice);
        result.setSr_signal(Signal.HOLD.name());
        result.setSr_trend(Trend.UNKNOWN.name());
        result.setSr_reason(reason);
        result.setFibo_signal(Signal.HOLD.name());
        result.setFibo_reason(reason);
        result.setFinal_signal(Signal.HOLD.name());
        result.setFinal_reason(reason);
        result.setFinal_confidence("LOW");
        result.setConsolidatedDecision("NO_TRADE");
        return result;
    }

    // ---------------------- IMPROVED FINAL MERGE WITH SOLID SIGNALS ----------------------
    private void mergeFinalSignal(PriceActionResult result) {
        Signal srSignal = Signal.valueOf(result.getSr_signal());
        Signal fiboSignal = Signal.valueOf(result.getFibo_signal());
        Trend trend = Trend.valueOf(result.getSr_trend());

        Signal finalSignal = Signal.HOLD;
        String finalReason = "";
        String finalConfidence = "LOW";

        // CASE 1: Perfect Confluence (Both SR and Fibo agree)
        if (srSignal == fiboSignal && srSignal != Signal.HOLD) {
            finalSignal = srSignal;
            finalReason = "STRONG CONFLUENCE: Both SR & Fibonacci align on " + srSignal;
            finalConfidence = "VERY_HIGH";
            
            // Further boost if trend aligns
            if ((finalSignal == Signal.BUY && trend == Trend.UPTREND) ||
                (finalSignal == Signal.SELL && trend == Trend.DOWNTREND)) {
                finalConfidence = "VERY_HIGH";
                finalReason += " + Trend confirmation";
            }
        }
        
        // CASE 2: SR Signal with Trend Support (Prioritize SR over Fibo)
        else if (srSignal != Signal.HOLD) {
            // SR BUY with supportive trend
            if (srSignal == Signal.BUY && (trend == Trend.UPTREND || trend == Trend.SIDEWAYS)) {
                finalSignal = Signal.BUY;
                finalConfidence = trend == Trend.UPTREND ? "HIGH" : "MEDIUM";
                finalReason = "SR BUY signal" + (trend == Trend.UPTREND ? " + Uptrend support" : " in sideways market");
                
                // Override HOLD from Fibo if trend is strong
                if (fiboSignal == Signal.HOLD && trend == Trend.UPTREND) {
                    finalConfidence = "HIGH";
                }
            }
            // SR SELL with supportive trend
            else if (srSignal == Signal.SELL && (trend == Trend.DOWNTREND || trend == Trend.SIDEWAYS)) {
                finalSignal = Signal.SELL;
                finalConfidence = trend == Trend.DOWNTREND ? "HIGH" : "MEDIUM";
                finalReason = "SR SELL signal" + (trend == Trend.DOWNTREND ? " + Downtrend support" : " in sideways market");
                
                // Override HOLD from Fibo if trend is strong
                if (fiboSignal == Signal.HOLD && trend == Trend.DOWNTREND) {
                    finalConfidence = "HIGH";
                }
            }
            // SR signal against trend - reduce confidence but still trade
            else if (srSignal == Signal.BUY && trend == Trend.DOWNTREND) {
                finalSignal = Signal.BUY;
                finalConfidence = "MEDIUM";
                finalReason = "SR BUY signal (counter-trend trade, use tight stop-loss)";
            }
            else if (srSignal == Signal.SELL && trend == Trend.UPTREND) {
                finalSignal = Signal.SELL;
                finalConfidence = "MEDIUM";
                finalReason = "SR SELL signal (counter-trend trade, use tight stop-loss)";
            }
        }
        
        // CASE 3: Only Fibo Signal (SR is HOLD)
        else if (fiboSignal != Signal.HOLD && srSignal == Signal.HOLD) {
            // Fibo signal with trend support
            if ((fiboSignal == Signal.BUY && trend == Trend.UPTREND) ||
                (fiboSignal == Signal.SELL && trend == Trend.DOWNTREND)) {
                finalSignal = fiboSignal;
                finalConfidence = "HIGH";
                finalReason = "Fibonacci " + fiboSignal + " with trend alignment";
            }
            // Fibo signal in sideways market
            else if (trend == Trend.SIDEWAYS) {
                finalSignal = fiboSignal;
                finalConfidence = "MEDIUM";
                finalReason = "Fibonacci " + fiboSignal + " in range-bound market";
            }
            // Fibo signal against trend
            else {
                finalSignal = fiboSignal;
                finalConfidence = "LOW";
                finalReason = "Fibonacci " + fiboSignal + " (weak, against trend)";
            }
        }
        
        // CASE 4: Conflicting Signals (SR says BUY, Fibo says SELL or vice versa)
        else if (srSignal != fiboSignal && srSignal != Signal.HOLD && fiboSignal != Signal.HOLD) {
            // Let trend be the tie-breaker
            if (trend == Trend.UPTREND) {
                finalSignal = Signal.BUY;
                finalConfidence = "MEDIUM";
                finalReason = "Conflict resolved: BUY based on uptrend (SR: " + srSignal + ", Fibo: " + fiboSignal + ")";
            } else if (trend == Trend.DOWNTREND) {
                finalSignal = Signal.SELL;
                finalConfidence = "MEDIUM";
                finalReason = "Conflict resolved: SELL based on downtrend (SR: " + srSignal + ", Fibo: " + fiboSignal + ")";
            } else {
                // In sideways, prefer SR over Fibo
                finalSignal = srSignal;
                finalConfidence = "LOW";
                finalReason = "Conflict: Prioritizing SR " + srSignal + " over Fibo " + fiboSignal + " in sideways market";
            }
        }
        
        // CASE 5: Both HOLD but trend is strong - generate trend-following signal
        else if (srSignal == Signal.HOLD && fiboSignal == Signal.HOLD) {
            if (trend == Trend.UPTREND) {
                finalSignal = Signal.BUY;
                finalConfidence = "LOW";
                finalReason = "Trend-following BUY (no SR/Fibo triggers, but strong uptrend)";
            } else if (trend == Trend.DOWNTREND) {
                finalSignal = Signal.SELL;
                finalConfidence = "LOW";
                finalReason = "Trend-following SELL (no SR/Fibo triggers, but strong downtrend)";
            } else {
                finalSignal = Signal.HOLD;
                finalConfidence = "LOW";
                finalReason = "NO SIGNAL: No triggers and unclear trend";
            }
        }

        result.setFinal_signal(finalSignal.name());
        result.setFinal_reason(finalReason);
        result.setFinal_confidence(finalConfidence);

        // CONSOLIDATED DECISION - More aggressive
        String consolidatedDecision;
        
        if ("BUY".equals(result.getFinal_signal())) {
            // Accept BUY if confidence is MEDIUM or higher, or if trend supports
            if ("VERY_HIGH".equals(result.getFinal_confidence()) || 
                "HIGH".equals(result.getFinal_confidence()) ||
                ("MEDIUM".equals(result.getFinal_confidence()) && "UPTREND".equals(result.getSr_trend()))) {
                consolidatedDecision = "BUY";
            } else {
                consolidatedDecision = "NO_TRADE";
            }
        } else if ("SELL".equals(result.getFinal_signal())) {
            // Accept SELL if confidence is MEDIUM or higher, or if trend supports
            if ("VERY_HIGH".equals(result.getFinal_confidence()) || 
                "HIGH".equals(result.getFinal_confidence()) ||
                ("MEDIUM".equals(result.getFinal_confidence()) && "DOWNTREND".equals(result.getSr_trend()))) {
                consolidatedDecision = "SELL";
            } else {
                consolidatedDecision = "NO_TRADE";
            }
        } else {
            consolidatedDecision = "NO_TRADE";
        }
        
        result.setConsolidatedDecision(consolidatedDecision);
    }

    // ---------------------- PRICE ACTION SR ----------------------
    private void analyzePriceActionSR(BigDecimal currentPrice, List<PricesIndex> candles, String timeframe, PriceActionResult result) {
        if (candles == null || candles.isEmpty()) return;

        String exchange = candles.get(0).getExchange();
        result.setExchange(exchange);

        // Detect trend with improved algorithm
        Trend trend = detectTrend(candles);
        result.setSr_trend(trend.name());

        // Single-pass calculation for better performance
        BigDecimal totalVolume = BigDecimal.ZERO;
        BigDecimal totalRange = BigDecimal.ZERO;
        int validCandles = 0;

        for (PricesIndex candle : candles) {
            if (candle.getVolume() != null) {
                totalVolume = totalVolume.add(candle.getVolume());
            }
            if (candle.getHigh() != null && candle.getLow() != null) {
                totalRange = totalRange.add(candle.getHigh().subtract(candle.getLow()));
                validCandles++;
            }
        }

        if (validCandles == 0) {
            result.setSr_signal(Signal.HOLD.name());
            result.setSr_reason("No valid candle data");
            return;
        }

        BigDecimal avgVolume = totalVolume.divide(BigDecimal.valueOf(candles.size()), RoundingMode.HALF_UP);
        BigDecimal avgRange = totalRange.divide(BigDecimal.valueOf(validCandles), RoundingMode.HALF_UP);

        // Adaptive tolerance
        BigDecimal tolerance = avgRange.max(currentPrice.multiply(TOLERANCE_PERCENTAGE));

        boolean intraday = INTRADAY_FRAMES.contains(timeframe.toUpperCase());

        // Adaptive maxDistance
        BigDecimal maxDistance;
        if (VOLUME_IGNORED_EXCHANGES.contains(exchange.toUpperCase())) {
            maxDistance = avgRange.max(currentPrice.multiply(MCX_MAX_DISTANCE_PERCENTAGE));
        } else {
            maxDistance = intraday
                    ? currentPrice.multiply(INTRADAY_MAX_DISTANCE_PERCENTAGE)
                    : currentPrice.multiply(POSITIONAL_MAX_DISTANCE_PERCENTAGE);
        }

        int maxSRZones = intraday ? MAX_SR_ZONES_INTRADAY : MAX_SR_ZONES_POSITIONAL;
        int minTouches = intraday ? MIN_TOUCHES_INTRADAY : MIN_TOUCHES_POSITIONAL;

        // Use HashMap for O(1) zone lookup
        Map<BigDecimal, SupportResistanceZone> supportZoneMap = new HashMap<>();
        Map<BigDecimal, SupportResistanceZone> resistanceZoneMap = new HashMap<>();

        for (int i = 0; i < candles.size(); i++) {
            PricesIndex c = candles.get(i);
            int age = candles.size() - 1 - i;
            if (c.getLow() != null) {
                addOrUpdateZoneOptimized(exchange, supportZoneMap, c.getLow(), c.getVolume(), avgVolume, tolerance, age);
            }
            if (c.getHigh() != null) {
                addOrUpdateZoneOptimized(exchange, resistanceZoneMap, c.getHigh(), c.getVolume(), avgVolume, tolerance, age);
            }
        }

        // Convert to lists and filter
        List<SupportResistanceZone> supportZones = supportZoneMap.values().stream()
                .filter(z -> z.getTouches() >= minTouches
                        && currentPrice.subtract(z.getLevel()).abs().compareTo(maxDistance) <= 0)
                .sorted(Comparator.comparing(z -> weightedDistance(z, currentPrice)))
                .limit(maxSRZones)
                .collect(Collectors.toList());

        List<SupportResistanceZone> resistanceZones = resistanceZoneMap.values().stream()
                .filter(z -> z.getTouches() >= minTouches
                        && z.getLevel().subtract(currentPrice).abs().compareTo(maxDistance) <= 0)
                .sorted(Comparator.comparing(z -> weightedDistance(z, currentPrice)))
                .limit(maxSRZones)
                .collect(Collectors.toList());

        result.setSr_nearestSupports(supportZones.stream().map(SupportResistanceZone::getLevel).toList());
        result.setSr_nearestResistances(resistanceZones.stream().map(SupportResistanceZone::getLevel).toList());

        // Determine signal
        Signal srSignal = Signal.HOLD;
        String srReason = "HOLD - Price not near key support/resistance.";
        String srConfidence = "LOW";

        boolean nearSupport = !supportZones.isEmpty() &&
                currentPrice.subtract(supportZones.get(0).getLevel()).abs().compareTo(tolerance) <= 0;
        boolean nearResistance = !resistanceZones.isEmpty() &&
                resistanceZones.get(0).getLevel().subtract(currentPrice).abs().compareTo(tolerance) <= 0;

        if (nearSupport && !nearResistance) {
            srSignal = Signal.BUY;
            srReason = "BUY - Price near strong support zone (touches: " + supportZones.get(0).getTouches() + ").";
            srConfidence = supportZones.get(0).getTouches() >= minTouches + 2 ? "VERY_HIGH" : "HIGH";
            result.setSr_stopLoss(supportZones.get(0).getLevel().subtract(tolerance));
            result.setSr_projectedTarget(resistanceZones.isEmpty() ? null : resistanceZones.get(0).getLevel());
        } else if (nearResistance && !nearSupport) {
            srSignal = Signal.SELL;
            srReason = "SELL - Price near strong resistance zone (touches: " + resistanceZones.get(0).getTouches() + ").";
            srConfidence = resistanceZones.get(0).getTouches() >= minTouches + 2 ? "VERY_HIGH" : "HIGH";
            result.setSr_stopLoss(resistanceZones.get(0).getLevel().add(tolerance));
            result.setSr_projectedTarget(supportZones.isEmpty() ? null : supportZones.get(0).getLevel());
        } else if (nearSupport && nearResistance) {
            srSignal = Signal.HOLD;
            srReason = "HOLD - Price between strong support and resistance zones.";
            srConfidence = "MEDIUM";
        }

        result.setSr_signal(srSignal.name());
        result.setSr_reason(srReason);
        result.setSr_confidence(srConfidence);
        result.setVolumeConfirmed(!supportZones.isEmpty() || !resistanceZones.isEmpty());
        result.setSr_priceActionTriggered(srSignal != Signal.HOLD);
    }

    // Optimized zone management with HashMap
    private void addOrUpdateZoneOptimized(String exchange, Map<BigDecimal, SupportResistanceZone> zoneMap, 
                                         BigDecimal level, BigDecimal volume, BigDecimal avgVolume, 
                                         BigDecimal tolerance, int ageInCandles) {
        // Find existing zone within tolerance
        SupportResistanceZone existingZone = null;
        BigDecimal existingKey = null;
        
        for (Map.Entry<BigDecimal, SupportResistanceZone> entry : zoneMap.entrySet()) {
            if (entry.getKey().subtract(level).abs().compareTo(tolerance) <= 0) {
                existingZone = entry.getValue();
                existingKey = entry.getKey();
                break;
            }
        }

        if (existingZone != null) {
            existingZone.setTouches(existingZone.getTouches() + 1);
            if (VOLUME_IGNORED_EXCHANGES.contains(exchange.toUpperCase())) {
                existingZone.setVolumeConfirmed(true);
            } else {
                if (volume != null && volume.compareTo(avgVolume) > 0) {
                    existingZone.setVolumeConfirmed(true);
                }
            }
            existingZone.setLastTouchAge(Math.min(existingZone.getLastTouchAge(), ageInCandles));
        } else {
            SupportResistanceZone newZone = new SupportResistanceZone();
            newZone.setLevel(level);
            newZone.setTouches(1);
            newZone.setVolumeConfirmed(VOLUME_IGNORED_EXCHANGES.contains(exchange.toUpperCase()) ||
                    (volume != null && volume.compareTo(avgVolume) > 0));
            newZone.setLastTouchAge(ageInCandles);
            zoneMap.put(level, newZone);
        }
    }

    // ---------------------- FIBONACCI SR ----------------------
    private void analyzeFiboSR(BigDecimal currentPrice, List<PricesIndex> candles, PriceActionResult result, String timeframe) {
        if (candles == null || candles.isEmpty()) return;

        int lookback = Math.min(candles.size(), 50);
        List<PricesIndex> recent = candles.subList(candles.size() - lookback, candles.size());

        BigDecimal high = recent.stream()
                .map(PricesIndex::getHigh)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(currentPrice);
        
        BigDecimal low = recent.stream()
                .map(PricesIndex::getLow)
                .filter(Objects::nonNull)
                .min(BigDecimal::compareTo)
                .orElse(currentPrice);
        
        BigDecimal range = high.subtract(low);

        if (range.compareTo(BigDecimal.ZERO) == 0) {
            result.setFibo_signal(Signal.HOLD.name());
            result.setFibo_reason("No price range for Fibonacci calculation");
            return;
        }

        BigDecimal tolerance = range.multiply(FIBO_TOLERANCE_PERCENTAGE)
                .max(currentPrice.multiply(BigDecimal.valueOf(0.002)));

        BigDecimal[] ratios = {
            BigDecimal.valueOf(0.236), 
            BigDecimal.valueOf(0.382),
            BigDecimal.valueOf(0.5), 
            BigDecimal.valueOf(0.618), 
            BigDecimal.valueOf(0.786)
        };

        List<FibonacciLevel> fiboSupports = new ArrayList<>();
        List<FibonacciLevel> fiboResistances = new ArrayList<>();

        for (BigDecimal r : ratios) {
            BigDecimal supportLevel = low.add(range.multiply(r)).setScale(2, RoundingMode.HALF_UP);
            fiboSupports.add(new FibonacciLevel(
                supportLevel, 
                r.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP) + "% (" + supportLevel + ")"
            ));

            BigDecimal resistanceLevel = high.subtract(range.multiply(r)).setScale(2, RoundingMode.HALF_UP);
            fiboResistances.add(new FibonacciLevel(
                resistanceLevel, 
                r.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP) + "% (" + resistanceLevel + ")"
            ));
        }

        fiboSupports.sort(Comparator.comparing(f -> currentPrice.subtract(f.getLevel()).abs()));
        fiboResistances.sort(Comparator.comparing(f -> f.getLevel().subtract(currentPrice).abs()));

        result.setFibo_supports(fiboSupports);
        result.setFibo_resistances(fiboResistances);

        FibonacciLevel nearest = findNearestFibonacciLevel(currentPrice, fiboSupports, fiboResistances);
        result.setFibo_nearestLevel(nearest);

        Signal fiboSignal = Signal.HOLD;
        String fiboReason = "HOLD - Price not near Fibonacci support/resistance.";
        String fiboConfidence = "LOW";
        BigDecimal stopLoss = null;
        BigDecimal projectedTarget = null;

        if (nearest != null) {
            BigDecimal distance = currentPrice.subtract(nearest.getLevel()).abs();
            if (distance.compareTo(tolerance) <= 0) {
                if (fiboSupports.contains(nearest)) {
                    fiboSignal = Signal.BUY;
                    fiboReason = "BUY - Price near Fibonacci support (" + nearest.getLabel() + ")";
                    fiboConfidence = "MEDIUM";
                    stopLoss = nearest.getLevel().subtract(tolerance);
                    projectedTarget = !fiboResistances.isEmpty() ? fiboResistances.get(0).getLevel() : null;
                } else if (fiboResistances.contains(nearest)) {
                    fiboSignal = Signal.SELL;
                    fiboReason = "SELL - Price near Fibonacci resistance (" + nearest.getLabel() + ")";
                    fiboConfidence = "MEDIUM";
                    stopLoss = nearest.getLevel().add(tolerance);
                    projectedTarget = !fiboSupports.isEmpty() ? fiboSupports.get(0).getLevel() : null;
                }
            }
        }

        result.setFibo_signal(fiboSignal.name());
        result.setFibo_reason(fiboReason);
        result.setFibo_confidence(fiboConfidence);
        result.setFibo_stopLoss(stopLoss);
        result.setFibo_projectedTarget(projectedTarget);
        result.setFibo_triggered(fiboSignal != Signal.HOLD);
    }

    // ---------------------- HELPERS ----------------------
    
    private FibonacciLevel findNearestFibonacciLevel(BigDecimal currentPrice, 
                                                     List<FibonacciLevel> supports, 
                                                     List<FibonacciLevel> resistances) {
        if (supports.isEmpty() && resistances.isEmpty()) return null;
        if (supports.isEmpty()) return resistances.get(0);
        if (resistances.isEmpty()) return supports.get(0);
        
        BigDecimal supportDistance = currentPrice.subtract(supports.get(0).getLevel()).abs();
        BigDecimal resistanceDistance = resistances.get(0).getLevel().subtract(currentPrice).abs();
        
        return supportDistance.compareTo(resistanceDistance) < 0 ? supports.get(0) : resistances.get(0);
    }

    private BigDecimal weightedDistance(SupportResistanceZone zone, BigDecimal currentPrice) {
        BigDecimal distance = currentPrice.subtract(zone.getLevel()).abs();
        BigDecimal strength = BigDecimal.valueOf(zone.getTouches())
                .multiply(zone.isVolumeConfirmed() ? BigDecimal.ONE : BigDecimal.valueOf(0.5))
                .divide(BigDecimal.valueOf(zone.getLastTouchAge() + 1), 8, RoundingMode.HALF_UP);
        
        if (strength.compareTo(BigDecimal.ZERO) == 0) return distance;
        return distance.divide(strength, 8, RoundingMode.HALF_UP);
    }

    private Trend detectTrend(List<PricesIndex> candles) {
        if (candles.size() < TREND_LONG_MA_PERIOD) {
            return Trend.UNKNOWN;
        }

        try {
            BigDecimal shortMA = calculateSMA(candles, TREND_SHORT_MA_PERIOD);
            BigDecimal longMA = calculateSMA(candles, TREND_LONG_MA_PERIOD);

            if (longMA.compareTo(BigDecimal.ZERO) == 0) {
                return Trend.SIDEWAYS;
            }

            BigDecimal difference = shortMA.subtract(longMA);
            BigDecimal threshold = longMA.multiply(TREND_THRESHOLD_PERCENTAGE);

            if (difference.compareTo(threshold) > 0) return Trend.UPTREND;
            if (difference.compareTo(threshold.negate()) < 0) return Trend.DOWNTREND;
            return Trend.SIDEWAYS;
        } catch (Exception e) {
            log.warn("Error detecting trend, falling back to basic detection", e);
            return detectTrendBasic(candles);
        }
    }

    private BigDecimal calculateSMA(List<PricesIndex> candles, int period) {
        if (candles.size() < period) {
            period = candles.size();
        }

        List<PricesIndex> recentCandles = candles.subList(candles.size() - period, candles.size());
        BigDecimal sum = recentCandles.stream()
                .map(PricesIndex::getClose)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return sum.divide(BigDecimal.valueOf(recentCandles.size()), 8, RoundingMode.HALF_UP);
    }

    private Trend detectTrendBasic(List<PricesIndex> candles) {
        if (candles.size() < 5) return Trend.UNKNOWN;
        
        long upCount = candles.stream()
                .filter(c -> c.getClose() != null && c.getOpen() != null)
                .filter(c -> c.getClose().compareTo(c.getOpen()) > 0)
                .count();
        
        long downCount = candles.stream()
                .filter(c -> c.getClose() != null && c.getOpen() != null)
                .filter(c -> c.getClose().compareTo(c.getOpen()) < 0)
                .count();
        
        if (upCount > downCount * 1.5) return Trend.UPTREND;
        if (downCount > upCount * 1.5) return Trend.DOWNTREND;
        return Trend.SIDEWAYS;
    }
}