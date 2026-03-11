package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
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
import com.crumbs.trade.entity.Candle;
import com.crumbs.trade.entity.Indicator;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.PricesIndex;
import com.crumbs.trade.entity.PricesMcx;
import com.crumbs.trade.entity.PricesNifty;
import com.crumbs.trade.repo.IndicatorRepo;
import com.crumbs.trade.repo.PricesIndexRepo;
import com.crumbs.trade.repo.PricesMcxRepo;
import com.crumbs.trade.repo.PricesNiftyRepo;

import jakarta.transaction.Transactional;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Consumes an in-memory price list and assembles all volume-derived signals
 * plus technical indicators onto the Indicator entity before persisting it.
 */
@Service
public class VolumeAnalysisService {

    Logger logger = LoggerFactory.getLogger(VolumeAnalysisService.class);

    @Autowired IndicatorRepo indicatorRepo;
    @Autowired PricesIndexRepo pricesIndexRepo;
    @Autowired PricesMcxRepo pricesMcxRepo;
    @Autowired PricesNiftyRepo pricesNiftyRepo;
    @Autowired AngelOne angelOne;

    // Calculators
    @Autowired HeikinAshiCalculator heikinAshiCalculator;
    @Autowired PSARCalculator psarCalculator;
    @Autowired RSICalculator rsiCalculator;
    @Autowired MovingAverageCalculator movingAverageCalculator;
    @Autowired BollingerBandsCalculator bollingerBandsCalculator;
    @Autowired VolumeService volumeService;
    @Autowired PivotPointService pivotPointService;
    @Autowired PriceActionService priceActionService;
    @Autowired IndicatorService indicatorService;

    // Delegate services
    @Autowired IndicatorComputeService indicatorComputeService;
    @Autowired SignalCheckService signalCheckService;
    @Autowired PriceUtilService priceUtilService;
    @Autowired StrategyHelperService strategyHelperService;
    @Autowired ChartService chartService;

