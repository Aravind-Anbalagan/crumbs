package com.crumbs.trade.scheduler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.crumbs.trade.dto.TrendChartResponse;
import com.crumbs.trade.dto.TrendPointDto;
import com.crumbs.trade.dto.TrendState;
import com.crumbs.trade.repo.StraddleIntradayRepo;
import com.crumbs.trade.service.StraddleTrendCache;
import com.crumbs.trade.service.StraddleTrendService;

@Component
public class StraddleTrendScheduler {

    private static final Logger log = LoggerFactory.getLogger(StraddleTrendScheduler.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final StraddleIntradayRepo straddleRepo;
    private final StraddleTrendService trendService;
    private final StraddleTrendCache   trendCache;

    private final Map<String, LocalDateTime> lastProcessedTs    = new ConcurrentHashMap<>();
    private final Map<String, TrendState>    lastConfirmedState = new ConcurrentHashMap<>();
    private volatile LocalDate activeSessionDate = LocalDate.now(IST);

    public StraddleTrendScheduler(
            StraddleIntradayRepo straddleRepo,
            StraddleTrendService trendService,
            StraddleTrendCache trendCache) {
        this.straddleRepo = straddleRepo;
        this.trendService = trendService;
        this.trendCache   = trendCache;
    }

    /**
     * Runs every 1 minute at :05s IST (Mon-Fri).
     * Automatically picks up CRUDEOIL, NIFTY, GOLDM, and any future index (e.g. SENSEX).
     */
    @Scheduled(cron = "5 * * * * MON-FRI", zone = "Asia/Kolkata")
    public void scanAndEvaluateEveryMinute() {
        LocalDate today = LocalDate.now(IST);
        resetIfNewTradingDay(today);

        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end   = today.atTime(LocalTime.MAX);

        List<Object[]> activePairs = straddleRepo.findActiveInstruments(start, end);
        if (activePairs.isEmpty()) {
            return;
        }

        for (Object[] pair : activePairs) {
            String name   = (String) pair[0];
            String expiry = (String) pair[1];
            String key    = name.toUpperCase() + "|" + expiry.toUpperCase();

            try {
                TrendChartResponse response = trendService.evaluateSession(name, expiry, today, null);
                TrendPointDto latest = response.latest();
                if (latest == null) {
                    continue;
                }

                // Always keep RAM cache warm for UI reads
                trendCache.put(response);

                // Skip alert check if no new candle arrived since previous run (e.g. NIFTY after 15:30)
                LocalDateTime prevTs = lastProcessedTs.get(key);
                if (prevTs != null && !latest.timestamp().isAfter(prevTs)) {
                    continue;
                }
                lastProcessedTs.put(key, latest.timestamp());

                // Seed state on first run after restart without sending a duplicate Telegram alert
                TrendState prevState = lastConfirmedState.put(key, latest.confirmedState());
                if (prevState == null) {
                    log.info("Seeded trend state for {} ({}) -> {} @ {}",
                            name, expiry, latest.confirmedState(), latest.timeLabel());
                    continue;
                }

                // Dispatch Telegram flip alert (Start or End -> SIDEWAYS)
                if (prevState != latest.confirmedState()) {
                    trendService.sendFlipAlert(response, prevState, latest);
                }
            } catch (Exception ex) {
                log.error("Trend scheduler failed for {} ({}): {}", name, expiry, ex.getMessage(), ex);
            }
        }
        trendCache.markAllInstrumentsLoaded(true);
    }

    /**
     * Refreshes RAM cache immediately when strikeRadius is changed from the UI,
     * updating lastConfirmedState cleanly without firing a false Telegram flip.
     */
    public void refreshCacheAfterRadiusChange(String name) {
        LocalDate sessionDate = trendService.resolveTradeDate(null);
        List<Object[]> activePairs = straddleRepo.findActiveInstruments(
                sessionDate.atStartOfDay(), sessionDate.atTime(LocalTime.MAX));

        for (Object[] pair : activePairs) {
            String sym    = (String) pair[0];
            String expiry = (String) pair[1];
            if (!"ALL".equalsIgnoreCase(name) && !sym.equalsIgnoreCase(name)) {
                continue;
            }
            TrendChartResponse refreshed = trendService.evaluateSession(sym, expiry, sessionDate, null);
            if (refreshed.latest() != null) {
                trendCache.put(refreshed);
                String key = sym.toUpperCase() + "|" + expiry.toUpperCase();
                lastConfirmedState.put(key, refreshed.latest().confirmedState());
            }
        }
    }

    private void resetIfNewTradingDay(LocalDate today) {
        if (!today.equals(activeSessionDate)) {
            lastProcessedTs.clear();
            lastConfirmedState.clear();
            trendCache.clearForNewDay(today);
            activeSessionDate = today;
            log.info("Cleared trend cache & scheduler state for new session: {}", today);
        }
    }
}