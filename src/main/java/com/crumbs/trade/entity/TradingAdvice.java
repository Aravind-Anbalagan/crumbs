package com.crumbs.trade.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.crumbs.trade.utility.AdviceStatus;
import com.crumbs.trade.utility.MarketDirection;
import com.crumbs.trade.utility.TradingMode;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "TRADING_ADVICE")
@Data
public class TradingAdvice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // --------------------
    // Instrument
    // --------------------
    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private LocalDate tradeDate;

    // --------------------
    // Decision
    // --------------------
    @Enumerated(EnumType.STRING)
    @Column(name = "RECOMMENDED_MODE", nullable = false)
    private TradingMode recommendedMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "DIRECTION", nullable = false)
    private MarketDirection direction;

    private BigDecimal strike; // optional, execution-time

    // --------------------
    // Entry snapshot
    // --------------------
    @Column(nullable = false)
    private LocalDateTime adviceTime;

    private int entryPressure;

    @Enumerated(EnumType.STRING)
    private com.crumbs.trade.utility.PressureZone entryZone;

    // Optional but STRONGLY recommended
    private BigDecimal entrySpot;
    private BigDecimal entryPremium;

    // --------------------
    // Lifecycle
    // --------------------
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdviceStatus status;

    private LocalDateTime exitTime;
    private String exitReason;
}
