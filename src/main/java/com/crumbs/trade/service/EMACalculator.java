package com.crumbs.trade.service;

import com.crumbs.trade.entity.Indicator;
import com.crumbs.trade.entity.PricesIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Computes Exponential Moving Averages (EMA) for a given period.
 *
 * Contract:
 *  - Input list must be NEWEST-FIRST (same convention as allData in VolumeAnalysisService).
 *  - Internally reverses to oldest-first before computing.
 *  - Returns the latest (most recent) EMA value.
 *  - Returns null if there are fewer candles than the requested period.
 */
@Component
public class EMACalculator {

    private static final Logger       logger   = LoggerFactory.getLogger(EMACalculator.class);
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final int          SCALE    = 4;

    /**
     * Computes EMA for the given period and returns the latest value.
     *
     * @param newestFirstList candles ordered newest → oldest
     * @param period          EMA period (e.g. 9, 21)
     * @return latest EMA value, or null if insufficient data
     */
    public BigDecimal computeEMA(List<PricesIndex> newestFirstList, int period) {
        if (newestFirstList == null || newestFirstList.size() < period) {
            logger.debug("EMA{}: insufficient data — need {}, got {}",
                    period, period, newestFirstList == null ? 0 : newestFirstList.size());
            return null;
        }

        // Reverse to oldest-first for chronological EMA computation
        List<PricesIndex> chronological = new ArrayList<>(newestFirstList);
        Collections.reverse(chronological);

        // Seed: SMA of the first `period` candles
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            BigDecimal close = chronological.get(i).getClose();
            if (close == null) {
                logger.warn("EMA{}: null close at index {} — aborting", period, i);
                return null;
            }
            sum = sum.add(close);
        }
        BigDecimal ema = sum.divide(BigDecimal.valueOf(period), SCALE, ROUNDING);

        // Multiplier: k = 2 / (period + 1)
        BigDecimal k         = BigDecimal.valueOf(2.0 / (period + 1)).setScale(SCALE, ROUNDING);
        BigDecimal oneMinusK = BigDecimal.ONE.subtract(k);

        // Rolling EMA from candle `period` onward
        for (int i = period; i < chronological.size(); i++) {
            BigDecimal close = chronological.get(i).getClose();
            if (close == null) continue;
            // EMA = close * k  +  prevEMA * (1 - k)
            ema = close.multiply(k)
                       .add(ema.multiply(oneMinusK))
                       .setScale(SCALE, ROUNDING);
        }

        logger.debug("EMA{} computed: {}", period, ema);
        return ema;
    }

    /**
     * Convenience method: computes EMA9 and EMA21 and sets them
     * directly on the Indicator entity using the existing mapped columns.
     *
     * @param indicator       entity to populate (mutated in place)
     * @param newestFirstList candles ordered newest → oldest
     */
    public void setEMAFields(Indicator indicator, List<PricesIndex> newestFirstList) {
        indicator.setMovingavg9(computeEMA(newestFirstList, 9));
        indicator.setMovingavg21(computeEMA(newestFirstList, 21));
    }
}