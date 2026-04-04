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

    // --------------------------------
    // 15-min → Levels
    // --------------------------------
    @Scheduled(cron = "0 */15 * * * ?")
    //@Scheduled(fixedRate = 10000)
    public void runLevelGeneration() {

        LocalTime now = LocalTime.now();

        if (isNiftyTime(now)) {
            levelService.generateLevels("NIFTY");
        }

        if (isCrudeTime(now)) {
            levelService.generateLevels("CRUDEOIL");
        }
    }

    // --------------------------------
    // 1-min → Trade Engine
    // --------------------------------
    @Scheduled(fixedRate = 60000)
    //@Scheduled(fixedRate = 10000)
    public void runTradeEngine() {

        LocalTime now = LocalTime.now();

        levelService.processSymbol("NIFTY", isNiftyTime(now));
        levelService.processSymbol("CRUDEOIL", isCrudeTime(now));
    }

    // --------------------------------
    // Time Windows
    // --------------------------------
    private boolean isNiftyTime(LocalTime now) {
        return !now.isBefore(LocalTime.of(9, 15)) &&
               !now.isAfter(LocalTime.of(15, 15));
    }

    private boolean isCrudeTime(LocalTime now) {
        return !now.isBefore(LocalTime.of(16, 0)) &&
               !now.isAfter(LocalTime.of(23, 30));
    }
}