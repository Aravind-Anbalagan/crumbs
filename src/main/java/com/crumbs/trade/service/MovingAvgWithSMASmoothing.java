package com.crumbs.trade.service;

import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.crumbs.trade.dto.Candlestick;

@Data

@Service
public class MovingAvgWithSMASmoothing {

    public enum SourceType {OPEN, HIGH, LOW, CLOSE, HL2, OHLC4}

    private static BigDecimal getSourceValue(Candlestick candle, SourceType source) {
        switch (source) {
            case OPEN:
                return candle.getOpen();
            case HIGH:
                return candle.getHigh();
            case LOW:
                return candle.getLow();
            case CLOSE:
                return candle.getClose();
            case HL2:
                return candle.getHigh().add(candle.getLow()).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
            case OHLC4:
                return candle.getOpen().add(candle.getHigh()).add(candle.getLow()).add(candle.getClose())
                        .divide(BigDecimal.valueOf(4), 8, RoundingMode.HALF_UP);
            default:
                return candle.getClose();
        }
    }

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

    public static List<Candlestick> calculateMovingAvgSignals(List<Candlestick> candles, int length, int smoothingLength, SourceType source, int offset) {
        List<BigDecimal> sourceValues = new ArrayList<>();
        for (Candlestick candle : candles) {
            sourceValues.add(getSourceValue(candle, source));
        }

        List<BigDecimal> sma = calculateSMA(sourceValues, length);

        List<BigDecimal> smoothedMA = smoothingLength > 1 ? calculateSMA(sma, smoothingLength) : sma;

        smoothedMA = applyOffset(smoothedMA, offset);

        BigDecimal prevClose = null;
        BigDecimal prevMA = null;

        for (int i = 0; i < candles.size(); i++) {
            Candlestick candle = candles.get(i);
            BigDecimal close = candle.getClose();
            BigDecimal maVal = i < smoothedMA.size() ? smoothedMA.get(i) : null;

            candle.setSmoothMA(maVal);

            String signal = "NEUTRAL";
            if (prevClose != null && prevMA != null && maVal != null) {
                boolean wasBelow = prevClose.compareTo(prevMA) < 0;
                boolean wasAbove = prevClose.compareTo(prevMA) > 0;
                boolean isAbove = close.compareTo(maVal) > 0;
                boolean isBelow = close.compareTo(maVal) < 0;

                if (wasBelow && isAbove) {
                    signal = "BUY";
                } else if (wasAbove && isBelow) {
                    signal = "SELL";
                }
            }
            candle.setMasignal(signal);

            prevClose = close;
            prevMA = maVal;
        }
        return candles;
    }

	public List<Candlestick> getMovingAverage(List<Candlestick> candles) {

		List<Candlestick> processedCandles = calculateMovingAvgSignals(candles, 9, 9, SourceType.CLOSE, 0);

		System.out.println("Index | Date       |   Close   |  SmoothMA  | Signal");
		System.out.println("------------------------------------------------------");
		for (int i = 0; i < processedCandles.size(); i++) {
			Candlestick c = processedCandles.get(i);
			System.out.printf("%5d | %tF | %9s | %9s | %6s\n", i, null,
					c.getClose().setScale(5, RoundingMode.HALF_UP).toPlainString(),
					c.getSmoothMA() != null ? c.getSmoothMA().setScale(5, RoundingMode.HALF_UP).toPlainString() : "N/A",
					c.getSignal());
		}
		return processedCandles;
	}
}
