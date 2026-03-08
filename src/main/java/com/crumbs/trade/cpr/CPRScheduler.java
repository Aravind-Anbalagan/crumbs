package com.crumbs.trade.cpr;

import java.io.IOException;
import java.time.LocalTime;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.StrategyService;
import com.crumbs.trade.utility.AppConstant;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CPRScheduler {

    private static final Logger logger = LoggerFactory.getLogger(CPRScheduler.class);

    private final StrategyService strategyService;
    private final StrategyRepo    strategyRepo;

    // =========================================================================
    // STEP 1 — Reset daily flags at 09:00 AM
    // =========================================================================
    @Scheduled(cron = "0 0 9 ? * MON-FRI", zone = "Asia/Kolkata")
    public void resetCPRDailyFlags() {
        if (!isActive()) return;
        logger.info("🔄 09:00 — Resetting CPR daily flags");
        strategyService.resetDailyFlags();
    }

    // =========================================================================
    // STEP 2 — Fetch CPR Data + First 5-min candle at 09:20
    // =========================================================================
    @Scheduled(cron = "10 20 9 ? * MON-FRI", zone = "Asia/Kolkata")
    public void fetchCPRDetails() throws IOException, SmartAPIException {
        if (!isActive()) return;
        logger.info("📊 CPR — fetching CPR details");
        strategyService.getCPRDetails();
    }

    // =========================================================================
    // STEP 3 — Monitor signal every 1 min from 09:21 to 15:20
    // =========================================================================
    @Scheduled(cron = "0 21-59 9 * * MON-FRI",  zone = "Asia/Kolkata")  // 09:21 – 09:59
    @Scheduled(cron = "0 * 10-14 * * MON-FRI",  zone = "Asia/Kolkata")  // 10:00 – 14:59
    @Scheduled(cron = "0 0-20 15 * * MON-FRI",  zone = "Asia/Kolkata")  // 15:00 – 15:20
    public void runCPRMonitor() {
        if (!isActive()) return;
        strategyService.executeCPRStrategy();
    }

    // =========================================================================
    // STEP 4 — Force exit all CPR positions at 15:20
    // =========================================================================
    @Scheduled(cron = "0 20 15 ? * MON-FRI", zone = "Asia/Kolkata")
    public void exitCPRAtEOD() {
        if (!isActive()) return;
        logger.info("⏰ 15:20 — EOD CPR exit triggered");
        strategyService.exitAllCPRPositions();
    }

    // =========================================================================
    // HELPER
    // =========================================================================
    private boolean isActive() {
        return "Y".equalsIgnoreCase(
                strategyRepo.findByName(AppConstant.CPR_STRATEGY).getActive()
        );
    }
}