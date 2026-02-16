package com.crumbs.trade.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import com.crumbs.trade.service.StraddleExecutionService;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class StraddleScheduler {
    
    private final StraddleExecutionService executionService;
    
    // ==================== PRE-MARKET ANALYSIS (9:10 AM) ====================
    
    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    //@Scheduled(fixedRate = 10000)
    public void preMarketNifty() {
        executionService.executePreMarket("NIFTY");
    }
    
    
    
    // ==================== REGULAR INTRADAY (9:15+ onwards) ====================
    
    // -------------------- NIFTY --------------------
    @Schedules({
        @Scheduled(cron = "0 15-59 9 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "0 * 10-14 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "0 0-30 15 * * MON-FRI", zone = "Asia/Kolkata")
    })
    public void straddleNifty() {
        executionService.execute("NIFTY");
    }
    
    // -------------------- CRUDE --------------------
    @Schedules({
        @Scheduled(cron = "0 * 16-22 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "0 0-45 23 * * MON-FRI", zone = "Asia/Kolkata")
    })
    public void straddleCrude() {
        executionService.execute("CRUDEOIL");
    }
}