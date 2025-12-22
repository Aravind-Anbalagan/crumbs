package com.crumbs.trade.repo;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.PredictionHistory;
@Repository
public interface PredictionHistoryRepo extends JpaRepository<PredictionHistory, Long> {
	@Modifying
	@Query("delete from PredictionHistory o")
	void deleteAll();
	
	  // Today (00:00 → now)
    @Query("""
        SELECT p FROM PredictionHistory p
        WHERE p.timestamp >= :startOfDay
    """)
    List<PredictionHistory> findToday(
            @Param("startOfDay") LocalDateTime startOfDay
    );

    // From a date → now (last N days)
    @Query("""
        SELECT p FROM PredictionHistory p
        WHERE p.timestamp >= :fromDate
    """)
    List<PredictionHistory> findFromDate(
            @Param("fromDate") LocalDateTime fromDate
    );
}
