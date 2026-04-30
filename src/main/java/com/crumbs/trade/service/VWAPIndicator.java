package com.crumbs.trade.service;

import com.crumbs.trade.dto.Candlestick;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class VWAPIndicator {

    /**
     * Calculate Intraday VWAP for a list of candles.
     * Formula: VWAP = Σ(typicalPrice * volume) / Σ(volume)
     * Resets at the start of each new trading day.
     */
    public List<Candlestick> calculateVWAP(List<Candlestick> candles) {
        List<Candlestick> result = new ArrayList<>();
        if (candles == null || candles.isEmpty()) return result;

        BigDecimal cumulativePV = BigDecimal.ZERO;      // Σ(price * volume)
        BigDecimal cumulativeVolume = BigDecimal.ZERO;  // Σ(volume)
        LocalDate currentDay = null;                    // Tracks the active trading day

        for (Candlestick c : candles) {
            // 1. Extract the date to check for a new trading session
            // Note: Adjust the parsing based on the actual data type of c.getTimestamp()
            // Assuming it's an ISO-8601 String like "2026-04-29T09:20:00+05:30"
            LocalDate candleDate = OffsetDateTime.parse(c.getTimestamp()).toLocalDate();

            // 2. Daily Reset Logic: If it's the first candle or a new day, reset accumulators
            if (currentDay == null || !currentDay.equals(candleDate)) {
                cumulativePV = BigDecimal.ZERO;
                cumulativeVolume = BigDecimal.ZERO;
                currentDay = candleDate;
            }

            if (c.getVolume() == null || c.getVolume().compareTo(BigDecimal.ZERO) == 0) {
                result.add(c);
                continue;
            }

            // Typical price = (High + Low + Close) / 3
            BigDecimal typicalPrice = c.getHigh()
                    .add(c.getLow())
                    .add(c.getClose())
                    .divide(BigDecimal.valueOf(3), 6, RoundingMode.HALF_UP);

            BigDecimal pv = typicalPrice.multiply(c.getVolume());

            cumulativePV = cumulativePV.add(pv);
            cumulativeVolume = cumulativeVolume.add(c.getVolume());

            BigDecimal vwap = cumulativePV.divide(cumulativeVolume, 6, RoundingMode.HALF_UP);
            c.setVwap(vwap);

            // VWAP-based signal
            if (c.getClose().compareTo(vwap) > 0) {
                c.setSignal("BUY");
            } else if (c.getClose().compareTo(vwap) < 0) {
                c.setSignal("SELL");
            } else {
                c.setSignal("NEUTRAL");
            }

            result.add(c);
        }

        return result;
    }
}