package com.crumbs.trade.scheduler;

import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.ShortStraddleService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor 
public class ShortStraddleScheduler {

    private final ShortStraddleService shortStraddleService;
    private final StrategyRepo strategyRepo;

    private static final String STRATEGY_NAME = "SHORT_STRADDLE";

    // ==================== NIFTY (9:20:10 AM - 3:30:10 PM) ====================
    @Schedules({
        @Scheduled(cron = "10 20-59 9 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "10 * 10-14 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "10 0-30 15 * * MON-FRI", zone = "Asia/Kolkata") // Buffer extended to 3:30 PM
    })
    public void straddleIndices() {
        // Evaluates NIFTY first
        executeIfActive(() -> shortStraddleService.evaluate("NIFTY"));
        
        // Then immediately evaluates SENSEX
        executeIfActive(() -> shortStraddleService.evaluate("SENSEX"));
    }

    // ==================== CRUDE OIL (4:00:10 PM - 11:31:10 PM) ====================
    @Schedules({
        @Scheduled(cron = "10 * 16-22 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "10 0-31 23 * * MON-FRI", zone = "Asia/Kolkata")
    })
    public void straddleCrude() {
        executeIfActive(() -> shortStraddleService.evaluate("CRUDEOIL"));
        executeIfActive(() -> shortStraddleService.evaluate("NATURALGAS"));
    }

    /**
     * Helper to wrap the execution with an activity check.
     */
    private void executeIfActive(Runnable tradeLogic) {
        if (isActive(STRATEGY_NAME)) {
            tradeLogic.run();
        }
    }

    /**
     * Optimized: Queries the database only once per pulse.
     */
    private boolean isActive(String strategyName) {
        Strategy strategy = strategyRepo.findByName(strategyName);
        return strategy != null && "Y".equalsIgnoreCase(strategy.getActive());
    }
}