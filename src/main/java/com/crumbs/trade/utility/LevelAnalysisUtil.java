package com.crumbs.trade.utility;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

import com.crumbs.trade.dto.LevelAnalysisResult;
import com.crumbs.trade.entity.Level;

public class LevelAnalysisUtil {

    private static final BigDecimal NEAR_RANGE = new BigDecimal("10");

    public static LevelAnalysisResult analyze(
            BigDecimal currentPrice,
            List<Level> levels) {

        LevelAnalysisResult r = new LevelAnalysisResult();
        r.setCurrentPrice(currentPrice);

        Level support = levels.stream()
                .filter(l -> l.getSeq() > 0)
                .min(Comparator.comparing(
                        l -> currentPrice.subtract(l.getLevelValue()).abs()))
                .orElse(null);

        Level resistance = levels.stream()
                .filter(l -> l.getSeq() < 0)
                .min(Comparator.comparing(
                        l -> currentPrice.subtract(l.getLevelValue()).abs()))
                .orElse(null);

        r.setNearestSupport(support);
        r.setNearestResistance(resistance);

        BigDecimal sd = support == null ? null :
                currentPrice.subtract(support.getLevelValue()).abs();

        BigDecimal rd = resistance == null ? null :
                currentPrice.subtract(resistance.getLevelValue()).abs();

        r.setSupportDistance(sd);
        r.setResistanceDistance(rd);

        boolean nearSupport = sd != null && sd.compareTo(NEAR_RANGE) <= 0;
        boolean nearResistance = rd != null && rd.compareTo(NEAR_RANGE) <= 0;

        if (nearSupport && nearResistance) {
            r.setZone("NO_TRADE_ZONE");
            r.setBias("NEUTRAL");
            r.setExplanation("Price in compression between support & resistance.");
            return r;
        }

        if (nearSupport) {
            r.setZone("BUY_ZONE");
            r.setBias("BULLISH");
            r.setExplanation("Price near strong support. Expect bounce.");
            return r;
        }

        if (nearResistance) {
            r.setZone("SELL_ZONE");
            r.setBias("BEARISH");
            r.setExplanation("Price near strong resistance. Expect rejection.");
            return r;
        }

        r.setZone("NO_TRADE_ZONE");
        r.setBias("NEUTRAL");
        r.setExplanation("Price away from key levels.");
        return r;
    }
}
