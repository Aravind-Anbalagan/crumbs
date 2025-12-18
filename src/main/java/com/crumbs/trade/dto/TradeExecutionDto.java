package com.crumbs.trade.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;

@Data
public class TradeExecutionDto {

    private Long id;
    private String symbol;
    private String timeframe;

    private String tradeType;   // BUY / SELL
    private String status;      // OPEN / CLOSED

    private BigDecimal entryPrice;
    private LocalDateTime entryTime;

    private BigDecimal exitPrice;
    private LocalDateTime exitTime;
    private BigDecimal pnl;

    private String method;      // PRICE_ACTION / FIBO
    private String strength;

    private String exitReason;
    private String explanation;


}
