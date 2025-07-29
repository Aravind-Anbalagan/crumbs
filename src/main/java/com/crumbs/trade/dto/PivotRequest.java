package com.crumbs.trade.dto;



import lombok.Data;

import java.math.BigDecimal;

@Data
public class PivotRequest {
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private String method; // "standard", "fibonacci", or "camarilla"
}

