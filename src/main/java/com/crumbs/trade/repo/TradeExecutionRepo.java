package com.crumbs.trade.repo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.crumbs.trade.entity.TradeExecution;

public interface TradeExecutionRepo
        extends JpaRepository<TradeExecution, Long> {

    Optional<TradeExecution>
    findFirstBySymbolAndTimeframeAndStatus(
            String symbol,
            String timeframe,
            String status);

    Optional<TradeExecution>
    findFirstBySymbolAndTimeframeOrderByEntryTimeDesc(
            String symbol,
            String timeframe);
    
    // Intraday = same day between start & end
    List<TradeExecution> findByEntryTimeBetween(LocalDateTime start, LocalDateTime end);

    List<TradeExecution> findBySymbolAndEntryTimeBetween(
            String symbol,
            LocalDateTime start,
            LocalDateTime end
    );

    List<TradeExecution> findBySymbolAndStatusAndEntryTimeBetween(
            String symbol,
            String status,
            LocalDateTime start,
            LocalDateTime end
    );
    
    @Modifying
	@Query("delete from TradeExecution o")
	void deleteAll();
}
