package com.crumbs.trade.repo;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.StraddleIntraday;

@Repository
public interface StraddleIntradayRepo extends JpaRepository<StraddleIntraday, Long>  {

	@Query("SELECT s FROM StraddleIntraday s WHERE s.name = :name AND s.expiry = :expiry AND s.strike = :strike ORDER BY s.timestamp ASC")
	List<StraddleIntraday> getByStrike(String name, String expiry, BigDecimal strike);

	@Query("SELECT s FROM StraddleIntraday s WHERE s.name = :name AND s.expiry = :expiry ORDER BY s.timestamp ASC")
	List<StraddleIntraday> getSpotHistory(String name, String expiry);

	@Query("""
		    SELECT s.name, s.expiry, s.strike
		    FROM StraddleIntraday s
		    ORDER BY s.name, s.expiry, s.strike
		""")
	List<Object[]> fetchNameExpiryStrikeRaw();

	@Modifying
	@Query("delete from StraddleIntraday o")
	void deleteAll();
}
