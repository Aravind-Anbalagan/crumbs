package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.FuturesConfigDto;
import com.crumbs.trade.entity.Futures;
import com.crumbs.trade.entity.FuturesBreakEvent;
import com.crumbs.trade.entity.FuturesConfig;
import com.crumbs.trade.entity.FuturesFilter;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.repo.FuturesBreakEventRepo;
import com.crumbs.trade.repo.FuturesConfigRepo;
import com.crumbs.trade.repo.FuturesFilterRepo;
import com.crumbs.trade.repo.FuturesRepo;
import com.crumbs.trade.repo.Nifty500Repo;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.utility.NSEWorkingDays;
import com.crumbs.trade.utility.NiftyIndexType;

@Service
public class FuturesStrategyService {

    // 1. Inject self lazily to avoid circular dependency
    @Autowired @Lazy private FuturesStrategyService self;
    private static final Logger logger = LogManager.getLogger(FuturesStrategyService.class);
    private static final String EXCHANGE = "NSE";
    @Autowired private AngelWebSocketService angelWebSocketService;
    // ✅ Tunable delay constants (ms)
    private static final int OHLC_FETCH_DELAY_MS  = 350;  // between each stock's OHLC call
    private static final int BATCH_FETCH_DELAY_MS = 200;  // between LTP batches
    private static final int RETRY_BASE_DELAY_MS  = 3000; // base retry delay
    private static final int RATE_LIMIT_SLEEP_MS  = 6000; // extra sleep on 503

    @Autowired private FuturesRepo futuresRepo;
    @Autowired private Nifty500Repo nifty500Repo;
    @Autowired private FuturesConfigRepo configRepo;
    @Autowired private FuturesFilterRepo filterRepo;
    @Autowired private IndexesRepo indexesRepo;
    @Autowired private PredictionService predictionService;
    @Autowired private AngelOne angelOne;
    @Autowired private TelegramService telegramService;
    @Autowired private FuturesBreakEventRepo futuresBreakEventRepo;
    @Autowired private SmcLiteService smcLiteService;

    // ─────────────────────────────────────────────
    //  NEW: Candlestick & SMC Domain Objects
    // ─────────────────────────────────────────────

    public static class HourlyCandle {
        String timestamp;
        BigDecimal open, high, low, close;
        long volume;

