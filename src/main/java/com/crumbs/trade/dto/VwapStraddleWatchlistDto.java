package com.crumbs.trade.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
public class VwapStraddleWatchlistDto {
    private Long id;
    private LocalDate tradeDate;
    private String symbol;
    private LocalDate expiry;
    private BigDecimal strike;
    private String ceToken;
    private String peToken;
    private Integer entryHitTarget;
    private Integer exitHitTarget;
    private Integer currentHits;
    private String status;
    private BigDecimal entryCp;
    private String alertFlag;
    private Boolean isActive;

    // Enriched from in-memory cache (not stored in DB)
    private BigDecimal liveCp;
    private BigDecimal liveCv;
    private String lastTickTime;
}