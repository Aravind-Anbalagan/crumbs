package com.crumbs.trade.dto;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class IntradayDecision {

    private String symbol;

    private BigDecimal price;

    private BigDecimal supertrend;
    private String supertrendSignal;

    private BigDecimal vwap;
    private String vwapSignal;

    private String state;       // ALIGNED_BUY / ALIGNED_SELL / NOT_ALIGNED
    private String action;      // BUY / SELL / WAIT / EXIT
}