    // =========================================================
    // Hourly Volume
    // =========================================================
    @Transactional
    public void getHourlyVolumeData(String timeFrame, Indexes indexes, BigDecimal index_CurrentPrice,
            SmartConnect smartConnect, Candle candle, List<PricesIndex> pricesList)
            throws IOException, SmartAPIException {

        String name = indexes.getName();
        if (pricesList == null || pricesList.isEmpty()) { logger.info("No hourly data for {} / {}", name, timeFrame); return; }

        PricesIndex lastRecord = pricesList.get(pricesList.size() - 1);
        List<PricesIndex> topVolumeList = pricesList.stream()
                .sorted(Comparator.comparing(PricesIndex::getVolume, Comparator.reverseOrder())).limit(10).collect(Collectors.toList());

        List<String> supportList = topVolumeList.stream().filter(s -> "BUY".equalsIgnoreCase(s.getType()))
                .map(v -> v.getVolume() + "=" + v.getHigh()).collect(Collectors.toList());
        List<String> resistanceList = topVolumeList.stream().filter(s -> "SELL".equalsIgnoreCase(s.getType()))
                .map(v -> v.getVolume() + "=" + v.getLow()).collect(Collectors.toList());

        int avgRange = (int) pricesList.stream().filter(d -> d.getRange() != null)
                .mapToInt(d -> d.getRange().intValue()).average().orElse(candle.getPriceLimit());

        Indicator indicator = indicatorRepo.findByname(name);
        if (indicator == null) indicator = new Indicator();

        indicator.setName(name); indicator.setToken(indexes.getToken()); indicator.setTimeFrame(timeFrame);
        indicator.setHoursupport(supportList.toString()); indicator.setHourresistance(resistanceList.toString());
        indicator.setAvgrange(new BigDecimal(avgRange)); indicator.setPrevdaycloseprice(index_CurrentPrice);
        indicator.setExchange(indexes.getExchange()); indicator.setTradingSymbol(indexes.getSymbol());
        indicator.setCreatedDate(LocalDateTime.now()); indicator.setCpr(lastRecord.getCpr());
        indicator.setCurrentPrice(index_CurrentPrice); indicator.setExecutedPrice(index_CurrentPrice);

        indicator = signalCheckService.checkForHourlySignal(indicator, supportList, resistanceList, index_CurrentPrice, new BigDecimal(avgRange));

        List<PricesIndex> pricesDesc = new ArrayList<>(pricesList);
        Collections.reverse(pricesDesc);

        List<PricesIndex> last3h = pricesDesc.subList(0, Math.min(3, pricesDesc.size()));
        if (last3h.size() >= 3) {
            List<Integer> highList = last3h.stream().map(p -> p.getHigh().intValue()).sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            List<Integer> lowList  = last3h.stream().map(p -> p.getLow().intValue()).sorted().collect(Collectors.toList());
            indicator.setLast3HourCandleHigh(highList.toString());
            indicator.setLast3Hourcandlelow(lowList.toString());
        }

        indicator.setHeikinAshiHourly(heikinAshiCalculator.computeSignal(pricesDesc));
        indicator.setPsarFlagHourly(psarCalculator.computeSignal(pricesDesc));

        if (indicator.getLast3HourCandleHigh() != null && indicator.getLast3Hourcandlelow() != null) {
            indicator.setHourlysellsl(priceUtilService.convertStringToList(indicator.getLast3HourCandleHigh(), "SELL"));
            indicator.setHourlybuysl(priceUtilService.convertStringToList(indicator.getLast3Hourcandlelow(), "BUY"));
        }

        boolean changed = false;
        if ("FIRST BUY".equalsIgnoreCase(indicator.getHeikinAshiHourly()) && "FIRST BUY".equalsIgnoreCase(indicator.getPsarFlagHourly())) {
            indicator.setIntraday("UP"); indicator.setTradetype("HOURLY"); indicator.setExecutedDate(priceUtilService.getCurrentDate()); changed = true;
        }
        if ("FIRST SELL".equalsIgnoreCase(indicator.getHeikinAshiHourly()) && "FIRST SELL".equalsIgnoreCase(indicator.getPsarFlagHourly())) {
            indicator.setIntraday("DOWN"); indicator.setTradetype("HOURLY"); indicator.setExecutedDate(priceUtilService.getCurrentDate()); changed = true;
        }
        if (changed) indicatorRepo.save(indicator);
    }

    // =========================================================
    // Day Volume
    // =========================================================

