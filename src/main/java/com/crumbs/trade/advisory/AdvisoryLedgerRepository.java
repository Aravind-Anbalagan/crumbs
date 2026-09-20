package com.crumbs.trade.advisory;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AdvisoryLedgerRepository extends JpaRepository<AdvisoryLedger, Long> {

    // =========================================================================
    // ✅ EXISTING QUERIES (from original)
    // =========================================================================

    Optional<AdvisoryLedger> findTopBySymbolOrderByTimestampDesc(String symbol);
    List<AdvisoryLedger> findBySymbolOrderByTimestampAsc(String symbol);
    List<AdvisoryLedger> findBySymbolAndTimestampBetweenOrderByTimestampAsc(
            String symbol, LocalDateTime startTime, LocalDateTime endTime);
    Optional<AdvisoryLedger> findTopBySymbolAndStatusOrderByTimestampDesc(String symbol, String status);
    List<AdvisoryLedger> findBySymbolAndStatus(String symbol, String status);
    List<AdvisoryLedger> findBySymbolAndActionTakenOrderByTimestampDesc(String symbol, String actionTaken);
    List<AdvisoryLedger> findBySymbolAndCycleStartDateAndCycleEndDate(
            String symbol, LocalDate cycleStartDate, LocalDate cycleEndDate);

    @Query("SELECT a FROM AdvisoryLedger a WHERE a.timestamp >= :startTime ORDER BY a.symbol ASC, a.timestamp ASC")
    List<AdvisoryLedger> findAllAfterTimestampOrderedBySymbolAndDate(@Param("startTime") LocalDateTime startTime);

    @Query("SELECT a FROM AdvisoryLedger a WHERE a.timestamp BETWEEN :startTime AND :endTime ORDER BY a.symbol ASC, a.timestamp ASC")
    List<AdvisoryLedger> findAllInDateRangeOrderedBySymbolAndDate(
            @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    @Query(value = "SELECT DISTINCT ON (symbol) * FROM advisory_ledger WHERE status = 'ACTIVE' ORDER BY symbol, timestamp DESC", nativeQuery = true)
    List<AdvisoryLedger> findCurrentlyActivePositions();

    @Query("SELECT COUNT(*) FROM AdvisoryLedger a WHERE a.symbol = :symbol AND a.cycleStartDate = :cycleStart AND a.cycleEndDate = :cycleEnd")
    long countTradesInCycle(@Param("symbol") String symbol, @Param("cycleStart") LocalDate cycleStart, @Param("cycleEnd") LocalDate cycleEnd);

    @Query("SELECT COUNT(*) FROM AdvisoryLedger a WHERE a.symbol = :symbol AND a.status = 'HISTORY' AND a.actionTaken = 'TARGET'")
    long countTargetHits(@Param("symbol") String symbol);

    @Query("SELECT COUNT(*) FROM AdvisoryLedger a WHERE a.symbol = :symbol AND a.status = 'HISTORY' AND a.actionTaken = 'SL'")
    long countStopLosses(@Param("symbol") String symbol);

    @Query("SELECT AVG(a.daysInPosition) FROM AdvisoryLedger a WHERE a.symbol = :symbol AND a.daysInPosition > 0")
    Double getAverageDaysInPosition(@Param("symbol") String symbol);

    @Query("SELECT COALESCE(SUM(a.realizedPnl), 0) FROM AdvisoryLedger a WHERE a.symbol = :symbol AND a.status = 'HISTORY'")
    BigDecimal getTotalRealizedPnL(@Param("symbol") String symbol);

    @Query("SELECT a FROM AdvisoryLedger a WHERE a.status = ?1 AND a.timestamp >= ?2 AND a.timestamp <= ?3 AND a.exitPremium IS NULL ORDER BY a.timestamp DESC")
    List<AdvisoryLedger> findByStatusAndTimestampBetweenAndExitPremiumIsNull(
            String status, LocalDateTime startTime, LocalDateTime endTime);

    // =========================================================================
    // 🆕 NEW QUERIES FOR ANALYTICS SERVICE (MUST ADD)
    // =========================================================================

    /**
     * Get ALL records by status (used for analytics)
     * Critical for: Overall metrics, per-cycle metrics, option type metrics
     */
    List<AdvisoryLedger> findByStatus(String status);

    /**
     * Get ALL records by status AND action (used for analytics)
     * Critical for: Win rate calculation, top winners/losers
     */
    List<AdvisoryLedger> findByStatusAndActionTaken(String status, String actionTaken);

    /**
     * Get records by status, ordered by timestamp (for trend analysis)
     */
    List<AdvisoryLedger> findByStatusOrderByTimestampAsc(String status);

    /**
     * Get records by action taken (for exit analysis)
     */
    List<AdvisoryLedger> findByActionTaken(String actionTaken);

    /**
     * Get ALL closed trades (status=HISTORY) with entry/exit details
     * Used for: Top winners, top losers, PnL calculations
     */
    @Query("SELECT a FROM AdvisoryLedger a WHERE a.status = 'HISTORY' ORDER BY a.timestamp DESC")
    List<AdvisoryLedger> findAllClosedTrades();

    /**
     * Get ALL active positions (status=ACTIVE) with entry details
     * Used for: Active open positions, unrealized PnL
     */
    @Query("SELECT a FROM AdvisoryLedger a WHERE a.status = 'ACTIVE' AND a.actionTaken IN ('NEW_ENTRY', 'MAINTAIN') ORDER BY a.entryDate DESC")
    List<AdvisoryLedger> findAllActiveTrades();

    /**
     * Get trades for a specific cycle (monthly expiry)
     */
    @Query("SELECT a FROM AdvisoryLedger a WHERE a.cycleStartDate = :cycleStart AND a.cycleEndDate = :cycleEnd ORDER BY a.timestamp ASC")
    List<AdvisoryLedger> findByCycle(@Param("cycleStart") LocalDate cycleStart, @Param("cycleEnd") LocalDate cycleEnd);

    /**
     * Get all distinct cycles (for cycle analytics)
     */
    @Query("SELECT DISTINCT a.cycleStartDate, a.cycleEndDate FROM AdvisoryLedger a ORDER BY a.cycleStartDate DESC")
    List<Object[]> findAllDistinctCycles();

    /**
     * Get trades by option type (PE vs CE analysis)
     */
    List<AdvisoryLedger> findByOptionType(String optionType);

    /**
     * Get trades within date range (for trend analysis)
     */
    @Query("SELECT a FROM AdvisoryLedger a WHERE a.timestamp BETWEEN :from AND :to ORDER BY a.timestamp ASC")
    List<AdvisoryLedger> findInDateRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Check if any records are missing exit_premium (data validation)
     */
    @Query("SELECT COUNT(*) FROM AdvisoryLedger a WHERE a.status = 'HISTORY' AND a.exitPremium IS NULL")
    long countMissingExitPremium();

    /**
     * Check if any records are missing realized_pnl (data validation)
     */
    @Query("SELECT COUNT(*) FROM AdvisoryLedger a WHERE a.status = 'HISTORY' AND a.realizedPnl IS NULL AND a.actionTaken IN ('TARGET', 'SL')")
    long countMissingRealizedPnL();

    /**
     * Check if any records are missing lot_size (data validation)
     */
    @Query("SELECT COUNT(*) FROM AdvisoryLedger a WHERE a.lotSize IS NULL AND a.status = 'HISTORY'")
    long countMissingLotSize();

    /**
     * Get summary statistics
     */
    @Query("SELECT COUNT(*), " +
            "SUM(CASE WHEN a.actionTaken = 'TARGET' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN a.actionTaken = 'SL' THEN 1 ELSE 0 END), " +
            "COALESCE(SUM(a.realizedPnl), 0) " +
            "FROM AdvisoryLedger a WHERE a.status = 'HISTORY'")
    Object[] getSummaryStats();

    /**
     * Get per-symbol summary
     */
    @Query("SELECT a.symbol, " +
            "COUNT(*), " +
            "SUM(CASE WHEN a.actionTaken = 'TARGET' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN a.actionTaken = 'SL' THEN 1 ELSE 0 END), " +
            "COALESCE(SUM(a.realizedPnl), 0), " +
            "AVG(a.daysInPosition) " +
            "FROM AdvisoryLedger a WHERE a.status = 'HISTORY' " +
            "GROUP BY a.symbol ORDER BY SUM(a.realizedPnl) DESC")
    List<Object[]> getPerSymbolStats();

    /**
     * Get per-cycle summary
     */
    @Query("SELECT a.cycleStartDate, a.cycleEndDate, " +
            "COUNT(*), " +
            "SUM(CASE WHEN a.actionTaken = 'TARGET' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN a.actionTaken = 'SL' THEN 1 ELSE 0 END), " +
            "COALESCE(SUM(a.realizedPnl), 0) " +
            "FROM AdvisoryLedger a WHERE a.status = 'HISTORY' " +
            "GROUP BY a.cycleStartDate, a.cycleEndDate ORDER BY a.cycleStartDate DESC")
    List<Object[]> getPerCycleStats();

    /**
     * Get per-option-type summary
     */
    @Query("SELECT a.optionType, " +
            "COUNT(*), " +
            "SUM(CASE WHEN a.actionTaken = 'TARGET' THEN 1 ELSE 0 END), " +
            "SUM(CASE WHEN a.actionTaken = 'SL' THEN 1 ELSE 0 END), " +
            "COALESCE(SUM(a.realizedPnl), 0), " +
            "COALESCE(AVG(a.realizedPnl), 0) " +
            "FROM AdvisoryLedger a WHERE a.status = 'HISTORY' " +
            "GROUP BY a.optionType")
    List<Object[]> getPerOptionTypeStats();

    /**
     * Get daily P&L trend (for charting)
     */
    @Query("SELECT CAST(a.timestamp AS date), " +
            "SUM(a.realizedPnl) " +
            "FROM AdvisoryLedger a WHERE a.status = 'HISTORY' " +
            "GROUP BY CAST(a.timestamp AS date) ORDER BY CAST(a.timestamp AS date) ASC")
    List<Object[]> getDailyPnLTrend();

    /**
     * Get ATR effectiveness by exit type
     */
    @Query("SELECT a.symbol, a.actionTaken, COUNT(*), " +
            "AVG(a.atr14), AVG(a.daysInPosition), COALESCE(AVG(a.realizedPnl), 0) " +
            "FROM AdvisoryLedger a WHERE a.status = 'HISTORY' " +
            "GROUP BY a.symbol, a.actionTaken ORDER BY a.symbol")
    List<Object[]> getATREffectiveness();
}