package com.crumbs.trade.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.PSARMcx;



@Repository
public interface PsarMcxRepo extends JpaRepository<PSARMcx, Long>{
	@Modifying
	@Query("delete from PSARMcx p")
	void deleteAll();

	public List<PSARMcx> findAllByOrderByIdAsc();

	public List<PSARMcx> findAllByOrderByIdDesc();
}
