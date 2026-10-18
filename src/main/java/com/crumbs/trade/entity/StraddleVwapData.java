package com.crumbs.trade.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "straddle_vwap_data")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StraddleVwapData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;      // e.g. "NIFTY"
    private double strike;      // e.g. 23600.0
    private String timeFrame;   // "ONE_MINUTE"

    @Column(name = "evaluated_at")
    private LocalDateTime evaluatedAt;

    @Column(name = "spot_price")
    private BigDecimal spotPrice;

    // --- CE Data ---
    @Column(name = "ce_ltp")
    private BigDecimal ceLtp;

    @Column(name = "ce_vwap")
    private BigDecimal ceVwap;

    // --- PE Data ---
    @Column(name = "pe_ltp")
    private BigDecimal peLtp;

    @Column(name = "pe_vwap")
    private BigDecimal peVwap;

    // --- Combined Data ---
    @Column(name = "combined_ltp")
    private BigDecimal combinedLtp;

    @Column(name = "combined_vwap")
    private BigDecimal combinedVwap;
}