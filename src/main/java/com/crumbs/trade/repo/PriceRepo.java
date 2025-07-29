package com.crumbs.trade.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;


import com.crumbs.trade.entity.Stoploss;



@Repository
public interface PriceRepo extends JpaRepository<Stoploss, Long> {
	List<Stoploss> findTop3ByNameOrderByIdDesc(String name);
	
	 @Modifying
     @Query("delete from Stoploss p" )
     void deleteAll();
}