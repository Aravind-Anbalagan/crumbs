package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.dto.Candlestick;
import com.crumbs.trade.dto.PivotRequest;
import com.crumbs.trade.dto.PivotResponse;
import com.crumbs.trade.dto.PriceActionResult;
import com.crumbs.trade.entity.Indicator;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.PricesIndex;
import com.crumbs.trade.entity.PricesMcx;
import com.crumbs.trade.entity.PricesNifty;
import com.crumbs.trade.repo.PriceHeikinashiIndexRepo;
import com.crumbs.trade.repo.PricesIndexRepo;
import com.crumbs.trade.repo.PricesMcxRepo;
import com.crumbs.trade.repo.PricesNiftyRepo;
import com.crumbs.trade.repo.PsarIndexRepo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Computes all technical indicators (Heikin-Ashi, PSAR, SuperTrend, VWAP,
 * CPR, Bollinger, RSI, Moving Averages, Pivot) and writes them onto the
 * Indicator entity. Does NOT persist — callers are responsible for saving.
 */
@Service
public class IndicatorComputeService {

    Logger logger = LoggerFactory.getLogger(IndicatorComputeService.class);

    @Autowired HeikinAshiCalculator heikinAshiCalculator;
    @Autowired PSARCalculator psarCalculator;
    @Autowired SuperTrendSwingIndicator superTrendSwingIndicator;
    @Autowired VWAPSwingIndicator vwapSwingIndicator;
    @Autowired PivotPointService pivotPointService;
    @Autowired PriceActionService priceActionService;
    @Autowired PricesIndexRepo pricesIndexRepo;
    @Autowired PricesMcxRepo pricesMcxRepo;
    @Autowired PricesNiftyRepo pricesNiftyRepo;
    @Autowired PriceHeikinashiIndexRepo priceHeikinashiIndexRepo;
    @Autowired PsarIndexRepo psarIndexRepo;
    @Autowired private ObjectMapper objectMapper;

    // =========================================================
    // Heikin-Ashi + PSAR
    // =========================================================

    /** DB-backed overload — fetches its own price list */
    public void updateHeikinAshi(String name, String timeFrame, String type) {
        if (type.equalsIgnoreCase("MCX")) {
            List<PricesMcx> mcxList = pricesMcxRepo.findAll();
            if (mcxList.size() > 1) { heikinAshiCalculator.createCandle(mcxList, null, null, type, name, timeFrame); psarCalculator.createPoints(mcxList, null, null, type, name, timeFrame); }
        } else if (type.equalsIgnoreCase("NFO")) {
            List<PricesNifty> niftyList = pricesNiftyRepo.findAll();
            if (niftyList.size() > 1) { heikinAshiCalculator.createCandle(null, niftyList, null, type, name, timeFrame); psarCalculator.createPoints(null, niftyList, null, type, name, timeFrame); }
        } else if (type.equalsIgnoreCase("INDEX")) {
            List<PricesIndex> indexList = pricesIndexRepo.findByNameAndTimeframe(name, timeFrame);
            if (indexList.size() > 1) { heikinAshiCalculator.createCandle(null, null, indexList, type, name, timeFrame); psarCalculator.createPoints(null, null, indexList, type, name, timeFrame); }
        }
    }

    /** In-memory overload — callers pass an already-fetched list */
    public void updateHeikinAshi(String name, String timeFrame, String type, List<PricesIndex> pricesList) {
        if (type.equalsIgnoreCase("INDEX") && pricesList != null && pricesList.size() > 1) {
            heikinAshiCalculator.createCandle(null, null, pricesList, type, name, timeFrame);
            psarCalculator.createPoints(null, null, pricesList, type, name, timeFrame);
        }
    }

    public void deletePsarAndHiekeinTableData(String name, String timeFrame) {
        priceHeikinashiIndexRepo.deleteByNameAndTimeframe(name, timeFrame);
        psarIndexRepo.deleteByNameAndTimeframe(name, timeFrame);
    }

    // =========================================================
    // CPR
    // =========================================================

