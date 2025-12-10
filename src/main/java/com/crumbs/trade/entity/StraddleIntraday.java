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
        @Index(name = "idx_timestamp", columnList = "timestamp"),
        @Index(name = "idx_name_expiry_timestamp", columnList = "name, expiry, timestamp")
    }
)
@Data
public class StraddleIntraday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;       // NIFTY / BANKNIFTY / FINNIFTY
    private String expiry;     // e.g. 2025-12-25

    @Column(precision = 20, scale = 4)
    private BigDecimal strike;

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

    @Column(precision = 10, scale = 4)
    private BigDecimal combinedIV;

    @Column(precision = 20, scale = 4)
    private BigDecimal ceVwap;

    @Column(precision = 20, scale = 4)
    private BigDecimal peVwap;

    @Column(precision = 20, scale = 4)
    private BigDecimal combinedVwap;

    @Column(precision = 20, scale = 4)
    private BigDecimal intrinsic;

    @Column(precision = 20, scale = 4)
    private BigDecimal extrinsic;

}
