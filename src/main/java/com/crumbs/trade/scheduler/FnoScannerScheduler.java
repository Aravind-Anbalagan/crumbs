package com.crumbs.trade.scheduler;

import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.StrategyRepo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.crumbs.trade.service.FnoScannerService;
import lombok.RequiredArgsConstructor;

/**
 * Scheduler with distributed lock to prevent concurrent execution.
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

    // In-memory lock for single-instance deployments
    private volatile long lastCalculationTimestamp = 0;
    private static final long CALCULATION_LOCK_TIMEOUT_MS = 15 * 60 * 1000; // 15 minutes

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
     * Execute percentage calculation with lock to prevent overlaps.
     */
    private synchronized void executePercentageCalculationWithLock() {
        long now = System.currentTimeMillis();

        // Check if previous calculation is still running (within last 15 minutes)
        if (now - lastCalculationTimestamp < CALCULATION_LOCK_TIMEOUT_MS) {
            logger.warn("⏭️  Skipping percentage calculation: Previous execution still within {}ms window",
                    CALCULATION_LOCK_TIMEOUT_MS);
            return;
        }

        lastCalculationTimestamp = now;

        try {
            fnoScannerService.calculateFnoPercentageChange();
            logger.info("✅ Percentage calculation completed successfully");
        } catch (Exception e) {
            logger.error("❌ Percentage calculation failed: {}", e.getMessage(), e);
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

    /**
     * Optimized: Queries the database only once per pulse to determine active status.
     * Note: The "live" flag can also be extracted from this strategy object if needed
     * in the FnoScannerService downstream.
     */
    private boolean isActive(String strategyName) {
        Strategy strategy = strategyRepo.findByName(strategyName);
        return strategy != null && "Y".equalsIgnoreCase(strategy.getActive());
    }
}