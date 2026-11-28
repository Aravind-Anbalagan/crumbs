package com.crumbs.trade.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record InstrumentTrendDto(
        String name,
        String expiry,
        BigDecimal strikeStep,
        int strikeRadius,
        int totalStrikes,
        BigDecimal latestSpot,
        TrendState latestState,
        String consolidatedMessage,
        LocalDateTime lastUpdated
) {}