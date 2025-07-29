package com.crumbs.trade.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.ResultNifty;



@Repository
public interface ResultNiftyRepo  extends JpaRepository<ResultNifty, Long> {

	ResultNifty findByName(String name);
	
	public List<ResultNifty> findAllByOrderByIdAsc();

	public List<ResultNifty> findAllByOrderByIdDesc();
	
	ResultNifty findByActiveAndName(String active,String name);
	
	@Modifying
	@Query("delete from ResultNifty r")
	void deleteAll();
}
