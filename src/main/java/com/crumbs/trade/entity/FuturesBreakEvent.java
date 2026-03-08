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
    
    @Column(nullable = false)
    private String name;
    
    @Column(name = "index_type", nullable = false)
    private String indexType;
    
    @Column(name = "break_type", nullable = false)
    private String breakType;
    
    @Column(name = "reference_level")
    private BigDecimal referenceLevel;
    
    @Column(name = "stop_loss")
    private BigDecimal stopLoss;
    
    @Column(name = "break_price")
    private BigDecimal breakPrice;
    
    @Column(name = "current_price")
    private BigDecimal currentPrice;
    
    @Column(name = "break_date", nullable = false)
    private LocalDate breakDate;
    
    @Column(name = "lastExpiry_date", nullable = false)
    private LocalDate lastExpiryDate;
    
    @Column(name = "break_time")
    private LocalDateTime breakTime;
    
    @Column(name = "percent_move")
    private BigDecimal percentMove;
    
    @Column(name = "range_percent")
    private BigDecimal rangePercent;
    
    @Column(name = "status", length = 20)
    private String status;
    
    @Column(name = "exit_reason", length = 50)
    private String exitReason;
    
    @Column(name = "exit_price")
    private BigDecimal exitPrice;
    
    @Column(name = "exit_date")
    private LocalDateTime exitDate;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = "ACTIVE";
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}