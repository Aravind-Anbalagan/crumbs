package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import com.crumbs.trade.dto.Candlestick;
import org.springframework.stereotype.Service;

@Service
public class SuperTrendIndicator {

    private static final int DEFAULT_PERIOD = 10;
    private static final BigDecimal DEFAULT_MULTIPLIER = new BigDecimal("3");
    private static final int SCALE = 8; // Precision for calculations
    
    // Flat detection parameters
    private static final int FLAT_LOOKBACK = 5; // Number of candles to look back
    private static final BigDecimal FLAT_THRESHOLD_PERCENT = new BigDecimal("0.5"); // 0.5% change threshold

    /**
     * Calculate SuperTrend with default parameters (period=10, multiplier=3)
     */
    public List<Candlestick> calculateSuperTrend(List<Candlestick> candles) {
        return calculateSuperTrend(candles, DEFAULT_PERIOD, DEFAULT_MULTIPLIER);
    }

    /**
     * Calculate SuperTrend with custom parameters
     * Recommended: Pass 2 previous days + current day data for accurate intraday signals
     * 
     * @param candles List of candlesticks (should include historical data for warmup)
     * @param period ATR period (length) - typically 7-14
     * @param multiplier ATR multiplier (factor) - typically 2-4
     */
    public List<Candlestick> calculateSuperTrend(List<Candlestick> candles, int period, BigDecimal multiplier) {
        if (candles == null || candles.isEmpty()) {
            return new ArrayList<>();
        }
        
        if (candles.size() < period + 1) {
            // Not enough data - mark all as NA
            for (Candlestick c : candles) {
                c.setSuperTrend(null);
                c.setSuperTrendSignal("NA");
            }
            return candles;
        }

        List<BigDecimal> atr = calculateATR(candles, period);
        BigDecimal prevFinalUpperBand = null;
        BigDecimal prevFinalLowerBand = null;
        BigDecimal prevSuperTrend = null;
        Integer prevDirection = null; // +1 = uptrend, -1 = downtrend

        for (int i = 0; i < candles.size(); i++) {
            Candlestick c = candles.get(i);
            
            // Need ATR to be available
            if (i < period || atr.get(i) == null) {
                c.setSuperTrend(null);
                c.setSuperTrendSignal("NA");
                continue;
            }

            // Validate OHLC data
            if (c.getHigh() == null || c.getLow() == null || c.getClose() == null) {
                c.setSuperTrend(null);
                c.setSuperTrendSignal("NA");
                continue;
            }

            // Calculate HL/2 (median price)
            BigDecimal hl2 = c.getHigh().add(c.getLow())
                    .divide(BigDecimal.valueOf(2), SCALE, RoundingMode.HALF_UP);
            
            // Calculate basic bands
            BigDecimal atrValue = atr.get(i);
            BigDecimal upperBandBasic = hl2.add(multiplier.multiply(atrValue));
            BigDecimal lowerBandBasic = hl2.subtract(multiplier.multiply(atrValue));

            // Calculate final bands
            BigDecimal finalUpperBand;
            BigDecimal finalLowerBand;

            if (prevFinalUpperBand == null) {
                finalUpperBand = upperBandBasic;
            } else {
                // Use previous close for comparison (i-1)
                BigDecimal prevClose = candles.get(i - 1).getClose();
                if (upperBandBasic.compareTo(prevFinalUpperBand) < 0 || 
                    prevClose.compareTo(prevFinalUpperBand) > 0) {
                    finalUpperBand = upperBandBasic;
                } else {
                    finalUpperBand = prevFinalUpperBand;
                }
            }

            if (prevFinalLowerBand == null) {
                finalLowerBand = lowerBandBasic;
            } else {
                // Use previous close for comparison (i-1)
                BigDecimal prevClose = candles.get(i - 1).getClose();
                if (lowerBandBasic.compareTo(prevFinalLowerBand) > 0 || 
                    prevClose.compareTo(prevFinalLowerBand) < 0) {
                    finalLowerBand = lowerBandBasic;
                } else {
                    finalLowerBand = prevFinalLowerBand;
                }
            }

            // Determine direction and SuperTrend value
            int direction;
            BigDecimal superTrendValue;

            if (prevDirection == null) {
                // Initial direction: compare close to lower band (more reliable)
                // If close is above lower band, we're in uptrend
                if (c.getClose().compareTo(finalLowerBand) > 0) {
                    direction = 1; // Uptrend
                    superTrendValue = finalLowerBand;
                } else {
                    direction = -1; // Downtrend
                    superTrendValue = finalUpperBand;
                }
            } else {
                direction = prevDirection;
                
                // Check for trend change
                if (prevDirection == -1) {
                    // In downtrend, check if close crosses above upper band
                    if (c.getClose().compareTo(finalUpperBand) > 0) {
                        direction = 1; // Switch to uptrend
                    }
                } else {
                    // In uptrend, check if close crosses below lower band
                    if (c.getClose().compareTo(finalLowerBand) < 0) {
                        direction = -1; // Switch to downtrend
                    }
                }
                
                superTrendValue = (direction == 1) ? finalLowerBand : finalUpperBand;
            }

            // Store results
            c.setSuperTrend(superTrendValue);
            c.setSuperTrendSignal(direction == 1 ? "BUY" : "SELL");

            // Update previous values
            prevFinalUpperBand = finalUpperBand;
            prevFinalLowerBand = finalLowerBand;
            prevSuperTrend = superTrendValue;
            prevDirection = direction;
        }

        return candles;
    }

