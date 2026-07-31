package com.crumbs.trade.advisory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptionRecommendation {
    private String symbol;
    private BigDecimal spotPrice;
    private String dailyTrend;
    private String status; 
    private String action; 
    private BigDecimal recommendedStrike;
    private BigDecimal putOiWallStrike;
    private BigDecimal callOiWallStrike;
    private BigDecimal atr14;
    private String reasoning;
    private LocalDateTime timestamp;
    private String smcSignal;
 // 🚀 ADD THESE THREE FIELDS
    private BigDecimal entryPremium;
    private BigDecimal entryDelta;
    private BigDecimal entryIv;
    private BigDecimal unrealizedPnl;
    private BigDecimal realizedPnl;
}