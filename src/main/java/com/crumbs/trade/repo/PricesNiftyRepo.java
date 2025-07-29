package com.crumbs.trade.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.PricesNifty;




@Repository
public interface PricesNiftyRepo extends JpaRepository<PricesNifty, Long> {
	 @Modifying
     @Query("delete from PricesNifty p" )
     void deleteAll();
	 
	 public List<PricesNifty> findAllByOrderByIdAsc();   
	 
	 public List<PricesNifty> findAllByOrderByIdDesc();
}
