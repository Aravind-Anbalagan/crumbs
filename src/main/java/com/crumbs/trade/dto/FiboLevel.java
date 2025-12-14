package com.crumbs.trade.dto;

import java.math.BigDecimal;

import lombok.Data;

@Data
public class FiboLevel {

    private BigDecimal level;
    private String label;
    private String strength;
    private Integer touches;
}
