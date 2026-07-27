package com.crumbs.trade.advisory;

import com.crumbs.trade.entity.Nifty;
import com.crumbs.trade.repo.NiftyRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdvisoryEngineScheduler {

    private final AdvisoryEngineService engineService;
    private final NiftyRepo niftyRepo;

    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void runDailyAdvisoryScan() {
        log.info("⏰ Starting Daily Advisory Scan for Active F&O Stocks...");

        List<Nifty> activeStocks = niftyRepo.findByIsActiveTrueAndTokenIsNotNull();

        // 🛡️ Safe Pool Size of 3
        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        try {
            for (Nifty stock : activeStocks) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    int maxRetries = 3;

                    for (int attempt = 1; attempt <= maxRetries; attempt++) {
                        try {
                            Thread.sleep(300);
                            engineService.processAdvisory(stock.getName(), stock.getToken());
                            break; // Success! Break out of the retry loop

                        } catch (Exception e) {
                            String errorLog = e.getMessage() + (e.getCause() != null ? e.getCause().getMessage() : "");

                            if (errorLog.contains("429") && attempt < maxRetries) {
                                log.warn("⏳ 429 Rate Limit hit for {} (Attempt {}/{}). Cooling down...",
                                        stock.getName(), attempt, maxRetries);
                                try {
                                    Thread.sleep(3000);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                                continue; // Retry
                            }

                            log.error("❌ Failed advisory scan for {}: {}", stock.getName(), e.getMessage());
                            break; // Fail permanently
                        }
                    }
                }, executor);

                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            log.info("✅ Advisory Scan completed for {} active stocks.", activeStocks.size());

        } finally {
            executor.shutdown();
        }
    }
}