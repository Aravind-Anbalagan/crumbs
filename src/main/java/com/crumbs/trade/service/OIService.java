package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.OIDto;
import com.crumbs.trade.dto.OIUIDto;
import com.crumbs.trade.dto.OptionTrackDTO;
import com.crumbs.trade.dto.StrategyDTO;
import com.crumbs.trade.entity.OI;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.OIRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.TradingSignalService.TradingSignalResult;
import com.crumbs.trade.utility.OIParsingUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.transaction.Transactional;

@Service
public class OIService {

    Logger logger = LoggerFactory.getLogger(OIService.class);

    @Autowired AngelOne angelOne;
    @Autowired StrategyRepo strategyRepo;
    @Autowired OIRepo oiRepo;
    @Autowired OISignalGenerator oiSignalGenerator;
    @Autowired TradingSignalService tradingSignalService;

    // ✅ Replaced: taskService.convertStrategyToDto() + taskService.getChart()
    @Autowired StrategyHelperService strategyHelperService;

    /*
     * Get the option Chain of the given index/stock
     */
    public void getOptionChain(String name) throws IOException, SmartAPIException {
        // ✅ was: taskService.convertStrategyToDto(strategyRepo.findByName(name))
        StrategyDTO strategy = strategyHelperService.convertStrategyToDto(strategyRepo.findByName(name));
        List<OIDto> optionChainList = new ArrayList<>();

        if (strategy != null) {
            BigDecimal currentPrice = getCurrentAdjustedPrice(strategy);
            if (currentPrice != null) {
                optionChainList = prepareOIStrikeData(currentPrice, strategy, name);
                if (optionChainList != null && !optionChainList.isEmpty()) {
                    saveOIData(optionChainList, name);
                }
            }
        }
    }

    @Transactional
    public void saveOIData(List<OIDto> optionChainList, String name) {
        if (name.contains("NIFTY")) name = "NIFTY";
        if (name != null) {
            List<OI> oiList = oiRepo.findByName(name);
            if (oiList != null && !oiList.isEmpty()) updateOI(optionChainList);
            else                                      saveOI(optionChainList);
        }
    }

    public String setNewValue(String newValue) {
        return Arrays.asList(newValue).toString();
    }

    @Transactional
    public void saveOI(List<OIDto> optionChainList) {
        optionChainList.forEach(t -> {
            OI oi = new OI();
            oi.setStrikePrice(t.getStrikePrice());
            oi.setName(t.getName());
            oi.setCallLTP(setNewValue(t.getCallLtp()));
            oi.setCallOI(setNewValue(t.getCallOi()));
            oi.setCallVolume(setNewValue(t.getCallVolume()));
            oi.setCallOIChange(setNewValue(t.getCallOiChange()));
            oi.setPutLTP(setNewValue(t.getPutLtp()));
            oi.setPutOI(setNewValue(t.getPutOi()));
            oi.setPutVolume(setNewValue(t.getPutVolume()));
            oi.setPutOIChange(setNewValue(t.getPutOiChange()));
            oi.setExpiry(t.getExpiry());
            oi.setCallSignal("BASE");
            oi.setPutSignal("BASE");
            if (t.getSpot() != null) {
                updateSpot(t.getName());
                oi.setSpot(t.getSpot());
            }
            oiRepo.save(oi);
        });
    }

    @Transactional
    public void updateSpot(String name) {
        OI oi = oiRepo.findBySpotAndName("Y", name);
        if (oi != null) {
            oi.setSpot(null);
            oiRepo.save(oi);
        }
    }

    @Transactional
    public void updateOI(List<OIDto> optionChainList) {
        optionChainList.forEach(t -> {
            if (t.getStrikePrice() != null && t.getName() != null) {
                OI oi = oiRepo.findByStrikePriceAndName(t.getStrikePrice(), t.getName());
                if (oi == null) oi = new OI();

                oi.setCallLTP(getExistingValue(oi.getCallLTP(), t.getCallLtp()));
                oi.setCallOI(getExistingValue(oi.getCallOI(), t.getCallOi()));
                oi.setCallOIChange(getExistingValue(oi.getCallOIChange(), t.getCallOiChange()));
                oi.setPutLTP(getExistingValue(oi.getPutLTP(), t.getPutLtp()));
                oi.setPutOI(getExistingValue(oi.getPutOI(), t.getPutOi()));
                oi.setPutOIChange(getExistingValue(oi.getPutOIChange(), t.getPutOiChange()));
                oi.setStrikePrice(t.getStrikePrice());
                oi.setName(t.getName());

                oi.setPutSignal(oiSignalGenerator.addTicksFromTimestampedStringsAsJson(
                        oi.getPutLTP(), oi.getPutOI(), oi.getPutVolume()));
                oi.setCallSignal(oiSignalGenerator.addTicksFromTimestampedStringsAsJson(
                        oi.getCallLTP(), oi.getCallOI(), oi.getCallVolume()));

                String formattedTime = LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                oi.setCallTradingSignal(formattedTime.concat(" - ").concat(
                        tradingSignalService.generateBestSignal(oi.getCallOI(), oi.getCallLTP(), oi.getCallVolume())));
                oi.setPutTradingSignal(formattedTime.concat(" - ").concat(
                        tradingSignalService.generateBestSignal(oi.getPutOI(), oi.getPutLTP(), oi.getPutVolume())));

                oi.setExpiry(t.getExpiry());
                oi.setCallVolume(getExistingValue(oi.getCallVolume(), t.getCallVolume()));
                oi.setPutVolume(getExistingValue(oi.getPutVolume(), t.getPutVolume()));
                if (t.getSpot() != null) {
                    updateSpot(t.getName());
                    oi.setSpot(t.getSpot());
                }
                oiRepo.save(oi);
            }
        });
    }

