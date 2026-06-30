package com.crumbs.trade.repo;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.crumbs.trade.entity.PreMarketAnalysis;

@Repository
public interface PreMarketAnalysisRepo extends JpaRepository<PreMarketAnalysis, Long> {
    
    /**
     * Find today's pre-market analysis for a specific strategy
     */
    @Query("SELECT p FROM PreMarketAnalysis p WHERE p.name = :name AND p.tradingDate = :date")
    Optional<PreMarketAnalysis> findByNameAndTradingDate(
        @Param("name") String name,
        @Param("date") LocalDate date
    );
    
    /**
     * Find latest pre-market analysis for a strategy
     */
    @Query("SELECT p FROM PreMarketAnalysis p WHERE p.name = :name ORDER BY p.timestamp DESC LIMIT 1")
    Optional<PreMarketAnalysis> findLatestByName(@Param("name") String name);
    
    // ADD THIS METHOD (copy this line exactly as shown):
    Optional<PreMarketAnalysis> findTopByNameOrderByTimestampDesc(String name);
    
    @Modifying
	@Query("delete from PreMarketAnalysis p")
	void deleteAll();
    
    @Modifying
    void deleteByName(String name);
    
}