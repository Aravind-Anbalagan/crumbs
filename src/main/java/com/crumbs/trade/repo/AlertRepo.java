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
    
 // Last N dominance alerts for symbol — prices + VWAP already inside each row
    @Query("""
        SELECT a FROM Alert a
        WHERE a.strategyName = :strategyName
          AND a.symbol       = :symbol
          AND a.signalType   IN ('VWAP_DOMINANCE_CE', 'VWAP_DOMINANCE_PE')
        ORDER BY a.sentAt DESC
        """)
    List<Alert> findTopDominanceAlerts(
            @Param("strategyName") String strategyName,
            @Param("symbol")       String symbol,
            Pageable pageable);

    // Crossovers in a time window — for noise check + warning
    @Query("""
        SELECT a FROM Alert a
        WHERE a.strategyName = :strategyName
          AND a.symbol       = :symbol
          AND a.signalType   IN ('CE_PE_CROSSOVER', 'PE_CE_CROSSOVER')
          AND a.sentAt      >= :since
        ORDER BY a.sentAt DESC
        """)
    List<Alert> findRecentCrossoverAlerts(
            @Param("strategyName") String strategyName,
            @Param("symbol")       String symbol,
            @Param("since")        LocalDateTime since);
}