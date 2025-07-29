package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.crumbs.trade.dto.Candlestick;


@Service
public class HeikinAshiIndicator {

    @Autowired
    private ChartService chartService;

   

    // Method to calculate the Heikin-Ashi candles and return them as a list
    public List<Candlestick> calculateHeikinAshiCandles(List<Candlestick> candles) {
        List<Candlestick> heikinAshiCandles = new ArrayList<>();

        if(candles!=null && !candles.isEmpty())
        {
        	// Initial HA-Open and HA-Close should be based on the first real candle
            BigDecimal haOpen = (candles.get(0).open.add(candles.get(0).close)).divide(BigDecimal.valueOf(2), BigDecimal.ROUND_HALF_UP);

            for (int i = 0; i < candles.size(); i++) {
            	Candlestick candle = candles.get(i);

                BigDecimal haClose = (candle.open.add(candle.high).add(candle.low).add(candle.close)).divide(BigDecimal.valueOf(4), BigDecimal.ROUND_HALF_UP);

                if (i > 0) {
                	Candlestick prevHaCandle = heikinAshiCandles.get(i - 1);
                    haOpen = (prevHaCandle.open.add(prevHaCandle.close)).divide(BigDecimal.valueOf(2), BigDecimal.ROUND_HALF_UP);
                }

                BigDecimal haHigh = candle.high.max(haOpen).max(haClose);
                BigDecimal haLow = candle.low.min(haOpen).min(haClose);
    			if (haClose.compareTo(haOpen) >= 1) {
    				candle.signal = "BUY";
    				if(haOpen.compareTo(haLow)==0)
    				{
    					candle.candleType ="OPEN-LOW-SAME";
    				}
    			} else {
    				candle.signal = "SELL";
    				if(haOpen.compareTo(haHigh)==0)
    				{
    					candle.candleType ="OPEN-HIGH-SAME";
    				}
    			}
                heikinAshiCandles.add(new Candlestick(haOpen, haHigh, haLow, haClose, candle.id, candle.signal,null,candle.candleType));
            }

            
        }
        return heikinAshiCandles;
    }

    // Method to call the Heikin-Ashi calculation and return the result list
    public List<Candlestick> callHeikinAshi(List<Candlestick> candles) {
        return calculateHeikinAshiCandles(candles);
    }
}

