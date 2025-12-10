package com.crumbs.trade.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;

@Data
public class ChartPoint {
    private LocalDateTime time;
    private BigDecimal value;

    public ChartPoint(LocalDateTime time, BigDecimal value) {
        this.time = time;
        this.value = value;
    }
}
