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
    private int        touches;
    private int        rejections;
    private int        breakouts;
    private boolean    volumeConfirmed;
    private int        candlesSinceLastTouch;
    private String     strength; // WEAK | MODERATE | STRONG | CRITICAL
}