    public String getOIList(OI oi, String type, String time) {
        List<BigDecimal> oiList = "CALL".equalsIgnoreCase(type)
                ? formatData(oi.getCallOI()) : formatData(oi.getPutOI());
        return calculateOI(oiList, time);
    }

    public List<BigDecimal> formatData(String value) {
        List<String> valueList = Arrays.stream(value.replaceAll("\\[|\\]", "").split(","))
                .map(String::trim).collect(Collectors.toList());
        if (valueList == null || valueList.isEmpty()) return null;
        return valueList.stream()
                .map(item -> item.split(" = "))
                .filter(parts -> parts.length == 2)
                .map(parts -> {
                    try   { return new BigDecimal(parts[1]); }
                    catch (NumberFormatException e) { System.err.println("Invalid number: " + parts[1]); return null; }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public String calculateOI(List<BigDecimal> prices, String time) {
        List<String> trendAnalysis = analyzePriceTrendsWith3PointConfirmation(prices, time);
        return trendAnalysis.isEmpty() ? null : trendAnalysis.get(trendAnalysis.size() - 1);
    }

    public static List<String> analyzePriceTrendsWith3PointConfirmation(List<BigDecimal> prices, String time) {
        List<String> analysis = new ArrayList<>();
        if (prices.isEmpty()) return analysis;

        String currentTrend = "N/A";
        List<BigDecimal> lastThreePrices = new ArrayList<>();

        for (BigDecimal currentPrice : prices) {
            lastThreePrices.add(currentPrice);
            if (lastThreePrices.size() > 3) lastThreePrices.remove(0);

            if (lastThreePrices.size() == 3) {
                int upCount = 0, downCount = 0;
                for (int j = 1; j < lastThreePrices.size(); j++) {
                    int cmp = lastThreePrices.get(j).compareTo(lastThreePrices.get(j - 1));
                    if (cmp > 0) upCount++;
                    else if (cmp < 0) downCount++;
                }
                if (upCount >= 2) {
                    analysis.add(!"Up".equals(currentTrend)
                            ? time + " = Trend changed to Up" : time + " = Continuing Up");
                    currentTrend = "Up";
                } else if (downCount >= 2) {
                    analysis.add(!"Down".equals(currentTrend)
                            ? time + " = Trend changed to Down" : time + " = Continuing Down");
                    currentTrend = "Down";
                } else {
                    if ("N/A".equals(currentTrend)) {
                        currentTrend = "Same";
                        analysis.add("Trend is Same (Start of comparison)");
                    } else {
                        analysis.add(time + " = No significant trend change");
                    }
                }
            } else {
                if ("N/A".equals(currentTrend)) {
                    currentTrend = "Same";
                    analysis.add(time + " = Trend is Same (Start of comparison)");
                } else {
                    analysis.add(time + " = No significant trend change");
                }
            }
        }
        return analysis;
    }

    public String getExistingValue(String oldValue, String newValue) {
        if (oldValue != null && !oldValue.contains("null")) {
            List<String> valueList = Arrays.stream(oldValue.replaceAll("\\[|\\]", "").split(","))
                    .map(String::trim).collect(Collectors.toList());
            valueList.add(newValue);
            return valueList.toString();
        }
        return newValue;
    }

    public BigDecimal getCurrentAdjustedPrice(StrategyDTO strategy) {
        SmartConnect smartConnect = angelOne.signIn();
        JSONObject jsonObject = smartConnect.getLTP(strategy.getExchange(),
                strategy.getTradingsymbol(), strategy.getToken());
        BigDecimal currentPrice = new BigDecimal(String.valueOf(jsonObject.get("ltp")));
        if (currentPrice.intValue() % 100 != 0) {
            int remainder = 100 - currentPrice.intValue() % 100;
            currentPrice = remainder <= 50
                    ? currentPrice.add(new BigDecimal(remainder))
                    : currentPrice.subtract(new BigDecimal(currentPrice.intValue() % 100));
        }
        return currentPrice;
    }

    public List<OIDto> prepareOIStrikeData(BigDecimal currentPrice, StrategyDTO strategy, String name)
            throws IOException, SmartAPIException {
        List<OIDto> optionChainList = new ArrayList<>();
        if (name.equalsIgnoreCase("NIFTY_OI")) name = "NIFTY";
        try {
            for (int i = 10; i >= 1; i--) {
                TimeUnit.SECONDS.sleep(1);
                OIDto oiDto = new OIDto();
                oiDto.setStrikePrice(new BigDecimal(currentPrice.intValue() - (50 * i)));
                oiDto.setExpiry(strategy.getExpiry());
                prepareOIData(oiDto, strategy, name);
                if (oiDto.getStrikePrice() != null && oiDto.getName() != null) optionChainList.add(oiDto);
            }
            OIDto spotDto = new OIDto();
            spotDto.setStrikePrice(new BigDecimal(currentPrice.intValue()));
            spotDto.setSpot("Y");
            spotDto.setExpiry(strategy.getExpiry());
            prepareOIData(spotDto, strategy, name);
            if (spotDto.getStrikePrice() != null && spotDto.getName() != null) optionChainList.add(spotDto);

            for (int i = 1; i <= 10; i++) {
                TimeUnit.SECONDS.sleep(1);
                OIDto oiDto = new OIDto();
                oiDto.setStrikePrice(new BigDecimal(currentPrice.intValue() + (50 * i)));
                oiDto.setExpiry(strategy.getExpiry());
                prepareOIData(oiDto, strategy, name);
                if (oiDto.getStrikePrice() != null && oiDto.getName() != null) optionChainList.add(oiDto);
            }
        } catch (Exception e) {
            logger.error("Error during reading option chain: {}", e.getMessage());
        }
        return optionChainList;
    }

    public OIDto prepareOIData(OIDto oiDto, StrategyDTO strategy, String name)
            throws IOException, SmartAPIException {
        String CEType = strategy.getSymbol() + strategy.getSymbol1()
                + oiDto.getStrikePrice().intValue() + "CE";
        String PEType = strategy.getSymbol() + strategy.getSymbol1()
                + oiDto.getStrikePrice().intValue() + "PE";

        // ✅ was: taskService.getChart(name, CEType, "N")
        Strategy strategyNifty = strategyHelperService.getChart(name, CEType, "N");
        oiDto = getMarketData(strategyNifty.getName(), strategyNifty.getToken(), oiDto, "CE");
        // ✅ was: taskService.getChart(name, PEType, "N")
        strategyNifty = strategyHelperService.getChart(name, PEType, "N");
        oiDto = getMarketData(strategyNifty.getName(), strategyNifty.getToken(), oiDto, "PE");
        return oiDto;
    }

    public OIDto getMarketData(String name, String token, OIDto oiDto, String type)
            throws SmartAPIException, IOException {
        String exchange = name.contains("NIFTY") ? "NFO" : "MCX";
        SmartConnect smartConnect = angelOne.signIn();
        JSONObject payload = new JSONObject();
        payload.put("mode", "FULL");
        JSONObject exchangeTokens = new JSONObject();
        JSONArray nseTokens = new JSONArray();
        nseTokens.put(token);
        exchangeTokens.put(exchange, nseTokens);
        payload.put("exchangeTokens", exchangeTokens);
        JSONObject response = smartConnect.marketData(payload);
        if (response.get("fetched") != null) {
            JSONArray jsonArray = (JSONArray) response.get("fetched");
            JSONObject item = jsonArray.getJSONObject(0);
            String tradeTime = item.get("exchTradeTime").toString();
            if (type.equalsIgnoreCase("CE")) {
                oiDto.setCallOi(getFormatedInput(tradeTime, item.get("opnInterest").toString()));
                oiDto.setCallLtp(getFormatedInput(tradeTime, item.get("ltp").toString()));
                oiDto.setCallVolume(getFormatedInput(tradeTime, item.get("tradeVolume").toString()));
            } else {
                oiDto.setPutOi(getFormatedInput(tradeTime, item.get("opnInterest").toString()));
                oiDto.setPutLtp(getFormatedInput(tradeTime, item.get("ltp").toString()));
                oiDto.setPutVolume(getFormatedInput(tradeTime, item.get("tradeVolume").toString()));
            }
            oiDto.setName(name);
        }
        return oiDto;
    }

    public String getFormatedInput(String key, String value) {
        return key.concat(" = ").concat(value);
    }

    public List<OIUIDto> getOIDataDetails() {
        List<OI> entities = oiRepo.findAll();
        List<OIUIDto> dtoList = new ArrayList<>();
        for (OI entity : entities) {
            OIUIDto dto = new OIUIDto();
            dto.setName(entity.getName());
            dto.setStrikePrice(entity.getStrikePrice());
            dto.setExpiry(entity.getExpiry());
            dto.setSpot(entity.getSpot());
            dto.setCallSignal(entity.getCallSignal());
            dto.setCallTradingSignal(entity.getCallTradingSignal());
            dto.setPutSignal(entity.getPutSignal());
            dto.setPutTradingSignal(entity.getPutTradingSignal());
            dtoList.add(dto);
        }
        return dtoList;
    }
}