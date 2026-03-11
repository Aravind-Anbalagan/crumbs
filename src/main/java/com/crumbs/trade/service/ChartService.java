package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.MessagingException;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.utils.Constants;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.cache.CandleCache;
import com.crumbs.trade.dto.Candlestick;
import com.crumbs.trade.dto.OHLC;
import com.crumbs.trade.dto.StrategyDTO;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.PricesIndex;
import com.crumbs.trade.entity.ResultVix;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.entity.Vix;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.PricesIndexRepo;
import com.crumbs.trade.repo.ResultVixRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.repo.VixRepo;
import com.crumbs.trade.utility.NSEWorkingDays;
import com.crumbs.trade.utility.TimerLog;

import jakarta.mail.internet.AddressException;
import jakarta.transaction.Transactional;

@Service
public class ChartService {

    Logger logger = LoggerFactory.getLogger(ChartService.class);

    @Autowired AngelOne angelOne;

    // ✅ Replaced: taskService.getHourAndMinutes() + taskService.getPriceType()
    @Autowired PriceUtilService priceUtilService;

    // ✅ Replaced: taskService.getStrategyDetails() + taskService.getChart()
    @Autowired StrategyHelperService strategyHelperService;

    @Autowired TrendLineService trendLineService;
    @Autowired HeikinAshiIndicator heikinAshiIndicator;
    @Autowired VixRepo vixRepo;
    @Autowired PSARIndicator pSARIndicator;
    @Autowired VolumeService volumeService;
    @Autowired ResultVixRepo resultVixRepo;
    @Autowired IndexesRepo indexesRepo;
    @Autowired AngelOneService angelOneService;
    @Autowired FlatTradeService flatTradeService;
    @Autowired PricesIndexRepo pricesIndexRepo;
    @Autowired SRService srService;
    @Autowired MovingAvgWithSMASmoothing movingAvgWithSMASmoothing;
    @Autowired TelegramService telegramService;
    @Autowired SuperTrendIndicator superTrendIndicator;
    @Autowired VWAPIndicator vwapIndicator;
    @Autowired StrategyRepo strategyRepo;
    @Autowired CandleCache candleCache;

    private static final String MCX_SYMBOL = "CRUDEOIL";

    private static final BigDecimal NIFTY_TARGET = new BigDecimal("20.00");
    private static final BigDecimal NIFTY_SL     = new BigDecimal("10.00");
    private static final BigDecimal MCX_TARGET    = new BigDecimal("50.00");
    private static final BigDecimal MCX_SL        = new BigDecimal("25.00");

    private static final String BUY  = "BUY";
    private static final String SELL = "SELL";

    // =========================================================
    // JSON / candle data fetch
    // =========================================================

    public JSONArray getJsonDetails(Indexes indexes, String type, boolean testflag,
                                    String fromDate, String toDate, String timeFrame) {
        try {
            SmartConnect smartConnect = angelOne.signIn();
            JSONObject requestObject = new JSONObject();
            requestObject.put("exchange",    indexes.getExchange());
            requestObject.put("symboltoken", indexes.getToken());
            requestObject.put("interval",    timeFrame);
            requestObject.put("fromdate",    fromDate);
            requestObject.put("todate",      toDate);
            return smartConnect.candleData(requestObject);
        } catch (Exception ex) {
            logger.error("Error in getJsonDetails() for {}", indexes.getName());
        }
        return null;
    }

    public OHLC getOHLC(JSONArray ohlcArray) {
        OHLC ohlc = new OHLC();
        ohlc.setTimestamp(String.valueOf(ohlcArray.getString(0)));
        ohlc.setOpen(new BigDecimal(String.valueOf(ohlcArray.getDouble(1))));
        ohlc.setHigh(new BigDecimal(String.valueOf(ohlcArray.getDouble(2))));
        ohlc.setLow(new BigDecimal(String.valueOf(ohlcArray.getDouble(3))));
        ohlc.setClose(new BigDecimal(String.valueOf(ohlcArray.getDouble(4))));
        ohlc.setVolume(new BigDecimal(String.valueOf(ohlcArray.getDouble(5))));
        ohlc.setRange(ohlc.getHigh().subtract(ohlc.getLow()));
        return ohlc;
    }

    // =========================================================
    // Date helpers
    // =========================================================

    /**
     * Calculate FROM / TO dates for API requests.
     *
     * ✅ Previously called taskService.getHourAndMinutes()
     *    Now calls priceUtilService.getHourAndMinutes() directly.
     */
    public String getDate(String timeline, String type, int interval) {
        LocalDate today = LocalDate.now();
        LocalDate lastWorkingDay = NSEWorkingDays.getLastWorkingDay(today);

        if (timeline.equalsIgnoreCase("FROM")) {
            // ✅ was: taskService.getHourAndMinutes(timeline, 5, type)
            return lastWorkingDay.toString()
                    .concat(priceUtilService.getHourAndMinutes(timeline, 5, type));
        } else {
            // ✅ was: taskService.getHourAndMinutes(timeline, interval, type)
            return new SimpleDateFormat("yyyy-MM-dd").format(new Date())
                    .concat(priceUtilService.getHourAndMinutes(timeline, interval, type));
        }
    }

