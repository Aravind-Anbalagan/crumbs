package com.crumbs.trade.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.PSARNifty;



@Repository
public interface PsarNiftyRepo extends JpaRepository<PSARNifty, Long>{
	@Modifying
	@Query("delete from PSARNifty p")
	void deleteAll();

	public List<PSARNifty> findAllByOrderByIdAsc();

	public List<PSARNifty> findAllByOrderByIdDesc();
}
