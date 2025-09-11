package com.crumbs.trade.dto;

import java.math.BigDecimal;

import lombok.Data;

@Data
public class CandleDTO {
    private long time;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private BigDecimal volume;
}