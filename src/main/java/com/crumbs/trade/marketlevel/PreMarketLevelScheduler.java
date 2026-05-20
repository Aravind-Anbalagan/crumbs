package com.crumbs.trade.marketlevel;

import java.io.IOException;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.StraddleExecutionService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PreMarketLevelScheduler {

    private final PreMarketLevelOrderManagementService oms;
    private final StrategyRepo strategyRepo;
    private final StraddleExecutionService executionService;
    
    // ==================== PRE-MARKET ANALYSIS (8:00:10 AM) ====================

    @Scheduled(cron = "10 0 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void preMarketNifty() {
        executionService.executePreMarket("NIFTY");
    }
   
    


 // ==================== UPDATED MARKET STRATEGY (Starts at 9:20) ====================
    // 1. First hour session: Changed starting minute from 15 to 20
    @Scheduled(cron = "*/10 20-59 9 * * MON-FRI", zone = "Asia/Kolkata")
    // 2. Mid-day session: Stays the same (10:00 AM to 02:59 PM)
    @Scheduled(cron = "*/10 * 10-14 * * MON-FRI", zone = "Asia/Kolkata")
    // 3. Closing session: Stays the same (03:00 PM to 03:30 PM)
    @Scheduled(cron = "*/10 0-30 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runMarketLevelStrategy() throws IOException, SmartAPIException {
      
        if (!isActive("PRE_MARKET_LEVEL")) {
            //LoggerFactory.getLogger(getClass())
               // .info("Strategy STRADDLE_PREMIUM is inactive, skipping pre-market analysis for {}", name);
            return;
        }
        oms.runCycle("NIFTY");
    }
    
    private boolean isActive(String strategy) {
        return "Y".equalsIgnoreCase(
                strategyRepo.findByName(strategy).getActive()
        );
    }
}