package com.crumbs.trade.service;

import com.crumbs.trade.dto.Candlestick;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class VWAPIndicator {

    /**
     * Calculate VWAP for a list of candles.
     * Formula: VWAP = Σ(typicalPrice * volume) / Σ(volume)
     * where typicalPrice = (high + low + close) / 3
     *
     * Also assigns BUY/SELL signals:
     *  - BUY  → if close > VWAP
     *  - SELL → if close < VWAP
     */
    public List<Candlestick> calculateVWAP(List<Candlestick> candles) {
        List<Candlestick> result = new ArrayList<>();
        if (candles == null || candles.isEmpty()) return result;

        BigDecimal cumulativePV = BigDecimal.ZERO;      // Σ(price * volume)
        BigDecimal cumulativeVolume = BigDecimal.ZERO;  // Σ(volume)

        for (Candlestick c : candles) {
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

            // ✅ VWAP-based signal
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