    @Transactional
    public void getDayVolumeData(String timeFrame, Indexes indexes, BigDecimal index_CurrentPrice,
            SmartConnect smartConnect, Candle candle, BigDecimal index_OpenPrice,
            List<String> optionNameList, Map<String, String> sectorMap, List<PricesIndex> pricesList)
            throws IOException, SmartAPIException {

        String name = indexes.getName();
        List<PricesIndex> allData = new ArrayList<>(pricesList);
        Collections.reverse(allData);   // newest first
        if (allData.isEmpty()) { logger.info("No price rows for {} / {} - skipping", name, timeFrame); return; }

        List<PricesIndex> last15 = allData.subList(0, Math.min(15, allData.size()));
        List<PricesIndex> last20 = allData.subList(0, Math.min(20, allData.size()));
        String cprData = allData.get(0).getCpr();
        PricesIndex openAndClose = allData.get(0);

        List<PricesIndex> topVolumeList = allData.stream()
                .sorted(Comparator.comparing(PricesIndex::getVolume, Comparator.reverseOrder())).limit(10).collect(Collectors.toList());
        List<String> supportList    = topVolumeList.stream().filter(s -> "BUY".equalsIgnoreCase(s.getType())).map(v -> v.getVolume() + "=" + v.getHigh()).collect(Collectors.toList());
        List<String> resistanceList = topVolumeList.stream().filter(s -> "SELL".equalsIgnoreCase(s.getType())).map(v -> v.getVolume() + "=" + v.getLow()).collect(Collectors.toList());

        int avgRange = (int) last15.stream().mapToInt(d -> d.getRange() == null ? 0 : d.getRange().intValue()).average().orElse(candle.getPriceLimit());

        Indicator indicator = indicatorRepo.findByname(name);
        if (indicator == null) indicator = new Indicator();

        indicator.setName(name); indicator.setToken(indexes.getToken()); indicator.setTimeFrame(timeFrame);
        indicator.setDailysupport(supportList.toString()); indicator.setDailyresistance(resistanceList.toString());
        indicator.setAvgrange(new BigDecimal(avgRange)); indicator.setPrevdaycloseprice(index_CurrentPrice);
        indicator.setExchange(indexes.getExchange()); indicator.setTradingSymbol(indexes.getSymbol());
        indicator.setCreatedDate(LocalDateTime.now()); indicator.setCurrentPrice(index_CurrentPrice);
        indicator.setExecutedPrice(index_CurrentPrice);

        List<PricesIndex> last3 = allData.subList(0, Math.min(3, allData.size()));
        if (last3.size() >= 3) {
            List<Integer> highList = last3.stream().map(p -> p.getHigh().intValue()).sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            List<Integer> lowList  = last3.stream().map(p -> p.getLow().intValue()).sorted().collect(Collectors.toList());
            indicator.setLast3daycandlehigh(highList.toString());
            indicator.setLast3daycandlelow(lowList.toString());
        }
        indicator.setLast3daycandleflag(indicatorComputeService.get3DaysHighAndLow(indicator));
        indicator.setCpr(cprData);
        indicator.setDailyopenandcloseissame(priceUtilService.findOpenAndClose(openAndClose));
        indicator = indicatorComputeService.get52WeekData(indexes, smartConnect, indicator);
        indicator = signalCheckService.checkForDaySignal(indicator, supportList, resistanceList, index_CurrentPrice, new BigDecimal(avgRange));

        // RSI
        if (last15.size() >= 5) indicator.setDailyRSI(rsiCalculator.getRSIData(last15));
        else                     indicator.setDailyRSI(null);

        // Moving Averages
        if (allData.size() >= 20) {
            if (allData.size() >= 200) {
                indicator.setMovingavg200(movingAverageCalculator.getMovingAverage(allData, 200));
                if (indicator.getMovingavg200() != null) indicator.setMovingavg200Flag(index_CurrentPrice.subtract(indicator.getMovingavg200()));
            } else { indicator.setMovingavg200(null); }

            if (allData.size() >= 50) {
                indicator.setMovingavg50(movingAverageCalculator.getMovingAverage(allData.subList(0, 50), 50));
                if (indicator.getMovingavg50() != null) indicator.setMovingavg50Flag(index_CurrentPrice.subtract(indicator.getMovingavg50()));
            } else { indicator.setMovingavg50(null); }

            indicator.setMovingavg20(movingAverageCalculator.getMovingAverage(allData.subList(0, 20), 20));
            if (indicator.getMovingavg20() != null) indicator.setMovingavg20Flag(index_CurrentPrice.subtract(indicator.getMovingavg20()));
        } else {
            indicator.setDailyPriceActionSupport(null); indicator.setDailyPriceActionResistance(null); indicator.setDailyPriceActionFlag(false);
        }

        // Bollinger Bands
        if (last20.size() >= 20) {
            indicator.setBollingerband(bollingerBandsCalculator.createBand(last20));
            indicator.setBollingerflag(indicatorComputeService.findBollingerBand(indicator.getBollingerband(), indicator.getCurrentPrice()));
        } else { indicator.setBollingerband(null); indicator.setBollingerflag(null); }

        // Heikin-Ashi + PSAR
        indicator.setHeikinAshiDay(heikinAshiCalculator.computeSignal(allData));
        indicator.setPsarFlagDay(psarCalculator.computeSignal(allData));

        // Volume
        indicator.setVolume(volumeService.getLastNDaysVolumeJsonString(allData.subList(0, Math.min(6, allData.size())), 5));
        indicator.setVolumeFlag(volumeService.calVolumeAvg(allData.subList(0, Math.min(6, allData.size()))));

        // Pivot
        indicator.setPivot(indicatorComputeService.calPivot(allData.subList(0, Math.min(1, allData.size()))));

        // CPR, SuperTrend, VWAP
        indicatorComputeService.getCPR(indicator, name, timeFrame, index_CurrentPrice, allData);
        indicator.setOneday("Y");
        indicatorComputeService.getSuperTrend(indicator, name, timeFrame, allData);
        indicatorComputeService.getVwap(indicator, name, timeFrame, allData);

        // F&O and Sector
        if (optionNameList.contains(name)) indicator.setOptions("Y");
        indicator.setSector(sectorMap.getOrDefault(name.toLowerCase(), "Unknown"));

        // Current Trend
        String currentTrend =
            ("BUY".equalsIgnoreCase(indicator.getHeikinAshiDay()) && "BUY".equalsIgnoreCase(indicator.getPsarFlagDay()))   ? "UP" :
            ("SELL".equalsIgnoreCase(indicator.getHeikinAshiDay()) && "SELL".equalsIgnoreCase(indicator.getPsarFlagDay())) ? "DOWN" : "SIDEWAYS";
        indicator.setSl(currentTrend);

        indicatorRepo.save(indicator);
    }

