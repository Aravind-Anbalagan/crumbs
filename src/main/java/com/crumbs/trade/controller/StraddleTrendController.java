package com.crumbs.trade.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.crumbs.trade.dto.InstrumentTrendDto;
import com.crumbs.trade.dto.TrendChartResponse;
import com.crumbs.trade.dto.TrendLatestResponse;
import com.crumbs.trade.dto.TrendPointDto;
import com.crumbs.trade.dto.TrendState;
import com.crumbs.trade.scheduler.StraddleTrendScheduler;
import com.crumbs.trade.service.StraddleTrendCache;
import com.crumbs.trade.service.StraddleTrendService;

@RestController
@RequestMapping("/api/trend")
public class StraddleTrendController {

    private final StraddleTrendService   trendService;
    private final StraddleTrendScheduler trendScheduler;
    private final StraddleTrendCache     trendCache;

    public StraddleTrendController(
            StraddleTrendService trendService,
            StraddleTrendScheduler trendScheduler,
            StraddleTrendCache trendCache) {
        this.trendService   = trendService;
        this.trendScheduler = trendScheduler;
        this.trendCache     = trendCache;
    }

    @GetMapping("/instruments")
    public ResponseEntity<List<InstrumentTrendDto>> getActiveInstruments(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        if (date == null && trendCache.isAllInstrumentsLoaded()) {
            List<InstrumentTrendDto> cached = trendCache.getAll().stream()
                    .sorted(Comparator.comparing(TrendChartResponse::name)
                            .thenComparing(TrendChartResponse::expiry))
                    .map(c -> {
                        TrendPointDto latest = c.latest();
                        return new InstrumentTrendDto(
                                c.name(),
                                c.expiry(),
                                c.strikeStep(),
                                c.strikeRadius(),
                                c.totalStrikes(),
                                latest != null ? latest.spot() : BigDecimal.ZERO,
                                latest != null ? latest.confirmedState() : TrendState.SIDEWAYS,
                                c.consolidatedMessage(),
                                latest != null ? latest.timestamp() : null
                        );
                    })
                    .toList();
            return ResponseEntity.ok(cached);
        }

        return ResponseEntity.ok(trendService.getActiveInstruments(date));
    }

    @GetMapping("/chart")
    public ResponseEntity<TrendChartResponse> getTrendChart(
            @RequestParam String name,
            @RequestParam String expiry,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer strikeRadius) {

        int defaultRadius = trendService.getStrikeRadius(name);
        int effectiveRadius = (strikeRadius == null)
                ? defaultRadius
                : trendService.clampRadius(strikeRadius);

        if (date == null && effectiveRadius == defaultRadius) {
            TrendChartResponse cached = trendCache.get(name, expiry).orElse(null);
            if (cached != null && cached.strikeRadius() == effectiveRadius) {
                return ResponseEntity.ok(cached);
            }
            TrendChartResponse computed = trendService.evaluateSession(name, expiry, null, null);
            if (computed.latest() != null) {
                trendCache.put(computed);
            }
            return ResponseEntity.ok(computed);
        }

        return ResponseEntity.ok(
                trendService.evaluateSession(name, expiry, date, effectiveRadius)
        );
    }

    @GetMapping("/latest")
    public ResponseEntity<TrendLatestResponse> getLatestTick(
            @RequestParam String name,
            @RequestParam String expiry,
            @RequestParam(required = false) Integer strikeRadius) {

        int defaultRadius = trendService.getStrikeRadius(name);
        int effectiveRadius = (strikeRadius == null)
                ? defaultRadius
                : trendService.clampRadius(strikeRadius);

        TrendChartResponse full;
        if (effectiveRadius == defaultRadius) {
            TrendChartResponse cached = trendCache.get(name, expiry).orElse(null);
            if (cached != null && cached.strikeRadius() == effectiveRadius) {
                full = cached;
            } else {
                full = trendService.evaluateSession(name, expiry, null, null);
                if (full.latest() != null) {
                    trendCache.put(full);
                }
            }
        } else {
            full = trendService.evaluateSession(name, expiry, null, effectiveRadius);
        }

        return ResponseEntity.ok(TrendLatestResponse.from(full));
    }

    @PutMapping("/config/radius")
    public ResponseEntity<?> updateStrikeRadius(
            @RequestParam(defaultValue = "ALL") String name,
            @RequestParam int radius) {

        if (radius < StraddleTrendService.ALL_STRIKES
                || radius > StraddleTrendService.MAX_RADIUS) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error",
                    "radius must be 0 (ALL) or between 1 and " + StraddleTrendService.MAX_RADIUS
            ));
        }

        int saved = trendService.updateStrikeRadius(name, radius);
        trendScheduler.refreshCacheAfterRadiusChange(name);

        int totalStrikes = (saved == StraddleTrendService.ALL_STRIKES) ? 0 : (2 * saved + 1);
        return ResponseEntity.ok(Map.of(
                "target", name.trim().toUpperCase(),
                "strikeRadius", saved,
                "totalStrikes", totalStrikes,
                "scope", saved == StraddleTrendService.ALL_STRIKES ? "ALL" : "±" + saved
        ));
    }
}