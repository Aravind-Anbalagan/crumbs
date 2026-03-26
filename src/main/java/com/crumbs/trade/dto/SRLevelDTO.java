package com.crumbs.trade.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

/**
 * A detected Support or Resistance zone.
 * "type" and "rank" are omitted — implied by which list this appears in and its index position.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SRLevelDTO {
    private BigDecimal price;
    private int        visited;
    // ADD these four:
    private int rejection;   // resistance only — how many times sellers won
    private int breakout;    // resistance only — how many times sellers got stopped out
    private int bounce;      // support only   — how many times buyers won
    private int breakdown;   // support only   — how many times buyers got stopped out
    private boolean    heavyVolume;
    private String     lastVisited;   // "2 hours ago (10:30)"
    private String     confidence;    // UNTESTED | LOW | MODERATE | HIGH | ABSOLUTE
}