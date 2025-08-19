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

    private static final int MAX_SR_ZONES_INTRADAY = 3;
    private static final int MAX_SR_ZONES_POSITIONAL = 5;

    // ---------------------- PUBLIC ANALYZE METHOD ----------------------
    public PriceActionResult analyze(BigDecimal currentPrice, List<PricesIndex> candles, String timeframe) {
        PriceActionResult result = new PriceActionResult();
        result.setCurrentPrice(currentPrice);

        if (candles == null || candles.size() < 5) {
            result.setSr_signal(Signal.HOLD.name());
            result.setSr_trend(Trend.UNKNOWN.name());
            result.setSr_reason("Insufficient data");
            return result;
        }

        // Price Action SR
        analyzePriceActionSR(currentPrice, candles, timeframe, result);

        // Fibonacci SR
        analyzeFiboSR(currentPrice, candles, result, timeframe);

        return result;
    }

    // ---------------------- PRICE ACTION SR ----------------------
    private void analyzePriceActionSR(BigDecimal currentPrice, List<PricesIndex> candles, String timeframe, PriceActionResult result) {

        Trend trend = detectTrend(candles);
        result.setSr_trend(trend.name());

        BigDecimal avgVolume = candles.stream()
                .map(PricesIndex::getVolume)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(candles.size()), RoundingMode.HALF_UP);

        BigDecimal avgRange = candles.stream()
                .map(c -> c.getHigh().subtract(c.getLow()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(candles.size()), RoundingMode.HALF_UP);

        BigDecimal tolerance = avgRange.max(currentPrice.multiply(BigDecimal.valueOf(0.002)));

        boolean intraday = Arrays.asList("ONE_MINUTE","FIVE_MINUTE","THIRTY_MINUTE","ONE_HOUR")
                .contains(timeframe.toUpperCase());

        BigDecimal maxDistance = intraday ? currentPrice.multiply(BigDecimal.valueOf(0.02))
                                          : currentPrice.multiply(BigDecimal.valueOf(0.1));
        int maxSRZones = intraday ? MAX_SR_ZONES_INTRADAY : MAX_SR_ZONES_POSITIONAL;

        List<SupportResistanceZone> supportZones = new ArrayList<>();
        List<SupportResistanceZone> resistanceZones = new ArrayList<>();

        for (int i = 0; i < candles.size(); i++) {
            PricesIndex c = candles.get(i);
            int age = candles.size() - 1 - i;
            if (c.getLow() != null) addOrUpdateZone(supportZones, c.getLow(), c.getVolume(), avgVolume, tolerance, age);
            if (c.getHigh() != null) addOrUpdateZone(resistanceZones, c.getHigh(), c.getVolume(), avgVolume, tolerance, age);
        }

        final int minTouches = 2;

        supportZones = supportZones.stream()
                .filter(z -> z.getTouches() >= minTouches && z.isVolumeConfirmed()
                        && currentPrice.subtract(z.getLevel()).abs().compareTo(maxDistance) <= 0)
                .sorted(Comparator.comparing(z -> weightedDistance(z, currentPrice)))
                .limit(maxSRZones)
                .collect(Collectors.toList());

        resistanceZones = resistanceZones.stream()
                .filter(z -> z.getTouches() >= minTouches && z.isVolumeConfirmed()
                        && z.getLevel().subtract(currentPrice).abs().compareTo(maxDistance) <= 0)
                .sorted(Comparator.comparing(z -> weightedDistance(z, currentPrice)))
                .limit(maxSRZones)
                .collect(Collectors.toList());

        result.setSr_nearestSupports(supportZones.stream().map(SupportResistanceZone::getLevel).toList());
        result.setSr_nearestResistances(resistanceZones.stream().map(SupportResistanceZone::getLevel).toList());

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

    // ---------------------- FIBONACCI SR ----------------------
    private void analyzeFiboSR(BigDecimal currentPrice, List<PricesIndex> candles, PriceActionResult result, String timeframe) {
        if (candles == null || candles.isEmpty()) return;

        int lookback = Math.min(candles.size(), 50);
        List<PricesIndex> recent = candles.subList(candles.size() - lookback, candles.size());

        BigDecimal high = recent.stream().map(PricesIndex::getHigh).max(BigDecimal::compareTo).orElse(currentPrice);
        BigDecimal low = recent.stream().map(PricesIndex::getLow).min(BigDecimal::compareTo).orElse(currentPrice);
        BigDecimal range = high.subtract(low);

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

        // Sort by distance to current price
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
        result.setFibo_signal("HOLD");
        result.setFibo_reason("Price not near Fibonacci SR");
        result.setFibo_confidence("LOW");
        result.setFibo_triggered(false);
    }

    // ---------------------- HELPERS ----------------------
    private void addOrUpdateZone(List<SupportResistanceZone> zones, BigDecimal level, BigDecimal volume,
                                 BigDecimal avgVolume, BigDecimal tolerance, int ageInCandles) {
        for (SupportResistanceZone z : zones) {
            if (z.getLevel().subtract(level).abs().compareTo(tolerance) <= 0) {
                z.setTouches(z.getTouches() + 1);
                if (volume != null && volume.compareTo(avgVolume) > 0) z.setVolumeConfirmed(true);
                z.setLastTouchAge(Math.min(z.getLastTouchAge(), ageInCandles));
                return;
            }
        }
        SupportResistanceZone newZone = new SupportResistanceZone();
        newZone.setLevel(level);
        newZone.setTouches(1);
        if (volume != null && volume.compareTo(avgVolume) > 0) newZone.setVolumeConfirmed(true);
        newZone.setLastTouchAge(ageInCandles);
        zones.add(newZone);
    }

    private BigDecimal weightedDistance(SupportResistanceZone zone, BigDecimal currentPrice) {
        BigDecimal distance = currentPrice.subtract(zone.getLevel()).abs();
        BigDecimal strength = BigDecimal.valueOf(zone.getTouches())
                .multiply(zone.isVolumeConfirmed() ? BigDecimal.ONE : BigDecimal.valueOf(0.5))
                .divide(BigDecimal.valueOf(zone.getLastTouchAge() + 1), 8, RoundingMode.HALF_UP);
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
