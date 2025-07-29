package com.crumbs.trade.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.Result;


@Repository
public interface ResultRepo  extends JpaRepository<Result, Long> {

	Result findByName(String name);
	
	public List<Result> findAllByOrderByIdAsc();

	public List<Result> findAllByOrderByIdDesc();
	
	Result findByActiveAndName(String active,String name);
	
	@Modifying
	@Query("delete from Result r")
	void deleteAll();
}
