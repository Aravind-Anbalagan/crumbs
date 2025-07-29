package com.crumbs.trade.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;

import com.crumbs.trade.entity.PricesIndex;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class BollingerBandsCalculator {

    public String createBand(List<PricesIndex> list) {
        List<BigDecimal> closingPrices = new ArrayList<>();

        /*// Add closing prices for the last 10 days
        closingPrices.add(new BigDecimal("23816.70"));
        closingPrices.add(new BigDecimal("23805.85"));
        closingPrices.add(new BigDecimal("23897.85"));
        closingPrices.add(new BigDecimal("24282.65"));
        closingPrices.add(new BigDecimal("24092.40"));
        closingPrices.add(new BigDecimal("23721.05"));
        closingPrices.add(new BigDecimal("23795.05"));
        closingPrices.add(new BigDecimal("23781.35"));
        closingPrices.add(new BigDecimal("23648.10"));
        closingPrices.add(new BigDecimal("23500.65"));*/

        for (PricesIndex pricesEq : list) {

			closingPrices.add(pricesEq.getClose());
		}

        int period = closingPrices.size(); // 10-day period
        BigDecimal multiplier = new BigDecimal("2"); // 2 standard deviations

        List<BollingerBand> bands = calculateBollingerBands(closingPrices, period, multiplier);

        
        BigDecimal[] val = { bands.get(0).getUpperBand(),
        		bands.get(0).getMiddleBand(),
        		bands.get(0).getLowerBand() };
		return Arrays.toString(val);
    }

    public static List<BollingerBand> calculateBollingerBands(List<BigDecimal> prices, int period, BigDecimal multiplier) {
        List<BollingerBand> bands = new ArrayList<>();
        
        for (int i = period - 1; i < prices.size(); i++) {
            BigDecimal sum = BigDecimal.ZERO;
            for (int j = i - period + 1; j <= i; j++) {
                sum = sum.add(prices.get(j));
            }

            BigDecimal middleBand = sum.divide(new BigDecimal(period), RoundingMode.HALF_UP);
            BigDecimal variance = BigDecimal.ZERO;

            for (int j = i - period + 1; j <= i; j++) {
                variance = variance.add(prices.get(j).subtract(middleBand).pow(2));
            }

            BigDecimal standardDeviation = BigDecimal.valueOf(Math.sqrt(variance.divide(new BigDecimal(period), RoundingMode.HALF_UP).doubleValue()));
            BigDecimal upperBand = middleBand.add(multiplier.multiply(standardDeviation));
            BigDecimal lowerBand = middleBand.subtract(multiplier.multiply(standardDeviation));

            bands.add(new BollingerBand(middleBand, upperBand, lowerBand));
        }

        return bands;
    }
}

class BollingerBand {
    private BigDecimal middleBand;
    private BigDecimal upperBand;
    private BigDecimal lowerBand;

    public BollingerBand(BigDecimal middleBand, BigDecimal upperBand, BigDecimal lowerBand) {
        this.middleBand = middleBand;
        this.upperBand = upperBand;
        this.lowerBand = lowerBand;
    }

    public BigDecimal getMiddleBand() {
        return middleBand;
    }

    public BigDecimal getUpperBand() {
        return upperBand;
    }

    public BigDecimal getLowerBand() {
        return lowerBand;
    }
}
