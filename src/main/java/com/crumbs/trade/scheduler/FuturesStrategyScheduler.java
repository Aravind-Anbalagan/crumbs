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

    private static final Logger logger =
            LogManager.getLogger(FuturesStrategyScheduler.class);

    private static final LocalTime MARKET_START = LocalTime.of(9, 15);
    private static final LocalTime MARKET_END   = LocalTime.of(15, 30);

    @Autowired
    private FuturesStrategyService futuresStrategyService;
    @Autowired
    private StrategyRepo strategyRepo;
    /**
     * ⏰ Every hour from 9:15 to 3:15
     * @throws SmartAPIException 
     */
    @Scheduled(cron = "0 15 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduler915to315() throws SmartAPIException {
    	 if (!isActive("FUTURE")) {
             return;
         }
        executeIfMarketOpen();
    }

    /**
     * ⏰ Final execution at 3:30 PM
     * @throws SmartAPIException 
     */
    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduler330() throws SmartAPIException {
    	 if (!isActive("FUTURE")) {
             return;
         }
        executeIfMarketOpen();
    }

    private void executeIfMarketOpen() throws SmartAPIException {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));

        if (now.isBefore(MARKET_START) || now.isAfter(MARKET_END)) {
            logger.info("Market Closed - skipping execution");
            return;
        }

        try {
            logger.info("Scheduled futures strategy execution started");
            futuresStrategyService.executeAll();
        } catch (Exception e) {
            logger.error("Scheduled execution failed", e);
        }
    }
    
    private boolean isActive(String strategy) {
        return "Y".equalsIgnoreCase(
                strategyRepo.findByName(strategy).getActive()
        );
    }
}
