package com.crumbs.trade.dto;


import java.math.BigDecimal;
import com.crumbs.trade.entity.Level;
import lombok.Data;

@Data
public class LevelAnalysisResult {

    private BigDecimal currentPrice;

    private String zone;      // BUY_ZONE / SELL_ZONE / NO_TRADE_ZONE
    private String bias;      // BULLISH / BEARISH / NEUTRAL

    private Level nearestSupport;
    private Level nearestResistance;

    private BigDecimal supportDistance;
    private BigDecimal resistanceDistance;

    private String explanation;
}