        public HourlyCandle(String timestamp, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {
            this.timestamp = timestamp;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
        }

        public BigDecimal getRange() {
            return high.subtract(low).abs();
        }

        public BigDecimal getBody() {
            return close.subtract(open).abs();
        }

        public BigDecimal getBodyStrengthPercent() {
            BigDecimal range = getRange();
            if (range.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
            return getBody().divide(range, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        }
    }

    public static class CandleMetrics {
        BigDecimal atr;
        BigDecimal recentSwingHigh;
        BigDecimal recentSwingLow;
        BigDecimal bodyStrengthPct;
        boolean isVolumeSurge;
        boolean isBullish;

        public CandleMetrics(BigDecimal atr, BigDecimal recentSwingHigh, BigDecimal recentSwingLow,
                             BigDecimal bodyStrengthPct, boolean isVolumeSurge, boolean isBullish) {
            this.atr = atr;
            this.recentSwingHigh = recentSwingHigh;
            this.recentSwingLow = recentSwingLow;
            this.bodyStrengthPct = bodyStrengthPct;
            this.isVolumeSurge = isVolumeSurge;
            this.isBullish = isBullish;
        }
    }

    public static class ExpiryOHLC {
        BigDecimal open, high, low, close;

        public ExpiryOHLC(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
            this.open = open; this.high = high; this.low = low; this.close = close;
        }

        public BigDecimal calculateRangePercent() {
            if (low == null || low.compareTo(BigDecimal.ZERO) == 0 || high == null) {
                return BigDecimal.ZERO;
            }
            return high.subtract(low).divide(low, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
    }

    // ─────────────────────────────────────────────
    //  Entry point
    // ─────────────────────────────────────────────


    public void executeAll() throws SmartAPIException {
        FuturesConfig masterConfig = configRepo.findByIndexType("NIFTY_50")
                .filter(c -> "Y".equalsIgnoreCase(c.getActive())).orElse(null);

        if (masterConfig == null) {
            logger.warn("NIFTY_500 config not active. Skipping execution.");
            return;
        }

        logger.info("Running master execution using NIFTY_500 with HIGH/LOW/RANGE tracking");
        executeMaster(masterConfig);
    }

    // ─────────────────────────────────────────────
    //  Master execution — SINGLE sign-in for all OHLC fetches
    // ─────────────────────────────────────────────

    private void executeMaster(FuturesConfig masterConfig) throws SmartAPIException {
        LocalDate expiryDate = resolveExecutionDate(masterConfig);
        List<Futures> futuresList = futuresRepo.findByIsNifty50True();

        if (futuresList.isEmpty()) {
            logger.warn("No NIFTY_500 stocks found");
            return;
        }

        SmartConnect sharedSession = angelOne.signIn();
        if (sharedSession == null) {
            logger.error("Failed to sign in. Aborting execution.");
            return;
        }

        Map<String, FuturesConfig> configMap = configRepo.findByActive("Y").stream()
                .collect(Collectors.toMap(FuturesConfig::getIndexType, c -> c));

        List<Indexes> indexesList = indexesRepo.findByNameInAndExchange(
                futuresList.stream().map(Futures::getName).toList(), EXCHANGE);
        //Add Indexes
        Indexes indexes =indexesRepo.findByToken("99926000");
        indexesList.add(indexes);
        Map<String, Indexes> indexByName = indexesList.stream()
                .collect(Collectors.toMap(Indexes::getName, i -> i,
                        (existing, duplicate) -> {
                            logger.warn("Duplicate index entry for {}. Keeping token {}, skipping {}",
                                    existing.getName(), existing.getToken(), duplicate.getToken());
                            return existing;
                        }));
        //Add Indexes
        indexByName.put("NIFTY 50", indexes);
        List<String> tokens = indexesList.stream()
                .map(Indexes::getToken).filter(Objects::nonNull).toList();

        Map<String, BigDecimal> todayPriceMap = fetchTodayPriceUsingPredictionService(tokens);
        Map<String, List<FuturesFilter>> bucket = new HashMap<>();

        for (Futures f : futuresList) {
            Indexes idx = indexByName.get(f.getName());
            if (idx == null) {
                if("NIFTY 50".equalsIgnoreCase(f.getName()))
                {
                    idx = indexByName.get(f.getName());
                }
                continue;
            }

            BigDecimal todayPrice = todayPriceMap.get(idx.getToken());
            if (todayPrice == null) continue;

            // ✅ Read instantly from the database (No API calls!)
            if (f.getExpiryClose() == null || f.getExpiryHigh() == null || f.getExpiryLow() == null) {
                logger.debug("Skipping {} - Morning setup hasn't cached OHLC data yet", f.getName());
                continue;
            }

            ExpiryOHLC ohlc = new ExpiryOHLC(BigDecimal.ZERO, f.getExpiryHigh(), f.getExpiryLow(), f.getExpiryClose());

            BigDecimal percentMove = todayPrice.subtract(ohlc.close)
                    .divide(ohlc.close, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            BigDecimal rangePercent = ohlc.calculateRangePercent();

            for (String indexType : resolveIndexTypes(f)) {
                FuturesConfig cfg = configMap.get(indexType);
                if (cfg == null) continue;

                FuturesFilter ff = buildFilter(f.getName(), indexType, todayPrice, ohlc,
                        percentMove, rangePercent, expiryDate, cfg);
                bucket.computeIfAbsent(indexType, k -> new ArrayList<>()).add(ff);
            }
        }

        bucket.forEach((indexType, rows) -> {
            FuturesConfig cfg = configMap.get(indexType);
            self.updateFilters(cfg, rows);
        });

        logger.info("Running breakout scan after filter update");
        runBreakoutScan(sharedSession, todayPriceMap, indexByName);
    }

    // ─────────────────────────────────────────────
    //  Filter helpers
    // ─────────────────────────────────────────────

    private FuturesFilter buildFilter(String name, String indexType, BigDecimal todayPrice,
                                      ExpiryOHLC ohlc, BigDecimal percentMove, BigDecimal rangePercent,
                                      LocalDate expiryDate, FuturesConfig config) {

        FuturesFilter ff = new FuturesFilter();
        ff.setName(name);
        ff.setIndexType(indexType);
        ff.setLastExpiryPrice(ohlc.close);
        ff.setLastExpiryHigh(ohlc.high);
        ff.setLastExpiryLow(ohlc.low);
        ff.setLastTradedPrice(todayPrice);
        ff.setPercentMove(percentMove);
        ff.setRangePercent(rangePercent);
        ff.setDirection(percentMove.signum() > 0 ? "UP"
                : percentMove.signum() < 0 ? "DOWN"
                : "NEUTRAL");

        if (percentMove.compareTo(config.getProfitPercent()) >= 0)
            ff.setStatus("PROFIT");
        else if (percentMove.compareTo(config.getLossPercent().negate()) <= 0)
            ff.setStatus("LOSS");
        else
            ff.setStatus("NEUTRAL");

        ff.setLastExpiryDate(expiryDate);
        ff.setLastTradedDate(LocalDateTime.now());
        return ff;
    }

    private List<String> resolveIndexTypes(Futures f) {
        List<String> list = new ArrayList<>();
        if (Boolean.TRUE.equals(f.getIsNifty50()))     list.add("NIFTY_50");
        if (Boolean.TRUE.equals(f.getIsNiftyNext50())) list.add("NIFTY_NEXT_50");
        if (Boolean.TRUE.equals(f.getIsNifty100()))    list.add("NIFTY_100");
        if (Boolean.TRUE.equals(f.getIsNifty200()))    list.add("NIFTY_200");
        if (Boolean.TRUE.equals(f.getIsNifty500()))    list.add("NIFTY_500");
        return list;
    }

    @Transactional
    public void updateFilters(FuturesConfig config, List<FuturesFilter> result) {
        String indexType = config.getIndexType();
        filterRepo.deleteByIndexType(indexType);
        List<FuturesFilter> saved = filterRepo.saveAll(result);
        logger.info("Saved {} filters for {} (with HIGH/LOW/RANGE data)", saved.size(), indexType);
    }

    // ─────────────────────────────────────────────
    //  Breakout scan — Re-structured for Multi-Threading
    // ─────────────────────────────────────────────
    public void runBreakoutScan(SmartConnect smartConnect,
                                Map<String, BigDecimal> ltpMap,
                                Map<String, Indexes> indexByName) {

        logger.info("========== BREAKOUT SCAN STARTED (MULTI-THREADED) ==========");
        // ✅ TRACK EXISTING POSITIONS FIRST
        self.trackExistingPositions(ltpMap, indexByName);
        List<FuturesFilter> filters = filterRepo.findAll();
        logger.info("Found {} filters to scan", filters.size());

        if (filters.isEmpty() || smartConnect == null) {
            logger.warn("Filters empty or SmartConnect is null — aborting scan.");
            return;
        }

        Map<String, FuturesConfig> configMap = configRepo.findByActive("Y").stream()
                .collect(Collectors.toMap(FuturesConfig::getIndexType, c -> c));

        List<Futures> futuresList = futuresRepo.findAll();
        Map<String, Futures> futuresMap = futuresList.stream()
                .collect(Collectors.toMap(Futures::getName, f -> f));

        // ✅ THREAD-SAFE DATA STRUCTURES
        List<FuturesBreakEvent> detectedEvents = Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger scannedCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger breakoutCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger breakdownCount = new java.util.concurrent.atomic.AtomicInteger(0);

        // ✅ Per-stock candle cache so a stock in multiple index buckets (NIFTY_50 + NIFTY_100 etc.)
        // only gets ONE broker candle fetch and ONE SMC evaluation/notify, not one per filter row.
        Map<String, List<HourlyCandle>> candleCacheByName = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.Set<String> smcEvaluatedNames = java.util.concurrent.ConcurrentHashMap.newKeySet();

        // Distinct stock names actually present in the filters, so we only touch each stock once.
        List<String> uniqueNames = filters.stream()
                .map(FuturesFilter::getName)
                .distinct()
                .toList();

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(5);
        LocalTime MARKET_CUTOFF = LocalTime.of(15, 30);

        try {
            // ──────────────────────────────────────────────────────────
            // PASS 1: one candle fetch + one SMC evaluation PER UNIQUE STOCK
            // ──────────────────────────────────────────────────────────
            List<java.util.concurrent.CompletableFuture<Void>> smcFutures = uniqueNames.stream().map(name ->
                    java.util.concurrent.CompletableFuture.runAsync(() -> {

                        if (LocalTime.now(ZoneId.of("Asia/Kolkata")).isAfter(MARKET_CUTOFF)) {
                            return;
                        }

                        scannedCount.incrementAndGet();

                        Indexes idx = indexByName.get(name);
                        BigDecimal ltp = ltpMap.get(idx != null ? idx.getToken() : null);
                        if (idx == null || ltp == null) {
                            logger.debug("Skipping {} - no index mapping or LTP", name);
                            return;
                        }

                        Futures futuresEntity = futuresMap.get(name);
                        String primaryIndexType = determinePrimaryIndexType(futuresEntity);

                        List<HourlyCandle> hourlyCandles = new ArrayList<>();
                        try {
                            JSONArray rawCandles = fetchOneHourCandle(smartConnect, idx);
                            if (rawCandles != null && !rawCandles.isEmpty()) {
                                hourlyCandles = parseToHourlyCandles(rawCandles);

                                if (!hourlyCandles.isEmpty()) {
                                    CandleMetrics metrics = analyzeCandleStructure(hourlyCandles);
                                    if (metrics != null && metrics.bodyStrengthPct.compareTo(new BigDecimal("35.00")) < 0) {
                                        logger.warn("⚠️ [{}] Latest candle has weak body ({}%) - potential false breakout",
                                                name, metrics.bodyStrengthPct);
                                    }

                                    boolean canNotify = configMap.values().stream()
                                            .filter(c -> c.getIndexType().equalsIgnoreCase(primaryIndexType))
                                            .map(c -> "Y".equalsIgnoreCase(c.getNotificationRequired()))
                                            .findFirst()
                                            .orElse(false);

                                    smcLiteService.evaluateAndNotify(name, primaryIndexType, hourlyCandles, ltp, canNotify)
                                            .ifPresent(smcSignal ->
                                                    logger.info("🧠 [SMC BOS CONFIRMED] {} Triggered at ₹{}", name, smcSignal.getCurrentPrice()));

                                    smcEvaluatedNames.add(name);
                                }
                            }
                        } catch (Exception e) {
                            logger.error("🛑 SMC evaluation processing failed for {}: {}", name, e.getMessage());
                        }

                        candleCacheByName.put(name, hourlyCandles);

                    }, executor)
            ).toList();

            java.util.concurrent.CompletableFuture.allOf(smcFutures.toArray(new java.util.concurrent.CompletableFuture[0])).join();

            // ──────────────────────────────────────────────────────────
            // PASS 2: expiry breakout check PER FILTER ROW, reusing cached candles
            // ──────────────────────────────────────────────────────────
            List<java.util.concurrent.CompletableFuture<Void>> breakoutFutures = filters.stream().map(f ->
                    java.util.concurrent.CompletableFuture.runAsync(() -> {

                        if (LocalTime.now(ZoneId.of("Asia/Kolkata")).isAfter(MARKET_CUTOFF)) {
                            return;
                        }

                        Indexes idx = indexByName.get(f.getName());
                        BigDecimal ltp = ltpMap.get(idx != null ? idx.getToken() : null);
                        if (idx == null || ltp == null) return;

                        List<HourlyCandle> hourlyCandles = candleCacheByName.getOrDefault(f.getName(), Collections.emptyList());

                        if (f.getLastExpiryHigh() == null || f.getLastExpiryLow() == null || hourlyCandles.isEmpty()) {
                            return;
                        }

                        HourlyCandle lastCandle = hourlyCandles.get(hourlyCandles.size() - 1);
                        BigDecimal hourClose = lastCandle.close;
                        String indexType = f.getIndexType();
                        LocalDateTime timestamp = LocalDateTime.now().withNano(0);
                        boolean criteriaMet = false;

                        if ("UP".equals(f.getDirection()) &&
                                hourClose.compareTo(f.getLastExpiryHigh()) > 0 &&
                                ltp.compareTo(f.getLastExpiryHigh()) > 0) {

                            logger.info("🚀 BREAKOUT: {} → 1H Close={} & LTP={} > ExpiryHigh={}", f.getName(), hourClose, ltp, f.getLastExpiryHigh());
                            FuturesBreakEvent event = createBreakEventNoSave(f, hourClose, "BREAKOUT", timestamp, indexType);
                            if (event != null) {
                                event.setCurrentPrice(ltp);
                                detectedEvents.add(event);
                                breakoutCount.incrementAndGet();
                                criteriaMet = true;
                            }
                        }

                        if ("DOWN".equals(f.getDirection()) &&
                                hourClose.compareTo(f.getLastExpiryLow()) < 0 &&
                                ltp.compareTo(f.getLastExpiryLow()) < 0) {

                            logger.info("📉 BREAKDOWN: {} → 1H Close={} & LTP={} < ExpiryLow={}", f.getName(), hourClose, ltp, f.getLastExpiryLow());
                            FuturesBreakEvent event = createBreakEventNoSave(f, hourClose, "BREAKDOWN", timestamp, indexType);
                            if (event != null) {
                                event.setCurrentPrice(ltp);
                                detectedEvents.add(event);
                                breakdownCount.incrementAndGet();
                                criteriaMet = true;
                            }
                        }

                        if (!criteriaMet) {
                            logger.debug("ℹ️ [EXPIRY SCAN DONE] {} ({}) | 1H Close={} | LTP={} | No level breached (High={}, Low={})",
                                    f.getName(), indexType, hourClose, ltp, f.getLastExpiryHigh(), f.getLastExpiryLow());
                        }

                    }, executor)
            ).toList();

            java.util.concurrent.CompletableFuture.allOf(breakoutFutures.toArray(new java.util.concurrent.CompletableFuture[0])).join();

        } finally {
            executor.shutdown();
        }

        logger.info("📊 Scan Summary: Scanned={}, Breakouts={}, Breakdowns={}",
                scannedCount.get(), breakoutCount.get(), breakdownCount.get());

        if (!detectedEvents.isEmpty()) {
            logger.info("Processing {} detected events for save/dedup", detectedEvents.size());
            List<FuturesBreakEvent> newSignals = self.saveAllBreakEventsWithDedup(detectedEvents);
            if (!newSignals.isEmpty()) {
                logger.info("✅ Sending notifications for {} NEW signals", newSignals.size());
                sendBreakoutNotificationsWithConfig(newSignals);
            } else {
                logger.info("ℹ️ Expiry breakout scanning completed. No new signals to notify.");
            }
        } else {
            logger.info("✅ Expiry breakout scanning completed across {} instruments. No criteria met.", scannedCount.get());
        }

        logger.info("========== BREAKOUT SCAN COMPLETED ==========");
    }

    // ─────────────────────────────────────────────
    //  Save & dedup
    // ─────────────────────────────────────────────

    @Transactional
    public List<FuturesBreakEvent> saveAllBreakEventsWithDedup(List<FuturesBreakEvent> events) {
        logger.info("======== SAVE & DEDUP STARTED ({} events) ========", events.size());

        Map<String, FuturesBreakEvent> uniqueMap = new LinkedHashMap<>();
        for (FuturesBreakEvent event : events) {
            String key = event.getName() + "_" + event.getBreakType();
            uniqueMap.putIfAbsent(key, event);
        }
        logger.info("After in-memory dedup: {} unique events", uniqueMap.size());

        List<FuturesBreakEvent> newSignals = new ArrayList<>();
        int updatedCount = 0;

        for (FuturesBreakEvent newEvent : uniqueMap.values()) {
            Optional<FuturesBreakEvent> existingOpt = futuresBreakEventRepo
                    .findByNameAndBreakType(newEvent.getName(), newEvent.getBreakType());

            if (existingOpt.isPresent()) {
                FuturesBreakEvent record = existingOpt.get();

                boolean isSameMonth = record.getBreakDate().getMonth() == newEvent.getBreakDate().getMonth()
                        && record.getBreakDate().getYear() == newEvent.getBreakDate().getYear();

                record.setCurrentPrice(newEvent.getCurrentPrice());
                record.setPercentMove(newEvent.getPercentMove());
                record.setBreakDate(newEvent.getBreakDate());
                record.setBreakTime(newEvent.getBreakTime());
                record.setReferenceLevel(newEvent.getReferenceLevel());
                record.setStopLoss(newEvent.getStopLoss());

                if (isSameMonth) {
                    if ("INACTIVE".equals(record.getStatus())) {
                        logger.debug("🛑 {} already hit SL this month. Keeping INACTIVE.", record.getName());
                    }
                    else if (checkStopLoss(record)) {
                        record.setStatus("INACTIVE");
                        record.setExitReason("SL_HIT");
                        record.setExitPrice(record.getCurrentPrice());
                        record.setExitDate(LocalDateTime.now());
                        logger.warn("❌ SL Hit: {} {} | Entry={} | Exit={} | %Move={}%",
                                record.getName(), record.getBreakType(),
                                record.getBreakPrice(), record.getExitPrice(), record.getPercentMove());
                    }
                    else {
                        record.setStatus("ACTIVE");
                        logger.info("🔄 Same-Month Update: {} {} | Price={} | PnL={}% — Silent (No Alert)",
                                record.getName(), record.getBreakType(),
                                record.getCurrentPrice(), record.getPercentMove());
                    }

                    futuresBreakEventRepo.save(record);
                    updatedCount++;

                } else {
                    record.setStatus("ACTIVE");
                    record.setExitReason(null);
                    record.setExitPrice(null);
                    record.setExitDate(null);
                    record.setBreakPrice(newEvent.getBreakPrice());

                    FuturesBreakEvent saved = futuresBreakEventRepo.save(record);
                    newSignals.add(saved);
                    logger.info("🔔 Previous-Month Rollover Alert (ID={}): {} {} at {} — Will Notify!",
                            saved.getId(), saved.getName(),
                            saved.getBreakType(), saved.getBreakPrice());
                }

            } else {
                newEvent.setStatus("ACTIVE");
                newEvent.setCurrentPrice(newEvent.getBreakPrice());
                FuturesBreakEvent saved = futuresBreakEventRepo.save(newEvent);
                newSignals.add(saved);
                logger.info("🆕 NEW signal (ID={}): {} {} at {} — Will Notify!",
                        saved.getId(), saved.getName(),
                        saved.getBreakType(), saved.getBreakPrice());
            }
        }

        logger.info("======== SAVE & DEDUP DONE: Alerts To Send={}, Silently Updated={} ========",
                newSignals.size(), updatedCount);

        return newSignals;
    }

    // ─────────────────────────────────────────────
    //  Break event helpers
    // ─────────────────────────────────────────────
    private boolean checkStopLoss(FuturesBreakEvent event) {
        if (event.getCurrentPrice() == null || event.getStopLoss() == null) return false;
        if ("BREAKOUT".equals(event.getBreakType()))
            return event.getCurrentPrice().compareTo(event.getStopLoss()) <= 0;
        if ("BREAKDOWN".equals(event.getBreakType()))
            return event.getCurrentPrice().compareTo(event.getStopLoss()) >= 0;
        return false;
    }

    private String determinePrimaryIndexType(Futures futures) {
        if (futures == null) return "NIFTY_500";
        if (Boolean.TRUE.equals(futures.getIsNifty50()))     return "NIFTY_50";
        if (Boolean.TRUE.equals(futures.getIsNiftyNext50())) return "NIFTY_NEXT_50";
        if (Boolean.TRUE.equals(futures.getIsNifty100()))    return "NIFTY_100";
        if (Boolean.TRUE.equals(futures.getIsNifty200()))    return "NIFTY_200";
        if (Boolean.TRUE.equals(futures.getIsNifty500()))    return "NIFTY_500";
        return "NIFTY_500";
    }

    private FuturesBreakEvent createBreakEventNoSave(FuturesFilter f, BigDecimal hourClose,
                                                     String breakType, LocalDateTime hourEnd, String primaryIndexType) {

        FuturesBreakEvent event = new FuturesBreakEvent();
        event.setName(f.getName());
        event.setIndexType(primaryIndexType);
        event.setBreakType(breakType);

        if ("BREAKOUT".equals(breakType)) {
            event.setReferenceLevel(f.getLastExpiryHigh());
            event.setStopLoss(f.getLastExpiryLow());
        } else {
            event.setReferenceLevel(f.getLastExpiryLow());
            event.setStopLoss(f.getLastExpiryHigh());
        }

        event.setBreakPrice(hourClose);
        event.setCurrentPrice(hourClose);
        event.setBreakDate(hourEnd.toLocalDate());
        event.setBreakTime(hourEnd);
        event.setRangePercent(f.getRangePercent());
        event.setPercentMove(f.getPercentMove());
        event.setStatus("ACTIVE");
        event.setLastExpiryDate(f.getLastExpiryDate());
        logger.info("✅ {} for {} (indexType={}) | Entry={} | SL={} | %Move={}%",
                breakType, f.getName(), primaryIndexType,
                hourClose, event.getStopLoss(), event.getPercentMove());
        return event;
    }

    // ─────────────────────────────────────────────
    //  API calls — shared session, with delays & 503 handling
    // ─────────────────────────────────────────────

    private JSONArray fetchOneHourCandle(SmartConnect smartConnect, Indexes idx) {
        int maxRetries = 5;
        long delay = 3000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 1) {
                    logger.info("Retrying 1H candle for {} after {}ms (attempt {}/{})...",
                            idx.getName(), delay, attempt, maxRetries);
                    sleepQuietly(delay);
                    delay *= 2;
                }

                LocalDateTime[] window = resolveNseOneHourWindow();
                JSONObject req = new JSONObject();
                req.put("exchange", idx.getExchange());
                req.put("symboltoken", idx.getToken());
                req.put("interval", "ONE_HOUR");
                req.put("fromdate", window[0].toString().replace("T", " "));
                req.put("todate", window[1].toString().replace("T", " "));

                logger.debug("Fetching 1H candle for {} from {} to {}", idx.getName(), window[0], window[1]);
                JSONArray candles = smartConnect.candleData(req);

                if (candles != null && !candles.isEmpty()) {
                    logger.debug("✅ 1H candle fetched for {} on attempt {}", idx.getName(), attempt);
                    return candles;
                }

                String reason = (candles == null) ? "NULL" : "empty";
                logger.warn("{} 1H candle response for {} (attempt {}/{}) — will retry",
                        reason, idx.getName(), attempt, maxRetries);

            } catch (Exception e) {
                boolean is503 = e.getMessage() != null &&
                        (e.getMessage().contains("503") || e.getMessage().contains("timedout") || e.getMessage().contains("Too Many Requests"));

                if (is503) {
                    logger.warn("⚠️ 503/Rate Limit hit for {} (attempt {}/{}). Forcing hard backoff of {}ms.",
                            idx.getName(), attempt, maxRetries, RATE_LIMIT_SLEEP_MS);
                    // ✅ Apply the hard 6-second rate limit penalty immediately
                    sleepQuietly(RATE_LIMIT_SLEEP_MS);
                } else {
                    logger.warn("Error fetching 1H candle for {} (attempt {}/{}): {} — will retry",
                            idx.getName(), attempt, maxRetries, e.getMessage());
                }
            }
        }

        logger.error("❌ Failed to fetch 1H candle for {} after {} retries (null/empty/error)",
                idx.getName(), maxRetries);
        return null;
    }

    public ExpiryOHLC fetchExpiryOHLC(SmartConnect smartConnect, Indexes idx, LocalDate expiryDate) {
        int maxRetries = 5;
        long delay = 3000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 1) {
                    logger.info("Retrying {} after {}ms (attempt {}/{})...",
                            idx.getName(), delay, attempt, maxRetries);
                    sleepQuietly(delay);
                    delay *= 2;
                }

                LocalDate tradingDate = NSEWorkingDays.isNSEWorkingDay(expiryDate) ?
                        expiryDate : NSEWorkingDays.getLastWorkingDay(expiryDate);
                LocalDate fromDate = tradingDate.minusDays(1);

                JSONObject req = new JSONObject();
                req.put("exchange", idx.getExchange());
                req.put("symboltoken", idx.getToken());
                req.put("interval", "ONE_DAY");
                req.put("fromdate", fromDate + " 09:15");
                req.put("todate", tradingDate + " 15:15");

                JSONArray candles = smartConnect.candleData(req);

                if (candles != null && !candles.isEmpty()) {
                    JSONArray c = candles.getJSONArray(candles.length() - 1);
                    BigDecimal open  = c.getBigDecimal(1);
                    BigDecimal high  = c.getBigDecimal(2);
                    BigDecimal low   = c.getBigDecimal(3);
                    BigDecimal close = c.getBigDecimal(4);
                    logger.debug("OHLC {}: O={} H={} L={} C={}", idx.getName(), open, high, low, close);
                    return new ExpiryOHLC(open, high, low, close);
                }

                String reason = (candles == null) ? "NULL" : "empty";
                logger.warn("{} candle response for {} (attempt {}/{}) — will retry",
                        reason, idx.getName(), attempt, maxRetries);

            } catch (Exception e) {
                boolean is503 = e.getMessage() != null &&
                        (e.getMessage().contains("503") || e.getMessage().contains("timedout"));

                if (is503) {
                    logger.warn("⚠️ 503/timeout for {} (attempt {}/{}) — will retry",
                            idx.getName(), attempt, maxRetries);
                } else {
                    logger.warn("Error for {} (attempt {}/{}): {} — will retry",
                            idx.getName(), attempt, maxRetries, e.getMessage());
                }
            }
        }

