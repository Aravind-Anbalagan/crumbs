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
    private static final int SCALE = 8;
    
    private static final int FLAT_LOOKBACK = 5;
    private static final BigDecimal FLAT_THRESHOLD_PERCENT = new BigDecimal("0.5");
    private static final BigDecimal GAP_THRESHOLD_PERCENT = new BigDecimal("2.0");

    // State holder for incremental calculations
    public static class SuperTrendState {
        public BigDecimal finalUpperBand;
        public BigDecimal finalLowerBand;
        public BigDecimal superTrend;
        public Integer direction; // +1 = uptrend, -1 = downtrend
        public BigDecimal atr;
        
        public SuperTrendState(BigDecimal finalUpperBand, BigDecimal finalLowerBand, 
                               BigDecimal superTrend, Integer direction, BigDecimal atr) {
            this.finalUpperBand = finalUpperBand;
            this.finalLowerBand = finalLowerBand;
            this.superTrend = superTrend;
            this.direction = direction;
            this.atr = atr;
        }
    }

    /**
     * Calculate SuperTrend with default parameters (period=10, multiplier=3)
     */
    public List<Candlestick> calculateSuperTrend(List<Candlestick> candles) {
        return calculateSuperTrend(candles, DEFAULT_PERIOD, DEFAULT_MULTIPLIER);
    }

    /**
     * INCREMENTAL: Calculate SuperTrend for only the LAST candle using previous state
     * Use this method when you're adding candles every 5 minutes
     * 
     * @param candles Full list of candles including the new one
     * @param period ATR period
     * @param multiplier ATR multiplier
     * @param previousState State from the previous calculation (pass null for first calculation)
     * @return Updated state after processing the last candle
     */
    public SuperTrendState calculateSuperTrendIncremental(
            List<Candlestick> candles, 
            int period, 
            BigDecimal multiplier,
            SuperTrendState previousState) {
        
        if (candles == null || candles.isEmpty()) {
            return null;
        }
        
        int lastIndex = candles.size() - 1;
        
        // If no previous state or not enough data, do full calculation
        if (previousState == null || candles.size() < period + 1) {
            calculateSuperTrend(candles, period, multiplier);
            Candlestick last = candles.get(lastIndex);
            
            if (last.getSuperTrend() == null) {
                return null;
            }
            
            // Extract state from the calculation
            BigDecimal atr = calculateATRForSingleCandle(candles, lastIndex, period, previousState);
            return new SuperTrendState(null, null, last.getSuperTrend(), 
                                       "BUY".equals(last.getSuperTrendSignal()) ? 1 : -1, atr);
        }
        
        // Incremental calculation for the last candle only
        Candlestick current = candles.get(lastIndex);
        Candlestick previous = candles.get(lastIndex - 1);
        
        // Validate data
        if (current.getHigh() == null || current.getLow() == null || 
            current.getClose() == null || current.getOpen() == null) {
            current.setSuperTrend(null);
            current.setSuperTrendSignal("NA");
            return previousState;
        }
        
        // Calculate ATR incrementally
        BigDecimal atr = calculateATRForSingleCandle(candles, lastIndex, period, previousState);
        if (atr == null) {
            current.setSuperTrend(null);
            current.setSuperTrendSignal("NA");
            return previousState;
        }
        
        // Detect gap
        boolean hasGap = false;
        BigDecimal gapSize = BigDecimal.ZERO;
        if (previous.getClose() != null) {
            gapSize = current.getOpen().subtract(previous.getClose());
            BigDecimal gapPercent = gapSize.divide(previous.getClose(), SCALE, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100")).abs();
            
            if (gapPercent.compareTo(GAP_THRESHOLD_PERCENT) > 0) {
                hasGap = true;
            }
        }
        
        // Calculate HL/2
        BigDecimal hl2 = current.getHigh().add(current.getLow())
                .divide(BigDecimal.valueOf(2), SCALE, RoundingMode.HALF_UP);
        
        // Calculate basic bands
        BigDecimal upperBandBasic = hl2.add(multiplier.multiply(atr));
        BigDecimal lowerBandBasic = hl2.subtract(multiplier.multiply(atr));
        
        // Calculate final bands using previous state
        BigDecimal finalUpperBand;
        BigDecimal finalLowerBand;
        
        if (previousState.finalUpperBand == null) {
            finalUpperBand = upperBandBasic;
        } else {
            if (upperBandBasic.compareTo(previousState.finalUpperBand) < 0 || 
                previous.getClose().compareTo(previousState.finalUpperBand) > 0) {
                finalUpperBand = upperBandBasic;
            } else {
                finalUpperBand = previousState.finalUpperBand;
            }
        }
        
        if (previousState.finalLowerBand == null) {
            finalLowerBand = lowerBandBasic;
        } else {
            if (lowerBandBasic.compareTo(previousState.finalLowerBand) > 0 || 
                previous.getClose().compareTo(previousState.finalLowerBand) < 0) {
                finalLowerBand = lowerBandBasic;
            } else {
                finalLowerBand = previousState.finalLowerBand;
            }
        }
        
        // Determine direction
        int direction = previousState.direction;
        
        // Handle gap - force trend change if gap breaks through SuperTrend
        if (hasGap && previousState.superTrend != null) {
            if (direction == -1) {
                // In downtrend, check if gap up breaks above SuperTrend
                if (current.getOpen().compareTo(previousState.superTrend) > 0 && 
                    gapSize.compareTo(BigDecimal.ZERO) > 0) {
                    direction = 1;
                }
            } else {
                // In uptrend, check if gap down breaks below SuperTrend
                if (current.getOpen().compareTo(previousState.superTrend) < 0 && 
                    gapSize.compareTo(BigDecimal.ZERO) < 0) {
                    direction = -1;
                }
            }
        }
        
        // Normal trend change logic (if not already changed by gap)
        if (direction == previousState.direction) {
            if (direction == -1) {
                if (current.getClose().compareTo(finalUpperBand) > 0) {
                    direction = 1;
                }
            } else {
                if (current.getClose().compareTo(finalLowerBand) < 0) {
                    direction = -1;
                }
            }
        }
        
        BigDecimal superTrendValue = (direction == 1) ? finalLowerBand : finalUpperBand;
        
        // Update the current candle
        current.setSuperTrend(superTrendValue);
        current.setSuperTrendSignal(direction == 1 ? "BUY" : "SELL");
        
        // Return updated state
        return new SuperTrendState(finalUpperBand, finalLowerBand, superTrendValue, direction, atr);
    }
    
    /**
     * INCREMENTAL with default parameters
     */
    public SuperTrendState calculateSuperTrendIncremental(
            List<Candlestick> candles, 
            SuperTrendState previousState) {
        return calculateSuperTrendIncremental(candles, DEFAULT_PERIOD, DEFAULT_MULTIPLIER, previousState);
    }
    
    /**
     * Calculate ATR for a single candle using previous ATR (Wilder's smoothing)
     */
    private BigDecimal calculateATRForSingleCandle(
            List<Candlestick> candles, 
            int index, 
            int period,
            SuperTrendState previousState) {
        
        if (index < 1) {
            return null;
        }
        
        Candlestick curr = candles.get(index);
        Candlestick prev = candles.get(index - 1);
        
        if (curr.getHigh() == null || curr.getLow() == null || 
            curr.getClose() == null || prev.getClose() == null) {
            return null;
        }
        
        // Calculate True Range
        BigDecimal tr1 = curr.getHigh().subtract(curr.getLow()).abs();
        BigDecimal tr2 = curr.getHigh().subtract(prev.getClose()).abs();
        BigDecimal tr3 = curr.getLow().subtract(prev.getClose()).abs();
        BigDecimal tr = tr1.max(tr2).max(tr3);
        
        if (index < period) {
            return null;
        }
        
        if (previousState != null && previousState.atr != null) {
            // Use previous ATR for Wilder's smoothing
            return previousState.atr.multiply(BigDecimal.valueOf(period - 1))
                    .add(tr)
                    .divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
        }
        
        // First time calculation - need to compute initial ATR
        if (index == period) {
            BigDecimal sum = BigDecimal.ZERO;
            for (int j = 1; j <= period; j++) {
                Candlestick c = candles.get(j);
                Candlestick p = candles.get(j - 1);
                
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
            return sum.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
        }
        
        return null;
    }

    /**
     * FULL CALCULATION: Use when you need to recalculate all candles
     * (e.g., initial load or when starting fresh)
     */
    public List<Candlestick> calculateSuperTrend(List<Candlestick> candles, int period, BigDecimal multiplier) {
        if (candles == null || candles.isEmpty()) {
            return new ArrayList<>();
        }
        
        if (candles.size() < period + 1) {
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
        Integer prevDirection = null;

        for (int i = 0; i < candles.size(); i++) {
            Candlestick c = candles.get(i);
            
            if (i < period || atr.get(i) == null) {
                c.setSuperTrend(null);
                c.setSuperTrendSignal("NA");
                continue;
            }

            if (c.getHigh() == null || c.getLow() == null || c.getClose() == null || c.getOpen() == null) {
                c.setSuperTrend(null);
                c.setSuperTrendSignal("NA");
                continue;
            }

            // Detect gap
            boolean hasGap = false;
            BigDecimal gapSize = BigDecimal.ZERO;
            if (i > 0 && candles.get(i - 1).getClose() != null) {
                BigDecimal prevClose = candles.get(i - 1).getClose();
                gapSize = c.getOpen().subtract(prevClose);
                BigDecimal gapPercent = gapSize.divide(prevClose, SCALE, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")).abs();
                
                if (gapPercent.compareTo(GAP_THRESHOLD_PERCENT) > 0) {
                    hasGap = true;
                }
            }

            BigDecimal hl2 = c.getHigh().add(c.getLow())
                    .divide(BigDecimal.valueOf(2), SCALE, RoundingMode.HALF_UP);
            
            BigDecimal atrValue = atr.get(i);
            BigDecimal upperBandBasic = hl2.add(multiplier.multiply(atrValue));
            BigDecimal lowerBandBasic = hl2.subtract(multiplier.multiply(atrValue));

            BigDecimal finalUpperBand;
            BigDecimal finalLowerBand;

            if (prevFinalUpperBand == null) {
                finalUpperBand = upperBandBasic;
            } else {
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
                BigDecimal prevClose = candles.get(i - 1).getClose();
                if (lowerBandBasic.compareTo(prevFinalLowerBand) > 0 || 
                    prevClose.compareTo(prevFinalLowerBand) < 0) {
                    finalLowerBand = lowerBandBasic;
                } else {
                    finalLowerBand = prevFinalLowerBand;
                }
            }

            int direction;
            BigDecimal superTrendValue;

            if (prevDirection == null) {
                if (c.getClose().compareTo(finalLowerBand) > 0) {
                    direction = 1;
                    superTrendValue = finalLowerBand;
                } else {
                    direction = -1;
                    superTrendValue = finalUpperBand;
                }
            } else {
                direction = prevDirection;
                
                if (hasGap && prevSuperTrend != null) {
                    if (prevDirection == -1) {
                        if (c.getOpen().compareTo(prevSuperTrend) > 0 && gapSize.compareTo(BigDecimal.ZERO) > 0) {
                            direction = 1;
                        }
                    } else {
                        if (c.getOpen().compareTo(prevSuperTrend) < 0 && gapSize.compareTo(BigDecimal.ZERO) < 0) {
                            direction = -1;
                        }
                    }
                }
                
                if (direction == prevDirection) {
                    if (prevDirection == -1) {
                        if (c.getClose().compareTo(finalUpperBand) > 0) {
                            direction = 1;
                        }
                    } else {
                        if (c.getClose().compareTo(finalLowerBand) < 0) {
                            direction = -1;
                        }
                    }
                }
                
                superTrendValue = (direction == 1) ? finalLowerBand : finalUpperBand;
            }

            c.setSuperTrend(superTrendValue);
            c.setSuperTrendSignal(direction == 1 ? "BUY" : "SELL");

            prevFinalUpperBand = finalUpperBand;
            prevFinalLowerBand = finalLowerBand;
            prevSuperTrend = superTrendValue;
            prevDirection = direction;
        }

        return candles;
    }

    public String detectGap(Candlestick current, Candlestick previous) {
        if (current == null || previous == null || 
            current.getOpen() == null || previous.getClose() == null) {
            return "NO_GAP";
        }
        
        BigDecimal prevClose = previous.getClose();
        BigDecimal gapSize = current.getOpen().subtract(prevClose);
        BigDecimal gapPercent = gapSize.divide(prevClose, SCALE, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
        
        if (gapPercent.abs().compareTo(GAP_THRESHOLD_PERCENT) > 0) {
            return gapPercent.compareTo(BigDecimal.ZERO) > 0 ? "GAP_UP" : "GAP_DOWN";
        }
        
        return "NO_GAP";
    }

    public List<Candlestick> calculateIntradaySuperTrend(
            List<Candlestick> historicalCandles,
            List<Candlestick> currentDayCandles,
            int period,
            BigDecimal multiplier) {
        
        if (historicalCandles == null || historicalCandles.isEmpty()) {
            throw new IllegalArgumentException("Historical candles required for warmup.");
        }
        
        if (currentDayCandles == null || currentDayCandles.isEmpty()) {
            throw new IllegalArgumentException("Current day candles cannot be empty.");
        }
        
        List<Candlestick> allCandles = new ArrayList<>(historicalCandles);
        allCandles.addAll(currentDayCandles);
        
        int minimumRequired = period * 2;
        if (allCandles.size() < minimumRequired) {
            throw new IllegalArgumentException(
                String.format("Need at least %d candles. Provided: %d.",
                    minimumRequired, allCandles.size())
            );
        }
        
        calculateSuperTrend(allCandles, period, multiplier);
        
        return new ArrayList<>(allCandles.subList(historicalCandles.size(), allCandles.size()));
    }
    
    public List<Candlestick> calculateIntradaySuperTrend(
            List<Candlestick> historicalCandles,
            List<Candlestick> currentDayCandles) {
        return calculateIntradaySuperTrend(historicalCandles, currentDayCandles, 
                                          DEFAULT_PERIOD, DEFAULT_MULTIPLIER);
    }

    public String identifyTrendState(List<Candlestick> candles, int lookback, BigDecimal changeThresholdPercent) {
        if (candles == null || candles.isEmpty()) {
            return "UNKNOWN";
        }
        
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
        
        Candlestick past = candles.get(currentIndex - lookback);
        if (past.getSuperTrend() == null) {
            return "UNKNOWN";
        }
        
        BigDecimal currentST = current.getSuperTrend();
        BigDecimal pastST = past.getSuperTrend();
        
        BigDecimal change = currentST.subtract(pastST);
        BigDecimal percentChange = change.divide(pastST, SCALE, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100")).abs();
        
        int signalChanges = 0;
        String prevSignal = candles.get(currentIndex - lookback).getSuperTrendSignal();
        for (int i = currentIndex - lookback + 1; i <= currentIndex; i++) {
            String currSignal = candles.get(i).getSuperTrendSignal();
            if (!currSignal.equals(prevSignal) && !currSignal.equals("NA") && !prevSignal.equals("NA")) {
                signalChanges++;
            }
            prevSignal = currSignal;
        }
        
        if (percentChange.compareTo(changeThresholdPercent) < 0 || signalChanges >= 2) {
            return "FLAT";
        }
        
        String currentSignal = current.getSuperTrendSignal();
        if ("BUY".equals(currentSignal)) {
            return "TRENDING_UP";
        } else if ("SELL".equals(currentSignal)) {
            return "TRENDING_DOWN";
        }
        
        return "UNKNOWN";
    }
    
    public String identifyTrendState(List<Candlestick> candles) {
        return identifyTrendState(candles, FLAT_LOOKBACK, FLAT_THRESHOLD_PERCENT);
    }
    
    public List<Candlestick> calculateSuperTrendWithFlatDetection(
            List<Candlestick> candles, int period, BigDecimal multiplier, boolean detectFlat) {
        
        List<Candlestick> result = calculateSuperTrend(candles, period, multiplier);
        
        if (!detectFlat || result.isEmpty()) {
            return result;
        }
        
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

    public String calculateVolatility(List<Candlestick> candles, int period) {
        if (candles == null || candles.size() < period + 1) {
            return "UNKNOWN";
        }
        
        List<BigDecimal> atr = calculateATR(candles, period);
        BigDecimal currentATR = atr.get(atr.size() - 1);
        
        if (currentATR == null) {
            return "UNKNOWN";
        }
        
        BigDecimal currentPrice = candles.get(candles.size() - 1).getClose();
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) == 0) {
            return "UNKNOWN";
        }
        
        BigDecimal atrPercent = currentATR.divide(currentPrice, SCALE, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
        
        if (atrPercent.compareTo(new BigDecimal("1.0")) < 0) {
            return "LOW";
        } else if (atrPercent.compareTo(new BigDecimal("2.5")) < 0) {
            return "MEDIUM";
        } else {
            return "HIGH";
        }
    }

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

            if (curr.getHigh() == null || curr.getLow() == null || 
                curr.getClose() == null || prev.getClose() == null) {
                atr.add(null);
                continue;
            }

            BigDecimal tr1 = curr.getHigh().subtract(curr.getLow()).abs();
            BigDecimal tr2 = curr.getHigh().subtract(prev.getClose()).abs();
            BigDecimal tr3 = curr.getLow().subtract(prev.getClose()).abs();
            BigDecimal tr = tr1.max(tr2).max(tr3);

            if (i < period) {
                atr.add(null);
                continue;
            }

            if (i == period) {
                BigDecimal sum = BigDecimal.ZERO;
                for (int j = 1; j <= period; j++) {
                    Candlestick c = candles.get(j);
                    Candlestick p = candles.get(j - 1);
                    
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
                prevATR = prevATR.multiply(BigDecimal.valueOf(period - 1))
                        .add(tr)
                        .divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
            }

            atr.add(prevATR);
        }
        
        return atr;
    }
    
    public boolean hasRecentSignalChange(List<Candlestick> candles, int lookback) {
        if (candles == null || candles.size() < lookback + 1) {
            return false;
        }
        
        int size = candles.size();
        String currentSignal = candles.get(size - 1).getSuperTrendSignal();
        
        if (currentSignal == null || currentSignal.equals("NA")) {
            return false;
        }
        
        for (int i = size - 2; i >= Math.max(0, size - lookback - 1); i--) {
            String signal = candles.get(i).getSuperTrendSignal();
            if (signal != null && !signal.equals("NA") && !signal.equals(currentSignal)) {
                return true;
            }
        }
        
        return false;
    }
    
    public boolean hasRecentSignalChange(List<Candlestick> candles) {
        return hasRecentSignalChange(candles, 1);
    }
}