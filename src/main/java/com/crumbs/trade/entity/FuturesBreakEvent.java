package com.crumbs.trade.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "FUTURES_BREAK_EVENT",
       uniqueConstraints = {
           @UniqueConstraint(
               columnNames = {"name", "index_type", "break_type", "break_date"}
           )
       })
@Data
public class FuturesBreakEvent {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    // Stock identification
    @Column(nullable = false)
    private String name;
    
    @Column(name = "index_type", nullable = false)
    private String indexType;
    
    // Breakout/Breakdown type
    @Column(name = "break_type", nullable = false)
    private String breakType;  // 'BREAKOUT' or 'BREAKDOWN'
    
    // Reference level (expiry high or low)
    @Column(name = "reference_level")
    private BigDecimal referenceLevel;
    
    // Stop loss (opposite level)
    @Column(name = "stop_loss")
    private BigDecimal stopLoss;
    
    // Break details
    @Column(name = "break_price")
    private BigDecimal breakPrice;  // 1H candle close that triggered
    
    @Column(name = "break_date", nullable = false)
    private LocalDate breakDate;
    
    @Column(name = "break_time")
    private LocalDateTime breakTime;
    
    // Metrics from futures_filter
    @Column(name = "percent_move")
    private BigDecimal percentMove;
    
    @Column(name = "range_percent")
    private BigDecimal rangePercent;
    
    // Timestamps
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}