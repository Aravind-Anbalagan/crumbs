package com.crumbs.trade.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor  // ← This generates ALL fields constructor
public class SupportResistanceZone {

    private BigDecimal level;
    private int        touches;
    private int        reacted;
    private int        broken;
    private String     lastTouchedDate;
    private boolean    volumeConfirmed;
    private int        lastTouchAge;

    // ✅ ADD THIS: Full constructor matching service call
    public SupportResistanceZone(BigDecimal level, int touches, String lastTouchedDate, 
                                int lastTouchAge, int reacted, int broken, boolean volumeConfirmed) {
        this.level = level;
        this.touches = touches;
        this.lastTouchedDate = lastTouchedDate;
        this.lastTouchAge = lastTouchAge;
        this.reacted = reacted;
        this.broken = broken;
        this.volumeConfirmed = volumeConfirmed;
    }

    // Your existing backwards-compatible constructor stays
    public SupportResistanceZone(BigDecimal level, int touches,
                                  String lastTouchedDate, int lastTouchAge) {
        this.level = level;
        this.touches = touches;
        this.reacted = 0;
        this.broken = 0;
        this.lastTouchedDate = lastTouchedDate;
        this.volumeConfirmed = false;
        this.lastTouchAge = lastTouchAge;
    }
}
