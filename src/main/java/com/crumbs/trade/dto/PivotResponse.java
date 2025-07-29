package com.crumbs.trade.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PivotResponse {
    private BigDecimal pivot;
    private BigDecimal r1;
    private BigDecimal r2;
    private BigDecimal r3;
    private BigDecimal s1;
    private BigDecimal s2;
    private BigDecimal s3;
    private BigDecimal tc; // Top Central (CPR)
    private BigDecimal bc; // Bottom Central (CPR)

    // Optional constructor without tc/bc
    public PivotResponse(BigDecimal pivot, BigDecimal r1, BigDecimal r2, BigDecimal r3,
                         BigDecimal s1, BigDecimal s2, BigDecimal s3) {
        this.pivot = pivot;
        this.r1 = r1;
        this.r2 = r2;
        this.r3 = r3;
        this.s1 = s1;
        this.s2 = s2;
        this.s3 = s3;
    }
}