    public void getCPR(Indicator indicator, String name, String timeframe,
                       BigDecimal index_CurrentPrice, List<PricesIndex> pricesList) {
        try {
            PricesIndex refCandle = null;
            if ("WEEK".equalsIgnoreCase(timeframe)) {
                if (pricesList != null && pricesList.size() > 1) refCandle = pricesList.get(pricesList.size() - 2);
            } else {
                if (pricesList != null && pricesList.size() > 1) refCandle = pricesList.get(1);
            }
            if (refCandle == null) { logger.warn("No previous candle for CPR: {} [{}]", name, timeframe); return; }

            String cprSignal = calculateCPRSignal(refCandle.getHigh(), refCandle.getLow(), refCandle.getClose(), index_CurrentPrice);
            switch (timeframe.toUpperCase()) {
                case "ONE_HOUR": indicator.setCpr1H(cprSignal); break;
                case "ONE_DAY":  indicator.setCpr1D(cprSignal); break;
                case "WEEK":     indicator.setCpr1W(cprSignal); break;
                default: logger.warn("Unsupported timeframe for CPR: {}", timeframe);
            }
        } catch (Exception e) {
            logger.warn("CPR calculation failed for {} [{}]: {}", name, timeframe, e.getMessage());
        }
    }

    public String calculateCPRSignal(BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal currentPrice) {
        if (high == null || low == null || close == null || currentPrice == null) return null;
        BigDecimal pivot    = high.add(low).add(close).divide(BigDecimal.valueOf(3), RoundingMode.HALF_UP);
        BigDecimal bc       = high.add(low).divide(BigDecimal.valueOf(2), RoundingMode.HALF_UP);
        BigDecimal tc       = pivot.multiply(BigDecimal.valueOf(2)).subtract(bc);
        BigDecimal lowerCPR = tc.min(bc);
        BigDecimal upperCPR = tc.max(bc);
        String position;
        if      (currentPrice.compareTo(upperCPR) > 0) position = "Above CPR";
        else if (currentPrice.compareTo(lowerCPR) < 0) position = "Below CPR";
        else                                            position = "Between CPR";
        return String.format("Pivot=%.2f, TC=%.2f, BC=%.2f | Position=%s", pivot, upperCPR, lowerCPR, position);
    }

    // =========================================================
    // SuperTrend
    // =========================================================

    public void getSuperTrend(Indicator indicator, String name, String timeframe, List<PricesIndex> pricesList) {
        try {
            int candleCount; int period; BigDecimal multiplier;
            switch (timeframe.toUpperCase()) {
                case "ONE_WEEK":  candleCount = 104; period = 7;  multiplier = new BigDecimal("4");   break;
                case "ONE_DAY":   candleCount = 100; period = 10; multiplier = new BigDecimal("3");   break;
                case "ONE_HOUR":  candleCount = 200; period = 10; multiplier = new BigDecimal("2.5"); break;
                default:          candleCount = 150; period = 10; multiplier = new BigDecimal("3");
            }

            List<PricesIndex> priceList = (pricesList != null && !pricesList.isEmpty()) ? pricesList
                    : pricesIndexRepo.findByNameAndTimeframe(name, timeframe,
                        PageRequest.of(0, candleCount, Sort.by(Sort.Direction.DESC, "id")));

            if (priceList == null || priceList.isEmpty()) { setIndicatorDefaults(indicator, timeframe); return; }

            List<PricesIndex> calcList = new ArrayList<>(priceList);
            Collections.reverse(calcList);

            List<Candlestick> candleList = calcList.stream().map(pi -> {
                Candlestick c = new Candlestick();
                c.setOpen(pi.getOpen()); c.setHigh(pi.getHigh()); c.setLow(pi.getLow());
                c.setClose(pi.getClose()); c.setVolume(pi.getVolume()); c.setTimestamp(pi.getTimestamp());
                return c;
            }).collect(Collectors.toList());

            List<Candlestick> result = superTrendSwingIndicator.calculateSuperTrend(candleList, period, multiplier);
            if (result == null || result.isEmpty()) { setIndicatorDefaults(indicator, timeframe); return; }

            Candlestick lastCandle = result.get(result.size() - 1);
            String volatility = superTrendSwingIndicator.calculateVolatility(candleList, period)
                    .setScale(2, RoundingMode.HALF_UP).toPlainString();

            switch (timeframe.toUpperCase()) {
                case "ONE_HOUR":
                    indicator.setSuperTrendHourly(lastCandle.getSuperTrend());
                    indicator.setSuperTrendSignalHourly(lastCandle.getSuperTrendSignal());
                    indicator.setSuperTrendVolatilityWeekly(volatility); break;
                case "ONE_DAY":
                    indicator.setSuperTrendDaily(lastCandle.getSuperTrend());
                    indicator.setSuperTrendSignalDaily(lastCandle.getSuperTrendSignal());
                    indicator.setSuperTrendVolatilityWeekly(volatility); break;
                case "ONE_WEEK":
                    indicator.setSuperTrendWeekly(lastCandle.getSuperTrend());
                    indicator.setSuperTrendSignalWeekly(lastCandle.getSuperTrendSignal());
                    indicator.setSuperTrendVolatilityWeekly(volatility); break;
            }
        } catch (Exception e) {
            logger.error("SuperTrend error for {} [{}]: {}", name, timeframe, e.getMessage());
            setIndicatorError(indicator, timeframe);
        }
    }

