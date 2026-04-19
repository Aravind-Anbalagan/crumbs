package com.crumbs.trade.scheduler;


import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.crumbs.trade.service.HeikinPsarExecutionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
@Component
public class HeikinPsarScheduler {

    private static final Logger logger = LogManager.getLogger(HeikinPsarScheduler.class);
    private static final DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ================= CONFIGURATION CONSTANTS =================
    private static final String ZONE = "Asia/Kolkata";
    
    // Symbols/Tags
    private static final String SYMBOL_NIFTY = "NIFTY";
    private static final String SYMBOL_CRUDEOILM = "CRUDEOILM"; // Used for MCX Exit
    
    // Exchanges
    private static final String EXCH_NFO = "NFO";
    private static final String EXCH_MCX = "MCX";
    
    // Execution Tags (for logging)
    private static final String TAG_NIFTY_EXEC = "NIFTY-EXEC";
    private static final String TAG_CRUDE_EXEC = "CRUDEOILM-EXEC";
    private static final String TAG_ORDER_MON = "ORDER-MONITOR";
    private static final String TAG_NIFTY_EXIT = "NIFTY-EXIT";
    private static final String TAG_MCX_EXIT   = "MCX-EXIT";

    @Autowired
    private HeikinPsarExecutionService executionService;

    // ------------------- NIFTY -------------------

    @Schedules({
        // 09:20 – 09:59 → every 5 minutes
        @Scheduled(cron = "5 20-59/5 9 * * MON-FRI", zone = ZONE),
        // 10:00 – 14:59 → every 5 minutes
        @Scheduled(cron = "5 */5 10-14 * * MON-FRI", zone = ZONE),
        // 15:00 – 15:15 → every 5 minutes
        @Scheduled(cron = "5 0-15/5 15 * * MON-FRI", zone = ZONE)
    })
    public void runNifty() {
        runSafely(TAG_NIFTY_EXEC, () -> executionService.commonExecutionNifty());
    }

    // ------------------- CRUDEOILM (MCX) -------------------

    @Schedules({
        // 16:00 – 22:59 → every minute (as per your cron "5 *")
        @Scheduled(cron = "5 * 16-22 * * MON-FRI", zone = ZONE),
        // 23:00 – 23:15
        @Scheduled(cron = "5 0-15 23 * * MON-FRI", zone = ZONE)
    })
    public void runCrudeOilM() {
        runSafely(TAG_CRUDE_EXEC, () -> executionService.commonExecutionMcx());
    }

    // ------------------- ORDER MONITOR -------------------

    //@Scheduled(cron = "*/10 * * * * MON-FRI")
    public void monitorOrders() {
        runSafely(TAG_ORDER_MON, () -> executionService.monitorExecutedOrders());
    }

    // ------------------- EXIT -------------------

    //@Scheduled(cron = "0 20 15 ? * MON-FRI", zone = ZONE)
    public void nfoExit() {
        runSafely(TAG_NIFTY_EXIT, () -> executionService.exit(SYMBOL_NIFTY, EXCH_NFO));
    }

    //@Scheduled(cron = "0 20 23 ? * MON-FRI", zone = ZONE)
    public void mcxExit() {
        // Keeping CRUDEOILM here as per your original logic for MCX exit
        runSafely(TAG_MCX_EXIT, () -> executionService.exit(SYMBOL_CRUDEOILM, EXCH_MCX));
    }

    // ------------------- HEARTBEAT -------------------

    @Schedules({
        @Scheduled(cron = "0 */5 9-22 * * MON-FRI", zone = ZONE),
        @Scheduled(cron = "0 0-30/5 23 * * MON-FRI", zone = ZONE)
    })
    public void heartbeat() {
        logger.info("🩵 Scheduler OK @ {}", LocalDateTime.now().format(timeFormat));
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
