package com.crumbs.trade.advisory;

import com.crumbs.trade.entity.Nifty;
import com.crumbs.trade.repo.NiftyRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdvisoryEngineScheduler {

    private final AdvisoryEngineService engineService;
    private final NiftyRepo niftyRepo;

    // =========================================================================
    // PHASE 1: MORNING SCAN (9:20 AM)
    // =========================================================================
    @Scheduled(cron = "0 20 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void runMorningScan() {
        log.info("🌅 PHASE 1: Starting 9:20 AM Morning Advisory Scan...");
        executeScanTask("MORNING_SCAN");
    }

    // =========================================================================
    // PHASE 2: PRE-CLOSE SCAN (3:15 PM)
    // =========================================================================
    @Scheduled(cron = "0 15 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runPreCloseScan() {
        log.info("🌇 PHASE 2: Starting 3:15 PM Pre-Close Advisory Scan...");
        executeScanTask("PRE_CLOSE_SCAN");
    }

    // =========================================================================
    // PHASE 3: EOD MTM SETTLEMENT (3:35 PM)
    // =========================================================================
    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runEodPnlSettlement() {
        log.info("📊 PHASE 3: Starting 3:35 PM EOD PnL Settlement...");
        executeScanTask("EOD_SETTLEMENT");
    }

    private void executeScanTask(String taskType) {
        List<Nifty> activeStocks = niftyRepo.findByIsActiveTrueAndTokenIsNotNull();

        Map<String, List<Nifty>> bySymbol = activeStocks.stream()
                .collect(Collectors.groupingBy(Nifty::getName));

        List<Nifty> dedupedStocks = new ArrayList<>();
        for (Map.Entry<String, List<Nifty>> entry : bySymbol.entrySet()) {
            List<Nifty> rows = entry.getValue();
            if (rows.size() > 1) {
                String tokens = rows.stream().map(Nifty::getToken).collect(Collectors.joining(", "));
                log.error("⚠️ DUPLICATE ACTIVE ENTRIES for symbol '{}' (tokens: {}). Skipping extras.", entry.getKey(), tokens);
                rows.stream().min(Comparator.comparing(Nifty::getToken)).ifPresent(dedupedStocks::add);
            } else {
                dedupedStocks.add(rows.get(0));
            }
        }

        // 🚀 INCREASED CONCURRENCY: Bumped from 2 to 10 threads.
        // Samco throttling is now handled dynamically by AdvisoryOiService.
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        AtomicInteger actionableCount = new AtomicInteger(0);
        AtomicInteger noTradeCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        try {
            for (Nifty stock : dedupedStocks) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    int maxRetries = 3;

                    for (int attempt = 1; attempt <= maxRetries; attempt++) {
                        try {
                            if ("MORNING_SCAN".equals(taskType) || "PRE_CLOSE_SCAN".equals(taskType)) {
                                OptionRecommendation rec = engineService.processAdvisory(stock.getName(), stock.getToken(), taskType);

                                if (rec != null) {
                                    if ("NO_TRADE".equals(rec.getAction())) {
                                        noTradeCount.incrementAndGet();
                                    } else {
                                        actionableCount.incrementAndGet();
                                    }
                                } else {
                                    errorCount.incrementAndGet();
                                }
                            } else if ("EOD_SETTLEMENT".equals(taskType)) {
                                engineService.processEodPnl(stock.getName(), stock.getToken());
                                actionableCount.incrementAndGet();
                            } else {
                                log.warn("⚠️ Unknown taskType received: {}", taskType);
                            }
                            break;

                        } catch (Exception e) {
                            String errorLog = e.getMessage() + (e.getCause() != null ? e.getCause().getMessage() : "");

                            if (errorLog.contains("429") && attempt < maxRetries) {
                                log.warn("⏳ 429 Rate Limit hit for {} during {} (Attempt {}/{}). Cooling down...",
                                        stock.getName(), taskType, attempt, maxRetries);
                                try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                                continue;
                            }

                            log.error("❌ Failed {} for {}: {}", taskType, stock.getName(), e.getMessage());
                            if (attempt == maxRetries) {
                                errorCount.incrementAndGet();
                            }
                            break;
                        }
                    }
                }, executor);
                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            log.info("======================================================");
            log.info("✅ {} SUMMARY REPORT", taskType);
            log.info("======================================================");
            log.info("Total Unique Symbols Scanned : {}", dedupedStocks.size());
            log.info("Actionable (Entry/Hold/Exit) : {}", actionableCount.get());
            log.info("No Trade (Rejected by logic) : {}", noTradeCount.get());
            log.info("Errors (API Fails/Skips)     : {}", errorCount.get());
            log.info("======================================================");

        } finally {
            executor.shutdown();
        }
    }
}