package com.crumbs.trade.dto;

import java.math.BigDecimal;

public record StrikeBreakdownDto(
        BigDecimal strike,
        String zone,          // SUPPORT, ATM, RESISTANCE
        BigDecimal cePrice,
        BigDecimal ceVwap,
        BigDecimal pePrice,
        BigDecimal peVwap,
        String priceSignal,   // BEARISH, BULLISH, DECAY, VOL_SPIKE
        BigDecimal ceOi,
        BigDecimal peOi,
        BigDecimal oiDiff,    // ceOi - peOi
        BigDecimal ceOiChange,// intraday change from session open
        BigDecimal peOiChange // intraday change from session open
) {}