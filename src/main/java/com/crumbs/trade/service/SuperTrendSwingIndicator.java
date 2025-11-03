package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import com.crumbs.trade.dto.Candlestick;

/**
 * SuperTrend Swing Indicator - Thread-safe implementation
 * 
 * Fixed issues:
 * - Added defensive copying for thread safety
 * - Improved null/zero validation
 * - Better edge case handling
 * - Clearer initialization logic
 */
@Service
public class SuperTrendSwingIndicator {

    private static final BigDecimal TWO = new BigDecimal("2");

    /**
     * Calculate SuperTrend indicator.
     * 
     * @param candles Input candlestick data
     * @param period ATR period (typically 10)
     * @param multiplier ATR multiplier (typically 3.0)
     * @return List of candlesticks with SuperTrend values
     */
    public List<Candlestick> calculateSuperTrend(List<Candlestick> candles, int period, BigDecimal multiplier) {
        if (candles == null || candles.isEmpty()) {
            return new ArrayList<>();
        }
        
        if (candles.size() < period) {
            // Return defensive copies without SuperTrend values
            List<Candlestick> result = new ArrayList<>();
            for (Candlestick original : candles) {
                result.add(new Candlestick(original));
            }
            return result;
        }

        // Validate inputs
        if (period <= 0) {
            throw new IllegalArgumentException("Period must be greater than 0");
        }
        if (multiplier == null || multiplier.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Multiplier must be greater than 0");
        }

        List<BigDecimal> trList = new ArrayList<>();
        List<BigDecimal> atrList = new ArrayList<>();

        // --- Step 1: Calculate TR (True Range) with validation ---
        for (int i = 0; i < candles.size(); i++) {
            Candlestick candle = candles.get(i);
            
            // Validate required fields
            if (candle.getHigh() == null || candle.getLow() == null || candle.getClose() == null) {
                trList.add(BigDecimal.ZERO);
                continue;
            }

            BigDecimal high = candle.getHigh();
            BigDecimal low = candle.getLow();
            BigDecimal closePrev = (i == 0) ? candle.getClose() : candles.get(i - 1).getClose();

            // Handle null previous close
            if (closePrev == null) {
                closePrev = candle.getClose();
            }

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
                // Simple average for first ATR
                atr = atr.add(trList.get(i));
                if (i == period - 1) {
                    atr = atr.divide(BigDecimal.valueOf(period), 6, RoundingMode.HALF_UP);
                    atrList.add(atr);
                } else {
                    atrList.add(BigDecimal.ZERO);
                }
            } else {
                // Wilder's smoothing formula: ATR = ((prevATR * (period-1)) + TR) / period
                BigDecimal prevATR = atrList.get(i - 1);
                atr = ((prevATR.multiply(BigDecimal.valueOf(period - 1)))
                        .add(trList.get(i)))
                        .divide(BigDecimal.valueOf(period), 6, RoundingMode.HALF_UP);
                atrList.add(atr);
            }
        }

        // --- Step 3: Calculate Basic Bands & SuperTrend with defensive copying ---
        List<Candlestick> result = new ArrayList<>();
        BigDecimal prevFinalUpperBand = BigDecimal.ZERO;
        BigDecimal prevFinalLowerBand = BigDecimal.ZERO;
        BigDecimal prevSuperTrend = BigDecimal.ZERO;

