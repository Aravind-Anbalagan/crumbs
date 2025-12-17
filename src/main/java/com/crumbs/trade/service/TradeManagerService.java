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

    /* ==============================
       CONFIG FLAGS (ON / OFF)
       ============================== */

    private static final boolean ENABLE_PRICE_ACTION = false;
    private static final boolean ENABLE_FIBO = true;

    private static final boolean ENABLE_TRAILING_SL = false;

    /* ==============================
       RISK CONFIG
       ============================== */

    private static final BigDecimal TARGET_POINTS = new BigDecimal("30");
    private static final BigDecimal SL_POINTS = new BigDecimal("10");
    private static final BigDecimal TRAIL_SL_STEP = new BigDecimal("10");

    private static final int COOLDOWN_MINUTES = 5;

    /* ==============================
       DEPENDENCY
       ============================== */

    private final TradeExecutionRepo repo;

    /* ==============================
       ENTRY LOGIC
       ============================== */

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

        t.setTargetPrice(calcTarget(analysis.getCurrentPrice(), tradeType));
        t.setSlPrice(calcSL(analysis.getCurrentPrice(), tradeType));

        t.setLevelValue(ref.getLevelValue());
        t.setMethod(ref.getMethod());
        t.setStrength(ref.getStrength());
        t.setExplanation(analysis.getExplanation());

        repo.save(t);
    }

    /* ==============================
       EXIT + TRAILING SL
       ============================== */

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

    /* ==============================
       CLOSE + PnL
       ============================== */

    private void close(TradeExecution t, BigDecimal exitPrice, String reason) {

        t.setStatus("CLOSED");
        t.setExitPrice(exitPrice);
        t.setExitTime(LocalDateTime.now());
        t.setExitReason(reason);

        // -----------------------
        // ✅ PnL CALCULATION
        // -----------------------
        BigDecimal pnl;

        if ("BUY".equals(t.getTradeType())) {
            pnl = exitPrice.subtract(t.getEntryPrice());
        } else {
            pnl = t.getEntryPrice().subtract(exitPrice);
        }

        t.setPnl(pnl);

        repo.save(t);
    }


    /* ==============================
       HELPERS
       ============================== */

    public boolean isMethodAllowed(String method) {
        return ("PRICE_ACTION".equals(method) && ENABLE_PRICE_ACTION)
                || ("FIBO".equals(method) && ENABLE_FIBO);
    }

    private BigDecimal calcTarget(BigDecimal price, String tradeType) {
        return "BUY".equals(tradeType)
                ? price.add(TARGET_POINTS)
                : price.subtract(TARGET_POINTS);
    }

    private BigDecimal calcSL(BigDecimal price, String tradeType) {
        return "BUY".equals(tradeType)
                ? price.subtract(SL_POINTS)
                : price.add(SL_POINTS);
    }

    private void applyTrailingSL(TradeExecution t, BigDecimal ltp) {

        if ("BUY".equals(t.getTradeType())) {
            BigDecimal newSl = ltp.subtract(TRAIL_SL_STEP);
            if (newSl.compareTo(t.getSlPrice()) > 0) {
                t.setSlPrice(newSl);
            }
        }

        if ("SELL".equals(t.getTradeType())) {
            BigDecimal newSl = ltp.add(TRAIL_SL_STEP);
            if (newSl.compareTo(t.getSlPrice()) < 0) {
                t.setSlPrice(newSl);
            }
        }
    }
}
