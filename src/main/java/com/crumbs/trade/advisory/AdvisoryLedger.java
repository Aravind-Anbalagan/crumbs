package com.crumbs.trade.advisory;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "advisory_ledger")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdvisoryLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // --- Core Identifiers ---
    private String symbol;

    @Column(name = "expiry_date")
    private String expiryDate;

    // 🚀 THE LAST UPDATE DATE (Updates every single day the cron runs)
    private LocalDateTime timestamp;

    // --- Technical State ---
    private String status; // ACTIVE or HISTORY

    @Column(name = "spot_price", precision = 10, scale = 2)
    private BigDecimal spotPrice;

    @Column(name = "daily_trend")
    private String dailyTrend;

    @Column(precision = 10, scale = 2)
    private BigDecimal atr14;

    @Column(name = "india_vix", precision = 10, scale = 2)
    private BigDecimal indiaVix;

    @Column(precision = 10, scale = 2)
    private BigDecimal pcr;

    // --- Samco Institutional Walls ---
    @Column(name = "put_wall_strike", precision = 10, scale = 2)
    private BigDecimal putWallStrike;

    // 🚀 CONVERTED TO BIGDECIMAL
    @Column(name = "put_wall_oi")
    private BigDecimal putWallOi;

    @Column(name = "call_wall_strike", precision = 10, scale = 2)
    private BigDecimal callWallStrike;

    // 🚀 CONVERTED TO BIGDECIMAL
    @Column(name = "call_wall_oi")
    private BigDecimal callWallOi;

    @Column(name = "max_pain_strike", precision = 10, scale = 2)
    private BigDecimal maxPainStrike;

    // --- 🚀 THE TRADE DATA (Carried over during MAINTAIN) ---
    @Column(name = "recommended_strike", precision = 10, scale = 2)
    private BigDecimal recommendedStrike;   // The specific strike we chose

    @Column(name = "option_type")
    private String optionType;              // CE or PE

    @Column(name = "entry_premium", precision = 10, scale = 2)
    private BigDecimal entryPremium;        // The price of the option when we entered

    @Column(name = "entry_delta", precision = 10, scale = 4)
    private BigDecimal entryDelta;          // The Delta risk when we entered

    @Column(name = "entry_iv", precision = 10, scale = 2)
    private BigDecimal entryIv;             // The Implied Volatility when we entered

    // 🚀 THE ORIGINAL ENTRY DATE (Stays exactly the same during MAINTAIN loops)
    @Column(name = "entry_date")
    private LocalDateTime entryDate;

    // --- Output & Reasoning ---
    @Column(name = "action_taken")
    private String actionTaken;

    @Column(length = 1000)
    private String reasoning;

    @Column(name = "smc_signal")
    private String smcSignal; // BREAKOUT or BREAKDOWN

    @Column(name = "exit_premium", precision = 10, scale = 2)
    private BigDecimal exitPremium;

    @Column(name = "realized_pnl", precision = 10, scale = 2)
    private BigDecimal realizedPnl;
}