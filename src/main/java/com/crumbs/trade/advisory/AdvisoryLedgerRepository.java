package com.crumbs.trade.advisory;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AdvisoryLedgerRepository extends JpaRepository<AdvisoryLedger, Long> {

    // =========================================================================
    // ✅ NEW QUERIES FOR COMPLETE LIFECYCLE TRACKING
    // =========================================================================

    /**
     * Get the most recent record for a symbol (regardless of status)
     * Used to track the previous day's state
     */
    Optional<AdvisoryLedger> findTopBySymbolOrderByTimestampDesc(String symbol);

    /**
     * Get all records for a symbol, ordered chronologically
     * Returns complete lifecycle history
     */
    List<AdvisoryLedger> findBySymbolOrderByTimestampAsc(String symbol);

    /**
     * Get all records for a symbol within a date range
     * Allows filtering by specific time period
     */
    List<AdvisoryLedger> findBySymbolAndTimestampBetweenOrderByTimestampAsc(
            String symbol,
            LocalDateTime startTime,
            LocalDateTime endTime);

    /**
     * Get all records after a specific date for all symbols
     * Used for loading "last 30 days" across all instruments
     */
    @Query("SELECT a FROM AdvisoryLedger a WHERE a.timestamp >= :startTime ORDER BY a.symbol ASC, a.timestamp ASC")
    List<AdvisoryLedger> findAllAfterTimestampOrderedBySymbolAndDate(@Param("startTime") LocalDateTime startTime);

    /**
     * Get all records within a date range for all symbols
     * Used for custom range queries
     */
    @Query("SELECT a FROM AdvisoryLedger a WHERE a.timestamp BETWEEN :startTime AND :endTime ORDER BY a.symbol ASC, a.timestamp ASC")
    List<AdvisoryLedger> findAllInDateRangeOrderedBySymbolAndDate(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * Get currently active positions (not closed)
     */
    @Query(value = "SELECT DISTINCT ON (symbol) * FROM advisory_ledger WHERE status = 'ACTIVE' ORDER BY symbol, timestamp DESC", nativeQuery = true)
    List<AdvisoryLedger> findCurrentlyActivePositions();

    // =========================================================================
    // 📋 KEPT FOR BACKWARD COMPATIBILITY
    // =========================================================================

    /**
     * Find most recent record with specific status
     * Still useful for finding last exit, etc.
     */
    Optional<AdvisoryLedger> findTopBySymbolAndStatusOrderByTimestampDesc(String symbol, String status);

    /**
     * Find records by symbol and status
     */
    List<AdvisoryLedger> findBySymbolAndStatus(String symbol, String status);

    /**
     * Find records by symbol and action taken
     * Useful for filtering by entry/exit/maintain
     */
    List<AdvisoryLedger> findBySymbolAndActionTakenOrderByTimestampDesc(String symbol, String actionTaken);

    /**
     * Find records with specific cycle dates
     * Useful for filtering by expiry cycle
     */
    List<AdvisoryLedger> findBySymbolAndCycleStartDateAndCycleEndDate(
            String symbol,
            java.time.LocalDate cycleStartDate,
            java.time.LocalDate cycleEndDate);

    // =========================================================================
    // 📊 ANALYTICS QUERIES
    // =========================================================================

    /**
     * Count total trades in a cycle
     */
    @Query("SELECT COUNT(*) FROM AdvisoryLedger a WHERE a.symbol = :symbol AND a.cycleStartDate = :cycleStart AND a.cycleEndDate = :cycleEnd")
    long countTradesInCycle(@Param("symbol") String symbol, @Param("cycleStart") java.time.LocalDate cycleStart, @Param("cycleEnd") java.time.LocalDate cycleEnd);

    /**
     * Calculate win rate (target vs SL)
     */
    @Query("SELECT COUNT(*) FROM AdvisoryLedger a WHERE a.symbol = :symbol AND a.status = 'HISTORY' AND a.actionTaken = 'TARGET'")
    long countTargetHits(@Param("symbol") String symbol);

    @Query("SELECT COUNT(*) FROM AdvisoryLedger a WHERE a.symbol = :symbol AND a.status = 'HISTORY' AND a.actionTaken = 'SL'")
    long countStopLosses(@Param("symbol") String symbol);

    /**
     * Get average days held in positions
     */
    @Query("SELECT AVG(a.daysInPosition) FROM AdvisoryLedger a WHERE a.symbol = :symbol AND a.daysInPosition > 0")
    Double getAverageDaysInPosition(@Param("symbol") String symbol);

    /**
     * Get total realized PnL
     */
    @Query("SELECT COALESCE(SUM(a.realizedPnl), 0) FROM AdvisoryLedger a WHERE a.symbol = :symbol AND a.status = 'HISTORY'")
    java.math.BigDecimal getTotalRealizedPnL(@Param("symbol") String symbol);

    @Query("SELECT a FROM AdvisoryLedger a WHERE a.status = ?1 " +
            "AND a.timestamp >= ?2 AND a.timestamp <= ?3 " +
            "AND a.exitPremium IS NULL ORDER BY a.timestamp DESC")
    List<AdvisoryLedger> findByStatusAndTimestampBetweenAndExitPremiumIsNull(
            String status, LocalDateTime startTime, LocalDateTime endTime);
}