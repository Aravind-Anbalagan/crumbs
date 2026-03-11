package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
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
import com.crumbs.trade.dto.StrategyDTO;
import com.crumbs.trade.entity.Expiry;
import com.crumbs.trade.entity.OIDATA;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.ExpiryRepo;
import com.crumbs.trade.repo.OIDataRepo;
import com.crumbs.trade.repo.StrategyRepo;
import jakarta.transaction.Transactional;

@Service
public class OIDataService {

    Logger logger = LoggerFactory.getLogger(OIDataService.class);

    @Autowired AngelOne angelOne;
    @Autowired ExpiryRepo expiryRepo;
    @Autowired StrategyRepo strategyRepo;
    @Autowired OIDataRepo oiDataRepo;
    @Autowired OIPredictionService oiPrediction;

    // ✅ Replaced: taskService.convertStrategyToDto() + taskService.getChart()
    @Autowired StrategyHelperService strategyHelperService;

    /*
     * Get the option Chain of the given index/stock
     */
    public void getOptionChain(String name) throws IOException, SmartAPIException {
        List<Expiry> expiryList = expiryRepo.findByActive("Y");

        if (expiryList != null && !expiryList.isEmpty()) {
            expiryList.forEach(expiry -> {
                // ✅ was: taskService.convertStrategyToDto(strategyRepo.findByName(name))
                StrategyDTO strategy = strategyHelperService.convertStrategyToDto(strategyRepo.findByName(name));
                List<OIDto> optionChainList = new ArrayList<>();

                if (strategy != null) {
                    strategy.setExpiry(expiry.getExpirydate());
                    BigDecimal currentPrice = getCurrentAdjustedPrice(strategy, expiry.getRoundOff());
                    if (currentPrice != null) {
                        try {
                            optionChainList = prepareOIStrikeData(currentPrice, strategy, name,
                                    expiry.getCount(), expiry.getRoundOff());
                            if (optionChainList != null && !optionChainList.isEmpty()) {
                                saveOIData(optionChainList, name, expiry.getExpirydate());
                            }
                        } catch (IOException | SmartAPIException e) {
                            logger.error("Error occurred while reading option chain for expiry {}", expiry);
                        }
                    }
                }
            });
        }
    }

    @Transactional
    public void saveOIData(List<OIDto> optionChainList, String name, String expiry) {
        if (name.contains("NIFTY")) name = "NIFTY";
        if (name != null) {
            List<OIDATA> oiList = oiDataRepo.findByNameAndExpiry(name, expiry);
            if (oiList != null && !oiList.isEmpty()) updateOI(optionChainList);
            else                                      saveOI(optionChainList);
        }
    }

    @Transactional
    public void saveOI(List<OIDto> optionChainList) {
        optionChainList.forEach(t -> {
            OIDATA oi = new OIDATA();
            oi.setStrikePrice(t.getStrikePrice());
            oi.setName(t.getName());
            oi.setCallLTP(setNewValue(t.getCallLtp()));
            oi.setCallOI(setNewValue(t.getCallOi()));
            oi.setCallOIChange(setNewValue(t.getCallOiChange()));
            oi.setPutLTP(setNewValue(t.getPutLtp()));
            oi.setPutOI(setNewValue(t.getPutOi()));
            oi.setPutOIChange(setNewValue(t.getPutOiChange()));
            oi.setExpiry(t.getExpiry());
            if (t.getSpot() != null) {
                updateSpot(t.getName());
                oi.setSpot(t.getSpot());
            }
            oiDataRepo.save(oi);
        });
    }

    @Transactional
    public void updateSpot(String name) {
        OIDATA oi = oiDataRepo.findBySpotAndName("Y", name);
        if (oi != null) {
            oi.setSpot(null);
            oiDataRepo.save(oi);
        }
    }

    public String setNewValue(String newValue) {
        return Arrays.asList(newValue).toString();
    }

    @Transactional
    public void updateOI(List<OIDto> optionChainList) {
        optionChainList.forEach(t -> {
            if (t.getStrikePrice() != null && t.getName() != null) {
                OIDATA oi = oiDataRepo.findByStrikePriceAndNameAndExpiry(
                        t.getStrikePrice(), t.getName(), t.getExpiry());
                if (oi == null) oi = new OIDATA();

                oi.setCallLTP(getExistingValue(oi.getCallLTP(), t.getCallLtp()));
                oi.setCallOI(getExistingValue(oi.getCallOI(), t.getCallOi()));
                oi.setCallOIChange(getExistingValue(oi.getCallOIChange(), t.getCallOiChange()));
                oi.setPutLTP(getExistingValue(oi.getPutLTP(), t.getPutLtp()));
                oi.setPutOI(getExistingValue(oi.getPutOI(), t.getPutOi()));
                oi.setPutOIChange(getExistingValue(oi.getPutOIChange(), t.getPutOiChange()));
                oi.setStrikePrice(t.getStrikePrice());
                oi.setName(t.getName());
                oi.setPutSignal(oiPrediction.getOISignal(convertStringToList(oi.getPutTrend())));
                oi.setCallSignal(oiPrediction.getOISignal(convertStringToList(oi.getCallTrend())));
                oi.setExpiry(t.getExpiry());
                if (t.getSpot() != null) {
                    updateSpot(t.getName());
                    oi.setSpot(t.getSpot());
                }
                oiDataRepo.save(oi);
            }
        });
    }

