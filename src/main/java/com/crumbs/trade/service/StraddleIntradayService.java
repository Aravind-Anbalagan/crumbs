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
     * Get Combined Straddle Premium
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

            // ATM Strike
            BigDecimal atmStrike = getATMStrike(name, strategy);

            // Fetch strike list (5 ITM, ATM, 5 OTM)
            List<StraddlePremiumDto> strikeList = buildStraddleDtos(atmStrike, 50);

            // Attach CE/PE tokens
            strikeList = getAllTokenDetails(strikeList, strategy);

            // Fetch prices
            strikeList = getPriceForAllTheStrikes(strikeList, smartconnect);

            // Save in DB
            savePriceDetails(strikeList, strategy, spotPrice);

        } catch (Exception e) {
            logger.error("Error in getCombineStraddlePremium: {}", e.getMessage());
        }
    }

    /*
     * Save Straddle prices
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

            // Safe price values
            entity.setCePrice(dto.getCePrice() != null ? dto.getCePrice() : BigDecimal.ZERO);
            entity.setPePrice(dto.getPePrice() != null ? dto.getPePrice() : BigDecimal.ZERO);

            entity.setSpot(spotPrice);

            entity.setCeIV(dto.getCeIv());
            entity.setPeIV(dto.getPeIv());
            entity.setCombinedIV(dto.getCombinedIv());

            entity.setCeVwap(dto.getCeVwap());
            entity.setPeVwap(dto.getPeVwap());

            straddleIntradayRepo.save(entity);
            count++;
        }

        return count;
    }

    /*
     * Get CE/PE prices safely
     */
    public List<StraddlePremiumDto> getPriceForAllTheStrikes(List<StraddlePremiumDto> strikeList,
                                                             SmartConnect smartconnect) {

        for (StraddlePremiumDto dto : strikeList) {

            // CE price
            Token ceToken = dto.getCeToken();
            if (ceToken != null) {
                BigDecimal cePrice = angelOneService.getcurrentPrice(
                        smartconnect,
                        ceToken.getExch_seg(),
                        ceToken.getSymbol(),
                        ceToken.getToken()
                );
                dto.setCePrice(cePrice);
            }

            // PE price
            Token peToken = dto.getPeToken();
            if (peToken != null) {
                BigDecimal pePrice = angelOneService.getcurrentPrice(
                        smartconnect,
                        peToken.getExch_seg(),
                        peToken.getSymbol(),
                        peToken.getToken()
                );
                dto.setPePrice(pePrice);
            }
        }
        return strikeList;
    }

    /*
     * Build ITM-ATM-OTM strike list
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
     * Calculate ATM Strike
     */
    public BigDecimal getATMStrike(String name, Strategy strategy) {

        SmartConnect smartconnect = angelOne.signIn();

        int strikeInterval = 0;
        switch (name.trim().toUpperCase()) {
            case "NIFTY":
                strikeInterval = 50;
                break;
            default:
                logger.warn("Unknown symbol {}", name);
        }

        BigDecimal currentPrice = angelOneService.getcurrentPrice(
                smartconnect, strategy.getExchange(), strategy.getTradingsymbol(), strategy.getToken());

        if (currentPrice == null) return BigDecimal.ZERO;

        int nearest = chartService.findNearestMultiple(currentPrice.intValue(), strikeInterval);

        return new BigDecimal(nearest);
    }

    /*
     * Fetch CE/PE token from DB
     */
    public List<StraddlePremiumDto> getAllTokenDetails(List<StraddlePremiumDto> strikeList, Strategy strategy) {

        for (StraddlePremiumDto dto : strikeList) {

            int strike = dto.getStrikePrice().intValue();

            String ceSymbol = String.format("%s%s%dCE", strategy.getName(), strategy.getExpiry(), strike);
            String peSymbol = String.format("%s%s%dPE", strategy.getName(), strategy.getExpiry(), strike);

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

        // CE
        for (StraddleIntraday r : ceRows) {
            String key = r.getTimestamp().atZone(ist).toOffsetDateTime().withNano(0).toString();
            map.computeIfAbsent(key, t -> new CombinedChartPoint(t, null, null, null))
               .setCe(r.getCePrice());
        }

        // PE
        for (StraddleIntraday r : peRows) {
            String key = r.getTimestamp().atZone(ist).toOffsetDateTime().withNano(0).toString();
            map.computeIfAbsent(key, t -> new CombinedChartPoint(t, null, null, null))
               .setPe(r.getPePrice());
        }

        // Spot
        for (StraddleIntraday r : spotRows) {
            String key = r.getTimestamp().atZone(ist).toOffsetDateTime().withNano(0).toString();
            map.computeIfAbsent(key, t -> new CombinedChartPoint(t, null, null, null))
               .setSpot(r.getSpot());
        }

        CombinedChartResponse response = new CombinedChartResponse();
        response.getData().addAll(map.values());

        return response;
    }
}
