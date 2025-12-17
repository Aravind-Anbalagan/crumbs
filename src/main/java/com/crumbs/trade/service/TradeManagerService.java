package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crumbs.trade.dto.LevelAnalysisResult;
import com.crumbs.trade.entity.Level;
import com.crumbs.trade.entity.TradeExecution;
import com.crumbs.trade.repo.TradeExecutionRepo;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TradeManagerService {

    /* ==================================================
       CONSTANTS (CHANGE VALUES ONLY HERE)
       ================================================== */

    // --- Strategy toggles
    private static final boolean ENABLE_PRICE_ACTION = false;
    private static final boolean ENABLE_FIBO = true;
    private static final boolean ENABLE_TRAILING_SL = false;

    // --- Cooldown
    private static final int COOLDOWN_MINUTES = 5;

    // --- Symbols
    private static final String SYMBOL_NIFTY = "NIFTY";
    private static final String SYMBOL_SILVERM = "SILVERM";

    // --- Risk config : NIFTY
    private static final BigDecimal NIFTY_TARGET = new BigDecimal("30");
    private static final BigDecimal NIFTY_SL = new BigDecimal("10");
    private static final BigDecimal NIFTY_TRAIL_SL = new BigDecimal("10");

    // --- Risk config : SILVERM
    private static final BigDecimal SILVER_TARGET = new BigDecimal("750");
    private static final BigDecimal SILVER_SL = new BigDecimal("250");
    private static final BigDecimal SILVER_TRAIL_SL = new BigDecimal("250");

    /* ==================================================
       DEPENDENCY
       ================================================== */

    private final TradeExecutionRepo repo;

    /* ==================================================
       ENTRY LOGIC
       ================================================== */

    @Transactional
    public void handleSignal(
            String symbol,
            String timeframe,
            LevelAnalysisResult analysis) {

        if (analysis == null) return;

        if (!"BUY_ZONE".equals(analysis.getZone())
                && !"SELL_ZONE".equals(analysis.getZone())) return;

        // Method filter
        Level ref = "BUY_ZONE".equals(analysis.getZone())
                ? analysis.getNearestSupport()
                : analysis.getNearestResistance();

        if (ref == null) return;

        if (!isMethodAllowed(ref.getMethod())) return;

        // No open trade
        Optional<TradeExecution> open =
                repo.findFirstBySymbolAndTimeframeAndStatus(
                        symbol, timeframe, "OPEN");

        if (open.isPresent()) return;

        // Cooldown
        Optional<TradeExecution> last =
                repo.findFirstBySymbolAndTimeframeOrderByEntryTimeDesc(
                        symbol, timeframe);

        if (last.isPresent()
                && last.get().getExitTime() != null
                && last.get().getExitTime()
                .isAfter(LocalDateTime.now()
                        .minusMinutes(COOLDOWN_MINUTES))) {
            return;
        }

        String tradeType =
                "BUY_ZONE".equals(analysis.getZone()) ? "BUY" : "SELL";

        TradeExecution t = new TradeExecution();
        t.setSymbol(symbol);
        t.setTimeframe(timeframe);
        t.setTradeType(tradeType);
        t.setStatus("OPEN");

        t.setEntryPrice(analysis.getCurrentPrice());
        t.setEntryTime(LocalDateTime.now());
        t.setLastSignalTime(LocalDateTime.now());

        // 🔥 Target & SL from constants
        t.setTargetPrice(calcTarget(
                analysis.getCurrentPrice(), tradeType, symbol));

        t.setSlPrice(calcSL(
                analysis.getCurrentPrice(), tradeType, symbol));

        t.setLevelValue(ref.getLevelValue());
        t.setMethod(ref.getMethod());
        t.setStrength(ref.getStrength());
        t.setExplanation(analysis.getExplanation());

        repo.save(t);
    }

    /* ==================================================
       EXIT + TRAILING SL
       ================================================== */

    @Transactional
    public void monitorTrade(
            String symbol,
            String timeframe,
            BigDecimal ltp) {

        Optional<TradeExecution> open =
                repo.findFirstBySymbolAndTimeframeAndStatus(
                        symbol, timeframe, "OPEN");

        if (open.isEmpty()) return;

        TradeExecution t = open.get();

        if (ENABLE_TRAILING_SL) {
            applyTrailingSL(t, ltp);
        }

        if ("BUY".equals(t.getTradeType())) {
            if (ltp.compareTo(t.getTargetPrice()) >= 0)
                close(t, ltp, "TARGET");
            else if (ltp.compareTo(t.getSlPrice()) <= 0)
                close(t, ltp, "SL");
        }

        if ("SELL".equals(t.getTradeType())) {
            if (ltp.compareTo(t.getTargetPrice()) <= 0)
                close(t, ltp, "TARGET");
            else if (ltp.compareTo(t.getSlPrice()) >= 0)
                close(t, ltp, "SL");
        }
    }

    /* ==================================================
       CLOSE + PnL
       ================================================== */

    private void close(TradeExecution t, BigDecimal exitPrice, String reason) {

        t.setStatus("CLOSED");
        t.setExitPrice(exitPrice);
        t.setExitTime(LocalDateTime.now());
        t.setExitReason(reason);

        BigDecimal pnl =
                "BUY".equals(t.getTradeType())
                        ? exitPrice.subtract(t.getEntryPrice())
                        : t.getEntryPrice().subtract(exitPrice);

        t.setPnl(pnl);

        repo.save(t);
    }

    /* ==================================================
       HELPERS
       ================================================== */

    public boolean isMethodAllowed(String method) {
        return ("PRICE_ACTION".equals(method) && ENABLE_PRICE_ACTION)
                || ("FIBO".equals(method) && ENABLE_FIBO);
    }

    private BigDecimal calcTarget(
            BigDecimal price,
            String tradeType,
            String symbol) {

        BigDecimal target =
                SYMBOL_SILVERM.equals(symbol)
                        ? SILVER_TARGET
                        : NIFTY_TARGET;

        return "BUY".equals(tradeType)
                ? price.add(target)
                : price.subtract(target);
    }

    private BigDecimal calcSL(
            BigDecimal price,
            String tradeType,
            String symbol) {

        BigDecimal sl =
                SYMBOL_SILVERM.equals(symbol)
                        ? SILVER_SL
                        : NIFTY_SL;

        return "BUY".equals(tradeType)
                ? price.subtract(sl)
                : price.add(sl);
    }

    private void applyTrailingSL(TradeExecution t, BigDecimal ltp) {

        BigDecimal step =
                SYMBOL_SILVERM.equals(t.getSymbol())
                        ? SILVER_TRAIL_SL
                        : NIFTY_TRAIL_SL;

        if ("BUY".equals(t.getTradeType())) {
            BigDecimal newSl = ltp.subtract(step);
            if (newSl.compareTo(t.getSlPrice()) > 0) {
                t.setSlPrice(newSl);
            }
        }

        if ("SELL".equals(t.getTradeType())) {
            BigDecimal newSl = ltp.add(step);
            if (newSl.compareTo(t.getSlPrice()) < 0) {
                t.setSlPrice(newSl);
            }
        }
    }
}
