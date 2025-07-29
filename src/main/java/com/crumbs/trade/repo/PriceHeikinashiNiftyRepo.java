package com.crumbs.trade.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.PricesHeikinAshiNifty;



@Repository
public interface PriceHeikinashiNiftyRepo extends JpaRepository<PricesHeikinAshiNifty, Long> {

	 @Modifying
     @Query("delete from PricesHeikinAshiNifty p" )
     void deleteAll();
	 
	 public List<PricesHeikinAshiNifty> findAllByOrderByIdAsc();   
	 
	 public List<PricesHeikinAshiNifty> findAllByOrderByIdDesc();
}
