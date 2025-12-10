package com.crumbs.trade.dto;

import java.math.BigDecimal;

import lombok.Data;

@Data
public class OptionData {
    private BigDecimal ltp;
    private BigDecimal iv;
    private BigDecimal vwap;
}