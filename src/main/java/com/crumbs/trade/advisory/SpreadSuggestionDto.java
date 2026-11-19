package com.crumbs.trade.advisory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpreadSuggestionDto {
    private String spreadType;       // "PE_SPREAD" or "CE_SPREAD"
    private String direction;        // "BULLISH" or "BEARISH"
    private String expiry;
    private Integer lotSize;
    private Leg sellLeg;
    private Leg buyLeg;
    private BigDecimal netCredit;    // Per-unit credit (Sell LTP - Buy LTP)
    private BigDecimal maxProfit;    // Net Credit * Lot Size
    private BigDecimal maxLoss;      // (Width - Net Credit) * Lot Size (Strictly <= 6000)
    private BigDecimal riskReward;   // maxProfit / maxLoss (higher is better)
    private BigDecimal breakeven;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Leg {
        private String action;       // "SELL" or "BUY"
        private String optionType;   // "PE" or "CE"
        private BigDecimal strike;
        private BigDecimal ltp;
        private double delta;
        private double iv;
    }
}