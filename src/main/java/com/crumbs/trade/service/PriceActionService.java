package com.crumbs.trade.service;

import com.crumbs.trade.dto.PriceActionResult;
import com.crumbs.trade.dto.SRLevelDTO;
import com.crumbs.trade.dto.SupportResistanceZone;
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
public class PriceActionService {

    // ==================== CONFIGURATION ====================

    private static final int     MAX_SR_ZONES              = 5;
    private static final int     MIN_TOUCHES_INTRADAY      = 2;
    private static final int     MIN_TOUCHES_POSITIONAL    = 3;
    private static final int     MIN_CANDLES_REQUIRED      = 10;

    private static final BigDecimal TOLERANCE_PCT           = BigDecimal.valueOf(0.0015);
    private static final BigDecimal MCX_MAX_DISTANCE_PCT    = BigDecimal.valueOf(0.005);
    private static final BigDecimal INTRADAY_MAX_DIST_PCT   = BigDecimal.valueOf(0.005);
    private static final BigDecimal POSITIONAL_MAX_DIST_PCT = BigDecimal.valueOf(0.1);

    private static final Set<String> INTRADAY_FRAMES = Set.of(
            "ONE_MINUTE", "FIVE_MINUTE", "THIRTY_MINUTE", "ONE_HOUR");
    private static final Set<String> VOLUME_IGNORED  = Set.of("MCX");

    // ==================== PUBLIC API ====================

    public PriceActionResult analyze(BigDecimal currentPrice,
                                     List<PricesIndex> candles,
                                     String timeframe) {
        log.debug("PriceActionService | price={} candles={} tf={}",
                currentPrice, candles != null ? candles.size() : 0, timeframe);

        PriceActionResult result = new PriceActionResult();
        result.setCurrentPrice(currentPrice);

        if (candles == null || candles.size() < MIN_CANDLES_REQUIRED) {
            result.setSupportLevels(Collections.emptyList());
            result.setResistanceLevels(Collections.emptyList());
            log.warn("Insufficient candles: {}", candles == null ? 0 : candles.size());
            return result;
        }

        String  exchange   = candles.get(0).getExchange();
        boolean intraday   = INTRADAY_FRAMES.contains(timeframe.toUpperCase());
        int     minTouches = intraday ? MIN_TOUCHES_INTRADAY : MIN_TOUCHES_POSITIONAL;

        // ── Single-pass averages ──────────────────────────────────────────────
        BigDecimal totalVolume = BigDecimal.ZERO;
        BigDecimal totalRange  = BigDecimal.ZERO;
        int        validCandles = 0;

        for (PricesIndex c : candles) {
            if (c.getVolume() != null)
                totalVolume = totalVolume.add(c.getVolume());
            if (c.getHigh() != null && c.getLow() != null) {
                totalRange = totalRange.add(c.getHigh().subtract(c.getLow()));
                validCandles++;
            }
        }
        if (validCandles == 0) return result;

        BigDecimal avgVolume  = totalVolume.divide(BigDecimal.valueOf(candles.size()), RoundingMode.HALF_UP);
        BigDecimal avgRange   = totalRange.divide(BigDecimal.valueOf(validCandles),    RoundingMode.HALF_UP);
        BigDecimal tolerance  = avgRange.max(currentPrice.multiply(TOLERANCE_PCT));

        BigDecimal maxDistance = VOLUME_IGNORED.contains(exchange.toUpperCase())
                ? avgRange.max(currentPrice.multiply(MCX_MAX_DISTANCE_PCT))
                : intraday
                    ? currentPrice.multiply(INTRADAY_MAX_DIST_PCT)
                    : currentPrice.multiply(POSITIONAL_MAX_DIST_PCT);

        // ── Build zones ──────────────────────────────────────────────────────
        Map<BigDecimal, SupportResistanceZone> supportMap    = new LinkedHashMap<>();
        Map<BigDecimal, SupportResistanceZone> resistanceMap = new LinkedHashMap<>();

        for (int i = 0; i < candles.size(); i++) {
            PricesIndex c   = candles.get(i);
            int         age = candles.size() - 1 - i;

            // ── SUPPORT: candle lows ──────────────────────────────────────────
            if (c.getLow() != null) {
                boolean suppRejection = (i + 1 < candles.size())
                        && candles.get(i + 1).getClose().compareTo(c.getLow()) > 0;
                boolean suppBreakout = (i > 0)
                        && candles.get(i - 1).getClose().compareTo(c.getLow()) > 0
                        && c.getClose().compareTo(c.getLow()) < 0;

                addOrUpdate(exchange, supportMap, c.getLow(), c.getVolume(),
                            avgVolume, tolerance, age, suppRejection, suppBreakout);
            }

            // ── RESISTANCE: candle highs ─────────────────────────────────────
            if (c.getHigh() != null) {
                boolean resRejection = (i + 1 < candles.size())
                        && candles.get(i + 1).getClose().compareTo(c.getHigh()) < 0;
                boolean resBreakout = (i > 0)
                        && candles.get(i - 1).getClose().compareTo(c.getHigh()) < 0
                        && c.getClose().compareTo(c.getHigh()) > 0;

                addOrUpdate(exchange, resistanceMap, c.getHigh(), c.getVolume(),
                            avgVolume, tolerance, age, resRejection, resBreakout);
            }
        }

        // ── Filter → sort → convert ───────────────────────────────────────────
        List<SRLevelDTO> supportLevels = supportMap.values().stream()
                .filter(z -> z.getTouches() >= minTouches
                          && currentPrice.subtract(z.getLevel()).abs().compareTo(maxDistance) <= 0)
                .sorted(Comparator.comparing(z -> weightedDistance(z, currentPrice)))
                .limit(MAX_SR_ZONES)
                .map(z -> toDTO(z, intraday))
                .collect(Collectors.toList());

        List<SRLevelDTO> resistanceLevels = resistanceMap.values().stream()
                .filter(z -> z.getTouches() >= minTouches
                          && z.getLevel().subtract(currentPrice).abs().compareTo(maxDistance) <= 0)
                .sorted(Comparator.comparing(z -> weightedDistance(z, currentPrice)))
                .limit(MAX_SR_ZONES)
                .map(z -> toDTO(z, intraday))
                .collect(Collectors.toList());

        result.setSupportLevels(supportLevels);
        result.setResistanceLevels(resistanceLevels);
        return result;
    }

