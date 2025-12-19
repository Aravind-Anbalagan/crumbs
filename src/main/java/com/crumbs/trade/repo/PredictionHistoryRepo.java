package com.crumbs.trade.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.PredictionHistory;
@Repository
public interface PredictionHistoryRepo extends JpaRepository<PredictionHistory, Long> {
	@Modifying
	@Query("delete from PredictionHistory o")
	void deleteAll();
}
