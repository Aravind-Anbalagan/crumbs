package com.crumbs.trade.service;

import com.crumbs.trade.dto.Candlestick;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;

/**
 * VWAP Swing Indicator — matches TradingView-style VWAP behavior.
 * Resets by timeframe:
 *  - ONE_HOUR → resets daily
 *  - ONE_DAY  → resets monthly
 *  - ONE_WEEK → resets weekly
 *
 * Fully thread-safe and consistent even under high concurrency.
 */
@Service
public class VWAPSwingIndicator {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd['T'][' ']HH:mm[:ss]");

    public List<Candlestick> calculateVWAP(List<Candlestick> candles, String timeframe) {
        List<Candlestick> result = new ArrayList<>();
        if (candles == null || candles.isEmpty()) return result;

        // Local accumulators (no shared state)
        BigDecimal cumulativePV = BigDecimal.ZERO;
        BigDecimal cumulativeVolume = BigDecimal.ZERO;

        Integer lastWeek = null;
        Integer lastDay = null;
        Integer lastMonth = null;

        for (Candlestick original : candles) {
            // Defensive copy for thread safety
            Candlestick c = new Candlestick(original);

            if (c.getHigh() == null || c.getLow() == null || c.getClose() == null
                    || c.getVolume() == null || c.getVolume().compareTo(BigDecimal.ZERO) == 0
                    || c.getTimestamp() == null) {
                result.add(c);
                continue;
            }

            LocalDateTime ts = parseTimestamp(c.getTimestamp());
            int currentWeek = ts.get(WeekFields.ISO.weekOfWeekBasedYear());
            int currentDay = ts.getDayOfYear();
            int currentMonth = ts.getMonthValue();

            // 🔁 Reset VWAP depending on timeframe
            switch (timeframe.toUpperCase()) {
                case "ONE_HOUR":
                    if (lastDay != null && currentDay != lastDay) {
                        cumulativePV = BigDecimal.ZERO;
                        cumulativeVolume = BigDecimal.ZERO;
                    }
                    lastDay = currentDay;
                    break;

                case "ONE_DAY":
                    if (lastMonth != null && currentMonth != lastMonth) {
                        cumulativePV = BigDecimal.ZERO;
                        cumulativeVolume = BigDecimal.ZERO;
                    }
                    lastMonth = currentMonth;
                    break;

                case "ONE_WEEK":
                    if (lastWeek != null && currentWeek != lastWeek) {
                        cumulativePV = BigDecimal.ZERO;
                        cumulativeVolume = BigDecimal.ZERO;
                    }
                    lastWeek = currentWeek;
                    break;
            }

            // Typical Price = (H + L + C) / 3
            BigDecimal typicalPrice = c.getHigh()
                    .add(c.getLow())
                    .add(c.getClose())
                    .divide(BigDecimal.valueOf(3), 6, RoundingMode.HALF_UP);

            // Accumulate
            cumulativePV = cumulativePV.add(typicalPrice.multiply(c.getVolume()));
            cumulativeVolume = cumulativeVolume.add(c.getVolume());

            if (cumulativeVolume.compareTo(BigDecimal.ZERO) == 0) {
                result.add(c);
                continue;
            }

            // VWAP = Σ(PV) / Σ(V)
            BigDecimal vwap = cumulativePV.divide(cumulativeVolume, 4, RoundingMode.HALF_UP);
            c.setVwap(vwap);

            // ✅ Signal: BUY if close ≥ vwap, SELL otherwise
            if (c.getClose().compareTo(vwap) > 0) {
                c.setSignal("BUY");
            } else if (c.getClose().compareTo(vwap) < 0) {
                c.setSignal("SELL");
            } else {
                c.setSignal("FLAT"); // optional for exact match
            }

            result.add(c);
        }

        return result;
    }

    private LocalDateTime parseTimestamp(String timestamp) {
        try {
            return LocalDateTime.parse(timestamp.replace(" ", "T"), FMT);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }
}
