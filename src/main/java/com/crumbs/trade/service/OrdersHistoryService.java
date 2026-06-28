package com.crumbs.trade.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.OrdersHistory;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.OrdersHistoryRepository;

@Service
public class OrdersHistoryService {

    @Autowired
    private OrderRepository ordersRepository;

    @Autowired
    private OrdersHistoryRepository historyRepository;

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    // ==========================================
    // 1. API FETCH METHODS (For React Dashboard)
    // ==========================================
    
    public List<OrdersHistory> getOverallHistory() {
        return historyRepository.findAll();
    }

    public List<OrdersHistory> getHistoryByMonth(String yearMonth) {
        return historyRepository.findByTradeMonth(yearMonth);
    }

    public List<OrdersHistory> getHistoryByDate(LocalDate date) {
        return historyRepository.findByTradeDate(date);
    }

    // ==========================================
    // 2. THE EOD ARCHIVER (Runs Daily)
    // ==========================================
    
    @Transactional
    // Runs at 23:30 (11:30 PM) every Mon-Fri. 
    @Scheduled(cron = "0 30 23 * * MON-FRI")
    public void archiveDailyOrders() {
        // 1. Fetch all CLOSED orders from the live table
        // (Assuming you have a findByStatus method in OrdersRepository)
        List<Orders> closedOrders = ordersRepository.findByStatus("CLOSED");

        if (closedOrders.isEmpty()) return;

        // 2. Map Orders to OrdersHistory
        List<OrdersHistory> historyRecords = closedOrders.stream().map(order -> {
            OrdersHistory history = new OrdersHistory();
            
            // Generate easy-filter dates based on when it closed
            LocalDate date = order.getClosedOn().toLocalDate();
            history.setTradeDate(date);
            history.setTradeMonth(date.format(MONTH_FORMATTER)); // e.g. "2026-06"
            
            // EXECUTION LOGIC: Determine LIVE vs PAPER based on orderid
            if (order.getOrderid() == null || order.getOrderid().trim().isEmpty()) {
                history.setExecutionType("PAPER");
                history.setBrokerOrderId(null);
            } else {
                history.setExecutionType("LIVE");
                history.setBrokerOrderId(order.getOrderid());
            }

            // Map identifiers and strategy
            history.setTradeCycleId(order.getTradeCycleId());
            history.setSignal(order.getSignal());
            
            // Map asset details
            history.setSymbol(order.getSymbol());
            history.setOptionType(order.getOptionType());
            history.setSide(order.getSide());
            history.setQuantity(order.getQuantity());
            
            // Map timing
            history.setEntryTime(order.getCreatedOn());
            history.setExitTime(order.getClosedOn());
            
            // Map financials
            history.setEntryPrice(order.getAskPrice());
            history.setExitPrice(order.getExitPrice());
            history.setRealizedPnl(order.getPl()); // Assuming 'pl' is fully updated by close
            history.setExitReason(order.getExitReason());

            return history;
        }).collect(Collectors.toList());

        // 3. Save all to history table
        historyRepository.saveAll(historyRecords);

        // 4. Delete the successfully archived records from the live table
        ordersRepository.deleteAll(closedOrders);
        
        System.out.println("EOD Archiving Complete: " + historyRecords.size() + " orders moved to history.");
    }
}