        logger.error("❌ Failed to fetch OHLC for {} after {} retries (null/empty/error)",
                idx.getName(), maxRetries);
        return null;
    }

    // ─────────────────────────────────────────────
    //  LTP batch fetch
    // ─────────────────────────────────────────────

    private Map<String, BigDecimal> fetchTodayPriceUsingPredictionService(List<String> tokens) throws SmartAPIException {
        if (tokens.isEmpty()) return Map.of();

        final int BATCH_SIZE = 50;
        Map<String, BigDecimal> priceMap = new HashMap<>();
        logger.info("Fetching LTPs for {} tokens in batches of {}", tokens.size(), BATCH_SIZE);

        SmartConnect smartconnect = angelOne.signIn();
        if (smartconnect == null) {
            logger.error("Failed to sign in for LTP fetch. Aborting market data query.");
            return priceMap;
        }

        for (int i = 0; i < tokens.size(); i += BATCH_SIZE) {
            int endIndex = Math.min(i + BATCH_SIZE, tokens.size());
            List<String> batch = tokens.subList(i, endIndex);

            try {
                JSONObject payload = predictionService.buildMarketDataPayload(batch, EXCHANGE);
                JSONObject response = predictionService.callMarketDataWithRetry(smartconnect, payload);

                if (response != null) {
                    JSONArray fetched = extractFetchedArray(response);
                    if (fetched != null) {
                        for (int j = 0; j < fetched.length(); j++) {
                            JSONObject obj = fetched.getJSONObject(j);
                            String token = obj.get("symbolToken").toString();
                            if (obj.has("ltp") && !obj.isNull("ltp")) {
                                priceMap.put(token, new BigDecimal(obj.get("ltp").toString()));
                            }
                        }
                    }
                }

                if (endIndex < tokens.size()) {
                    sleepQuietly(BATCH_FETCH_DELAY_MS);
                }

            } catch (Exception e) {
                logger.error("Error fetching LTPs for batch at index {}: {}", i, e.getMessage());
            }
        }

        logger.info("Fetched LTPs: {}/{} tokens", priceMap.size(), tokens.size());
        return priceMap;
    }

