package com.crumbs.trade.controller;

import com.crumbs.trade.advisory.AdvisoryEngineService;
import com.crumbs.trade.advisory.AdvisoryLedger;
import com.crumbs.trade.advisory.AdvisoryLedgerRepository;
import com.crumbs.trade.advisory.OptionRecommendation;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.Nifty;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.NiftyRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/advisory")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // Allow UI to connect
public class AdvisoryDashboardController {

    private final AdvisoryEngineService engineService;
    private final NiftyRepo niftyRepo;
    private final IndexesRepo indexesRepo;
    private final AdvisoryLedgerRepository ledgerRepository;

    // =========================================================
    // 📊 VIEW 1: GLOBAL DASHBOARD (Sub-10ms Load)
    // =========================================================
    
    @GetMapping("/dashboard")
    public ResponseEntity<List<AdvisoryLedger>> getDashboardSummary() {
        // Fetches only the currently ACTIVE rows for all symbols directly from the DB.
        // Extremely fast for the UI to load on refresh.
        List<AdvisoryLedger> activeRecords = ledgerRepository.findByStatus("ACTIVE");
        return ResponseEntity.ok(activeRecords);
    }

    // =========================================================
    // 🔎 VIEW 2: SYMBOL DRILL-DOWN (The Timeline)
    // =========================================================
    
    @GetMapping("/history/{symbol}")
    public ResponseEntity<List<AdvisoryLedger>> getSymbolHistory(@PathVariable String symbol) {
        // Returns the historical audit trail for the Timeline component
        List<AdvisoryLedger> history = ledgerRepository.findBySymbolOrderByTimestampDesc(symbol);
        if (history.isEmpty()) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(history);
    }

    // =========================================================
    // ⚙️ ADMIN COMMANDS (Manual Engine Triggers)
    // =========================================================

    @PostMapping("/trigger/{symbol}")
    public ResponseEntity<OptionRecommendation> triggerSingleEngine(@PathVariable String symbol) {
        // 🚀 Fix: Look up the token first before passing it to the engine
        Indexes index = indexesRepo.findByNameAndExchange(symbol, "NSE"); 
        
        if (index == null) {
            log.warn("Cannot run engine: No index found for symbol {}", symbol);
            return ResponseEntity.badRequest().build();
        }
        
        OptionRecommendation rec = engineService.processAdvisory(symbol, index.getToken());
        if (rec == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(rec);
    }

    @PostMapping("/scan-active")
    public ResponseEntity<List<OptionRecommendation>> triggerFullScan() {
        log.info("🚀 Initiating Full Advisory Scan for Active Nifty 50 Stocks...");
        List<Nifty> activeStocks = niftyRepo.findByIsActiveTrueAndTokenIsNotNull();

        // 🛡️ 1. Reduce to 3 threads. Option Chains are massive (~2.5MB each).
        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<CompletableFuture<OptionRecommendation>> futures = new ArrayList<>();

        try {
            for (Nifty stock : activeStocks) {
                CompletableFuture<OptionRecommendation> future = CompletableFuture.supplyAsync(() -> {
                    int maxRetries = 3;

                    // 🛡️ 2. Self-Healing Retry Loop
                    for (int attempt = 1; attempt <= maxRetries; attempt++) {
                        try {
                            Thread.sleep(300); // Base stagger
                            return engineService.processAdvisory(stock.getName(), stock.getToken());

                        } catch (Exception e) {
                            // Extract full error string including the nested Samco Exception
                            String errorLog = e.getMessage() + (e.getCause() != null ? e.getCause().getMessage() : "");

                            // 🛡️ 3. Catch the NGINX 429 Block and Cool Down
                            if (errorLog.contains("429") && attempt < maxRetries) {
                                log.warn("⏳ 429 Rate Limit hit for {} (Attempt {}/{}). Cooling down for 3s...",
                                        stock.getName(), attempt, maxRetries);
                                try {
                                    Thread.sleep(3000);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                                continue; // Loop again and retry!
                            }

                            log.error("❌ Error evaluating {}: {}", stock.getName(), e.getMessage());
                            return null; // Fail gracefully if it's not a 429
                        }
                    }
                    return null;
                }, executor);

                futures.add(future);
            }

            List<OptionRecommendation> recommendations = futures.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            log.info("✅ Full Advisory Scan Complete. Generated {} recommendations.", recommendations.size());
            return ResponseEntity.ok(recommendations);

        } finally {
            executor.shutdown();
        }
    }

    // 🚀 NEW: Feeds the 31-Day Timeline UI
    @GetMapping("/timeline")
    public ResponseEntity<List<AdvisoryLedger>> getMonthlyTimeline() {
        // Fetch the last 30 days of data so the UI can draw the lifecycle ribbons
        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        List<AdvisoryLedger> timelineData = ledgerRepository.findAll().stream()
                .filter(r -> r.getTimestamp() != null && r.getTimestamp().isAfter(thirtyDaysAgo))
                .toList();

        return ResponseEntity.ok(timelineData);
    }
}