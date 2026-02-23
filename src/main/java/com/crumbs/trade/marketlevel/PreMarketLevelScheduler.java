package com.crumbs.trade.marketlevel;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PreMarketLevelScheduler {

    private final PreMarketLevelOrderManagementService oms;

 // Runs every 10 seconds during market hours

    @Scheduled(cron = "*/10 15-59 9 * * MON-FRI", zone = "Asia/Kolkata")
    @Scheduled(cron = "*/10 * 10-14 * * MON-FRI", zone = "Asia/Kolkata")
    @Scheduled(cron = "*/10 0-30 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runMarketLevelStrategy() {
        oms.runCycle("NIFTY");
    }
}