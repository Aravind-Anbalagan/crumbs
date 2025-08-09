package com.crumbs.trade.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.PricesIndex;

import jakarta.transaction.Transactional;
import org.springframework.data.domain.*;



@Repository
public interface PricesIndexRepo extends JpaRepository<PricesIndex, Long> {
	 @Modifying
     @Query("delete from PricesIndex p" )
     void deleteAll();
	 
	 public List<PricesIndex> findAllByOrderByIdAsc();   
	 
	 public List<PricesIndex> findAllByOrderByIdDesc();
	 
	 @Modifying
	 @Transactional
	 @Query("DELETE FROM PricesIndex p WHERE p.name = :name AND p.timeframe = :timeframe")
	 void deleteByNameAndTimeframe(@Param("name") String name, @Param("timeframe") String timeframe);

	
	 // Get latest N records (by id desc) for a given name/timeframe
	 List<PricesIndex> findByNameAndTimeframe(String name, String timeframe, Pageable pageable);

	 // Get top N by volume for a given name/timeframe (descending volume)
	 List<PricesIndex> findByNameAndTimeframeOrderByVolumeDesc(String name, String timeframe, Pageable pageable);
	 List<PricesIndex> findByNameAndTimeframe(String name, String timeframe);
}