    // =========================================================
    // Weekly Volume
    // =========================================================

    @Transactional
    public void getWeeklyVolumeData(String timeFrame, Indexes indexes, BigDecimal index_CurrentPrice,
            SmartConnect smartConnect, Candle candle, BigDecimal index_OpenPrice,
            List<PricesIndex> pricesList) {

        Indicator indicator = indicatorRepo.findByname(indexes.getName());
        if (indicator == null) { logger.warn("No indicator found for {}", indexes.getName()); return; }

        indicatorComputeService.getCPR(indicator, indexes.getName(), timeFrame, index_CurrentPrice, pricesList);
        indicator.setWeeklyvolumeFlag(checkLastVolumeVsAvg(pricesList));

        pricesList.sort(Comparator.comparing(PricesIndex::getVolume, Comparator.reverseOrder()));
        List<PricesIndex> top10 = pricesList.stream().limit(10).toList();
        List<String> supportList    = top10.stream().filter(s -> "BUY".equalsIgnoreCase(s.getType())).map(v -> v.getVolume() + "=" + v.getOpen()).toList();
        List<String> resistanceList = top10.stream().filter(s -> "SELL".equalsIgnoreCase(s.getType())).map(v -> v.getVolume() + "=" + v.getClose()).toList();
        int avgRange = candle.getPriceLimit();

        indicator.setWeeklysupport(supportList.toString()); indicator.setWeeklyresistance(resistanceList.toString());
        indicator = signalCheckService.checkForWeekSignal(indicator, supportList, resistanceList, index_CurrentPrice, new BigDecimal(avgRange));

        List<PricesIndex> pricesDesc = new ArrayList<>(pricesList);
        Collections.reverse(pricesDesc);
        indicator.setHeikinAshiWeekly(heikinAshiCalculator.computeSignal(pricesDesc));
        indicator.setPsarFlagWeekly(psarCalculator.computeSignal(pricesDesc));
        indicator.setWeeklyRSI(rsiCalculator.getRSIData(pricesList.subList(0, Math.min(15, pricesList.size()))));

        indicator.setOneweek("Y");
        indicatorComputeService.getSuperTrend(indicator, indexes.getName(), "ONE_WEEK", pricesList);
        indicatorComputeService.getVwap(indicator, indexes.getName(), "ONE_WEEK", pricesList);
        indicatorService.saveIndicator(indicator);
    }

