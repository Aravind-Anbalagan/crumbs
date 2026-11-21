package com.crumbs.trade.scheduler;

import com.crumbs.trade.service.VwapStraddleService;
import com.crumbs.trade.utility.NSEWorkingDays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
public class VwapStraddleScheduler {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_START = LocalTime.of(9, 16);
    private static final LocalTime MARKET_END = LocalTime.of(15, 29);

    private final VwapStraddleService vwapStraddleService;

    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void resetDailyCache() {
        vwapStraddleService.clearDailyCache();
    }

    @Scheduled(cron = "5 * 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runOneMinuteStraddleMonitor() {
        LocalDate today = LocalDate.now(IST);
        if (!NSEWorkingDays.isNSEWorkingDay(today)) {
            return;
        }

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(MARKET_START) || now.isAfter(MARKET_END)) {
            return;
        }

        try {
            vwapStraddleService.processOneMinuteTick();
        } catch (Exception e) {
            log.error("VWAP Straddle 1-min tick failed: {}", e.getMessage(), e);
        }
    }
}