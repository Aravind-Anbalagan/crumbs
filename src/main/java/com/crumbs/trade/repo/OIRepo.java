package com.crumbs.trade.repo;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.OI;



@Repository
public interface OIRepo extends JpaRepository<OI, Long> {
	
	List<OI> findByName(String name);
	
	OI findByStrikePriceAndName(BigDecimal strikePrice,String name);
	
	@Modifying
	@Query("delete from OI o")
	void deleteAll();
	
	OI findBySpotAndName(String active,String name);
}