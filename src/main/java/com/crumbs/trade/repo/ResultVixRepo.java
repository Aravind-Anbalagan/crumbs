package com.crumbs.trade.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.ResultVix;



@Repository
public interface ResultVixRepo  extends JpaRepository<ResultVix, Long> {

	List<ResultVix> findByName(String name);
	
	public List<ResultVix> findAllByOrderByIdAsc();

	public List<ResultVix> findAllByOrderByIdDesc();
	
	ResultVix findByActiveAndName(String active,String name);
	
	@Modifying
	@Query("delete from ResultVix r")
	void deleteAll();
	
	
}
