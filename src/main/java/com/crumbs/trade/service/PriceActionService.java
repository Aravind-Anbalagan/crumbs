package com.crumbs.trade.service;

import com.crumbs.trade.dto.FibonacciLevel;
import com.crumbs.trade.dto.PriceActionResult;
import com.crumbs.trade.dto.SupportResistanceZone;
import com.crumbs.trade.entity.PricesIndex;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PriceActionService {

    private static final int MAX_ZONES = 3;
    private static final BigDecimal VOLUME_SPIKE_THRESHOLD = BigDecimal.valueOf(1.2);

    @Autowired
    private ObjectMapper objectMapper;

    public enum Signal {
        BUY, SELL, HOLD
    }

    public enum Trend {
        UPTREND, DOWNTREND, SIDEWAYS, UNKNOWN
    }

    public PriceActionResult analyze(BigDecimal currentPrice, List<PricesIndex> candles) {
    	PriceActionResult result = new PriceActionResult();
        result.setCurrentPrice(currentPrice);

        if (candles == null || candles.size() < 5) {
            result.setSr_signal(Signal.HOLD.name());
            result.setSr_trend(Trend.UNKNOWN.name());
            result.setSr_reason("Insufficient data");
            result.setFibo_signal(Signal.HOLD.name());
            result.setFibo_reason("Insufficient data");
            result.setFibo_confidence("LOW");
            result.setFibo_trend(Trend.UNKNOWN.name());
            return result;
        }

        BigDecimal avgVolume = candles.stream()
                .map(PricesIndex::getVolume)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(candles.size()), RoundingMode.HALF_UP);

        BigDecimal avgRange = candles.stream()
                .map(c -> c.getHigh().subtract(c.getLow()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(candles.size()), RoundingMode.HALF_UP);

        BigDecimal tolerance = avgRange.multiply(BigDecimal.valueOf(0.4));

        List<SupportResistanceZone> sr_supportZones = detectZones(candles, tolerance, true);
        List<SupportResistanceZone> sr_resistanceZones = detectZones(candles, tolerance, false);

        List<BigDecimal> sr_supportLevels = sr_supportZones.stream().map(SupportResistanceZone::getLevel).toList();
        List<BigDecimal> sr_resistanceLevels = sr_resistanceZones.stream().map(SupportResistanceZone::getLevel).toList();

        boolean sr_nearSupport = isNearAny(currentPrice, sr_supportLevels, tolerance);
        boolean sr_nearResistance = isNearAny(currentPrice, sr_resistanceLevels, tolerance);

        Signal signal;
        String reason = "";
        String confidence = "LOW";

        if (sr_nearSupport && !sr_nearResistance) {
            signal = Signal.BUY;
            reason = "BUY - Price near strong support tested multiple times.";
        } else if (sr_nearResistance && !sr_nearSupport) {
            signal = Signal.SELL;
            reason = "SELL - Price near strong resistance tested multiple times.";
        } else {
            signal = Signal.HOLD;
            reason = "HOLD - Price not near key support/resistance.";
        }

        int sr_touchCount = 0;
        if (signal != Signal.HOLD) {
            sr_touchCount = getTouchCountNear(currentPrice,
                    signal == Signal.BUY ? sr_supportZones : sr_resistanceZones,
                    tolerance);
            if (sr_touchCount >= 3) confidence = "HIGH";
            else if (sr_touchCount == 2) confidence = "MEDIUM";
        }

        BigDecimal currentVolume = Optional.ofNullable(candles.get(candles.size() - 1).getVolume()).orElse(BigDecimal.ZERO);
        boolean volumeConfirmed = currentVolume.compareTo(avgVolume.multiply(VOLUME_SPIKE_THRESHOLD)) >= 0
                && (signal == Signal.BUY || signal == Signal.SELL);

        BigDecimal sr_stopLoss = signal != Signal.HOLD
                ? calculateStopLoss(currentPrice, signal, sr_supportLevels, sr_resistanceLevels, avgRange)
                : null;

        BigDecimal sr_projectedTarget = signal != Signal.HOLD
                ? projectTarget(currentPrice, signal.name(), avgRange)
                : null;

        List<FibonacciLevel> fibo_levels = calculateFibonacciLevelsWithLabels(
                candles.stream().map(PricesIndex::getHigh).max(Comparator.naturalOrder()).orElse(currentPrice),
                candles.stream().map(PricesIndex::getLow).min(Comparator.naturalOrder()).orElse(currentPrice)
        );

        List<FibonacciLevel> fibo_supports = fibo_levels.stream()
                .filter(f -> f.getLevel().compareTo(currentPrice) < 0)
                .collect(Collectors.toList());

        List<FibonacciLevel> fibo_resistances = fibo_levels.stream()
                .filter(f -> f.getLevel().compareTo(currentPrice) > 0)
                .collect(Collectors.toList());

        boolean fibo_nearSupport = isNearAnyFibo(currentPrice, fibo_supports, tolerance);
        boolean fibo_nearResistance = isNearAnyFibo(currentPrice, fibo_resistances, tolerance);

        boolean sr_priceActionTriggered = sr_nearSupport || sr_nearResistance;
        boolean fibo_triggered = fibo_nearSupport || fibo_nearResistance;

        Trend trend = detectTrendDirection(candles);
        result.setSr_trend(trend.name());

        boolean trendConflict = (trend == Trend.UPTREND && signal == Signal.SELL)
                || (trend == Trend.DOWNTREND && signal == Signal.BUY);

        if (trendConflict) {
            reason += " ⚠️ Signal contradicts trend.";
        } else if (signal != Signal.HOLD) {
            reason += " ✅ Trend confirmed.";
        }

        Signal fiboSignal = Signal.HOLD;
        String fiboReason = "HOLD - Price not near key Fibonacci levels.";
        Trend fiboTrend = Trend.SIDEWAYS;

        if (fibo_nearSupport && !fibo_nearResistance) {
            fiboSignal = Signal.BUY;
            fiboReason = "BUY - Price near Fibonacci support level — bounce expected.";
            fiboTrend = Trend.UPTREND;
        } else if (fibo_nearResistance && !fibo_nearSupport) {
            fiboSignal = Signal.SELL;
            fiboReason = "SELL - Price near Fibonacci resistance — possible rejection.";
            fiboTrend = Trend.DOWNTREND;
        }

        Optional<FibonacciLevel> nearestFibo = fibo_levels.stream()
                .min(Comparator.comparing(f -> f.getLevel().subtract(currentPrice).abs()));

        result.setSr_signal(signal.name());
        result.setVolumeConfirmed(volumeConfirmed);
        result.setSr_stopLoss(sr_stopLoss);
        result.setSr_projectedTarget(sr_projectedTarget);
        result.setSr_confidence(confidence);
        result.setSr_reason(reason + " (Touches: " + sr_touchCount + ")");
        result.setSr_nearestSupports(sr_supportLevels);
        result.setSr_nearestResistances(sr_resistanceLevels);
        result.setSr_priceActionTriggered(sr_priceActionTriggered);

        result.setFibo_supports(fibo_supports);
        result.setFibo_resistances(fibo_resistances);
        result.setFibo_triggered(fibo_triggered);
        result.setFibo_signal(fiboSignal.name());
        result.setFibo_reason(fiboReason);
        result.setFibo_trend(fiboTrend.name());

        if (nearestFibo.isPresent()) {
            FibonacciLevel fibo = nearestFibo.get();
            result.setFibo_nearestLevel(fibo.getLevel());
            result.setFibo_label(fibo.getLabel());
            result.setFibo_type(fibo.getLevel().compareTo(currentPrice) < 0 ? "Support" : "Resistance");
            result.setFibo_bias(fibo.getLevel().compareTo(currentPrice) < 0 ? "Bullish bounce expected" : "Bearish rejection likely");

            BigDecimal distance = fibo.getLevel().subtract(currentPrice).abs();
            if (distance.compareTo(tolerance.divide(BigDecimal.valueOf(2), RoundingMode.HALF_UP)) <= 0) {
                result.setFibo_confidence("HIGH");
            } else if (distance.compareTo(tolerance) <= 0) {
                result.setFibo_confidence("MEDIUM");
            } else {
                result.setFibo_confidence("LOW");
            }
        } else {
            result.setFibo_confidence("LOW");
        }

        if (signal == Signal.BUY) {
            result.setBuyStopLoss(sr_stopLoss);
        } else if (signal == Signal.SELL) {
            result.setSellStopLoss(sr_stopLoss);
        }

        result.serializeListsToJson();

        return result;
    }

    private List<SupportResistanceZone> detectZones(List<PricesIndex> candles, BigDecimal tolerance, boolean isSupport) {
        Map<BigDecimal, SupportResistanceZone> zoneMap = new TreeMap<>();

        for (PricesIndex c : candles) {
            BigDecimal level = isSupport ? c.getLow() : c.getHigh();
            if (level == null) continue;

            boolean matched = false;
            for (Map.Entry<BigDecimal, SupportResistanceZone> entry : zoneMap.entrySet()) {
                if (isWithinTolerance(entry.getKey(), level, tolerance)) {
                    SupportResistanceZone zone = entry.getValue();
                    zone.setTouches(zone.getTouches() + 1);
                    zone.setLastTouchedDate(c.getTimestamp());
                    matched = true;
                    break;
                }
            }

            if (!matched) {
                zoneMap.put(level, new SupportResistanceZone(level, 1, c.getTimestamp()));
            }
        }

        return zoneMap.values().stream()
                .sorted((a, b) -> isSupport ? b.getLevel().compareTo(a.getLevel()) : a.getLevel().compareTo(b.getLevel()))
                .limit(MAX_ZONES)
                .collect(Collectors.toList());
    }

    private boolean isWithinTolerance(BigDecimal a, BigDecimal b, BigDecimal tolerance) {
        return a.subtract(b).abs().compareTo(tolerance) <= 0;
    }

    private int getTouchCountNear(BigDecimal price, List<SupportResistanceZone> zones, BigDecimal tolerance) {
        return zones.stream()
                .filter(z -> isWithinTolerance(z.getLevel(), price, tolerance))
                .map(SupportResistanceZone::getTouches)
                .findFirst().orElse(0);
    }

    private BigDecimal calculateStopLoss(BigDecimal price, Signal signal,
                                         List<BigDecimal> supports, List<BigDecimal> resistances, BigDecimal range) {
        BigDecimal buffer = range.multiply(BigDecimal.valueOf(0.5));
        if (signal == Signal.BUY && !supports.isEmpty()) {
            return supports.get(0).subtract(buffer).setScale(2, RoundingMode.HALF_UP);
        } else if (signal == Signal.SELL && !resistances.isEmpty()) {
            return resistances.get(0).add(buffer).setScale(2, RoundingMode.HALF_UP);
        }
        return price;
    }

    private BigDecimal projectTarget(BigDecimal currentPrice, String signal, BigDecimal avgRange) {
        return switch (signal) {
            case "BUY" -> currentPrice.add(avgRange.multiply(BigDecimal.valueOf(1.5))).setScale(2, RoundingMode.HALF_UP);
            case "SELL" -> currentPrice.subtract(avgRange.multiply(BigDecimal.valueOf(1.5))).setScale(2, RoundingMode.HALF_UP);
            default -> currentPrice;
        };
    }

    private List<FibonacciLevel> calculateFibonacciLevelsWithLabels(BigDecimal high, BigDecimal low) {
        BigDecimal range = high.subtract(low);
        BigDecimal[] ratios = {
                BigDecimal.valueOf(0.236),
                BigDecimal.valueOf(0.382),
                BigDecimal.valueOf(0.5),
                BigDecimal.valueOf(0.618),
                BigDecimal.valueOf(0.786)
        };
        String[] labels = {"23.6%", "38.2%", "50.0%", "61.8%", "78.6%"};

        List<FibonacciLevel> levels = new ArrayList<>();
        for (int i = 0; i < ratios.length; i++) {
            BigDecimal level = high.subtract(range.multiply(ratios[i])).setScale(2, RoundingMode.HALF_UP);
            levels.add(new FibonacciLevel(level, labels[i]));
        }

        levels.sort(Comparator.comparing(FibonacciLevel::getLevel));
        return levels;
    }

    private boolean isNearAny(BigDecimal price, List<BigDecimal> levels, BigDecimal tolerance) {
        return levels.stream().anyMatch(l -> isWithinTolerance(price, l, tolerance));
    }

    private boolean isNearAnyFibo(BigDecimal price, List<FibonacciLevel> levels, BigDecimal tolerance) {
        return levels.stream().anyMatch(f -> isWithinTolerance(price, f.getLevel(), tolerance));
    }

    private Trend detectTrendDirection(List<PricesIndex> candles) {
        BigDecimal start = candles.get(0).getClose();
        BigDecimal end = candles.get(candles.size() - 1).getClose();

        if (start == null || end == null) return Trend.UNKNOWN;

        BigDecimal percentChange = end.subtract(start)
                .divide(start, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        if (percentChange.compareTo(BigDecimal.valueOf(1.5)) > 0) return Trend.UPTREND;
        if (percentChange.compareTo(BigDecimal.valueOf(-1.5)) < 0) return Trend.DOWNTREND;
        return Trend.SIDEWAYS;
    }
}
