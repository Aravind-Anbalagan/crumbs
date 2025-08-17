package com.crumbs.trade.dto;

import lombok.Data;

@Data
public class StrategyDTO {
    private Long id;
    private String symbol;
    private String symbol1;
    private String name;
    private String active;
    private String execute;
    private String exchange;
    private String token;
    private String tradingsymbol;
    private int points;
    private int candlestick;
    private String expiry;
    private String live;
    private String papertrade;
    private String dayCandle;
}
