package com.crumbs.trade.scheduler;

import java.time.LocalTime;
import java.time.ZoneId;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.FuturesStrategyService;

@Component
public class FuturesStrategyScheduler {

    private static final Logger logger = LogManager.getLogger(FuturesStrategyScheduler.class);

    // Exact NSE Equity timings
    private static final LocalTime MARKET_START = LocalTime.of(9, 15);
    private static final LocalTime MARKET_END   = LocalTime.of(15, 30);

    @Autowired
    private FuturesStrategyService futuresStrategyService;
    
    @Autowired
    private StrategyRepo strategyRepo;

    /**
     * 🌅 Pre-Cache Initialization Step
     * Executes once at 8:45 AM every morning (Monday through Friday)
     * Automatically handles the heavy broker REST API calls outside market hours.
     */
    @Scheduled(cron = "0 45 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void runMorningStructureInitialization() {
        if (!isActive("FUTURE")) {
            return;
        }
        
        try {
            logger.info("🌅 Beginning morning pre-cache initialization scheduler...");
            futuresStrategyService.initializeDailyExpiryStructure();
            logger.info("🌅 Morning initialization setup complete.");
        } catch (Exception | SmartAPIException e) {
            logger.error("🛑 Critical Exception caught during morning structure initialization", e);
        }
    }

    /**
     * ⏰ Hourly NSE Execution (Runs at 9:16, 10:16, 11:16, 12:16, 13:16, 14:16, 15:16)
     * Shifted to the 16th minute to avoid the 0-second API race condition and 
     * ensure the 1-hour candle has officially closed on the broker's side.
     */
    @Scheduled(cron = "0 16 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void schedulerHourlyNse() {
         if (!isActive("FUTURE")) {
             return;
         }
         executeIfMarketOpen();
    }

    /**
     * ⏰ Final EOD Execution at 3:30 PM
     * Catches the final closing tick for structure breaks.
     */
    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void schedulerEOD() {
         if (!isActive("FUTURE")) {
             return;
         }
         executeIfMarketOpen();
    }

    private void executeIfMarketOpen() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));

        // Safeguard: Hard block any execution outside NSE hours
        if (now.isBefore(MARKET_START) || now.isAfter(MARKET_END)) {
            logger.info("Market Closed - skipping execution (Current Time: {})", now);
            return;
        }

        try {
            logger.info("Scheduled futures strategy execution started");
            futuresStrategyService.executeAll();
        } catch (Exception | SmartAPIException e) {
            logger.error("Scheduled execution failed", e);
        }
    }
    
    private boolean isActive(String strategy) {
        return "Y".equalsIgnoreCase(
                strategyRepo.findByName(strategy).getActive()
        );
    }
}