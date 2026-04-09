package com.crumbs.trade.repo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.StraddleIntraday;
import com.crumbs.trade.entity.Strategy;



@Repository
public interface StrategyRepo extends JpaRepository<Strategy, Long> {

	Strategy findByName(String name);
	
	Optional<Strategy> findByNameIgnoreCase(String name);
	
	@Modifying
	@Query("update Strategy s set s.active = ?1 where s.id=?2" )
    int updateStrategyById(String active,long id);
	
	@Modifying
	@Query("update Strategy s set s.candlestick = ?1 where s.id=?2" )
    int updateCandleStickById(int candlestick,long id);
	
	@Query("""
		    SELECT s.name, s.expiry, s.strike
		    FROM StraddleIntraday s
		    ORDER BY s.name, s.expiry, s.strike
		""")
	List<Object[]> fetchNameExpiryStrikeRaw();
	
	// In StraddleIntradayRepo.java
	@Query("SELECT s FROM StraddleIntraday s WHERE s.name = :name AND s.strike = :strike " +
	       "ORDER BY s.timestamp DESC")
	List<StraddleIntraday> findLastTwo(@Param("name") String name, 
	                                   @Param("strike") BigDecimal strike, 
	                                   Pageable pageable);

}