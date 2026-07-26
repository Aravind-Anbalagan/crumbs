package com.crumbs.trade.advisory;

import com.crumbs.trade.entity.Nifty;
import com.crumbs.trade.repo.NiftyRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdvisoryEngineScheduler {

    private final AdvisoryEngineService engineService;
    private final NiftyRepo niftyRepo; 

 // Runs at 9:00:00 AM IST, Monday-Friday
    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void runDailyAdvisoryScan() {
        log.info("⏰ Starting Daily Advisory Scan for Active F&O Stocks...");

        List<Nifty> activeStocks = niftyRepo.findByIsActiveTrueAndTokenIsNotNull();
        
        for (Nifty stock : activeStocks) {
            try {
                engineService.processAdvisory(stock.getName(),stock.getToken());
            } catch (Exception e) {
                log.error("Failed advisory scan for {}: {}", stock.getName(), e.getMessage());
            }
        }
        
        log.info("✅ Advisory Scan completed for {} active stocks.", activeStocks.size());
    }
}