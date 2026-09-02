package com.crumbs.trade.dto;

import com.crumbs.trade.builder.OptionScannerConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScannedContractDto {

    // ==========================================
    // 1. Static Contract Master Data
    // ==========================================
    private String name;                            // e.g. "NIFTY", "RELIANCE"
    private String symbol;                          // e.g. "NIFTY08SEP2623600CE"
    private String token;                           // e.g. "42623"
    private String exchange;                        // e.g. "NFO", "BFO"
    private double strike;                          // Normalized strike, e.g. 23600.0
    private String optionType;                      // "CE" or "PE"
    private LocalDate expiryDate;                   // Parsed expiry date
    private String rawExpiry;                       // "08SEP2026"
    private boolean isMonthly;                      // true if Monthly, false if Weekly
    private OptionScannerConfig.Moneyness moneyness;// ATM, ITM, OTM
    private int lotsize;

    // ==========================================
    // 2. Dynamic Price & StochRSI Values
    // ==========================================
    private BigDecimal spotPrice;                   // Live underlying spot price
    private BigDecimal currentLtp;                  // Option premium LTP
    private Double currentStochRsi;                 // Latest 14-period Stochastic RSI
    private Double previousStochRsi;                // Previous cycle's StochRSI (for hook detection)

    // ==========================================
    // 3. Stateful Extreme Tracking (Your Fields)
    // ==========================================
    private boolean isRSIAbove80;                   // Actively in Overbought territory
    private boolean isRSIBelow20;                   // Actively in Oversold territory
    private LocalDateTime aboveRSI80At;             // Timestamp when it first crossed >= 80.0
    private LocalDateTime belowRSI20At;             // Timestamp when it first crossed <= 20.0
    private int aboveRSI80Count;                    // Consecutive check cycles stayed >= 80.0
    private int belowRSI20Count;                    // Consecutive check cycles stayed <= 20.0

    // ==========================================
    // 4. Recommended Engine Improvements
    // ==========================================
    private Double extremePeakRsi;                  // Highest StochRSI reached while above 80 (e.g. 96.5)
    private Double extremeTroughRsi;                // Lowest StochRSI reached while below 20 (e.g. 4.2)
    private LocalDateTime lastEvaluatedAt;          // Last cycle timestamp
    private SignalAction signalAction;              // REVERSAL_SELL_HOOK, REVERSAL_BUY_HOOK, TRACKING, NONE

    public enum SignalAction {
        NONE,
        TRACKING_OVERBOUGHT,
        TRACKING_OVERSOLD,
        TRIGGER_OVERBOUGHT_HOOK, // Reached >= 80, stayed, and now hooked down < 80 / < 70
        TRIGGER_OVERSOLD_HOOK    // Reached <= 20, stayed, and now hooked up > 20 / > 30
    }
}