package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
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
import com.crumbs.trade.dto.Time;
import com.crumbs.trade.entity.Candle;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.PricesIndex;
import com.crumbs.trade.repo.PricesIndexRepo;

/**
 * Responsible for fetching OHLCV candle data from the Angel One Smart API
 * for all timeframes (Day, Hourly, Weekly, 4-Hour, Monthly).
 * Does NOT compute indicators — delegates to VolumeAnalysisService.
 */
@Service
public class CandleFetchService {

    Logger logger = LoggerFactory.getLogger(CandleFetchService.class);

    // Rate limiter shared across all candle fetch threads
    private static final Semaphore RATE_LIMITER = new Semaphore(5);
    private static final ScheduledExecutorService RATE_LIMIT_RELEASE = Executors.newScheduledThreadPool(1);

    @Autowired AngelOne angelOne;
    @Autowired PricesIndexRepo pricesIndexRepo;
    @Autowired PriceUtilService priceUtilService;
    @Autowired VolumeAnalysisService volumeAnalysisService;

    // =========================================================
    // Day candle
    // =========================================================

    public void getDaysCandleData(Indexes index, SmartConnect smartConnect, Candle candle,
                                  List<String> optionNameList, Map<String, String> sectorMap) {
        try {
            String timeframe  = candle.getTimeFrame();
            String stockName  = index.getName();
            Thread.sleep(200);

            JSONObject jsonObject = priceUtilService.getLTPWithRetry(smartConnect, index.getExchange(), index.getSymbol(), index.getToken());
            if (jsonObject != null) {
                BigDecimal index_CurrentPrice = new BigDecimal(String.valueOf(jsonObject.get("ltp")));
                BigDecimal index_OpenPrice    = new BigDecimal(String.valueOf(jsonObject.get("open")));

                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                String fromDate  = fmt.format(fmt.parse(priceUtilService.calculateDate(candle.getStartTime())));
                String toDateStr = fmt.format(fmt.parse(priceUtilService.calculateDate(candle.getEndTime())));

                JSONObject requestObject = new JSONObject();
                requestObject.put("exchange", index.getExchange());
                requestObject.put("symboltoken", index.getToken());
                requestObject.put("interval", candle.getTimeFrame());
                requestObject.put("fromdate", fromDate);
                requestObject.put("todate", toDateStr);

                JSONArray responseArray = safeCandleData(smartConnect, requestObject);
                if (responseArray != null) {
                    List<PricesIndex> pricesList = new ArrayList<>();
                    responseArray.forEach(item -> {
                        JSONArray ohlcArray = (JSONArray) item;
                        BigDecimal open   = new BigDecimal(String.valueOf(ohlcArray.getDouble(1)));
                        BigDecimal high   = new BigDecimal(String.valueOf(ohlcArray.getDouble(2)));
                        BigDecimal low    = new BigDecimal(String.valueOf(ohlcArray.getDouble(3)));
                        BigDecimal close  = new BigDecimal(String.valueOf(ohlcArray.getDouble(4)));
                        BigDecimal volume = new BigDecimal(String.valueOf(ohlcArray.getDouble(5)));
                        BigDecimal range  = high.subtract(low);
                        PricesIndex prices = new PricesIndex();
                        prices.setHigh(high); prices.setLow(low); prices.setClose(close);
                        prices.setOpen(open); prices.setVolume(volume); prices.setRange(range);
                        prices.setName(stockName);
                        prices.setTimestamp(ohlcArray.getString(0));
                        prices.setType(priceUtilService.getPriceType(open, close));
                        prices.setTimeframe(timeframe);
                        prices.setCpr(priceUtilService.calculateCpr(high, low, close).toString());
                        prices.setCurrentprice(index_CurrentPrice);
                        prices.setExchange(index.getExchange());
                        pricesList.add(prices);
                    });

                    if ("HOURLY".equalsIgnoreCase(candle.getName())) {
                        volumeAnalysisService.getHourlyVolumeData(timeframe, index, index_CurrentPrice, smartConnect, candle, pricesList);
                    } else if ("DAY".equalsIgnoreCase(candle.getName())) {
                        volumeAnalysisService.getDayVolumeData(timeframe, index, index_CurrentPrice, smartConnect, candle, index_OpenPrice, optionNameList, sectorMap, pricesList);
                    }
                } else {
                    logger.info("Unable to fetch candle data for {}", stockName);
                }
            }
        } catch (Exception | SmartAPIException e) {
            logger.error("Error processing {}: {}", index.getName(), e.getMessage());
        }
    }

