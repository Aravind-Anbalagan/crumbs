package com.crumbs.trade.scheduler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.crumbs.trade.service.FnoScannerService;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class FnoScannerScheduler {

    private static final Logger logger = LogManager.getLogger(FnoScannerScheduler.class);
    private static final String ZONE = "Asia/Kolkata";

    private final FnoScannerService fnoScannerService;

    /**
     * Step 1: Pre-caches yesterday's closing prices.
     * Runs at 8:30 AM IST, Monday to Friday.
     */
    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = ZONE)
    public void runMorningSetup() {
        logger.info("⏰ Triggering Morning F&O Scanner Setup...");
        fnoScannerService.precacheFnoPreviousClose();
    }

    /**
     * Step 2: Calculates % price move from yesterday's close using live WebSocket LTP.
     * Runs every 30 minutes between 9:15 AM and 3:15 PM IST, Monday to Friday.
     */
    @Scheduled(cron = "0 15/30 9-15 * * MON-FRI", zone = ZONE)
    public void run30MinPercentageCalculator() {
        logger.info("⏱️ Triggering 30-Minute F&O Percentage Change Calculation...");
        fnoScannerService.calculateFnoPercentageChange();
    }
}