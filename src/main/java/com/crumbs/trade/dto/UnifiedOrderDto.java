package com.crumbs.trade.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class UnifiedOrderDto {
    private Long id;               // Numeric Unique ID
    private String strategyName;   // SR, CPR, or HEIKIN_PSAR
    private String symbol;         
    private String instrumentType; 
    private String strike;         
    private String strategyType;   // BUYER / SELLER
    private Integer quantity;      
    
    private String entryTime;      
    private String exitTime;       
    private BigDecimal entryPrice;
    private BigDecimal exitPrice;
    private BigDecimal points;     
    private BigDecimal pnl;        
    private String status;         
}