package com.crumbs.trade.repo;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.OIDATA;



@Repository
public interface OIDataRepo extends JpaRepository<OIDATA, Long> {
	
	List<OIDATA> findByNameAndExpiry(String name,String expiry);
	
	OIDATA findByStrikePriceAndNameAndExpiry(BigDecimal strikePrice,String name,String Epxiry);
	
	@Modifying
	@Query("delete from OIDATA o")
	void deleteAll();
	
	OIDATA findBySpotAndName(String active,String name);
}