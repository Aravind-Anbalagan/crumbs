package com.crumbs.trade.advisory;

public enum CandleTimeFrame {
    ONE_DAY("ONE_DAY", 30),       // Macro Trend & Volatility (ATR)
    ONE_HOUR("ONE_HOUR", 10);     // Intermediate Structure & Entry Timing

    private final String apiValue;
    private final int daysToFetch;

    CandleTimeFrame(String apiValue, int daysToFetch) {
        this.apiValue = apiValue;
        this.daysToFetch = daysToFetch;
    }

    public String getApiValue() { return apiValue; }
    public int getDaysToFetch() { return daysToFetch; }
}