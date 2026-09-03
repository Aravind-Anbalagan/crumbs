package com.crumbs.trade.utility;

import java.util.List;

public class MaCalculation {

    public static Double calculateSMA(List<Double> closes, int period) {
        if (closes == null || closes.size() < period) {
            return null;
        }

        double sum = 0;
        // Only loop through the most recent 'period' candles
        for (int i = closes.size() - period; i < closes.size(); i++) {
            sum += closes.get(i);
        }

        return sum / period;
    }
}