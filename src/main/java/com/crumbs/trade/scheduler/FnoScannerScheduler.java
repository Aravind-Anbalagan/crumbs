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
     * Runs exactly at 9:16 AM IST, Monday to Friday.
     */
    @Scheduled(cron = "0 16 9 * * MON-FRI", zone = ZONE)
    public void runMorningSetup() {
        logger.info("⏰ Triggering Morning F&O Scanner Setup...");
        fnoScannerService.precacheFnoPreviousClose();
    }

    /**
     * Step 2: Calculates % price move from yesterday's close using live WebSocket LTP.
     * Runs every 30 minutes from 9:30 AM to 3:00 PM IST (last run before 3:15 PM), Monday to Friday.
     */
    @Scheduled(cron = "0 30 9 * * MON-FRI", zone = ZONE)       // Runs strictly at 9:30 AM
    @Scheduled(cron = "0 0,30 10-14 * * MON-FRI", zone = ZONE) // Runs every 30 mins from 10:00 AM to 2:30 PM
    @Scheduled(cron = "0 0 15 * * MON-FRI", zone = ZONE)       // Runs strictly at 3:00 PM
    public void run30MinPercentageCalculator() {
        logger.info("⏱️ Triggering 30-Minute F&O Percentage Change Calculation...");
        fnoScannerService.calculateFnoPercentageChange();
    }
}