    private JSONArray extractFetchedArray(JSONObject response) {
        try {
            if (response.has("data") && !response.isNull("data")) {
                JSONObject data = response.getJSONObject("data");
                if (data.has("fetched")) return data.getJSONArray("fetched");
            }
            if (response.has("fetched")) return response.getJSONArray("fetched");
        } catch (JSONException e) {
            logger.warn("Could not parse fetched array: {}", e.getMessage());
        }
        return null;
    }

    // ─────────────────────────────────────────────
    //  Notifications
    // ─────────────────────────────────────────────

    private void sendBreakoutNotificationsWithConfig(List<FuturesBreakEvent> events) {
        if (events.isEmpty()) return;

        Map<String, FuturesConfig> configMap = configRepo.findByActive("Y").stream()
                .collect(Collectors.toMap(FuturesConfig::getIndexType, c -> c));

        Map<String, List<FuturesBreakEvent>> eventsByIndex = events.stream()
                .collect(Collectors.groupingBy(FuturesBreakEvent::getIndexType));

        eventsByIndex.forEach((indexType, indexEvents) -> {
            FuturesConfig config = configMap.get(indexType);
            if (config == null || !"Y".equalsIgnoreCase(config.getNotificationRequired())) {
                logger.info("Notifications disabled for {}. Skipping {} events.", indexType, indexEvents.size());
                return;
            }

            List<FuturesBreakEvent> breakouts = indexEvents.stream()
                    .filter(e -> "BREAKOUT".equals(e.getBreakType())).toList();
            List<FuturesBreakEvent> breakdowns = indexEvents.stream()
                    .filter(e -> "BREAKDOWN".equals(e.getBreakType())).toList();

            if (!breakouts.isEmpty()) {
                sendTelegramBatch("🚀 *BREAKOUT ALERTS - " + indexType + "*", breakouts);
                logger.info("Sent {} breakout alerts for {}", breakouts.size(), indexType);
            }
            if (!breakdowns.isEmpty()) {
                sendTelegramBatch("📉 *BREAKDOWN ALERTS - " + indexType + "*", breakdowns);
                logger.info("Sent {} breakdown alerts for {}", breakdowns.size(), indexType);
            }
        });
    }

