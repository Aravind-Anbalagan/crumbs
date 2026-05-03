package com.crumbs.trade.scheduler;

import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.GreekStrategyService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GreekStrategyScheduler {

    private static final Logger logger = LoggerFactory.getLogger(GreekStrategyScheduler.class);
    
    private final GreekStrategyService greekStrategyService;
    private final StrategyRepo strategyRepo;

    private static final String MASTER_STRATEGY = "GREEK_STRADDLE";

    // =========================================================================
    // NIFTY 1-MINUTE CYCLE (9:15 AM to 3:30 PM)
    // =========================================================================

    // Ingests Greeks every minute at :00 seconds
    @Schedules({
        @Scheduled(cron = "0 15-59 9 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "0 * 10-14 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "0 0-30 15 * * MON-FRI", zone = "Asia/Kolkata")
    })
    public void ingestNiftyData() {
        executeIfActive("SAMCO_NIFTY", () -> {
            logger.info(">>> [NIFTY] 1-Min Ingestion Cycle Started");
            greekStrategyService.ingestAtmChain("SAMCO_NIFTY");
        });
    }

    // Evaluates Entry every minute at :05 seconds (5s after data is saved)
    @Schedules({
        @Scheduled(cron = "5 15-59 9 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "5 * 10-14 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "5 0-30 15 * * MON-FRI", zone = "Asia/Kolkata")
    })
    public void evaluateNiftyTrades() {
        executeIfActive("SAMCO_NIFTY", () -> {
            //greekStrategyService.evaluate("SAMCO_NIFTY");
        });
    }

    // =========================================================================
    // CRUDEOIL 1-MINUTE CYCLE (4:00 PM to 11:30 PM)
    // =========================================================================

    // Ingests Greeks every minute at :00 seconds
    @Schedules({
        @Scheduled(cron = "0 * 16-22 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "0 0-30 23 * * MON-FRI", zone = "Asia/Kolkata")
    })
    public void ingestCrudeData() {
        executeIfActive("SAMCO_CRUDEOIL", () -> {
            logger.info(">>> [CRUDEOIL] 1-Min Ingestion Cycle Started");
            greekStrategyService.ingestAtmChain("SAMCO_CRUDEOIL");
        });
    }

    // Evaluates Entry every minute at :05 seconds
    @Schedules({
        @Scheduled(cron = "5 * 16-22 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "5 0-30 23 * * MON-FRI", zone = "Asia/Kolkata")
    })
    public void evaluateCrudeTrades() {
        executeIfActive("SAMCO_CRUDEOIL", () -> {
            //greekStrategyService.evaluate("SAMCO_CRUDEOIL");
        });
    }

    // =========================================================================
    // SAFETY WRAPPER
    // =========================================================================

    private void executeIfActive(String instrumentName, Runnable logic) {
        // Double check: Is the master switch ON and is the specific instrument active?
        if (isActive(MASTER_STRATEGY) && isActive(instrumentName)) {
            logic.run();
        } else {
            logger.trace("Skipping {}. Strategy or Master switch is OFF.", instrumentName);
        }
    }

    private boolean isActive(String strategyName) {
        Strategy strategy = strategyRepo.findByName(strategyName);
        return strategy != null && "Y".equalsIgnoreCase(strategy.getActive());
    }
}