        for (int i = 0; i < candles.size(); i++) {
            // Create defensive copy
            Candlestick c = new Candlestick(candles.get(i));

            // Skip if required fields are null
            if (c.getHigh() == null || c.getLow() == null || c.getClose() == null) {
                c.setSuperTrend(BigDecimal.ZERO);
                c.setSuperTrendSignal("FLAT");
                result.add(c);
                continue;
            }

            BigDecimal hl2 = c.getHigh().add(c.getLow()).divide(TWO, 6, RoundingMode.HALF_UP);
            BigDecimal atrValue = atrList.get(i);

            // Skip if ATR is not yet calculated
            if (atrValue.compareTo(BigDecimal.ZERO) == 0) {
                c.setSuperTrend(BigDecimal.ZERO);
                c.setSuperTrendSignal("FLAT");
                result.add(c);
                continue;
            }

            BigDecimal basicUpperBand = hl2.add(multiplier.multiply(atrValue));
            BigDecimal basicLowerBand = hl2.subtract(multiplier.multiply(atrValue));

            BigDecimal finalUpperBand;
            BigDecimal finalLowerBand;

            if (i == 0 || prevFinalUpperBand.compareTo(BigDecimal.ZERO) == 0) {
                // First calculation
                finalUpperBand = basicUpperBand;
                finalLowerBand = basicLowerBand;
            } else {
                // Upper band: use basic if it's lower OR previous close crossed above
                finalUpperBand = (basicUpperBand.compareTo(prevFinalUpperBand) < 0
                        || candles.get(i - 1).getClose().compareTo(prevFinalUpperBand) > 0)
                                ? basicUpperBand
                                : prevFinalUpperBand;

                // Lower band: use basic if it's higher OR previous close crossed below
                finalLowerBand = (basicLowerBand.compareTo(prevFinalLowerBand) > 0
                        || candles.get(i - 1).getClose().compareTo(prevFinalLowerBand) < 0)
                                ? basicLowerBand
                                : prevFinalLowerBand;
            }

            // Determine SuperTrend and Signal
            BigDecimal superTrend;
            String signal;

            if (i == 0 || prevSuperTrend.compareTo(BigDecimal.ZERO) == 0) {
                // Initial trend: if HL2 is above upper band, trend is UP (use lower band)
                if (hl2.compareTo(finalUpperBand) > 0) {
                    superTrend = finalLowerBand;
                    signal = "BUY";
                } else {
                    superTrend = finalUpperBand;
                    signal = "SELL";
                }
            } else {
                // Continuation logic based on previous SuperTrend position
                if (prevSuperTrend.compareTo(prevFinalUpperBand) == 0) {
                    // Previous trend was DOWN (SuperTrend at upper band)
                    if (c.getClose().compareTo(finalUpperBand) > 0) {
                        // Close broke above upper band - switch to UP trend
                        superTrend = finalLowerBand;
                        signal = "BUY";
                    } else {
                        // Continue DOWN trend
                        superTrend = finalUpperBand;
                        signal = "SELL";
                    }
                } else {
                    // Previous trend was UP (SuperTrend at lower band)
                    if (c.getClose().compareTo(finalLowerBand) < 0) {
                        // Close broke below lower band - switch to DOWN trend
                        superTrend = finalUpperBand;
                        signal = "SELL";
                    } else {
                        // Continue UP trend
                        superTrend = finalLowerBand;
                        signal = "BUY";
                    }
                }
            }

            c.setSuperTrend(superTrend.setScale(2, RoundingMode.HALF_UP));
            c.setSuperTrendSignal(signal);
            result.add(c);

            // Update tracking vars for next iteration
            prevFinalUpperBand = finalUpperBand;
            prevFinalLowerBand = finalLowerBand;
            prevSuperTrend = superTrend;
        }

        return result;
    }

    /**
     * Calculate volatility as percentage of ATR over price.
     * 
     * @param candles Input candlestick data
     * @param period Lookback period
     * @return Volatility percentage
     */
    public BigDecimal calculateVolatility(List<Candlestick> candles, int period) {
        if (candles == null || candles.isEmpty() || period <= 0) {
            return BigDecimal.ZERO;
        }
        
        if (candles.size() < period) {
            return BigDecimal.ZERO;
        }

        BigDecimal avgATR = BigDecimal.ZERO;
        BigDecimal avgClose = BigDecimal.ZERO;
        int validCount = 0;

        int start = Math.max(0, candles.size() - period);
        for (int i = start; i < candles.size(); i++) {
            Candlestick candle = candles.get(i);
            
            // Skip invalid candles
            if (candle.getHigh() == null || candle.getLow() == null || candle.getClose() == null) {
                continue;
            }

            BigDecimal high = candle.getHigh();
            BigDecimal low = candle.getLow();
            BigDecimal tr = high.subtract(low).abs();
            
            avgATR = avgATR.add(tr);
            avgClose = avgClose.add(candle.getClose());
            validCount++;
        }

        if (validCount == 0 || avgClose.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        avgATR = avgATR.divide(BigDecimal.valueOf(validCount), 6, RoundingMode.HALF_UP);
        avgClose = avgClose.divide(BigDecimal.valueOf(validCount), 6, RoundingMode.HALF_UP);

        return avgATR.divide(avgClose, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    }
}