package com.crumbs.trade.advisory;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "advisory_ledger")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdvisoryLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    private String expiryDate;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(nullable = false)
    private String status; // "ACTIVE" or "HISTORY"

    // --- Market Snapshot ---
    private BigDecimal spotPrice;
    private String dailyTrend;
    private BigDecimal atr14;
    private BigDecimal indiaVix;
    private BigDecimal pcr;

    // --- Samco Walls ---
    private BigDecimal putWallStrike;
    private Long putWallOi;
    private BigDecimal callWallStrike;
    private Long callWallOi;
    private BigDecimal maxPainStrike;

    // --- Recommended Trade Execution ---
    private BigDecimal recommendedStrike;
    private String optionType; // "PE" or "CE"
    private BigDecimal entryPremium;
    private Double entryDelta;
    private Double entryIv;

    // --- Decision State ---
    @Column(nullable = false)
    private String actionTaken;

    @Column(length = 1000)
    private String reasoning;
    
 // Inside AdvisoryLedger.java
    @Column(name = "smc_signal")
    private String smcSignal; // Will store "BREAKOUT" or "BREAKDOWN"
    
    
}