    public String getCurrentCandleTime(String input, int intervalMinutes) {
        LocalDateTime now = LocalDateTime.now();
        int minute = (now.getMinute() / intervalMinutes) * intervalMinutes;
        LocalDateTime to = intervalMinutes >= 60
                ? now.withMinute(0).withSecond(0).withNano(0)
                : now.withMinute(minute).withSecond(0).withNano(0);
        LocalDateTime from = to.minusMinutes(intervalMinutes);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        return "FROM".equalsIgnoreCase(input) ? from.format(formatter) : to.format(formatter);
    }

    public static CandleRange getLastFiveMinuteRange(LocalDateTime time) {
        int minute = (time.getMinute() / 5) * 5;
        LocalDateTime to   = time.withMinute(minute).withSecond(0).withNano(0);
        LocalDateTime from = to.minusMinutes(5);
        return new CandleRange(from, to);
    }

    public static class CandleRange {
        private final LocalDateTime from;
        private final LocalDateTime to;
        public CandleRange(LocalDateTime from, LocalDateTime to) { this.from = from; this.to = to; }
        public LocalDateTime getFrom() { return from; }
        public LocalDateTime getTo()   { return to;   }
    }

    // =========================================================
    // Main chart pipeline
    // =========================================================

    public String readChartData(String timeFrame, String type, boolean testflag, String name,
                                String fromDate, String toDate, String symbol) throws SmartAPIException {
        try {
            Indexes indexes = indexesRepo.findByNameAndSymbol(name, symbol);
            Strategy strategy = getTokenDetails(name, type);

            if (strategy.getName() != null) {
                readCandle(indexes, type, testflag, timeFrame, name, fromDate, toDate, "HEIKIN_PSAR");

                List<Candlestick> heikinAshiList = heikinAshiIndicator.calculateHeikinAshiCandles(getValuesAsList(name));
                if (heikinAshiList == null || heikinAshiList.isEmpty()) return "No HeikinAshi Data Found";
                updateCandleData(heikinAshiList, "HEIKINACHI");

                List<Candlestick> pSARList = pSARIndicator.calculatePSAR(getValuesAsList(name));
                if (pSARList == null || pSARList.isEmpty()) return "No PSAR Data Found";
                updateCandleData(pSARList, "PSAR");

                List<Candlestick> maCandleList = movingAvgWithSMASmoothing.getMovingAverage(getValuesAsList(name));
                if (maCandleList != null && !maCandleList.isEmpty()) updateCandleData(maCandleList, "MA");

                List<Candlestick> superTrendList = superTrendIndicator.calculateSuperTrend(getValuesAsList(name));
                if (superTrendList != null && !superTrendList.isEmpty()) updateCandleData(superTrendList, "SUPER_TREND");
                else logger.warn("SuperTrend returned no data for {}", name);

                List<Candlestick> vwapList = vwapIndicator.calculateVWAP(getValuesAsList(name));
                if (vwapList != null && !vwapList.isEmpty()) updateCandleData(vwapList, "VWAP");
                else logger.warn("VWAP returned no data for {}", name);

            } else {
                logger.error("No Strategy found for {}", name);
            }
        } catch (Exception e) {
            logger.error("Error in readChartData() for {}: {}", name, e.getMessage(), e);
        }
        return "Completed";
    }

    // =========================================================
    // Candle update helpers
    // =========================================================

    public void updateCandleData(List<Candlestick> list, String candleType) {
        if (list == null || list.isEmpty()) return;
        long t = TimerLog.start();
        List<Long> ids = list.stream().map(Candlestick::getId).collect(Collectors.toList());
        Map<Long, Vix> vixMap = vixRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(Vix::getId, v -> v));
        TimerLog.end(logger, "findAllById for " + candleType, t);

        t = TimerLog.start();
        List<Vix> toUpdate = new ArrayList<>();
        for (Candlestick c : list) {
            Vix vix = vixMap.get(c.getId());
            if (vix == null) continue;
            switch (candleType.toUpperCase()) {
                case "PSAR"        -> vix.setPsar(c.getSignal());
                case "HEIKINACHI"  -> { vix.setHeikinachi(c.getSignal()); vix.setCandleType(c.getCandleType()); }
                case "MA"          -> { vix.setSmoothma(c.getSmoothMA()); vix.setMasignal(c.getMasignal()); }
                case "SUPER_TREND" -> { vix.setSuperTrend(c.getSuperTrend()); vix.setSupertrendSignal(c.getSuperTrendSignal()); }
                case "VWAP"        -> { vix.setVwap(c.getVwap()); vix.setVwapSignal(c.getSignal()); }
            }
            toUpdate.add(vix);
        }
        TimerLog.end(logger, "build update list for " + candleType, t);

