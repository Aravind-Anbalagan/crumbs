package com.crumbs.trade.service;


import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.crumbs.trade.entity.PricesIndex;

import java.math.BigDecimal;
import java.util.ArrayList;

@Service
public class MovingAverageCalculator {
	Logger logger = LoggerFactory.getLogger(MovingAverageCalculator.class);
	public BigDecimal getMovingAverage(List<PricesIndex> list,int period) {
        List<BigDecimal> closingPrices = new ArrayList<>();

        // Add closing prices to the list (ensure you have at least 200 prices)
        
		for (PricesIndex pricesEq : list) {

			closingPrices.add(pricesEq.getClose());
		}


   
        BigDecimal movingAverage = calculateMovingAverage(closingPrices, period);
        if (movingAverage != null) {
        	//logger.info("200-Day Moving Average: " + movingAverage);
           
        } else {
        	logger.error("Not enough data to calculate the 200-day moving average.");
        }
		return movingAverage;
    }

    public static BigDecimal calculateMovingAverage(List<BigDecimal> prices, int period) {
    	  if (prices.size() < period) {
              return null; // Not enough data points
          }

          BigDecimal sum = BigDecimal.ZERO;
          for (int i = prices.size() - period; i < prices.size(); i++) {
              sum = sum.add(prices.get(i));
          }
          return sum.divide(BigDecimal.valueOf(period), BigDecimal.ROUND_HALF_UP);
    }
}
