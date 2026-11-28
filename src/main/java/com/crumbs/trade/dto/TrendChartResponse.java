package com.crumbs.trade.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TrendChartResponse(
        String name,
        String expiry,
        LocalDate tradeDate,
        BigDecimal strikeStep,
        int strikeRadius,
        int totalStrikes,
        double confirmMinutes,
        String consolidatedMessage,
        TrendPointDto latest,
        List<TrendPointDto> timeline,
        List<StrikeBreakdownDto> strikeBreakdown
) {}