package com.crumbs.trade.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CombinedChartPoint {
    private LocalDateTime time;
    private BigDecimal ce;
    private BigDecimal pe;
    private BigDecimal spot;
}
