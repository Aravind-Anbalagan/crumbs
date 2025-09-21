package com.crumbs.trade.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import com.crumbs.trade.entity.NIFTY500;

@Repository
public interface Nifty500Repo extends JpaRepository<NIFTY500, Long> {

	@Query(value = "select name from NIFTY500 ")
	List<String> getAllNames();
	
	 // Fetch only stocks where sector is null or empty
    List<NIFTY500> findBySectorIsNullOrSectorEquals(String emptyString);
    
    Optional<NIFTY500> findByNameIgnoreCase(String name);
}
