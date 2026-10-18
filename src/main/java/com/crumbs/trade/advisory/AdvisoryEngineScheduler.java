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

    // 🛡️ Global Throttle Lock to prevent 429 API rate limits
    private static final Object THROTTLE_LOCK = new Object();

    // =========================================================================
    // PHASE 1: MORNING SCAN (9:20 AM)
    // Evaluates targets and logs warnings, but is BLOCKED from triggering SLs.
    // =========================================================================
    @Scheduled(cron = "0 20 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void runMorningScan() {
        log.info("🌅 PHASE 1: Starting 9:20 AM Morning Advisory Scan...");
        executeScanTask("MORNING_SCAN");
    }

    // =========================================================================
    // PHASE 2: PRE-CLOSE SCAN (3:15 PM)
    // Authorized to execute physical Stop-Losses if the daily candle closes
    // beyond the anchored Swing zones.
    // =========================================================================
    @Scheduled(cron = "0 15 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runPreCloseScan() {
        log.info("🌇 PHASE 2: Starting 3:15 PM Pre-Close Advisory Scan...");
        executeScanTask("PRE_CLOSE_SCAN");
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

        // 🚀 FAST CONCURRENCY: 3 Threads process DB/WebSocket operations in parallel
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // Summary Counters
        AtomicInteger actionableCount = new AtomicInteger(0);
        AtomicInteger noTradeCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        try {
            for (Nifty stock : dedupedStocks) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    int maxRetries = 3;

                    for (int attempt = 1; attempt <= maxRetries; attempt++) {
                        try {
                            // 🛡️ SYNCHRONIZED THROTTLE:
                            // Guarantees no two threads start their API fetch at the exact same millisecond.
                            // 500ms delay = max 2 requests per second globally, perfectly safe for Samco.
                            synchronized (THROTTLE_LOCK) {
                                Thread.sleep(500);
                            }

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

                            break; // Success! Break out of retry loop

                        } catch (Exception e) {
                            String errorLog = e.getMessage() + (e.getCause() != null ? e.getCause().getMessage() : "");

                            if (errorLog.contains("429") && attempt < maxRetries) {
                                log.warn("⏳ 429 Rate Limit hit for {} during {} (Attempt {}/{}). Cooling down...",
                                        stock.getName(), taskType, attempt, maxRetries);
                                try {
                                    Thread.sleep(3000); // Penalty box if rate limit is somehow hit
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                                continue; // Retry
                            }

                            log.error("❌ Failed {} for {}: {}", taskType, stock.getName(), e.getMessage());
                            if (attempt == maxRetries) {
                                errorCount.incrementAndGet();
                            }
                            break; // Fail permanently
                        }
                    }
                }, executor);
                futures.add(future);
            }

            // Wait for all threads to finish
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 📊 Final Summary Output
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