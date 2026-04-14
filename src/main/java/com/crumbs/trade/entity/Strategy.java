package com.crumbs.trade.entity;

import java.math.BigDecimal;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "STRATEGY")
public class Strategy {

    @Id
    @Column(name = "id", nullable = false, unique = true)
    private Long id;

    @Column(name = "symbol")
    private String symbol;

    @Column(name = "symbol1")
    private String symbol1;

    @Column(name = "name")
    private String name;

    @Column(name = "active")
    private String active;

    @Column(name = "execute")
    private String execute;

    @Column(name = "exchange")
    private String exchange;

    @Column(name = "token")
    private String token;

    @Column(name = "tradingsymbol")
    private String tradingsymbol;

    @Column(name = "points")
    private int points;

    @Column(name = "candlestick")
    private int candlestick;

    @Column(name = "expiry")
    private String expiry;

    @Column(name = "live")
    private String live;

    @Column(name = "papertrade")
    private String papertrade;

    @Column(name = "dayCandle")
    private String dayCandle;

    @Column(name = "maxloss")
    private String maxloss;

    @Column(name = "alert", nullable = false, length = 1)
    private String alert = "N";
    
    @Column(name = "enable_logging")
    private String enableLogging = "N"; // Default to "N" (no logging)
    
    @Column(name = "target_points") // Replaces your static TARGET_POINTS
    private BigDecimal targetPoints;

    @Column(name = "max_entry_risk") // Replaces your static MAX_ENTRY_RISK
    private BigDecimal maxEntryRisk;
    
    @Column(name = "entry_hits_required")
    private int entryHitsRequired = 3;

    @Column(name = "exit_hits_required")
    private int exitHitsRequired = 3;
}
