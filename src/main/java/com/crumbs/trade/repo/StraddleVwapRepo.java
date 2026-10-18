package com.crumbs.trade.repo;

import com.crumbs.trade.entity.StraddleVwapData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface StraddleVwapRepo extends JpaRepository<StraddleVwapData, Long> {

    // Fetch the time-series chart data for a specific day
    @Query("SELECT v FROM StraddleVwapData v WHERE v.symbol = :symbol " +
            "AND v.evaluatedAt >= :startOfDay AND v.evaluatedAt <= :endOfDay " +
            "ORDER BY v.evaluatedAt ASC")
    List<StraddleVwapData> findChartDataForSymbol(
            @Param("symbol") String symbol,
            @Param("startOfDay") LocalDateTime startOfDay,
            @Param("endOfDay") LocalDateTime endOfDay);
}