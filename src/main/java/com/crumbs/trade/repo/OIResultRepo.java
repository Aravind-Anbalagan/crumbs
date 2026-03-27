package com.crumbs.trade.repo;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.crumbs.trade.entity.OIResult;

public interface OIResultRepo extends JpaRepository<OIResult, Long> {

    // 🔥 Optional: clear table (if you reset daily)
    @Modifying
    @Query("delete from OIResult")
    void deleteAll();

    // 🔥 Latest full option chain (bar chart)
    @Query("""
        SELECT o FROM OIResult o
        WHERE o.name = :name
        AND o.timestamp = (
            SELECT MAX(o2.timestamp)
            FROM OIResult o2
            WHERE o2.name = :name
        )
        ORDER BY o.strike ASC
    """)
    List<OIResult> findLatestByName(@Param("name") String name);

    // 🔥 Full time-series for a strike (core API)
    @Query(value = """
        SELECT *
        FROM oi_result
        WHERE name = :name
        AND strike = :strike
        ORDER BY timestamp ASC
    """, nativeQuery = true)
    List<OIResult> findStrikeData(
            @Param("name") String name,
            @Param("strike") BigDecimal strike
    );
}