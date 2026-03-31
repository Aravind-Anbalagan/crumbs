package com.crumbs.trade.scheduler;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.crumbs.trade.service.DominanceService;

@Component
public class DominanceScheduler {

    @Autowired
    private DominanceService dominanceService;

    // Every 1 minute
    @Scheduled(fixedRate = 60000)
    public void runDominanceCheck() {
        dominanceService.process();
    }
}