    /**
     * Calculate SuperTrend for intraday with historical warmup data
     * This method combines historical + current data, calculates SuperTrend, and returns only current day candles
     * 
     * @param historicalCandles Previous 2 days of candle data (for ATR warmup)
     * @param currentDayCandles Today's candles
     * @param period ATR period
     * @param multiplier ATR multiplier
     * @return Only today's candles with SuperTrend calculated
     */
    public List<Candlestick> calculateIntradaySuperTrend(
            List<Candlestick> historicalCandles,
            List<Candlestick> currentDayCandles,
            int period,
            BigDecimal multiplier) {
        
        if (historicalCandles == null || historicalCandles.isEmpty()) {
            throw new IllegalArgumentException("Historical candles required for warmup. Provide at least 2 days of data.");
        }
        
        if (currentDayCandles == null || currentDayCandles.isEmpty()) {
            throw new IllegalArgumentException("Current day candles cannot be empty.");
        }
        
        // Combine historical + current data
        List<Candlestick> allCandles = new ArrayList<>(historicalCandles);
        allCandles.addAll(currentDayCandles);
        
        // Validate minimum data requirement
        int minimumRequired = period * 2;
        if (allCandles.size() < minimumRequired) {
            throw new IllegalArgumentException(
                String.format("Need at least %d candles for accurate calculation. Provided: %d. Use 2 full days of data.",
                    minimumRequired, allCandles.size())
            );
        }
        
        // Calculate SuperTrend on all data
        calculateSuperTrend(allCandles, period, multiplier);
        
        // Return only current day candles with SuperTrend
        return new ArrayList<>(allCandles.subList(historicalCandles.size(), allCandles.size()));
    }
    
    /**
     * Calculate SuperTrend for intraday with default parameters
     */
    public List<Candlestick> calculateIntradaySuperTrend(
            List<Candlestick> historicalCandles,
            List<Candlestick> currentDayCandles) {
        return calculateIntradaySuperTrend(historicalCandles, currentDayCandles, 
                                          DEFAULT_PERIOD, DEFAULT_MULTIPLIER);
    }

