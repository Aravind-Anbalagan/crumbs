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
    
    // ========================================
    // 🔥 NEW: TRADING SIGNAL FIELDS
    // ========================================
    
    /**
     * Primary trading signal
     * Values: STRADDLE_BUY_SETUP, STRADDLE_SELL_SETUP, PREMIUM_DECAY, 
     *         PREMIUM_SURGE, BULLISH_MOVE, BEARISH_MOVE, RANGE_BOUND, NEUTRAL
     */
    @Column(name = "signal_primary", length = 50)
    private String signalPrimary;
    
    /**
     * Secondary context signal (optional)
     * Values: HIGH_EXTRINSIC, LOW_EXTRINSIC, CE_VOLUME_SPIKE, PE_VOLUME_SPIKE, 
     *         HIGH_OI_STRIKE, ATM_STRIKE
     */
    @Column(name = "signal_secondary", length = 50)
    private String signalSecondary;
    
    /**
     * Signal strength/confidence (1 = weak, 5 = strong)
     */
    @Column(name = "signal_strength")
    private Integer signalStrength;
    
    /**
     * Is this strike near ATM? (within ±50 points)
     */
    @Column(name = "is_atm")
    private Boolean isAtm;
    
    /**
     * CE price % change from open
     * Positive = CE getting expensive, Negative = CE decaying
     */
    @Column(name = "ce_change_pct", precision = 8, scale = 2)
    private BigDecimal ceChangePct;
    
    /**
     * PE price % change from open
     * Positive = PE getting expensive, Negative = PE decaying
     */
    @Column(name = "pe_change_pct", precision = 8, scale = 2)
    private BigDecimal peChangePct;
    
    /**
     * Combined premium % change from open
     * Positive = Straddle getting expensive, Negative = Straddle decaying
     */
    @Column(name = "combined_change_pct", precision = 8, scale = 2)
    private BigDecimal combinedChangePct;
    
    /**
     * Extrinsic value as % of total premium
     * High = Lots of time value (good to sell)
     * Low = Mostly intrinsic (avoid selling)
     */
    @Column(name = "extrinsic_ratio", precision = 5, scale = 2)
    private BigDecimal extrinsicRatio;
    
    /**
     * Market directional bias
     * Values: BULLISH, BEARISH, NEUTRAL
     */
    @Column(name = "directional_bias", length = 20)
    private String directionalBias;
    
    /**
     * Volume imbalance indicator
     * Positive = CE volume higher, Negative = PE volume higher
     */
    @Column(name = "volume_ratio", precision = 6, scale = 2)
    private BigDecimal volumeRatio;
    
    /**
     * OI imbalance indicator  
     * Positive = CE OI higher, Negative = PE OI higher
     */
    @Column(name = "oi_ratio", precision = 6, scale = 2)
    private BigDecimal oiRatio;
    
    @Column(name = "ce_prev_high", precision = 10, scale = 2)
    private BigDecimal cePrevHigh;
    
    @Column(name = "ce_prev_low", precision = 10, scale = 2)
    private BigDecimal cePrevLow;
    
    @Column(name = "pe_prev_high", precision = 10, scale = 2)
    private BigDecimal pePrevHigh;
    
    @Column(name = "pe_prev_low", precision = 10, scale = 2)
    private BigDecimal pePrevLow;
}