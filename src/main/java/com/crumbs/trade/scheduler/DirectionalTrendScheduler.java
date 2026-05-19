package com.crumbs.trade.scheduler;

import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.DirectionalTrendService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor 
public class DirectionalTrendScheduler {

    private final DirectionalTrendService directionalTrendService;
    private final StrategyRepo strategyRepo;

    private static final String STRATEGY_NAME = "DIRECTIONAL_TREND";

    // ==================== EQUITY INDICES CRON TIME TUNING ====================
    // Session 1 (Morning): 9:20 AM - 12:59 PM
    // Session 2 (Afternoon): 1:30 PM - 3:20 PM
    @Schedules({
        @Scheduled(cron = "10 20-59 9 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "10 * 10-12 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "10 30-59 13 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "10 * 14 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "10 0-20 15 * * MON-FRI", zone = "Asia/Kolkata")
    })
    public void scanTrendIndices() {
        executeIfActive(() -> directionalTrendService.evaluate("NIFTY"));
        executeIfActive(() -> directionalTrendService.evaluate("SENSEX"));
    }

    // ==================== MCX COMMODITIES CRON TIME TUNING ====================
    // Evening Window: 4:00 PM - 11:31 PM
    @Schedules({
        @Scheduled(cron = "10 * 16-22 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "10 0-31 23 * * MON-FRI", zone = "Asia/Kolkata")
    })
    public void scanTrendCommodities() {
        executeIfActive(() -> directionalTrendService.evaluate("CRUDEOIL"));
        executeIfActive(() -> directionalTrendService.evaluate("NATURALGAS"));
    }

    private void executeIfActive(Runnable tradeLogic) {
        Strategy strategy = strategyRepo.findByName(STRATEGY_NAME);
        if (strategy != null && "Y".equalsIgnoreCase(strategy.getActive())) {
            tradeLogic.run();
        }
    }
}