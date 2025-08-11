package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.crumbs.trade.entity.PricesIndex;



@Service
public class RSICalculator {
	Logger logger = LoggerFactory.getLogger(RSICalculator.class);
	public BigDecimal getRSIData(List<PricesIndex> list) {
		
		int period = 14;
		List<PricesIndex> pricesList = new ArrayList<>(list);
		Collections.reverse(pricesList);
		BigDecimal closePrice[] = new BigDecimal[15];
		int i = 0;
		for (PricesIndex pricesEq : pricesList) {

			closePrice[i] = pricesEq.getClose();
			i++;
		}

		BigDecimal rsi = calculateRSI(closePrice, period);
		//System.out.println("RSI: " + rsi);
		return rsi;
	}
	
	public BigDecimal calculateRSI(BigDecimal[] prices, int period) {
	    try {
	        // Step 1: Remove nulls
	        prices = Arrays.stream(prices)
	                .filter(Objects::nonNull)
	                .toArray(BigDecimal[]::new);

	        // Step 2: Validate
	        if (prices.length <= period) {
	            logger.warn("Not enough data to calculate RSI. Needed: {}, Found: {}", period, prices.length);
	            return null;
	        }

	        BigDecimal[] gains = new BigDecimal[prices.length];
	        BigDecimal[] losses = new BigDecimal[prices.length];

	        // Step 3: Calculate gains and losses
	        for (int i = 1; i < prices.length; i++) {
	            BigDecimal change = prices[i].subtract(prices[i - 1]);
	            if (change.compareTo(BigDecimal.ZERO) > 0) {
	                gains[i] = change;
	                losses[i] = BigDecimal.ZERO;
	            } else {
	                gains[i] = BigDecimal.ZERO;
	                losses[i] = change.abs();
	            }
	        }

	        // Step 4: Initial average gain/loss
	        BigDecimal avgGain = BigDecimal.ZERO;
	        BigDecimal avgLoss = BigDecimal.ZERO;
	        for (int i = 1; i <= period; i++) {
	            avgGain = avgGain.add(gains[i]);
	            avgLoss = avgLoss.add(losses[i]);
	        }
	        avgGain = avgGain.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
	        avgLoss = avgLoss.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);

	        // Step 5: Smooth averages until the last candle
	        for (int i = period + 1; i < prices.length; i++) {
	            avgGain = (avgGain.multiply(BigDecimal.valueOf(period - 1))
	                    .add(gains[i]))
	                    .divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
	            avgLoss = (avgLoss.multiply(BigDecimal.valueOf(period - 1))
	                    .add(losses[i]))
	                    .divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
	        }

	        // Step 6: Handle division by zero (no losses case)
	        if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
	            return BigDecimal.valueOf(100).setScale(2, RoundingMode.HALF_UP);
	        }

	        // Step 7: Calculate RSI
	        BigDecimal rs = avgGain.divide(avgLoss, 8, RoundingMode.HALF_UP);
	        BigDecimal rsi = BigDecimal.valueOf(100)
	                .subtract(BigDecimal.valueOf(100)
	                .divide(BigDecimal.ONE.add(rs), 8, RoundingMode.HALF_UP));

	        return rsi.setScale(2, RoundingMode.HALF_UP);
	    }
	    catch (Exception e) {
	        logger.error("Error while calculating RSI: {}", e.getMessage(), e);
	        return null;
	    }
	}


}
