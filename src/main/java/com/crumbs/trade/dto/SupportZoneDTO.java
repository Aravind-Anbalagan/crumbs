package com.crumbs.trade.dto;

import java.math.BigDecimal;

import lombok.Data;

@Data
public class SupportZoneDTO {
    private BigDecimal level;
    private int touches;
    private boolean volumeConfirmed;
}