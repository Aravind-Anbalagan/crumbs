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
    private boolean volumeConfirmed;
    private int lastTouchAge;
 // Convenience constructor (defaults volumeConfirmed to false)
    public SupportResistanceZone(BigDecimal level, int touches, String lastTouchedDate,int lastTouchAge) {
        this.level = level;
        this.touches = touches;
        this.lastTouchedDate = lastTouchedDate;
        this.volumeConfirmed = false;
        this.lastTouchAge =lastTouchAge;
    }
}
