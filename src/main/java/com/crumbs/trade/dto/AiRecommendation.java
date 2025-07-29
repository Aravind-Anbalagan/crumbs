package com.crumbs.trade.dto;



import lombok.Data;

import java.math.BigDecimal;

@Data
public class AiRecommendation {
    private String recommendation; // BUY, SELL, HOLD
    private String confidence;     // LOW, MEDIUM, HIGH
    private BigDecimal stopLoss;
    private BigDecimal target;
    private String reason;
}