    // =========================================================
    // VWAP
    // =========================================================

    public void getVwap(Indicator indicator, String name, String timeframe, List<PricesIndex> pricesList) {
        try {
            List<PricesIndex> vwapPrices = (pricesList != null && !pricesList.isEmpty()) ? pricesList
                    : pricesIndexRepo.findByNameAndTimeframe(name, timeframe,
                        PageRequest.of(0, 200, Sort.by(Sort.Direction.DESC, "id")));

            if (vwapPrices == null || vwapPrices.isEmpty()) { logger.warn("No VWAP data for {} [{}]", name, timeframe); return; }

            List<Candlestick> vwapCandles = vwapPrices.stream().map(p -> {
                Candlestick c = new Candlestick();
                c.setOpen(p.getOpen()); c.setHigh(p.getHigh()); c.setLow(p.getLow());
                c.setClose(p.getClose()); c.setVolume(p.getVolume()); c.setTimestamp(p.getTimestamp());
                return c;
            }).collect(Collectors.toList());

            List<Candlestick> vwapResult = vwapSwingIndicator.calculateVWAP(vwapCandles, timeframe);
            if (vwapResult.isEmpty()) { logger.warn("VWAP result empty for {} [{}]", name, timeframe); return; }

            Candlestick latest = vwapResult.get(0);
            switch (timeframe.toUpperCase()) {
                case "ONE_HOUR": indicator.setVwapHourly(latest.getVwap()); indicator.setVwapSignalHourly(latest.getSignal()); break;
                case "ONE_DAY":  indicator.setVwapDaily(latest.getVwap());  indicator.setVwapSignalDaily(latest.getSignal());  break;
                case "ONE_WEEK": indicator.setVwapWeekly(latest.getVwap()); indicator.setVwapSignalWeekly(latest.getSignal()); break;
                default: logger.warn("Unknown timeframe {} for VWAP", timeframe);
            }
        } catch (Exception e) {
            logger.error("VWAP calc failed for {} [{}]: {}", name, timeframe, e.getMessage());
        }
    }

    // =========================================================
    // Price Action / Pivot
    // =========================================================

    public PriceActionResult getLevels(List<PricesIndex> list, BigDecimal index_CurrentPrice)
            throws JsonProcessingException {
        return priceActionService.analyze(index_CurrentPrice, list, "ONE_DAY");
    }

