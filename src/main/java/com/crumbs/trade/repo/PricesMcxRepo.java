package com.crumbs.trade.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.PricesMcx;



@Repository
public interface PricesMcxRepo extends JpaRepository<PricesMcx, Long> {
	@Modifying
	@Query("delete from PricesMcx p")
	void deleteAll();

	public List<PricesMcx> findAllByOrderByIdAsc();

	public List<PricesMcx> findAllByOrderByIdDesc();
}