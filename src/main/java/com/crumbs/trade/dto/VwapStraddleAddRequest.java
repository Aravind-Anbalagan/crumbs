package com.crumbs.trade.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class VwapStraddleAddRequest {
    private String symbol;          // e.g., "TCS"
    private BigDecimal strike;      // e.g., 3000
    private Integer entryHitTarget; // optional, defaults to 5
    private Integer exitHitTarget;  // optional, defaults to 5
    private String alertFlag;       // optional ("Y"/"N"), defaults to "Y"
}