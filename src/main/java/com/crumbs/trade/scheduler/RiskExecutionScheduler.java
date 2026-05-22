package com.crumbs.trade.scheduler;



import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.crumbs.trade.service.RiskService;

@Component
public class RiskExecutionScheduler {

    @Autowired
    private RiskService riskService;

    /**
     * Executes the centralized risk tracking assessment pipeline continuously.
     * 2000ms delay ensures we don't breach Angel One rate limits.
     */
    @Scheduled(fixedDelay = 2000)
    public void runRiskEvaluationCycle() {
        // Mapped directly to RiskService's top-level execution thread
        riskService.processSystemRiskMatrix(); 
    }
}