package com.crumbs.trade.repo;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.crumbs.trade.entity.OrdersHistory;

@Repository
public interface OrdersHistoryRepository extends JpaRepository<OrdersHistory, Long> {
    
    // Fetch a specific day's history
    List<OrdersHistory> findByTradeDate(LocalDate tradeDate);
    
    // Fetch a whole month's history (e.g., "2026-06")
    List<OrdersHistory> findByTradeMonth(String tradeMonth);
    
    // Filter by LIVE or PAPER across the whole history (if needed)
    List<OrdersHistory> findByExecutionType(String executionType);
    
    // Fetch overall history by strategy (signal)
    List<OrdersHistory> findBySignal(String signal);
}