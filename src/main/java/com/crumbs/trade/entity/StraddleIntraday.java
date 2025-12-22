package com.crumbs.trade.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(
    name = "straddle_intraday",
    indexes = {
        @Index(name = "idx_name_expiry_strike", columnList = "name, expiry, strike"),
        @Index(name = "idx_timestamp", columnList = "trade_timestamp"),
        @Index(name = "idx_name_expiry_timestamp", columnList = "name, expiry, trade_timestamp")
    }
)
@Data
public class StraddleIntraday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;       // NIFTY / BANKNIFTY / FINNIFTY

    private String expiry;     // e.g., 2025-12-25

    @Column(precision = 20, scale = 4)
    private BigDecimal strike;

    @Column(name = "trade_timestamp")
    private LocalDateTime timestamp;

    @Column(precision = 20, scale = 4)
    private BigDecimal spot;

    @Column(precision = 20, scale = 4)
    private BigDecimal cePrice;

    @Column(precision = 20, scale = 4)
    private BigDecimal pePrice;

    @Column(precision = 10, scale = 4)
    private BigDecimal ceIV;

    @Column(precision = 10, scale = 4)
    private BigDecimal peIV;

    // 🔥 Combined Premium (CE + PE)
    @Column(precision = 20, scale = 4)
    private BigDecimal combinedPremium;

    @Column(precision = 20, scale = 4)
    private BigDecimal ceVwap;

    @Column(precision = 20, scale = 4)
    private BigDecimal peVwap;

    @Column(precision = 20, scale = 4)
    private BigDecimal combinedVwap;

    @Column(precision = 20, scale = 4)
    private BigDecimal ceVolume;

    @Column(precision = 20, scale = 4)
    private BigDecimal peVolume;
    
    // --- CE ---
    @Column(precision = 20, scale = 4)
    private BigDecimal ceIntrinsic;

    @Column(precision = 20, scale = 4)
    private BigDecimal ceExtrinsic;

    // --- PE ---
    @Column(precision = 20, scale = 4)
    private BigDecimal peIntrinsic;

    @Column(precision = 20, scale = 4)
    private BigDecimal peExtrinsic;

    // 🔥 CE Open price
    @Column(precision = 20, scale = 4)
    private BigDecimal ceOpenPrice;

    // 🔥 PE Open price
    @Column(precision = 20, scale = 4)
    private BigDecimal peOpenPrice;

    // 🔥 Combined Open price (ceOpen + peOpen)
    @Column(precision = 20, scale = 4)
    private BigDecimal combinedOpenPrice;
    
    @Column(precision = 20, scale = 4)
    private BigDecimal avgPrice;
}
