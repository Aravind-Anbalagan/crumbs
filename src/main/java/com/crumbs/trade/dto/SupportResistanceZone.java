package com.crumbs.trade.dto;



import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupportResistanceZone {
    private BigDecimal level;         // The price level
    private int touches;              // Number of times price touched this zone
    private String lastTouchedDate;   // Timestamp of last touch (assumed format from PricesIndex.getTimestamp())
}
