package com.crumbs.trade.scheduler;

import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.LevelService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

@Component
@RequiredArgsConstructor
public class LevelScheduler {

    private final LevelService levelService;
    private final StrategyRepo strategyRepo;

    // Constants for better maintenance
    private static final String SYMBOL_NIFTY = "NIFTY";
    private static final String SYMBOL_CRUDE = "CRUDEOIL";
    private static final String STRATEGY_MARKET_LEVEL = "MARKET_LEVEL";
    private static final String ACTIVE_STATUS = "Y";

    /**
     * 15-min → Level Generation
     * Runs every 15 mins MON-FRI.
     */
    @Scheduled(cron = "0 0/15 * * * MON-FRI", zone = "Asia/Kolkata")
    public void runLevelGeneration() {
        LocalTime now = LocalTime.now();
        boolean isMarketLevelActive = isActive(STRATEGY_MARKET_LEVEL);

        if (isMarketLevelActive) {
            if (isNiftyTime(now)) {
                levelService.generateLevels(SYMBOL_NIFTY);
            }

            if (isCrudeTime(now)) {
                levelService.generateLevels(SYMBOL_CRUDE);
            }
        }
    }

    /**
     * 1-min → Trade Engine
     * Covers Nifty (09:15-15:30) and Crude (16:00-23:30)
     */
    @Scheduled(cron = "0 15-59 9 * * MON-FRI", zone = "Asia/Kolkata")
    @Scheduled(cron = "0 * 10-14 * * MON-FRI", zone = "Asia/Kolkata")
    @Scheduled(cron = "0 0-30 15 * * MON-FRI", zone = "Asia/Kolkata")
    @Scheduled(cron = "0 * 16-22 * * MON-FRI", zone = "Asia/Kolkata")
    @Scheduled(cron = "0 0-30 23 * * MON-FRI", zone = "Asia/Kolkata")
    public void runTradeEngine() {
        LocalTime now = LocalTime.now();

        // Process Nifty and Crude based on their respective time windows
        levelService.processSymbol(SYMBOL_NIFTY, isNiftyTime(now));
        levelService.processSymbol(SYMBOL_CRUDE, isCrudeTime(now));
    }

    // -------------------------------------------------------------------------
    // Helper Methods
    // -------------------------------------------------------------------------

    private boolean isNiftyTime(LocalTime now) {
        // 09:15 to 15:15
        return !now.isBefore(LocalTime.of(9, 15)) && !now.isAfter(LocalTime.of(15, 15));
    }

    private boolean isCrudeTime(LocalTime now) {
        // 16:00 to 23:30
        return !now.isBefore(LocalTime.of(16, 0)) && !now.isAfter(LocalTime.of(23, 30));
    }

    private boolean isActive(String strategyName) {
        return strategyRepo.findByName(strategyName) != null && 
               ACTIVE_STATUS.equalsIgnoreCase(strategyRepo.findByName(strategyName).getActive());
    }
}