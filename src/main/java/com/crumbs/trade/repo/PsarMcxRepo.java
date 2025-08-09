package com.crumbs.trade.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.PSARMcx;

import jakarta.transaction.Transactional;



@Repository
public interface PsarMcxRepo extends JpaRepository<PSARMcx, Long>{
	@Modifying
	@Query("delete from PSARMcx p")
	void deleteAll();

	public List<PSARMcx> findAllByOrderByIdAsc();

	public List<PSARMcx> findAllByOrderByIdDesc();
	
	@Modifying
	 @Transactional
	 @Query("DELETE FROM PSARMcx p WHERE p.name = :name AND p.timeframe = :timeframe")
	 void deleteByNameAndTimeframe(@Param("name") String name, @Param("timeframe") String timeframe);

}
