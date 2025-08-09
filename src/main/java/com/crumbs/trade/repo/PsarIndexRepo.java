package com.crumbs.trade.repo;


import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.PSARIndex;

import jakarta.transaction.Transactional;



@Repository
public interface PsarIndexRepo extends JpaRepository<PSARIndex, Long>{
	@Modifying
	@Query("delete from PSARIndex p")
	void deleteAll();

	public List<PSARIndex> findAllByOrderByIdAsc();

	public List<PSARIndex> findAllByOrderByIdDesc();
	
	 @Query(value = "SELECT e FROM PSARIndex e ORDER BY e.id DESC")
	 List<PSARIndex> findLastTwoRows(Pageable pageable);
	 
	 @Modifying
	 @Transactional
	 @Query("DELETE FROM PSARIndex p WHERE p.name = :name AND p.timeframe = :timeframe")
	 void deleteByNameAndTimeframe(@Param("name") String name, @Param("timeframe") String timeframe);

}
