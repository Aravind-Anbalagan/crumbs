package com.crumbs.trade.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "option_prices")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptionPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String symbol;
    private String token;
    private String exchange;

    private double strike;
    private String optionType;
    private LocalDate expiryDate;
    private String moneyness;

    private BigDecimal spotPrice;
    private Double currentRsi;

    private boolean isRsiAbove80;
    private boolean isRsiBelow20;

    // ==========================================
    // AUDIT & TRACKING FIELDS
    // ==========================================
    @Column(name = "above_rsi80count")
    private Integer aboveRSI80Count;

    @Column(name = "below_rsi20count")
    private Integer belowRSI20Count;

    @Column(name = "above_rsi80at")
    private LocalDateTime aboveRSI80At;

    @Column(name = "below_rsi20at")
    private LocalDateTime belowRSI20At;
    @Column(name = "previous_rsi")
    private Double previousRsi;
    // Add this inside OptionPrice.java
    @Column(name = "time_frame")
    private String timeFrame;
    @Column(name = "ltp")
    private BigDecimal ltp;       // Option contract premium (e.g., 142.50)
    private Double extremePeakRsi;
    private Double extremeTroughRsi;
    private String signalAction;

    // Used specifically for finding the unique record for the day
    private LocalDate evaluatedDate;
    private LocalDateTime evaluatedAt;

    private Double currentMa;
    private boolean isPriceAboveMa;
}