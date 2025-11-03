package com.crumbs.trade.service;

import com.crumbs.trade.dto.Candlestick;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
 * 
 * Fixed issues:
 * - Year boundary handling for all timeframes
 * - Signal logic now matches comment (>= instead of >)
 * - Better error handling for timestamp parsing
 * - Week comparison now includes year context
 */
@Service
public class VWAPSwingIndicator {

    // Support multiple timestamp formats including timezone offsets
    private static final DateTimeFormatter FMT_WITH_ZONE = DateTimeFormatter.ofPattern("yyyy-MM-dd['T'][' ']HH:mm[:ss][XXX][XX][X]");
    private static final DateTimeFormatter FMT_NO_ZONE = DateTimeFormatter.ofPattern("yyyy-MM-dd['T'][' ']HH:mm[:ss]");

    public List<Candlestick> calculateVWAP(List<Candlestick> candles, String timeframe) {
        List<Candlestick> result = new ArrayList<>();
        if (candles == null || candles.isEmpty()) return result;

        // Local accumulators (no shared state)
        BigDecimal cumulativePV = BigDecimal.ZERO;
        BigDecimal cumulativeVolume = BigDecimal.ZERO;

        // Store full date/time context to handle year boundaries correctly
        LocalDate lastDate = null;
        YearMonth lastYearMonth = null;
        WeekYear lastWeekYear = null;

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
            if (ts == null) {
                // Skip invalid timestamps instead of using current time
                result.add(c);
                continue;
            }

            LocalDate currentDate = ts.toLocalDate();
            YearMonth currentYearMonth = YearMonth.from(ts);
            WeekYear currentWeekYear = getWeekYear(ts);

            // 🔁 Reset VWAP depending on timeframe
            switch (timeframe.toUpperCase()) {
                case "ONE_HOUR":
                    // Reset daily - now handles year boundaries correctly
                    if (lastDate != null && !currentDate.equals(lastDate)) {
                        cumulativePV = BigDecimal.ZERO;
                        cumulativeVolume = BigDecimal.ZERO;
                    }
                    lastDate = currentDate;
                    break;

                case "ONE_DAY":
                    // Reset monthly - now handles year boundaries correctly
                    if (lastYearMonth != null && !currentYearMonth.equals(lastYearMonth)) {
                        cumulativePV = BigDecimal.ZERO;
                        cumulativeVolume = BigDecimal.ZERO;
                    }
                    lastYearMonth = currentYearMonth;
                    break;

                case "ONE_WEEK":
                    // Reset weekly - now handles year boundaries correctly
                    if (lastWeekYear != null && !currentWeekYear.equals(lastWeekYear)) {
                        cumulativePV = BigDecimal.ZERO;
                        cumulativeVolume = BigDecimal.ZERO;
                    }
                    lastWeekYear = currentWeekYear;
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
            if (c.getClose().compareTo(vwap) >= 0) {  // Fixed: now uses >= instead of >
                c.setSignal("BUY");
            } else {
                c.setSignal("SELL");
            }

            result.add(c);
        }

        return result;
    }

    /**
     * Parse timestamp with improved error handling.
     * Supports timestamps with or without timezone offsets.
     * Returns null on parse failure instead of current time.
     */
    private LocalDateTime parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.trim().isEmpty()) {
            return null;
        }
        
        String normalized = timestamp.replace(" ", "T");
        
        try {
            // Try parsing with timezone offset first (e.g., 2025-01-17T00:00:00+05:30)
            return java.time.ZonedDateTime.parse(normalized, FMT_WITH_ZONE).toLocalDateTime();
        } catch (DateTimeParseException e1) {
            try {
                // Fallback to parsing without timezone
                return LocalDateTime.parse(normalized, FMT_NO_ZONE);
            } catch (DateTimeParseException e2) {
                // Log the error in production
                System.err.println("Failed to parse timestamp: " + timestamp + " - " + e2.getMessage());
                return null;
            }
        }
    }

    /**
     * Get week number with year context to handle year boundaries correctly.
     */
    private WeekYear getWeekYear(LocalDateTime dateTime) {
        int weekOfYear = dateTime.get(WeekFields.ISO.weekOfWeekBasedYear());
        int weekBasedYear = dateTime.get(WeekFields.ISO.weekBasedYear());
        return new WeekYear(weekBasedYear, weekOfYear);
    }

    /**
     * Helper class to store week and year together for proper comparison.
     */
    private static class WeekYear {
        private final int year;
        private final int week;

        public WeekYear(int year, int week) {
            this.year = year;
            this.week = week;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            WeekYear weekYear = (WeekYear) obj;
            return year == weekYear.year && week == weekYear.week;
        }

        @Override
        public int hashCode() {
            return 31 * year + week;
        }

        @Override
        public String toString() {
            return year + "-W" + week;
        }
    }
}