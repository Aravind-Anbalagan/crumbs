package com.crumbs.trade.service;

import com.crumbs.trade.dto.PriceActionResult;
import com.crumbs.trade.dto.SRLevelDTO;
import com.crumbs.trade.dto.SupportResistanceZone;
import com.crumbs.trade.entity.PricesIndex;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PriceActionService {

    // ==================== CONFIGURATION ====================

    private static final int     MAX_SR_ZONES             = 5;
    private static final int     MIN_TOUCHES_INTRADAY     = 2;
    private static final int     MIN_TOUCHES_POSITIONAL   = 3;
    private static final int     MIN_CANDLES_REQUIRED     = 10;

    private static final BigDecimal TOLERANCE_PCT          = BigDecimal.valueOf(0.0015);
    private static final BigDecimal MCX_MAX_DISTANCE_PCT   = BigDecimal.valueOf(0.005);
    private static final BigDecimal INTRADAY_MAX_DIST_PCT  = BigDecimal.valueOf(0.005);
    private static final BigDecimal POSITIONAL_MAX_DIST_PCT= BigDecimal.valueOf(0.1);

    private static final Set<String> INTRADAY_FRAMES       = Set.of("ONE_MINUTE", "FIVE_MINUTE", "THIRTY_MINUTE", "ONE_HOUR");
    private static final Set<String> VOLUME_IGNORED        = Set.of("MCX");

    // ==================== PUBLIC API ====================

    public PriceActionResult analyze(BigDecimal currentPrice, List<PricesIndex> candles, String timeframe) {
        log.debug("PriceActionService | price={} candles={} tf={}", currentPrice,
                candles != null ? candles.size() : 0, timeframe);

        PriceActionResult result = new PriceActionResult();
        result.setCurrentPrice(currentPrice);

        if (candles == null || candles.size() < MIN_CANDLES_REQUIRED) {
            result.setSupportLevels(Collections.emptyList());
            result.setResistanceLevels(Collections.emptyList());
            log.warn("Insufficient candles for analysis: {}", candles == null ? 0 : candles.size());
            return result;
        }

        String exchange = candles.get(0).getExchange();
        boolean intraday = INTRADAY_FRAMES.contains(timeframe.toUpperCase());
        int minTouches = intraday ? MIN_TOUCHES_INTRADAY : MIN_TOUCHES_POSITIONAL;

        // Compute averages in one pass
        BigDecimal totalVolume = BigDecimal.ZERO;
        BigDecimal totalRange  = BigDecimal.ZERO;
        int validCandles = 0;

        for (PricesIndex c : candles) {
            if (c.getVolume() != null) totalVolume = totalVolume.add(c.getVolume());
            if (c.getHigh() != null && c.getLow() != null) {
                totalRange = totalRange.add(c.getHigh().subtract(c.getLow()));
                validCandles++;
            }
        }

        if (validCandles == 0) return result;

        BigDecimal avgVolume = totalVolume.divide(BigDecimal.valueOf(candles.size()), RoundingMode.HALF_UP);
        BigDecimal avgRange  = totalRange.divide(BigDecimal.valueOf(validCandles), RoundingMode.HALF_UP);
        BigDecimal tolerance = avgRange.max(currentPrice.multiply(TOLERANCE_PCT));

        BigDecimal maxDistance = VOLUME_IGNORED.contains(exchange.toUpperCase())
                ? avgRange.max(currentPrice.multiply(MCX_MAX_DISTANCE_PCT))
                : intraday
                    ? currentPrice.multiply(INTRADAY_MAX_DIST_PCT)
                    : currentPrice.multiply(POSITIONAL_MAX_DIST_PCT);

        // Build zones
        Map<BigDecimal, SupportResistanceZone> supportMap    = new HashMap<>();
        Map<BigDecimal, SupportResistanceZone> resistanceMap = new HashMap<>();

        for (int i = 0; i < candles.size(); i++) {
            PricesIndex c = candles.get(i);
            int age = candles.size() - 1 - i;
            if (c.getLow()  != null) addOrUpdate(exchange, supportMap,    c.getLow(),  c.getVolume(), avgVolume, tolerance, age);
            if (c.getHigh() != null) addOrUpdate(exchange, resistanceMap, c.getHigh(), c.getVolume(), avgVolume, tolerance, age);
        }

        // Filter, sort, convert → SRLevelDTO
        List<SRLevelDTO> supportLevels = supportMap.values().stream()
                .filter(z -> z.getTouches() >= minTouches
                          && currentPrice.subtract(z.getLevel()).abs().compareTo(maxDistance) <= 0)
                .sorted(Comparator.comparing(z -> weightedDistance(z, currentPrice)))
                .limit(MAX_SR_ZONES)
                .map((z) -> toDTO(z, "Support"))
                .collect(Collectors.toList());

        List<SRLevelDTO> resistanceLevels = resistanceMap.values().stream()
                .filter(z -> z.getTouches() >= minTouches
                          && z.getLevel().subtract(currentPrice).abs().compareTo(maxDistance) <= 0)
                .sorted(Comparator.comparing(z -> weightedDistance(z, currentPrice)))
                .limit(MAX_SR_ZONES)
                .map((z) -> toDTO(z, "Resistance"))
                .collect(Collectors.toList());


        result.setSupportLevels(supportLevels);
        result.setResistanceLevels(resistanceLevels);

        return result;
    }

    // ==================== ZONE MANAGEMENT ====================

    private void addOrUpdate(String exchange,
                             Map<BigDecimal, SupportResistanceZone> map,
                             BigDecimal level, BigDecimal volume,
                             BigDecimal avgVolume, BigDecimal tolerance, int age) {

        SupportResistanceZone zone = null;
        for (Map.Entry<BigDecimal, SupportResistanceZone> e : map.entrySet()) {
            if (e.getKey().subtract(level).abs().compareTo(tolerance) <= 0) {
                zone = e.getValue();
                break;
            }
        }

        if (zone != null) {
            zone.setTouches(zone.getTouches() + 1);
            zone.setLastTouchAge(Math.min(zone.getLastTouchAge(), age));
            if (VOLUME_IGNORED.contains(exchange.toUpperCase()) ||
                    (volume != null && volume.compareTo(avgVolume) > 0)) {
                zone.setVolumeConfirmed(true);
            }
        } else {
            SupportResistanceZone newZone = new SupportResistanceZone();
            newZone.setLevel(level);
            newZone.setTouches(1);
            newZone.setLastTouchAge(age);
            newZone.setVolumeConfirmed(VOLUME_IGNORED.contains(exchange.toUpperCase()) ||
                    (volume != null && volume.compareTo(avgVolume) > 0));
            map.put(level, newZone);
        }
    }

    // ==================== CONVERSION ====================

    private SRLevelDTO toDTO(SupportResistanceZone z, String type) {
        SRLevelDTO dto = new SRLevelDTO();
        dto.setPrice(z.getLevel());
        dto.setTouches(z.getTouches());
        dto.setVolumeConfirmed(z.isVolumeConfirmed());
        dto.setCandlesSinceLastTouch(z.getLastTouchAge());
        dto.setStrength(calcStrength(z));
        // rejections / breakouts not tracked in SupportResistanceZone — default 0
        dto.setRejections(0);
        dto.setBreakouts(0);
        return dto;
    }

    private String calcStrength(SupportResistanceZone z) {
        int score = 0;
        score += z.getTouches() * 10;
        if (z.isVolumeConfirmed())    score += 20;
        if (z.getLastTouchAge() < 10) score += 10;
        if (score >= 60) return "CRITICAL";
        if (score >= 40) return "STRONG";
        if (score >= 20) return "MODERATE";
        return "WEAK";
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
}