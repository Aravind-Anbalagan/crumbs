package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.crumbs.trade.entity.PricesHeikinAshiIndex;
import com.crumbs.trade.entity.PricesHeikinAshiMcx;
import com.crumbs.trade.entity.PricesHeikinAshiNifty;
import com.crumbs.trade.entity.PricesIndex;
import com.crumbs.trade.entity.PricesMcx;
import com.crumbs.trade.entity.PricesNifty;
import com.crumbs.trade.repo.PriceHeikinashiIndexRepo;
import com.crumbs.trade.repo.PriceHeikinashiMcxRepo;
import com.crumbs.trade.repo.PriceHeikinashiNiftyRepo;


@Service
public class HeikinAshiCalculator {

	@Autowired
	PriceHeikinashiNiftyRepo priceHeikinashiNiftyRepo;
	
	@Autowired
	PriceHeikinashiMcxRepo priceHeikinashiMcxRepo;
	
	@Autowired
	PriceHeikinashiIndexRepo priceHeikinashiIndexRepo;
	
    public static class Candle {
        BigDecimal open;
        BigDecimal high;
        BigDecimal low;
        BigDecimal close;
        BigDecimal currentprice;
        String timeStamp;

        public Candle(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,String timeStamp,BigDecimal currentprice) {
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.timeStamp = timeStamp;
            this.currentprice = currentprice;
        }
    }

	public void createCandle(List<PricesMcx> mcxcandleList, List<PricesNifty> niftycandleList,List<PricesIndex> indexcandleList, String type, String name, String timeFrame) {

		List<Candle> candles = new ArrayList<>();

		if (type.equalsIgnoreCase("NFO")) {
			priceHeikinashiNiftyRepo.deleteAll();
			niftycandleList.stream().forEach(price -> {
				candles.add(new Candle(price.getOpen(), price.getHigh(), price.getLow(), price.getClose(),
						price.getTimestamp(),price.getCurrentprice()));

			});
		} else if (type.equalsIgnoreCase("MCX")) {
			priceHeikinashiMcxRepo.deleteAll();
			mcxcandleList.stream().forEach(price -> {
				candles.add(new Candle(price.getOpen(), price.getHigh(), price.getLow(), price.getClose(),
						price.getTimestamp(),price.getCurrentprice()));

			});
		} else if (type.equalsIgnoreCase("INDEX")) {
			priceHeikinashiIndexRepo.deleteByNameAndTimeframe(name,timeFrame);
			indexcandleList.stream().forEach(price -> {
				candles.add(new Candle(price.getOpen(), price.getHigh(), price.getLow(), price.getClose(),
						price.getTimestamp(),price.getCurrentprice()));

			});
		}

		List<Candle> heikinAshiCandles = calculateHeikinAshi(candles);

		for (Candle haCandle : heikinAshiCandles) {
			//System.out.println("Open: " + haCandle.open + ", High: " + haCandle.high + ", Low: " + haCandle.low
				//	+ ", Close: " + haCandle.close);
			if (type.equalsIgnoreCase("NFO")) {
				saveNiftyPrice(haCandle,type);
			}
			else if (type.equalsIgnoreCase("MCX")) {
				saveMcxPrice(haCandle,type);
			}
			else if (type.equalsIgnoreCase("INDEX")) {
				saveIndexPrice(haCandle,type,timeFrame,name);
			}
			
		}
		
	}
	
	

	public void saveIndexPrice(Candle candle,String type, String timeFrame, String name)
	{
		PricesHeikinAshiIndex prices = new PricesHeikinAshiIndex();
		prices.setHigh(candle.high);
		prices.setLow(candle.low);
		prices.setClose(candle.close);
		prices.setOpen(candle.open);
		prices.setVolume(null);
		prices.setRange(null);
		prices.setName(name);
		prices.setTimestamp(candle.timeStamp);
		prices.setType(getPriceType(candle.open, candle.close));
		prices.setTimeframe(timeFrame);
		priceHeikinashiIndexRepo.save(prices);
	}
	public void saveMcxPrice(Candle candle,String type)
	{
		PricesHeikinAshiMcx prices = new PricesHeikinAshiMcx();
		prices.setHigh(candle.high);
		prices.setLow(candle.low);
		prices.setClose(candle.close);
		prices.setOpen(candle.open);
		prices.setVolume(null);
		prices.setRange(null);
		prices.setName(type);
		prices.setTimestamp(candle.timeStamp);
		prices.setType(getPriceType(candle.open, candle.close));
		prices.setTimeframe(null);
		priceHeikinashiMcxRepo.save(prices);
	}
	public void saveNiftyPrice(Candle candle,String type)
	{
		PricesHeikinAshiNifty prices = new PricesHeikinAshiNifty();
		prices.setHigh(candle.high);
		prices.setLow(candle.low);
		prices.setClose(candle.close);
		prices.setOpen(candle.open);
		prices.setVolume(null);
		prices.setRange(null);
		prices.setName("NIFTY");
		prices.setTimestamp(candle.timeStamp);
		prices.setType(getPriceType(candle.open, candle.close));
		prices.setTimeframe(null);
		priceHeikinashiNiftyRepo.save(prices);
	}
	public String getPriceType(BigDecimal open, BigDecimal close) {
		if (close.compareTo(open) >= 1) {
			return "BUY";
		} else {
			return "SELL";
		}
	}
    public static List<Candle> calculateHeikinAshi(List<Candle> candles) {
        List<Candle> heikinAshiCandles = new ArrayList<>();

        // Initial HA-Open and HA-Close should be based on the first real candle
        BigDecimal haOpen = (candles.get(0).open.add(candles.get(0).close)).divide(BigDecimal.valueOf(2), BigDecimal.ROUND_HALF_UP);

        for (int i = 0; i < candles.size(); i++) {
            Candle candle = candles.get(i);

            BigDecimal haClose = (candle.open.add(candle.high).add(candle.low).add(candle.close)).divide(BigDecimal.valueOf(4), BigDecimal.ROUND_HALF_UP);

            if (i > 0) {
                Candle prevHaCandle = heikinAshiCandles.get(i - 1);
                haOpen = (prevHaCandle.open.add(prevHaCandle.close)).divide(BigDecimal.valueOf(2), BigDecimal.ROUND_HALF_UP);
            }

            BigDecimal haHigh = candle.high.max(haOpen).max(haClose);
            BigDecimal haLow = candle.low.min(haOpen).min(haClose);

            heikinAshiCandles.add(new Candle(haOpen, haHigh, haLow, haClose,candle.timeStamp,candle.currentprice));
        }

        return heikinAshiCandles;
    }
}