    // =========================================================
    // 4-Hour Volume
    // =========================================================
    @Transactional
    public void getFourHourVolumeData(String timeFrame, Indexes indexes, BigDecimal index_CurrentPrice,
            SmartConnect smartConnect, Candle candle, BigDecimal index_OpenPrice)
            throws IOException, SmartAPIException {
        List<PricesIndex> pricesList = pricesIndexRepo.findAll();
        pricesList.sort(Comparator.comparing(PricesIndex::getVolume, Comparator.reverseOrder()));
        List<PricesIndex> n_pricesList = pricesList.stream().limit(10).collect(Collectors.toList());
        List<String> supportList    = n_pricesList.stream().filter(s -> "BUY".equalsIgnoreCase(s.getType())).map(v -> v.getVolume() + "=" + v.getOpen()).collect(Collectors.toList());
        List<String> resistanceList = n_pricesList.stream().filter(s -> "SELL".equalsIgnoreCase(s.getType())).map(v -> v.getVolume() + "=" + v.getClose()).collect(Collectors.toList());
        int avgRange = candle.getPriceLimit();
        Indicator indicator = indicatorRepo.findByname(indexes.getName());
        indicator.setFourHoursupport(supportList.toString()); indicator.setFourHourresistance(resistanceList.toString());
        indicator = signalCheckService.checkForFourHourSignal(indicator, supportList, resistanceList, index_CurrentPrice, new BigDecimal(avgRange));
        indicatorRepo.save(indicator);
    }

    // =========================================================
    // Monthly Volume
    // =========================================================
    @Transactional
    public void getMonthlyVolumeData(String timeFrame, Indexes indexes, BigDecimal index_CurrentPrice,
            SmartConnect smartConnect, Candle candle, BigDecimal index_OpenPrice)
            throws IOException, SmartAPIException {
        List<PricesIndex> pricesList = pricesIndexRepo.findAll();
        List<PricesIndex> n_pricesList = pricesList.stream().limit(10).collect(Collectors.toList());
        List<String> supportList    = n_pricesList.stream().filter(s -> "BUY".equalsIgnoreCase(s.getType())).map(v -> v.getVolume() + "=" + v.getOpen()).collect(Collectors.toList());
        List<String> resistanceList = n_pricesList.stream().filter(s -> "SELL".equalsIgnoreCase(s.getType())).map(v -> v.getVolume() + "=" + v.getClose()).collect(Collectors.toList());
        int avgRange = candle.getPriceLimit();
        Indicator indicator = indicatorRepo.findByname(indexes.getName());
        indicator.setMonthlysupport(supportList.toString()); indicator.setMonthlyresistance(resistanceList.toString());
        indicator = signalCheckService.checkForMonthlySignal(indicator, supportList, resistanceList, index_CurrentPrice, new BigDecimal(avgRange));
        indicatorRepo.save(indicator);
    }

    // =========================================================
    // Intraday volume flow (NFO / MCX)
    // =========================================================

