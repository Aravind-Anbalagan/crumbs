package com.crumbs.trade.repo;

import com.crumbs.trade.entity.OptionPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface OptionPriceRepo extends JpaRepository<OptionPrice, Long> {

    // 👈 Add this line to fix the error
    Optional<OptionPrice> findByTokenAndEvaluatedDateAndTimeFrame(String token, LocalDate evaluatedDate, String timeFrame);
    List<OptionPrice> findAllByTimeFrameOrderByEvaluatedAtDesc(String timeFrame);
    // This fetches data ordered by the most recent evaluations for your API
    List<OptionPrice> findAllByOrderByEvaluatedAtDesc();

    @Modifying
    @Query("delete from OptionPrice p" )
    void deleteAll();

    // 1. For the Lifecycle Audit (Gets all rows for a symbol, ordered Oldest -> Newest)
    List<OptionPrice> findAllBySymbolOrderByEvaluatedAtAsc(String symbol);
    List<OptionPrice> findAllBySymbolAndTimeFrameOrderByEvaluatedAtAsc(String symbol, String timeFrame);

    // 2. For the Live Dashboard (Gets ONLY the latest row for each symbol today)
    @Query(value = "SELECT DISTINCT ON (symbol) * FROM option_prices " +
            "WHERE evaluated_date = CURRENT_DATE " +
            "ORDER BY symbol, evaluated_at DESC",
            nativeQuery = true)
    List<OptionPrice> findLatestLiveTrackedDataAllTimeFrames();

    @Query(value = "SELECT DISTINCT ON (symbol) * FROM option_prices " +
            "WHERE time_frame = :timeFrame AND evaluated_date = CURRENT_DATE " +
            "ORDER BY symbol, evaluated_at DESC",
            nativeQuery = true)
    List<OptionPrice> findLatestLiveTrackedDataByTimeFrame(@Param("timeFrame") String timeFrame);
}