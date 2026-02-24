package com.crumbs.trade.service;

import com.crumbs.trade.dto.PriceActionResult;
import com.crumbs.trade.dto.SRLevelDTO;
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

    // ==================== PUBLIC API ====================

    /**
     * Detects Support and Resistance zones from candle data.
     * Returns price lists + rich SRLevelDTO objects with touch/strength metadata.
     */
    public PriceActionResult analyzePredictive(BigDecimal currentPrice,
                                               List<PricesIndex> candles,
                                               String timeframe) {

        log.info("📐 S/R analysis | price={} | candles={} | tf={}",
                currentPrice, candles == null ? 0 : candles.size(), timeframe);

        if (candles == null || candles.size() < 10) {
            log.warn("Not enough candles for S/R analysis");
            return emptyResult(currentPrice);
        }

        // 0.2% of current price used for zone clustering
        BigDecimal tolerance = currentPrice.multiply(BigDecimal.valueOf(0.002));

        List<LevelAccumulator> supports    = detectLevels(candles, currentPrice, tolerance, true);
        List<LevelAccumulator> resistances = detectLevels(candles, currentPrice, tolerance, false);

        return buildResult(currentPrice, supports, resistances);
    }

    // ==================== LEVEL DETECTION ====================

    /**
     * Scans candles and clusters pivot prices (lows for support, highs for resistance)
     * into zones. A touch is only counted when price approaches from outside the zone.
     *
     * @param isSupport true → cluster candle lows; false → cluster candle highs
     */
    private List<LevelAccumulator> detectLevels(List<PricesIndex> candles,
                                                 BigDecimal currentPrice,
                                                 BigDecimal tolerance,
                                                 boolean isSupport) {

        Map<BigDecimal, LevelAccumulator> clusters = new LinkedHashMap<>();
        BigDecimal avgVolume = calcAvgVolume(candles);

        for (int i = 0; i < candles.size(); i++) {
            PricesIndex c = candles.get(i);
            BigDecimal pivot = isSupport ? c.getLow() : c.getHigh();

            // Only consider levels on the correct side of current price
            if (isSupport  && pivot.compareTo(currentPrice) >= 0) continue;
            if (!isSupport && pivot.compareTo(currentPrice) <= 0) continue;

            LevelAccumulator acc = findOrCreate(clusters, pivot, tolerance);

            // Count as a touch only when entering the zone from outside
            boolean approachFromOutside = i == 0 || (
                isSupport
                    ? candles.get(i - 1).getLow().compareTo(acc.price.add(tolerance)) > 0
                    : candles.get(i - 1).getHigh().compareTo(acc.price.subtract(tolerance)) < 0
            );
            if (approachFromOutside) {
                acc.registerTouch(candles.size() - 1 - i);
            }

            // Rejection: next candle closes back on the originating side
            if (i + 1 < candles.size()) {
                PricesIndex next = candles.get(i + 1);
                boolean rejected = isSupport
                        ? next.getClose().compareTo(pivot) > 0
                        : next.getClose().compareTo(pivot) < 0;
                if (rejected) acc.rejections++;
            }

            // Breakout: candle closed cleanly through the level
            if (i > 0) {
                PricesIndex prev = candles.get(i - 1);
                boolean broke = isSupport
                        ? (prev.getLow().compareTo(pivot) > 0 && c.getClose().compareTo(pivot) < 0)
                        : (prev.getHigh().compareTo(pivot) < 0 && c.getClose().compareTo(pivot) > 0);
                if (broke) acc.breakouts++;
            }

            // Volume confirmation
            if (c.getVolume() != null && avgVolume.compareTo(BigDecimal.ZERO) > 0) {
                if (c.getVolume().compareTo(avgVolume.multiply(BigDecimal.valueOf(1.2))) > 0) {
                    acc.volumeConfirmed = true;
                }
            }
        }

        // Filter (minimum 2 real touches) → sort by proximity → top 5
        return clusters.values().stream()
                .filter(a -> a.touches >= 2)
                .sorted(Comparator.comparing(a -> currentPrice.subtract(a.price).abs()))
                .limit(5)
                .collect(Collectors.toList());
    }

    // ==================== CLUSTER HELPERS ====================

    private LevelAccumulator findOrCreate(Map<BigDecimal, LevelAccumulator> map,
                                          BigDecimal price,
                                          BigDecimal tolerance) {
        for (Map.Entry<BigDecimal, LevelAccumulator> e : map.entrySet()) {
            if (e.getKey().subtract(price).abs().compareTo(tolerance) <= 0) {
                return e.getValue();
            }
        }
        LevelAccumulator acc = new LevelAccumulator(price);
        map.put(price, acc);
        return acc;
    }

    private static class LevelAccumulator {
        BigDecimal price;
        int touches             = 0;
        int rejections          = 0;
        int breakouts           = 0;
        boolean volumeConfirmed = false;
        int minCandlesAgo       = Integer.MAX_VALUE;

        LevelAccumulator(BigDecimal price) { this.price = price; }

        void registerTouch(int candlesAgo) {
            touches++;
            minCandlesAgo = Math.min(minCandlesAgo, candlesAgo);
        }

        String calcStrength() {
            int score = 0;
            score += touches    * 10;
            score += rejections * 15;
            score -= breakouts  * 20;
            if (volumeConfirmed)       score += 20;
            if (minCandlesAgo < 10)    score += 10; // recently tested
            if (breakouts == 0)        score += 15; // fresh / unbroken
            if (score >= 60) return "CRITICAL";
            if (score >= 40) return "STRONG";
            if (score >= 20) return "MODERATE";
            return "WEAK";
        }

        SRLevelDTO toDTO() {
            return new SRLevelDTO(
                price,
                touches,
                rejections,
                breakouts,
                volumeConfirmed,
                minCandlesAgo == Integer.MAX_VALUE ? -1 : minCandlesAgo,
                calcStrength()
            );
        }
    }

    // ==================== RESULT BUILDER ====================

    private PriceActionResult buildResult(BigDecimal currentPrice,
                                          List<LevelAccumulator> supports,
                                          List<LevelAccumulator> resistances) {

        PriceActionResult result = new PriceActionResult();
        result.setCurrentPrice(currentPrice);

        result.setSupportLevels(
            supports.stream().map(LevelAccumulator::toDTO).collect(Collectors.toList()));
        result.setResistanceLevels(
            resistances.stream().map(LevelAccumulator::toDTO).collect(Collectors.toList()));

        return result;
    }

    private PriceActionResult emptyResult(BigDecimal currentPrice) {
        PriceActionResult r = new PriceActionResult();
        r.setCurrentPrice(currentPrice);
        r.setSupportLevels(Collections.emptyList());
        r.setResistanceLevels(Collections.emptyList());
        return r;
    }

    // ==================== UTILITY ====================

    private BigDecimal calcAvgVolume(List<PricesIndex> candles) {
        long count = candles.stream()
                .map(PricesIndex::getVolume)
                .filter(Objects::nonNull)
                .count();
        if (count == 0) return BigDecimal.ZERO;

        BigDecimal sum = candles.stream()
                .map(PricesIndex::getVolume)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return sum.divide(BigDecimal.valueOf(count), RoundingMode.HALF_UP);
    }
}