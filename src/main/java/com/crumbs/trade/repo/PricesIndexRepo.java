package com.crumbs.trade.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.PricesIndex;




@Repository
public interface PricesIndexRepo extends JpaRepository<PricesIndex, Long> {
	 @Modifying
     @Query("delete from PricesIndex p" )
     void deleteAll();
	 
	 public List<PricesIndex> findAllByOrderByIdAsc();   
	 
	 public List<PricesIndex> findAllByOrderByIdDesc();
}
