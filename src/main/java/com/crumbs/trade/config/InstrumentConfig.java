package com.crumbs.trade.config;



import java.time.LocalTime;

public record InstrumentConfig(
        String symbol,
        LocalTime start,
        LocalTime end,
        int cooldownMinutes
) {}