    private void sendTelegramBatch(String headerText, List<FuturesBreakEvent> events) {
        String header = headerText + "\n\n```\n" +
                String.format("%-8s | %-12s | %8s | %8s%n", "SIGNAL", "STOCK", "ENTRY", "SL") +
                "---------------------------------------------------\n";
        String footer = "```\n";
        StringBuilder batch = new StringBuilder(header);
        final int TELEGRAM_LIMIT = 3800;

        for (FuturesBreakEvent e : events) {
            String signal = "BREAKOUT".equals(e.getBreakType()) ? "BUY" : "SELL";
            String row = String.format("%-8s | %-12s | %8.2f | %8.2f%n",
                    signal, e.getName(), e.getBreakPrice(), e.getStopLoss());

            if (batch.length() + row.length() + footer.length() > TELEGRAM_LIMIT) {
                batch.append(footer);
                try { telegramService.sendBroadcast(batch.toString()); }
                catch (Exception ex) { logger.error("Telegram send failed: {}", ex.getMessage()); }
                batch = new StringBuilder(header);
            }
            batch.append(row);
        }

        if (batch.length() > header.length()) {
            batch.append(footer);
            try { telegramService.sendBroadcast(batch.toString()); }
            catch (Exception ex) { logger.error("Telegram send failed: {}", ex.getMessage()); }
        }
    }

