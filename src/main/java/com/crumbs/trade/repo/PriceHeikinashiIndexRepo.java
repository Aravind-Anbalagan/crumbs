package com.crumbs.trade.repo;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.PricesHeikinAshiIndex;



@Repository
public interface PriceHeikinashiIndexRepo extends JpaRepository<PricesHeikinAshiIndex, Long> {

	 @Modifying
     @Query("delete from PricesHeikinAshiIndex p" )
     void deleteAll();
	 
	 public List<PricesHeikinAshiIndex> findAllByOrderByIdAsc();   
	 
	 public List<PricesHeikinAshiIndex> findAllByOrderByIdDesc();
	 
	 @Query(value = "SELECT e FROM PricesHeikinAshiIndex e ORDER BY e.id DESC")
	 List<PricesHeikinAshiIndex> findLastTwoRows(Pageable pageable);
}
