package com.crumbs.trade.controller;

import com.crumbs.trade.advisory.*;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.Nifty;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.NiftyRepo;
import com.crumbs.trade.utility.CycleUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/advisory")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AdvisoryDashboardController {

    private final AdvisoryEngineService engineService;
    private final NiftyRepo niftyRepo;
    private final IndexesRepo indexesRepo;
    private final AdvisoryLedgerRepository ledgerRepository;
    private final AdvisoryEngineScheduler advisoryEngineScheduler;

    // =========================================================================
    // 🚀 NEW ENDPOINTS FOR COMPLETE LIFECYCLE TRACKING
    // =========================================================================

    /**
     * Get complete lifecycle for a single symbol
     * Shows all entries, maintains, and exits in order
     */
    @GetMapping("/timeline/symbol/{symbol}")
    public ResponseEntity<?> getSymbolLifecycle(
            @PathVariable String symbol,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        log.info("📊 Fetching lifecycle for symbol: {}", symbol);

        List<AdvisoryLedger> records;

        if (startDate != null && endDate != null) {
            LocalDateTime start = startDate.atStartOfDay();
            LocalDateTime end = endDate.atTime(23, 59, 59);
            records = ledgerRepository.findBySymbolAndTimestampBetweenOrderByTimestampAsc(symbol, start, end);
        } else {
            // Default: last 90 days
            LocalDateTime ninetyDaysAgo = LocalDateTime.now().minusDays(90);
            LocalDateTime now = LocalDateTime.now();
            records = ledgerRepository.findBySymbolAndTimestampBetweenOrderByTimestampAsc(
                    symbol, ninetyDaysAgo, now);
        }

        if (records.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        // Transform to lifecycle view
        Map<String, Object> lifecycle = buildSymbolLifecycleResponse(symbol, records);
        return ResponseEntity.ok(lifecycle);
    }

    /**
     * Get all symbols with complete history
     * Default: last 30 days
     */
    @GetMapping("/timeline/all-symbols")
    public ResponseEntity<?> getAllSymbolsLifecycle(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        log.info("📊 Fetching complete timeline for all symbols");

        List<AdvisoryLedger> allRecords;

        if (startDate != null && endDate != null) {
            LocalDateTime start = startDate.atStartOfDay();
            LocalDateTime end = endDate.atTime(23, 59, 59);
            allRecords = ledgerRepository.findAllInDateRangeOrderedBySymbolAndDate(start, end);
        } else {
            // Default: last 30 days
            LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
            allRecords = ledgerRepository.findAllAfterTimestampOrderedBySymbolAndDate(thirtyDaysAgo);
        }

        if (allRecords.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        // Group by symbol
        Map<String, List<AdvisoryLedger>> groupedBySymbol = allRecords.stream()
                .collect(Collectors.groupingBy(AdvisoryLedger::getSymbol, Collectors.toList()));

        // Build response for each symbol
        Map<String, Object> response = new LinkedHashMap<>();
        for (String symbol : groupedBySymbol.keySet()) {
            response.put(symbol, buildSymbolLifecycleResponse(symbol, groupedBySymbol.get(symbol)));
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Get timeline data in flat format (for legacy dashboard compatibility)
     * Returns all records, frontend handles filtering
     */
    @GetMapping("/timeline")
    public ResponseEntity<?> getTimelineFlat(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate) {

        LocalDateTime start;
        if (startDate != null) {
            start = startDate.atStartOfDay();
        } else {
            // Default: last 30 days
            start = LocalDateTime.now().minusDays(30);
        }

        List<AdvisoryLedger> records = ledgerRepository.findAllAfterTimestampOrderedBySymbolAndDate(start);

        if (records.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok(records);
    }

    /**
     * Get currently active positions only
     * Useful for real-time monitoring dashboard
     */
    @GetMapping("/active-positions")
    public ResponseEntity<?> getActivePositions() {
        log.info("🟢 Fetching currently active positions");

        List<AdvisoryLedger> activeRecords = ledgerRepository.findCurrentlyActivePositions();

        if (activeRecords.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok(activeRecords);
    }

    /**
     * Get analytics for a symbol
     * Win rate, avg days held, total PnL, etc.
     */
    @GetMapping("/analytics/{symbol}")
    public ResponseEntity<?> getSymbolAnalytics(@PathVariable String symbol) {
        log.info("📈 Fetching analytics for: {}", symbol);

        long targetHits = ledgerRepository.countTargetHits(symbol);
        long stopLosses = ledgerRepository.countStopLosses(symbol);
        Double avgDaysHeld = ledgerRepository.getAverageDaysInPosition(symbol);
        java.math.BigDecimal totalPnL = ledgerRepository.getTotalRealizedPnL(symbol);

        long totalTrades = targetHits + stopLosses;
        double winRate = totalTrades > 0 ? (double) targetHits / totalTrades * 100 : 0;

        Map<String, Object> analytics = new LinkedHashMap<>();
        analytics.put("symbol", symbol);
        analytics.put("totalTrades", totalTrades);
        analytics.put("targetHits", targetHits);
        analytics.put("stopLosses", stopLosses);
        analytics.put("winRate", String.format("%.2f%%", winRate));
        analytics.put("avgDaysInPosition", avgDaysHeld != null ? String.format("%.1f", avgDaysHeld) : "N/A");
        analytics.put("totalRealizedPnL", totalPnL);

        return ResponseEntity.ok(analytics);
    }

    /**
     * Get specific trade details (for modal/detail view)
     */
    @GetMapping("/trade/{recordId}")
    public ResponseEntity<?> getTradeDetails(@PathVariable Long recordId) {
        return ledgerRepository.findById(recordId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // =========================================================================
    // 🏃 SCAN OPERATIONS
    // =========================================================================

    @PostMapping("/scan-active")
    public ResponseEntity<?> scanActivePositions() {
        log.info("🔄 Triggering manual active scan across all symbols from UI...");
        try {
            // 🚀 Runs the exact same deduped, 3-thread pooled, rate-limited scan as the cron
            advisoryEngineScheduler.runDailyAdvisoryScan();

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Scan completed successfully",
                    "timestamp", LocalDateTime.now()
            ));
        } catch (Exception e) {
            log.error("❌ Manual scan execution failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "message", "Scan failed: " + e.getMessage(),
                    "timestamp", LocalDateTime.now()
            ));
        }
    }

    // =========================================================================
    // 🛠️ HELPER METHODS
    // =========================================================================

    /**
     * Transform raw records into a structured lifecycle response
     */
    private Map<String, Object> buildSymbolLifecycleResponse(String symbol, List<AdvisoryLedger> records) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("symbol", symbol);
        response.put("totalRecords", records.size());

        // Calculate stats
        long activeCount = records.stream().filter(r -> "ACTIVE".equals(r.getStatus())).count();
        long historyCount = records.stream().filter(r -> "HISTORY".equals(r.getStatus())).count();
        long targetHits = records.stream().filter(r -> "TARGET".equals(r.getActionTaken())).count();
        long stopLosses = records.stream().filter(r -> "SL".equals(r.getActionTaken())).count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("activeRecords", activeCount);
        stats.put("closedRecords", historyCount);
        stats.put("targetHits", targetHits);
        stats.put("stopLosses", stopLosses);
        stats.put("winRate", historyCount > 0 ?
                String.format("%.1f%%", (double) targetHits / historyCount * 100) : "N/A");

        response.put("stats", stats);

        // Timeline events
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (AdvisoryLedger record : records) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("date", record.getTimestamp().toLocalDate());
            event.put("time", record.getTimestamp().toLocalTime());
            event.put("action", record.getActionTaken());
            event.put("status", record.getStatus());
            event.put("strike", record.getRecommendedStrike());
            event.put("optionType", record.getOptionType());
            event.put("spotPrice", record.getSpotPrice());
            event.put("entryPremium", record.getEntryPremium());
            event.put("exitPremium", record.getExitPremium());
            event.put("currentPremium", record.getCurrentPremium());
            event.put("unrealizedPnL", record.getUnrealizedPnl());
            event.put("realizedPnL", record.getRealizedPnl());
            event.put("daysHeld", record.getDaysInPosition());
            event.put("reasoning", record.getReasoning());
            event.put("trend", record.getDailyTrend());
            event.put("atr14", record.getAtr14());

            timeline.add(event);
        }

        response.put("timeline", timeline);

        return response;
    }
}