    // =========================================================
    // Weekly candle
    // =========================================================

    public void getWeeklyCandleData(Indexes index, SmartConnect smartConnect, Candle candle)
            throws IOException, SmartAPIException, ParseException {
        List<PricesIndex> pricesList = fetchWeeklyPrices(index, smartConnect, candle);
        volumeAnalysisService.getWeeklyVolumeData("WEEK", index, getCurrentPrice(pricesList),
                smartConnect, candle, getOpenPrice(pricesList), pricesList);
    }

    public List<PricesIndex> fetchWeeklyPrices(Indexes index, SmartConnect smartConnect, Candle candle)
            throws IOException, SmartAPIException {
        List<PricesIndex> result = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");

        Calendar toCal = Calendar.getInstance();
        toCal.set(Calendar.HOUR_OF_DAY, 15); toCal.set(Calendar.MINUTE, 15); toCal.set(Calendar.SECOND, 0);
        Calendar fromCal = (Calendar) toCal.clone();
        fromCal.add(Calendar.DAY_OF_YEAR, -728);
        fromCal.set(Calendar.HOUR_OF_DAY, 9); fromCal.set(Calendar.MINUTE, 15);

        JSONObject requestObject = new JSONObject();
        requestObject.put("exchange", index.getExchange());
        requestObject.put("symboltoken", index.getToken());
        requestObject.put("interval", "ONE_DAY");
        requestObject.put("fromdate", sdf.format(fromCal.getTime()));
        requestObject.put("todate", sdf.format(toCal.getTime()));

        JSONArray responseArray = safeCandleData(smartConnect, requestObject);
        if (responseArray == null || responseArray.isEmpty()) {
            logger.warn("No daily candle data for {}", index.getName());
            return result;
        }

        SimpleDateFormat apiDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");
        List<DailyCandle> dailyList = new ArrayList<>();
        for (int i = 0; i < responseArray.length(); i++) {
            JSONArray arr = responseArray.getJSONArray(i);
            DailyCandle dc = new DailyCandle();
            try { dc.timestamp = apiDateFormat.parse(arr.getString(0)); }
            catch (ParseException e) { logger.error("Date parse error for {}: {}", index.getName(), arr.getString(0)); continue; }
            dc.open = BigDecimal.valueOf(arr.getDouble(1)); dc.high = BigDecimal.valueOf(arr.getDouble(2));
            dc.low  = BigDecimal.valueOf(arr.getDouble(3)); dc.close = BigDecimal.valueOf(arr.getDouble(4));
            dc.volume = BigDecimal.valueOf(arr.getDouble(5));
            dailyList.add(dc);
        }

        // Group into Monday-Friday weeks
        Map<String, List<DailyCandle>> weeklyGroups = new LinkedHashMap<>();
        Calendar weekCal = Calendar.getInstance();
        for (DailyCandle dc : dailyList) {
            weekCal.setTime(dc.timestamp);
            int dow = weekCal.get(Calendar.DAY_OF_WEEK);
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) continue;
            weekCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
            weeklyGroups.computeIfAbsent(sdf.format(weekCal.getTime()), k -> new ArrayList<>()).add(dc);
        }

