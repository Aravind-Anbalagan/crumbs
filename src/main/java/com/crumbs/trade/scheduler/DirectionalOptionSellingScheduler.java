package com.crumbs.trade.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.DirectionalOptionSellingService;
import com.crumbs.trade.service.StraddleExecutionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class DirectionalOptionSellingScheduler {

    private final DirectionalOptionSellingService directionalService;
    private final StrategyRepo strategyRepo;
    private final StraddleExecutionService executionService;
    
    // ==================== PRE-MARKET ANALYSIS (8:00:10 AM) ====================

    @Scheduled(cron = "10 30 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void preMarketNifty() {
        executionService.executePreMarket("NIFTY");
    }
    
   // ==================== CRUDE OIL PRE-MARKET ANALYSIS (4:00:30 PM) ====================

    @Scheduled(cron = "30 0 16 * * MON-FRI", zone = "Asia/Kolkata")
    public void preMarketCrudeOil() {
        executionService.executePreMarket("CRUDEOIL");
    }
    
   
    // ==================== UPDATED MARKET STRATEGY (Starts at 9:20) ====================
    
    // 1. First hour session: Starts at 9:20
    @Scheduled(cron = "*/10 20-59 9 * * MON-FRI", zone = "Asia/Kolkata")
    // 2. Mid-day session: Stays the same (10:00 AM to 02:59 PM)
    @Scheduled(cron = "*/10 * 10-14 * * MON-FRI", zone = "Asia/Kolkata")
    // 3. Closing session: Stays the same (03:00 PM to 03:30 PM)
    @Scheduled(cron = "*/10 0-30 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runDirectionalOptionSellingStrategy() {
      
        // Master strategy flag in the database
        if (!isActive("DIRECTIONAL_SELL")) {
            // log.debug("Strategy DIRECTIONAL_SELL is inactive, skipping scan for NIFTY.");
            return;
        }
        
        directionalService.evaluate("NIFTY");
    }
    
    private boolean isActive(String strategyName) {
        Strategy strategy = strategyRepo.findByName(strategyName);
        return strategy != null && "Y".equalsIgnoreCase(strategy.getActive());
    }
}