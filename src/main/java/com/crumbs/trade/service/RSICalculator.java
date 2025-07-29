package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
        BigDecimal[] gains = new BigDecimal[prices.length];
        BigDecimal[] losses = new BigDecimal[prices.length];

        try
        {
        	  
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

              BigDecimal avgGain = Arrays.stream(Arrays.copyOfRange(gains, 1, period + 1))
                  .filter(gain -> gain != null)
                  .reduce(BigDecimal.ZERO, BigDecimal::add)
                  .divide(new BigDecimal(period), RoundingMode.HALF_UP);

              BigDecimal avgLoss = Arrays.stream(Arrays.copyOfRange(losses, 1, period + 1))
                  .filter(loss -> loss != null)
                  .reduce(BigDecimal.ZERO, BigDecimal::add)
                  .divide(new BigDecimal(period), RoundingMode.HALF_UP);

              for (int i = period + 1; i < prices.length; i++) {
                  avgGain = ((avgGain.multiply(new BigDecimal(period - 1))).add(gains[i] != null ? gains[i] : BigDecimal.ZERO))
                      .divide(new BigDecimal(period), RoundingMode.HALF_UP);
                  avgLoss = ((avgLoss.multiply(new BigDecimal(period - 1))).add(losses[i] != null ? losses[i] : BigDecimal.ZERO))
                      .divide(new BigDecimal(period), RoundingMode.HALF_UP);
              }

              BigDecimal rs = avgGain.divide(avgLoss, RoundingMode.HALF_UP);
              BigDecimal rsi = BigDecimal.valueOf(100).subtract(
                  BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), RoundingMode.HALF_UP)
              );

              return rsi.setScale(2, RoundingMode.HALF_UP);
        }
        catch (Exception e) {
			// TODO: handle exception
        	logger.error("Error while calculate RSI : " + e.getMessage() );
		}
		return null;
       
    }
}
