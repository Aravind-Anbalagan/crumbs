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
import java.util.concurrent.ConcurrentHashMap;
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

    // FIX 3: FIFTEEN_MINUTE was missing — fell through to positional logic
    private static final Set<String> INTRADAY_FRAMES = Set.of(
            "ONE_MINUTE", "FIVE_MINUTE", "FIFTEEN_MINUTE", "THIRTY_MINUTE", "ONE_HOUR");
    private static final Set<String> VOLUME_IGNORED  = Set.of("MCX");

    // ==================== RANGE LOCK CACHE ====================
    //
    // Once a zone is detected it is stored in the locked cache keyed by
    // instrument + timeframe (e.g. "NIFTY|FIVE_MINUTE").
    //
    // On every analyze() call:
    //   1. Zones whose level has been broken by a confirmed CLOSE are evicted.
    //   2. Fresh zones from the latest candle scan are merged in — existing locked
    //      zones just get their stats updated, new zones are pinned.
    //   3. The locked list (not the fresh scan) is what gets returned.
    //
    // This makes levels stable between calls. They only move when price
    // closes through them — wicks alone are ignored.

    private static final ConcurrentHashMap<String, LockedZoneState> ZONE_LOCK_CACHE
            = new ConcurrentHashMap<>();

    private static class LockedZoneState {
        final List<LockedZone> supports    = new ArrayList<>();
        final List<LockedZone> resistances = new ArrayList<>();
    }

    private static class LockedZone {
        final BigDecimal    level;
        int                 touches;
        boolean             volumeConfirmed;
        int                 reacted;
        int                 broken;
        int                 lastTouchAge;
        final LocalDateTime lockedAt;

        LockedZone(BigDecimal level, int touches, boolean volumeConfirmed,
                   int reacted, int broken, int lastTouchAge) {
            this.level          = level;
            this.touches        = touches;
            this.volumeConfirmed = volumeConfirmed;
            this.reacted        = reacted;
            this.broken         = broken;
            this.lastTouchAge   = lastTouchAge;
            this.lockedAt       = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        }
    }

    // ==================== PUBLIC API ====================

    /**
     * Backward-compatible overload.
     * Derives a rough lockKey from the first candle's exchange field.
     * Callers that have an instrument name should use the 4-arg version
     * with lockKey = name + "|" + timeframe for proper isolation.
     */
    public PriceActionResult analyze(BigDecimal currentPrice,
                                     List<PricesIndex> candles,
                                     String timeframe) {
        String lockKey = (candles != null && !candles.isEmpty()
                && candles.get(0).getExchange() != null)
                ? candles.get(0).getExchange() + "|" + timeframe
                : "UNKNOWN|" + timeframe;
        return analyze(currentPrice, candles, timeframe, lockKey);
    }

    /**
     * Primary entry point.
     *
     * @param lockKey  Unique key per instrument + timeframe, e.g. "NIFTY|FIVE_MINUTE".
     *                 Pass this from callers that know the instrument name to ensure
     *                 each instrument has its own isolated locked-zone list.
     */
    public PriceActionResult analyze(BigDecimal currentPrice,
                                     List<PricesIndex> candles,
                                     String timeframe,
                                     String lockKey) {
        log.debug("PriceActionService.analyze | price={} candles={} tf={} key={}",
                currentPrice, candles != null ? candles.size() : 0, timeframe, lockKey);

        PriceActionResult result = new PriceActionResult();
        result.setCurrentPrice(currentPrice);

        if (candles == null || candles.size() < MIN_CANDLES_REQUIRED) {
            result.setSupportLevels(Collections.emptyList());
            result.setResistanceLevels(Collections.emptyList());
            log.warn("Insufficient candles for {}: {}", lockKey, candles == null ? 0 : candles.size());
            return result;
        }

        String  exchange      = candles.get(0).getExchange();
        boolean intraday      = INTRADAY_FRAMES.contains(timeframe.toUpperCase());
        int     minTouches    = intraday ? MIN_TOUCHES_INTRADAY : MIN_TOUCHES_POSITIONAL;
        int     candleMinutes = resolveCandleMinutes(timeframe);

        // ── Single-pass averages ─────────────────────────────────────────────
        BigDecimal totalVolume  = BigDecimal.ZERO;
        BigDecimal totalRange   = BigDecimal.ZERO;
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

        // ── Build fresh zones from latest candle scan ────────────────────────
        // FIX 7: TreeMap gives O(log n) zone lookup via floor/ceiling entries
        TreeMap<BigDecimal, SupportResistanceZone> supportMap    = new TreeMap<>();
        TreeMap<BigDecimal, SupportResistanceZone> resistanceMap = new TreeMap<>();

        for (int i = 0; i < candles.size(); i++) {
            PricesIndex c   = candles.get(i);
            int         age = candles.size() - 1 - i;

            // ── Support: candle lows ─────────────────────────────────────────
            if (c.getLow() != null) {
                boolean suppRejection = (i + 1 < candles.size())
                        && candles.get(i + 1).getClose().compareTo(c.getLow()) > 0;
                boolean suppBreakout = (i > 0)
                        && candles.get(i - 1).getClose().compareTo(c.getLow()) > 0
                        && c.getClose().compareTo(c.getLow()) < 0;
                addOrUpdate(exchange, supportMap, c.getLow(), c.getVolume(),
                        avgVolume, tolerance, age, suppRejection, suppBreakout, candleMinutes);
            }

            // ── Resistance: candle highs ─────────────────────────────────────
            if (c.getHigh() != null) {
                boolean resRejection = (i + 1 < candles.size())
                        && candles.get(i + 1).getClose().compareTo(c.getHigh()) < 0;
                boolean resBreakout = (i > 0)
                        && candles.get(i - 1).getClose().compareTo(c.getHigh()) < 0
                        && c.getClose().compareTo(c.getHigh()) > 0;
                addOrUpdate(exchange, resistanceMap, c.getHigh(), c.getVolume(),
                        avgVolume, tolerance, age, resRejection, resBreakout, candleMinutes);
            }
        }

        // ── RANGE LOCK: purge broken → merge fresh → return stable list ──────
        LockedZoneState locked = ZONE_LOCK_CACHE.computeIfAbsent(lockKey, k -> new LockedZoneState());

        // Step 1 — evict zones whose level has been broken by a confirmed close
        purgeBreachedZones(locked.supports,    currentPrice, tolerance, true);
        purgeBreachedZones(locked.resistances, currentPrice, tolerance, false);

        // Step 2 — merge: update existing locked zones, pin new ones
        mergeFreshIntoLocked(locked.supports,    supportMap.values(),    tolerance);
        mergeFreshIntoLocked(locked.resistances, resistanceMap.values(), tolerance);

        // Step 3 — filter locked list by distance and minTouches, return as DTO
        // FIX 2: directional comparisons — no abs(). Supports are below price,
        //         resistances are above. Mixed-side levels are filtered out.
        List<SRLevelDTO> supportLevels = locked.supports.stream()
                .filter(z -> z.touches >= minTouches
                          && z.level.compareTo(currentPrice) <= 0
                          && currentPrice.subtract(z.level).compareTo(maxDistance) <= 0)
                .sorted(Comparator.comparing(z -> lockedWeightedDistance(z, currentPrice)))
                .limit(MAX_SR_ZONES)
                .map(z -> lockedToDTO(z, candleMinutes, true))
                .collect(Collectors.toList());

        List<SRLevelDTO> resistanceLevels = locked.resistances.stream()
                .filter(z -> z.touches >= minTouches
                          && z.level.compareTo(currentPrice) >= 0
                          && z.level.subtract(currentPrice).compareTo(maxDistance) <= 0)
                .sorted(Comparator.comparing(z -> lockedWeightedDistance(z, currentPrice)))
                .limit(MAX_SR_ZONES)
                .map(z -> lockedToDTO(z, candleMinutes, false))
                .collect(Collectors.toList());

        result.setSupportLevels(supportLevels);
        result.setResistanceLevels(resistanceLevels);

        log.debug("RangeLock [{}] locked S={} R={} | returned S={} R={}",
                lockKey,
                locked.supports.size(), locked.resistances.size(),
                supportLevels.size(), resistanceLevels.size());

        return result;
    }

    // ==================== RANGE LOCK HELPERS ====================

    /**
     * Evicts zones that have been broken by a confirmed close.
     * Wicks alone (high/low) do NOT trigger eviction — only the close price matters.
     *
     * Support broken  → close is below (level - tolerance)
     * Resistance broken → close is above (level + tolerance)
     */
    private void purgeBreachedZones(List<LockedZone> zones,
                                    BigDecimal currentPrice,
                                    BigDecimal tolerance,
                                    boolean isSupport) {
        zones.removeIf(z -> {
            boolean broken = isSupport
                    ? currentPrice.compareTo(z.level.subtract(tolerance)) < 0
                    : currentPrice.compareTo(z.level.add(tolerance)) > 0;
            if (broken)
                log.info("🔴 {} BROKEN → evicted: {} (close={})",
                        isSupport ? "Support" : "Resistance", z.level, currentPrice);
            return broken;
        });
    }

    /**
     * For each freshly detected zone:
     *   - If an existing locked zone is within tolerance → update its stats (level stays pinned).
     *   - If no match exists → add as a new locked zone.
     */
    private void mergeFreshIntoLocked(List<LockedZone> locked,
                                      Collection<SupportResistanceZone> fresh,
                                      BigDecimal tolerance) {
        for (SupportResistanceZone fz : fresh) {
            LockedZone existing = findLockedZone(locked, fz.getLevel(), tolerance);
            if (existing != null) {
                // Update stats — the level itself never moves
                existing.touches        = Math.max(existing.touches, fz.getTouches());
                existing.reacted        = Math.max(existing.reacted,  fz.getReacted());
                existing.broken         = Math.max(existing.broken,   fz.getBroken());
                existing.lastTouchAge   = Math.min(existing.lastTouchAge, fz.getLastTouchAge());
                if (fz.isVolumeConfirmed()) existing.volumeConfirmed = true;
            } else {
                locked.add(new LockedZone(
                        fz.getLevel(),
                        fz.getTouches(),
                        fz.isVolumeConfirmed(),
                        fz.getReacted(),
                        fz.getBroken(),
                        fz.getLastTouchAge()
                ));
                log.debug("🔒 Locked new zone: {}", fz.getLevel());
            }
        }
    }

    private LockedZone findLockedZone(List<LockedZone> locked,
                                      BigDecimal price,
                                      BigDecimal tolerance) {
        return locked.stream()
                .filter(z -> z.level.subtract(price).abs().compareTo(tolerance) <= 0)
                .findFirst()
                .orElse(null);
    }

    // ── Cache management ─────────────────────────────────────────────────────

    /** Call this at market open, or on a strategy reset, to force fresh detection. */
    public void clearLockedZones(String lockKey) {
        ZONE_LOCK_CACHE.remove(lockKey);
        log.info("🔄 Cleared locked zones for: {}", lockKey);
    }

    /** Clear all instruments (e.g. overnight job before next session). */
    public void clearAllLockedZones() {
        ZONE_LOCK_CACHE.clear();
        log.info("🔄 Cleared all locked zones");
    }

    // ==================== ZONE MANAGEMENT ====================

    private void addOrUpdate(String exchange,
                             TreeMap<BigDecimal, SupportResistanceZone> map,
                             BigDecimal level,
                             BigDecimal volume,
                             BigDecimal avgVolume,
                             BigDecimal tolerance,
                             int age,
                             boolean isRejection,
                             boolean isBreakout,
                             int candleMinutes) {
        SupportResistanceZone zone = findZone(map, level, tolerance);

        if (zone != null) {
            zone.setTouches(zone.getTouches() + 1);
            zone.setLastTouchAge(Math.min(zone.getLastTouchAge(), age));
            if (isRejection) zone.setReacted(zone.getReacted() + 1);
            if (isBreakout)  zone.setBroken(zone.getBroken() + 1);
            if (isVolumeConfirmed(exchange, volume, avgVolume)) zone.setVolumeConfirmed(true);
        } else {
            // FIX 4: pass actual candleMinutes instead of hardcoded 5
            SupportResistanceZone newZone = new SupportResistanceZone(
                    level,
                    1,
                    formatTimestamp(age, candleMinutes),
                    age,
                    isRejection ? 1 : 0,
                    isBreakout  ? 1 : 0,
                    isVolumeConfirmed(exchange, volume, avgVolume)
            );
            map.put(level, newZone);
        }
    }

    /**
     * FIX 7: O(log n) zone lookup using TreeMap floor/ceiling.
     * Previous LinkedHashMap approach was O(n) per candle → O(n²) total.
     */
    private SupportResistanceZone findZone(TreeMap<BigDecimal, SupportResistanceZone> map,
                                           BigDecimal price,
                                           BigDecimal tolerance) {
        BigDecimal lo = price.subtract(tolerance);
        BigDecimal hi = price.add(tolerance);

        Map.Entry<BigDecimal, SupportResistanceZone> floor = map.floorEntry(hi);
        if (floor != null && floor.getKey().compareTo(lo) >= 0)
            return floor.getValue();

        Map.Entry<BigDecimal, SupportResistanceZone> ceil = map.ceilingEntry(lo);
        if (ceil != null && ceil.getKey().compareTo(hi) <= 0)
            return ceil.getValue();

        return null;
    }

    private boolean isVolumeConfirmed(String exchange, BigDecimal volume, BigDecimal avgVolume) {
        return VOLUME_IGNORED.contains(exchange.toUpperCase())
                || (volume != null
                    && volume.compareTo(avgVolume.multiply(BigDecimal.valueOf(1.5))) > 0);
    }

    // ==================== SCORING ====================

    /**
     * FIX 6: Balanced thresholds.
     *
     * Old thresholds (CRITICAL=60, HIGH=30) were never reachable at MIN_TOUCHES=2
     * with no rejections: max score = 2×10 = 20 → always LOW.
     *
     * New scoring:
     *   Each rejection  : +20 pts
     *   Each touch      : +10 pts
     *   Each breakout   : -30 pts (penalises zones that failed to hold)
     *   Volume confirm  : +15 pts
     *
     *   CRITICAL (≥40) : e.g. 2 touches + 1 rejection           = 40
     *   HIGH     (≥20) : e.g. 2 touches                         = 20
     *   LOW      (<20) : single touch, no reaction              = 10
     */
    private String calcConfidence(int touches, int reacted, int broken, boolean volumeConfirmed) {
        int score = (reacted * 20) + (touches * 10) - (broken * 30);
        if (volumeConfirmed) score += 15;
        if (score >= 40) return "CRITICAL";
        if (score >= 20) return "HIGH";
        return "LOW";
    }

    // ==================== CONVERSION ====================

    private SRLevelDTO lockedToDTO(LockedZone z, int candleMinutes, boolean isSupport) {
        SRLevelDTO dto = new SRLevelDTO();
        dto.setPrice(z.level);
        dto.setVisited(z.touches);
        dto.setHeavyVolume(z.volumeConfirmed);
        dto.setLastVisited(formatLastVisited(z.lastTouchAge, candleMinutes));
        dto.setConfidence(calcConfidence(z.touches, z.reacted, z.broken, z.volumeConfirmed));

        if (isSupport) {
            dto.setBounce(z.reacted);       // buyers defended → bounce
            dto.setBreakdown(z.broken);     // sellers punched through → breakdown
        } else {
            dto.setRejection(z.reacted);    // sellers defended → rejection
            dto.setBreakout(z.broken);      // buyers punched through → breakout
        }

        return dto;
    }

    // ==================== UTILITY ====================

    private BigDecimal lockedWeightedDistance(LockedZone zone, BigDecimal currentPrice) {
        BigDecimal distance = currentPrice.subtract(zone.level).abs();
        BigDecimal strength = BigDecimal.valueOf(zone.touches)
                .multiply(zone.volumeConfirmed ? BigDecimal.ONE : BigDecimal.valueOf(0.5))
                .divide(BigDecimal.valueOf(zone.lastTouchAge + 1), 8, RoundingMode.HALF_UP);
        if (strength.compareTo(BigDecimal.ZERO) == 0) return distance;
        return distance.divide(strength, 8, RoundingMode.HALF_UP);
    }

    private static String formatLastVisited(int candlesAgo, int candleMinutes) {
        if (candlesAgo == Integer.MAX_VALUE || candlesAgo < 0) return "Unknown";

        LocalDateTime visitedAt = LocalDateTime.now(ZoneId.of("Asia/Kolkata"))
                .minusMinutes((long) candlesAgo * candleMinutes);
        long minutesAgo = ChronoUnit.MINUTES.between(visitedAt, LocalDateTime.now(ZoneId.of("Asia/Kolkata")));

        String timeStr = visitedAt.format(DateTimeFormatter.ofPattern("HH:mm"));
        String dateStr = visitedAt.format(DateTimeFormatter.ofPattern("MMM dd"));

        if (minutesAgo < 60)   return minutesAgo + " mins ago (" + timeStr + ")";
        if (minutesAgo < 1440) return (minutesAgo / 60) + " hours ago (" + timeStr + ")";
        if (minutesAgo < 2880) return "Yesterday (" + timeStr + ")";
        return (minutesAgo / 1440) + " days ago (" + dateStr + ")";
    }

    // FIX 4: accepts actual candleMinutes instead of hardcoded 5
    private String formatTimestamp(int candlesAgo, int candleMinutes) {
        LocalDateTime visitedAt = LocalDateTime.now(ZoneId.of("Asia/Kolkata"))
                .minusMinutes((long) candlesAgo * candleMinutes);
        return visitedAt.format(DateTimeFormatter.ofPattern("MMM dd HH:mm"));
    }

    /** Maps the timeframe string to its candle duration in minutes. */
    private int resolveCandleMinutes(String timeframe) {
        return switch (timeframe.toUpperCase()) {
            case "ONE_MINUTE"      -> 1;
            case "FIVE_MINUTE"     -> 5;
            case "FIFTEEN_MINUTE"  -> 15;
            case "THIRTY_MINUTE"   -> 30;
            case "ONE_HOUR"        -> 60;
            case "FOUR_HOUR"       -> 240;
            default                -> 1440;  // ONE_DAY
        };
    }
}