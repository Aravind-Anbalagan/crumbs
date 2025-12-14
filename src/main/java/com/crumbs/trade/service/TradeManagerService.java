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

    private final TradeExecutionRepo repo;

    // ENTRY
    @Transactional
    public void handleSignal(
            String symbol,
            String timeframe,
            LevelAnalysisResult analysis) {

        if (analysis == null) return;
        if (!"BUY_ZONE".equals(analysis.getZone())
                && !"SELL_ZONE".equals(analysis.getZone())) return;

        Optional<TradeExecution> open =
                repo.findFirstBySymbolAndTimeframeAndStatus(
                        symbol, timeframe, "OPEN");

        if (open.isPresent()) return;

        TradeExecution t = new TradeExecution();
        t.setSymbol(symbol);
        t.setTimeframe(timeframe);
        t.setTradeType(
                "BUY_ZONE".equals(analysis.getZone()) ? "BUY" : "SELL");
        t.setStatus("OPEN");
        t.setEntryPrice(analysis.getCurrentPrice());
        t.setEntryTime(LocalDateTime.now());

        t.setTargetPrice(calcTarget(analysis));
        t.setSlPrice(calcSL(analysis));

        Level ref = "BUY".equals(t.getTradeType())
                ? analysis.getNearestSupport()
                : analysis.getNearestResistance();

        t.setLevelValue(ref.getLevelValue());
        t.setMethod(ref.getMethod());
        t.setStrength(ref.getStrength());
        t.setExplanation(analysis.getExplanation());

        repo.save(t);
    }

    // EXIT
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

    private void close(TradeExecution t, BigDecimal price, String reason) {
        t.setStatus("CLOSED");
        t.setExitPrice(price);
        t.setExitTime(LocalDateTime.now());
        t.setExitReason(reason);
        repo.save(t);
    }

    private BigDecimal calcTarget(LevelAnalysisResult a) {
        return a.getCurrentPrice().add(new BigDecimal("30"));
    }

    private BigDecimal calcSL(LevelAnalysisResult a) {
        return a.getCurrentPrice().subtract(new BigDecimal("15"));
    }
}
