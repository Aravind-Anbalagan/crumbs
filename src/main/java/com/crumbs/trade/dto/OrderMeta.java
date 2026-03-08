package com.crumbs.trade.dto;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class OrderMeta {

    private BigDecimal entryPrice;
    private BigDecimal slPrice;
    private BigDecimal first5High;
    private BigDecimal first5Low;
    private BigDecimal upperBand;
    private BigDecimal lowerBand;
    private BigDecimal pivot;
    private String     marketType;   // NORMAL | GAP_UP | GAP_DOWN
    private String     signal;       // BUY | SELL
    private String     entryTime;    // ISO datetime string
}