    // ─────────────────────────────────────────────
    //  Date / window helpers
    // ─────────────────────────────────────────────

    private LocalDate resolveExecutionDate(FuturesConfig config) {
        if ("Y".equalsIgnoreCase(config.getUseNiftyExpiry())) {
            LocalDate today = LocalDate.now();
            LocalDate thisMonthExpiry = today.with(TemporalAdjusters.lastInMonth(DayOfWeek.TUESDAY));

            // On or before this month's expiry, "last expiry" is still last month's —
            // this month's expiry day hasn't closed yet when the 8:45 AM morning job runs.
            if (!today.isAfter(thisMonthExpiry)) {
                return today.minusMonths(1).with(TemporalAdjusters.lastInMonth(DayOfWeek.TUESDAY));
            }
            return thisMonthExpiry;
        }
        if (config.getExecutionDate() == null) {
            throw new IllegalStateException("execution_date must be set when use_nifty_expiry = N");
        }
        return config.getExecutionDate();
    }

    private LocalDateTime[] resolveNseOneHourWindow() {
        ZoneId IST = ZoneId.of("Asia/Kolkata");
        LocalDate today = LocalDate.now(IST);

        // ✅ Fetch 15 days back to feed 100+ candles to the SMC Engine
        LocalDate currentTradingDay = NSEWorkingDays.isNSEWorkingDay(today) ?
                today : NSEWorkingDays.getLastWorkingDay(today);
        LocalDate prevDay = currentTradingDay.minusDays(15);
        LocalDate previousTradingDay = NSEWorkingDays.isNSEWorkingDay(prevDay) ?
                prevDay : NSEWorkingDays.getLastWorkingDay(prevDay);

        LocalDateTime from = LocalDateTime.of(previousTradingDay, LocalTime.of(9, 15));
        LocalDateTime to   = LocalDateTime.of(currentTradingDay,  LocalTime.of(15, 15));

        logger.info("1H Window: from={}, to={}", from, to);
        return new LocalDateTime[]{from, to};
    }

    // ─────────────────────────────────────────────
    //  Config CRUD
    // ─────────────────────────────────────────────