    // ==================== ZONE MANAGEMENT ====================

    private void addOrUpdate(String exchange,
                             Map<BigDecimal, SupportResistanceZone> map,
                             BigDecimal level,
                             BigDecimal volume,
                             BigDecimal avgVolume,
                             BigDecimal tolerance,
                             int age,
                             boolean isRejection,
                             boolean isBreakout) {

        SupportResistanceZone zone = findZone(map, level, tolerance);

        if (zone != null) {
            zone.setTouches(zone.getTouches() + 1);
            zone.setLastTouchAge(Math.min(zone.getLastTouchAge(), age));
            if (isRejection) zone.setReacted(zone.getReacted() + 1);  // ✅ DTO field
            if (isBreakout)  zone.setBroken(zone.getBroken() + 1);    // ✅ DTO field
            if (isVolumeConfirmed(exchange, volume, avgVolume))
                zone.setVolumeConfirmed(true);
        } else {
            // ✅ Full DTO constructor
            SupportResistanceZone newZone = new SupportResistanceZone(
                level,
                1,                                      // touches
                formatTimestamp(age),                   // lastTouchedDate
                age,                                    // lastTouchAge
                isRejection ? 1 : 0,                    // reacted
                isBreakout ? 1 : 0,                     // broken
                isVolumeConfirmed(exchange, volume, avgVolume)
            );
            map.put(level, newZone);
        }
    }

