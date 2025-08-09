package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;



import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.crumbs.trade.entity.PSARIndex;
import com.crumbs.trade.entity.PSARMcx;
import com.crumbs.trade.entity.PSARNifty;
import com.crumbs.trade.entity.PricesIndex;
import com.crumbs.trade.entity.PricesMcx;
import com.crumbs.trade.entity.PricesNifty;
import com.crumbs.trade.repo.PsarIndexRepo;
import com.crumbs.trade.repo.PsarMcxRepo;
import com.crumbs.trade.repo.PsarNiftyRepo;

import jakarta.transaction.Transactional;


@Service
public class PSARCalculator {

	@Autowired
	PsarNiftyRepo psarNiftyRepo;
	
	@Autowired
	PsarMcxRepo psarMcxRepo;
	
	@Autowired
	PsarIndexRepo psarIndexRepo;
    
	public static class Candle {
		
		public Candle() {
			
			// TODO Auto-generated constructor stub
		}
        BigDecimal open;
        BigDecimal high;
        BigDecimal low;
        BigDecimal close;
        String timeStamp;
        BigDecimal currentprice;
        BigDecimal psarPrice;
        String name;
        
		public Candle(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, String timeStamp,
				BigDecimal currentprice, BigDecimal psarPrice,String name) {
			this.open = open;
			this.high = high;
			this.low = low;
			this.close = close;
			this.timeStamp = timeStamp;
			this.currentprice = currentprice;
			this.psarPrice = psarPrice;
			this.name =name;
		}
    }

    public  void createPoints(List<PricesMcx> mcxcandleList, List<PricesNifty> niftycandleList,List<PricesIndex> indexcandleList,String type, String name, String timeFrame) {
    
        List<Candle> candles = new ArrayList<>();

		if (type.equalsIgnoreCase("NFO")) {
			psarNiftyRepo.deleteAll();
			niftycandleList.stream().forEach(price -> {
				candles.add(new Candle(price.getOpen(), price.getHigh(), price.getLow(), price.getClose(),
						price.getTimestamp(),price.getCurrentprice(), new BigDecimal("0"),price.getName()));

			});
		} else if (type.equalsIgnoreCase("MCX")) {
			psarMcxRepo.deleteAll();
			mcxcandleList.stream().forEach(price -> {
				candles.add(new Candle(price.getOpen(), price.getHigh(), price.getLow(), price.getClose(),
						price.getTimestamp(),price.getCurrentprice(),new BigDecimal("0"),price.getName()));

			});
		}
		else if (type.equalsIgnoreCase("INDEX")) {
			psarIndexRepo.deleteByNameAndTimeframe(name, timeFrame);
			indexcandleList.stream().forEach(price -> {
				candles.add(new Candle(price.getOpen(), price.getHigh(), price.getLow(), price.getClose(),
						price.getTimestamp(),price.getCurrentprice(),new BigDecimal("0"),price.getName()));

			});
		}
		
        // Calculate PSAR values
        List<Candle> psarValues = calculatePSAR(candles);

        // Print PSAR values,
        for (Candle valueCandle : psarValues) {
        	
        	if (type.equalsIgnoreCase("NFO")) {
        		saveNiftyPrice(valueCandle);
			}
			else if (type.equalsIgnoreCase("MCX")) {
				saveMcxPrice(valueCandle);
			}
			else if (type.equalsIgnoreCase("INDEX")) {
				saveIndexPrice(valueCandle,timeFrame);
			}
        	
        }
        
    }
    
    @Transactional
    private void saveIndexPrice(Candle valueCandle,String timeFrame) {
		PSARIndex psarIndex = new PSARIndex();
		psarIndex.setCurrentprice(valueCandle.currentprice);
		psarIndex.setPsarPrice(valueCandle.psarPrice);
		psarIndex.setTimestamp(valueCandle.timeStamp);
		psarIndex.setHigh(valueCandle.high);
		psarIndex.setLow(valueCandle.low);
		psarIndex.setName(valueCandle.name);
		psarIndex.setTimeframe(timeFrame);
		if (valueCandle.psarPrice.compareTo(valueCandle.currentprice) > 0) {
			psarIndex.setType("SELL");
		} else if (valueCandle.psarPrice.compareTo(valueCandle.currentprice) < 0) {
			psarIndex.setType("BUY");
		}
		psarIndexRepo.save(psarIndex);		
	}


    @Transactional
    private void saveMcxPrice(Candle valueCandle) {
		PSARMcx psarMcx = new PSARMcx();
		//psarMcx.setCurrentprice(valueCandle.currentprice);
		psarMcx.setPsarPrice(valueCandle.psarPrice);
		psarMcx.setTimestamp(valueCandle.timeStamp);
		psarMcx.setHigh(valueCandle.high);
		psarMcx.setLow(valueCandle.low);
		psarMcx.setName(valueCandle.name);
		psarMcx.setOpen(valueCandle.open);
		if (valueCandle.psarPrice.compareTo(valueCandle.open) > 0) {
			psarMcx.setType("SELL");
		} else if (valueCandle.psarPrice.compareTo(valueCandle.open) < 0) {
			psarMcx.setType("BUY");
		}
		psarMcxRepo.save(psarMcx);		
	}

    @Transactional
	public void saveNiftyPrice(Candle valueCandle) {
		PSARNifty psarNifty = new PSARNifty();
		//psarNifty.setCurrentprice(valueCandle.currentprice);
		psarNifty.setPsarPrice(valueCandle.psarPrice);
		psarNifty.setTimestamp(valueCandle.timeStamp);
		psarNifty.setHigh(valueCandle.high);
		psarNifty.setLow(valueCandle.low);
		psarNifty.setName(valueCandle.name);
		psarNifty.setOpen(valueCandle.open);
		if (valueCandle.psarPrice.compareTo(valueCandle.open) > 0) {
			psarNifty.setType("SELL");
		} else if (valueCandle.psarPrice.compareTo(valueCandle.open) < 0) {
			psarNifty.setType("BUY");
		}
		psarNiftyRepo.save(psarNifty);
	}
    
   
	public static List<Candle> calculatePSAR(List<Candle> candles) {
		List<Candle> psarValues = new ArrayList<>();

		// Initial values for PSAR calculation
		BigDecimal af = new BigDecimal("0.02");
		BigDecimal maxAf = new BigDecimal("0.20");
		BigDecimal ep = candles.get(0).high;
		BigDecimal psar = candles.get(0).low;

		boolean uptrend = true;

		for (int i = 1; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            Candle psarCandle = new Candle();

         
            
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
			psarCandle.timeStamp = candle.timeStamp;
			psarCandle.currentprice = candle.currentprice;
			psarCandle.high = candle.high;
			psarCandle.low = candle.low;
			psarCandle.open = candle.open;
			// psarValues.add(psar.setScale(2, BigDecimal.ROUND_HALF_UP));
			psarValues.add(psarCandle);
		}

		return psarValues;
	}
}