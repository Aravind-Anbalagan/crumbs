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
        @Index(name = "idx_name_expiry_timestamp", columnList = "name, expiry, trade_timestamp"),
        @Index(name = "idx_signal_primary", columnList = "signal_primary"), // 🔥 NEW: Query by signal type
        @Index(name = "idx_signal_timestamp", columnList = "signal_primary, trade_timestamp") // 🔥 NEW: Signal history
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
    
    @Column(precision = 20, scale = 4)
    private BigDecimal combinedIv;
    
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
    
    @Column(name = "ce_oi", precision = 20, scale = 0)
    private BigDecimal ceOi;
    
    @Column(name = "pe_oi", precision = 20, scale = 0)
    private BigDecimal peOi;
    
    // Crossover detection flags
    private Boolean ceCrossoverAbove;
    private Boolean peCrossoverAbove;
    
   
    
    @Column(name = "ce_prev_high", precision = 10, scale = 2)
    private BigDecimal cePrevHigh;
    
    @Column(name = "ce_prev_low", precision = 10, scale = 2)
    private BigDecimal cePrevLow;
    
    @Column(name = "pe_prev_high", precision = 10, scale = 2)
    private BigDecimal pePrevHigh;
    
    @Column(name = "pe_prev_low", precision = 10, scale = 2)
    private BigDecimal pePrevLow;
    
 // Add these fields to the entity class
    @Column(name = "ce_prev_close", precision = 10, scale = 2)
    private BigDecimal cePrevClose;

    @Column(name = "pe_prev_close", precision = 10, scale = 2)
    private BigDecimal pePrevClose;

    @Column(name = "combined_prev_close", precision = 10, scale = 2)
    private BigDecimal combinedPrevClose;
    
    @Column(precision = 20, scale = 4)
    private BigDecimal ceLtpChange;

    @Column(precision = 20, scale = 4)
    private BigDecimal peLtpChange;

    @Column(precision = 20, scale = 4)
    private BigDecimal ceOiChange;

    @Column(precision = 20, scale = 4)
    private BigDecimal peOiChange;

    private String marketType;
    private String dominance;
}