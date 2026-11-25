package com.crumbs.trade.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VwapStraddleTickDto {
    private String time;             // "HH:mm" or ISO timestamp for Lightweight Charts
    private long epochSeconds;       // UNIX seconds for Lightweight Charts time axis
    private BigDecimal combinedPrice;
    private BigDecimal combinedVwap;
    private String signalEvent;      // null | "ENTRY" | "EXIT"
}