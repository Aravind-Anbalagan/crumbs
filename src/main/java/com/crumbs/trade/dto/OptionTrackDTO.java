package com.crumbs.trade.dto;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class OptionTrackDTO {
    private String time;             // Timestamp string
    private BigDecimal oi;           // Current OI
    private BigDecimal ltp;          // Current LTP
    private BigDecimal volume;       // Current volume
    private BigDecimal oiChange;     // Change in OI from BASE
    private BigDecimal ltpChange;    // Change in LTP from BASE
    private BigDecimal ltpPercentChange; // Percent change in LTP from BASE
    private BigDecimal volumeChange; // Change in volume from BASE
}
