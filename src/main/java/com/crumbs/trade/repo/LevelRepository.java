package com.crumbs.trade.repo;

import com.crumbs.trade.entity.Level;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface LevelRepository extends JpaRepository<Level, Long> {

	@Modifying
	@Query("delete from Level o")
	void deleteAll();
    // =========================================================
    // 🔹 1. FIND MATCHING LEVEL (MERGE LOGIC)
    // =========================================================
    @Query("""
        SELECT l FROM Level l
        WHERE l.symbol = :symbol
        AND l.timeframe = :timeframe
        AND l.active = true
        AND ABS(l.price - :price) <= :buffer
    """)
    Level findMatchingLevel(@Param("symbol") String symbol,
                            @Param("timeframe") String timeframe,
                            @Param("price") BigDecimal price,
                            @Param("buffer") BigDecimal buffer);


    // =========================================================
    // 🔹 2. GET ACTIVE LEVELS (FOR TRADING)
    // =========================================================
    @Query("""
        SELECT l FROM Level l
        WHERE l.symbol = :symbol
        AND l.active = true
        ORDER BY l.price ASC
    """)
    List<Level> findActiveLevels(@Param("symbol") String symbol);


    // =========================================================
    // 🔹 3. (OPTIONAL) FETCH ALL FOR UI (EOD PLOT)
    // =========================================================
    @Query("""
        SELECT l FROM Level l
        WHERE l.symbol = :symbol
        AND l.timeframe = :timeframe
        ORDER BY l.price ASC
    """)
    List<Level> findAllLevels(@Param("symbol") String symbol,
                             @Param("timeframe") String timeframe);


    // =========================================================
    // 🔹 4. (OPTIONAL) DEACTIVATE OLD LEVELS
    // =========================================================
    @Query("""
        UPDATE Level l
        SET l.active = false
        WHERE l.symbol = :symbol
        AND l.active = true
        AND l.lastTouchedAt < :cutoffTime
    """)
    void deactivateOldLevels(@Param("symbol") String symbol,
                             @Param("cutoffTime") java.time.LocalDateTime cutoffTime);
}