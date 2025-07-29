package com.crumbs.trade.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class VolumeByDate {
    private String date;
    private BigDecimal volume;
}