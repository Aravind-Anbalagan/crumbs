package com.crumbs.trade.utility;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

import com.crumbs.trade.dto.LevelAnalysisResult;
import com.crumbs.trade.entity.Level;

public class LevelAnalysisUtil {

    /* ==================================================
       CONSTANTS (CHANGE VALUES ONLY HERE)
       ================================================== */

    // Symbols
    private static final String SYMBOL_NIFTY = "NIFTY";
    private static final String SYMBOL_SILVERM = "SILVERM";

    // Near-range (points)
    private static final BigDecimal NIFTY_NEAR_RANGE = new BigDecimal("5");
    private static final BigDecimal SILVER_NEAR_RANGE = new BigDecimal("25");

    // Zones
    private static final String ZONE_BUY = "BUY_ZONE";
    private static final String ZONE_SELL = "SELL_ZONE";
    private static final String ZONE_NO_TRADE = "NO_TRADE_ZONE";

    // Bias
    private static final String BIAS_BULLISH = "BULLISH";
    private static final String BIAS_BEARISH = "BEARISH";
    private static final String BIAS_NEUTRAL = "NEUTRAL";

    // Explanations
    private static final String EXPLAIN_COMPRESSION =
            "Price in compression between support & resistance.";

    private static final String EXPLAIN_BUY =
            "Price near strong support. Expect bounce.";

    private static final String EXPLAIN_SELL =
            "Price near strong resistance. Expect rejection.";

    private static final String EXPLAIN_NO_TRADE =
            "Price away from key levels.";

    /* ==================================================
       ANALYSIS LOGIC
       ================================================== */

    public static LevelAnalysisResult analyze(
            String symbol,
            BigDecimal currentPrice,
            List<Level> levels) {

        LevelAnalysisResult r = new LevelAnalysisResult();
        r.setCurrentPrice(currentPrice);

        BigDecimal nearRange = getNearRange(symbol);

        // Nearest support
        Level support = levels.stream()
                .filter(l -> l.getSeq() > 0)
                .min(Comparator.comparing(
                        l -> currentPrice.subtract(l.getLevelValue()).abs()))
                .orElse(null);

        // Nearest resistance
        Level resistance = levels.stream()
                .filter(l -> l.getSeq() < 0)
                .min(Comparator.comparing(
                        l -> currentPrice.subtract(l.getLevelValue()).abs()))
                .orElse(null);

        r.setNearestSupport(support);
        r.setNearestResistance(resistance);

        BigDecimal supportDistance = support == null
                ? null
                : currentPrice.subtract(support.getLevelValue()).abs();

        BigDecimal resistanceDistance = resistance == null
                ? null
                : currentPrice.subtract(resistance.getLevelValue()).abs();

        r.setSupportDistance(supportDistance);
        r.setResistanceDistance(resistanceDistance);

        boolean nearSupport =
                supportDistance != null
                        && supportDistance.compareTo(nearRange) <= 0;

        boolean nearResistance =
                resistanceDistance != null
                        && resistanceDistance.compareTo(nearRange) <= 0;

        // --------------------------------------------------
        // ZONE DECISION
        // --------------------------------------------------

        if (nearSupport && nearResistance) {
            r.setZone(ZONE_NO_TRADE);
            r.setBias(BIAS_NEUTRAL);
            r.setExplanation(EXPLAIN_COMPRESSION);
            return r;
        }

        if (nearSupport) {
            r.setZone(ZONE_BUY);
            r.setBias(BIAS_BULLISH);
            r.setExplanation(EXPLAIN_BUY);
            return r;
        }

        if (nearResistance) {
            r.setZone(ZONE_SELL);
            r.setBias(BIAS_BEARISH);
            r.setExplanation(EXPLAIN_SELL);
            return r;
        }

        r.setZone(ZONE_NO_TRADE);
        r.setBias(BIAS_NEUTRAL);
        r.setExplanation(EXPLAIN_NO_TRADE);
        return r;
    }

    /* ==================================================
       HELPERS
       ================================================== */

    private static BigDecimal getNearRange(String symbol) {
        return SYMBOL_SILVERM.equals(symbol)
                ? SILVER_NEAR_RANGE
                : NIFTY_NEAR_RANGE;
    }
}
