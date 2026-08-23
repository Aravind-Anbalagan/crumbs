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
     * Runs at 9:20 AM IST (Monday–Friday).
     * Note: Since this now runs at 9:20 AM, it will execute after the 9:15 AM market open.
     */
    @Scheduled(cron = "0 20 9 * * MON-FRI", zone = ZONE)
    public void runMorningSetup() {
        logger.info("⏰ Triggering Morning F&O Scanner Setup...");
        fnoScannerService.precacheFnoPreviousClose();
    }

    /**
     * Step 2: Calculates % price move from yesterday's close using live WebSocket LTP.
     * Runs every 15 minutes from 9:30 AM to 3:15 PM IST, Monday to Friday.
     */
    @Scheduled(cron = "0 30 9 * * MON-FRI", zone = ZONE)          // 9:30 AM
    @Scheduled(cron = "0 0,15,30,45 10-14 * * MON-FRI", zone = ZONE) // every 15 min, 10:00 AM–2:45 PM
    @Scheduled(cron = "0 0,15 15 * * MON-FRI", zone = ZONE)       // 3:00 PM, 3:15 PM
    public void run30MinPercentageCalculator() {
        logger.info("⏱️ Triggering 15-Minute F&O Percentage Change Calculation...");
        fnoScannerService.calculateFnoPercentageChange();
    }
}