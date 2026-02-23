package com.crumbs.trade.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "pre_market_analysis")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PreMarketAnalysis {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String name;              // "NIFTY" / "CRUDEOIL"
    private String expiry;            // "14JAN26"
    private LocalDateTime timestamp;  // 2026-02-14 09:10:00
    private LocalDate tradingDate;    // 2026-02-14
    
    // ===== SELECTED ATM STRIKE =====
    private BigDecimal atmStrike;     // Strike with minimum diff
    private BigDecimal ltpDiff;       // |CE - PE| at 9:10
    
    // ===== 9:10 AM LTP (Post Pre-Market) =====
    private BigDecimal ceLtp;         // CE price at 9:10
    private BigDecimal peLtp;         // PE price at 9:10
    private BigDecimal midPoint;      // (CE + PE) / 2
    private BigDecimal secondMidPoint;
    
    // ===== PREVIOUS DAY DATA =====
    private BigDecimal cePrevHigh;    // Previous day CE high
    private BigDecimal cePrevLow;     // Previous day CE low
    private BigDecimal pePrevHigh;    // Previous day PE high
    private BigDecimal pePrevLow;     // Previous day PE low
    
    // ===== ADDITIONAL CONTEXT =====
    private BigDecimal combinedLtp;   // CE + PE (total premium)
    
    //Tokens
    private String ceToken;
    private String peToken;
}