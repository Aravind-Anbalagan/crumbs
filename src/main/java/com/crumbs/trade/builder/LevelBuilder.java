package com.crumbs.trade.builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.crumbs.trade.dto.FiboLevel;
import com.crumbs.trade.entity.Level;

@Component
public class LevelBuilder {

    public Level buildPriceActionLevel(
            String symbol,
            String timeframe,
            int seq,
            BigDecimal levelValue,
            LocalDateTime generatedAt) {

        Level level = baseLevel(symbol, timeframe, generatedAt);
        level.setMethod("PRICE_ACTION");
        level.setSeq(seq);
        level.setLevelValue(levelValue);
        return level;
    }

    public Level buildFiboLevel(
            String symbol,
            String timeframe,
            int seq,
            FiboLevel fibo,
            LocalDateTime generatedAt) {

        Level level = baseLevel(symbol, timeframe, generatedAt);
        level.setMethod("FIBO");
        level.setSeq(seq);
        level.setLevelValue(fibo.getLevel());
        level.setStrength(fibo.getStrength());
        level.setTouches(fibo.getTouches());
        level.setLabel(fibo.getLabel());
        return level;
    }

    private Level baseLevel(
            String symbol,
            String timeframe,
            LocalDateTime generatedAt) {

        Level level = new Level();
        level.setSymbol(symbol);
        level.setTimeframe(timeframe);
        level.setGeneratedAt(generatedAt);
        return level;
    }
}
