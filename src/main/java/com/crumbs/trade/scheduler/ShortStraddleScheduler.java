package com.crumbs.trade.scheduler;

import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.ShortStraddleService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor // Automatically handles constructor injection for final fields
public class ShortStraddleScheduler {

    private final ShortStraddleService shortStraddleService;
    private final StrategyRepo strategyRepo;

    private static final String STRATEGY_NAME = "SHORT_STRADDLE";

    // ==================== NIFTY (9:20:10 AM - 3:21:10 PM) ====================
    @Schedules({
        @Scheduled(cron = "10 20-59 9 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "10 * 10-14 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "10 0-21 15 * * MON-FRI", zone = "Asia/Kolkata") 
    })
    public void straddleNifty() {
        executeIfActive(() -> shortStraddleService.evaluate("NIFTY"));
    }

    // ==================== CRUDE OIL (4:00:10 PM - 11:31:10 PM) ====================
    @Schedules({
        @Scheduled(cron = "10 * 16-22 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "10 0-31 23 * * MON-FRI", zone = "Asia/Kolkata")
    })
    public void straddleCrude() {
        executeIfActive(() -> shortStraddleService.evaluate("CRUDEOILM"));
    }

    /**
     * Helper to wrap the execution with an activity check.
     */
    private void executeIfActive(Runnable tradeLogic) {
        if (isActive(STRATEGY_NAME)) {
            tradeLogic.run();
        }
    }

    private boolean isActive(String strategy) {
        // Safe check for null and status value
        return strategyRepo.findByName(strategy) != null && 
               "Y".equalsIgnoreCase(strategyRepo.findByName(strategy).getActive());
    }
}