package com.crumbs.trade.service;

import lombok.Data;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;
import com.crumbs.trade.dto.Candlestick;

@Data
@Service
public class MovingAvgWithSMASmoothing {

    // =========================================================
    // ⚙️ STRATEGY CONFIGURATION (EASY TWEAK PANEL)
    // =========================================================
    
    // Core Momentum Lengths
    private int fastEmaLength = 9;  //Short
    private int slowEmaLength = 26; //Long
    
    // Data Source (OPEN, HIGH, LOW, CLOSE, HL2, OHLC4)
    private SourceType defaultSource = SourceType.CLOSE;
    
    // Mathematical Precision
    private int calculationScale = 8;
    private RoundingMode defaultRounding = RoundingMode.HALF_UP;

    // =========================================================

    public enum SourceType {OPEN, HIGH, LOW, CLOSE, HL2, OHLC4}

    // ✅ Get the value from candle based on source type
    private BigDecimal getSourceValue(Candlestick candle, SourceType source) {
        switch (source) {
            case OPEN: return candle.getOpen();
            case HIGH: return candle.getHigh();
            case LOW: return candle.getLow();
            case CLOSE: return candle.getClose();
            case HL2: return candle.getHigh().add(candle.getLow())
                    .divide(BigDecimal.valueOf(2), calculationScale, defaultRounding);
            case OHLC4: return candle.getOpen().add(candle.getHigh())
                    .add(candle.getLow()).add(candle.getClose())
                    .divide(BigDecimal.valueOf(4), calculationScale, defaultRounding);
            default: return candle.getClose();
        }
    }

    // ✅ Exponential Moving Average (EMA) Calculation
    public List<BigDecimal> calculateEMA(List<BigDecimal> data, int period) {
        List<BigDecimal> ema = new ArrayList<>(Collections.nCopies(data.size(), null));
        if (data.size() < period) return ema;

        BigDecimal k = BigDecimal.valueOf(2.0).divide(BigDecimal.valueOf(period + 1), calculationScale, defaultRounding);
        BigDecimal oneMinusK = BigDecimal.ONE.subtract(k);

        // Seed the first EMA value using a Simple Average
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            BigDecimal val = data.get(i) != null ? data.get(i) : BigDecimal.ZERO;
            sum = sum.add(val);
        }
        BigDecimal previousEma = sum.divide(BigDecimal.valueOf(period), calculationScale, defaultRounding);
        ema.set(period - 1, previousEma);

        // Calculate EMA for the rest of the dataset
        for (int i = period; i < data.size(); i++) {
            BigDecimal currentPrice = data.get(i) != null ? data.get(i) : previousEma;
            
            BigDecimal currentEma = currentPrice.multiply(k)
                    .add(previousEma.multiply(oneMinusK))
                    .setScale(calculationScale, defaultRounding);
            
            ema.set(i, currentEma);
            previousEma = currentEma;
        }
        return ema;
    }

    // ✅ Core logic: Dual EMA Crossover with STRICT State-Change Tracking
    public List<Candlestick> calculateDualEmaCrossover(List<Candlestick> todayCandles,
                                                       List<Candlestick> previousDayCandles) {

        int seedCount = Math.min(previousDayCandles.size(), slowEmaLength * 2); 
        List<Candlestick> seedCandles = previousDayCandles
                .subList(previousDayCandles.size() - seedCount, previousDayCandles.size());

        List<Candlestick> combined = new ArrayList<>(seedCandles);
        combined.addAll(todayCandles);

        List<BigDecimal> sourceValues = new ArrayList<>();
        for (Candlestick c : combined) {
            sourceValues.add(getSourceValue(c, defaultSource));
        }

        List<BigDecimal> fastEMA = calculateEMA(sourceValues, fastEmaLength);
        List<BigDecimal> slowEMA = calculateEMA(sourceValues, slowEmaLength);

        // 🚀 State Tracking Variables (REPLACES THE FLAWED wasBelow/wasAbove LOGIC)
        String currentTrend = "NONE";
        String lastConfirmedTrend = "NONE";

        // Iterate through and find crossovers
        for (int i = 0; i < combined.size(); i++) {
            Candlestick candle = combined.get(i);
            
            BigDecimal currFast = fastEMA.get(i);
            BigDecimal currSlow = slowEMA.get(i);
            
            String triggerEvent = "NONE";

            if (currFast != null && currSlow != null) {
                // 1. Determine who is strictly on top right now
                if (currFast.compareTo(currSlow) > 0) {
                    currentTrend = "BUY";
                } else if (currFast.compareTo(currSlow) < 0) {
                    currentTrend = "SELL";
                }

                // 2. ONLY fire a crossover if the trend state actually changed from the last candle
                if (!currentTrend.equals(lastConfirmedTrend) && !lastConfirmedTrend.equals("NONE")) {
                    triggerEvent = currentTrend + "_CROSS"; 
                }
                
                // 3. Save the state for the next loop iteration
                lastConfirmedTrend = currentTrend;
            }

            // Save both the continuous trend and the exact one-time trigger event
            candle.setMasignal(currentTrend);
            candle.setCrossoverEvent(triggerEvent);
            candle.setFastEma(currFast);
            candle.setSlowEma(currSlow);
        }

        return combined.subList(seedCandles.size(), combined.size());
    }

    // ✅ Public method to trigger the execution
    public List<Candlestick> getMovingAvgWithPreviousDaySeed(List<Candlestick> todayCandles,
                                                             List<Candlestick> previousDayCandles) {
        return calculateDualEmaCrossover(todayCandles, previousDayCandles);
    }
}