    /**
     * Identify if the market is flat/sideways or trending
     * @param candles List of candlesticks with SuperTrend already calculated
     * @param lookback Number of candles to analyze (default: 5)
     * @param changeThresholdPercent Maximum % change to consider flat (default: 0.5%)
     * @return "FLAT", "TRENDING_UP", "TRENDING_DOWN", or "UNKNOWN"
     */
    public String identifyTrendState(List<Candlestick> candles, int lookback, BigDecimal changeThresholdPercent) {
        if (candles == null || candles.isEmpty()) {
            return "UNKNOWN";
        }
        
        // Get the most recent candle with valid SuperTrend
        Candlestick current = null;
        int currentIndex = -1;
        for (int i = candles.size() - 1; i >= 0; i--) {
            if (candles.get(i).getSuperTrend() != null) {
                current = candles.get(i);
                currentIndex = i;
                break;
            }
        }
        
        if (current == null || currentIndex < lookback) {
            return "UNKNOWN";
        }
        
        // Get SuperTrend value from lookback period
        Candlestick past = candles.get(currentIndex - lookback);
        if (past.getSuperTrend() == null) {
            return "UNKNOWN";
        }
        
        BigDecimal currentST = current.getSuperTrend();
        BigDecimal pastST = past.getSuperTrend();
        
        // Calculate percentage change in SuperTrend
        BigDecimal change = currentST.subtract(pastST);
        BigDecimal percentChange = change.divide(pastST, SCALE, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")).abs();
        
        // Count signal changes (trend flips) in lookback period
        int signalChanges = 0;
        String prevSignal = candles.get(currentIndex - lookback).getSuperTrendSignal();
        for (int i = currentIndex - lookback + 1; i <= currentIndex; i++) {
            String currSignal = candles.get(i).getSuperTrendSignal();
            if (!currSignal.equals(prevSignal) && !currSignal.equals("NA") && !prevSignal.equals("NA")) {
                signalChanges++;
            }
            prevSignal = currSignal;
        }
        
        // Determine state
        // Flat if: minimal price change OR multiple signal flips (choppy market)
        if (percentChange.compareTo(changeThresholdPercent) < 0 || signalChanges >= 2) {
            return "FLAT";
        }
        
        // Trending
        String currentSignal = current.getSuperTrendSignal();
        if ("BUY".equals(currentSignal)) {
            return "TRENDING_UP";
        } else if ("SELL".equals(currentSignal)) {
            return "TRENDING_DOWN";
        }
        
        return "UNKNOWN";
    }
    
    /**
     * Identify trend state with default parameters (lookback=5, threshold=0.5%)
     */
    public String identifyTrendState(List<Candlestick> candles) {
        return identifyTrendState(candles, FLAT_LOOKBACK, FLAT_THRESHOLD_PERCENT);
    }
    
    /**
     * Enhanced method: Calculate SuperTrend and add flat detection
     * @param candles List of candlesticks
     * @param period ATR period
     * @param multiplier ATR multiplier
     * @param detectFlat If true, adds "FLAT_" prefix to signal when market is sideways
     */
    public List<Candlestick> calculateSuperTrendWithFlatDetection(
            List<Candlestick> candles, int period, BigDecimal multiplier, boolean detectFlat) {
        
        List<Candlestick> result = calculateSuperTrend(candles, period, multiplier);
        
        if (!detectFlat || result.isEmpty()) {
            return result;
        }
        
        // Add flat detection to each candle (after sufficient history)
        for (int i = FLAT_LOOKBACK; i < result.size(); i++) {
            List<Candlestick> subList = result.subList(0, i + 1);
            String trendState = identifyTrendState(subList, FLAT_LOOKBACK, FLAT_THRESHOLD_PERCENT);
            
            Candlestick c = result.get(i);
            if ("FLAT".equals(trendState)) {
                String currentSignal = c.getSuperTrendSignal();
                if (currentSignal != null && !currentSignal.equals("NA")) {
                    c.setSuperTrendSignal("FLAT_" + currentSignal);
                }
            }
        }
        
        return result;
    }

    /**
     * Calculate market volatility using ATR
     * @param candles List of candlesticks
     * @param period ATR period
     * @return "LOW", "MEDIUM", "HIGH", or "UNKNOWN" volatility
     */
    public String calculateVolatility(List<Candlestick> candles, int period) {
        if (candles == null || candles.size() < period + 1) {
            return "UNKNOWN";
        }
        
        List<BigDecimal> atr = calculateATR(candles, period);
        BigDecimal currentATR = atr.get(atr.size() - 1);
        
        if (currentATR == null) {
            return "UNKNOWN";
        }
        
        // Get current price for comparison
        BigDecimal currentPrice = candles.get(candles.size() - 1).getClose();
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) == 0) {
            return "UNKNOWN";
        }
        
