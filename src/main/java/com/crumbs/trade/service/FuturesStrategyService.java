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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final Logger logger = LogManager.getLogger(FuturesStrategyService.class);

    private static final String EXCHANGE = "NSE";

    // ✅ Tunable delay constants (ms)
    private static final int OHLC_FETCH_DELAY_MS   = 350;  // between each stock's OHLC call
    private static final int BATCH_FETCH_DELAY_MS  = 200;  // between LTP batches
    private static final int RETRY_BASE_DELAY_MS   = 3000; // base retry delay
    private static final int RATE_LIMIT_SLEEP_MS   = 6000; // extra sleep on 503

    @Autowired private FuturesRepo futuresRepo;
    @Autowired private Nifty500Repo nifty500Repo;
    @Autowired private FuturesConfigRepo configRepo;
    @Autowired private FuturesFilterRepo filterRepo;
    @Autowired private IndexesRepo indexesRepo;
    @Autowired private PredictionService predictionService;
    @Autowired private AngelOne angelOne;
    @Autowired private TelegramService telegramService;
    @Autowired private FuturesBreakEventRepo futuresBreakEventRepo;

    // ─────────────────────────────────────────────
    //  Inner classes
    // ─────────────────────────────────────────────

    private static class ExpiryOHLC {
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

    @Transactional
    public void executeAll() throws SmartAPIException {
        FuturesConfig masterConfig = configRepo.findByIndexType("NIFTY_500")
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

    @Transactional
    private void executeMaster(FuturesConfig masterConfig) throws SmartAPIException {
        LocalDate expiryDate = resolveExecutionDate(masterConfig);
        List<Futures> futuresList = futuresRepo.findByIsNifty500True();

        if (futuresList.isEmpty()) {
            logger.warn("No NIFTY_500 stocks found");
            return;
        }

        // ✅ Sign in ONCE and reuse for ALL OHLC + breakout calls
        SmartConnect sharedSession = angelOne.signIn();
        if (sharedSession == null) {
            logger.error("Failed to sign in. Aborting execution.");
            return;
        }

        Map<String, FuturesConfig> configMap = configRepo.findByActive("Y").stream()
                .collect(Collectors.toMap(FuturesConfig::getIndexType, c -> c));

        List<Indexes> indexesList = indexesRepo.findByNameInAndExchange(
                futuresList.stream().map(Futures::getName).toList(), EXCHANGE);

        Map<String, Indexes> indexByName = indexesList.stream()
                .collect(Collectors.toMap(Indexes::getName, i -> i,
                        (existing, duplicate) -> {
                            logger.warn("Duplicate index entry for {}. Keeping token {}, skipping {}",
                                    existing.getName(), existing.getToken(), duplicate.getToken());
                            return existing;
                        }));

        List<String> tokens = indexesList.stream()
                .map(Indexes::getToken).filter(Objects::nonNull).toList();

        // ✅ LTP fetch still uses its own batched sign-ins (different API endpoint)
        Map<String, BigDecimal> todayPriceMap = fetchTodayPriceUsingPredictionService(tokens);

        // ─── Build filters ───
        Map<String, List<FuturesFilter>> bucket = new HashMap<>();

        for (Futures f : futuresList) {
            Indexes idx = indexByName.get(f.getName());
            if (idx == null) continue;

            BigDecimal todayPrice = todayPriceMap.get(idx.getToken());
            if (todayPrice == null) continue;

            // ✅ Pass shared session — no re-login per stock
            ExpiryOHLC ohlc = fetchExpiryOHLC(sharedSession, idx, expiryDate);
            if (ohlc == null || ohlc.close == null || ohlc.close.signum() == 0) {
                logger.debug("Skipping {} - no valid OHLC data", f.getName());
                continue;
            }

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

        // ─── Save filters ───
        bucket.forEach((indexType, rows) -> {
            FuturesConfig cfg = configMap.get(indexType);
            updateFilters(cfg, rows);
        });

        // ─── Breakout scan in same transaction, same session ───
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
        ff.setDirection(percentMove.signum() > 0 ? "UP" : "DOWN");

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

    private void updateFilters(FuturesConfig config, List<FuturesFilter> result) {
        String indexType = config.getIndexType();
        filterRepo.deleteByIndexType(indexType);
        List<FuturesFilter> saved = filterRepo.saveAll(result);
        logger.info("Saved {} filters for {} (with HIGH/LOW/RANGE data)", saved.size(), indexType);
    }

    // ─────────────────────────────────────────────
    //  Breakout scan — reuses the shared session
    // ─────────────────────────────────────────────

    public void runBreakoutScan(SmartConnect smartConnect,
                                Map<String, BigDecimal> ltpMap,
                                Map<String, Indexes> indexByName) {

        logger.info("========== BREAKOUT SCAN STARTED ==========");

        List<FuturesFilter> filters = filterRepo.findAll();
        logger.info("Found {} filters to scan", filters.size());

        if (filters.isEmpty()) {
            logger.warn("No filters found for breakout scan");
            return;
        }

        if (smartConnect == null) {
            logger.error("SmartConnect session is null — cannot run breakout scan");
            return;
        }

        List<Futures> futuresList = futuresRepo.findAll();
        Map<String, Futures> futuresMap = futuresList.stream()
                .collect(Collectors.toMap(Futures::getName, f -> f));

        List<FuturesBreakEvent> detectedEvents = new ArrayList<>();
        int scannedCount = 0, nearStructureCount = 0, breakoutCount = 0, breakdownCount = 0;

        for (FuturesFilter f : filters) {
            scannedCount++;

            Indexes idx = indexByName.get(f.getName());
            if (idx == null) {
                logger.debug("Skipping {} - no index mapping", f.getName());
                continue;
            }

            BigDecimal ltp = ltpMap.get(idx.getToken());
            if (ltp == null) {
                logger.debug("Skipping {} - no LTP", f.getName());
                continue;
            }

            if (!isNearExpiryStructure(f, ltp)) {
                logger.debug("Skipping {} - not near structure (LTP={}, Dir={}, High={}, Low={})",
                        f.getName(), ltp, f.getDirection(), f.getLastExpiryHigh(), f.getLastExpiryLow());
                continue;
            }

            nearStructureCount++;
            logger.info("🎯 {} NEAR structure: LTP={}, Dir={}, High={}, Low={}",
                    f.getName(), ltp, f.getDirection(), f.getLastExpiryHigh(), f.getLastExpiryLow());

            try {
                // ✅ Use shared session — no new login per stock
                JSONArray candles = fetchOneHourCandle(smartConnect, idx);
                if (candles == null || candles.isEmpty()) {
                    logger.warn("No candles returned for {}", f.getName());
                    continue;
                }

                JSONArray last = candles.getJSONArray(candles.length() - 1);
                BigDecimal hourClose = last.getBigDecimal(4);
                LocalDateTime hourEnd = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);

                logger.info("Candle check {}: hourClose={}, high={}, low={}, dir={}",
                        f.getName(), hourClose, f.getLastExpiryHigh(), f.getLastExpiryLow(), f.getDirection());

                Futures futures = futuresMap.get(f.getName());
                String primaryIndexType = determinePrimaryIndexType(futures);

                if ("UP".equals(f.getDirection()) && hourClose.compareTo(f.getLastExpiryHigh()) > 0) {
                    logger.info("🚀 BREAKOUT: {} → hourClose={} > high={}", f.getName(), hourClose, f.getLastExpiryHigh());
                    FuturesBreakEvent event = createBreakEventNoSave(f, hourClose, "BREAKOUT", hourEnd, primaryIndexType);
                    if (event != null) {
                        event.setCurrentPrice(ltp);
                        detectedEvents.add(event);
                        breakoutCount++;
                    }
                }

                if ("DOWN".equals(f.getDirection()) && hourClose.compareTo(f.getLastExpiryLow()) < 0) {
                    logger.info("📉 BREAKDOWN: {} → hourClose={} < low={}", f.getName(), hourClose, f.getLastExpiryLow());
                    FuturesBreakEvent event = createBreakEventNoSave(f, hourClose, "BREAKDOWN", hourEnd, primaryIndexType);
                    if (event != null) {
                        event.setCurrentPrice(ltp);
                        detectedEvents.add(event);
                        breakdownCount++;
                    }
                }

            } catch (Exception e) {
                logger.error("Breakout scan failed for {}: {}", f.getName(), e.getMessage(), e);
            }
        }

        logger.info("📊 Scan Summary: Scanned={}, Near Structure={}, Breakouts={}, Breakdowns={}",
                scannedCount, nearStructureCount, breakoutCount, breakdownCount);

        if (!detectedEvents.isEmpty()) {
            logger.info("Processing {} detected events for save/dedup", detectedEvents.size());
            List<FuturesBreakEvent> newSignals = saveAllBreakEventsWithDedup(detectedEvents);
            if (!newSignals.isEmpty()) {
                logger.info("✅ Sending notifications for {} NEW signals", newSignals.size());
                sendBreakoutNotificationsWithConfig(newSignals);
            } else {
                logger.info("ℹ️ No new signals to notify (all were updates)");
            }
        } else {
            logger.info("ℹ️ No breakout/breakdown events detected");
        }

        logger.info("========== BREAKOUT SCAN COMPLETED ==========");
    }

    // ─────────────────────────────────────────────
    //  Save & dedup
    // ─────────────────────────────────────────────

    @Transactional
    public List<FuturesBreakEvent> saveAllBreakEventsWithDedup(List<FuturesBreakEvent> events) {

        logger.info("======== SAVE & DEDUP STARTED ({} events) ========", events.size());

        // Step 1: in-memory dedup — one entry per stock+breakType
        Map<String, FuturesBreakEvent> uniqueMap = new LinkedHashMap<>();
        for (FuturesBreakEvent event : events) {
            String key = event.getName() + "_" + event.getBreakType(); // ← removed date
            uniqueMap.putIfAbsent(key, event);
        }
        logger.info("After in-memory dedup: {} unique events", uniqueMap.size());

        List<FuturesBreakEvent> newSignals   = new ArrayList<>();
        int updatedCount = 0;

        for (FuturesBreakEvent newEvent : uniqueMap.values()) {

            // Step 2: Check DB — does ANY record exist for this stock+breakType?
            Optional<FuturesBreakEvent> existing = futuresBreakEventRepo
                    .findByNameAndBreakType(newEvent.getName(), newEvent.getBreakType());

            if (existing.isPresent()) {
                // ✅ Record exists → just update price/PnL, NO notification
                FuturesBreakEvent record = existing.get();
                record.setCurrentPrice(newEvent.getCurrentPrice());
                record.setPercentMove(newEvent.getPercentMove());
                record.setBreakDate(newEvent.getBreakDate());
                record.setBreakTime(newEvent.getBreakTime());
                record.setStatus("ACTIVE");
                futuresBreakEventRepo.save(record);
                updatedCount++;
                logger.info("🔄 Updated existing signal: {} {} | Price={} | PnL={}% — no notification",
                        record.getName(), record.getBreakType(),
                        record.getCurrentPrice(), record.getPercentMove());

            } else {
                // ✅ No record exists → insert + send notification
                newEvent.setStatus("ACTIVE");
                newEvent.setCurrentPrice(newEvent.getBreakPrice());
                FuturesBreakEvent saved = futuresBreakEventRepo.save(newEvent);
                newSignals.add(saved);
                logger.info("🆕 NEW signal (ID={}): {} {} at {} — will notify",
                        saved.getId(), saved.getName(),
                        saved.getBreakType(), saved.getBreakPrice());
            }
        }

        logger.info("======== SAVE & DEDUP DONE: New={}, Updated={} ========",
                newSignals.size(), updatedCount);

        return newSignals; // ← only new ones trigger notification
    }

    // ─────────────────────────────────────────────
    //  Break event helpers
    // ─────────────────────────────────────────────

    private void updateBreakEvent(FuturesBreakEvent existing, FuturesBreakEvent newData) {
        existing.setCurrentPrice(newData.getCurrentPrice());
        existing.setPercentMove(newData.getPercentMove());

        if (checkStopLoss(existing)) {
            existing.setStatus("INACTIVE");
            existing.setExitReason("SL_HIT");
            existing.setExitPrice(existing.getCurrentPrice());
            existing.setExitDate(LocalDateTime.now());
            logger.warn("❌ SL Hit: {} {} | Entry={} | Exit={} | %Move={}%",
                    existing.getName(), existing.getBreakType(),
                    existing.getBreakPrice(), existing.getExitPrice(), existing.getPercentMove());
        }
    }

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
    //  Structure proximity check
    // ─────────────────────────────────────────────

    private boolean isNearExpiryStructure(FuturesFilter f, BigDecimal ltp) {
        if (ltp == null) return false;

        BigDecimal proximity = new BigDecimal("0.005"); // 0.5%

        if ("UP".equals(f.getDirection())) {
            if (f.getLastExpiryHigh() == null) return false;
            return percentDiff(ltp, f.getLastExpiryHigh()).compareTo(proximity) <= 0;
        }
        if ("DOWN".equals(f.getDirection())) {
            if (f.getLastExpiryLow() == null) return false;
            return percentDiff(ltp, f.getLastExpiryLow()).compareTo(proximity) <= 0;
        }
        return false;
    }

    private BigDecimal percentDiff(BigDecimal price, BigDecimal level) {
        if (price == null || level == null || level.compareTo(BigDecimal.ZERO) == 0)
            return BigDecimal.ZERO;
        return price.subtract(level).abs().divide(level, 6, RoundingMode.HALF_UP);
    }

    // ─────────────────────────────────────────────
    //  API calls — shared session, with delays & 503 handling
    // ─────────────────────────────────────────────

    private JSONArray fetchOneHourCandle(SmartConnect smartConnect, Indexes idx) {
        int maxRetries = 5;
        long delay = 3000;  // Start with 3s

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // ✅ Delay BEFORE retry (not before first attempt)
                if (attempt > 1) {
                    logger.info("Retrying 1H candle for {} after {}ms (attempt {}/{})...",
                            idx.getName(), delay, attempt, maxRetries);
                    sleepQuietly(delay);
                    delay *= 2;  // Exponential: 3s → 6s → 12s → 24s → 48s
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

                // ✅ Valid data — return immediately
                if (candles != null && !candles.isEmpty()) {
                    logger.debug("✅ 1H candle fetched for {} on attempt {}", idx.getName(), attempt);
                    return candles;
                }

                // ✅ null or empty — both are retryable
                String reason = (candles == null) ? "NULL" : "empty";
                logger.warn("{} 1H candle response for {} (attempt {}/{}) — will retry",
                        reason, idx.getName(), attempt, maxRetries);

            } catch (Exception e) {
                boolean is503 = e.getMessage() != null &&
                        (e.getMessage().contains("503") || e.getMessage().contains("timedout"));

                if (is503) {
                    logger.warn("⚠️ 503/timeout for {} 1H candle (attempt {}/{}) — will retry",
                            idx.getName(), attempt, maxRetries);
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


    /**
     * Fetches expiry-day OHLC for a single stock.
     * ✅ Uses the shared SmartConnect session — no re-login.
     * ✅ Adds inter-call delay to avoid rate limiting (503).
     * ✅ Handles 503 specifically with an extended back-off.
     */
    private ExpiryOHLC fetchExpiryOHLC(SmartConnect smartConnect, Indexes idx, LocalDate expiryDate) {
        int maxRetries = 5;
        long delay = 3000;  // Start with 3s like your working method

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // ✅ Delay BEFORE retry (not before first attempt)
                if (attempt > 1) {
                    logger.info("Retrying {} after {}ms (attempt {}/{})...",
                            idx.getName(), delay, attempt, maxRetries);
                    sleepQuietly(delay);
                    delay *= 2;  // Exponential backoff: 3s → 6s → 12s → 24s → 48s
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

                // ✅ Valid data — return immediately
                if (candles != null && !candles.isEmpty()) {
                    JSONArray c = candles.getJSONArray(candles.length() - 1);
                    BigDecimal open  = c.getBigDecimal(1);
                    BigDecimal high  = c.getBigDecimal(2);
                    BigDecimal low   = c.getBigDecimal(3);
                    BigDecimal close = c.getBigDecimal(4);
                    logger.debug("OHLC {}: O={} H={} L={} C={}", idx.getName(), open, high, low, close);
                    return new ExpiryOHLC(open, high, low, close);
                }

                // ✅ null or empty — both are retryable
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

        for (int i = 0; i < tokens.size(); i += BATCH_SIZE) {
            int endIndex = Math.min(i + BATCH_SIZE, tokens.size());
            List<String> batch = tokens.subList(i, endIndex);

            try {
                // ✅ Each LTP batch still gets its own session (different quota bucket)
                SmartConnect smartconnect = angelOne.signIn();
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

    /** Handles both response shapes: { data: { fetched: [...] } } and { fetched: [...] } */
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
            if (today.isBefore(thisMonthExpiry)) {
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

        LocalDate currentTradingDay = NSEWorkingDays.isNSEWorkingDay(today) ?
                today : NSEWorkingDays.getLastWorkingDay(today);
        LocalDate prevDay = currentTradingDay.minusDays(1);
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
}