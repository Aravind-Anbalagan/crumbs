package com.crumbs.trade.service;

import java.io.IOException;
import java.text.ParseException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.CandlesDetails;
import com.crumbs.trade.dto.StrategyDTO;
import com.crumbs.trade.entity.Candle;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.Indicator;
import com.crumbs.trade.entity.Stoploss;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.CandleRepo;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.IndicatorRepo;
import com.crumbs.trade.repo.Nifty500Repo;
import com.crumbs.trade.repo.NiftyRepo;
import com.crumbs.trade.repo.PriceRepo;

/**
 * Orchestrator — owns the top-level scan loops and threading infrastructure.
 * All business logic has been moved to dedicated services.
 * Public API is fully preserved so no controller or scheduler needs to change.
 */
@Service
public class TaskService {

    Logger logger = LoggerFactory.getLogger(TaskService.class);

    // Thread pool shared across all parallel scan tasks
    private static final int SMART_API_THREADS = 2;
    private final ExecutorService sharedExecutor = Executors.newFixedThreadPool(SMART_API_THREADS);

    // =========================================================
    // Injected repos (only those still needed by TaskService)
    // =========================================================
    @Autowired AngelOne angelOne;
    @Autowired IndicatorRepo indicatorRepo;
    @Autowired IndexesRepo indexesRepo;
    @Autowired NiftyRepo niftyRepo;
    @Autowired CandleRepo candleRepo;
    @Autowired Nifty500Repo nifty500Repo;
    @Autowired PriceRepo priceRepo;

    // =========================================================
    // Injected split services
    // =========================================================
    @Autowired CandleFetchService candleFetchService;
    @Autowired VolumeAnalysisService volumeAnalysisService;
    @Autowired SignalCheckService signalCheckService;
    @Autowired IndicatorComputeService indicatorComputeService;
    @Autowired StockProcessingService stockProcessingService;
    @Autowired PriceUtilService priceUtilService;
    @Autowired StrategyHelperService strategyHelperService;
    @Autowired CombinedSignalService combinedSignalService;
    @Autowired ChartService chartService;

    // =========================================================
    // Top-level orchestration
    // =========================================================

    public void getSupportAndResistance(String indexName, String symbol, long timeId)
            throws IOException, SmartAPIException, ParseException {

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime startTime = LocalDateTime.now();
        logger.info("Processing started at: {}", startTime.format(formatter));

        SmartConnect smartConnect = angelOne.signIn();
        List<Indexes> indexesList = new ArrayList<>();

        if (timeId == 4L) indicatorRepo.deleteAll();

        if (indexName.equalsIgnoreCase("ALL")) {
            indexesList = indexesRepo.findByNameInAndExchange(nifty500Repo.getAllNames(), "NSE");
        } else if (indexName.equalsIgnoreCase("NIFTY50")) {
            indexesList = indexesRepo.findBySymbolIn(niftyRepo.getAllNames());
        } else if (indexName != null) {
            Indexes indexes = indexesRepo.findByNameAndSymbolAndExchange(indexName, symbol, "NSE");
            indexesList.add(indexes);
        }

        Map<Long, Candle> candleMap = Stream.of(2L, 3L, 4L, 5L, 6L)
                .map(id -> candleRepo.findById(id).orElse(null))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toMap(Candle::getId, c -> c));

        // Step 1 — Day candle scan
        read_Day_Candle(smartConnect, indexesList, candleMap, timeId);

        // Step 2 — Weekly candle + combined signals (daily run only)
        if (timeId == 4L) {
            read_weekly_candle(smartConnect, fetchIndexes(), candleMap);
            List<Indicator> allIndicators = indicatorRepo.findByOnedayIsNotNullAndOneweekIsNotNull();
            combinedSignalService.updateCombinedSignals(allIndicators);
        }

