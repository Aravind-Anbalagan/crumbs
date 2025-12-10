package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.CombinedChartPoint;
import com.crumbs.trade.dto.CombinedChartResponse;
import com.crumbs.trade.dto.StraddlePremiumDto;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.StraddleIntraday;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.StraddleIntradayRepo;
import com.crumbs.trade.repo.StrategyRepo;

@Service
public class StraddleIntradayService {
	Logger logger = LoggerFactory.getLogger(StraddleIntradayService.class);

    @Autowired PredictionService predictionService;
    @Autowired ChartService chartService;
    @Autowired AngelOneService angelOneService;
    @Autowired AngelOne angelOne;
    @Autowired IndexesRepo indexesRepo;
    @Autowired StrategyRepo strategyRepo;
    @Autowired StraddleIntradayRepo straddleIntradayRepo;

   
    /*
     * Get Combine Straddle Premium
     */
    public void getCombineStraddlePremium(String name)
    {
    	StraddlePremiumDto straddlePremiumDto = new StraddlePremiumDto();
    	try
    	{
    		SmartConnect smartconnect = angelOne.signIn();
    		//Get Index Details
    		Strategy strategy = strategyRepo.findByName(name);
    		BigDecimal spotPrice = angelOneService.getcurrentPrice(smartconnect, strategy.getExchange(),
    				strategy.getTradingsymbol(), strategy.getToken());
    		//Find ATM Strike for given index
        	BigDecimal atmStrike = getATMStrike(name,strategy);
        	
        	//Fetch All the Stike
        	List<StraddlePremiumDto> strikeList = buildStraddleDtos(atmStrike, 50);
        	
        	//Get Token Details
        	strikeList = getAllTokenDetails(strikeList,strategy);
        	
        	//Get Price for all the strike
        	strikeList=getPriceForAllTheStrikes(strikeList);
        	
        	//Save the data in DB
        	savePriceDetails(strikeList,strategy,spotPrice);
        	
            //Get Market Data
        	//getMarketData(straddlePremiumDto);
    	}
    	catch (Exception e) {
    		logger.error(e.getMessage());
			// TODO: handle exception
		}
    	
    	
    }
    
	public int savePriceDetails(List<StraddlePremiumDto> strikeList, Strategy strategy, BigDecimal spotPrice) {

		int count = 0;

		// Exact timestamp to the second
		LocalDateTime timestamp = LocalDateTime.now().withNano(0);

		for (StraddlePremiumDto dto : strikeList) {

			StraddleIntraday entity = new StraddleIntraday();

			entity.setName(strategy.getName());
			entity.setExpiry(strategy.getExpiry());
			entity.setStrike(dto.getStrikePrice());

			// precise, clean timestamp
			entity.setTimestamp(timestamp);

			// Prices
			entity.setCePrice(dto.getCePrice());
			entity.setPePrice(dto.getPePrice());

			// Spot
			entity.setSpot(spotPrice);

			// IV
			entity.setCeIV(dto.getCeIv());
			entity.setPeIV(dto.getPeIv());
			entity.setCombinedIV(dto.getCombinedIv());

			// VWAP
			entity.setCeVwap(dto.getCeVwap());
			entity.setPeVwap(dto.getPeVwap());
			// entity.setCombinedVwap(dto.getCombinedVwap());

			// extrinsic / intrinsic
			// entity.setIntrinsic(dto.getIntrinsic());
			// entity.setExtrinsic(dto.getExtrinsic());

			straddleIntradayRepo.save(entity);
			count++;
		}

		return count;
	}

    
 // Get CE and PE prices for all strikes
    public List<StraddlePremiumDto> getPriceForAllTheStrikes(List<StraddlePremiumDto> strikeList) {

        SmartConnect smartconnect = angelOne.signIn();

        for (StraddlePremiumDto dto : strikeList) {

            // --- CE Price ---
            Token ceToken = dto.getCeToken();
            if (ceToken != null) {
                BigDecimal ceCurrentPrice = angelOneService.getcurrentPrice(
                        smartconnect,
                        ceToken.getExch_seg(),
                        ceToken.getSymbol(),
                        ceToken.getToken()
                );
                dto.setCePrice(ceCurrentPrice);
            }

            // --- PE Price ---
            Token peToken = dto.getPeToken();
            if (peToken != null) {
                BigDecimal peCurrentPrice = angelOneService.getcurrentPrice(
                        smartconnect,
                        peToken.getExch_seg(),
                        peToken.getSymbol(),
                        peToken.getToken()
                );
                dto.setPePrice(peCurrentPrice);
            }
        }

        return strikeList; // Same list updated
    }

    
    //Fetch ITM and OTM
    public List<StraddlePremiumDto> buildStraddleDtos(BigDecimal spot, int interval) {

        List<StraddlePremiumDto> list = new ArrayList<>();

        // ---- 1. Find ATM strike ----
        BigDecimal atm = spot
                .divide(BigDecimal.valueOf(interval), 0, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(interval));

        // ---- 2. Add 5 ITM (lower strikes) ----
        for (int i = 5; i >= 1; i--) {
            BigDecimal strike = atm.subtract(BigDecimal.valueOf(interval).multiply(BigDecimal.valueOf(i)));
            list.add(createDto(strike));
        }

        // ---- 3. Add ATM strike ----
        list.add(createDto(atm));

        // ---- 4. Add 5 OTM (higher strikes) ----
        for (int i = 1; i <= 5; i++) {
            BigDecimal strike = atm.add(BigDecimal.valueOf(interval).multiply(BigDecimal.valueOf(i)));
            list.add(createDto(strike));
        }

        return list;
    }

