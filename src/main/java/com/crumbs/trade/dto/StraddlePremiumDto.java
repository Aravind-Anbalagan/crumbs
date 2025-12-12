package com.crumbs.trade.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;

@Data
public class StraddlePremiumDto {

    private LocalDateTime timestamp;     // Time for chart

    private BigDecimal cePrice;          // CE LTP
    private BigDecimal pePrice;          // PE LTP

    private Token peToken;
    private Token ceToken;

    private BigDecimal strikePrice;

    private BigDecimal combinedPremium;  // CE + PE

    private BigDecimal ceIv;
    private BigDecimal peIv;
    private BigDecimal combinedIv;

    private BigDecimal ceVwap;
    private BigDecimal peVwap;

    private BigDecimal intrinsicValue;   // CallIntrinsic + PutIntrinsic
    private BigDecimal extrinsicValue;   // CE Extrinsic + PE Extrinsic

    // 🔥 Added Open Prices
    private BigDecimal ceOpenPrice;      // CE open price at 9:15
    private BigDecimal peOpenPrice;      // PE open price at 9:15
    private BigDecimal combinedOpenPrice; // CE open + PE open
}
