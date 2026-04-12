package com.crumbs.trade.scheduler;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.DominanceService;

@Component
public class DominanceScheduler {

    @Autowired
    private DominanceService dominanceService;

    @Autowired
    private StrategyRepo strategyRepo;
    // Every 1 minute
    @Scheduled(cron = "0 * * * * MON-FRI", zone = "Asia/Kolkata")
    public void runDominanceCheck() {
    	 if (!isActive("FUTURE")) {
             return;
         }
        dominanceService.process();
    }
    
    private boolean isActive(String strategy) {
        return "Y".equalsIgnoreCase(
                strategyRepo.findByName(strategy).getActive()
        );
    }
}