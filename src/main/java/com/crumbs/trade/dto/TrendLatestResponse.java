package com.crumbs.trade.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TrendLatestResponse(
        String name,
        String expiry,
        LocalDate tradeDate,
        BigDecimal strikeStep,
        int strikeRadius,
        int totalStrikes,
        String consolidatedMessage,
        TrendPointDto latest,
        List<StrikeBreakdownDto> strikeBreakdown
) {
    public static TrendLatestResponse from(TrendChartResponse full) {
        return new TrendLatestResponse(
                full.name(),
                full.expiry(),
                full.tradeDate(),
                full.strikeStep(),
                full.strikeRadius(),
                full.totalStrikes(),
                full.consolidatedMessage(),
                full.latest(),
                full.strikeBreakdown()
        );
    }
}