    private StraddlePremiumDto createDto(BigDecimal strike) {
        StraddlePremiumDto dto = new StraddlePremiumDto();
        dto.setStrikePrice(strike);
        //dto.setTimestamp(LocalDateTime.now());  // or intraday snapshot timestamp
        return dto;
    }

    
    /*
     * Get ATM Strike
     */
	public BigDecimal getATMStrike(String name, Strategy strategy) {
		SmartConnect smartconnect = angelOne.signIn();
		int strikeInterval = 0;
		String key = name.trim().toUpperCase();

		switch (key) {
		case "NIFTY":
			strikeInterval = 50;
			break;

		default:
			logger.warn("Unknown symbol name: {}", name);
			break;
		}

		BigDecimal currentPrice = angelOneService.getcurrentPrice(smartconnect, strategy.getExchange(),
				strategy.getTradingsymbol(), strategy.getToken());

		if (currentPrice == null) {
			logger.warn("Unable to fetch current price for {}", strategy.getName());
		}

		int nearestStrike = chartService.findNearestMultiple(currentPrice.intValue(), strikeInterval);

		return new BigDecimal(nearestStrike);
	}
	
	public List<StraddlePremiumDto> getAllTokenDetails(List<StraddlePremiumDto> strikeList, Strategy strategy) {

		for (StraddlePremiumDto dto : strikeList) {

			BigDecimal strike = dto.getStrikePrice(); // already present in your list

            // CE Symbol
			String ceSymbol = String.format("%s%s%dCE", strategy.getName(), strategy.getExpiry(), strike.intValue());

			Indexes ceIndex = indexesRepo.findByNameAndSymbol(strategy.getName(), ceSymbol);

			if (ceIndex != null) {
				Token ceToken = new Token();
				ceToken.setToken(ceIndex.getToken());
				ceToken.setSymbol(ceIndex.getSymbol());
				ceToken.setExch_seg(ceIndex.getExchange());
				dto.setCeToken(ceToken);
			}

            // PE Symbol
			String peSymbol = String.format("%s%s%dPE", strategy.getName(), strategy.getExpiry(), strike.intValue());

			Indexes peIndex = indexesRepo.findByNameAndSymbol(strategy.getName(), peSymbol);

			if (peIndex != null) {
				Token peToken = new Token();
				peToken.setToken(peIndex.getToken());
				peToken.setSymbol(peIndex.getSymbol());
				peToken.setExch_seg(peIndex.getExchange());
				dto.setPeToken(peToken);
			}
		}

		return strikeList; // same list updated
	}


	public void getMarketData(StraddlePremiumDto straddlePremiumDto) throws InterruptedException, IOException, SmartAPIException
	{
		SmartConnect smartconnect = angelOne.signIn();
		List<String> batch = Arrays.asList(straddlePremiumDto.getCeToken().getToken());
    	JSONObject payload = predictionService.buildMarketDataPayload(batch,"NFO");
    	JSONObject jsonObject=predictionService.callMarketDataWithRetry(smartconnect, payload);
    	System.out.println(jsonObject);
	}
	
	//Display Line chart
	public CombinedChartResponse getStraddleCombinedChart(
	        String name,
	        String expiry,
	        BigDecimal ceStrike,
	        BigDecimal peStrike) {

	    List<StraddleIntraday> ceRows = straddleIntradayRepo.getByStrike(name, expiry, ceStrike);
	    List<StraddleIntraday> peRows = straddleIntradayRepo.getByStrike(name, expiry, peStrike);
	    List<StraddleIntraday> spotRows = straddleIntradayRepo.getSpotHistory(name, expiry);

	    Map<LocalDateTime, CombinedChartPoint> map = new TreeMap<>();

	    // CE mapping
	    for (StraddleIntraday r : ceRows) {
	        map.computeIfAbsent(r.getTimestamp(), t ->
	                new CombinedChartPoint(t, null, null, null)
	        ).setCe(r.getCePrice());
	    }

	    // PE mapping
	    for (StraddleIntraday r : peRows) {
	        map.computeIfAbsent(r.getTimestamp(), t ->
	                new CombinedChartPoint(t, null, null, null)
	        ).setPe(r.getPePrice());
	    }

	    // Spot mapping
	    for (StraddleIntraday r : spotRows) {
	        map.computeIfAbsent(r.getTimestamp(), t ->
	                new CombinedChartPoint(t, null, null, null)
	        ).setSpot(r.getSpot());
	    }

	    CombinedChartResponse response = new CombinedChartResponse();
	    response.getData().addAll(map.values());

	    return response;
	}



}
