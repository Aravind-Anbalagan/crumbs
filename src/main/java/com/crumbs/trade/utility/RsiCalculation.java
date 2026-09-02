package com.crumbs.trade.utility;

import java.util.List;

public class RsiCalculation {

    /**
     * Calculates the Standard RSI exactly as TradingView does,
     * using Wilder's Smoothing / Running Moving Average (RMA).
     *
     * @param closes List of historical close prices (oldest to newest)
     * @param period Typically 14
     * @return The latest RSI value
     */
    public static Double calculate(List<Double> closes, int period) {
        if (closes == null || closes.size() <= period) return null;

        double sumGain = 0;
        double sumLoss = 0;

        // 1. Initial SMA for the first 'period'
        for (int i = 1; i <= period; i++) {
            double diff = closes.get(i) - closes.get(i - 1);
            if (diff >= 0) sumGain += diff;
            else sumLoss -= (diff);
        }

        double avgGain = sumGain / period;
        double avgLoss = sumLoss / period;

        // 2. Wilder's Smoothing (RMA) for all subsequent periods (This matches TradingView)
        for (int i = period + 1; i < closes.size(); i++) {
            double diff = closes.get(i) - closes.get(i - 1);
            double gain = Math.max(0, diff);
            double loss = Math.max(0, -diff);

            // RMA Formula: (Previous Average * (Period - 1) + Current Value) / Period
            avgGain = ((avgGain * (period - 1)) + gain) / period;
            avgLoss = ((avgLoss * (period - 1)) + loss) / period;
        }

        // Prevent division by zero if there are no losses
        if (avgLoss == 0) return 100.0;

        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }
}