        BigDecimal currentPrice = dailyList.isEmpty() ? BigDecimal.ZERO : dailyList.get(dailyList.size() - 1).close;
        for (Map.Entry<String, List<DailyCandle>> entry : weeklyGroups.entrySet()) {
            List<DailyCandle> weekCandles = entry.getValue();
            weekCandles.sort(Comparator.comparing(dc -> dc.timestamp));
            PricesIndex prices = new PricesIndex();
            prices.setTimestamp(entry.getKey());
            prices.setOpen(weekCandles.get(0).open);
            prices.setClose(weekCandles.get(weekCandles.size() - 1).close);
            prices.setHigh(weekCandles.stream().map(dc -> dc.high).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO));
            prices.setLow(weekCandles.stream().map(dc -> dc.low).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO));
            prices.setVolume(weekCandles.stream().map(dc -> dc.volume).reduce(BigDecimal.ZERO, BigDecimal::add));
            prices.setName(index.getName());
            prices.setType(priceUtilService.getPriceType(prices.getOpen(), prices.getClose()));
            prices.setRange(prices.getHigh().subtract(prices.getLow()));
            prices.setCurrentprice(currentPrice);
            prices.setExchange(index.getExchange());
            result.add(prices);
        }
        return result;
    }

    public List<Time> getWeeklySupportCandle() {
        List<Time> weeklyList = new ArrayList<>();
        LocalDate currentDate = LocalDate.now();
        LocalDate startDate = currentDate.minusYears(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate dateIterator = startDate;
        while (dateIterator.isBefore(currentDate)) {
            Time weekly = new Time();
            LocalDate monday = dateIterator.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).minusDays(3);
            weekly.setFromDate(monday.toString() + " 09:15");
            monday = dateIterator.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
            LocalDate friday = monday.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
            weekly.setToDate(friday.toString() + " 15:30");
            weeklyList.add(weekly);
            dateIterator = monday.plusWeeks(1);
        }
        return weeklyList;
    }

    // =========================================================
    // 4-Hour candle
    // =========================================================

    public void get4HourCandleData(Indexes index, SmartConnect smartConnect, Candle candle)
            throws SmartAPIException {
        try {
            pricesIndexRepo.deleteAll();
            JSONObject jsonObject = smartConnect.getLTP(index.getExchange(), index.getSymbol(), index.getToken());
            if (jsonObject != null) {
                BigDecimal index_CurrentPrice = new BigDecimal(String.valueOf(jsonObject.get("ltp")));
                BigDecimal index_OpenPrice    = new BigDecimal(String.valueOf(jsonObject.get("open")));
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                String fromDate = fmt.format(fmt.parse(priceUtilService.calculateDate("52WEEKS_EARLY")));
                String toDate   = fmt.format(fmt.parse(priceUtilService.calculateDate("HOUR")));

                JSONObject requestObject = new JSONObject();
                requestObject.put("exchange", index.getExchange()); requestObject.put("symboltoken", index.getToken());
                requestObject.put("interval", "ONE_HOUR"); requestObject.put("fromdate", fromDate); requestObject.put("todate", toDate);
                Thread.sleep(1000);
                JSONArray responseArray = smartConnect.candleData(requestObject);
                if (responseArray != null) {
                    responseArray.forEach(item -> {
                        JSONArray ohlcArray = (JSONArray) item;
                        PricesIndex prices = new PricesIndex();
                        prices.setOpen(new BigDecimal(String.valueOf(ohlcArray.getDouble(1))));
                        prices.setHigh(new BigDecimal(String.valueOf(ohlcArray.getDouble(2))));
                        prices.setLow(new BigDecimal(String.valueOf(ohlcArray.getDouble(3))));
                        prices.setClose(new BigDecimal(String.valueOf(ohlcArray.getDouble(4))));
                        prices.setVolume(new BigDecimal(String.valueOf(ohlcArray.getDouble(5))));
                        prices.setRange(prices.getHigh().subtract(prices.getLow()));
                        prices.setName(index.getName());
                        prices.setTimestamp(ohlcArray.getString(0));
                        prices.setType(priceUtilService.getPriceType(prices.getOpen(), prices.getClose()));
                        prices.setTimeframe(index.getTimeFrame());
                        pricesIndexRepo.save(prices);
                    });
                    get4HoursCandleData();
                    volumeAnalysisService.getFourHourVolumeData(candle.getTimeFrame(), index, index_CurrentPrice, smartConnect, candle, index_OpenPrice);
                }
            }
        } catch (Exception e) {
            logger.error("Error processing {}: {}", index.getName(), e.getMessage());
        }
    }

    public void get4HoursCandleData() {
        List<PricesIndex> pricesList = pricesIndexRepo.findAll();
        List<PricesIndex> modifiedPricesList = new ArrayList<>();
        BigDecimal open = null; BigDecimal close = null;
        List<BigDecimal> highList = new ArrayList<>(), lowList = new ArrayList<>(), volumeList = new ArrayList<>();
        int firstCandle = 0, secondCandle = 0;
        boolean firstCandle_Flag = true, secondCandle_Flag = false;
        String timeStamp = null;

        for (int i = 0; i < pricesList.size(); i++) {
            if (secondCandle == 0 && firstCandle_Flag) {
                String ts = pricesList.get(i).getTimestamp();
                if (ts.contains("09:15:00") || ts.contains("10:15:00") || ts.contains("11:15:00") || ts.contains("12:15:00")) {
                    firstCandle++;
                    if (ts.contains("09:15:00")) { open = pricesList.get(i).getOpen(); timeStamp = ts; }
                    if (ts.contains("12:15:00"))   close = pricesList.get(i).getClose();
                    highList.add(pricesList.get(i).getHigh()); lowList.add(pricesList.get(i).getLow()); volumeList.add(pricesList.get(i).getVolume());
                }
            }
            if (firstCandle == 0 && secondCandle_Flag) {
                String ts = pricesList.get(i).getTimestamp();
                if (ts.contains("13:15:00") || ts.contains("14:15:00") || ts.contains("15:15:00")) {
                    secondCandle++;
                    if (ts.contains("13:15:00")) { open = pricesList.get(i).getOpen(); timeStamp = ts; }
                    if (ts.contains("15:15:00"))   close = pricesList.get(i).getClose();
                    highList.add(pricesList.get(i).getHigh()); lowList.add(pricesList.get(i).getLow()); volumeList.add(pricesList.get(i).getVolume());
                }
            }
            if (firstCandle == 4) {
                secondCandle_Flag = true; firstCandle = 0; firstCandle_Flag = false;
                modifiedPricesList.add(createModifiedList(open, close, highList, lowList, volumeList, timeStamp, pricesList.get(i).getName()));
                highList.clear(); lowList.clear(); volumeList.clear();
            }
            if (secondCandle_Flag && secondCandle == 3) {
                firstCandle_Flag = true; secondCandle_Flag = false; secondCandle = 0;
                modifiedPricesList.add(createModifiedList(open, close, highList, lowList, volumeList, timeStamp, pricesList.get(i).getName()));
                highList.clear(); lowList.clear(); volumeList.clear();
            }
        }
        pricesIndexRepo.deleteAll();
        modifiedPricesList.forEach(prices -> {
            prices.setType(priceUtilService.getPriceType(prices.getOpen(), prices.getClose()));
            prices.setRange(prices.getHigh().subtract(prices.getLow()));
            pricesIndexRepo.save(prices);
        });
    }

    // =========================================================
    // Monthly candle
    // =========================================================

    public void getMonthlyCandleData(Indexes index, SmartConnect smartConnect, Candle candle)
            throws SmartAPIException {
        try {
            pricesIndexRepo.deleteAll();
            JSONObject jsonObject = smartConnect.getLTP(index.getExchange(), index.getSymbol(), index.getToken());
            if (jsonObject != null) {
                BigDecimal index_CurrentPrice = new BigDecimal(String.valueOf(jsonObject.get("ltp")));
                BigDecimal index_OpenPrice    = new BigDecimal(String.valueOf(jsonObject.get("open")));
                String fromDate = priceUtilService.getStartOfMonthExcludingWeekends();
                String toDate   = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new SimpleDateFormat("yyyy-MM-dd HH:mm").parse(priceUtilService.calculateDate("TODAY")));

                JSONObject requestObject = new JSONObject();
                requestObject.put("exchange", index.getExchange()); requestObject.put("symboltoken", index.getToken());
                requestObject.put("interval", candle.getTimeFrame()); requestObject.put("fromdate", fromDate); requestObject.put("todate", toDate);
                Thread.sleep(1000);
                JSONArray responseArray = smartConnect.candleData(requestObject);
                if (responseArray != null) {
                    responseArray.forEach(item -> {
                        JSONArray ohlcArray = (JSONArray) item;
                        PricesIndex prices = new PricesIndex();
                        prices.setOpen(new BigDecimal(String.valueOf(ohlcArray.getDouble(1))));
                        prices.setHigh(new BigDecimal(String.valueOf(ohlcArray.getDouble(2))));
                        prices.setLow(new BigDecimal(String.valueOf(ohlcArray.getDouble(3))));
                        prices.setClose(new BigDecimal(String.valueOf(ohlcArray.getDouble(4))));
                        prices.setVolume(new BigDecimal(String.valueOf(ohlcArray.getDouble(5))));
                        prices.setRange(prices.getHigh().subtract(prices.getLow()));
                        prices.setName(index.getName());
                        prices.setTimestamp(ohlcArray.getString(0));
                        prices.setType(priceUtilService.getPriceType(prices.getOpen(), prices.getClose()));
                        prices.setTimeframe(candle.getTimeFrame());
                        pricesIndexRepo.save(prices);
                    });
                    getmonthlyCandleData();
                    volumeAnalysisService.getMonthlyVolumeData(candle.getTimeFrame(), index, index_CurrentPrice, smartConnect, candle, index_OpenPrice);
                }
            } else {
                throw new Exception("Script is null");
            }
        } catch (Exception e) {
            logger.error("Error processing {}: {}", index.getName(), e.getMessage());
        }
    }

    public void getmonthlyCandleData() {
        List<PricesIndex> pricesList = pricesIndexRepo.findAll();
        List<PricesIndex> modifiedPricesList = new ArrayList<>();
        BigDecimal open = new BigDecimal("0"), close = new BigDecimal("0");
        List<BigDecimal> highList = new ArrayList<>(), lowList = new ArrayList<>(), volumeList = new ArrayList<>();
        String timeStamp = null;

        for (int i = 0; i < pricesList.size(); i++) {
            String curMonth  = pricesList.get(i).getTimestamp().substring(5, 7);
            String prevMonth = (i != pricesList.size() - 1) ? pricesList.get(i + 1).getTimestamp().substring(5, 7) : curMonth;

            if (curMonth.equalsIgnoreCase(prevMonth)) {
                if (open.compareTo(BigDecimal.ZERO) == 0) { open = pricesList.get(i).getOpen(); timeStamp = pricesList.get(i).getTimestamp(); }
                highList.add(pricesList.get(i).getHigh()); lowList.add(pricesList.get(i).getLow()); volumeList.add(pricesList.get(i).getVolume());
            } else {
                if (close.compareTo(BigDecimal.ZERO) == 0) close = pricesList.get(i).getClose();
                highList.add(pricesList.get(i).getHigh()); lowList.add(pricesList.get(i).getLow()); volumeList.add(pricesList.get(i).getVolume());
                modifiedPricesList.add(createModifiedList(open, close, highList, lowList, volumeList, timeStamp, pricesList.get(i).getName()));
                highList.clear(); lowList.clear(); volumeList.clear();
                open = BigDecimal.ZERO; close = BigDecimal.ZERO;
            }
        }
        pricesIndexRepo.deleteAll();
        modifiedPricesList.forEach(price -> {
            price.setType(priceUtilService.getPriceType(price.getOpen(), price.getClose()));
            price.setRange(price.getHigh().subtract(price.getLow()));
            pricesIndexRepo.save(price);
        });
    }

    // =========================================================
    // Safe candle fetch with retry + rate limiting
    // =========================================================

    /** Uses caller-provided SmartConnect session (preferred) */
    public JSONArray safeCandleData(SmartConnect smartConnect, JSONObject requestObject) {
        int retries = 10; int baseDelay = 1000;
        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                RATE_LIMITER.acquire();
                try {
                    JSONArray response = smartConnect.candleData(requestObject);
                    if (response != null && response.length() > 0) return response;
                } finally {
                    RATE_LIMIT_RELEASE.schedule(() -> RATE_LIMITER.release(), 1, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                logger.error("Candle fetch error (attempt {}): {}", attempt, e.getMessage());
            }
            try { Thread.sleep((long) baseDelay * attempt); } catch (InterruptedException ignored) {}
        }
        logger.warn("Null/empty candle data for {}", requestObject);
        return null;
    }

    /** Creates its own SmartConnect session (legacy fallback) */
    public JSONArray safeCandleData(JSONObject requestObject) {
        int retries = 3; int delay = 1000;
        for (int i = 0; i < retries; i++) {
            try {
                SmartConnect smartConnect = angelOne.signIn();
                JSONArray response = smartConnect.candleData(requestObject);
                if (response != null) return response;
                Thread.sleep(delay);
            } catch (Exception e) {
                e.printStackTrace();
                try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
            }
        }
        return null;
    }

    // =========================================================
    // Helpers
    // =========================================================

    public PricesIndex createModifiedList(BigDecimal open, BigDecimal close, List<BigDecimal> highList,
                                          List<BigDecimal> lowList, List<BigDecimal> volumeList,
                                          String timeStamp, String name) {
        PricesIndex priceEq = new PricesIndex();
        Collections.sort(highList, Collections.reverseOrder());
        priceEq.setHigh(highList.get(0));
        Collections.sort(lowList);
        priceEq.setLow(lowList.get(0));
        priceEq.setVolume(volumeList.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        priceEq.setTimestamp(timeStamp);
        priceEq.setOpen(open);
        priceEq.setClose(close);
        priceEq.setName(name);
        return priceEq;
    }

    private BigDecimal getCurrentPrice(List<PricesIndex> pricesList) {
        return pricesList.isEmpty() ? BigDecimal.ZERO : pricesList.get(0).getClose();
    }

    private BigDecimal getOpenPrice(List<PricesIndex> pricesList) {
        return pricesList.isEmpty() ? BigDecimal.ZERO : pricesList.get(0).getOpen();
    }

    // =========================================================
    // Inner POJO — daily candle data model
    // =========================================================

    public static class DailyCandle {
        public Date timestamp;
        public BigDecimal open, high, low, close, volume;
        public DailyCandle() {}
    }
}