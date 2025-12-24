package com.crumbs.trade.repo;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.FuturesFilter;

@Repository
public interface FuturesFilterRepo extends JpaRepository<FuturesFilter, Long> {

    List<FuturesFilter> findByLastExpiryDate(LocalDate toDate);

    List<FuturesFilter> findByStatus(String status);

    List<FuturesFilter> findByDirection(String direction);
}