        t = TimerLog.start();
        vixRepo.saveAll(toUpdate);
        TimerLog.end(logger, "saveAll " + candleType + " (" + toUpdate.size() + " records)", t);
    }

    public void updateCandle(Candlestick candleStick, String candleType) {
        Optional<Vix> vixOptional = vixRepo.findById(candleStick.getId());
        if (vixOptional.isPresent()) {
            Vix vix = vixOptional.get();
            if      (candleType.equalsIgnoreCase("PSAR"))        vix.setPsar(candleStick.getSignal());
            else if (candleType.equalsIgnoreCase("HEIKINACHI"))  { vix.setHeikinachi(candleStick.getSignal()); vix.setCandleType(candleStick.getCandleType()); }
            else if (candleType.equalsIgnoreCase("MA"))          { vix.setSmoothma(candleStick.getSmoothMA()); vix.setMasignal(candleStick.getMasignal()); }
            else if (candleType.equalsIgnoreCase("SUPER_TREND")) { vix.setSuperTrend(candleStick.getSuperTrend()); vix.setSupertrendSignal(candleStick.getSuperTrendSignal()); }
            else if (candleType.equalsIgnoreCase("VWAP"))        { vix.setVwap(candleStick.getVwap()); vix.setVwapSignal(candleStick.getSignal()); }
            vixRepo.save(vix);
        }
    }

    // =========================================================
    // Token / strategy resolution
    // =========================================================

    /**
     * ✅ Previously called taskService.getStrategyDetails() + taskService.getChart()
     *    Now delegates directly to StrategyHelperService.
     */
    public Strategy getTokenDetails(String name, String exchange) {
        // ✅ was: taskService.getStrategyDetails(name, exchange)
        StrategyDTO strategyModified = strategyHelperService.getStrategyDetails(name, exchange);
        // ✅ was: taskService.getChart(...)
        Strategy strategy = strategyHelperService.getChart(
                strategyModified.getSymbol(),
                strategyModified.getTradingsymbol(),
                strategyModified.getLive());
        return strategy;
    }

    // =========================================================
    // Candle reading (DB + cache)
    // =========================================================

    public void readCandle(Indexes indexes, String type, boolean testflag, String timeFrame, String name,
                           String fromDate, String toDate, String tableName) {
        if (indexes == null) return;

        if ("HEIKIN_PSAR".equalsIgnoreCase(tableName)) {
            JSONArray responseArray = getJsonDetails(indexes, type, testflag, fromDate, toDate, timeFrame);
            if (responseArray == null) return;
            List<Vix> batch = new ArrayList<>();
            responseArray.forEach(item -> {
                OHLC ohlc = getOHLC((JSONArray) item);
                if (ohlc != null) batch.add(buildVix(ohlc, name));
            });
            if (!batch.isEmpty()) vixRepo.saveAll(batch);

        } else {
            String cacheKey = name + "_" + timeFrame;
            if (candleCache.isLoadedToday(cacheKey)) {
                logger.info("⚡ [CACHE] Hit for {} — updating latest candle only", cacheKey);
                updateLatestCandle(indexes, type, cacheKey, timeFrame);
            } else {
                logger.info("📦 [CACHE] Miss for {} — full fetch from AngelOne", cacheKey);
                long t = TimerLog.start();
                JSONArray responseArray = getJsonDetails(indexes, type, testflag, fromDate, toDate, timeFrame);
                TimerLog.end(logger, "AngelOne API full fetch for " + name, t);
                if (responseArray == null) return;
                logger.info("📊 {} candles fetched for {}", responseArray.length(), name);

                t = TimerLog.start();
                List<PricesIndex> candles = new ArrayList<>();
                responseArray.forEach(item -> {
                    OHLC ohlc = getOHLC((JSONArray) item);
                    if (ohlc != null) candles.add(buildPricesIndex(ohlc, name, indexes.getExchange()));
                });
                TimerLog.end(logger, "Build in-memory list for " + name, t);
                candleCache.loadAll(cacheKey, candles);
                logger.info("✅ [CACHE] {} candles loaded for {}", candles.size(), cacheKey);
            }
        }
    }

    private void updateLatestCandle(Indexes indexes, String type, String cacheKey, String timeFrame) {
        int intervalMinutes = SRService.TimeFrame.valueOf(timeFrame).getCandleMinutes();
        String from = getCurrentCandleTime("FROM", intervalMinutes);
        String to   = getCurrentCandleTime("TO",   intervalMinutes);
        logger.info("🕯 [INCREMENTAL] Fetching latest candle for {} | from={} to={}", cacheKey, from, to);
        JSONArray responseArray = getJsonDetails(indexes, type, false, from, to, timeFrame);
        if (responseArray == null || responseArray.isEmpty()) return;
        OHLC ohlc = getOHLC((JSONArray) responseArray.get(responseArray.length() - 1));
        if (ohlc == null) return;
        PricesIndex latest = buildPricesIndex(ohlc, cacheKey, indexes.getExchange());
        candleCache.addOrUpdateLatest(cacheKey, latest);
    }

    // =========================================================
    // Entity builders
    // =========================================================

    private PricesIndex buildPricesIndex(OHLC ohlc, String name, String exchange) {
        PricesIndex pi = new PricesIndex();
        pi.setTimestamp(formatTime(ohlc.getTimestamp()));
        pi.setClose(ohlc.getClose()); pi.setHigh(ohlc.getHigh());
        pi.setOpen(ohlc.getOpen());   pi.setLow(ohlc.getLow());
        pi.setName(name); pi.setVolume(ohlc.getVolume()); pi.setRange(ohlc.getRange());
        // ✅ was: taskService.getPriceType(...)
        pi.setType(priceUtilService.getPriceType(ohlc.getOpen(), ohlc.getClose()));
        pi.setExchange(exchange);
        return pi;
    }

    private Vix buildVix(OHLC ohlc, String name) {
        Vix vix = new Vix();
        vix.setTimestamp(ohlc.getTimestamp());
        vix.setClose(ohlc.getClose()); vix.setHigh(ohlc.getHigh());
        vix.setOpen(ohlc.getOpen());   vix.setLow(ohlc.getLow());
        vix.setName(name); vix.setVolume(ohlc.getVolume()); vix.setRange(ohlc.getRange());
        // ✅ was: taskService.getPriceType(...)
        vix.setType(priceUtilService.getPriceType(ohlc.getOpen(), ohlc.getClose()));
        return vix;
    }

    // =========================================================
    // Legacy save methods (unchanged)
    // =========================================================

    public void saveCandleData(OHLC ohlc, String name) {
        Vix vix = buildVix(ohlc, name);   // reuse builder
        vixRepo.save(vix);
    }

    public void saveCandleData_Index(OHLC ohlc, String name, String exchange) {
        PricesIndex pi = buildPricesIndex(ohlc, name, exchange);   // reuse builder
        pricesIndexRepo.save(pi);
    }

    public String formatTime(String input) {
        OffsetDateTime istDateTime = OffsetDateTime.parse(input);
        OffsetDateTime utcDateTime = istDateTime.withOffsetSameInstant(ZoneOffset.UTC);
        return utcDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    // =========================================================
    // Vix → Candlestick list
    // =========================================================

    public List<Candlestick> getValuesAsList(String name) {
        List<Vix> vixList = vixRepo.findByName(name);
        List<Candlestick> candlesticksList = new ArrayList<>();
        if (vixList != null && !vixList.isEmpty()) {
            for (Vix item : vixList) {
                Candlestick candlestick = new Candlestick(
                        item.getOpen(), item.getHigh(), item.getLow(), item.getClose(),
                        item.getId(), null, null, null);
                candlestick.setVolume(item.getVolume() != null ? item.getVolume() : BigDecimal.ZERO);
                candlesticksList.add(candlestick);
            }
        }
        return candlesticksList;
    }

    // =========================================================
    // Signal monitoring
    // =========================================================

    public void monitorSignal(String name, String type, boolean testFlag, int i)
            throws AddressException, MessagingException, IOException {
        Strategy strategy = getTokenDetails(name, type);
        SmartConnect smartconnect = angelOne.signIn();
        BigDecimal currentPrice = angelOneService.getcurrentPrice(smartconnect,
                strategy.getExchange(), strategy.getSymbol(), strategy.getToken());
        List<Vix> vixList = vixRepo.findAllByNameContainingOrderByIdDesc(name);
        ResultVix resultVix = resultVixRepo.findByActiveTrueAndName(name);

        if (vixList != null && !vixList.isEmpty()) {
            Vix vix = vixList.get(i);
            if (testFlag) currentPrice = vix.getClose();

            if (resultVix == null) {
                if (vix.getType().equalsIgnoreCase("BUY") && buyEntrySignal(vix)) {
                    if (compareHeikinAchiAndPsarCandle(vixList, i)) makeEntry(vix, strategy, "BUY", testFlag, currentPrice);
                    else logger.error("First Psar Failed");
                } else if (vix.getType().equalsIgnoreCase("SELL") && sellEntrySignal(vix)) {
                    if (compareHeikinAchiAndPsarCandle(vixList, i)) makeEntry(vix, strategy, "SELL", testFlag, currentPrice);
                    else logger.error("First Psar Failed");
                }
            } else {
                if      (vix.getType().equalsIgnoreCase("BUY")  && buyExitSignal(vix))  makeEntry(vix, strategy, "BUY",  testFlag, currentPrice);
                else if (vix.getType().equalsIgnoreCase("SELL") && sellExitSignal(vix)) makeEntry(vix, strategy, "SELL", testFlag, currentPrice);
            }
        }
    }

    // =========================================================
    // Entry / exit signal conditions
    // =========================================================

    public boolean buyEntrySignal(Vix vix) {
        String name = vix.getName();
        if ("NIFTY".equalsIgnoreCase(name)) {
            return "BUY".equalsIgnoreCase(vix.getHeikinachi())
                && "BUY".equalsIgnoreCase(vix.getSupertrendSignal())
                && "BUY".equalsIgnoreCase(vix.getPsar());
        } else if (MCX_SYMBOL.equalsIgnoreCase(name)) {
            return "BUY".equalsIgnoreCase(vix.getHeikinachi())
                && "BUY".equalsIgnoreCase(vix.getPsar())
                && "BUY".equalsIgnoreCase(vix.getSupertrendSignal());
        }
        return false;
    }

    public boolean sellEntrySignal(Vix vix) {
        String name = vix.getName();
        if ("NIFTY".equalsIgnoreCase(name)) {
            return "SELL".equalsIgnoreCase(vix.getHeikinachi())
                && "SELL".equalsIgnoreCase(vix.getPsar())
                && "SELL".equalsIgnoreCase(vix.getSupertrendSignal());
        } else if ("SILVERM".equalsIgnoreCase(name)) {
            return "SELL".equalsIgnoreCase(vix.getHeikinachi())
                && "SELL".equalsIgnoreCase(vix.getPsar())
                && "SELL".equalsIgnoreCase(vix.getSupertrendSignal());
        }
        return false;
    }

    public boolean buyExitSignal(Vix vix) {
        return vix.getType().equalsIgnoreCase("BUY")
            && vix.getHeikinachi().equalsIgnoreCase("BUY")
            && vix.getPsar().equalsIgnoreCase("BUY");
    }

    public boolean sellExitSignal(Vix vix) {
        return vix.getType().equalsIgnoreCase("SELL")
            && vix.getHeikinachi().equalsIgnoreCase("SELL")
            && vix.getPsar().equalsIgnoreCase("SELL");
    }

    // =========================================================
    // Exit from trade at market close
    // =========================================================

    public void exitFromTrade(String name, String type)
            throws AddressException, MessagingException, IOException {
        SmartConnect smartconnect = angelOne.signIn();
        Strategy strategy = getTokenDetails(name, type);
        BigDecimal currentPrice = angelOneService.getcurrentPrice(smartconnect,
                strategy.getExchange(), strategy.getSymbol(), strategy.getToken());
        ResultVix resultVix = resultVixRepo.findByActiveTrueAndName(name);

        if (resultVix != null) {
            String currentDate = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(Calendar.getInstance().getTime());
            int hour = 0, min = 0;
            if      ("NIFTY".equalsIgnoreCase(name))   { hour = 15; min = 20; }
            else if ("SILVERM".equalsIgnoreCase(name)) { hour = 23; min = 20; }

            if (isToday(currentDate) && IsExit(currentDate, hour, min)) {
                triggerExitOrder(resultVix, true);
                resultVix.setExitPrice(currentPrice);
                resultVix.setExitTime(currentDate);
                BigDecimal profitLoss = "BUY".equalsIgnoreCase(resultVix.getType())
                        ? resultVix.getExitPrice().subtract(resultVix.getEntryPrice())
                        : resultVix.getEntryPrice().subtract(resultVix.getExitPrice());
                resultVix.setResult(profitLoss.compareTo(BigDecimal.ZERO) > 0 ? "PROFIT" : "LOSS");
                resultVix.setActive(false);
                resultVixRepo.save(resultVix);
            }
        }
    }

    public static boolean isToday(String timestamp) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
        ZoneId zone = ZoneId.systemDefault();
        LocalDate givenDate = LocalDateTime.parse(timestamp, formatter).atZone(zone).toLocalDate();
        return givenDate.equals(LocalDate.now(zone));
    }

    public boolean IsExit(String input, int hour, int min) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
        ZoneId zone = ZoneId.systemDefault();
        LocalTime localTime = LocalDateTime.parse(input, formatter).atZone(zone).toLocalTime();
        return localTime.isAfter(LocalTime.of(hour, min));
    }

    // =========================================================
    // Trade entry / exit execution
    // =========================================================

    @SuppressWarnings("null")
    @Transactional
    public void makeEntry(Vix vix, Strategy strategy, String type, boolean testFlag, BigDecimal currentPrice)
            throws AddressException, MessagingException, IOException {
        String currentDate = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(Calendar.getInstance().getTime());
        ResultVix resultVix = resultVixRepo.findByActiveTrueAndName(vix.getName());
        boolean tradeFlag = false;
        SmartConnect smartconnect = angelOne.signIn();

        if (resultVix == null) {
            resultVix = new ResultVix();
            resultVix.setName(vix.getName());
            if (testFlag) { resultVix.setEntryTime(formatDateTime(vix.getTimestamp())); resultVix.setEntryPrice(vix.getOpen()); }
            else          { resultVix.setEntryTime(currentDate); }
            resultVix.setActive(true); resultVix.setTimestamp(vix.getTimestamp());
            resultVix.setType(type);   resultVix.setMa(vix.getMasignal());
            resultVix.setSuperTrend(vix.getSupertrendSignal());

            tradeFlag = "Y".equals(strategy.getLive());
            logger.info("Entry trade: Signal={}, Ma={}", type, resultVix.getMa());
            Token token = triggerEntryOrder(strategy, type, resultVix, tradeFlag);
            if (token != null) {
                resultVix.setLotSize(token.getQuantity()); resultVix.setToken(token.getToken());
                resultVix.setExchange(token.getExch_seg()); resultVix.setSymbol(token.getSymbol());
                resultVix.setEntryPrice(token.getCurrentPrice());
            }
            resultVixRepo.save(resultVix);

            if ("Y".equalsIgnoreCase(strategy.getAlert())) notifyTelegram(type);

        } else if (resultVix.getType() != null && !type.equalsIgnoreCase(resultVix.getType())) {
            tradeFlag = "Y".equals(strategy.getLive());
            Token token = triggerExitOrder(resultVix, tradeFlag);
            if (testFlag) {
                resultVix.setExitPrice(vix.getOpen());
                resultVix.setExitTime(formatDateTime(vix.getTimestamp()));
            } else {
                resultVix.setExitPrice(token.getCurrentPrice());
                resultVix.setExitTime(currentDate);
                BigDecimal entry = resultVix.getEntryPrice(), exit = resultVix.getExitPrice();
                if (entry == null || exit == null) { logger.error("Entry or Exit price missing for {}", resultVix.getName()); return; }
                BigDecimal profitLoss = exit.subtract(entry).setScale(2, RoundingMode.HALF_UP);
                int compare = profitLoss.compareTo(BigDecimal.ZERO);
                resultVix.setResult(compare > 0 ? "PROFIT" : compare < 0 ? "LOSS" : "NO CHANGE");
            }
            resultVix.setPoints(calculatePoints(resultVix));
            resultVix.setActive(false);
            tradeFlag = "Y".equals(strategy.getLive());
            logger.info("Exit trade: Signal={}, Ma={}", type, vix.getMasignal());
            resultVixRepo.save(resultVix);
            if ("Y".equalsIgnoreCase(strategy.getAlert())) notifyTelegram(String.format("EXIT %s | %s", strategy.getName(), type));
        }
    }

    // =========================================================
    // Telegram notification
    // =========================================================

    private void notifyTelegram(String message) {
        try {
            logger.info("📤 Attempting to send Telegram message: {}", message);
            boolean ok = telegramService.sendMessage(message);
            if (ok) logger.info("✅ Telegram notification sent");
            else    { logger.error("❌ Telegram notification FAILED for: {}", message); retryTelegramMessage(message, 3); }
        } catch (Exception e) {
            logger.error("💥 Exception while sending Telegram: {}", e.getMessage(), e);
        }
    }

    private void retryTelegramMessage(String message, int maxRetries) {
        for (int i = 1; i <= maxRetries; i++) {
            try {
                logger.info("🔄 Retry {}/{}", i, maxRetries);
                Thread.sleep(1000L * i);
                if (telegramService.sendMessage(message)) { logger.info("✅ Retry successful on attempt {}", i); return; }
            } catch (Exception e) {
                logger.error("❌ Retry {} failed: {}", i, e.getMessage());
            }
        }
        logger.error("💀 All retry attempts exhausted for: {}", message);
    }

    // =========================================================
    // Order placement
    // =========================================================

    public Token triggerExitOrder(ResultVix resultVix, boolean tradeFlag) {
        StrategyDTO strategyModified = new StrategyDTO();
        strategyModified.setName("NIFTY".equalsIgnoreCase(resultVix.getName()) ? "NIFTY" : resultVix.getName());
        strategyModified.setTradingsymbol(resultVix.getSymbol());
        String transactionType = resultVix.getType().equalsIgnoreCase("BUY")
                ? Constants.TRANSACTION_TYPE_SELL : Constants.TRANSACTION_TYPE_BUY;
        return placeOrder(strategyModified, transactionType, "S", tradeFlag);
    }

    public Token triggerEntryOrder(Strategy strategy, String type, ResultVix resultVix, boolean tradeFlag)
            throws AddressException, MessagingException, IOException {
        // ✅ was: taskService.getStrategyDetails(strategy.getName(), strategy.getExchange())
        StrategyDTO strategyModified = strategyHelperService.getStrategyDetails(strategy.getName(), strategy.getExchange());
        strategyModified = getNameAndTradingSymbol(strategyModified, type);
        return placeOrder(strategyModified, type, "B", tradeFlag);
    }

    public StrategyDTO getNameAndTradingSymbol(StrategyDTO strategy, String type)
            throws AddressException, MessagingException, IOException {
        if (strategy == null || strategy.getName() == null || strategy.getExchange() == null) {
            logger.warn("Invalid strategy data"); return strategy;
        }
        SmartConnect smartconnect = angelOne.signIn();
        BigDecimal currentPrice = angelOneService.getcurrentPrice(smartconnect,
                strategy.getExchange(), strategy.getTradingsymbol(), strategy.getToken());
        if (currentPrice == null) { logger.warn("Unable to fetch price for {}", strategy.getName()); return strategy; }

        String key = strategy.getName().trim().toUpperCase();
        int strikeInterval;
        switch (key) {
            case "NIFTY": case "CPR_STRATEGY": case "CRUDEOIL": strikeInterval = 50;   break;
            case "SILVERM":                                      strikeInterval = 1000; return strategy;
            default: logger.warn("Unknown symbol: {}", strategy.getName()); return strategy;
        }

        int nearestStrike = findNearestMultiple(currentPrice.intValue(), strikeInterval);
        String optionType = "BUY".equalsIgnoreCase(type) ? "CE" : "PE";
        if ("NIFTY".equalsIgnoreCase(key)) {
            nearestStrike += "CE".equalsIgnoreCase(optionType) ? -150 : 150;
        }
        String tradingSymbol = String.format("%s%s%d%s", strategy.getName(), strategy.getExpiry(), nearestStrike, optionType);
        logger.info("Generated Symbol: {} | Price: {} | Type: {} | Strike: {}", tradingSymbol, currentPrice, optionType, nearestStrike);
        strategy.setTradingsymbol(tradingSymbol);
        return strategy;
    }

    int findNearestMultiple(int number, int base) {
        int remainder = number % base;
        return remainder < base / 2 ? number - remainder : number + (base - remainder);
    }

    public Token placeOrder(StrategyDTO strategy, String transactionType, String flatTradeType, boolean tradeFlag) {
        SmartConnect smartconnect = angelOne.signIn();
        Token token = new Token();
        Indexes indexes = indexesRepo.findByNameAndSymbol(strategy.getName(), strategy.getTradingsymbol());
        if (indexes != null) {
            token.setVariety(Constants.VARIETY_NORMAL);     token.setExch_seg(indexes.getExchange());
            token.setOrderType(Constants.ORDER_TYPE_MARKET); token.setProductType(Constants.PRODUCT_CARRYFORWARD);
            token.setTransactionType(transactionType);       token.setQuantity(indexes.getLotsize());
            token.setToken(indexes.getToken());              token.setSymbol(indexes.getSymbol());
            BigDecimal currentPrice = angelOneService.getcurrentPrice(smartconnect,
                    indexes.getExchange(), indexes.getSymbol(), indexes.getToken());
            token.setCurrentPrice(currentPrice);
            if (tradeFlag) placeOrderInFlatTrade(token, flatTradeType);
        } else {
            logger.error("Failed to get trading symbol {} : {}", strategy.getName(), strategy.getTradingsymbol());
        }
        return token;
    }

    public void placeOrderInFlatTrade(Token token, String flatTradeType) {
        try {
            Token flatToken = new Token();
            flatToken.setExch_seg(token.getExch_seg());     flatToken.setSymbol(token.getSymbol());
            flatToken.setTransactionType(flatTradeType);    flatToken.setQuantity(token.getQuantity());
            flatTradeService.PlaceOrderInFlatTrade(flatToken);
        } catch (SmartAPIException | Exception e) {
            logger.error("Error placing order in FlatTrade: {}", e.getMessage());
        }
    }

    // =========================================================
    // Executed order monitoring
    // =========================================================

    public void lookForExecutedOrder(String name, String type, Vix vix, boolean testFlag) {
        ResultVix resultVix = resultVixRepo.findByActiveTrueAndName(name);
        Strategy strategy = getTokenDetails(name, type);
        BigDecimal currentPrice = BigDecimal.ZERO;
        if (resultVix == null) return;

        SmartConnect smartconnect = angelOne.signIn();
        currentPrice = testFlag ? vix.getClose()
                : angelOneService.getcurrentPrice(smartconnect, resultVix.getExchange(), resultVix.getSymbol(), resultVix.getToken());
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) == 0) return;

        String result = checkPrice(currentPrice, resultVix.getEntryPrice(), name);
        if (result == null) return;

        String exitTransactionType = "BUY".equalsIgnoreCase(resultVix.getType())
                ? Constants.TRANSACTION_TYPE_SELL : Constants.TRANSACTION_TYPE_BUY;

        boolean tradeFlag = "Y".equals(strategy.getLive());
        logger.info("Exit Trade | Symbol={} | Result={} | EntryPrice={} | LTP={}", name, result, resultVix.getEntryPrice(), currentPrice);
        Token token = placeOrder(setValues(resultVix), exitTransactionType, "S", tradeFlag);
        closeOrder(resultVix, token, currentPrice, vix, testFlag, result);
    }

    public static String checkPrice(BigDecimal currentPrice, BigDecimal executedPrice, String symbol) {
        BigDecimal target = getTarget(symbol);
        BigDecimal sl     = getSL(symbol);
        BigDecimal move   = currentPrice.subtract(executedPrice);
        if (move.compareTo(target) >= 0)          return "TARGET";
        if (move.compareTo(sl.negate()) <= 0)     return "SL";
        return null;
    }

    private static BigDecimal getTarget(String symbol) { return MCX_SYMBOL.equalsIgnoreCase(symbol) ? MCX_TARGET : NIFTY_TARGET; }
    private static BigDecimal getSL(String symbol)     { return MCX_SYMBOL.equalsIgnoreCase(symbol) ? MCX_SL     : NIFTY_SL; }

    public StrategyDTO setValues(ResultVix resultVix) {
        StrategyDTO strategy = new StrategyDTO();
        strategy.setToken(resultVix.getToken());
        strategy.setTradingsymbol(resultVix.getSymbol());
        strategy.setName(resultVix.getName());
        return strategy;
    }

    public boolean timeCheck(String timeStamp, String name, boolean testFlag) {
        if (testFlag) { if (IsExit(timeStamp, 15, 15) && "NIFTY".equalsIgnoreCase(name)) return true; }
        else {
            if (IsExit(timeStamp, 15, 20) && "NIFTY".equalsIgnoreCase(name))    return true;
            if (IsExit(timeStamp, 17, 00) && "CRUDEOIL".equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    @Transactional
    public void closeOrder(ResultVix resultVix, Token token, BigDecimal currentPrice,
                           Vix vix, boolean testFlag, String result) {
        String currentDate = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(Calendar.getInstance().getTime());
        if (testFlag) {
            resultVix.setExitPrice(token.getPrice() != null ? new BigDecimal(token.getPrice()) : currentPrice);
            resultVix.setExitTime(vix != null ? formatDateTime(vix.getTimestamp()) : null);
            if      (resultVix.getPoints() > 0) resultVix.setPoints(40);
            else if (resultVix.getPoints() < 0) resultVix.setPoints(-20);
        } else {
            resultVix.setExitPrice(token.getCurrentPrice());
            resultVix.setExitTime(currentDate);
            resultVix.setResult(result);
        }
        resultVix.setPoints(calculatePoints(resultVix));
        resultVix.setActive(false);
        resultVixRepo.save(resultVix);
    }

    // =========================================================
    // Misc helpers
    // =========================================================

    public int calculatePoints(ResultVix resultVix) {
        if (resultVix.getEntryPrice() == null || resultVix.getExitPrice() == null) return 0;
        return resultVix.getExitPrice().subtract(resultVix.getEntryPrice()).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    public int findMaxAndLowPrice(ResultVix resultVix, String startTimeStamp, String endTimeStamp, String type) {
        List<Vix> vixList = vixRepo.findAll();
        List<Vix> filteredVix = vixList.stream()
                .filter(v -> v.getTimestamp().compareTo(startTimeStamp) >= 0 && v.getTimestamp().compareTo(endTimeStamp) <= 0)
                .collect(Collectors.toList());
        if ("BUY".equalsIgnoreCase(type)) {
            return Collections.max(filteredVix, Comparator.comparing(Vix::getHigh))
                    .getHigh().subtract(resultVix.getEntryPrice()).intValue();
        } else {
            return resultVix.getEntryPrice().subtract(
                    Collections.min(filteredVix, Comparator.comparing(Vix::getLow)).getLow()).intValue();
        }
    }

    public static String formatDateTime(String dateStr) {
        return OffsetDateTime.parse(dateStr).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    public boolean compareHeikinAchiAndPsarCandle(List<Vix> vixList, int i) {
        if (vixList == null || vixList.isEmpty() || i < 0 || i >= vixList.size()) {
            logger.info("Invalid list or index {}", i); return false;
        }
        Vix current = vixList.get(i);
        String psar = current.getPsar(), heikinAchi = current.getHeikinachi();
        if (!psar.equalsIgnoreCase(heikinAchi)) {
            logger.info("Candle[{}] → PSAR={} ❌ Heikin={}", i, psar, heikinAchi); return false;
        }
        if (i + 2 < vixList.size()) {
            String psarI2 = vixList.get(i + 2).getPsar();
            if (psarI2.equalsIgnoreCase(psar)) {
                logger.info("Candle[{}] → PSAR same at i+2 → ❌ Not Fresh Start", i); return false;
            }
        }
        logger.info("Candle[{}] → Fresh PSAR Start ✅", i);
        return true;
    }

    public String getName() {
        return LocalTime.now().isAfter(LocalTime.of(15, 30)) ? "MCX" : "NIFTY";
    }

    public BigDecimal getCurrentPrice(String name) {
        try {
            SmartConnect smartconnect = angelOne.signIn();
            Strategy strategy = strategyRepo.findByName(name);
            return angelOneService.getcurrentPrice(smartconnect,
                    strategy.getExchange(), strategy.getTradingsymbol(), strategy.getToken());
        } catch (Exception e) {
            logger.error("Unable to get Current Price for {}", name);
            return BigDecimal.ZERO;
        }
    }
}