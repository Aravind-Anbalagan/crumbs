package com.crumbs.trade.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TrendPointDto(
        LocalDateTime timestamp,
        long epochSeconds,          // Ready for Lightweight Charts time axis
        String timeLabel,           // "16:27"
        BigDecimal spot,
        BigDecimal atmStrike,
        BigDecimal syntheticVwap,   // 5/7-strike options-implied VWAP
        TrendState confirmedState,
        TrendState pendingState,    // Non-null while building toward a flip
        double pendingMinutes,      // e.g. 5.0 of 8.0 mins
        boolean stateChanged,       // True only on confirmed flip candles
        String transitionLabel,     // "BEARISH STARTED" / "BULLISH ENDED -> (SIDEWAYS)"
        VolRegime volRegime,
        double atmCombDiffPct,      // ATM combinedPremium vs combinedVwap %
        int alignedStrikes,         // e.g. 7
        int totalStrikes,           // e.g. 7
        String priceSignal,         // BEARISH / BULLISH / DECAY / SPLIT
        String oiWallSignal,        // BEARISH / BULLISH / BALANCED
        String oiFlowSignal,        // BEARISH / BULLISH / NEUTRAL
        BigDecimal peWall,          // Sum(PE - CE) below ATM
        BigDecimal ceWall,          // Sum(CE - PE) above ATM
        BigDecimal atmCePrice,      // For TelegramService Alert logging
        BigDecimal atmPePrice,
        BigDecimal atmCeVwap,
        BigDecimal atmPeVwap,
        String consolidatedMessage  // Banner message displayed on chart
) {}