package com.crumbs.trade.controller;

import com.crumbs.trade.advisory.AdvisoryStrategyAnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/advisory/analytics")
@RequiredArgsConstructor
public class AdvisoryAnalyticsController {

    private final AdvisoryStrategyAnalyticsService analyticsService;

    /**
     * 1️⃣ Get overall strategy performance metrics
     * GET /api/advisory/analytics/metrics
     */
    @GetMapping("/metrics")
    public ResponseEntity<AdvisoryStrategyAnalyticsService.StrategyMetrics> getOverallMetrics() {
        log.info("📊 Fetching overall strategy metrics...");
        AdvisoryStrategyAnalyticsService.StrategyMetrics metrics = analyticsService.getOverallMetrics();
        return ResponseEntity.ok(metrics);
    }

    /**
     * 2️⃣ Get per-stock performance
     * GET /api/advisory/analytics/per-stock
     */
    @GetMapping("/per-stock")
    public ResponseEntity<List<AdvisoryStrategyAnalyticsService.StockMetrics>> getPerStockMetrics() {
        log.info("📈 Fetching per-stock metrics...");
        List<AdvisoryStrategyAnalyticsService.StockMetrics> metrics = analyticsService.getPerStockMetrics();
        return ResponseEntity.ok(metrics);
    }

    /**
     * 3️⃣ Get top winning trades
     * GET /api/advisory/analytics/top-winners?limit=5
     */
    @GetMapping("/top-winners")
    public ResponseEntity<List<AdvisoryStrategyAnalyticsService.TradeRecord>> getTopWinners(
            @RequestParam(defaultValue = "5") int limit) {
        log.info("🏆 Fetching top {} winning trades...", limit);
        List<AdvisoryStrategyAnalyticsService.TradeRecord> winners = analyticsService.getTopWinningTrades(limit);
        return ResponseEntity.ok(winners);
    }

    /**
     * 4️⃣ Get top losing trades
     * GET /api/advisory/analytics/top-losers?limit=5
     */
    @GetMapping("/top-losers")
    public ResponseEntity<List<AdvisoryStrategyAnalyticsService.TradeRecord>> getTopLosers(
            @RequestParam(defaultValue = "5") int limit) {
        log.info("💔 Fetching top {} losing trades...", limit);
        List<AdvisoryStrategyAnalyticsService.TradeRecord> losers = analyticsService.getTopLosingTrades(limit);
        return ResponseEntity.ok(losers);
    }

    /**
     * 5️⃣ Get per-cycle (monthly) performance
     * GET /api/advisory/analytics/per-cycle
     */
    @GetMapping("/per-cycle")
    public ResponseEntity<List<AdvisoryStrategyAnalyticsService.CycleMetrics>> getPerCycleMetrics() {
        log.info("📅 Fetching per-cycle metrics...");
        List<AdvisoryStrategyAnalyticsService.CycleMetrics> cycles = analyticsService.getPerCycleMetrics();
        return ResponseEntity.ok(cycles);
    }

    /**
     * 6️⃣ Get cumulative P&L trend (for charting)
     * GET /api/advisory/analytics/cumulative-pnl
     */
    @GetMapping("/cumulative-pnl")
    public ResponseEntity<Map<LocalDate, Object>> getCumulativePnLTrend() {
        log.info("📊 Fetching cumulative P&L trend...");
        Map<LocalDate, Object> trend = new LinkedHashMap<>(analyticsService.getCumulativePnLTrend());
        return ResponseEntity.ok(trend);
    }

    /**
     * 7️⃣ Get option type effectiveness (PE vs CE)
     * GET /api/advisory/analytics/option-types
     */
    @GetMapping("/option-types")
    public ResponseEntity<Map<String, Map<String, Object>>> getOptionTypeMetrics() {
        log.info("📊 Fetching option type metrics...");
        Map<String, Map<String, Object>> metrics = analyticsService.getOptionTypeMetrics();
        return ResponseEntity.ok(metrics);
    }

    /**
     * 8️⃣ Get dashboard summary (all metrics combined)
     * GET /api/advisory/analytics/dashboard
     */
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard() {
        log.info("🎯 Building comprehensive dashboard...");

        Map<String, Object> dashboard = new LinkedHashMap<>();

        // Overall metrics
        dashboard.put("overall", analyticsService.getOverallMetrics());

        // Per-stock (top 5 by P&L)
        List<AdvisoryStrategyAnalyticsService.StockMetrics> perStock = analyticsService.getPerStockMetrics();
        dashboard.put("topStocks", perStock.stream()
                .limit(5)
                .toList());

        // Top winners & losers
        dashboard.put("topWinners", analyticsService.getTopWinningTrades(3));
        dashboard.put("topLosers", analyticsService.getTopLosingTrades(3));

        // Per-cycle
        dashboard.put("cycles", analyticsService.getPerCycleMetrics());

        // Option type breakdown
        dashboard.put("optionTypes", analyticsService.getOptionTypeMetrics());

        return ResponseEntity.ok(dashboard);
    }

    /**
     * 9️⃣ Health check & data validation
     * GET /api/advisory/analytics/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealthCheck() {
        log.info("🏥 Running analytics health check...");

        Map<String, Object> health = new LinkedHashMap<>();

        try {
            AdvisoryStrategyAnalyticsService.StrategyMetrics metrics = analyticsService.getOverallMetrics();

            health.put("status", "HEALTHY");
            health.put("timestamp", new Date());
            health.put("totalTrades", metrics.getTotalClosedTrades());
            health.put("hasValidData", metrics.getTotalClosedTrades() > 0);

            if (metrics.getTotalClosedTrades() == 0) {
                health.put("warning", "No closed trades found. Data might be incomplete.");
            }

            if (metrics.getTotalRealizedPnL() == null || metrics.getTotalRealizedPnL().signum() == 0) {
                health.put("dataIssue", "⚠️ exit_premium or realized_pnl appears to be NULL");
            }

            return ResponseEntity.ok(health);

        } catch (Exception e) {
            log.error("❌ Health check failed", e);
            health.put("status", "ERROR");
            health.put("error", e.getMessage());
            return ResponseEntity.status(500).body(health);
        }
    }

    /**
     * 🔟 Export metrics as JSON (for external tools)
     * GET /api/advisory/analytics/export
     */
    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> exportAllMetrics() {
        log.info("📥 Exporting all metrics for external tools...");

        Map<String, Object> export = new LinkedHashMap<>();
        export.put("exportTimestamp", new Date());
        export.put("overall", analyticsService.getOverallMetrics());
        export.put("perStock", analyticsService.getPerStockMetrics());
        export.put("perCycle", analyticsService.getPerCycleMetrics());
        export.put("cumulativePnL", analyticsService.getCumulativePnLTrend());
        export.put("optionTypes", analyticsService.getOptionTypeMetrics());
        export.put("topWinners", analyticsService.getTopWinningTrades(10));
        export.put("topLosers", analyticsService.getTopLosingTrades(10));

        return ResponseEntity.ok(export);
    }
}