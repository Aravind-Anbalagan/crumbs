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
    private static final DateTimeFormatter timeFormat =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    @Autowired
    private HeikinPsarExecutionService executionService;

    // ------------------- NIFTY -------------------

    @Schedules({
        // 09:20 – 09:59
        @Scheduled(cron = "5 20-59 9 * * MON-FRI", zone = "Asia/Kolkata"),

        // 10:00 – 14:59
        @Scheduled(cron = "5 * 10-14 * * MON-FRI", zone = "Asia/Kolkata"),

        // 15:00 – 15:15
        @Scheduled(cron = "5 0-15 15 * * MON-FRI", zone = "Asia/Kolkata")
    })
    public void runNifty() {
        runSafely("NIFTY-EXEC", () -> executionService.commonExecutionNifty());
    }


    // ------------------- SILVERM -------------------

    @Schedules({
        // 16:00 – 22:59
        @Scheduled(cron = "5 * 16-22 * * MON-FRI", zone = "Asia/Kolkata"),

        // 23:00 – 23:15
        @Scheduled(cron = "5 0-15 23 * * MON-FRI", zone = "Asia/Kolkata")
    })
    public void runSilverm() {
        runSafely("SILVERM-EXEC", () -> executionService.commonExecutionMcx());
    }


    // ------------------- ORDER MONITOR -------------------

    @Scheduled(cron = "*/10 * * * * MON-FRI")
    public void monitorOrders() {
        runSafely("ORDER-MONITOR", () -> executionService.monitorExecutedOrders());
    }

    // ------------------- EXIT -------------------

    @Scheduled(cron = "0 20 15 ? * MON-FRI", zone = "Asia/Kolkata")
    public void nfoExit() {
        runSafely("NIFTY-EXIT", () -> executionService.exit("NIFTY", "NFO"));
    }

    @Scheduled(cron = "0 20 23 ? * MON-FRI", zone = "Asia/Kolkata")
    public void mcxExit() {
        runSafely("MCX-EXIT", () -> executionService.exit("SILVERM", "MCX"));
    }

    // ------------------- HEARTBEAT -------------------

    @Scheduled(cron = "0 */5 9-22 * * MON-FRI", zone = "Asia/Kolkata")
    @Scheduled(cron = "0 0-30/5 23 * * MON-FRI", zone = "Asia/Kolkata")
    public void heartbeat() {
        logger.info("🩵 Scheduler OK @ {}", LocalDateTime.now().format(timeFormat));
    }

    private void runSafely(String name, Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            logger.error("❌ {} failed", name, e);
        }
    }
}
