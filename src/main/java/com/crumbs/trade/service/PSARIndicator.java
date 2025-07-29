package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.stereotype.Service;

import com.crumbs.trade.dto.Candlestick;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;



@Service
public class PSARIndicator {

    // Constants for PSAR calculation
    private static final BigDecimal INITIAL_AF = new BigDecimal("0.02");
    private static final BigDecimal MAX_AF = new BigDecimal("0.2");

    public List<Candlestick> callPsar(List<Candlestick> candles) {
    	List<Candlestick> psarValues = new ArrayList<>();

		// Initial values for PSAR calculation
		BigDecimal af = new BigDecimal("0.02");
		BigDecimal maxAf = new BigDecimal("0.20");
		BigDecimal ep = candles.get(0).high;
		BigDecimal psar = candles.get(0).low;

		boolean uptrend = true;

		for (int i = 1; i < candles.size(); i++) {
			Candlestick candle = candles.get(i);
			Candlestick psarCandle = new Candlestick();

         
            
            if (uptrend) {
                psar = psar.add(af.multiply(ep.subtract(psar))).setScale(0, BigDecimal.ROUND_HALF_UP);

                // Adjust PSAR to handle sudden spikes
                if (candle.low.compareTo(psar) < 0) {
                    uptrend = false;
                    psar = ep;
                    af = new BigDecimal("0.02");
                    ep = candle.low;
                } else {
                    if (candle.high.compareTo(ep) > 0) {
                        ep = candle.high;
                        af = af.add(new BigDecimal("0.02")).min(maxAf);
                    }
                    // Ensure PSAR does not exceed current candle low
                    if (psar.compareTo(candle.low) > 0) {
                        psar = candle.low;
                    }
                }
            } else {
                psar = psar.subtract(af.multiply(psar.subtract(ep))).setScale(0, BigDecimal.ROUND_HALF_UP);

                // Adjust PSAR to handle sudden spikes
                if (candle.high.compareTo(psar) > 0) {
                    uptrend = true;
                    psar = ep;
                    af = new BigDecimal("0.02");
                    ep = candle.high;
                } else {
                    if (candle.low.compareTo(ep) < 0) {
                        ep = candle.low;
                        af = af.add(new BigDecimal("0.02")).min(maxAf);
                    }
                    // Ensure PSAR does not exceed current candle high
                    if (psar.compareTo(candle.high) < 0) {
                        psar = candle.high;
                    }
                }
            }
            psarCandle.psarPrice = psar.setScale(2, BigDecimal.ROUND_HALF_UP);
			//psarCandle.timeStamp = candle.timeStamp;
			//psarCandle.currentprice = candle.currentprice;
			psarCandle.high = candle.high;
			psarCandle.low = candle.low;
			psarCandle.open = candle.open;
			psarCandle.id = candle.id;
			// psarValues.add(psar.setScale(2, BigDecimal.ROUND_HALF_UP));
			if (psarCandle.psarPrice.compareTo(psarCandle.open) > 0) {
				psarCandle.signal ="SELL";
			} else if (psarCandle.psarPrice.compareTo(psarCandle.open) < 0) {
				psarCandle.signal ="BUY";
			}
			psarValues.add(psarCandle);
		}

		return psarValues;
    }


}