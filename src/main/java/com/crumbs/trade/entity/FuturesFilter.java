package com.crumbs.trade.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "FUTURES_FILTER")
@Data
public class FuturesFilter {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String name;
    
    private BigDecimal lastExpiryPrice;  // Close price (existing)
    
    // ✅ NEW: High and Low from expiry date
    private BigDecimal lastExpiryHigh;
    
    private BigDecimal lastExpiryLow;
    
    private BigDecimal lastTradedPrice;
    
    private BigDecimal percentMove;
    
    // ✅ NEW: Range percentage ((High - Low) / Low) * 100
    private BigDecimal rangePercent;
    
    private String direction;
    
    private String status;
    
    private LocalDate lastExpiryDate;
    
    private LocalDateTime lastTradedDate;
    
    private String indexType; // Track which index this belongs to
}