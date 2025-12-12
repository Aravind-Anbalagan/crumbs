package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.json.JSONArray;
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
     * MAIN ENTRY – GET COMBINED STRADDLE PREMIUM
     */
    public void getCombineStraddlePremium(String name) {

        try {
            SmartConnect smartconnect = angelOne.signIn();  // login once

            Strategy strategy = strategyRepo.findByName(name);

            BigDecimal spotPrice = angelOneService.getcurrentPrice(
                    smartconnect,
                    strategy.getExchange(),
                    strategy.getTradingsymbol(),
                    strategy.getToken()
            );

            // ATM strike
            BigDecimal atmStrike = getATMStrike(name, strategy);

            // Strikes list
            List<StraddlePremiumDto> strikeList = buildStraddleDtos(atmStrike, 50);

            // Add CE/PE tokens
            strikeList = getAllTokenDetails(strikeList, strategy);

            // IMPORTANT → ONE API CALL for all prices
            strikeList = getPriceForAllTheStrikesBatch(strikeList, smartconnect);

            // Save to DB
            savePriceDetails(strikeList, strategy, spotPrice);

        } catch (Exception e) {
            logger.error("Error in getCombineStraddlePremium: {}", e.getMessage(), e);
        }
    }


	/*
	 * SAVE PRICES TO DB
	 */
	public int savePriceDetails(List<StraddlePremiumDto> strikeList, Strategy strategy, BigDecimal spotPrice) {

		int count = 0;

		LocalDateTime timestamp = LocalDateTime.now(ZoneId.of("Asia/Kolkata")).withNano(0);

		for (StraddlePremiumDto dto : strikeList) {

			StraddleIntraday entity = new StraddleIntraday();

			entity.setName(strategy.getName());
			entity.setExpiry(strategy.getExpiry());
			entity.setStrike(dto.getStrikePrice());
			entity.setTimestamp(timestamp);

			BigDecimal ce = dto.getCePrice() != null ? dto.getCePrice() : BigDecimal.ZERO;
			BigDecimal pe = dto.getPePrice() != null ? dto.getPePrice() : BigDecimal.ZERO;

			entity.setCePrice(ce);
			entity.setPePrice(pe);
			entity.setSpot(spotPrice);

			entity.setCeIV(dto.getCeIv());
			entity.setPeIV(dto.getPeIv());
			//entity.setCombinedIV(dto.getCombinedIv());

			entity.setCeVwap(dto.getCeVwap());
			entity.setPeVwap(dto.getPeVwap());

// -----------------------------
// 1️⃣ Combined Premium
// -----------------------------
			BigDecimal combinedPremium = ce.add(pe);
			entity.setCombinedPremium(combinedPremium);

// -----------------------------
// 2️⃣ Intrinsic values
// -----------------------------
			BigDecimal callIntrinsic = spotPrice.subtract(dto.getStrikePrice()).max(BigDecimal.ZERO);
			BigDecimal putIntrinsic = dto.getStrikePrice().subtract(spotPrice).max(BigDecimal.ZERO);

			BigDecimal totalIntrinsic = callIntrinsic.add(putIntrinsic);
			entity.setIntrinsic(totalIntrinsic);

// -----------------------------
// 3️⃣ Extrinsic values
// -----------------------------
			BigDecimal ceExtrinsic = ce.subtract(callIntrinsic).max(BigDecimal.ZERO);
			BigDecimal peExtrinsic = pe.subtract(putIntrinsic).max(BigDecimal.ZERO);

			BigDecimal totalExtrinsic = ceExtrinsic.add(peExtrinsic);
			entity.setExtrinsic(totalExtrinsic);

// -----------------------------
// 4️⃣ Open Prices (if sent from DTO)
// -----------------------------
			entity.setCeOpenPrice(dto.getCeOpenPrice());
			entity.setPeOpenPrice(dto.getPeOpenPrice());

// Combined Open Price
			if (dto.getCeOpenPrice() != null && dto.getPeOpenPrice() != null) {
				entity.setCombinedOpenPrice(dto.getCeOpenPrice().add(dto.getPeOpenPrice()));
			}
			
//Avg price

			if (ce != null && pe != null) {
			    entity.setAvgPrice(
			        ce.add(pe).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP)
			    );
			} else {
			    entity.setAvgPrice(null); // or keep previous value
			}


// -----------------------------
// Save row
// -----------------------------
			straddleIntradayRepo.save(entity);
			count++;
		}

		return count;
	}


    /*
     * ⭐ ⭐ ONE API CALL FOR ALL STRIKES ⭐ ⭐
     */
	public List<StraddlePremiumDto> getPriceForAllTheStrikesBatch(
	        List<StraddlePremiumDto> strikeList,
	        SmartConnect smartconnect) {

	    try {
	        // 1️⃣ Collect all tokens
	        List<String> tokens = new ArrayList<>();
	        for (StraddlePremiumDto dto : strikeList) {
	            if (dto.getCeToken() != null)
	                tokens.add(dto.getCeToken().getToken());
	            if (dto.getPeToken() != null)
	                tokens.add(dto.getPeToken().getToken());
	        }

	        if (tokens.isEmpty()) return strikeList;

	        // 2️⃣ FULL MODE → returns LTP + OHLC (which includes OPEN)
	        JSONObject payload = new JSONObject();
	        payload.put("mode", "FULL");

	        JSONObject map = new JSONObject();
	        map.put("NFO", tokens);
	        payload.put("exchangeTokens", map);

	        // 3️⃣ Call AngelOne Market Data API
	        JSONObject response = predictionService.callMarketDataWithRetry(
	                smartconnect, payload);

	        JSONArray fetchedArray = response.getJSONArray("fetched");

	        // 4️⃣ Prepare maps for LTP & OPEN
	        Map<String, BigDecimal> ltpMap = new HashMap<>();
	        Map<String, BigDecimal> openMap = new HashMap<>();

	        // 5️⃣ Parse API Response
	        for (int i = 0; i < fetchedArray.length(); i++) {

	            JSONObject item = fetchedArray.getJSONObject(i);

	            String token = item.getString("symbolToken");

	            BigDecimal ltp = item.getBigDecimal("ltp");
	            BigDecimal open = item.getBigDecimal("open");  // <--- use this

	            ltpMap.put(token, ltp);
	            openMap.put(token, open);
	        }

	        // 6️⃣ Assign values back to StraddlePremiumDto
	        for (StraddlePremiumDto dto : strikeList) {

	            BigDecimal ceLtp = null;
	            BigDecimal peLtp = null;

	            BigDecimal ceOpen = null;
	            BigDecimal peOpen = null;

	            if (dto.getCeToken() != null) {
	                String t = dto.getCeToken().getToken();
	                ceLtp = ltpMap.get(t);
	                ceOpen = openMap.get(t);

	                dto.setCePrice(ceLtp);
	                dto.setCeOpenPrice(ceOpen);
	            }

	            if (dto.getPeToken() != null) {
	                String t = dto.getPeToken().getToken();
	                peLtp = ltpMap.get(t);
	                peOpen = openMap.get(t);

	                dto.setPePrice(peLtp);
	                dto.setPeOpenPrice(peOpen);
	            }

	            // 7️⃣ Combined values
	            if (ceLtp != null && peLtp != null)
	                dto.setCombinedPremium(ceLtp.add(peLtp));

	            if (ceOpen != null && peOpen != null)
	                dto.setCombinedOpenPrice(ceOpen.add(peOpen));
	        }

	    } catch (Exception | SmartAPIException e) {
	        logger.error("Batch FULL error: {}", e.getMessage(), e);
	    }

	    return strikeList;
	}




    /*
     * BUILD ITM/ATM/OTM STRIKES
     */
    public List<StraddlePremiumDto> buildStraddleDtos(BigDecimal spot, int interval) {

        List<StraddlePremiumDto> list = new ArrayList<>();

        BigDecimal atm = spot.divide(BigDecimal.valueOf(interval), 0, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(interval));

        for (int i = 5; i >= 1; i--) {
            list.add(createDto(atm.subtract(BigDecimal.valueOf(interval).multiply(BigDecimal.valueOf(i)))));
        }

        list.add(createDto(atm));

        for (int i = 1; i <= 5; i++) {
            list.add(createDto(atm.add(BigDecimal.valueOf(interval).multiply(BigDecimal.valueOf(i)))));
        }

        return list;
    }

    private StraddlePremiumDto createDto(BigDecimal strike) {
        StraddlePremiumDto dto = new StraddlePremiumDto();
        dto.setStrikePrice(strike);
        return dto;
    }


    /*
     * ATM Calculation
     */
    public BigDecimal getATMStrike(String name, Strategy strategy) {

        SmartConnect smartconnect = angelOne.signIn();

        int strikeInterval = 50;

        BigDecimal currentPrice = angelOneService.getcurrentPrice(
                smartconnect,
                strategy.getExchange(),
                strategy.getTradingsymbol(),
                strategy.getToken()
        );

        if (currentPrice == null) return BigDecimal.ZERO;

        int nearest = chartService.findNearestMultiple(currentPrice.intValue(), strikeInterval);

        return new BigDecimal(nearest);
    }


    /*
     * Get CE/PE token details from DB
     */
    public List<StraddlePremiumDto> getAllTokenDetails(List<StraddlePremiumDto> strikeList,
                                                       Strategy strategy) {

        for (StraddlePremiumDto dto : strikeList) {

            int strike = dto.getStrikePrice().intValue();

            String ceSymbol = String.format("%s%s%dCE", strategy.getName(),
                    strategy.getExpiry(), strike);

            String peSymbol = String.format("%s%s%dPE", strategy.getName(),
                    strategy.getExpiry(), strike);

            Indexes ceIndex = indexesRepo.findByNameAndSymbol(strategy.getName(), ceSymbol);
            if (ceIndex != null) {
                Token t = new Token();
                t.setToken(ceIndex.getToken());
                t.setSymbol(ceIndex.getSymbol());
                t.setExch_seg(ceIndex.getExchange());
                dto.setCeToken(t);
            }

            Indexes peIndex = indexesRepo.findByNameAndSymbol(strategy.getName(), peSymbol);
            if (peIndex != null) {
                Token t = new Token();
                t.setToken(peIndex.getToken());
                t.setSymbol(peIndex.getSymbol());
                t.setExch_seg(peIndex.getExchange());
                dto.setPeToken(t);
            }
        }

        return strikeList;
    }
    /*
     * Combined Line Chart
     */
    public CombinedChartResponse getStraddleCombinedChart(
            String name,
            String expiry,
            BigDecimal ceStrike,
            BigDecimal peStrike) {

        List<StraddleIntraday> ceRows = straddleIntradayRepo.getByStrike(name, expiry, ceStrike);
        List<StraddleIntraday> peRows = straddleIntradayRepo.getByStrike(name, expiry, peStrike);
        List<StraddleIntraday> spotRows = straddleIntradayRepo.getSpotHistory(name, expiry);

        Map<String, CombinedChartPoint> map = new TreeMap<>();
        ZoneId ist = ZoneId.of("Asia/Kolkata");

        // CE rows
        for (StraddleIntraday r : ceRows) {

            String key = r.getTimestamp().atZone(ist).toOffsetDateTime().withNano(0).toString();

            CombinedChartPoint pt = map.computeIfAbsent(
                    key,
                    t -> new CombinedChartPoint(t, null, null,null, null, null, null, null, null, null)
            );

            pt.setCe(r.getCePrice());
            pt.setCeOpen(r.getCeOpenPrice());
            pt.setCombinedPremium(r.getCePrice().add(r.getCeOpenPrice()));
          
            if (r.getExtrinsic() != null)
                pt.setExtrinsic(r.getExtrinsic());
        }

        // PE rows
        for (StraddleIntraday r : peRows) {

            String key = r.getTimestamp().atZone(ist).toOffsetDateTime().withNano(0).toString();

            CombinedChartPoint pt = map.computeIfAbsent(
                    key,
                    t -> new CombinedChartPoint(t, null, null,null, null, null, null, null, null, null)
            );

            pt.setPe(r.getPePrice());
            pt.setPeOpen(r.getPeOpenPrice());

            if (r.getExtrinsic() != null)
                pt.setExtrinsic(r.getExtrinsic());
        }

        // SPOT rows
        for (StraddleIntraday r : spotRows) {

            String key = r.getTimestamp().atZone(ist).toOffsetDateTime().withNano(0).toString();

            CombinedChartPoint pt = map.computeIfAbsent(
                    key,
                    t -> new CombinedChartPoint(t, null, null,null, null, null, null, null, null, null)
            );

            pt.setSpot(r.getSpot());
        }

        // Derived values: combinedOpen, avgPrice
        for (CombinedChartPoint pt : map.values()) {

            // Open sum
            if (pt.getCeOpen() != null && pt.getPeOpen() != null) {
                pt.setCombinedOpen(pt.getCeOpen().add(pt.getPeOpen()));
            }

            // Avg price
            if (pt.getCe() != null && pt.getPe() != null) {
                BigDecimal avg = pt.getCe()
                        .add(pt.getPe())
                        .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);

                pt.setAvgPrice(avg);
            }
        }

        CombinedChartResponse response = new CombinedChartResponse();
        response.getData().addAll(map.values());

        return response;
    }



}
