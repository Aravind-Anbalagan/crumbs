package com.crumbs.trade.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.Nifty;



@Repository
public interface NiftyRepo extends JpaRepository<Nifty, Long> {

	@Query(value = "select name from Nifty ")
	List<String> getAllNames();
}
