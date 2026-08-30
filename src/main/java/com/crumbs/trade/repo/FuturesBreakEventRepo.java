package com.crumbs.trade.repo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.FuturesBreakEvent;

@Repository
public interface FuturesBreakEventRepo
        extends JpaRepository<FuturesBreakEvent, Long> {

    /**
     * Used to prevent duplicate breakout / breakdown alerts
     * per stock per index per day
     */
    boolean existsByNameAndIndexTypeAndBreakTypeAndBreakDate(
            String name,
            String indexType,
            String breakType,
            LocalDate breakDate
    );
    
    // ✅ NEW: For API endpoints
    List<FuturesBreakEvent> findByBreakDate(LocalDate date);
    List<FuturesBreakEvent> findByBreakDateOrderByBreakTimeDesc(LocalDate date);
    
    // Get all for today
    default List<FuturesBreakEvent> findByToday() {
        return findByBreakDate(LocalDate.now());
    }
    
    // Get breakouts only
    List<FuturesBreakEvent> findByBreakTypeAndBreakDateOrderByBreakTimeDesc(
            String breakType, LocalDate date);
    
    // Get by stock name
    List<FuturesBreakEvent> findByNameAndBreakDateOrderByBreakTimeDesc(
            String name, LocalDate date);
    
    // Custom query for dashboard summary
    @Query("SELECT f FROM FuturesBreakEvent f WHERE f.breakDate = ?1 ORDER BY f.breakTime DESC")
    List<FuturesBreakEvent> findAllByDateOrdered(LocalDate date);
    
 // ✅ FIXED: Without indexType to prevent duplicates
    boolean existsByNameAndBreakTypeAndBreakDate(String name, String breakType, LocalDate breakDate);
    
    List<FuturesBreakEvent> findByIndexType(String indexType);
    
    List<FuturesBreakEvent> findByBreakDateAndIndexType(LocalDate breakDate, String indexType);
    
 // ✅ Find active signal for a stock
    Optional<FuturesBreakEvent> findByNameAndBreakTypeAndBreakDateAndStatus(
            String name, String breakType, LocalDate breakDate, String status);
    
 // Single record lookup — no date, no status
    Optional<FuturesBreakEvent> findByNameAndBreakType(String name, String breakType);
    List<FuturesBreakEvent> findByStatus(String status);
    List<FuturesBreakEvent> findByStatusOrderByBreakDateAsc(String status);
}
