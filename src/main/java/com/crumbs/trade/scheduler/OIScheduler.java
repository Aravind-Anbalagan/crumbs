package com.crumbs.trade.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Component;

import com.crumbs.trade.service.OIAnalysisService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class OIScheduler {

    private final OIAnalysisService oiAnalysisService;

    // ==================== NIFTY (Market Hours) ====================

    @Schedules({
        @Scheduled(cron = "5 15-59 9 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "5 * 10-14 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "5 0-30 15 * * MON-FRI", zone = "Asia/Kolkata")
    })
    public void oiNifty() {
        process("NIFTY");
    }


    // ==================== CRUDE (Evening Session) ====================

    @Schedules({
        @Scheduled(cron = "5 * 16-22 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "5 0-45 23 * * MON-FRI", zone = "Asia/Kolkata")
    })
    public void oiCrude() {
        process("CRUDEOIL");
    }


    // ==================== COMMON PROCESS ====================

    private void process(String name) {

        long start = System.currentTimeMillis();

        try {
            // 🔥 CORE: Save multi-strike snapshot
            oiAnalysisService.saveSnapshot(name);

            

        } catch (Exception e) {
            System.err.println("❌ OI Scheduler Error (" + name + "): " + e.getMessage());
        }

        long end = System.currentTimeMillis();

        System.out.println("⏱ " + name + " completed in " + (end - start) + " ms");
    }
}