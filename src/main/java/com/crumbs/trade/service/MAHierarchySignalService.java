package com.crumbs.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Evaluates the Moving Average hierarchy signal.
 *
 * BUY  hierarchy — full bull stack (top → bottom):
 *      currentPrice > EMA9 > EMA21 > MA50 > MA200
 *
 * SELL hierarchy — full bear stack (bottom → top):
 *      currentPrice < EMA9 < EMA21 < MA50 < MA200
 *
 * NEUTRAL — any partial or mixed alignment.
 *
 * Returns null if any required value is missing (insufficient history),
 * OR if the current price is more than 2% away from MA200
 * (filters for stocks that are close to a potential breakout/breakdown).
 */
@Service
public class MAHierarchySignalService {

    private static final Logger logger = LoggerFactory.getLogger(MAHierarchySignalService.class);

    public static final String BUY     = "BUY";
    public static final String SELL    = "SELL";
    public static final String NEUTRAL = "NEUTRAL";

    /** Maximum allowed distance (%) between current price and MA200. */
    private static final BigDecimal MAX_MA200_DISTANCE_PCT = new BigDecimal("5");

    /**
     * Evaluates the 5-level MA hierarchy for a single stock.
     *
     * @param currentPrice live LTP
     * @param ema9         9-period EMA  (movingavg9)
     * @param ema21        21-period EMA (movingavg21)
     * @param ma50         50-period SMA (movingavg50)
     * @param ma200        200-period SMA(movingavg200)
     * @return "BUY", "SELL", "NEUTRAL", or null if any level value is missing
     *         or if current price is more than 2% away from MA200
     */
    public String evaluate(BigDecimal currentPrice,
                           BigDecimal ema9,
                           BigDecimal ema21,
                           BigDecimal ma50,
                           BigDecimal ma200) {

        // If any level is null we cannot evaluate the full stack — return null explicitly
        // so callers can distinguish "evaluated → NEUTRAL" from "could not evaluate"
        if (currentPrice == null || ema9 == null || ema21 == null
                || ma50 == null || ma200 == null) {
            logger.debug("MA hierarchy: skipping — one or more values are null "
                    + "[price={}, ema9={}, ema21={}, ma50={}, ma200={}]",
                    currentPrice, ema9, ema21, ma50, ma200);
            return null;
        }

        // Only consider stocks where current price is within 2% of MA200.
        // Stocks further away are unlikely to be at a key breakout/breakdown zone.
        if (isOutsideMa200Band(currentPrice, ma200)) {
            logger.debug("MA hierarchy: skipping — price={} is >{}% from ma200={} (distance={}%)",
                    currentPrice, MAX_MA200_DISTANCE_PCT, ma200,
                    ma200DistancePct(currentPrice, ma200));
            return null;
        }

        if (isBullStack(currentPrice, ema9, ema21, ma50, ma200)) {
            logger.debug("MA hierarchy → BUY  [price={} > ema9={} > ema21={} > ma50={} > ma200={}]",
                    currentPrice, ema9, ema21, ma50, ma200);
            return BUY;
        }

        if (isBearStack(currentPrice, ema9, ema21, ma50, ma200)) {
            logger.debug("MA hierarchy → SELL [price={} < ema9={} < ema21={} < ma50={} < ma200={}]",
                    currentPrice, ema9, ema21, ma50, ma200);
            return SELL;
        }

        logger.debug("MA hierarchy → NEUTRAL [price={}, ema9={}, ema21={}, ma50={}, ma200={}]",
                currentPrice, ema9, ema21, ma50, ma200);
        return NEUTRAL;
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /**
     * Returns true if the current price is more than {@link #MAX_MA200_DISTANCE_PCT}%
     * away from MA200 in either direction.
     */
    private boolean isOutsideMa200Band(BigDecimal price, BigDecimal ma200) {
        return ma200DistancePct(price, ma200).compareTo(MAX_MA200_DISTANCE_PCT) > 0;
    }

    /**
     * Computes |price − ma200| / ma200 × 100, rounded to 4 decimal places.
     */
    private BigDecimal ma200DistancePct(BigDecimal price, BigDecimal ma200) {
        return price.subtract(ma200).abs()
                .divide(ma200, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    /** currentPrice > EMA9 > EMA21 > MA50 > MA200 */
    private boolean isBullStack(BigDecimal price, BigDecimal ema9,
                                BigDecimal ema21, BigDecimal ma50, BigDecimal ma200) {
        return price.compareTo(ema9)  > 0
            && ema9.compareTo(ema21)  > 0
            && ema21.compareTo(ma50)  > 0
            && ma50.compareTo(ma200)  > 0;
    }

    /** currentPrice < EMA9 < EMA21 < MA50 < MA200 */
    private boolean isBearStack(BigDecimal price, BigDecimal ema9,
                                BigDecimal ema21, BigDecimal ma50, BigDecimal ma200) {
        return price.compareTo(ema9)  < 0
            && ema9.compareTo(ema21)  < 0
            && ema21.compareTo(ma50)  < 0
            && ma50.compareTo(ma200)  < 0;
    }
}