package com.crumbs.trade.scheduler;

import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.FnoScannerService;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduler with state lock to prevent concurrent execution.
 * Includes database-driven master switch (executeIfActive) to toggle scanning.
 */
@Component
@RequiredArgsConstructor
public class FnoScannerScheduler {

    private static final Logger logger = LogManager.getLogger(FnoScannerScheduler.class);
    private static final String ZONE = "Asia/Kolkata";
    private static final String STRATEGY_NAME = "FNO_SCANNER";

    private final FnoScannerService fnoScannerService;
    private final StrategyRepo strategyRepo;

    // Atomic flag to track active execution state
    private final AtomicBoolean isCalculating = new AtomicBoolean(false);

    /**
     * Step 1: Pre-caches yesterday's closing prices.
     * Runs at 9:20 AM IST (Monday–Friday).
     */
    @Scheduled(cron = "0 20 9 * * MON-FRI", zone = ZONE)
    public void runMorningSetup() {
        executeIfActive(() -> {
            logger.info("⏰ Triggering Morning F&O Scanner Setup...");
            try {
                fnoScannerService.precacheFnoPreviousClose();
                logger.info("✅ Morning setup completed successfully");
            } catch (Exception e) {
                logger.error("❌ Morning setup failed: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * Step 2: Calculates % price move from yesterday's close using live WebSocket LTP.
     * Runs multiple times per day in 15-minute intervals.
     */
    @Scheduled(cron = "0 30 9 * * MON-FRI", zone = ZONE)
    public void run15MinPercentageCalculatorMorning() {
        executeIfActive(() -> {
            logger.info("⏱️ [09:30 AM] Triggering 15-Minute F&O Percentage Change Calculation...");
            executePercentageCalculationWithLock();
        });
    }

    @Scheduled(cron = "0 0,15,30,45 10-14 * * MON-FRI", zone = ZONE)
    public void run15MinPercentageCalculatorIntraday() {
        executeIfActive(() -> {
            logger.info("⏱️ [Intraday] Triggering 15-Minute F&O Percentage Change Calculation...");
            executePercentageCalculationWithLock();
        });
    }

    @Scheduled(cron = "0 0,15 15 * * MON-FRI", zone = ZONE)
    public void run15MinPercentageCalculatorClosing() {
        executeIfActive(() -> {
            logger.info("⏱️ [Closing] Triggering 15-Minute F&O Percentage Change Calculation...");
            executePercentageCalculationWithLock();
        });
    }

    /**
     * Execute percentage calculation with state lock to prevent overlaps.
     */
    private void executePercentageCalculationWithLock() {
        // Atomic check-and-set: returns false if already calculating
        if (!isCalculating.compareAndSet(false, true)) {
            logger.warn("⏭️  Skipping percentage calculation: Previous execution is still running");
            return;
        }

        try {
            fnoScannerService.calculateFnoPercentageChange();
            logger.info("✅ Percentage calculation completed successfully");
        } catch (Exception e) {
            logger.error("❌ Percentage calculation failed: {}", e.getMessage(), e);
        } finally {
            // Always reset lock back to false when execution finishes
            isCalculating.set(false);
        }
    }

    /**
     * Evaluates whether the strategy is active before invoking the scheduler's logic.
     */
    private void executeIfActive(Runnable tradeLogic) {
        if (isActive(STRATEGY_NAME)) {
            tradeLogic.run();
        } else {
            logger.debug("⏸️ Strategy {} is INACTIVE. Skipping scheduled execution.", STRATEGY_NAME);
        }
    }

    private boolean isActive(String strategyName) {
        Strategy strategy = strategyRepo.findByName(strategyName);
        return strategy != null && "Y".equalsIgnoreCase(strategy.getActive());
    }
}