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
    
 // ==================== PRE-MARKET ANALYSIS (8:00 AM) ====================

    @Scheduled(cron = "0 0 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void preMarketNifty() {
        executionService.executePreMarket("NIFTY");
    }
   
    
 // Runs every 10 seconds during market hours

    @Scheduled(cron = "*/10 15-59 9 * * MON-FRI", zone = "Asia/Kolkata")
    @Scheduled(cron = "*/10 * 10-14 * * MON-FRI", zone = "Asia/Kolkata")
    @Scheduled(cron = "*/10 0-30 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runMarketLevelStrategy() throws IOException, SmartAPIException {
      
        if (!isActive("MARKET_LEVEL")) {
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