    private SupportResistanceZone findZone(Map<BigDecimal, SupportResistanceZone> map,
                                           BigDecimal price,
                                           BigDecimal tolerance) {
        for (Map.Entry<BigDecimal, SupportResistanceZone> e : map.entrySet()) {
            if (e.getKey().subtract(price).abs().compareTo(tolerance) <= 0)
                return e.getValue();
        }
        return null;
    }

    private boolean isVolumeConfirmed(String exchange, BigDecimal volume, BigDecimal avgVolume) {
        return VOLUME_IGNORED.contains(exchange.toUpperCase())
                || (volume != null && volume.compareTo(avgVolume.multiply(BigDecimal.valueOf(1.5))) > 0);
    }

    // ==================== SCORING ====================

    private String calcConfidence(SupportResistanceZone z) {
        int score = (z.getReacted() * 25) + (z.getTouches() * 10) - (z.getBroken() * 40);
        if (z.isVolumeConfirmed()) score += 15;
        
        if (score >= 60) return "CRITICAL";  // 🔴 Priority trades
        if (score >= 30) return "HIGH";      // 🟡 Watchlist  
        return "LOW";                        // 🟢 Background
    }

    // ==================== CONVERSION ====================

    private SRLevelDTO toDTO(SupportResistanceZone z, boolean intraday) {
        int candleMinutes = intraday ? 5 : 1440;

        SRLevelDTO dto = new SRLevelDTO();
        dto.setPrice(z.getLevel());
        dto.setVisited(z.getTouches());
        dto.setReacted(z.getReacted());          // ✅ Real data now
        dto.setBroken(z.getBroken());            // ✅ Real data now
        dto.setHeavyVolume(z.isVolumeConfirmed());
        dto.setLastVisited(formatLastVisited(z.getLastTouchAge(), candleMinutes));
        dto.setConfidence(calcConfidence(z));
        return dto;
    }

    // ==================== UTILITY ====================

    private BigDecimal weightedDistance(SupportResistanceZone zone, BigDecimal currentPrice) {
        BigDecimal distance = currentPrice.subtract(zone.getLevel()).abs();
        BigDecimal strength = BigDecimal.valueOf(zone.getTouches())
                .multiply(zone.isVolumeConfirmed() ? BigDecimal.ONE : BigDecimal.valueOf(0.5))
                .divide(BigDecimal.valueOf(zone.getLastTouchAge() + 1), 8, RoundingMode.HALF_UP);
        if (strength.compareTo(BigDecimal.ZERO) == 0) return distance;
        return distance.divide(strength, 8, RoundingMode.HALF_UP);
    }

    private static String formatLastVisited(int candlesAgo, int candleMinutes) {
        if (candlesAgo == Integer.MAX_VALUE || candlesAgo < 0) return "Unknown";

        LocalDateTime visitedAt = LocalDateTime.now(ZoneId.of("Asia/Kolkata"))
                .minusMinutes((long) candlesAgo * candleMinutes);
        LocalDateTime now       = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        long minutesAgo         = ChronoUnit.MINUTES.between(visitedAt, now);

        String timeStr = visitedAt.format(DateTimeFormatter.ofPattern("HH:mm"));
        String dateStr = visitedAt.format(DateTimeFormatter.ofPattern("MMM dd"));

        if (minutesAgo < 60)   return minutesAgo + " mins ago (" + timeStr + ")";
        if (minutesAgo < 1440) return (minutesAgo / 60) + " hours ago (" + timeStr + ")";
        if (minutesAgo < 2880) return "Yesterday (" + timeStr + ")";
        return (minutesAgo / 1440) + " days ago (" + dateStr + ")";
    }

    private String formatTimestamp(int candlesAgo) {
        LocalDateTime visitedAt = LocalDateTime.now(ZoneId.of("Asia/Kolkata"))
                .minusMinutes((long) candlesAgo * 5);  // Default 5min candles
        return visitedAt.format(DateTimeFormatter.ofPattern("MMM dd HH:mm"));
    }
}
