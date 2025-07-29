package com.crumbs.trade.repo;


import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.PSARIndex;



@Repository
public interface PsarIndexRepo extends JpaRepository<PSARIndex, Long>{
	@Modifying
	@Query("delete from PSARIndex p")
	void deleteAll();

	public List<PSARIndex> findAllByOrderByIdAsc();

	public List<PSARIndex> findAllByOrderByIdDesc();
	
	 @Query(value = "SELECT e FROM PSARIndex e ORDER BY e.id DESC")
	 List<PSARIndex> findLastTwoRows(Pageable pageable);
}
