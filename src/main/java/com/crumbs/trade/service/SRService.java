package com.crumbs.trade.service;

import com.crumbs.trade.builder.LevelBuilder;
import com.crumbs.trade.cache.CandleCache;
import com.crumbs.trade.dto.*;
import com.crumbs.trade.entity.*;
import com.crumbs.trade.repo.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import com.angelbroking.smartapi.SmartConnect;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.utility.NSEWorkingDays;
import com.crumbs.trade.utility.TimerLog;
import com.crumbs.trade.entity.Level;

import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class SRService {

    @Autowired ChartService chartService;
    @Autowired PricesIndexRepo pricesIndexRepo;
    @Autowired IndexesRepo indexesRepo;
    @Autowired AngelOneService angelOneService;
    @Autowired AngelOne angelOne;
    @Autowired PriceActionService priceActionService;
    @Autowired TaskService taskService;
    @Autowired SignalsRepo signalRepo;
    @Autowired ChartRepo chartRepo;
    @Autowired StrategyRepo strategyRepo;
    @Autowired LevelRepository levelRepo;
    @Autowired LevelBuilder levelBuilder;
    @Autowired PredictivePriceActionService predictivePriceActionService;
    @Autowired CandleCache candleCache;

    private static final Map<String, CandleDTO> previousDayCache = new ConcurrentHashMap<>();
    private static final ZoneId NSE_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    Logger logger = LoggerFactory.getLogger(SRService.class);

    // ==================== TIMEFRAME ====================

    public enum TimeFrame {
        ONE_MINUTE(15, 1, 10),
        FIVE_MINUTE(50, 5, 10),
        FIFTEEN_MINUTE(100, 15, 30),
        THIRTY_MINUTE(150, 30, 50),
        ONE_HOUR(200, 60, 80),
        FOUR_HOUR(200, 60, 80),
        ONE_DAY(365, 1440, 365);

        private final int nseBestDays;
        private final int candleMinutes;
        private final int mcxBestDays;

        TimeFrame(int nseBestDays, int candleMinutes, int mcxBestDays) {
            this.nseBestDays  = nseBestDays;
            this.candleMinutes = candleMinutes;
            this.mcxBestDays  = mcxBestDays;
        }

        public int getCandleMinutes() { return candleMinutes; }

        public int getBestDays(Market market) {
            return market == Market.NSE ? nseBestDays : mcxBestDays;
        }

        public enum Market { NSE, MCX }
    }

    // ==================== CANDLE DATA ====================

 // SRService.getCandleData()
    public List<PricesIndex> getCandleData(CandleRequestDto candleRequestDto, String name, String symbol) {
        Indexes indexes = indexesRepo.findByNameAndSymbol(name, symbol);
        if (indexes != null) {
            chartService.readCandle(
                    indexes,
                    candleRequestDto.getType(),
                    false,
                    candleRequestDto.getTimeFrame(),
                    name,                           // ✅ explicit name
                    candleRequestDto.getFromDate(),
                    candleRequestDto.getToDate(),
                    name                            // ✅ tableName = name
            );
            String cacheKey = name + "_" + candleRequestDto.getTimeFrame();
            return candleCache.get(cacheKey); // ✅// ✅ always from cache
                        
        }
        return null;
    }

    public BigDecimal getCurrentPriceForIndex(String name, String symbol) {
        try {
            SmartConnect smartConnect = angelOne.signIn();
            Indexes indexes = indexesRepo.findByNameAndSymbol(name, symbol);
            return angelOneService.getcurrentPrice(smartConnect,
                    indexes.getExchange(), indexes.getSymbol(), indexes.getToken());
        } catch (Exception e) {
            logger.error("Unable to get price {} {}", name, symbol);
        }
        return null;
    }

    // ==================== TIMING HELPERS ====================

    private LocalDateTime getLastValidCandleCloseForMarket(TimeFrame tf, TimeFrame.Market market) {
        if (market == TimeFrame.Market.NSE) {
            return getLastValidCandleClose(tf);
        }

        LocalDateTime now = LocalDateTime.now(NSE_ZONE);
        LocalTime marketOpen  = LocalTime.of(9, 0);
        LocalTime marketClose = LocalTime.of(23, 30);

        if (tf == TimeFrame.ONE_DAY) {
            return now.toLocalTime().isBefore(marketClose)
                    ? LocalDate.now(NSE_ZONE).minusDays(1).atTime(marketClose)
                    : LocalDate.now(NSE_ZONE).atTime(marketClose);
        }
        if (now.toLocalTime().isBefore(marketOpen))  return LocalDate.now(NSE_ZONE).minusDays(1).atTime(marketClose);
        if (now.toLocalTime().isAfter(marketClose))  return LocalDate.now(NSE_ZONE).atTime(marketClose);

        int interval = tf.getCandleMinutes();
        LocalDateTime marketStart = LocalDate.now(NSE_ZONE).atTime(marketOpen);
        long completed = (ChronoUnit.MINUTES.between(marketStart, now) / interval) * interval;
        return marketStart.plusMinutes(completed);
    }

    private static LocalDateTime getLastValidCandleClose(TimeFrame tf) {
        LocalDateTime now = LocalDateTime.now(NSE_ZONE);

        if (tf == TimeFrame.ONE_DAY) {
            return now.toLocalTime().isBefore(LocalTime.of(15, 30))
                    ? LocalDate.now(NSE_ZONE).minusDays(1).atTime(15, 30)
                    : LocalDate.now(NSE_ZONE).atTime(15, 30);
        }

        LocalTime marketOpen  = LocalTime.of(9, 15);
        LocalTime marketClose = LocalTime.of(15, 30);

        if (now.toLocalTime().isBefore(marketOpen))  return LocalDate.now(NSE_ZONE).minusDays(1).atTime(15, 30);
        if (now.toLocalTime().isAfter(marketClose))  return LocalDate.now(NSE_ZONE).atTime(15, 30);

        int interval = tf.getCandleMinutes();
        LocalDateTime marketStart = LocalDate.now(NSE_ZONE).atTime(marketOpen);
        long completed = (ChronoUnit.MINUTES.between(marketStart, now) / interval) * interval;
        return marketStart.plusMinutes(completed);
    }

    public CandleRequestDto getCandleTiming(String timeFrame, String exchange) {
        CandleRequestDto candle = new CandleRequestDto();
        TimeFrame selected = TimeFrame.valueOf(timeFrame);
        TimeFrame.Market market = mapExchangeToMarket(exchange);

        LocalDateTime toDateTime   = getLastValidCandleCloseForMarket(selected, market);
        LocalDateTime fromDateTime = toDateTime.minusDays(selected.getBestDays(market));

        candle.setFromDate(fromDateTime.format(FORMATTER));
        candle.setToDate(toDateTime.format(FORMATTER));
        candle.setTimeFrame(timeFrame);
        candle.setType(exchange);
        return candle;
    }

    private TimeFrame.Market mapExchangeToMarket(String exchange) {
        return "MCX".equalsIgnoreCase(exchange) ? TimeFrame.Market.MCX : TimeFrame.Market.NSE;
    }

    // ==================== SIGNALS ====================

    public PriceActionResult getPriceAction(String timeFrame, String name, String exchange, String symbol) {
        pricesIndexRepo.deleteAll();
        CandleRequestDto candle = getCandleTiming(timeFrame, exchange);
        List<PricesIndex> candles = getCandleData(candle, name, symbol);

        if (candles != null && !candles.isEmpty()) {
            BigDecimal currentPrice = getCurrentPriceForIndex(name, symbol);
            return priceActionService.analyze(currentPrice, candles, timeFrame);
        }
        logger.error("Unable to get price action for {}", name);
        return null;
    }

    @Transactional
    public Signals getSignals(String name, String type) {
        Strategy strategy = getTokenDetails(name, type);
        Signals signal = new Signals();

        PriceActionResult pr = getPriceAction(
                "FIVE_MINUTE",
                strategy.getName(),
                strategy.getExchange(),
                strategy.getTradingsymbol()
        );

        if (pr != null) {
            String currentDate = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"));

            BigDecimal currentPrice = pr.getCurrentPrice();
            BigDecimal buffer = currentPrice.multiply(BigDecimal.valueOf(0.003));

            List<BigDecimal> supports = pr.getSupportLevels() != null
                    ? pr.getSupportLevels().stream().map(SRLevelDTO::getPrice).collect(java.util.stream.Collectors.toList())
                    : new ArrayList<>();
            List<BigDecimal> resistances = pr.getResistanceLevels() != null
                    ? pr.getResistanceLevels().stream().map(SRLevelDTO::getPrice).collect(java.util.stream.Collectors.toList())
                    : new ArrayList<>();

            BigDecimal nearestSupport = supports.stream()
                    .filter(s -> s.compareTo(currentPrice) <= 0)
                    .max(Comparator.naturalOrder()).orElse(null);

            BigDecimal nearestResistance = resistances.stream()
                    .filter(r -> r.compareTo(currentPrice) >= 0)
                    .min(Comparator.naturalOrder()).orElse(null);

            boolean nearSupport    = nearestSupport    != null && currentPrice.subtract(nearestSupport).compareTo(buffer) <= 0;
            boolean nearResistance = nearestResistance != null && nearestResistance.subtract(currentPrice).compareTo(buffer) <= 0;

            String finalSignal = "HOLD";
            if      (nearSupport && nearResistance) finalSignal = "HOLD";
            else if (nearSupport)                   finalSignal = "BUY";
            else if (nearResistance)                finalSignal = "SELL";

            signal.setFinals(finalSignal);
            signal.setName(name);
            signal.setCreatedAt(currentDate);
            signalRepo.save(signal);
        }

        return signal;
    }

    // ==================== PREVIOUS DAY OHLC ====================

    public CandleDTO getPreviousOHLC(String timeFrame, String name, String exchange, String symbol) {
        LocalDate today          = LocalDate.now();
        LocalDate lastWorkingDay = NSEWorkingDays.getLastWorkingDay(today);
        Strategy strategy = taskService.getChart(name,
                strategyRepo.findByName(name).getTradingsymbol(),
                strategyRepo.findByName(name).getLive());
        SmartConnect smartConnect = angelOne.signIn();

        String fromDate, toDate;
        if (name.equalsIgnoreCase("NIFTY")) {
            fromDate = NSEWorkingDays.getLastWorkingDay(lastWorkingDay) + " 09:15";
            toDate   = lastWorkingDay + " 15:30";
        } else {
            fromDate = NSEWorkingDays.getLastWorkingDay(lastWorkingDay) + " 09:00";
            toDate   = lastWorkingDay + " 23:30";
        }

        CandleDTO candleDTO = new CandleDTO();
        JSONObject requestObject = new JSONObject();
        requestObject.put("exchange",    strategy.getExchange());
        requestObject.put("symboltoken", strategy.getToken());
        requestObject.put("interval",    timeFrame);
        requestObject.put("fromdate",    fromDate);
        requestObject.put("todate",      toDate);

        JSONArray responseArray = smartConnect.candleData(requestObject);
        if (!responseArray.isEmpty()) {
            JSONArray ohlcArray = (JSONArray) responseArray.get(0);
            candleDTO.setOpen(new BigDecimal(String.valueOf(ohlcArray.getDouble(1))));
            candleDTO.setHigh(new BigDecimal(String.valueOf(ohlcArray.getDouble(2))));
            candleDTO.setLow(new BigDecimal(String.valueOf(ohlcArray.getDouble(3))));
            candleDTO.setClose(new BigDecimal(String.valueOf(ohlcArray.getDouble(4))));
        }
        return candleDTO;
    }

    public CandleDTO getPreviousDayCandle(String name, String exchange, String symbol) {
        String key = name + "|" + exchange + "|" + symbol;

        // ✅ Return from cache if already loaded today
        if (previousDayCache.containsKey(key)) {
            logger.debug("⚡ [CACHE] Previous day candle hit for {}", name);
            return previousDayCache.get(key);
        }

        // Fetch from AngelOne and cache it
        CandleDTO candle = getPreviousOHLC("ONE_DAY", name, exchange, symbol);
        previousDayCache.put(key, candle);
        logger.info("✅ [CACHE] Previous day candle cached for {}", name);
        return candle;
    }

    // ==================== INTRADAY ANALYSIS ====================

    public ChartDataDTO analyzeIntraday(String name, String timeFrame) {
        long total = TimerLog.start();

        long t = TimerLog.start();
        Strategy strategy = strategyRepo.findByName(name);  // ✅ single fetch
        String symbol   = strategy.getTradingsymbol();
        String exchange = getExchange(name);
        CandleRequestDto candle = getCandleTiming(timeFrame, exchange);
        candle.setName(name);                               // ✅ ensure name is set
        TimerLog.end(logger, "getCandleTiming", t);

        t = TimerLog.start();
        List<PricesIndex> candles = getCandleData(candle, name, symbol);
        TimerLog.end(logger, "getCandleData", t);

        if (candles == null || candles.isEmpty()) {
            logger.warn("⚠️ No candles available for {}", name);
            return null;
        }

        t = TimerLog.start();
        BigDecimal currentPrice = candles.get(candles.size() - 1).getClose();
        PriceActionResult result = predictivePriceActionService.analyzePredictive(currentPrice, candles, timeFrame);
        TimerLog.end(logger, "analyzePredictive", t);

        ChartDataDTO dto = new ChartDataDTO();
        dto.setSupportLevels(result.getSupportLevels());
        dto.setResistanceLevels(result.getResistanceLevels());

        t = TimerLog.start();
        dto.setPreviousDayCandle(getPreviousDayCandle(name, exchange, symbol));
        TimerLog.end(logger, "getPreviousDayCandle", t);

        dto.setCandles(toCandleList(candles));              // ✅ reuse in-memory list

        TimerLog.end(logger, "=== TOTAL analyzeIntraday ===", total);
        return dto;
    }

    // ✅ Convert PricesIndex list to CandleDTO list (no DB call)
    private List<CandleDTO> toCandleList(List<PricesIndex> pricesList) {
        ZoneId istZone = ZoneId.of("Asia/Kolkata");
        return pricesList.stream().map(p -> {
            CandleDTO c = new CandleDTO();
            c.setTime(Instant.parse(p.getTimestamp()).atZone(istZone).toEpochSecond());
            c.setOpen(p.getOpen());
            c.setHigh(p.getHigh());
            c.setLow(p.getLow());
            c.setClose(p.getClose());
            c.setVolume(p.getVolume() != null ? p.getVolume() : BigDecimal.ZERO);
            return c;
        }).collect(Collectors.toList());
    }

    // ==================== SAVE LEVELS ====================

    @Transactional
    public void saveLevels(String name, String timeFrame, ChartDataDTO dto) {
        if (dto == null) return;

        levelRepo.deleteBySymbolAndTimeframe(name, timeFrame);

        LocalDateTime now = LocalDateTime.now();
        List<Level> levels = new ArrayList<>();

        // Support levels (seq > 0)
        int seq = 1;
        if (dto.getSupportLevels() != null) {
            for (SRLevelDTO s : dto.getSupportLevels()) {
                levels.add(levelBuilder.buildPriceActionLevel(name, timeFrame, seq++, s.getPrice(), now));
            }
        }

        // Resistance levels (seq < 0)
        seq = -1;
        if (dto.getResistanceLevels() != null) {
            for (SRLevelDTO r : dto.getResistanceLevels()) {
                levels.add(levelBuilder.buildPriceActionLevel(name, timeFrame, seq--, r.getPrice(), now));
            }
        }

        if (!levels.isEmpty()) {
            levelRepo.saveAll(levels);
        }
    }

    // ==================== MISC HELPERS ====================

    public Strategy getTokenDetails(String name, String exchange) {
        StrategyDTO strategyModified = taskService.getStrategyDetails(name, exchange);
        return taskService.getChart(strategyModified.getSymbol(),
                strategyModified.getTradingsymbol(), strategyModified.getLive());
    }

    public String getChartDetails() {
        return chartRepo.findById(1L).map(Chart::getJson).orElse(null);
    }

    public String getExchange(String input) {
        return "NIFTY".equalsIgnoreCase(input) ? "NFO" : "MCX";
    }

    public List<CandleDTO> getcandleList() {
        List<CandleDTO> candles = new ArrayList<>();
        ZoneId istZone = ZoneId.of("Asia/Kolkata");

        for (PricesIndex p : pricesIndexRepo.findAll()) {
            ZonedDateTime istTime = Instant.parse(p.getTimestamp()).atZone(istZone);
            CandleDTO c = new CandleDTO();
            c.setTime(istTime.toEpochSecond());
            c.setOpen(p.getOpen());
            c.setHigh(p.getHigh());
            c.setLow(p.getLow());
            c.setClose(p.getClose());
            c.setVolume(p.getVolume() != null ? p.getVolume() : BigDecimal.ZERO);
            candles.add(c);
        }
        return candles;
    }
}