package com.crumbs.trade.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;

@Data
public class FuturesConfigDto {

    private LocalDate expiryDate;      // maps to executionDate
    private BigDecimal movementPercent;
    private BigDecimal profitPercent;
    private BigDecimal lossPercent;
    private String useNiftyExpiry;
    private String active;
}
