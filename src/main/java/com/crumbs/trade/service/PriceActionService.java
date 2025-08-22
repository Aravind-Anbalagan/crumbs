package com.crumbs.trade.service;

import com.crumbs.trade.dto.FibonacciLevel;
import com.crumbs.trade.dto.PriceActionResult;
import com.crumbs.trade.dto.SupportResistanceZone;
import com.crumbs.trade.entity.PricesIndex;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PriceActionService {

    public enum Signal { BUY, SELL, HOLD }
    public enum Trend { UPTREND, DOWNTREND, SIDEWAYS, UNKNOWN }

    private static final int MAX_SR_ZONES_INTRADAY = 5;
    private static final int MAX_SR_ZONES_POSITIONAL = 5;
    private static final Set<String> INTRADAY_FRAMES = Set.of("ONE_MINUTE", "FIVE_MINUTE", "THIRTY_MINUTE", "ONE_HOUR");

    // ---------------------- PUBLIC ANALYZE METHOD ----------------------
    public PriceActionResult analyze(BigDecimal currentPrice, List<PricesIndex> candles, String timeframe) {
        PriceActionResult result = new PriceActionResult();
        result.setCurrentPrice(currentPrice);

        if (candles == null || candles.size() < 5) {
            result.setSr_signal(Signal.HOLD.name());
            result.setSr_trend(Trend.UNKNOWN.name());
            result.setSr_reason("Insufficient data");
            result.setFibo_signal(Signal.HOLD.name());
            result.setFibo_reason("Insufficient data");
            result.setFinal_signal(Signal.HOLD.name());
            result.setFinal_reason("Insufficient data");
            result.setFinal_confidence("LOW");
            return result;
        }

        // Price Action SR
        analyzePriceActionSR(currentPrice, candles, timeframe, result);

        // Fibonacci SR
        analyzeFiboSR(currentPrice, candles, result, timeframe);

        // Final merged signal
        mergeFinalSignal(result);

        return result;
    }

    // ---------------------- FINAL MERGE ----------------------
    private void mergeFinalSignal(PriceActionResult result) {
        Signal srSignal = Signal.valueOf(result.getSr_signal());
        Signal fiboSignal = Signal.valueOf(result.getFibo_signal());
        Trend trend = Trend.valueOf(result.getSr_trend());

        Signal finalSignal = Signal.HOLD;
        String finalReason = "HOLD - No strong confluence detected.";
        String finalConfidence = "LOW";

        if (srSignal == fiboSignal && srSignal != Signal.HOLD) {
            finalSignal = srSignal;
            finalReason = "Strong confluence: " + srSignal + " from both SR & Fibonacci.";
            finalConfidence = "HIGH";
        } else if ((srSignal != Signal.HOLD && fiboSignal == Signal.HOLD) ||
                   (fiboSignal != Signal.HOLD && srSignal == Signal.HOLD)) {
            finalSignal = srSignal != Signal.HOLD ? srSignal : fiboSignal;
            finalReason = "Signal from " + (srSignal != Signal.HOLD ? "Price Action SR" : "Fibonacci SR") +
                          " while other is neutral.";
            finalConfidence = "MEDIUM";
        } else if (srSignal != fiboSignal && srSignal != Signal.HOLD && fiboSignal != Signal.HOLD) {
            finalSignal = Signal.HOLD;
            finalReason = "Conflict between SR (" + srSignal + ") and Fibo (" + fiboSignal + "). Staying aside.";
            finalConfidence = "LOW";
        }

        if (finalSignal == Signal.BUY && trend == Trend.UPTREND) {
            finalConfidence = "VERY_HIGH";
            finalReason += " Supported by uptrend.";
        } else if (finalSignal == Signal.SELL && trend == Trend.DOWNTREND) {
            finalConfidence = "VERY_HIGH";
            finalReason += " Supported by downtrend.";
        } else if (trend == Trend.SIDEWAYS || trend == Trend.UNKNOWN) {
            if (!finalSignal.equals(Signal.HOLD)) {
                finalConfidence = "MEDIUM";
                finalReason += " But trend is sideways/unclear.";
            }
        }

        result.setFinal_signal(finalSignal.name());
        result.setFinal_reason(finalReason);
        result.setFinal_confidence(finalConfidence);
    }

    // ---------------------- PRICE ACTION SR ----------------------
    private void analyzePriceActionSR(BigDecimal currentPrice, List<PricesIndex> candles, String timeframe, PriceActionResult result) {
        if (candles == null || candles.isEmpty()) return;

        // Set exchange from first candle
        String exchange = candles.get(0).getExchange();
        result.setExchange(exchange);

        // Detect trend
        Trend trend = detectTrend(candles);
        result.setSr_trend(trend.name());

        // Calculate average volume and range
        BigDecimal avgVolume = candles.stream()
                .map(PricesIndex::getVolume)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(candles.size()), RoundingMode.HALF_UP);

        BigDecimal avgRange = candles.stream()
                .map(c -> c.getHigh().subtract(c.getLow()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(candles.size()), RoundingMode.HALF_UP);

        // Adaptive tolerance
        BigDecimal tolerance = avgRange.max(currentPrice.multiply(BigDecimal.valueOf(0.0015)));

        boolean intraday = INTRADAY_FRAMES.contains(timeframe.toUpperCase());

        // Adaptive maxDistance for MCX vs NFO
        BigDecimal maxDistance;
        if ("MCX".equalsIgnoreCase(exchange)) {
            maxDistance = avgRange.max(currentPrice.multiply(BigDecimal.valueOf(0.005))); // 0.5% or avgRange
        } else {
            maxDistance = intraday
                    ? currentPrice.multiply(BigDecimal.valueOf(0.005))  // 0.5%
                    : currentPrice.multiply(BigDecimal.valueOf(0.1));   // 10%
        }

        int maxSRZones = intraday ? MAX_SR_ZONES_INTRADAY : MAX_SR_ZONES_POSITIONAL;
        final int minTouches = 1; // 1 touch sufficient for all instruments

        List<SupportResistanceZone> supportZones = new ArrayList<>();
        List<SupportResistanceZone> resistanceZones = new ArrayList<>();

        for (int i = 0; i < candles.size(); i++) {
            PricesIndex c = candles.get(i);
            int age = candles.size() - 1 - i;
            if (c.getLow() != null) addOrUpdateZone(exchange, supportZones, c.getLow(), c.getVolume(), avgVolume, tolerance, age);
            if (c.getHigh() != null) addOrUpdateZone(exchange, resistanceZones, c.getHigh(), c.getVolume(), avgVolume, tolerance, age);
        }

        // Filter and sort zones
        supportZones = supportZones.stream()
                .filter(z -> z.getTouches() >= minTouches
                        && currentPrice.subtract(z.getLevel()).abs().compareTo(maxDistance) <= 0)
                .sorted(Comparator.comparing(z -> weightedDistance(z, currentPrice)))
                .limit(maxSRZones)
                .collect(Collectors.toList());

        resistanceZones = resistanceZones.stream()
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
            srReason = "BUY - Price near strong support zone.";
            srConfidence = "HIGH";
            result.setSr_stopLoss(supportZones.get(0).getLevel().subtract(tolerance));
            result.setSr_projectedTarget(resistanceZones.isEmpty() ? null : resistanceZones.get(0).getLevel());
        } else if (nearResistance && !nearSupport) {
            srSignal = Signal.SELL;
            srReason = "SELL - Price near strong resistance zone.";
            srConfidence = "HIGH";
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

    // Updated addOrUpdateZone() for exchange
    private void addOrUpdateZone(String exchange, List<SupportResistanceZone> zones, BigDecimal level, BigDecimal volume,
                                 BigDecimal avgVolume, BigDecimal tolerance, int ageInCandles) {
        for (SupportResistanceZone z : zones) {
            if (z.getLevel().subtract(level).abs().compareTo(tolerance) <= 0) {
                z.setTouches(z.getTouches() + 1);
                if ("MCX".equalsIgnoreCase(exchange)) {
                    z.setVolumeConfirmed(true); // ignore volume for MCX
                } else {
                    if (volume != null && volume.compareTo(avgVolume) > 0) z.setVolumeConfirmed(true);
                }
                z.setLastTouchAge(Math.min(z.getLastTouchAge(), ageInCandles));
                return;
            }
        }
        SupportResistanceZone newZone = new SupportResistanceZone();
        newZone.setLevel(level);
        newZone.setTouches(1);
        if ("MCX".equalsIgnoreCase(exchange)) {
            newZone.setVolumeConfirmed(true);
        } else {
            if (volume != null && volume.compareTo(avgVolume) > 0) newZone.setVolumeConfirmed(true);
        }
        newZone.setLastTouchAge(ageInCandles);
        zones.add(newZone);
    }





    // ---------------------- FIBONACCI SR ----------------------
    private void analyzeFiboSR(BigDecimal currentPrice, List<PricesIndex> candles, PriceActionResult result, String timeframe) {
        if (candles == null || candles.isEmpty()) return;

        int lookback = Math.min(candles.size(), 50);
        List<PricesIndex> recent = candles.subList(candles.size() - lookback, candles.size());

        BigDecimal high = recent.stream().map(PricesIndex::getHigh).max(BigDecimal::compareTo).orElse(currentPrice);
        BigDecimal low = recent.stream().map(PricesIndex::getLow).min(BigDecimal::compareTo).orElse(currentPrice);
        BigDecimal range = high.subtract(low);

        BigDecimal tolerance = range.multiply(BigDecimal.valueOf(0.02)).max(currentPrice.multiply(BigDecimal.valueOf(0.002)));

        BigDecimal[] ratios = {BigDecimal.valueOf(0.236), BigDecimal.valueOf(0.382),
                BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.618), BigDecimal.valueOf(0.786)};

        List<FibonacciLevel> fiboSupports = new ArrayList<>();
        List<FibonacciLevel> fiboResistances = new ArrayList<>();

        for (BigDecimal r : ratios) {
            BigDecimal supportLevel = low.add(range.multiply(r)).setScale(2, RoundingMode.HALF_UP);
            fiboSupports.add(new FibonacciLevel(supportLevel, r.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP) + "% (" + supportLevel + ")"));

            BigDecimal resistanceLevel = high.subtract(range.multiply(r)).setScale(2, RoundingMode.HALF_UP);
            fiboResistances.add(new FibonacciLevel(resistanceLevel, r.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP) + "% (" + resistanceLevel + ")"));
        }

        fiboSupports.sort(Comparator.comparing(f -> currentPrice.subtract(f.getLevel()).abs()));
        fiboResistances.sort(Comparator.comparing(f -> f.getLevel().subtract(currentPrice).abs()));

        result.setFibo_supports(fiboSupports);
        result.setFibo_resistances(fiboResistances);

        FibonacciLevel nearest = !fiboSupports.isEmpty() && !fiboResistances.isEmpty()
                ? (currentPrice.subtract(fiboSupports.get(0).getLevel()).abs()
                .compareTo(fiboResistances.get(0).getLevel().subtract(currentPrice).abs()) < 0
                ? fiboSupports.get(0) : fiboResistances.get(0))
                : (fiboSupports.isEmpty() ? (fiboResistances.isEmpty() ? null : fiboResistances.get(0)) : fiboSupports.get(0));

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
   
    private BigDecimal weightedDistance(SupportResistanceZone zone, BigDecimal currentPrice) {
        BigDecimal distance = currentPrice.subtract(zone.getLevel()).abs();
        BigDecimal strength = BigDecimal.valueOf(zone.getTouches())
                .multiply(zone.isVolumeConfirmed() ? BigDecimal.ONE : BigDecimal.valueOf(0.5))
                .divide(BigDecimal.valueOf(zone.getLastTouchAge() + 1), 8, RoundingMode.HALF_UP);
        if (strength.compareTo(BigDecimal.ZERO) == 0) return distance;
        return distance.divide(strength, 8, RoundingMode.HALF_UP);
    }

    private Trend detectTrend(List<PricesIndex> candles) {
        if (candles.size() < 5) return Trend.SIDEWAYS;
        long upCount = candles.stream().filter(c -> c.getClose().compareTo(c.getOpen()) > 0).count();
        long downCount = candles.stream().filter(c -> c.getClose().compareTo(c.getOpen()) < 0).count();
        if (upCount > downCount) return Trend.UPTREND;
        if (downCount > upCount) return Trend.DOWNTREND;
        return Trend.SIDEWAYS;
    }
}