    @Transactional
    public FuturesConfig partialUpdate(String indexType, FuturesConfigDto dto) {
        FuturesConfig config = configRepo.findByIndexType(indexType)
                .orElseThrow(() -> new IllegalStateException("Config not found for " + indexType));

        if (dto.getExpiryDate()       != null) config.setExecutionDate(dto.getExpiryDate());
        if (dto.getMovementPercent()  != null) config.setMovementPercent(dto.getMovementPercent());
        if (dto.getProfitPercent()    != null) config.setProfitPercent(dto.getProfitPercent());
        if (dto.getLossPercent()      != null) config.setLossPercent(dto.getLossPercent());
        if (dto.getUseNiftyExpiry()   != null) config.setUseNiftyExpiry(dto.getUseNiftyExpiry());
        if (dto.getActive()           != null) config.setActive(dto.getActive());

        return configRepo.save(config);
    }

    public FuturesConfig fetch(String indexType) {
        return configRepo.findByIndexType(indexType)
                .orElseThrow(() -> new IllegalStateException("Config not found for " + indexType));
    }

    public List<FuturesConfig> fetchAllActive() { return configRepo.findByActive("Y"); }
    public List<FuturesConfig> fetchAll()        { return configRepo.findAll(); }

    // ─────────────────────────────────────────────
    //  Break event queries
    // ─────────────────────────────────────────────

    public List<FuturesBreakEvent> getAllBreakEvents()                       { return futuresBreakEventRepo.findAll(); }
    public List<FuturesBreakEvent> getBreakEventsByDate(LocalDate date)      { return futuresBreakEventRepo.findByBreakDate(date); }

    // ─────────────────────────────────────────────
    //  Utility
    // ─────────────────────────────────────────────

