package com.crumbs.trade.repo;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.crumbs.trade.entity.Pnl;

@Repository
public interface PnlRepo extends JpaRepository<Pnl, Long> {
	  List<Pnl> findByName(String name);
	    List<Pnl> findByTradeDateBetween(LocalDate from, LocalDate to);
	    List<Pnl> findByNameAndTradeDateBetween(String name, LocalDate from, LocalDate to);
	    List<Pnl> findByNameAndTradeDateGreaterThanEqual(String name, LocalDate from);
	    List<Pnl> findByNameAndTradeDateLessThanEqual(String name, LocalDate to);
}
