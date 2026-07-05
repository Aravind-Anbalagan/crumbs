package com.crumbs.trade.scheduler;

import java.util.concurrent.CompletableFuture;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Component;

import com.crumbs.trade.service.StraddleExecutionService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class StraddleScheduler {

    private final StraddleExecutionService executionService;

    // ==================== REGULAR INTRADAY (9:15+ onwards) ====================

    // -------------------- NIFTY + SENSEX --------------------
    @Schedules({
        @Scheduled(cron = "0 15-59 9 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "0 * 10-14 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "0 0-30 15 * * MON-FRI", zone = "Asia/Kolkata")
    })
    public void straddleNifty() {

        CompletableFuture<Void> niftyFuture =
                CompletableFuture.runAsync(() ->
                        executionService.execute("NIFTY"));

        CompletableFuture<Void> sensexFuture =
                CompletableFuture.runAsync(() ->
                        executionService.execute("SENSEX"));

        CompletableFuture.allOf(
                niftyFuture,
                sensexFuture
        ).join();
    }

    // -------------------- CRUDE + NATURALGAS --------------------
    @Schedules({
        @Scheduled(cron = "0 * 16-22 * * MON-FRI", zone = "Asia/Kolkata"),
        @Scheduled(cron = "0 0-45 23 * * MON-FRI", zone = "Asia/Kolkata")
    })
    public void straddleCrude() {

        CompletableFuture<Void> crudeFuture =
                CompletableFuture.runAsync(() ->
                        executionService.execute("CRUDEOIL"));

        CompletableFuture<Void> gasFuture =
                CompletableFuture.runAsync(() ->
                        executionService.execute("NATURALGAS"));

        CompletableFuture.allOf(
                crudeFuture,
                gasFuture
        ).join();
    }
}