    public String calPivot(List<PricesIndex> list) throws JsonProcessingException {
        if (list != null && !list.isEmpty()) {
            PivotRequest pivotRequest = new PivotRequest();
            pivotRequest.setClose(list.get(0).getClose());
            pivotRequest.setHigh(list.get(0).getHigh());
            pivotRequest.setLow(list.get(0).getLow());
            pivotRequest.setMethod("fibonacci");
            PivotResponse pivotResponse = pivotPointService.calculatePivot(pivotRequest);
            return objectMapper.writeValueAsString(pivotResponse);
        }
        return null;
    }

    // =========================================================
    // Bollinger Band
    // =========================================================

    public String findBollingerBand(String bolingervalue, BigDecimal currentPrice) {
        String[] stringValues = bolingervalue.replace("[", "").replace("]", "").split(", ");
        List<BigDecimal> valueList = new ArrayList<>();
        for (String value : stringValues) valueList.add(new BigDecimal(value));
        if      (currentPrice.compareTo(valueList.get(0)) > 0) return "UP";
        else if (currentPrice.compareTo(valueList.get(2)) < 0) return "DOWN";
        return null;
    }

    // =========================================================
    // 52-Week High/Low
    // =========================================================

    public Indicator get52WeekData(Indexes indexes, SmartConnect smartConnect, Indicator indicator)
            throws IOException, SmartAPIException {
        JSONObject payload = new JSONObject();
        payload.put("mode", "FULL");
        JSONObject exchangeTokens = new JSONObject();
        JSONArray nseTokens = new JSONArray();
        nseTokens.put(indexes.getToken());
        exchangeTokens.put(indexes.getExchange(), nseTokens);
        payload.put("exchangeTokens", exchangeTokens);
        JSONObject response = smartConnect.marketData(payload);
        if (response.get("fetched") != null) {
            JSONArray jsonArray = (JSONArray) response.get("fetched");
            JSONObject item = jsonArray.getJSONObject(0);
            indicator.setFifty2_weeklow(new BigDecimal(item.getInt("52WeekLow")));
            indicator.setFifty2_weekhigh(new BigDecimal(item.getInt("52WeekHigh")));
        }
        return indicator;
    }

    // =========================================================
    // 3-Day candle flag
    // =========================================================

    public String get3DaysHighAndLow(Indicator stock) {
        String result = null;
        List<Integer> highs = Arrays.stream(stock.getLast3daycandlehigh().replaceAll("\\[|\\]", "").split(","))
                .map(String::trim).map(Integer::parseInt).collect(Collectors.toList());
        List<Integer> lows  = Arrays.stream(stock.getLast3daycandlelow().replaceAll("\\[|\\]", "").split(","))
                .map(String::trim).map(Integer::parseInt).collect(Collectors.toList());
        if (stock.getCurrentPrice().compareTo(new BigDecimal(highs.get(0))) > 0) result = "UP";
        if (stock.getCurrentPrice().compareTo(new BigDecimal(lows.get(0)))  < 0) result = "DOWN";
        return result;
    }

    // =========================================================
    // Default / error state helpers
    // =========================================================

    public void setIndicatorDefaults(Indicator indicator, String timeframe) {
        switch (timeframe.toUpperCase()) {
            case "ONE_DAY":  indicator.setSuperTrendSignalDaily("NA");   indicator.setVwapSignalDaily("NA");   break;
            case "ONE_WEEK": indicator.setSuperTrendSignalWeekly("NA");  indicator.setVwapSignalWeekly("NA");  break;
            case "ONE_HOUR": indicator.setSuperTrendSignalHourly("NA");  indicator.setVwapSignalHourly("NA");  break;
        }
    }

    public void setIndicatorError(Indicator indicator, String timeframe) {
        switch (timeframe.toUpperCase()) {
            case "ONE_DAY":  indicator.setSuperTrendSignalDaily("ERROR");  indicator.setVwapSignalDaily("ERROR");  break;
            case "ONE_WEEK": indicator.setSuperTrendSignalWeekly("ERROR"); indicator.setVwapSignalWeekly("ERROR"); break;
            case "ONE_HOUR": indicator.setSuperTrendSignalHourly("ERROR"); indicator.setVwapSignalHourly("ERROR"); break;
        }
    }
}