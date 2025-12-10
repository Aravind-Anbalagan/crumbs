package com.crumbs.trade.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
public class NameExpiryStrikeGroupedDto {
    private String name;
    private Map<String, List<BigDecimal>> expiries;  // expiry → list of strikes
    private BigDecimal atmStrike;
}