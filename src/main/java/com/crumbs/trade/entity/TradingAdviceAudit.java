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

@Entity
@Table(name = "trading_advice_audit")
@Data
public class TradingAdviceAudit {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String symbol;
    private LocalDate tradeDate;
    private Long adviceId;
    private String advisedMode;
    private LocalDateTime adviceTime;
    private LocalDateTime exitTime;
    
    private int entryPressure;
    private int maxPressureAfterEntry;
    
    // =====================================================
    // NEW FIELDS - Add these for enhanced audit analysis
    // =====================================================
    
    @Column(name = "min_pressure_after_entry")
    private Integer minPressureAfterEntry;
    
    private BigDecimal maxSpotMoveAgainst;
    
    @Column(name = "max_favorable_move")
    private BigDecimal maxFavorableMove;
    
    @Column(name = "final_spot_at_exit")
    private BigDecimal finalSpotAtExit;
    
    private boolean adviceSurvived;
    private boolean exitWasTimely;
    
    @Column(name = "exit_quality", length = 50)
    private String exitQuality;
    
    private String auditConclusion;
}