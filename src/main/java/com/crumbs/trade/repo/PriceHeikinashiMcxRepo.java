package com.crumbs.trade.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.PricesHeikinAshiMcx;



@Repository
public interface PriceHeikinashiMcxRepo extends JpaRepository<PricesHeikinAshiMcx, Long> {

	 @Modifying
     @Query("delete from PricesHeikinAshiMcx p" )
     void deleteAll();
	 
	 public List<PricesHeikinAshiMcx> findAllByOrderByIdAsc();   
	 
	 public List<PricesHeikinAshiMcx> findAllByOrderByIdDesc();
}
