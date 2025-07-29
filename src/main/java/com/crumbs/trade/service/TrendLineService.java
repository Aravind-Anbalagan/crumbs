package com.crumbs.trade.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TrendLineService {

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class OHLCV {
        private LocalDateTime time;
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private BigDecimal volume;
    }

    public enum SwingType { HIGH, LOW }

    @Data
    @AllArgsConstructor
    public static class SwingPoint {
        private int index;
        private BigDecimal price;
        private SwingType type;
    }

    @Data
    @AllArgsConstructor
    public static class TrendLine {
        private int index1;
        private int index2;
        private BigDecimal price1;
        private BigDecimal price2;

        public BigDecimal getPriceAt(int index) {
            if (index2 == index1) return price1;
            BigDecimal slope = price2.subtract(price1)
                .divide(BigDecimal.valueOf(index2 - index1), 4, RoundingMode.HALF_UP);
            return price1.add(slope.multiply(BigDecimal.valueOf(index - index1)));
        }

        public BigDecimal getSlope() {
            if (index2 == index1) return BigDecimal.ZERO;
            return price2.subtract(price1)
                    .divide(BigDecimal.valueOf(index2 - index1), 4, RoundingMode.HALF_UP);
        }
    }

    @Data
    @NoArgsConstructor
    public static class TrendAnalysisResult {
        private BigDecimal trendSupport;
        private BigDecimal trendResistance;
        private BigDecimal zigzagSupport;
        private BigDecimal zigzagResistance;
        private BigDecimal currentPrice;
        private BigDecimal currentVolume;
        private BigDecimal avgVolume;
        private String trendSignal;
        private String zigzagSignal;
        private String trendDirection;
        private String zigzagDirection;
    }

    public List<SwingPoint> findSwingPoints(List<OHLCV> data, int window) {
        List<SwingPoint> swingPoints = new ArrayList<>();
        for (int i = window; i < data.size() - window; i++) {
            OHLCV current = data.get(i);
            BigDecimal high = current.getHigh();
            BigDecimal low = current.getLow();
            boolean isHigh = true, isLow = true;

            for (int j = i - window; j <= i + window; j++) {
                if (data.get(j).getHigh().compareTo(high) > 0) isHigh = false;
                if (data.get(j).getLow().compareTo(low) < 0) isLow = false;
            }

            if (isHigh && (swingPoints.isEmpty() || high.subtract(swingPoints.get(swingPoints.size() - 1).getPrice()).abs().compareTo(BigDecimal.valueOf(0.5)) > 0))
                swingPoints.add(new SwingPoint(i, high, SwingType.HIGH));
            if (isLow && (swingPoints.isEmpty() || low.subtract(swingPoints.get(swingPoints.size() - 1).getPrice()).abs().compareTo(BigDecimal.valueOf(0.5)) > 0))
                swingPoints.add(new SwingPoint(i, low, SwingType.LOW));
        }
        return swingPoints;
    }

    public TrendAnalysisResult analyzeTrend(List<OHLCV> data, String interval) {
        TrendAnalysisResult result = new TrendAnalysisResult();
        if (data.size() < 10) {
            result.setTrendSignal("Insufficient data.");
            return result;
        }

        int window = switch (interval) {
            case "1m", "5m" -> 2;
            case "15m", "30m" -> 3;
            case "1h" -> 4;
            default -> 5;
        };

        List<SwingPoint> swings = findSwingPoints(data, window);
        List<SwingPoint> lows = swings.stream().filter(s -> s.getType() == SwingType.LOW).collect(Collectors.toList());
        List<SwingPoint> highs = swings.stream().filter(s -> s.getType() == SwingType.HIGH).collect(Collectors.toList());

        if (lows.size() < 2 || highs.size() < 2) {
            result.setTrendSignal("Not enough swing points.");
            return result;
        }

        TrendLine supportLine = createTrendLineFromPoints(lows);
        TrendLine resistanceLine = createTrendLineFromPoints(highs);

        BigDecimal support = supportLine.getPriceAt(data.size() - 1);
        BigDecimal resistance = resistanceLine.getPriceAt(data.size() - 1);
        BigDecimal currentPrice = data.get(data.size() - 1).getClose();
        BigDecimal currentVolume = data.get(data.size() - 1).getVolume();

        BigDecimal trendSupport = support.min(resistance);
        BigDecimal trendResistance = support.max(resistance);

        BigDecimal supportSlope = supportLine.getSlope();
        BigDecimal resistanceSlope = resistanceLine.getSlope();

        String trendDirection = getTrendDirection(supportSlope, resistanceSlope);

        BigDecimal avgVolume = calculateAverageVolume(data);
        boolean volSpike = currentVolume.compareTo(avgVolume.multiply(BigDecimal.valueOf(1.5))) >= 0;

        result.setTrendSupport(trendSupport);
        result.setTrendResistance(trendResistance);
        result.setCurrentPrice(currentPrice);
        result.setCurrentVolume(currentVolume);
        result.setAvgVolume(avgVolume);
        result.setTrendDirection(trendDirection);

        if (currentPrice.compareTo(trendResistance) > 0)
            result.setTrendSignal(String.format("BREAKOUT - %s | Trend: %s", volSpike ? "VOLUME CONFIRMED" : "LOW VOLUME", trendDirection));
        else if (currentPrice.compareTo(trendSupport) < 0)
            result.setTrendSignal(String.format("BREAKDOWN - %s | Trend: %s", volSpike ? "VOLUME CONFIRMED" : "LOW VOLUME", trendDirection));
        else
            result.setTrendSignal(String.format("INSIDE TREND RANGE | Trend: %s", trendDirection));

        calculateZigzag(data, result);

        return result;
    }

    private TrendLine createTrendLineFromPoints(List<SwingPoint> points) {
        SwingPoint p1 = points.get(points.size() - 2);
        SwingPoint p2 = points.get(points.size() - 1);
        return new TrendLine(p1.getIndex(), p2.getIndex(), p1.getPrice(), p2.getPrice());
    }

    private String getTrendDirection(BigDecimal supportSlope, BigDecimal resistanceSlope) {
        if (supportSlope.compareTo(BigDecimal.ZERO) > 0 && resistanceSlope.compareTo(BigDecimal.ZERO) > 0) {
            return "UPTREND";
        } else if (supportSlope.compareTo(BigDecimal.ZERO) < 0 && resistanceSlope.compareTo(BigDecimal.ZERO) < 0) {
            return "DOWNTREND";
        } else {
            return "SIDEWAYS";
        }
    }

    private BigDecimal calculateAverageVolume(List<OHLCV> data) {
        int lookback = Math.min(10, data.size());
        return data.subList(data.size() - lookback, data.size()).stream()
                .map(OHLCV::getVolume)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(lookback), RoundingMode.HALF_UP);
    }

    private void calculateZigzag(List<OHLCV> data, TrendAnalysisResult result) {
        int lookback = Math.min(10, data.size());
        BigDecimal totalRange = BigDecimal.ZERO;
        for (int i = data.size() - lookback; i < data.size(); i++) {
            totalRange = totalRange.add(data.get(i).getHigh().subtract(data.get(i).getLow()));
        }
        BigDecimal avgRange = totalRange.divide(BigDecimal.valueOf(lookback), 4, RoundingMode.HALF_UP);
        BigDecimal threshold = avgRange.multiply(BigDecimal.valueOf(1.5));
        BigDecimal percentThreshold = data.get(data.size() - 1).getClose().multiply(BigDecimal.valueOf(0.03));
        threshold = threshold.max(percentThreshold);

        List<SwingPoint> zigzagPoints = new ArrayList<>();
        BigDecimal lastPivot = data.get(0).getClose();
        SwingType lastType = null;

        for (int i = 1; i < data.size(); i++) {
            BigDecimal price = data.get(i).getClose();
            BigDecimal change = price.subtract(lastPivot).abs();
            if (change.compareTo(threshold) >= 0) {
                lastType = (price.compareTo(lastPivot) > 0) ? SwingType.HIGH : SwingType.LOW;
                zigzagPoints.add(new SwingPoint(i, price, lastType));
                lastPivot = price;
            }
        }

        Optional<SwingPoint> zzLow = zigzagPoints.stream().filter(p -> p.getType() == SwingType.LOW).reduce((first, second) -> second);
        Optional<SwingPoint> zzHigh = zigzagPoints.stream().filter(p -> p.getType() == SwingType.HIGH).reduce((first, second) -> second);

        zzLow.ifPresent(p -> result.setZigzagSupport(p.getPrice()));
        zzHigh.ifPresent(p -> result.setZigzagResistance(p.getPrice()));

        BigDecimal currentPrice = data.get(data.size() - 1).getClose();
        if (zzHigh.isPresent() && currentPrice.compareTo(zzHigh.get().getPrice()) > 0)
            result.setZigzagSignal("ZIGZAG BREAKOUT");
        else if (zzLow.isPresent() && currentPrice.compareTo(zzLow.get().getPrice()) < 0)
            result.setZigzagSignal("ZIGZAG BREAKDOWN");
        else
            result.setZigzagSignal("WITHIN ZIGZAG RANGE");

        if (zigzagPoints.size() >= 2) {
            SwingPoint last = zigzagPoints.get(zigzagPoints.size() - 1);
            SwingPoint prev = zigzagPoints.get(zigzagPoints.size() - 2);
            if (last.getPrice().compareTo(prev.getPrice()) > 0) {
                result.setZigzagDirection("UPTREND");
            } else if (last.getPrice().compareTo(prev.getPrice()) < 0) {
                result.setZigzagDirection("DOWNTREND");
            } else {
                result.setZigzagDirection("SIDEWAYS");
            }
        } else {
            result.setZigzagDirection("UNKNOWN");
        }
    }
}