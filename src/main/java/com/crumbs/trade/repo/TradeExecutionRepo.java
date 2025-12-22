package com.crumbs.trade.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.crumbs.trade.entity.TradeExecution;

public interface TradeExecutionRepo extends JpaRepository<TradeExecution, Long> {

    // Intraday
    List<TradeExecution> findByEntryTimeBetween(LocalDateTime start, LocalDateTime end);

    List<TradeExecution> findBySymbolAndEntryTimeBetween(
            String symbol,
            LocalDateTime start,
            LocalDateTime end
    );

    @Modifying
    @Query("delete from TradeExecution")
    void deleteAll();

    Optional<TradeExecution> findFirstBySymbolAndTimeframeAndStatus(
            String symbol,
            String timeframe,
            String status
    );

    Optional<TradeExecution> findFirstBySymbolAndTimeframeOrderByEntryTimeDesc(
            String symbol,
            String timeframe
    );

    // Rejected trades
    List<TradeExecution> findByStatusOrderByEntryTimeDesc(String status);

    List<TradeExecution> findBySymbolAndStatusOrderByEntryTimeDesc(
            String symbol,
            String status
    );

    @Query("""
        SELECT t FROM TradeExecution t
        WHERE t.status = :status
          AND t.entryTime BETWEEN :startDate AND :endDate
        ORDER BY t.entryTime DESC
    """)
    List<TradeExecution> findRejectedTradesBetweenDates(
            String status,
            LocalDateTime startDate,
            LocalDateTime endDate
    );

    long countByStatus(String status);

    @Query("""
        SELECT COUNT(t)
        FROM TradeExecution t
        WHERE t.symbol = :symbol AND t.status = 'REJECTED'
    """)
    long countRejectedBySymbol(String symbol);

    // ✅ FIXED today query
    @Query("""
        SELECT t
        FROM TradeExecution t
        WHERE t.status = 'REJECTED'
          AND t.entryTime >= :start
          AND t.entryTime < :end
        ORDER BY t.entryTime DESC
    """)
    List<TradeExecution> findTodaysRejectedTrades(
            LocalDateTime start,
            LocalDateTime end
    );

    // Profitability / streak logic
    @Query("""
        SELECT t FROM TradeExecution t
        WHERE t.symbol = :symbol
          AND t.status = :status
          AND t.levelValue BETWEEN :lowerBound AND :upperBound
    """)
    List<TradeExecution> findBySymbolAndStatusAndLevelValueBetween(
            String symbol,
            String status,
            BigDecimal lowerBound,
            BigDecimal upperBound
    );

    // ✅ JPQL-safe
    List<TradeExecution> findTop5BySymbolAndStatusOrderByExitTimeDesc(
            String symbol,
            String status
    );

    List<TradeExecution> findBySymbolAndStatusAndEntryTimeBetween(
            String symbol,
            String status,
            LocalDateTime startDate,
            LocalDateTime endDate
    );
}
