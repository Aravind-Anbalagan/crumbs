package com.crumbs.trade.scheduler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.crumbs.trade.service.FnoScannerService;
import lombok.RequiredArgsConstructor;

/**
 * FIXED Issue #16: Scheduler with distributed lock to prevent concurrent execution.
 *
 * Uses a simple in-memory lock mechanism. For multi-instance deployments,
 * replace with Redis-based distributed lock or Spring Integration lock registry.
 */
@Component
@RequiredArgsConstructor
public class FnoScannerScheduler {

    private static final Logger logger = LogManager.getLogger(FnoScannerScheduler.class);
    private static final String ZONE = "Asia/Kolkata";

    private final FnoScannerService fnoScannerService;

    // In-memory lock for single-instance deployments
    // For multi-instance: inject a DistributedLockProvider and use that instead
    private volatile long lastCalculationTimestamp = 0;
    private static final long CALCULATION_LOCK_TIMEOUT_MS = 15 * 60 * 1000; // 15 minutes

    /**
     * Step 1: Pre-caches yesterday's closing prices.
     * Runs at 9:20 AM IST (Monday–Friday).
     * Note: Execution time: ~2-3 minutes depending on network.
     */
    @Scheduled(cron = "0 20 9 * * MON-FRI", zone = ZONE)
    public void runMorningSetup() {
        logger.info("⏰ Triggering Morning F&O Scanner Setup...");
        try {
            fnoScannerService.precacheFnoPreviousClose();
            logger.info("✅ Morning setup completed successfully");
        } catch (Exception e) {
            logger.error("❌ Morning setup failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Step 2: Calculates % price move from yesterday's close using live WebSocket LTP.
     * Runs multiple times per day in 15-minute intervals:
     * - 9:30 AM (single run)
     * - 10:00 AM to 2:45 PM (every 15 min)
     * - 3:00 PM, 3:15 PM (closing times)
     *
     * FIXED Issue #16: Prevents overlapping executions.
     * If a calculation takes >15 minutes, next scheduled run is skipped.
     */
    @Scheduled(cron = "0 30 9 * * MON-FRI", zone = ZONE)
    public void run15MinPercentageCalculatorMorning() {
        logger.info("⏱️ [09:30 AM] Triggering 15-Minute F&O Percentage Change Calculation...");
        executePercentageCalculationWithLock();
    }

    @Scheduled(cron = "0 0,15,30,45 10-14 * * MON-FRI", zone = ZONE)
    public void run15MinPercentageCalculatorIntraday() {
        logger.info("⏱️ [Intraday] Triggering 15-Minute F&O Percentage Change Calculation...");
        executePercentageCalculationWithLock();
    }

    @Scheduled(cron = "0 0,15 15 * * MON-FRI", zone = ZONE)
    public void run15MinPercentageCalculatorClosing() {
        logger.info("⏱️ [Closing] Triggering 15-Minute F&O Percentage Change Calculation...");
        executePercentageCalculationWithLock();
    }

    /**
     * FIXED Issue #16: Execute percentage calculation with lock to prevent overlaps.
     *
     * This method checks if another instance is already running and skips execution if so.
     * For production multi-instance deployments, replace with DistributedLock backed by Redis.
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
            // Don't reset the timestamp here — let the lock expire naturally
            // This prevents rapid retry storms if the service is unhealthy
        }
    }
}
