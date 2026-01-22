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
    
    @Autowired private FuturesRepo futuresRepo;
    @Autowired private Nifty500Repo nifty500Repo;
    @Autowired private FuturesConfigRepo configRepo;
    @Autowired private FuturesFilterRepo filterRepo;
    @Autowired private IndexesRepo indexesRepo;
    @Autowired private PredictionService predictionService;
    @Autowired private AngelOne angelOne;
    @Autowired private TelegramService telegramService;
    @Autowired private FuturesBreakEventRepo futuresBreakEventRepo;
    
    private static class ExpiryOHLC {
        BigDecimal open, high, low, close;
        
        public ExpiryOHLC(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
            this.open = open; this.high = high; this.low = low; this.close = close;
        }
        
        public BigDecimal calculateRangePercent() {
            if (low == null || low.compareTo(BigDecimal.ZERO) == 0 || high == null) {
                return BigDecimal.ZERO;
            }
            return high.subtract(low).divide(low, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        }
    }

    @Transactional
    public void executeAll() {
        FuturesConfig masterConfig = configRepo.findByIndexType("NIFTY_500")
                .filter(c -> "Y".equalsIgnoreCase(c.getActive())).orElse(null);

        if (masterConfig == null) {
            logger.warn("NIFTY_500 config not active. Skipping execution.");
            return;
        }

        logger.info("Running master execution using NIFTY_500 with HIGH/LOW/RANGE tracking");
        executeMaster(masterConfig);
    }

    @Transactional
    private void executeMaster(FuturesConfig masterConfig) {
        LocalDate expiryDate = resolveExecutionDate(masterConfig);
        List<Futures> futuresList = futuresRepo.findByIsNifty500True();

        if (futuresList.isEmpty()) {
            logger.warn("No NIFTY_500 stocks found");
            return;
        }

        Map<String, FuturesConfig> configMap = configRepo.findByActive("Y").stream()
                .collect(Collectors.toMap(FuturesConfig::getIndexType, c -> c));

        List<Indexes> indexesList = indexesRepo.findByNameInAndExchange(
                futuresList.stream().map(Futures::getName).toList(), EXCHANGE);

        Map<String, Indexes> indexByName = indexesList.stream()
                .collect(Collectors.toMap(Indexes::getName, i -> i, 
                        (existing, duplicate) -> { 
                            logger.warn("Duplicate index entry found for {}. Skipping token {} (keeping {})",
                                    existing.getName(), duplicate.getToken(), existing.getToken());
                            return existing; 
                        }));

        List<String> tokens = indexesList.stream().map(Indexes::getToken)
                .filter(Objects::nonNull).toList();

        Map<String, BigDecimal> todayPriceMap = fetchTodayPriceUsingPredictionService(tokens);

        // Build filters
        Map<String, List<FuturesFilter>> bucket = new HashMap<>();
        for (Futures f : futuresList) {
            Indexes idx = indexByName.get(f.getName());
            if (idx == null) continue;

            BigDecimal todayPrice = todayPriceMap.get(idx.getToken());
            if (todayPrice == null) continue;

            ExpiryOHLC ohlc = fetchExpiryOHLC(idx, expiryDate);
            if (ohlc == null || ohlc.close == null || ohlc.close.signum() == 0) {
                logger.debug("Skipping {} - no valid OHLC data", f.getName());
                continue;
            }

            BigDecimal percentMove = todayPrice.subtract(ohlc.close)
                    .divide(ohlc.close, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            BigDecimal rangePercent = ohlc.calculateRangePercent();

            for (String indexType : resolveIndexTypes(f)) {
                FuturesConfig cfg = configMap.get(indexType);
                if (cfg == null) continue;

                FuturesFilter ff = buildFilter(f.getName(), indexType, todayPrice, ohlc,
                        percentMove, rangePercent, expiryDate, cfg);
                bucket.computeIfAbsent(indexType, k -> new ArrayList<>()).add(ff);
            }
        }

        // Save filters
        bucket.forEach((indexType, rows) -> {
            FuturesConfig cfg = configMap.get(indexType);
            updateFilters(cfg, rows);
        });

        // ✅ NOW RUN BREAKOUT SCAN IN SAME TRANSACTION
        logger.info("Running breakout scan after filter update");
        runBreakoutScan(todayPriceMap, indexByName);
    }
    
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
        if (Boolean.TRUE.equals(f.getIsNifty50())) list.add("NIFTY_50");
        if (Boolean.TRUE.equals(f.getIsNiftyNext50())) list.add("NIFTY_NEXT_50");
        if (Boolean.TRUE.equals(f.getIsNifty100())) list.add("NIFTY_100");
        if (Boolean.TRUE.equals(f.getIsNifty200())) list.add("NIFTY_200");
        if (Boolean.TRUE.equals(f.getIsNifty500())) list.add("NIFTY_500");
        return list;
    }

    private void updateFilters(FuturesConfig config, List<FuturesFilter> result) {
        String indexType = config.getIndexType();
        filterRepo.deleteByIndexType(indexType);
        List<FuturesFilter> saved = filterRepo.saveAll(result);
        logger.info("Saved {} filters for {} (with HIGH/LOW/RANGE data)", saved.size(), indexType);
    }

    // ✅ FIXED: Runs synchronously, deduplicates, sends notifications only for saved events
    public void runBreakoutScan(Map<String, BigDecimal> ltpMap, Map<String, Indexes> indexByName) {
        List<FuturesFilter> filters = filterRepo.findAll();
        if (filters.isEmpty()) {
            logger.info("No filters found for breakout scan");
            return;
        }

        SmartConnect smartConnect = angelOne.signIn();
        if (smartConnect == null) {
            logger.error("Failed to sign in for breakout scan");
            return;
        }

        List<FuturesBreakEvent> detectedEvents = new ArrayList<>();

        for (FuturesFilter f : filters) {
            Indexes idx = indexByName.get(f.getName());
            if (idx == null) continue;

            BigDecimal ltp = ltpMap.get(idx.getToken());
            if (ltp == null) continue;
            if (!isNearExpiryStructure(f, ltp)) continue;

            try {
                JSONArray candles = fetchOneHourCandle(smartConnect, idx);
                if (candles == null || candles.isEmpty()) continue;

                JSONArray last = candles.getJSONArray(candles.length() - 1);
                BigDecimal hourClose = last.getBigDecimal(4);
                LocalDateTime hourEnd = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);

                if ("UP".equals(f.getDirection()) && hourClose.compareTo(f.getLastExpiryHigh()) > 0) {
                    FuturesBreakEvent event = createBreakEventNoSave(f, hourClose, "BREAKOUT", hourEnd);
                    if (event != null) detectedEvents.add(event);
                }

                if ("DOWN".equals(f.getDirection()) && hourClose.compareTo(f.getLastExpiryLow()) < 0) {
                    FuturesBreakEvent event = createBreakEventNoSave(f, hourClose, "BREAKDOWN", hourEnd);
                    if (event != null) detectedEvents.add(event);
                }
            } catch (Exception e) {
                logger.error("Breakout scan failed for {}: {}", f.getName(), e.getMessage());
            }
        }

        // ✅ Deduplicate, save, and send notifications ONLY for saved events
        if (!detectedEvents.isEmpty()) {
            List<FuturesBreakEvent> savedEvents = saveAllBreakEventsWithDedup(detectedEvents);
            if (!savedEvents.isEmpty()) {
                sendBreakoutNotifications(savedEvents);
            }
        }
    }

    // ✅ NEW: Deduplicate in-memory, check DB, save and return ONLY saved events
    @Transactional
    public List<FuturesBreakEvent> saveAllBreakEventsWithDedup(List<FuturesBreakEvent> events) {
        LocalDate today = LocalDate.now();
        
        // Step 1: Deduplicate in-memory by name + breakType + date (ignore indexType)
        Map<String, FuturesBreakEvent> uniqueMap = new LinkedHashMap<>();
        
        for (FuturesBreakEvent event : events) {
            String key = event.getName() + "_" + event.getBreakType() + "_" + today;
            
            if (!uniqueMap.containsKey(key)) {
                uniqueMap.put(key, event);
                logger.debug("Added to unique map: {} {} {}", event.getName(), event.getBreakType(), today);
            } else {
                logger.debug("Duplicate in batch skipped: {} {} {} (indexType={})", 
                    event.getName(), event.getBreakType(), today, event.getIndexType());
            }
        }
        
        // Step 2: Filter out events that already exist in DB (check without indexType)
        List<FuturesBreakEvent> eventsToSave = new ArrayList<>();
        
        for (FuturesBreakEvent event : uniqueMap.values()) {
            boolean existsInDb = futuresBreakEventRepo.existsByNameAndBreakTypeAndBreakDate(
                    event.getName(), event.getBreakType(), today);
            
            if (!existsInDb) {
                eventsToSave.add(event);
                logger.info("Will save: {} {} {}", event.getName(), event.getBreakType(), today);
            } else {
                logger.info("Already exists in DB, skipped: {} {} {}", 
                    event.getName(), event.getBreakType(), today);
            }
        }
        
        // Step 3: Save only new unique events and return them
        if (!eventsToSave.isEmpty()) {
            List<FuturesBreakEvent> saved = futuresBreakEventRepo.saveAllAndFlush(eventsToSave);
            logger.info("✅ Saved {} unique break events for {}", saved.size(), today);
            return saved;
        } else {
            logger.info("No new events to save for {}", today);
            return new ArrayList<>();
        }
    }

    // ✅ Non-saving version for detection phase
    private FuturesBreakEvent createBreakEventNoSave(FuturesFilter f, BigDecimal hourClose, 
            String breakType, LocalDateTime hourEnd) {
        LocalDate today = hourEnd.toLocalDate();
        
        FuturesBreakEvent event = new FuturesBreakEvent();
        event.setName(f.getName());
        event.setIndexType(f.getIndexType());
        event.setBreakType(breakType);
        
        if ("BREAKOUT".equals(breakType)) {
            event.setReferenceLevel(f.getLastExpiryHigh());
            event.setStopLoss(f.getLastExpiryLow());
        } else {
            event.setReferenceLevel(f.getLastExpiryLow());
            event.setStopLoss(f.getLastExpiryHigh());
        }
        
        event.setBreakPrice(hourClose);
        event.setBreakDate(today);
        event.setBreakTime(hourEnd);
        event.setPercentMove(f.getPercentMove());
        event.setRangePercent(f.getRangePercent());
        
        logger.info("✅ Detected {} for {} | Entry={} | SL={}", 
                breakType, f.getName(), hourClose, event.getStopLoss());
        return event;
    }

    private boolean isNearExpiryStructure(FuturesFilter f, BigDecimal ltp) {
        BigDecimal proximity = new BigDecimal("0.005");
        if ("UP".equals(f.getDirection())) {
            return percentDiff(ltp, f.getLastExpiryHigh()).compareTo(proximity) <= 0;
        }
        if ("DOWN".equals(f.getDirection())) {
            return percentDiff(ltp, f.getLastExpiryLow()).compareTo(proximity) <= 0;
        }
        return false;
    }

    private BigDecimal percentDiff(BigDecimal price, BigDecimal level) {
        return price.subtract(level).abs().divide(level, 6, RoundingMode.HALF_UP);
    }

    private JSONArray fetchOneHourCandle(SmartConnect smartConnect, Indexes idx) throws Exception {
        LocalDateTime[] window = resolveNseOneHourWindow();
        JSONObject req = new JSONObject();
        req.put("exchange", idx.getExchange());
        req.put("symboltoken", idx.getToken());
        req.put("interval", "ONE_HOUR");
        req.put("fromdate", window[0].toString().replace("T", " "));
        req.put("todate", window[1].toString().replace("T", " "));
        return smartConnect.candleData(req);
    }

    private void sendBreakoutNotifications(List<FuturesBreakEvent> events) {
        if (events.isEmpty()) return;
        
        // Group events by index type
        Map<String, List<FuturesBreakEvent>> eventsByIndex = events.stream()
                .collect(Collectors.groupingBy(FuturesBreakEvent::getIndexType));
        
        // Send notifications for each index type
        eventsByIndex.forEach((indexType, indexEvents) -> {
            List<FuturesBreakEvent> breakouts = indexEvents.stream()
                    .filter(e -> "BREAKOUT".equals(e.getBreakType()))
                    .toList();
            List<FuturesBreakEvent> breakdowns = indexEvents.stream()
                    .filter(e -> "BREAKDOWN".equals(e.getBreakType()))
                    .toList();
            
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
                String.format("%-12s | %8s | %8s | %7s%n", "NAME", "ENTRY", "SL", "RANGE%") +
                "-----------------------------------------------\n";
        String footer = "```\n";
        StringBuilder batch = new StringBuilder(header);
        final int TELEGRAM_LIMIT = 3800;
        
        for (FuturesBreakEvent e : events) {
            String row = String.format("%-12s | %8.2f | %8.2f | %6.2f%%%n",
                    e.getName(), e.getBreakPrice(), e.getStopLoss(), e.getRangePercent());
            if (batch.length() + row.length() + footer.length() > TELEGRAM_LIMIT) {
                batch.append(footer);
                try { telegramService.sendBroadcast(batch.toString()); } 
                catch (Exception ex) { logger.error("Failed telegram: {}", ex.getMessage()); }
                batch = new StringBuilder(header);
            }
            batch.append(row);
        }
        
        if (batch.length() > header.length()) {
            batch.append(footer);
            try { telegramService.sendBroadcast(batch.toString()); } 
            catch (Exception ex) { logger.error("Failed telegram: {}", ex.getMessage()); }
        }
    }

    private Map<String, BigDecimal> fetchTodayPriceUsingPredictionService(List<String> tokens) {
        if (tokens.isEmpty()) return Map.of();

        final int BATCH_SIZE = 50;
        Map<String, BigDecimal> priceMap = new HashMap<>();
        logger.info("Fetching prices for {} tokens in batches of {}", tokens.size(), BATCH_SIZE);

        for (int i = 0; i < tokens.size(); i += BATCH_SIZE) {
            int endIndex = Math.min(i + BATCH_SIZE, tokens.size());
            List<String> batch = tokens.subList(i, endIndex);
            
            try {
                SmartConnect smartconnect = angelOne.signIn();
                JSONObject payload = predictionService.buildMarketDataPayload(batch, EXCHANGE);
                JSONObject response = predictionService.callMarketDataWithRetry(smartconnect, payload);

                if (response != null && response.has("data") && !response.isNull("data")) {
                    JSONObject data = response.getJSONObject("data");
                    if (data.has("fetched")) {
                        JSONArray fetched = data.getJSONArray("fetched");
                        for (int j = 0; j < fetched.length(); j++) {
                            JSONObject obj = fetched.getJSONObject(j);
                            String token = obj.get("symbolToken").toString();
                            if (obj.has("ltp") && !obj.isNull("ltp")) {
                                priceMap.put(token, new BigDecimal(obj.get("ltp").toString()));
                            }
                        }
                    }
                } else if (response != null && response.has("fetched")) {
                    JSONArray fetched = response.getJSONArray("fetched");
                    for (int j = 0; j < fetched.length(); j++) {
                        JSONObject obj = fetched.getJSONObject(j);
                        String token = obj.get("symbolToken").toString();
                        if (obj.has("ltp") && !obj.isNull("ltp")) {
                            priceMap.put(token, new BigDecimal(obj.get("ltp").toString()));
                        }
                    }
                }

                if (endIndex < tokens.size()) {
                    Thread.sleep(200);
                }
            } catch (Exception e) {
                logger.error("Error fetching prices for batch starting at index {}: {}", i, e.getMessage());
            } catch (SmartAPIException e) {
            	 logger.error("Error fetching prices for batch starting at index {}: {}", i, e.getMessage());
			}
        }

        logger.info("Successfully fetched prices for {}/{} tokens", priceMap.size(), tokens.size());
        return priceMap;
    }

    private ExpiryOHLC fetchExpiryOHLC(Indexes idx, LocalDate expiryDate) {
        int maxRetries = 3;
        int retryDelayMs = 2000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                SmartConnect smartconnect = angelOne.signIn();
                LocalDate tradingDate = NSEWorkingDays.isNSEWorkingDay(expiryDate) ?
                        expiryDate : NSEWorkingDays.getLastWorkingDay(expiryDate);
                LocalDate fromDate = tradingDate.minusDays(1);

                JSONObject req = new JSONObject();
                req.put("exchange", idx.getExchange());
                req.put("symboltoken", idx.getToken());
                req.put("interval", "ONE_DAY");
                req.put("fromdate", fromDate + " 09:15");
                req.put("todate", tradingDate + " 15:15");

                JSONArray candles = smartconnect.candleData(req);
                if (candles != null && !candles.isEmpty()) {
                    JSONArray lastCandle = candles.getJSONArray(candles.length() - 1);
                    BigDecimal open = lastCandle.getBigDecimal(1);
                    BigDecimal high = lastCandle.getBigDecimal(2);
                    BigDecimal low = lastCandle.getBigDecimal(3);
                    BigDecimal close = lastCandle.getBigDecimal(4);
                    
                    logger.debug("Fetched OHLC for {}: O={}, H={}, L={}, C={}", 
                            idx.getName(), open, high, low, close);
                    return new ExpiryOHLC(open, high, low, close);
                }
            } catch (Exception e) {
                logger.warn("Retry {}/{} failed for {} OHLC fetch: {}", attempt, maxRetries, idx.getName(), e.getMessage());
            }

            try {
                Thread.sleep(retryDelayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        logger.error("Failed to fetch OHLC data for {} after {} retries", idx.getName(), maxRetries);
        return null;
    }

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

    @Transactional
    public FuturesConfig partialUpdate(String indexType, FuturesConfigDto dto) {
        FuturesConfig config = configRepo.findByIndexType(indexType)
                .orElseThrow(() -> new IllegalStateException("Config not found for " + indexType));

        if (dto.getExpiryDate() != null) config.setExecutionDate(dto.getExpiryDate());
        if (dto.getMovementPercent() != null) config.setMovementPercent(dto.getMovementPercent());
        if (dto.getProfitPercent() != null) config.setProfitPercent(dto.getProfitPercent());
        if (dto.getLossPercent() != null) config.setLossPercent(dto.getLossPercent());
        if (dto.getUseNiftyExpiry() != null) config.setUseNiftyExpiry(dto.getUseNiftyExpiry());
        if (dto.getActive() != null) config.setActive(dto.getActive());

        return configRepo.save(config);
    }

    public FuturesConfig fetch(String indexType) {
        return configRepo.findByIndexType(indexType)
                .orElseThrow(() -> new IllegalStateException("Config not found for " + indexType));
    }

    public List<FuturesConfig> fetchAllActive() {
        return configRepo.findByActive("Y");
    }

    public List<FuturesConfig> fetchAll() {
        return configRepo.findAll();
    }

    private LocalDateTime[] resolveNseOneHourWindow() {
        ZoneId IST = ZoneId.of("Asia/Kolkata");
        LocalDate today = LocalDate.now(IST);

        LocalDate currentTradingDay = NSEWorkingDays.isNSEWorkingDay(today) ?
                today : NSEWorkingDays.getLastWorkingDay(today);

        LocalDate previousDay = currentTradingDay.minusDays(1);
        LocalDate previousTradingDay = NSEWorkingDays.isNSEWorkingDay(previousDay) ?
                previousDay : NSEWorkingDays.getLastWorkingDay(previousDay);

        LocalDateTime from = LocalDateTime.of(previousTradingDay, LocalTime.of(9, 15));
        LocalDateTime to = LocalDateTime.of(currentTradingDay, LocalTime.of(15, 15));

        logger.info("1H Window: from={}, to={}", from, to);
        return new LocalDateTime[]{from, to};
    }
    
    public List<FuturesBreakEvent> getAllBreakEvents() {
        return futuresBreakEventRepo.findAll();
    }

    public List<FuturesBreakEvent> getBreakEventsByDate(LocalDate date) {
        return futuresBreakEventRepo.findByBreakDate(date);
    }
}