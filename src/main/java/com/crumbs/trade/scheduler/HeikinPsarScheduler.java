package com.crumbs.trade.scheduler;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Component;

import com.crumbs.trade.service.HeikinPsarExecutionService;

@Component
public class HeikinPsarScheduler {

    private static final Logger logger = LogManager.getLogger(HeikinPsarScheduler.class);
    private static final DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ================= CONFIGURATION CONSTANTS =================
    private static final String ZONE = "Asia/Kolkata";
    
    // Execution Tags (for logging)
    private static final String TAG_NIFTY_EXEC = "NIFTY-EXEC";
    private static final String TAG_CRUDE_EXEC = "CRUDEOIL-EXEC";
    private static final String TAG_SCALP_MONITOR = "SCALP-MONITOR";
    private volatile boolean stateRestored = false;

    @Autowired
    private HeikinPsarExecutionService executionService;

    // ------------------- NIFTY (5-Min Candle Generation & Entry) -------------------

    @Schedules({
        @Scheduled(cron = "5 20-59/5 9 * * MON-FRI", zone = ZONE),
        @Scheduled(cron = "5 */5 10-14 * * MON-FRI", zone = ZONE),
        @Scheduled(cron = "5 0-25/5 15 * * MON-FRI", zone = ZONE)
    })
    public void runNifty() {
        runSafely(TAG_NIFTY_EXEC, () -> executionService.commonExecutionNifty());
    }

    // ------------------- CRUDEOILM (5-Min Candle Generation & Entry) -------------------

    @Schedules({
        @Scheduled(cron = "5 */5 16-22 * * MON-FRI", zone = ZONE),
        @Scheduled(cron = "5 0-30/5 23 * * MON-FRI", zone = ZONE)
    })
    public void runCrudeOilM() {
        runSafely(TAG_CRUDE_EXEC, () -> executionService.commonExecutionMcx());
    }

    // ------------------- 🔥 FAST-LOOP SCALPING MONITOR -------------------
    // Runs every 10 seconds to lock in profits or cut losses instantly
    @Scheduled(fixedDelay = 10000, zone = ZONE)
    public void monitorScalpPositions() {
        runSafely(TAG_SCALP_MONITOR, () -> executionService.monitorActiveScalpTrades());
    }

    // ------------------- HEARTBEAT -------------------

    @Schedules({
        @Scheduled(cron = "0 */5 9-22 * * MON-FRI", zone = ZONE),
        @Scheduled(cron = "0 0-30/5 23 * * MON-FRI", zone = ZONE)
    })
    public void heartbeat() {
        logger.info("🩵 Scheduler OK @ {}", LocalDateTime.now().format(timeFormat));
    }

    // ------------------- STATE RESTORATION -------------------

    @Scheduled(initialDelay = 30000, fixedDelay = 60000)
    public void restoreState() {
        if (stateRestored) {
            return;
        }
        executionService.restoreStateOnStartup();
        stateRestored = true;
    }

    // ------------------- HELPER -------------------

    private void runSafely(String name, Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            logger.error("❌ {} failed", name, e);
        }
    }

}