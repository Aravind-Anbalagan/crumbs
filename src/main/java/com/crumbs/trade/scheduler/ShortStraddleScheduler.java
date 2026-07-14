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
    // ==================== NIFTY (9:20:00 AM - 3:30:59 PM) - now every second ====================
    @Schedules({
        @Scheduled(cron = "* 20-59 9 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "* * 10-14 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "* 0-30 15 * * MON-FRI", zone = "Asia/Kolkata") // Buffer extended to 3:30 PM
    })
    public void straddleIndices() {
        // Evaluates NIFTY first
        executeIfActive(() -> shortStraddleService.evaluate("NIFTY"));
        
        // Then immediately evaluates SENSEX
        executeIfActive(() -> shortStraddleService.evaluate("SENSEX"));
    }
    // ==================== CRUDE OIL (4:00:00 PM - 11:31:59 PM) - now every second ====================
    @Schedules({
        @Scheduled(cron = "* * 16-22 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "* 0-31 23 * * MON-FRI", zone = "Asia/Kolkata")
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