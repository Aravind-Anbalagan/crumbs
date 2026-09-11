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

    Optional<OptionPrice> findByTokenAndEvaluatedDateAndTimeFrame(String token, LocalDate evaluatedDate, String timeFrame);

    List<OptionPrice> findAllByTimeFrameOrderByEvaluatedAtDesc(String timeFrame);

    List<OptionPrice> findAllByOrderByEvaluatedAtDesc();

    @Modifying
    @Query("delete from OptionPrice p")
    void deleteAll();

    // ==========================================
    // 1. LIFECYCLE AUDIT (CHART HISTORY)
    // ==========================================
    List<OptionPrice> findAllBySymbolOrderByEvaluatedAtAsc(String symbol);

    List<OptionPrice> findAllBySymbolAndTimeFrameOrderByEvaluatedAtAsc(String symbol, String timeFrame);

    // ==========================================
    // 2. LIVE DASHBOARD: RSI SIGNALS ONLY
    // ==========================================
    @Query(value = "SELECT DISTINCT ON (symbol) * FROM option_prices " +
            "WHERE evaluated_date = CURRENT_DATE AND signal_action != 'NONE' " +
            "ORDER BY symbol, evaluated_at DESC",
            nativeQuery = true)
    List<OptionPrice> findLatestRsiSignalsAllTimeFrames();

    @Query(value = "SELECT DISTINCT ON (symbol) * FROM option_prices " +
            "WHERE time_frame = :timeFrame AND evaluated_date = CURRENT_DATE AND signal_action != 'NONE' " +
            "ORDER BY symbol, evaluated_at DESC",
            nativeQuery = true)
    List<OptionPrice> findLatestRsiSignalsByTimeFrame(@Param("timeFrame") String timeFrame);

    // ==========================================
    // 3. LIVE DASHBOARD: MA SIGNALS (Breakouts & Breakdowns)
    // ==========================================
    // FIXED: Removed 'AND is_price_above_ma = true' so Breakdowns can finally be queried.
    @Query(value = "SELECT DISTINCT ON (symbol) * FROM option_prices " +
            "WHERE evaluated_date = CURRENT_DATE " +
            "ORDER BY symbol, evaluated_at DESC",
            nativeQuery = true)
    List<OptionPrice> findLatestMaSignalsAllTimeFrames();

    @Query(value = "SELECT DISTINCT ON (symbol) * FROM option_prices " +
            "WHERE time_frame = :timeFrame AND evaluated_date = CURRENT_DATE " +
            "ORDER BY symbol, evaluated_at DESC",
            nativeQuery = true)
    List<OptionPrice> findLatestMaSignalsByTimeFrame(@Param("timeFrame") String timeFrame);

    // ==========================================
    // 4. LIVE DASHBOARD: ALL TRACKED (Used for Dominance UI)
    // ==========================================
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