    private void sleepQuietly(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    // ─────────────────────────────────────────────
    //  NEW: Candlestick & SMC Pattern Engine
    // ─────────────────────────────────────────────

    private List<HourlyCandle> parseToHourlyCandles(JSONArray rawCandles) {
        List<HourlyCandle> candles = new ArrayList<>();
        if (rawCandles == null) return candles;

        try {
            for (int i = 0; i < rawCandles.length(); i++) {
                JSONArray c = rawCandles.getJSONArray(i);
                candles.add(new HourlyCandle(
                        c.getString(0),
                        c.getBigDecimal(1),
                        c.getBigDecimal(2),
                        c.getBigDecimal(3),
                        c.getBigDecimal(4),
                        c.getBigDecimal(5).longValue() // Bulletproof against decimal volumes
                ));
            }
        } catch (Exception e) {
            logger.warn("Failed parsing JSON candle array: {}", e.getMessage());
        }
        return candles;
    }

    private CandleMetrics analyzeCandleStructure(List<HourlyCandle> candles) {
        if (candles == null || candles.isEmpty()) return null;

        HourlyCandle latest = candles.get(candles.size() - 1);
        boolean isBullish = latest.close.compareTo(latest.open) >= 0;
        BigDecimal bodyStrength = latest.getBodyStrengthPercent();

        // 1. Calculate 5-Bar Volume Surge
        long volSum = 0;
        int volCount = 0;
        for (int i = Math.max(0, candles.size() - 6); i < candles.size() - 1; i++) {
            volSum += candles.get(i).volume;
            volCount++;
        }
        long avgVol = (volCount > 0) ? (volSum / volCount) : 0;
        boolean isVolumeSurge = avgVol > 0 && latest.volume > (avgVol * 1.25); // 25% above average

        // 2. Calculate Rolling SMC Swing High / Low (Excludes latest bar to reflect prior structure)
        BigDecimal swingHigh = latest.high;
        BigDecimal swingLow = latest.low;
        int lookback = Math.min(10, candles.size());
        for (int i = Math.max(0, candles.size() - lookback); i < candles.size() - 1; i++) {
            if (candles.get(i).high.compareTo(swingHigh) > 0) swingHigh = candles.get(i).high;
            if (candles.get(i).low.compareTo(swingLow) < 0) swingLow = candles.get(i).low;
        }

        // 3. Approximate ATR
        BigDecimal trSum = BigDecimal.ZERO;
        for (int i = 1; i < candles.size(); i++) {
            HourlyCandle curr = candles.get(i);
            HourlyCandle prev = candles.get(i - 1);
            BigDecimal hl = curr.getRange();
            BigDecimal hc = curr.high.subtract(prev.close).abs();
            BigDecimal lc = curr.low.subtract(prev.close).abs();
            trSum = trSum.add(hl.max(hc).max(lc));
        }
        BigDecimal atr = (candles.size() > 1) ? trSum.divide(BigDecimal.valueOf(candles.size() - 1), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        return new CandleMetrics(atr, swingHigh, swingLow, bodyStrength, isVolumeSurge, isBullish);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 🌅 MORNING INITIALIZATION: Pre-cache Expiry Structure in Master Database Table
    // ──────────────────────────────────────────────────────────────────────────

    public void initializeDailyExpiryStructure() throws SmartAPIException {
        FuturesConfig masterConfig = configRepo.findByIndexType("NIFTY_50")
                .filter(c -> "Y".equalsIgnoreCase(c.getActive())).orElse(null);

        if (masterConfig == null) {
            logger.warn("⚠️ NIFTY_50 config not active. Skipping morning setup initialization.");
            return;
        }

        LocalDate targetExpiryDate = resolveExecutionDate(masterConfig);
        List<Futures> futuresList = futuresRepo.findAll();

        logger.info("🌅 Starting Daily Expiry Pre-cache. Target Date: {}. Processing {} records...", targetExpiryDate, futuresList.size());

        SmartConnect sharedSession = angelOne.signIn();
        if (sharedSession == null) {
            logger.error("🛑 Failed to establish session for morning setup initialization.");
            return;
        }

        List<Indexes> indexesList = indexesRepo.findByNameInAndExchange(
                futuresList.stream().map(Futures::getName).toList(), EXCHANGE);
        //Add Indexes
        Indexes indexes =indexesRepo.findByToken("99926000");
        indexesList.add(indexes);
        Map<String, Indexes> indexByName = indexesList.stream()
                .collect(Collectors.toMap(Indexes::getName, i -> i, (exist, dup) -> exist));

        //Add Indexes
        indexByName.put("NIFTY 50", indexes);
        int updatedCount = 0;

        for (Futures f : futuresList) {

            Indexes idx = indexByName.get(f.getName());
            if (idx == null)
            {
                //Add Index
                if("NIFTY 50".equalsIgnoreCase(f.getName()))
                {
                    idx = indexByName.get(f.getName());
                }
                continue;
            }

            if (f.getLastExpiryDate() != null && f.getLastExpiryDate().equals(targetExpiryDate) && f.getExpiryClose() != null) {
                logger.debug("⏩ Structure for {} is already up to date for target expiry {}", f.getName(), targetExpiryDate);
                continue;
            }

            ExpiryOHLC ohlc = fetchExpiryOHLC(sharedSession, idx, targetExpiryDate);
            if (ohlc == null || ohlc.close == null || ohlc.close.signum() == 0) {
                sleepQuietly(OHLC_FETCH_DELAY_MS);
                continue;
            }

            f.setExpiryHigh(ohlc.high);
            f.setExpiryLow(ohlc.low);
            f.setExpiryClose(ohlc.close);
            f.setLastExpiryDate(targetExpiryDate);
            f.setLastUpdated(LocalDateTime.now());

            futuresRepo.save(f);
            updatedCount++;

            sleepQuietly(OHLC_FETCH_DELAY_MS);
        }

        logger.info("✅ Morning Expiry Pre-cache Finished. Successfully updated {} instruments.", updatedCount);
    }


    /**
     * Re-evaluates BOS against already-cached SMC zones using fresh WebSocket LTPs —
     * no candle fetch, no broker API call. Keeps zones honest between full hourly scans.
     */
    public void recheckAllBosOnly() {
        List<Futures> futuresList = futuresRepo.findAll();
        logger.info("🔁 BOS-only recheck starting for {} instruments", futuresList.size());

        List<Indexes> indexesList = indexesRepo.findByNameInAndExchange(
                futuresList.stream().map(Futures::getName).toList(), EXCHANGE);
        Map<String, Indexes> indexByName = indexesList.stream()
                .collect(Collectors.toMap(Indexes::getName, i -> i, (exist, dup) -> exist));

        int checked = 0;
        for (Futures f : futuresList) {
            Indexes idx = indexByName.get(f.getName());
            if (idx == null) continue;

            BigDecimal ltp = angelWebSocketService.getLatestLTP(ExchangeType.NSE_CM, idx.getToken());
            if (ltp == null || ltp.compareTo(BigDecimal.ZERO) <= 0) continue;

            String primaryIndexType = determinePrimaryIndexType(f);
            smcLiteService.recheckBosOnly(f.getName(), primaryIndexType, ltp, true);
            checked++;
        }

        logger.info("🔁 BOS-only recheck done — checked {} instruments", checked);
    }
    @Transactional
    public void trackExistingPositions(Map<String, BigDecimal> ltpMap, Map<String, Indexes> indexByName) {
        List<FuturesBreakEvent> activeEvents = futuresBreakEventRepo.findByStatus("ACTIVE");
        List<FuturesBreakEvent> slHits = new ArrayList<>();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        for (FuturesBreakEvent event : activeEvents) {
            Indexes idx = indexByName.get(event.getName());
            if (idx == null) continue;

            BigDecimal ltp = ltpMap.get(idx.getToken());
            if (ltp == null) continue;

            event.setCurrentPrice(ltp);

            // 1. Update Holding Days
            if (event.getBreakDate() != null) {
                long days = java.time.temporal.ChronoUnit.DAYS.between(event.getBreakDate(), today);
                event.setHoldingDays((int) days);
            }

            // 2. Track Highest and Lowest Prices
            if (event.getHighestPrice() == null || ltp.compareTo(event.getHighestPrice()) > 0) {
                event.setHighestPrice(ltp);
            }
            if (event.getLowestPrice() == null || ltp.compareTo(event.getLowestPrice()) < 0) {
                event.setLowestPrice(ltp);
            }

            // 3. Calculate PnL and Check SL
            BigDecimal pnl = ltp.subtract(event.getBreakPrice())
                    .divide(event.getBreakPrice(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            if ("BREAKOUT".equals(event.getBreakType())) {
                if (event.getStopLoss() != null && ltp.compareTo(event.getStopLoss()) <= 0) {
                    event.setStatus("INACTIVE");
                    event.setExitReason("SL_HIT");
                    slHits.add(event);
                }
            } else if ("BREAKDOWN".equals(event.getBreakType())) {
                pnl = pnl.negate(); // Reverse PnL for short trades
                if (event.getStopLoss() != null && ltp.compareTo(event.getStopLoss()) >= 0) {
                    event.setStatus("INACTIVE");
                    event.setExitReason("SL_HIT");
                    slHits.add(event);
                }
            }

            event.setPercentMove(pnl);
            futuresBreakEventRepo.save(event);
        }

        if (!slHits.isEmpty()) {
            sendTelegramBatch("❌ *SL HIT ALERT (EXISTING POSITIONS)*", slHits);
        }
    }

    public void sendEODReport() {
        List<FuturesBreakEvent> activeEvents = futuresBreakEventRepo.findByStatus("ACTIVE");
        if (activeEvents.isEmpty()) return;

        StringBuilder msg = new StringBuilder("📊 *EOD ACTIVE POSITIONS REPORT*\n\n```\n");
        msg.append(String.format("%-10s | %-3s | %-6s | %-4s | %s%n", "STOCK", "DIR", "PNL%", "DAYS", "PEAK"));
        msg.append("------------------------------------------\n");

        for (FuturesBreakEvent e : activeEvents) {
            String dir = "BREAKOUT".equals(e.getBreakType()) ? "UP" : "DN";
            BigDecimal pnl = e.getPercentMove() != null ? e.getPercentMove() : BigDecimal.ZERO;
            Integer days = e.getHoldingDays() != null ? e.getHoldingDays() : 0;

            // Show Highest Price for BUYs, Lowest Price for SELLs
            BigDecimal peak = "UP".equals(dir)
                    ? (e.getHighestPrice() != null ? e.getHighestPrice() : e.getCurrentPrice())
                    : (e.getLowestPrice() != null ? e.getLowestPrice() : e.getCurrentPrice());

            msg.append(String.format("%-10s | %-3s | %+5.2f%% | %-4d | %7.2f%n", e.getName(), dir, pnl, days, peak));
        }
        msg.append("```");
        try { telegramService.sendBroadcast(msg.toString()); }
        catch (Exception ex) { logger.error("EOD report failed", ex); }
    }
}