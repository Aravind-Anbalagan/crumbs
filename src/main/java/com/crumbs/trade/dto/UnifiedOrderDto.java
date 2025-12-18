package com.crumbs.trade.dto;


import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;

@Data
public class UnifiedOrderDto {

    private Long id;
    private String strategy;        // HEIKIN_PSAR / SR / CPR

    private String symbol;
    private String timeframe;       // only for SR, else null

    private String tradeType;       // BUY/SELL or type/name
    private String status;          // OPEN/CLOSED or derived

    private BigDecimal entryPrice;

    private LocalDateTime entryTime; 

    private BigDecimal exitPrice;
    
    private LocalDateTime exitTime;

    private BigDecimal targetPrice;
    private BigDecimal slPrice;
    private BigDecimal pnl;

    private String result;          // for ResultVix
    private Integer points;
    private Integer lotSize;

    private String method;
    private String strength;
    private String explanation;
    private String comment;
    private BigDecimal levelValue;      // ← add this
    private String exchange;
    private String token;
    private String signal;
    private String orderId;
    private Integer quantity;
 
}
