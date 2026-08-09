package com.crumbs.trade.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(OrdersHistoryService.class);
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    @Autowired
    private OrderRepository ordersRepository;

    @Autowired
    private OrdersHistoryRepository historyRepository;

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
    // Explicitly set timezone to IST, otherwise cloud servers default to UTC
    @Scheduled(cron = "0 30 23 * * MON-FRI", zone = "Asia/Kolkata")
    public void archiveDailyOrders() {
        log.info("Starting EOD Archiving process...");

        List<Orders> closedOrders = ordersRepository.findByStatus("CLOSED");

        if (closedOrders.isEmpty()) {
            log.info("No CLOSED orders found to archive today.");
            return;
        }

        List<OrdersHistory> historyRecords = closedOrders.stream()
                .map(order -> {
                    try {
                        // DEFENSIVE CHECK: Prevent one bad record from failing the whole batch
                        if (order.getClosedOn() == null) {
                            log.warn("Order ID {} is CLOSED but has null closedOn date. Skipping archive.", order.getId());
                            return null;
                        }

                        OrdersHistory history = new OrdersHistory();

                        LocalDate date = order.getClosedOn().toLocalDate();
                        history.setTradeDate(date);
                        history.setTradeMonth(date.format(MONTH_FORMATTER));

                        String orderId = order.getOrderid();
                        if (orderId == null || orderId.trim().isEmpty() || orderId.trim().equals("1")) {
                            history.setExecutionType("PAPER");
                            history.setBrokerOrderId(null);
                        } else {
                            history.setExecutionType("LIVE");
                            history.setBrokerOrderId(orderId);
                        }

                        history.setTradeCycleId(order.getTradeCycleId());
                        history.setSignal(order.getSignal());

                        history.setSymbol(order.getSymbol());
                        history.setOptionType(order.getOptionType());
                        history.setSide(order.getSide());
                        history.setQuantity(order.getQuantity());

                        history.setEntryTime(order.getCreatedOn());
                        history.setExitTime(order.getClosedOn());

                        history.setEntryPrice(order.getAskPrice());
                        history.setExitPrice(order.getExitPrice());
                        history.setRealizedPnl(order.getPl());
                        history.setExitReason(order.getExitReason());

                        return history;
                    } catch (Exception e) {
                        log.error("Failed to map order {} to history: {}", order.getId(), e.getMessage());
                        return null; // Skip this specific record but let the rest process
                    }
                })
                .filter(Objects::nonNull) // Remove any skipped records
                .collect(Collectors.toList());

        if (!historyRecords.isEmpty()) {
            historyRepository.saveAll(historyRecords);

            // Extract IDs of successfully mapped orders to delete them safely
            List<Long> idsToDelete = closedOrders.stream()
                    .filter(o -> o.getClosedOn() != null) // Only delete ones we successfully processed
                    .map(Orders::getId) // Assuming your primary key is 'id'
                    .collect(Collectors.toList());

            // Bulk delete using a custom repository method (faster than JPA deleteAll)
            if (!idsToDelete.isEmpty()) {
                ordersRepository.deleteAllByIdInBatch(idsToDelete);
            }

            log.info("EOD Archiving Complete: {} orders moved to history.", historyRecords.size());
        }
    }
}