        timeDisplay(startTime, formatter);
    }

    // =========================================================
    // Day candle parallel loop
    // =========================================================

    public void read_Day_Candle(SmartConnect smartConnect, List<Indexes> indexesList,
                                Map<Long, Candle> candleMap, long timeId) {
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(0);
        List<String> optionNameList = niftyRepo.getAllNames();
        CountDownLatch latch = new CountDownLatch(indexesList.size());

        // Build sector map once, shared read-only
        Map<String, String> sectorMap = nifty500Repo.findAll().stream()
                .collect(Collectors.toMap(
                    n -> n.getName().toLowerCase(),
                    n -> n.getSector() != null ? n.getSector() : "Unknown",
                    (a, b) -> a));

        for (Indexes index : indexesList) {
            sharedExecutor.submit(() -> {
                try {
                    Candle dayCandle = candleMap.get(timeId);
                    if (dayCandle != null && "Y".equalsIgnoreCase(dayCandle.getActive())) {
                        boolean done = false; int attempts = 0; long backoff = 1000;
                        while (!done && attempts < 5) {
                            try {
                                candleFetchService.getDaysCandleData(index, smartConnect, dayCandle, optionNameList, sectorMap);
                                done = true;
                            } catch (Exception e) {
                                if (isRateLimitError(e)) {
                                    attempts++;
                                    logger.warn("Rate limit for {}. Retry in {} ms ({}/5)", index.getName(), backoff, attempts);
                                    Thread.sleep(backoff); backoff *= 2;
                                } else {
                                    logger.error("Error processing {}: {}", index.getName(), e.getMessage(), e); return;
                                }
                            }
                        }
                    }
                    if (counter.incrementAndGet() % 100 == 0) {
                        Runtime rt = Runtime.getRuntime();
                        logger.info("Used memory after {} records: {} MB", counter.get(), (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024);
                    }
                } catch (Exception e) {
                    logger.error("Unexpected error in {}: {}", index.getName(), e.getMessage(), e);
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            if (!latch.await(2, TimeUnit.HOURS)) logger.warn("Timeout waiting for tasks to finish");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // =========================================================
    // Weekly candle parallel loop
    // =========================================================

    public void read_weekly_candle(SmartConnect smartConnect, List<Indexes> indexesList,
                                   Map<Long, Candle> candleMap) {
        Candle weeklyCandle = candleMap.get(5L);
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(0);
        if (weeklyCandle == null || !"Y".equalsIgnoreCase(weeklyCandle.getActive())) {
            logger.warn("Weekly candle inactive or missing, skipping");
            return;
        }

        CountDownLatch latch = new CountDownLatch(indexesList.size());
        for (Indexes index : indexesList) {
            sharedExecutor.submit(() -> {
                try {
                    int attempts = 0; long backoff = 1000;
                    while (attempts < 5) {
                        try { candleFetchService.getWeeklyCandleData(index, smartConnect, weeklyCandle); break; }
                        catch (SmartAPIException e) { logger.error("SmartAPI error for {}: {}", index.getName(), e.getMessage(), e); return; }
                        catch (Exception e) {
                            if (isRateLimitError(e)) {
                                attempts++;
                                logger.warn("Rate limit for {}. Retry in {} ms ({}/5)", index.getName(), backoff, attempts);
                                Thread.sleep(backoff); backoff *= 2;
                            } else { logger.error("Error processing {}: {}", index.getName(), e.getMessage(), e); return; }
                        }
                    }
                    int processed = counter.incrementAndGet();
                    if (processed % 100 == 0) {
                        Runtime rt = Runtime.getRuntime();
                        logger.info("Used memory after {} records: {} MB", processed, (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); logger.warn("Task interrupted for {}", index.getName());
                } catch (Exception e) {
                    logger.error("Unexpected error in {}: {}", index.getName(), e.getMessage(), e);
                } finally { latch.countDown(); }
            });
        }

        try {
            if (!latch.await(2, TimeUnit.HOURS)) logger.warn("Timeout waiting for weekly tasks");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // =========================================================
    // Fetch eligible indexes for weekly scan
    // =========================================================

    private List<Indexes> fetchIndexes() {
        List<Indexes> indexesList = new ArrayList<>();
        List<Indicator> indicators = indicatorRepo.findByPsarFlagDayInAndHeikinAshiDayIn(
                Arrays.asList("FIRST BUY", "FIRST SELL"), Arrays.asList("FIRST BUY", "FIRST SELL"));
        indicators.forEach(symbol -> indexesList.add(indexesRepo.findBySymbol(symbol.getTradingSymbol())));
        logger.info("Eligible Weekly Stock {}", indexesList.size());
        return indexesList;
    }

    // =========================================================
    // Chart price saving (called from controller)
    // =========================================================

    public CandlesDetails saveChartPrice(Strategy strategy, String candleTimeFrame) {
        SmartConnect smartConnect = angelOne.signIn();
        CandlesDetails candleStick = new CandlesDetails();
        String format = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date());
        List<Integer> MAX_List = new ArrayList<>(), MIN_List = new ArrayList<>();

        org.json.JSONObject requestObject = new org.json.JSONObject();
        requestObject.put("exchange", strategy.getExchange());
        requestObject.put("symboltoken", strategy.getToken());
        requestObject.put("interval", candleTimeFrame);

        int[] t = priceUtilService.adjustedTime();
        requestObject.put("todate",   format + " " + priceUtilService.intToString(t[2]) + ":" + priceUtilService.intToString(t[3]));
        requestObject.put("fromdate", format + " " + priceUtilService.intToString(t[0]) + ":" + priceUtilService.intToString(t[1]));

        org.json.JSONArray jsonArray = smartConnect.candleData(requestObject);
        if (jsonArray != null) {
            for (int i = 0; i < jsonArray.length(); i++) {
                org.json.JSONArray inner = (org.json.JSONArray) jsonArray.get(i);
                MAX_List.add(inner.getInt(2)); MIN_List.add(inner.getInt(3));
            }
            Collections.sort(MAX_List, Collections.reverseOrder());
            Collections.sort(MIN_List);
            candleStick.setMax(java.math.BigDecimal.valueOf(MAX_List.get(0)));
            candleStick.setMin(java.math.BigDecimal.valueOf(MIN_List.get(0)));
            candleStick.setStartTime(requestObject.get("fromdate").toString());
            candleStick.setEndTime(requestObject.get("todate").toString());
            savePrice(candleStick);
        }
        return candleStick;
    }

    @jakarta.transaction.Transactional
    public void savePrice(CandlesDetails candleStick) {
        Stoploss price = new Stoploss();
        price.setStartdate(candleStick.getStartTime());
        price.setEnddate(candleStick.getEndTime());
        price.setMin(candleStick.getMin());
        price.setMax(candleStick.getMax());
        price.setName("NIFTY");
        priceRepo.save(price);
    }

    // =========================================================
    // Public delegate methods — preserve API for controllers/schedulers
    // All heavy work is in the appropriate split service.
    // =========================================================

    /** @see StockProcessingService#findBullishStocks() */
    public void findBullishStocks() throws SmartAPIException { stockProcessingService.findBullishStocks(); }

    /** @see StockProcessingService#getResult() */
    public void getResult() { stockProcessingService.getResult(); }

    /** @see StockProcessingService#callAI() */
    public void callAI() { stockProcessingService.callAI(); }

    /** @see StockProcessingService#addOtherIndicator(String) */
    public List<Indicator> addOtherIndicator(String input) { return stockProcessingService.addOtherIndicator(input); }

    /** @see StockProcessingService#sendMsg() */
    public void sendMsg() { stockProcessingService.sendMsg(); }

    /** @see StockProcessingService#getEmailData() */
    public List<String[]> getEmailData() { return stockProcessingService.getEmailData(); }

    /** @see VolumeAnalysisService#getVolumeData(String, String, boolean) */
    public void getVolumeData(String timeFrame, String type, boolean testflag) throws SmartAPIException { volumeAnalysisService.getVolumeData(timeFrame, type, testflag); }

    /** @see StrategyHelperService#getStrategyDetails(String, String) */
    public StrategyDTO getStrategyDetails(String name, String exchange) { return strategyHelperService.getStrategyDetails(name, exchange); }

    /** @see StrategyHelperService#getChart(String, String, String) */
    public Strategy getChart(String indexName, String symbol, String live) { return strategyHelperService.getChart(indexName, symbol, live); }

    /** @see StrategyHelperService#getIndexChart(String, String) */
    public Indexes getIndexChart(String indexName, String symbol) { return strategyHelperService.getIndexChart(indexName, symbol); }

    // =========================================================
    // Timing utility
    // =========================================================

    public void timeDisplay(LocalDateTime startTime, DateTimeFormatter formatter) {
        LocalDateTime endTime = LocalDateTime.now();
        Duration duration = Duration.between(startTime, endTime);
        String durationFormatted = String.format("%02d:%02d:%02d", duration.toHours(), duration.toMinutesPart(), duration.toSecondsPart());
        logger.info("Processing completed. Start: {} | End: {} | Duration: {}",
                startTime.format(formatter), endTime.format(formatter), durationFormatted);
    }

    // =========================================================
    // Private helpers
    // =========================================================

    private boolean isRateLimitError(Exception e) {
        if (e == null) return false;
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains("rate limit");
    }
}