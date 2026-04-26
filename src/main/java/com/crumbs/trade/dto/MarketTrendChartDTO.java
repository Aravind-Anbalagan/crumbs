package com.crumbs.trade.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MarketTrendChartDTO {
    private String timestamp;
    private BigDecimal open;   // 🚀 Restored for Candlesticks
    private BigDecimal high;   // 🚀 Restored for Candlesticks
    private BigDecimal low;    // 🚀 Restored for Candlesticks
    private BigDecimal close;  // Acts as your Current Price
    private BigDecimal vwap;
    private BigDecimal fastEma;
    private BigDecimal slowEma;
    private String crossoverEvent; // 🎯 This is your EMA Execution Signal
    private String masignal; 
}