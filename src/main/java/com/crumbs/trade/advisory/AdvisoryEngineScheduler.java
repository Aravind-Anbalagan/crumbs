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

    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void runDailyAdvisoryScan() {
        log.info("⏰ Starting Daily Advisory Scan for Active F&O Stocks...");

        List<Nifty> activeStocks = niftyRepo.findByIsActiveTrueAndTokenIsNotNull();

        // =====================================================================
        // 🚨 DEDUP GUARD: The AdvisoryLedger keys all state by symbol NAME, not
        // token. If the Nifty table ever contains two active rows sharing the
        // same name (duplicate seed data, stale re-import, EQ+FUT both active,
        // etc.), dispatching one async task per ROW causes two concurrent
        // processAdvisory() calls to race against the SAME ledger key —
        // producing same-day NEW_ENTRY + EXIT pairs and corrupting the
        // one-position-per-symbol invariant. We collapse to one task per
        // unique symbol name and loudly flag any duplicates so they get
        // cleaned up at the data level instead of silently racing.
        // =====================================================================
        Map<String, List<Nifty>> bySymbol = activeStocks.stream()
                .collect(Collectors.groupingBy(Nifty::getName));

        List<Nifty> dedupedStocks = new ArrayList<>();
        for (Map.Entry<String, List<Nifty>> entry : bySymbol.entrySet()) {
            List<Nifty> rows = entry.getValue();
            if (rows.size() > 1) {
                String tokens = rows.stream().map(Nifty::getToken).collect(Collectors.joining(", "));
                log.error("⚠️ DUPLICATE ACTIVE ENTRIES for symbol '{}' (tokens: {}). " +
                                "Skipping the extras to avoid a ledger race — fix the Nifty table so " +
                                "each symbol maps to exactly one active token for this engine.",
                        entry.getKey(), tokens);
                // Deterministic pick so behavior doesn't flip between runs: lowest token id wins.
                rows.stream()
                        .min(Comparator.comparing(Nifty::getToken))
                        .ifPresent(dedupedStocks::add);
            } else {
                dedupedStocks.add(rows.get(0));
            }
        }

        if (dedupedStocks.size() != activeStocks.size()) {
            log.warn("🧹 Deduped active stock list: {} rows -> {} unique symbols.",
                    activeStocks.size(), dedupedStocks.size());
        }

        // 🛡️ Safe Pool Size of 3
        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        try {
            for (Nifty stock : dedupedStocks) {
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
            log.info("✅ Advisory Scan completed for {} unique symbols ({} raw active rows).",
                    dedupedStocks.size(), activeStocks.size());

        } finally {
            executor.shutdown();
        }
    }


}