    public void getVolumeData(String timeFrame, String type, boolean testflag) throws SmartAPIException {
        try {
            com.crumbs.trade.dto.StrategyDTO strategyModified = strategyHelperService.getStrategyDetails("NIFTY", type);
            com.crumbs.trade.entity.Strategy strategy = strategyHelperService.getChart(strategyModified.getSymbol(), strategyModified.getTradingsymbol(), strategyModified.getLive());
            SmartConnect smartConnect = angelOne.signIn();
            if (strategy == null) return;

            JSONObject jsonObject = smartConnect.getLTP(strategy.getExchange(), strategy.getTradingsymbol(), strategy.getToken());
            if (jsonObject == null) throw new Exception("Script is null");
            BigDecimal index_CurrentPrice = new BigDecimal(String.valueOf(jsonObject.get("ltp")));

            String fromDate, toDate;
            if (testflag) {
                fromDate = "2025-03-07 09:15"; toDate = "2025-03-07 15:30";
            } else {
                fromDate = chartService.getDate("FROM", type, 5);
                toDate   = chartService.getDate("TO",   type, 5);
            }

            JSONObject requestObject = new JSONObject();
            requestObject.put("exchange", strategy.getExchange()); requestObject.put("symboltoken", strategy.getToken());
            requestObject.put("interval", timeFrame); requestObject.put("fromdate", fromDate); requestObject.put("todate", toDate);
            JSONArray responseArray = smartConnect.candleData(requestObject);
            if (responseArray == null) return;

            responseArray.forEach(item -> {
                JSONArray ohlcArray = (JSONArray) item;
                BigDecimal open = new BigDecimal(String.valueOf(ohlcArray.getDouble(1)));
                BigDecimal high = new BigDecimal(String.valueOf(ohlcArray.getDouble(2)));
                BigDecimal low  = new BigDecimal(String.valueOf(ohlcArray.getDouble(3)));
                BigDecimal close = new BigDecimal(String.valueOf(ohlcArray.getDouble(4)));
                BigDecimal volume = new BigDecimal(String.valueOf(ohlcArray.getDouble(5)));
                if (type.equalsIgnoreCase("NFO")) {
                    PricesNifty prices = new PricesNifty();
                    prices.setHigh(high); prices.setLow(low); prices.setClose(close); prices.setOpen(open);
                    prices.setVolume(volume); prices.setRange(high.subtract(low)); prices.setName(strategy.getName());
                    prices.setTimestamp(ohlcArray.getString(0)); prices.setType(priceUtilService.getPriceType(open, close));
                    prices.setCurrentprice(index_CurrentPrice);
                    pricesNiftyRepo.save(prices);
                } else {
                    PricesMcx prices = new PricesMcx();
                    prices.setHigh(high); prices.setLow(low); prices.setClose(close); prices.setOpen(open);
                    prices.setVolume(volume); prices.setRange(high.subtract(low)); prices.setName(strategy.getName());
                    prices.setTimestamp(ohlcArray.getString(0)); prices.setType(priceUtilService.getPriceType(open, close));
                    prices.setCurrentprice(index_CurrentPrice);
                    pricesMcxRepo.save(prices);
                }
            });

            if (type.equalsIgnoreCase("NFO")) {
                indicatorComputeService.updateHeikinAshi(strategy.getName(), null, type);
                percentageCalc();
                signalCheckService.getSignal(index_CurrentPrice);
                signalCheckService.monitorPsarAndheikinachiStrategy("NFO", index_CurrentPrice);
            } else {
                indicatorComputeService.updateHeikinAshi(strategy.getName(), null, type);
                percentageCalcMcx();
                signalCheckService.getSignalMcx(index_CurrentPrice);
                signalCheckService.monitorPsarAndheikinachiStrategy("MCX", index_CurrentPrice);
            }
        } catch (Exception e) {
            logger.error("Error in getVolumeData(): {}", e.getMessage());
        }
    }

    public BigDecimal getSingleVolumeData(String timeFrame, String type) throws SmartAPIException {
        BigDecimal firstVolume = BigDecimal.ZERO;
        try {
            com.crumbs.trade.dto.StrategyDTO strategyModified = strategyHelperService.getStrategyDetails("NIFTY", type);
            com.crumbs.trade.entity.Strategy strategy = strategyHelperService.getChart(strategyModified.getSymbol(), strategyModified.getTradingsymbol(), strategyModified.getLive());
            SmartConnect smartConnect = angelOne.signIn();
            if (strategy == null) return firstVolume;

            JSONObject jsonObject = smartConnect.getLTP(strategy.getExchange(), strategy.getTradingsymbol(), strategy.getToken());
            if (jsonObject == null) throw new Exception("Script is null");

            String format = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            JSONObject requestObject = new JSONObject();
            requestObject.put("exchange", strategy.getExchange()); requestObject.put("symboltoken", strategy.getToken());
            requestObject.put("interval", timeFrame); requestObject.put("fromdate", format + " 09:15"); requestObject.put("todate", format + " 09:20");
            JSONArray responseArray = smartConnect.candleData(requestObject);
            if (responseArray != null && responseArray.length() != 0) {
                JSONArray ohlcArray = responseArray.getJSONArray(0);
                firstVolume = new BigDecimal(String.valueOf(ohlcArray.getDouble(5)));
            }
        } catch (Exception e) {
            logger.error("Error in getSingleVolumeData(): {}", e.getMessage());
        }
        return firstVolume;
    }

