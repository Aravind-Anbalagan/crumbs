package com.crumbs.trade.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "ORDERS_HISTORY")
public class OrdersHistory {
    
    @Id
    @GeneratedValue(strategy= GenerationType.AUTO)
    private Long id;

    // --- 1. FILTERING COLUMNS ---
    @Column(name="trade_date", nullable = false)
    private LocalDate tradeDate; 
    
    @Column(name="trade_month")
    private String tradeMonth; // Format: "YYYY-MM"

    // --- 2. IDENTIFIERS & EXECUTION ---
    @Column(name="broker_order_id")
    private String brokerOrderId; // Mapped from Orders.orderid
    
    @Column(name="trade_cycle_id")
    private String tradeCycleId; 
    
    @Column(name="signal")
    private String signal; // Strategy Name
    
    @Column(name="execution_type")
    private String executionType; // "LIVE" or "PAPER"

    // --- 3. ASSET DETAILS ---
    @Column(name="symbol")
    private String symbol;
    
    @Column(name="option_type")
    private String optionType; 
    
    @Column(name="side")
    private String side; 
    
    @Column(name="quantity")
    private int quantity;

    // --- 4. TIMING ---
    @Column(name="entry_time")
    private LocalDateTime entryTime; 
    
    @Column(name="exit_time")
    private LocalDateTime exitTime; 

    // --- 5. FINANCIALS ---
    @Column(name = "entry_price", precision = 12, scale = 2)
    private BigDecimal entryPrice; 

    @Column(name = "exit_price", precision = 12, scale = 2)
    private BigDecimal exitPrice;

    @Column(name = "realized_pnl", precision = 12, scale = 2)
    private BigDecimal realizedPnl; 

    @Column(name = "exit_reason")
    private String exitReason; 
}