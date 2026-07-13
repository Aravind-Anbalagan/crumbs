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

    // ==================== CRUDEOIL EVENING SESSION (4:05 PM - 11:20 PM) ====================
    // Entries start 4:05 PM, entry cutoff 11:00 PM, square-off 11:20 PM.
    // Mirrors the NIFTY polling cadence (every 10 seconds) across the session.

    // 1. Session open: 4:05 PM to 4:59 PM
    @Scheduled(cron = "*/10 5-59 16 * * MON-FRI", zone = "Asia/Kolkata")
    // 2. Mid-session: 5:00 PM to 10:59 PM
    @Scheduled(cron = "*/10 * 17-22 * * MON-FRI", zone = "Asia/Kolkata")
    // 3. Closing session: 11:00 PM to 11:20 PM (covers entry cutoff + square-off)
    @Scheduled(cron = "*/10 0-20 23 * * MON-FRI", zone = "Asia/Kolkata")
    public void runDirectionalOptionSellingStrategyCrudeOil() {

        // Master strategy flag in the database - shared with NIFTY since both run
        // under the same DIRECTIONAL_SELL strategy signal.
        if (!isActive("DIRECTIONAL_SELL")) {
            // log.debug("Strategy DIRECTIONAL_SELL is inactive, skipping scan for CRUDEOIL.");
            return;
        }

        directionalService.evaluate("CRUDEOIL");
    }
    
    private boolean isActive(String strategyName) {
        Strategy strategy = strategyRepo.findByName(strategyName);
        return strategy != null && "Y".equalsIgnoreCase(strategy.getActive());
    }
}