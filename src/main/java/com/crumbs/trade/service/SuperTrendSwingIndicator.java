package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import com.crumbs.trade.dto.Candlestick;

@Service
public class SuperTrendSwingIndicator {

    private static final BigDecimal TWO = new BigDecimal("2");

    /**
     * Calculate SuperTrend indicator.
     */
    public List<Candlestick> calculateSuperTrend(List<Candlestick> candles, int period, BigDecimal multiplier) {
        if (candles == null || candles.size() < period) {
            return candles;
        }

        List<BigDecimal> trList = new ArrayList<>();
        List<BigDecimal> atrList = new ArrayList<>();

        // --- Step 1: Calculate TR (True Range) ---
        for (int i = 0; i < candles.size(); i++) {
            BigDecimal high = candles.get(i).getHigh();
            BigDecimal low = candles.get(i).getLow();
            BigDecimal closePrev = (i == 0) ? candles.get(i).getClose() : candles.get(i - 1).getClose();

            BigDecimal highLow = high.subtract(low).abs();
            BigDecimal highClose = high.subtract(closePrev).abs();
            BigDecimal lowClose = low.subtract(closePrev).abs();

            BigDecimal tr = highLow.max(highClose).max(lowClose);
            trList.add(tr);
        }

        // --- Step 2: Calculate ATR (Average True Range) ---
        BigDecimal atr = BigDecimal.ZERO;
        for (int i = 0; i < trList.size(); i++) {
            if (i < period) {
                // simple average for first ATR
                atr = atr.add(trList.get(i));
                if (i == period - 1) {
                    atr = atr.divide(BigDecimal.valueOf(period), 6, RoundingMode.HALF_UP);
                    atrList.add(atr);
                } else {
                    atrList.add(BigDecimal.ZERO);
                }
            } else {
                // Wilder’s smoothing formula
                BigDecimal prevATR = atrList.get(i - 1);
                atr = ((prevATR.multiply(BigDecimal.valueOf(period - 1)))
                        .add(trList.get(i)))
                        .divide(BigDecimal.valueOf(period), 6, RoundingMode.HALF_UP);
                atrList.add(atr);
            }
        }

        // --- Step 3: Calculate Basic Bands & SuperTrend ---
        BigDecimal prevFinalUpperBand = BigDecimal.ZERO;
        BigDecimal prevFinalLowerBand = BigDecimal.ZERO;
        BigDecimal prevSuperTrend = BigDecimal.ZERO;
        String prevSignal = "FLAT";

        for (int i = 0; i < candles.size(); i++) {
            Candlestick c = candles.get(i);

            BigDecimal hl2 = c.getHigh().add(c.getLow()).divide(TWO, 6, RoundingMode.HALF_UP);
            BigDecimal atrValue = atrList.get(i);

            BigDecimal basicUpperBand = hl2.add(multiplier.multiply(atrValue));
            BigDecimal basicLowerBand = hl2.subtract(multiplier.multiply(atrValue));

            BigDecimal finalUpperBand;
            BigDecimal finalLowerBand;

            if (i == 0) {
                finalUpperBand = basicUpperBand;
                finalLowerBand = basicLowerBand;
            } else {
                finalUpperBand = (basicUpperBand.compareTo(prevFinalUpperBand) < 0
                        || candles.get(i - 1).getClose().compareTo(prevFinalUpperBand) > 0)
                                ? basicUpperBand
                                : prevFinalUpperBand;

                finalLowerBand = (basicLowerBand.compareTo(prevFinalLowerBand) > 0
                        || candles.get(i - 1).getClose().compareTo(prevFinalLowerBand) < 0)
                                ? basicLowerBand
                                : prevFinalLowerBand;
            }

            BigDecimal superTrend;
            String signal;

            if (prevSuperTrend.compareTo(BigDecimal.ZERO) == 0) {
                // initial trend
                superTrend = hl2.compareTo(finalUpperBand) > 0 ? finalLowerBand : finalUpperBand;
                signal = hl2.compareTo(finalUpperBand) > 0 ? "BUY" : "SELL";
            } else {
                if (prevSuperTrend.compareTo(prevFinalUpperBand) == 0) {
                    if (c.getClose().compareTo(finalUpperBand) <= 0) {
                        superTrend = finalUpperBand;
                        signal = "SELL";
                    } else {
                        superTrend = finalLowerBand;
                        signal = "BUY";
                    }
                } else {
                    if (c.getClose().compareTo(finalLowerBand) >= 0) {
                        superTrend = finalLowerBand;
                        signal = "BUY";
                    } else {
                        superTrend = finalUpperBand;
                        signal = "SELL";
                    }
                }
            }

            c.setSuperTrend(superTrend.setScale(2, RoundingMode.HALF_UP));
            c.setSuperTrendSignal(signal);

            // Update tracking vars
            prevFinalUpperBand = finalUpperBand;
            prevFinalLowerBand = finalLowerBand;
            prevSuperTrend = superTrend;
            prevSignal = signal;
        }

        return candles;
    }

    /**
     * Calculate volatility as percentage of ATR over price.
     */
    public BigDecimal calculateVolatility(List<Candlestick> candles, int period) {
        if (candles == null || candles.size() < period) return BigDecimal.ZERO;

        BigDecimal avgATR = BigDecimal.ZERO;
        BigDecimal avgClose = BigDecimal.ZERO;

        int start = Math.max(0, candles.size() - period);
        for (int i = start; i < candles.size(); i++) {
            BigDecimal high = candles.get(i).getHigh();
            BigDecimal low = candles.get(i).getLow();
            BigDecimal tr = high.subtract(low).abs();
            avgATR = avgATR.add(tr);
            avgClose = avgClose.add(candles.get(i).getClose());
        }

        avgATR = avgATR.divide(BigDecimal.valueOf(period), 6, RoundingMode.HALF_UP);
        avgClose = avgClose.divide(BigDecimal.valueOf(period), 6, RoundingMode.HALF_UP);

        if (avgClose.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;

        return avgATR.divide(avgClose, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    }
}
