package com.crumbs.trade.service;

import com.crumbs.trade.dto.Candlestick;
import com.crumbs.trade.entity.IntradayTrade;
import com.crumbs.trade.repo.IntradayTradeRepo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class IntradayExecutionService {

    // ✅ FIX #1: Replaced all System.out.println with proper logger
    private static final Logger logger = LoggerFactory.getLogger(IntradayExecutionService.class);

    private final IntradayTradeRepo tradeRepo;
    private final SuperTrendIndicator superTrendIndicator;
    private final VWAPIndicator vwapIndicator;

    public void process(String symbol, String name, String exchange, List<Candlestick> candles) {

        // Need at least 3 candles: last, prev, prevPrev (for fresh crossover detection)
        if (candles == null || candles.size() < 3) return;

        // 🔹 Apply indicators
        candles = superTrendIndicator.calculateSuperTrend(candles);
        candles = vwapIndicator.calculateVWAP(candles);

        Candlestick last     = candles.get(candles.size() - 1);
        Candlestick prev     = candles.get(candles.size() - 2);
        Candlestick prevPrev = candles.get(candles.size() - 3); // ✅ FIX #6: for crossover freshness

        BigDecimal price = last.getClose();
        BigDecimal vwap  = last.getVwap();

        if (price == null || vwap == null) return;

        String stSignal     = last.getSuperTrendSignal();
        String prevStSignal = prev.getSuperTrendSignal();
        String prevPrevSt   = prevPrev.getSuperTrendSignal(); // ✅ FIX #6

        String vwapSignal = price.compareTo(vwap) > 0 ? "BUY" : "SELL";

        // 🔥 Determine alignment
        String state;

        if ("BUY".equals(stSignal) && "BUY".equals(vwapSignal)) {
            state = "ALIGNED_BUY";
        } else if ("SELL".equals(stSignal) && "SELL".equals(vwapSignal)) {
            state = "ALIGNED_SELL";
        } else {
            state = "NOT_ALIGNED";
        }

        // ✅ FIX #3: Defensive handling — warn if multiple OPEN trades exist for same symbol
        //            DB unique constraint on (symbol, status='OPEN') is the real fix,
        //            but we guard here too so we never act on a stale duplicate
        List<IntradayTrade> openTrades = tradeRepo.findAllBySymbolAndStatus(symbol, "OPEN");

        if (openTrades.size() > 1) {
            logger.warn("Multiple OPEN trades found for {} — skipping tick to avoid duplicate actions. " +
                        "Check DB for data integrity issue.", symbol);
            return;
        }

        IntradayTrade trade = openTrades.isEmpty() ? null : openTrades.get(0);

        // =========================
        // 🟢 ENTRY (fresh crossover + 2-candle confirmation)
        // =========================
        if (trade == null) {

            // ✅ FIX #6: 2-candle confirmation — last and prev must agree on direction
            if (!stSignal.equals(prevStSignal)) return;

            // ✅ FIX #6: Fresh crossover guard — prevPrev must differ from prev,
            //            meaning the crossover actually happened at the prev candle.
            //            Without this, a stock bullish for 10 candles would re-enter
            //            immediately after a trade is closed.
            if (prevStSignal.equals(prevPrevSt)) {
                logger.debug("{} — ST signal stale (no fresh crossover), skipping entry", symbol);
                return;
            }

            if ("ALIGNED_BUY".equals(state)) {
                createTrade(symbol, name, exchange, price, "BUY", "ST+VWAP_CONFIRMED");
            } else if ("ALIGNED_SELL".equals(state)) {
                createTrade(symbol, name, exchange, price, "SELL", "ST+VWAP_CONFIRMED");
            }

            return;
        }

        // =========================
        // 🔴 EXIT LOGIC
        // =========================
        if ("BUY".equals(trade.getPosition())) {

            // 1. SL → previous candle low
            if (price.compareTo(prev.getLow()) < 0) {
                closeTrade(trade, price, "SL_HIT");
                return;
            }

            // 2. FULL REVERSAL → ST + VWAP
            if ("SELL".equals(stSignal) && price.compareTo(vwap) < 0) {
                closeTrade(trade, price, "FULL_REVERSAL");
                return;
            }

            return; // HOLD

        } else if ("SELL".equals(trade.getPosition())) {

            // 1. SL → previous candle high
            if (price.compareTo(prev.getHigh()) > 0) {
                closeTrade(trade, price, "SL_HIT");
                return;
            }

            // 2. FULL REVERSAL
            if ("BUY".equals(stSignal) && price.compareTo(vwap) > 0) {
                closeTrade(trade, price, "FULL_REVERSAL");
                return;
            }

            return; // HOLD
        }
    }

    /**
     * ✅ FIX #2: Called by scheduler at 3:20 PM to force-close any remaining OPEN trade
     */
    public void squareOff(IntradayTrade trade, BigDecimal exitPrice, String reason) {
        closeTrade(trade, exitPrice, reason);
    }

    // ✅ FIX #1: logger.info instead of System.out.println
    private void createTrade(String symbol, String name, String exchange,
                             BigDecimal price, String position, String reason) {

        IntradayTrade trade = new IntradayTrade();

        trade.setSymbol(symbol);
        trade.setName(name);
        trade.setExchange(exchange);

        trade.setPosition(position);
        trade.setEntryPrice(price);
        trade.setEntryTime(LocalDateTime.now());

        trade.setStatus("OPEN");
        trade.setEntryReason(reason);

        trade.setCreatedAt(LocalDateTime.now());
        trade.setUpdatedAt(LocalDateTime.now());

        tradeRepo.save(trade);

        // ✅ FIX #1: was System.out.println
        logger.info("ENTER {} | position={} | price={} | reason={}", symbol, position, price, reason);
    }

    // ✅ FIX #1: logger.info instead of System.out.println
    private void closeTrade(IntradayTrade trade, BigDecimal price, String reason) {

        trade.setExitPrice(price);
        trade.setExitTime(LocalDateTime.now());
        trade.setStatus("CLOSED");

        BigDecimal pnl;

        if ("BUY".equals(trade.getPosition())) {
            pnl = price.subtract(trade.getEntryPrice());
        } else {
            pnl = trade.getEntryPrice().subtract(price);
        }

        trade.setPnl(pnl);
        trade.setExitReason(reason);
        trade.setUpdatedAt(LocalDateTime.now());

        tradeRepo.save(trade);

        // ✅ FIX #1: was System.out.println
        logger.info("EXIT {} | pnl={} | reason={}", trade.getSymbol(), pnl, reason);
    }
}