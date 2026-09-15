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
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdvisoryEngineScheduler {

    private final AdvisoryEngineService engineService;
    private final NiftyRepo niftyRepo;

    // =========================================================================
    // PHASE 1: MORNING SCAN (9:00 AM)
    // Evaluates overnight gaps, early morning trend flips, and wall migrations.
    // =========================================================================
    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void runMorningScan() {
        log.info("🌅 PHASE 1: Starting 9:00 AM Morning Advisory Scan...");
        executeScanTask("ADVISORY_SCAN");
    }

    // =========================================================================
    // PHASE 2: PRE-CLOSE SCAN (3:15 PM)
    // Catches intraday SL breaches before market close & evaluates fresh entries
    // on a fully-formed daily candle.
    // =========================================================================
    @Scheduled(cron = "0 15 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runPreCloseScan() {
        log.info("🌇 PHASE 2: Starting 3:15 PM Pre-Close Advisory Scan...");
        executeScanTask("ADVISORY_SCAN");
    }

    // =========================================================================
    // PHASE 3: EOD MTM SETTLEMENT (3:35 PM)
    // Pure accounting run. Fetches the final closing bells prices to update
    // the Unrealized PnL for active holdings. Does NOT evaluate trades.
    // =========================================================================
    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runEodPnlSettlement() {
        log.info("📊 PHASE 3: Starting 3:35 PM EOD PnL Settlement...");
        executeScanTask("EOD_SETTLEMENT");
    }

    // 🧠 Centralized execution engine to handle deduping, threading, and rate limits
    private void executeScanTask(String taskType) {
        List<Nifty> activeStocks = niftyRepo.findByIsActiveTrueAndTokenIsNotNull();

        // 🚨 DEDUP GUARD: Collapse to one task per unique symbol name
        Map<String, List<Nifty>> bySymbol = activeStocks.stream()
                .collect(Collectors.groupingBy(Nifty::getName));

        List<Nifty> dedupedStocks = new ArrayList<>();
        for (Map.Entry<String, List<Nifty>> entry : bySymbol.entrySet()) {
            List<Nifty> rows = entry.getValue();
            if (rows.size() > 1) {
                String tokens = rows.stream().map(Nifty::getToken).collect(Collectors.joining(", "));
                log.error("⚠️ DUPLICATE ACTIVE ENTRIES for symbol '{}' (tokens: {}). Skipping extras to avoid ledger race.", entry.getKey(), tokens);
                rows.stream().min(Comparator.comparing(Nifty::getToken)).ifPresent(dedupedStocks::add);
            } else {
                dedupedStocks.add(rows.get(0));
            }
        }

        // 🛡️ Safe Pool Size of 3 to respect broker rate limits
        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        try {
            for (Nifty stock : dedupedStocks) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    int maxRetries = 3;

                    for (int attempt = 1; attempt <= maxRetries; attempt++) {
                        try {
                            // Micro-delay to stagger API hits
                            Thread.sleep(300);

                            // Route the task to the correct engine logic
                            if ("ADVISORY_SCAN".equals(taskType)) {
                                engineService.processAdvisory(stock.getName(), stock.getToken());
                            } else if ("EOD_SETTLEMENT".equals(taskType)) {
                                engineService.processEodPnl(stock.getName(), stock.getToken());
                            }

                            break; // Success! Break out of retry loop

                        } catch (Exception e) {
                            String errorLog = e.getMessage() + (e.getCause() != null ? e.getCause().getMessage() : "");

                            if (errorLog.contains("429") && attempt < maxRetries) {
                                log.warn("⏳ 429 Rate Limit hit for {} during {} (Attempt {}/{}). Cooling down...",
                                        stock.getName(), taskType, attempt, maxRetries);
                                try {
                                    Thread.sleep(3000);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                                continue; // Retry
                            }
                            log.error("❌ Failed {} for {}: {}", taskType, stock.getName(), e.getMessage());
                            break; // Fail permanently
                        }
                    }
                }, executor);
                futures.add(future);
            }

            // Wait for all threads to finish
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            log.info("✅ {} completed for {} unique symbols.", taskType, dedupedStocks.size());

        } finally {
            executor.shutdown();
        }
    }
}