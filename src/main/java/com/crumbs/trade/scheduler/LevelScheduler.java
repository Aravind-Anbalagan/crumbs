package com.crumbs.trade.scheduler;

import com.crumbs.trade.service.LevelService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

@Component
@RequiredArgsConstructor
public class LevelScheduler {

    private final LevelService levelService;

    // -------------------------------------------------------------------------
    // 15-min → Levels (Generates support/resistance levels)
    // Runs every 15 mins. Since the methods inside have time checks, 
    // we can run this broad cron, but only MON-FRI.
    // -------------------------------------------------------------------------
    @Scheduled(cron = "0 0/15 * * * MON-FRI", zone = "Asia/Kolkata")
    public void runLevelGeneration() {
        LocalTime now = LocalTime.now();

        if (isNiftyTime(now)) {
            levelService.generateLevels("NIFTY");
        }

        if (isCrudeTime(now)) {
            levelService.generateLevels("CRUDEOIL");
        }
    }

    // -------------------------------------------------------------------------
    // 1-min → Trade Engine (The execution heart)
    // We must cover BOTH Nifty (09:15-15:30) and Crude (16:00-23:30)
    // -------------------------------------------------------------------------
    
    // NIFTY WINDOW: 09:15 - 15:30
    @Scheduled(cron = "0 15-59 9 * * MON-FRI", zone = "Asia/Kolkata")
    @Scheduled(cron = "0 * 10-14 * * MON-FRI", zone = "Asia/Kolkata")
    @Scheduled(cron = "0 0-30 15 * * MON-FRI", zone = "Asia/Kolkata")
    
    // CRUDE WINDOW: 16:00 - 23:30
    @Scheduled(cron = "0 * 16-22 * * MON-FRI", zone = "Asia/Kolkata") // 4:00 PM to 10:59 PM
    @Scheduled(cron = "0 0-30 23 * * MON-FRI", zone = "Asia/Kolkata") // 11:00 PM to 11:30 PM
    public void runTradeEngine() {
        LocalTime now = LocalTime.now();

        // Process Nifty if in Nifty window
        levelService.processSymbol("NIFTY", isNiftyTime(now));
        
        // Process Crude if in Crude window
        levelService.processSymbol("CRUDEOIL", isCrudeTime(now));
    }

    // -------------------------------------------------------------------------
    // Time Windows (Logic Helpers)
    // -------------------------------------------------------------------------
    private boolean isNiftyTime(LocalTime now) {
        // 09:15 to 15:15 (as per your requirement)
        return !now.isBefore(LocalTime.of(9, 15)) &&
               !now.isAfter(LocalTime.of(15, 15));
    }

    private boolean isCrudeTime(LocalTime now) {
        // 16:00 (4 PM) to 23:30 (11:30 PM)
        return !now.isBefore(LocalTime.of(16, 0)) &&
               !now.isAfter(LocalTime.of(23, 30));
    }
}