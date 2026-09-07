package com.crumbs.trade.utility;

import java.util.List;

public class RsiCalculation {

    /**
     * Calculates TradingView-accurate RSI using Wilder's Smoothing (RMA).
     * Requires at least 150+ historical candles for accurate warm-up.
     */
    public static Double calculate(List<Double> closes, int period) {
        if (closes == null || closes.size() <= period) {
            return null;
        }

        double sumGain = 0;
        double sumLoss = 0;

        // Step 1: Calculate initial Average Gain/Loss using Simple Moving Average (SMA)
        for (int i = 1; i <= period; i++) {
            double change = closes.get(i) - closes.get(i - 1);
            if (change > 0) {
                sumGain += change;
            } else {
                sumLoss -= change; // Make loss positive
            }
        }

        double avgGain = sumGain / period;
        double avgLoss = sumLoss / period;

        // Step 2: Calculate subsequent Average Gain/Loss using Wilder's Smoothing (RMA)
        for (int i = period + 1; i < closes.size(); i++) {
            double change = closes.get(i) - closes.get(i - 1);
            double currentGain = Math.max(0, change);
            double currentLoss = Math.max(0, -change); // Keep loss positive

            // RMA Formula: (PrevAvg * (Period - 1) + Current) / Period
            avgGain = ((avgGain * (period - 1)) + currentGain) / period;
            avgLoss = ((avgLoss * (period - 1)) + currentLoss) / period;
        }

        // Handle edge case where there is no loss (straight vertical move)
        if (avgLoss == 0) {
            return 100.0;
        }

        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }
}