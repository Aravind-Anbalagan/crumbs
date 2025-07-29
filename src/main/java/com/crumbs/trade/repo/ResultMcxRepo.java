package com.crumbs.trade.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.ResultMcx;



@Repository
public interface ResultMcxRepo  extends JpaRepository<ResultMcx, Long> {

	ResultMcx findByName(String name);
	
	public List<ResultMcx> findAllByOrderByIdAsc();

	public List<ResultMcx> findAllByOrderByIdDesc();
	
	ResultMcx findByActiveAndName(String active,String name);
	
	@Modifying
	@Query("delete from ResultMcx r")
	void deleteAll();
}
