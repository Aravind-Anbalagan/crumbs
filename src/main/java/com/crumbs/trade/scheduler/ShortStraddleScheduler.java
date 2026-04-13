package com.crumbs.trade.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import com.crumbs.trade.service.ShortStraddleService;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ShortStraddleScheduler {
    
    private final ShortStraddleService shortStraddleService;
    
 // ==================== NIFTY (9:20:10 AM - 3:21:10 PM) ====================
    @Schedules({
        @Scheduled(cron = "10 20-59 9 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "10 * 10-14 * * MON-FRI", zone = "Asia/Kolkata"),
        // Extended to 21 minutes to ensure the 15:20 square-off logic runs
        @Scheduled(cron = "10 0-21 15 * * MON-FRI", zone = "Asia/Kolkata") 
    })
    public void straddleNifty() {
        shortStraddleService.evaluate("NIFTY");
    }
    
    // ==================== CRUDE OIL (4:00:10 PM - 11:31:10 PM) ====================
    @Schedules({
        @Scheduled(cron = "10 * 16-22 * * MON-FRI", zone = "Asia/Kolkata"),
        // Extended to 31 minutes to ensure the 23:20 square-off logic runs
        @Scheduled(cron = "10 0-31 23 * * MON-FRI", zone = "Asia/Kolkata")
    })
    public void straddleCrude() {
        shortStraddleService.evaluate("CRUDEOILM");
    }
}