    // =========================================================
    // Volume percentage calculations
    // =========================================================

    public BigDecimal percentageCalc() throws SmartAPIException {
        List<PricesIndex> priceList = pricesIndexRepo.findAllByOrderByIdAsc();
        if (priceList != null) {
            BigDecimal volume     = getSingleVolumeData("FIVE_MINUTE", "NFO");
            BigDecimal percentage = calcPercentage(volume);
            BigDecimal result     = volume.multiply(percentage).divide(new BigDecimal("100"));
            priceList.stream().map(p -> pricesIndexRepo.save(getPercVolume(p, result))).collect(Collectors.toList());
        }
        return null;
    }

    public BigDecimal percentageCalcMcx() {
        List<PricesMcx> priceList = pricesMcxRepo.findAllByOrderByIdDesc();
        if (priceList != null) {
            BigDecimal result = priceList.get(1).getVolume().multiply(new BigDecimal("10")).divide(new BigDecimal("100"));
            priceList.stream().map(p -> pricesMcxRepo.save(getPercVolumeMcx(p, result))).collect(Collectors.toList());
        }
        return null;
    }

    public PricesIndex getPercVolume(PricesIndex prices, BigDecimal firstVolume) {
        prices.setPercentage(prices.getVolume().subtract(firstVolume));
        return prices;
    }

    public PricesMcx getPercVolumeMcx(PricesMcx prices, BigDecimal firstVolume) {
        prices.setPercentage(prices.getVolume().subtract(firstVolume));
        return prices;
    }

    public BigDecimal calcPercentage(BigDecimal volume) {
        if      (volume.compareTo(new BigDecimal("15000")) > 0)                                                              return new BigDecimal("5");
        else if (volume.compareTo(new BigDecimal("15000")) < 0 && volume.compareTo(new BigDecimal("13000")) > 0)             return new BigDecimal("10");
        else if (volume.compareTo(new BigDecimal("13000")) < 0 && volume.compareTo(new BigDecimal("10000")) > 0)             return new BigDecimal("12");
        else if (volume.compareTo(new BigDecimal("10000")) < 0 && volume.compareTo(new BigDecimal("8000"))  > 0)             return new BigDecimal("15");
        else if (volume.compareTo(new BigDecimal("8000"))  < 0 && volume.compareTo(new BigDecimal("6000"))  > 0)             return new BigDecimal("20");
        else if (volume.compareTo(new BigDecimal("6000"))  < 0 && volume.compareTo(new BigDecimal("3000"))  > 0)             return new BigDecimal("25");
        else if (volume.compareTo(new BigDecimal("3000"))  < 0 && volume.compareTo(new BigDecimal("1000"))  > 0)             return new BigDecimal("30");
        else if (volume.compareTo(new BigDecimal("1000"))  < 0 && volume.compareTo(new BigDecimal("500"))   > 0)             return new BigDecimal("35");
        return new BigDecimal("40");
    }

    // =========================================================
    // Volume utility
    // =========================================================

    public static String checkLastVolumeVsAvg(List<PricesIndex> pricesList) {
        if (pricesList == null || pricesList.size() < 6) return null;
        int lastIndex = pricesList.size() - 1;
        PricesIndex lastPrice = pricesList.get(lastIndex);
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = lastIndex - 5; i < lastIndex; i++) sum = sum.add(pricesList.get(i).getVolume());
        BigDecimal avg = sum.divide(BigDecimal.valueOf(5), BigDecimal.ROUND_HALF_UP);
        return (lastPrice.getVolume().compareTo(avg) > 0) ? "HIGH" : null;
    }
}