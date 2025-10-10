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

    public enum SourceType {OPEN, HIGH, LOW, CLOSE, HL2, OHLC4}

    // ✅ Get the value from candle based on source type
    private static BigDecimal getSourceValue(Candlestick candle, SourceType source) {
        switch (source) {
            case OPEN: return candle.getOpen();
            case HIGH: return candle.getHigh();
            case LOW: return candle.getLow();
            case CLOSE: return candle.getClose();
            case HL2: return candle.getHigh().add(candle.getLow())
                    .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
            case OHLC4: return candle.getOpen().add(candle.getHigh())
                    .add(candle.getLow()).add(candle.getClose())
                    .divide(BigDecimal.valueOf(4), 8, RoundingMode.HALF_UP);
            default: return candle.getClose();
        }
    }

    // ✅ Simple Moving Average
    public static List<BigDecimal> calculateSMA(List<BigDecimal> data, int period) {
        List<BigDecimal> sma = new ArrayList<>(Collections.nCopies(data.size(), null));
        if (data.size() < period) return sma;

        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < data.size(); i++) {
            BigDecimal val = data.get(i) != null ? data.get(i) : BigDecimal.ZERO;
            sum = sum.add(val);

            if (i >= period) {
                BigDecimal removeVal = data.get(i - period) != null ? data.get(i - period) : BigDecimal.ZERO;
                sum = sum.subtract(removeVal);
            }

            if (i >= period - 1) {
                sma.set(i, sum.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP));
            }
        }
        return sma;
    }

    // ✅ Shift by offset
    public static List<BigDecimal> applyOffset(List<BigDecimal> data, int offset) {
        List<BigDecimal> shifted = new ArrayList<>(Collections.nCopies(data.size(), null));
        int size = data.size();
        for (int i = 0; i < size; i++) {
            int shiftedIndex = i + offset;
            if (shiftedIndex >= 0 && shiftedIndex < size) {
                shifted.set(shiftedIndex, data.get(i));
            }
        }
        return shifted;
    }

    // ✅ Core logic: calculate signals using previous-day seeding
    public static List<Candlestick> calculateMovingAvgSignalsWithSeed(List<Candlestick> todayCandles,
                                                                      List<Candlestick> previousDayCandles,
                                                                      int length,
                                                                      int smoothingLength,
                                                                      SourceType source,
                                                                      int offset) {

        // Seed with previous day's last N candles
        int seedCount = Math.min(previousDayCandles.size(), length);
        List<Candlestick> seedCandles = previousDayCandles
                .subList(previousDayCandles.size() - seedCount, previousDayCandles.size());

        List<Candlestick> combined = new ArrayList<>(seedCandles);
        combined.addAll(todayCandles);

        // Compute base and smoothed MA
        List<BigDecimal> sourceValues = new ArrayList<>();
        for (Candlestick c : combined) {
            sourceValues.add(getSourceValue(c, source));
        }

        List<BigDecimal> sma = calculateSMA(sourceValues, length);
        List<BigDecimal> smoothedMA = (smoothingLength > 1)
                ? calculateSMA(sma, smoothingLength)
                : sma;
        smoothedMA = applyOffset(smoothedMA, offset);

        BigDecimal prevClose = null;
        BigDecimal prevMA = null;
        String currentTrend = null;

        for (int i = 0; i < combined.size(); i++) {
            Candlestick candle = combined.get(i);
            BigDecimal close = candle.getClose();
            BigDecimal maVal = i < smoothedMA.size() ? smoothedMA.get(i) : null;
            candle.setSmoothMA(maVal);

            if (prevClose != null && prevMA != null && maVal != null) {
                boolean wasBelow = prevClose.compareTo(prevMA) < 0;
                boolean wasAbove = prevClose.compareTo(prevMA) > 0;

                if (wasBelow && close.compareTo(maVal) > 0) {
                    currentTrend = "BUY";
                } else if (wasAbove && close.compareTo(maVal) < 0) {
                    currentTrend = "SELL";
                }
            }

            candle.setMasignal(currentTrend);
            prevClose = close;
            prevMA = maVal;
        }

        // ✅ Return only today's candles with early signals
        return combined.subList(seedCandles.size(), combined.size());
    }

    // ✅ Public method for early-start MA & signals
    public List<Candlestick> getMovingAvgWithPreviousDaySeed(List<Candlestick> todayCandles,
                                                             List<Candlestick> previousDayCandles) {
        return calculateMovingAvgSignalsWithSeed(todayCandles, previousDayCandles, 9, 9, SourceType.CLOSE, 0);
    }

    // ✅ Original method preserved
    public List<Candlestick> getMovingAverage(List<Candlestick> candles) {
        return calculateMovingAvgSignalsWithSeed(candles, new ArrayList<>(), 9, 9, SourceType.CLOSE, 0);
    }
}
