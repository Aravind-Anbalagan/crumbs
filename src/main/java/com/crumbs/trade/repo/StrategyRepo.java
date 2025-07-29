package com.crumbs.trade.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.Strategy;



@Repository
public interface StrategyRepo extends JpaRepository<Strategy, Long> {

	Strategy findByName(String name);
	
	@Modifying
	@Query("update Strategy s set s.active = ?1 where s.id=?2" )
    int updateStrategyById(String active,long id);
	
	@Modifying
	@Query("update Strategy s set s.candlestick = ?1 where s.id=?2" )
    int updateCandleStickById(int candlestick,long id);
}