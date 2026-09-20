package com.crumbs.trade.advisory;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdvisoryStrategyAnalyticsService {

    private final AdvisoryLedgerRepository ledgerRepository;

    // =========================================================================
    // DATA CLASSES
    // =========================================================================

    @Data
    @Builder
    public static class StrategyMetrics {
        private long totalClosedTrades;
        private long winningTrades;
        private long losingTrades;
        private BigDecimal winRatePct;
        private BigDecimal totalRealizedPnL;
        private BigDecimal totalUnrealizedPnL;
        private BigDecimal netPnL;
        private Double avgDaysHeld;
        private Long activeOpenPositions;
        private BigDecimal avgPnLPerTrade;
    }

    @Data
    @Builder
    public static class CycleMetrics {
        private LocalDate cycleStart;
        private LocalDate cycleEnd;
        private long totalTrades;
        private long winningTrades;
        private long losingTrades;
        private BigDecimal totalPnL;
        private BigDecimal winRatePct;
    }

    @Data
    @Builder
    public static class StockMetrics {
        private String symbol;
        private long totalTrades;
        private long wins;
        private long losses;
        private BigDecimal totalPnL;
        private BigDecimal avgPnL;
        private BigDecimal winRatePct;
        private Double avgDaysHeld;
    }

    @Data
    @Builder
    public static class TradeRecord {
        private String symbol;
        private String optionType;
        private BigDecimal strike;
        private LocalDate entryDate;
        private LocalDate exitDate;
        private BigDecimal entryPremium;
        private BigDecimal exitPremium;
        private Integer daysHeld;
        private String action;  // TARGET or SL
        private BigDecimal pnL;
        private BigDecimal roiPct;
    }

    // =========================================================================
    // 1️⃣ OVERALL STRATEGY METRICS
    // =========================================================================
    public StrategyMetrics getOverallMetrics() {
        // ✅ FIXED: Now uses findByStatus() which takes 1 argument
        List<AdvisoryLedger> closedTrades = ledgerRepository.findByStatus("HISTORY");

        // ✅ FIXED: Now uses findByStatus() which takes 1 argument
        List<AdvisoryLedger> activeTrades = ledgerRepository.findByStatus("ACTIVE");

        if (closedTrades.isEmpty()) {
            log.warn("⚠️ No closed trades found for metrics calculation");
            return StrategyMetrics.builder()
                    .totalClosedTrades(0)
                    .winningTrades(0)
                    .losingTrades(0)
                    .winRatePct(BigDecimal.ZERO)
                    .totalRealizedPnL(BigDecimal.ZERO)
                    .totalUnrealizedPnL(BigDecimal.ZERO)
                    .netPnL(BigDecimal.ZERO)
                    .avgDaysHeld(0.0)
                    .activeOpenPositions(0L)
                    .build();
        }

        long totalClosed = closedTrades.size();
        long wins = closedTrades.stream()
                .filter(t -> "TARGET".equals(t.getActionTaken()))
                .count();
        long losses = closedTrades.stream()
                .filter(t -> "SL".equals(t.getActionTaken()))
                .count();

        BigDecimal totalRealized = closedTrades.stream()
                .map(t -> t.getRealizedPnl() != null ? t.getRealizedPnl() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalUnrealized = activeTrades.stream()
                .filter(t -> t.getUnrealizedPnl() != null)
                .map(AdvisoryLedger::getUnrealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal winRate = losses + wins > 0
                ? BigDecimal.valueOf(wins).multiply(new BigDecimal("100"))
                .divide(BigDecimal.valueOf(losses + wins), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        Double avgDays = closedTrades.stream()
                .mapToInt(t -> t.getDaysInPosition() != null ? t.getDaysInPosition() : 0)
                .average()
                .orElse(0.0);

        BigDecimal avgPnlPerTrade = totalClosed > 0
                ? totalRealized.divide(BigDecimal.valueOf(totalClosed), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return StrategyMetrics.builder()
                .totalClosedTrades(totalClosed)
                .winningTrades(wins)
                .losingTrades(losses)
                .winRatePct(winRate)
                .totalRealizedPnL(totalRealized)
                .totalUnrealizedPnL(totalUnrealized)
                .netPnL(totalRealized.add(totalUnrealized))
                .avgDaysHeld(avgDays)
                .activeOpenPositions((long) activeTrades.stream()
                        .filter(t -> t.getEntryPremium() != null &&
                                ("NEW_ENTRY".equals(t.getActionTaken()) || "MAINTAIN".equals(t.getActionTaken())))
                        .count())
                .avgPnLPerTrade(avgPnlPerTrade)
                .build();
    }

    // =========================================================================
    // 2️⃣ PER-STOCK METRICS
    // =========================================================================
    public List<StockMetrics> getPerStockMetrics() {
        // ✅ FIXED: Now uses findByStatus() which takes 1 argument
        Map<String, List<AdvisoryLedger>> bySymbol = ledgerRepository.findByStatus("HISTORY")
                .stream()
                .collect(Collectors.groupingBy(AdvisoryLedger::getSymbol));

        return bySymbol.entrySet().stream()
                .map(entry -> {
                    String symbol = entry.getKey();
                    List<AdvisoryLedger> trades = entry.getValue();

                    long wins = trades.stream()
                            .filter(t -> "TARGET".equals(t.getActionTaken()))
                            .count();
                    long losses = trades.stream()
                            .filter(t -> "SL".equals(t.getActionTaken()))
                            .count();

                    BigDecimal totalPnl = trades.stream()
                            .map(t -> t.getRealizedPnl() != null ? t.getRealizedPnl() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    BigDecimal avgPnl = trades.size() > 0
                            ? totalPnl.divide(BigDecimal.valueOf(trades.size()), 2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;

                    BigDecimal winRate = wins + losses > 0
                            ? BigDecimal.valueOf(wins).multiply(new BigDecimal("100"))
                            .divide(BigDecimal.valueOf(wins + losses), 2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;

                    Double avgDays = trades.stream()
                            .mapToInt(t -> t.getDaysInPosition() != null ? t.getDaysInPosition() : 0)
                            .average()
                            .orElse(0.0);

                    return StockMetrics.builder()
                            .symbol(symbol)
                            .totalTrades(trades.size())
                            .wins(wins)
                            .losses(losses)
                            .totalPnL(totalPnl)
                            .avgPnL(avgPnl)
                            .winRatePct(winRate)
                            .avgDaysHeld(avgDays)
                            .build();
                })
                .sorted(Comparator.comparing(StockMetrics::getTotalPnL).reversed())
                .collect(Collectors.toList());
    }

    // =========================================================================
    // 3️⃣ TOP WINNING TRADES
    // =========================================================================
    public List<TradeRecord> getTopWinningTrades(int limit) {
        // ✅ FIXED: Now uses findByStatusAndActionTaken() with 2 arguments
        return ledgerRepository.findByStatusAndActionTaken("HISTORY", "TARGET")
                .stream()
                .filter(t -> t.getRealizedPnl() != null && t.getEntryPremium() != null)
                .sorted(Comparator.comparing(AdvisoryLedger::getRealizedPnl).reversed())
                .limit(limit)
                .map(this::ledgerToTradeRecord)
                .collect(Collectors.toList());
    }

    // =========================================================================
    // 4️⃣ TOP LOSING TRADES
    // =========================================================================
    public List<TradeRecord> getTopLosingTrades(int limit) {
        // ✅ FIXED: Now uses findByStatusAndActionTaken() with 2 arguments
        return ledgerRepository.findByStatusAndActionTaken("HISTORY", "SL")
                .stream()
                .filter(t -> t.getRealizedPnl() != null && t.getEntryPremium() != null)
                .sorted(Comparator.comparing(AdvisoryLedger::getRealizedPnl))
                .limit(limit)
                .map(this::ledgerToTradeRecord)
                .collect(Collectors.toList());
    }

    // =========================================================================
    // 5️⃣ PER-CYCLE METRICS
    // =========================================================================
    public List<CycleMetrics> getPerCycleMetrics() {
        // ✅ FIXED: Now uses findByStatus() which takes 1 argument
        Map<String, List<AdvisoryLedger>> byCycle = ledgerRepository.findByStatus("HISTORY")
                .stream()
                .collect(Collectors.groupingBy(t ->
                        t.getCycleStartDate() + "_" + t.getCycleEndDate()));

        return byCycle.entrySet().stream()
                .map(entry -> {
                    String[] dates = entry.getKey().split("_");
                    LocalDate startDate = LocalDate.parse(dates[0]);
                    LocalDate endDate = LocalDate.parse(dates[1]);
                    List<AdvisoryLedger> trades = entry.getValue();

                    long wins = trades.stream()
                            .filter(t -> "TARGET".equals(t.getActionTaken()))
                            .count();
                    long losses = trades.stream()
                            .filter(t -> "SL".equals(t.getActionTaken()))
                            .count();

                    BigDecimal totalPnl = trades.stream()
                            .map(t -> t.getRealizedPnl() != null ? t.getRealizedPnl() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    BigDecimal winRate = wins + losses > 0
                            ? BigDecimal.valueOf(wins).multiply(new BigDecimal("100"))
                            .divide(BigDecimal.valueOf(wins + losses), 2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;

                    return CycleMetrics.builder()
                            .cycleStart(startDate)
                            .cycleEnd(endDate)
                            .totalTrades(trades.size())
                            .winningTrades(wins)
                            .losingTrades(losses)
                            .totalPnL(totalPnl)
                            .winRatePct(winRate)
                            .build();
                })
                .sorted(Comparator.comparing(CycleMetrics::getCycleEnd).reversed())
                .collect(Collectors.toList());
    }

    // =========================================================================
    // 6️⃣ CUMULATIVE P&L TREND (for charting)
    // =========================================================================
    public Map<LocalDate, BigDecimal> getCumulativePnLTrend() {
        // ✅ FIXED: Now uses findByStatus() which takes 1 argument
        List<AdvisoryLedger> trades = ledgerRepository.findByStatus("HISTORY")
                .stream()
                .sorted(Comparator.comparing(AdvisoryLedger::getTimestamp))
                .collect(Collectors.toList());

        Map<LocalDate, BigDecimal> cumulative = new LinkedHashMap<>();
        BigDecimal runningTotal = BigDecimal.ZERO;

        for (AdvisoryLedger trade : trades) {
            LocalDate tradeDate = trade.getTimestamp().toLocalDate();
            BigDecimal pnl = trade.getRealizedPnl() != null ? trade.getRealizedPnl() : BigDecimal.ZERO;
            runningTotal = runningTotal.add(pnl);
            cumulative.put(tradeDate, runningTotal);
        }

        return cumulative;
    }

    // =========================================================================
    // 7️⃣ OPTION TYPE EFFECTIVENESS (PE vs CE)
    // =========================================================================
    public Map<String, Map<String, Object>> getOptionTypeMetrics() {
        // ✅ FIXED: Now uses findByStatus() which takes 1 argument
        Map<String, List<AdvisoryLedger>> byType = ledgerRepository.findByStatus("HISTORY")
                .stream()
                .collect(Collectors.groupingBy(AdvisoryLedger::getOptionType));

        Map<String, Map<String, Object>> result = new HashMap<>();

        byType.forEach((optionType, trades) -> {
            long wins = trades.stream()
                    .filter(t -> "TARGET".equals(t.getActionTaken()))
                    .count();
            long losses = trades.stream()
                    .filter(t -> "SL".equals(t.getActionTaken()))
                    .count();

            BigDecimal totalPnl = trades.stream()
                    .map(t -> t.getRealizedPnl() != null ? t.getRealizedPnl() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            Map<String, Object> metrics = new HashMap<>();
            metrics.put("totalTrades", trades.size());
            metrics.put("wins", wins);
            metrics.put("losses", losses);
            metrics.put("winRate", wins + losses > 0
                    ? BigDecimal.valueOf(wins * 100).divide(BigDecimal.valueOf(wins + losses), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
            metrics.put("totalPnL", totalPnl);
            metrics.put("avgPnL", trades.size() > 0
                    ? totalPnl.divide(BigDecimal.valueOf(trades.size()), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);

            result.put(optionType, metrics);
        });

        return result;
    }

    // =========================================================================
    // HELPER METHOD
    // =========================================================================
    private TradeRecord ledgerToTradeRecord(AdvisoryLedger ledger) {
        BigDecimal roiPct = ledger.getEntryPremium() != null && ledger.getLotSize() != null
                ? ledger.getRealizedPnl()
                .divide(ledger.getEntryPremium().multiply(BigDecimal.valueOf(ledger.getLotSize())),
                        4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        return TradeRecord.builder()
                .symbol(ledger.getSymbol())
                .optionType(ledger.getOptionType())
                .strike(ledger.getRecommendedStrike())
                .entryDate(ledger.getEntryDate() != null ? ledger.getEntryDate().toLocalDate() : null)
                .exitDate(ledger.getTimestamp().toLocalDate())
                .entryPremium(ledger.getEntryPremium())
                .exitPremium(ledger.getExitPremium())
                .daysHeld(ledger.getDaysInPosition())
                .action(ledger.getActionTaken())
                .pnL(ledger.getRealizedPnl())
                .roiPct(roiPct)
                .build();
    }
}