package com.crumbs.trade.repo;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.crumbs.trade.entity.Alert;

public interface AlertRepo extends JpaRepository<Alert, Long> {
    List<Alert> findByStrategyName(String strategyName);
    List<Alert> findByStrategyNameAndSignalType(String strategyName, String signalType);
    List<Alert> findBySentAtBetween(LocalDateTime from, LocalDateTime to);
    @Modifying
	@Query("delete from Alert a")
	void deleteAll();
    
 // ── Dominance alerts — filter by symbol + signalType only
    //    strategyName removed: saved as "VWAP_DOMINANCE" by StraddleIntradayService
    //    but queried as "VWAP_OPTION_SELLER" by DominanceService → always 0 results
    @Query("""
        SELECT a FROM Alert a
        WHERE a.symbol     = :symbol
          AND a.signalType IN ('VWAP_DOMINANCE_CE', 'VWAP_DOMINANCE_PE')
        ORDER BY a.sentAt DESC
        """)
    List<Alert> findTopDominanceAlerts(
            @Param("symbol")   String symbol,
            Pageable           pageable);

    // ── Crossover alerts — filter by symbol + signalType + time window
    @Query("""
        SELECT a FROM Alert a
        WHERE a.symbol     = :symbol
          AND a.signalType IN ('CE_PE_CROSSOVER', 'PE_CE_CROSSOVER')
          AND a.sentAt    >= :since
        ORDER BY a.sentAt DESC
        """)
    List<Alert> findRecentCrossoverAlerts(
            @Param("symbol") String symbol,
            @Param("since")  LocalDateTime since);

    // ── Recent dominance alerts — for SL check (last N ticks)
    @Query("""
        SELECT a FROM Alert a
        WHERE a.symbol     = :symbol
          AND a.signalType IN ('VWAP_DOMINANCE_CE', 'VWAP_DOMINANCE_PE')
          AND a.sentAt    >= :since
        ORDER BY a.sentAt DESC
        """)
    List<Alert> findRecentDominanceAlerts(
            @Param("symbol") String symbol,
            @Param("since")  LocalDateTime since);
}