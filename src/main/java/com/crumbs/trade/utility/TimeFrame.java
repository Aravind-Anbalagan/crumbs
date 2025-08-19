package com.crumbs.trade.utility;

import java.time.LocalDateTime;

public enum TimeFrame {
    ONE_MINUTE(15),
    FIVE_MINUTE(50),
    THIRTY_MINUTE(100),
    ONE_HOUR(200),
    ONE_DAY(1000);

    private final int bestDays;

    TimeFrame(int bestDays) {
        this.bestDays = bestDays;
    }

    public int getBestDays() {
        return bestDays;
    }

    public LocalDateTime calculateFromDate() {
        return LocalDateTime.now().minusDays(bestDays);
    }

    public LocalDateTime getToDate() {
        return LocalDateTime.now();
    }
}
