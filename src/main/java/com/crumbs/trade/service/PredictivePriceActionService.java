package com.crumbs.trade.service;

import com.crumbs.trade.dto.PriceActionResult;
import com.crumbs.trade.dto.SRLevelDTO;
import com.crumbs.trade.entity.PricesIndex;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PredictivePriceActionService {

   

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
        String calcConfidence() {
            int score = touches * 10 + rejections * 15 - breakouts * 20;
            if (volumeConfirmed) score += 20;
            if (minCandlesAgo < 10) score += 10;
            if (breakouts == 0) score += 15;
            
            if (score >= 80) return "ABSOLUTE";
            if (score >= 60) return "HIGH";
            if (score >= 40) return "MODERATE";
            if (score >= 20) return "LOW";
            return "UNTESTED";
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

        SRLevelDTO toDTO(int candleMinutes, boolean isSupport) {
            SRLevelDTO dto = new SRLevelDTO();
            dto.setPrice(price);
            dto.setVisited(touches);
            dto.setHeavyVolume(volumeConfirmed);
            dto.setLastVisited(formatLastVisited(minCandlesAgo, candleMinutes));
            dto.setConfidence(calcConfidence());

            if (isSupport) {
                dto.setBounce(rejections);       // buyers defended → bounce
                dto.setBreakdown(breakouts);     // sellers punched through → breakdown
            } else {
                dto.setRejection(rejections);    // sellers defended → rejection
                dto.setBreakout(breakouts);      // buyers punched through → breakout
            }

            return dto;
        }
    }

   

    // Same formatLastVisited helper — add to PredictivePriceActionService too
    private static String formatLastVisited(int candlesAgo, int candleMinutes) {
        if (candlesAgo == Integer.MAX_VALUE || candlesAgo < 0) return "Unknown";
        
        LocalDateTime visitedAt = LocalDateTime.now(ZoneId.of("Asia/Kolkata"))
            .minusMinutes((long) candlesAgo * candleMinutes);
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        long minutesAgo = ChronoUnit.MINUTES.between(visitedAt, now);

        String timeStr = visitedAt.format(DateTimeFormatter.ofPattern("HH:mm"));
        String dateStr = visitedAt.format(DateTimeFormatter.ofPattern("MMM dd"));

        if (minutesAgo < 60) return minutesAgo + " mins ago (" + timeStr + ")";
        if (minutesAgo < 1440) return (minutesAgo / 60) + " hours ago (" + timeStr + ")";
        if (minutesAgo < 2880) return "Yesterday (" + timeStr + ")";
        return (minutesAgo / 1440) + " days ago (" + dateStr + ")";
    }
    // ==================== RESULT BUILDER ====================

    private PriceActionResult buildResult(BigDecimal currentPrice,
            List<LevelAccumulator> supports,
            List<LevelAccumulator> resistances,
            int candleMinutes) {
        PriceActionResult result = new PriceActionResult();
        result.setCurrentPrice(currentPrice);

        result.setSupportLevels(
                supports.stream()
                        .map(acc -> acc.toDTO(candleMinutes, true))    // isSupport = true
                        .collect(Collectors.toList()));

        result.setResistanceLevels(
                resistances.stream()
                        .map(acc -> acc.toDTO(candleMinutes, false))   // isSupport = false
                        .collect(Collectors.toList()));

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