package com.crumbs.trade.repo;

import java.time.LocalDate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.FuturesBreakEvent;

@Repository
public interface FuturesBreakEventRepo
        extends JpaRepository<FuturesBreakEvent, Long> {

    /**
     * Used to prevent duplicate breakout / breakdown alerts
     * per stock per index per day
     */
    boolean existsByNameAndIndexTypeAndBreakTypeAndBreakDate(
            String name,
            String indexType,
            String breakType,
            LocalDate breakDate
    );
}