        // Calculate ATR as percentage of price
        BigDecimal atrPercent = currentATR.divide(currentPrice, SCALE, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
        
        // Classify volatility (thresholds for intraday trading)
        if (atrPercent.compareTo(new BigDecimal("1.0")) < 0) {
            return "LOW";
        } else if (atrPercent.compareTo(new BigDecimal("2.5")) < 0) {
            return "MEDIUM";
        } else {
            return "HIGH";
        }
    }

    /**
     * Calculate Average True Range (ATR)
     * Uses Wilder's smoothing method
     */
    private List<BigDecimal> calculateATR(List<Candlestick> candles, int period) {
        List<BigDecimal> atr = new ArrayList<>();
        BigDecimal prevATR = null;

        for (int i = 0; i < candles.size(); i++) {
            if (i == 0) {
                atr.add(null);
                continue;
            }

            Candlestick curr = candles.get(i);
            Candlestick prev = candles.get(i - 1);

            // Null checks for OHLC data
            if (curr.getHigh() == null || curr.getLow() == null || 
                curr.getClose() == null || prev.getClose() == null) {
                atr.add(null);
                continue;
            }

            // Calculate True Range
            BigDecimal tr1 = curr.getHigh().subtract(curr.getLow()).abs();
            BigDecimal tr2 = curr.getHigh().subtract(prev.getClose()).abs();
            BigDecimal tr3 = curr.getLow().subtract(prev.getClose()).abs();
            BigDecimal tr = tr1.max(tr2).max(tr3);

            if (i < period) {
                atr.add(null);
                continue;
            }

            if (i == period) {
                // Initial ATR = Simple Moving Average of True Range
                BigDecimal sum = BigDecimal.ZERO;
                for (int j = 1; j <= period; j++) {
                    Candlestick c = candles.get(j);
                    Candlestick p = candles.get(j - 1);
                    
                    // Null checks
                    if (c.getHigh() == null || c.getLow() == null || 
                        c.getClose() == null || p.getClose() == null) {
                        continue;
                    }
                    
                    BigDecimal t1 = c.getHigh().subtract(c.getLow()).abs();
                    BigDecimal t2 = c.getHigh().subtract(p.getClose()).abs();
                    BigDecimal t3 = c.getLow().subtract(p.getClose()).abs();
                    BigDecimal trueRange = t1.max(t2).max(t3);
                    
                    sum = sum.add(trueRange);
                }
                prevATR = sum.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
            } else {
                // Wilder's smoothing: ATR = ((prevATR * (period - 1)) + TR) / period
                prevATR = prevATR.multiply(BigDecimal.valueOf(period - 1))
                        .add(tr)
                        .divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
            }

            atr.add(prevATR);
        }
        
        return atr;
    }
    
    /**
     * Check if there was a signal change (crossover) in the last N candles
     * Useful for detecting recent trend reversals
     * 
     * @param candles List of candlesticks with SuperTrend calculated
     * @param lookback Number of candles to check (default 1 for immediate crossover)
     * @return true if signal changed within lookback period
     */
    public boolean hasRecentSignalChange(List<Candlestick> candles, int lookback) {
        if (candles == null || candles.size() < lookback + 1) {
            return false;
        }
        
        int size = candles.size();
        String currentSignal = candles.get(size - 1).getSuperTrendSignal();
        
        if (currentSignal == null || currentSignal.equals("NA")) {
            return false;
        }
        
        // Check for signal change in lookback period
        for (int i = size - 2; i >= Math.max(0, size - lookback - 1); i--) {
            String signal = candles.get(i).getSuperTrendSignal();
            if (signal != null && !signal.equals("NA") && !signal.equals(currentSignal)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Check for immediate signal change (last candle vs previous candle)
     */
    public boolean hasRecentSignalChange(List<Candlestick> candles) {
        return hasRecentSignalChange(candles, 1);
    }
}