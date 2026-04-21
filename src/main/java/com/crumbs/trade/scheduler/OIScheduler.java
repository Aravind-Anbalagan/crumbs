package com.crumbs.trade.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Component;

import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.OIAnalysisService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class OIScheduler {

    private final OIAnalysisService oiAnalysisService;
    private final StrategyRepo strategyRepo;

    // ==================== CONFIGURATION CONSTANTS ====================
    private static final String STRAT_OI_FLOW = "OI_FLOW";
    private static final String ACTIVE_YES = "Y";
    private static final String ZONE = "Asia/Kolkata";

    // ==================== NIFTY (Market Hours) ====================

    @Schedules({
        @Scheduled(cron = "5 15-59 9 * * MON-FRI", zone = ZONE),
        @Scheduled(cron = "5 * 10-14 * * MON-FRI", zone = ZONE),
        @Scheduled(cron = "5 0-30 15 * * MON-FRI", zone = ZONE)
    })
    public void oiNifty() {
        if (isOiFlowActive()) {
            process("NIFTY");
        }
    }


    // ==================== CRUDE (Evening Session) ====================

    @Schedules({
        @Scheduled(cron = "5 * 16-22 * * MON-FRI", zone = ZONE),
        @Scheduled(cron = "5 0-45 23 * * MON-FRI", zone = ZONE)
    })
    public void oiCrude() {
        if (isOiFlowActive()) {
            process("CRUDEOIL");
        }
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

    /**
     * Checks if the OI_FLOW strategy is enabled in the database.
     */
    private boolean isOiFlowActive() {
        Strategy s = strategyRepo.findByName(STRAT_OI_FLOW);
        return s != null && ACTIVE_YES.equalsIgnoreCase(s.getActive());
    }
}