    public String getOIList(OIDATA oi, String type, String time) {
        List<BigDecimal> oiList = "CALL".equalsIgnoreCase(type)
                ? formatData(oi.getCallOI()) : formatData(oi.getPutOI());
        return calculateOI(oiList, time);
    }

    public String calculateOI(List<BigDecimal> prices, String time) {
        List<String> trendAnalysis = analyzePriceTrendsWith3PointConfirmation(prices, time);
        return trendAnalysis.isEmpty() ? "N/A" : trendAnalysis.get(trendAnalysis.size() - 1);
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
                            ? time + " = Trend changed to Down" : time + "  = Continuing Down");
                    currentTrend = "Down";
                } else {
                    if ("N/A".equals(currentTrend)) {
                        currentTrend = "Same";
                        analysis.add(time + " = Trend is Same (Start of comparison)");
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

    public String getExistingValue(String oldValue, String newValue) {
        if (oldValue != null && !oldValue.contains("null")) {
            List<String> valueList = Arrays.stream(oldValue.replaceAll("\\[|\\]", "").split(","))
                    .map(String::trim).collect(Collectors.toList());
            valueList.add(newValue);
            return valueList.toString();
        }
        return newValue;
    }

    public List<String> convertStringToList(String input) {
        if (input.startsWith("[") && input.endsWith("]")) {
            input = input.substring(1, input.length() - 1);
            return Arrays.asList(input.split(", "));
        }
        return Arrays.asList(input);
    }

    public BigDecimal getCurrentAdjustedPrice(StrategyDTO strategy, int roundOff) {
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
        return 50 != roundOff ? roundToNearest500(currentPrice, roundOff) : currentPrice;
    }

    public static BigDecimal roundToNearest500(BigDecimal price, int roundOff) {
        BigDecimal divisor = new BigDecimal(roundOff);
        return price.divide(divisor, 0, RoundingMode.HALF_UP).multiply(divisor);
    }

    public List<OIDto> prepareOIStrikeData(BigDecimal currentPrice, StrategyDTO strategy,
                                            String name, int count, int roundoff)
            throws IOException, SmartAPIException {
        List<OIDto> optionChainList = new ArrayList<>();
        if (name.equalsIgnoreCase("NIFTY_OI")) name = "NIFTY";
        try {
            for (int i = 1; i <= count; i++) {
                TimeUnit.SECONDS.sleep(1);
                OIDto oiDto = new OIDto();
                oiDto.setStrikePrice(new BigDecimal(currentPrice.intValue() - (roundoff * i)));
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

            for (int i = 1; i <= count; i++) {
                TimeUnit.SECONDS.sleep(1);
                OIDto oiDto = new OIDto();
                oiDto.setStrikePrice(new BigDecimal(currentPrice.intValue() + (roundoff * i)));
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
        String CEType = strategy.getTradingsymbol() + strategy.getExpiry()
                + oiDto.getStrikePrice().intValue() + "CE";
        String PEType = strategy.getTradingsymbol() + strategy.getExpiry()
                + oiDto.getStrikePrice().intValue() + "PE";

        // ✅ was: taskService.getChart(name, CEType, "N")
        Strategy strategyNifty = strategyHelperService.getChart(name, CEType, "N");
        if (strategyNifty.getToken() != null) {
            oiDto = getMarketData(strategyNifty.getName(), strategyNifty.getToken(), oiDto, "CE");
            // ✅ was: taskService.getChart(name, PEType, "N")
            strategyNifty = strategyHelperService.getChart(name, PEType, "N");
            oiDto = getMarketData(strategyNifty.getName(), strategyNifty.getToken(), oiDto, "PE");
            return oiDto;
        }
        return null;
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
            } else {
                oiDto.setPutOi(getFormatedInput(tradeTime, item.get("opnInterest").toString()));
                oiDto.setPutLtp(getFormatedInput(tradeTime, item.get("ltp").toString()));
            }
            oiDto.setName(name);
        }
        return oiDto;
    }

    public String getFormatedInput(String key, String value) {
        return key.concat(" = ").concat(value);
    }
}