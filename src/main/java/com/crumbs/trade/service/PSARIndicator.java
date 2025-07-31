package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.crumbs.trade.dto.Candlestick;

@Service
public class PSARIndicator {

    private static final BigDecimal INITIAL_AF = new BigDecimal("0.02");
    private static final BigDecimal MAX_AF = new BigDecimal("0.20");
    private static final BigDecimal AF_INCREMENT = new BigDecimal("0.02");
    private static final MathContext MC = new MathContext(8, RoundingMode.HALF_UP);
    private static final int RESULT_SCALE = 4;

    public List<Candlestick> calculatePSAR(List<Candlestick> candles) {
        if (candles == null || candles.size() < 2) {
            throw new IllegalArgumentException("Minimum 2 candles required");
        }

        List<Candlestick> results = new ArrayList<>();
        String currentSignal = null;
        boolean currentTrend = determineInitialTrend(candles);

        // Initialize first candle
        results.add(createCandleWithPSAR(candles.get(0), null, null));

        // PSAR calculation variables
        BigDecimal af = INITIAL_AF;
        BigDecimal ep = currentTrend ? candles.get(0).getHigh() : candles.get(0).getLow();
        BigDecimal psar = currentTrend ? candles.get(0).getLow() : candles.get(0).getHigh();

        for (int i = 1; i < candles.size(); i++) {
            Candlestick candle = candles.get(i);
            BigDecimal previousPSAR = psar;
            boolean trendReversed = false;

            // Calculate new PSAR
            if (currentTrend) {
                psar = psar.add(af.multiply(ep.subtract(psar))).setScale(RESULT_SCALE, RoundingMode.HALF_UP);
                psar = psar.min(candles.get(i-1).getLow()); // Boundary condition
                
                if (candle.getLow().compareTo(psar) < 0) {
                    currentTrend = false;
                    psar = ep;
                    af = INITIAL_AF;
                    ep = candle.getLow();
                    trendReversed = true;
                } else if (candle.getHigh().compareTo(ep) > 0) {
                    ep = candle.getHigh();
                    af = af.add(AF_INCREMENT).min(MAX_AF);
                }
            } else {
                psar = psar.subtract(af.multiply(psar.subtract(ep))).setScale(RESULT_SCALE, RoundingMode.HALF_UP);
                psar = psar.max(candles.get(i-1).getHigh()); // Boundary condition
                
                if (candle.getHigh().compareTo(psar) > 0) {
                    currentTrend = true;
                    psar = ep;
                    af = INITIAL_AF;
                    ep = candle.getHigh();
                    trendReversed = true;
                } else if (candle.getLow().compareTo(ep) < 0) {
                    ep = candle.getLow();
                    af = af.add(AF_INCREMENT).min(MAX_AF);
                }
            }

            // Determine signal - only change when trend reverses
            String newSignal = currentTrend ? "BUY" : "SELL";
            if (currentSignal == null || trendReversed) {
                currentSignal = newSignal;
            }

            results.add(createCandleWithPSAR(candle, psar, currentSignal));
        }

        return results;
    }

    private boolean determineInitialTrend(List<Candlestick> candles) {
        // Simple initial trend detection
        return candles.get(1).getHigh().compareTo(candles.get(0).getHigh()) > 0;
    }

    private Candlestick createCandleWithPSAR(Candlestick source, BigDecimal psar, String signal) {
        Candlestick result = new Candlestick();
        result.setId(source.getId());
        result.setOpen(source.getOpen());
        result.setHigh(source.getHigh());
        result.setLow(source.getLow());
        result.setClose(source.getClose());
        result.setPsarPrice(psar);
        result.setSignal(signal);
        return result;
    }
}