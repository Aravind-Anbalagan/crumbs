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

        // ✅ Get all futures for primary index type lookup
        List<Futures> futuresList = futuresRepo.findAll();
        Map<String, Futures> futuresMap = futuresList.stream()
                .collect(Collectors.toMap(Futures::getName, f -> f));

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

                // ✅ Get the primary index type from FUTURES table
                Futures futures = futuresMap.get(f.getName());
                String primaryIndexType = determinePrimaryIndexType(futures);

                if ("UP".equals(f.getDirection()) && hourClose.compareTo(f.getLastExpiryHigh()) > 0) {
                    FuturesBreakEvent event = createBreakEventNoSave(f, hourClose, "BREAKOUT", hourEnd, primaryIndexType);
                    if (event != null) {
                        event.setCurrentPrice(ltp); // Set current LTP for tracking
                        detectedEvents.add(event);
                    }
                }

                if ("DOWN".equals(f.getDirection()) && hourClose.compareTo(f.getLastExpiryLow()) < 0) {
                    FuturesBreakEvent event = createBreakEventNoSave(f, hourClose, "BREAKDOWN", hourEnd, primaryIndexType);
                    if (event != null) {
                        event.setCurrentPrice(ltp); // Set current LTP for tracking
                        detectedEvents.add(event);
                    }
                }
            } catch (Exception e) {
                logger.error("Breakout scan failed for {}: {}", f.getName(), e.getMessage());
            }
        }

        // ✅ Deduplicate, save, and send notifications ONLY for saved events with notification enabled
        if (!detectedEvents.isEmpty()) {
            List<FuturesBreakEvent> savedEvents = saveAllBreakEventsWithDedup(detectedEvents);
            if (!savedEvents.isEmpty()) {
                sendBreakoutNotificationsWithConfig(savedEvents);
            }
        }
    }

    // ✅ NEW: Track and update existing break events, calculate % from break price
    @Transactional
    public List<FuturesBreakEvent> saveAllBreakEventsWithDedup(List<FuturesBreakEvent> events) {
        LocalDate today = LocalDate.now();
        
        // Step 1: Deduplicate in-memory by name + breakType + date
        Map<String, FuturesBreakEvent> uniqueMap = new LinkedHashMap<>();
        
        for (FuturesBreakEvent event : events) {
            String key = event.getName() + "_" + event.getBreakType() + "_" + today;
            
            if (!uniqueMap.containsKey(key)) {
                uniqueMap.put(key, event);
                logger.debug("Added to unique map: {} {} {} (indexType={})", 
                    event.getName(), event.getBreakType(), today, event.getIndexType());
            }
        }
        
        // Step 2: Check existing records and update or create new
        List<FuturesBreakEvent> savedEvents = new ArrayList<>();
        
        for (FuturesBreakEvent newEvent : uniqueMap.values()) {
            // Check if active signal already exists
            Optional<FuturesBreakEvent> existingActive = futuresBreakEventRepo
                    .findByNameAndBreakTypeAndBreakDateAndStatus(
                            newEvent.getName(), newEvent.getBreakType(), today, "ACTIVE");
            
            if (existingActive.isPresent()) {
                // Update existing active record
                FuturesBreakEvent existing = existingActive.get();
                updateBreakEvent(existing, newEvent);
                FuturesBreakEvent updated = futuresBreakEventRepo.save(existing);
                savedEvents.add(updated);
                logger.info("✅ Updated active signal: {} {} {} (PnL: {}%)", 
                        updated.getName(), updated.getBreakType(), today, updated.getPercentMove());
            } else {
                // Check if inactive signal exists (don't allow new signal same day if SL hit)
                boolean inactiveExists = futuresBreakEventRepo.existsByNameAndBreakTypeAndBreakDate(
                        newEvent.getName(), newEvent.getBreakType(), today);
                
                if (!inactiveExists) {
                    // Create new signal
                    newEvent.setStatus("ACTIVE");
                    newEvent.setCurrentPrice(newEvent.getBreakPrice()); // Initial current price
                    FuturesBreakEvent saved = futuresBreakEventRepo.save(newEvent);
                    savedEvents.add(saved);
                    logger.info("✅ New signal created: {} {} {} at {}", 
                            saved.getName(), saved.getBreakType(), today, saved.getBreakPrice());
                } else {
                    logger.info("Inactive signal exists for {} {} {}, skipping new entry", 
                            newEvent.getName(), newEvent.getBreakType(), today);
                }
            }
        }
        
        logger.info("✅ Processed {} break events for {}", savedEvents.size(), today);
        return savedEvents;
    }

    // ✅ NEW: Update existing break event with current price and check SL
    private void updateBreakEvent(FuturesBreakEvent existing, FuturesBreakEvent newData) {
        // Update current price
        existing.setCurrentPrice(newData.getCurrentPrice());
        
        // ✅ Update percentMove from filter (already calculated from expiry close to current)
        existing.setPercentMove(newData.getPercentMove());
        
        // Check if stop loss is hit
        boolean slHit = checkStopLoss(existing);
        
        if (slHit) {
            existing.setStatus("INACTIVE");
            existing.setExitReason("SL_HIT");
            existing.setExitPrice(existing.getCurrentPrice());
            existing.setExitDate(LocalDateTime.now());
            logger.warn("❌ Stop Loss Hit: {} {} | Entry={} | Exit={} | %Move={}%", 
                    existing.getName(), existing.getBreakType(), 
                    existing.getBreakPrice(), existing.getExitPrice(), existing.getPercentMove());
        }
    }

    // ✅ NEW: Calculate % move from entry (break price) to current price
    private BigDecimal calculatePercentMove(BigDecimal entryPrice, BigDecimal currentPrice) {
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) == 0 || currentPrice == null) {
            return BigDecimal.ZERO;
        }
        return currentPrice.subtract(entryPrice)
                .divide(entryPrice, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    // ✅ NEW: Check if stop loss is hit
    private boolean checkStopLoss(FuturesBreakEvent event) {
        if (event.getCurrentPrice() == null || event.getStopLoss() == null) {
            return false;
        }
        
        if ("BREAKOUT".equals(event.getBreakType())) {
            // For breakout (long), SL is below entry
            return event.getCurrentPrice().compareTo(event.getStopLoss()) <= 0;
        } else if ("BREAKDOWN".equals(event.getBreakType())) {
            // For breakdown (short), SL is above entry
            return event.getCurrentPrice().compareTo(event.getStopLoss()) >= 0;
        }
        
        return false;
    }

    // ✅ NEW: Determine primary index type based on FUTURES table hierarchy
    private String determinePrimaryIndexType(Futures futures) {
        if (futures == null) return "NIFTY_500"; // Default fallback
        
        // Priority: NIFTY_50 > NIFTY_NEXT_50 > NIFTY_100 > NIFTY_200 > NIFTY_500
        if (Boolean.TRUE.equals(futures.getIsNifty50())) return "NIFTY_50";
        if (Boolean.TRUE.equals(futures.getIsNiftyNext50())) return "NIFTY_NEXT_50";
        if (Boolean.TRUE.equals(futures.getIsNifty100())) return "NIFTY_100";
        if (Boolean.TRUE.equals(futures.getIsNifty200())) return "NIFTY_200";
        if (Boolean.TRUE.equals(futures.getIsNifty500())) return "NIFTY_500";
        
        return "NIFTY_500"; // Default fallback
    }

    // ✅ Updated: Now accepts primaryIndexType parameter from FUTURES table
    private FuturesBreakEvent createBreakEventNoSave(FuturesFilter f, BigDecimal hourClose, 
            String breakType, LocalDateTime hourEnd, String primaryIndexType) {
        LocalDate today = hourEnd.toLocalDate();
        
        FuturesBreakEvent event = new FuturesBreakEvent();
        event.setName(f.getName());
        event.setIndexType(primaryIndexType); // ✅ Use primary index from FUTURES table
        event.setBreakType(breakType);
        
        if ("BREAKOUT".equals(breakType)) {
            event.setReferenceLevel(f.getLastExpiryHigh());
            event.setStopLoss(f.getLastExpiryLow());
        } else {
            event.setReferenceLevel(f.getLastExpiryLow());
            event.setStopLoss(f.getLastExpiryHigh());
        }
        
        event.setBreakPrice(hourClose); // Entry price
        event.setCurrentPrice(hourClose); // Initial current price = entry price
        event.setBreakDate(today);
        event.setBreakTime(hourEnd);
        event.setRangePercent(f.getRangePercent()); // Original expiry day range
        
        // ✅ Use percentMove from filter (calculated from expiry close to current LTP)
        event.setPercentMove(f.getPercentMove());
        event.setStatus("ACTIVE");
        
        logger.info("✅ Detected {} for {} (indexType={}) | Entry={} | SL={} | %Move={}%", 
                breakType, f.getName(), primaryIndexType, hourClose, event.getStopLoss(), event.getPercentMove());
        return event;
    }

    private boolean isNearExpiryStructure(FuturesFilter f, BigDecimal ltp) {
        if (ltp == null) return false;
        
        BigDecimal proximity = new BigDecimal("0.005");
        
        if ("UP".equals(f.getDirection())) {
            if (f.getLastExpiryHigh() == null) {
                logger.debug("Skipping {} - lastExpiryHigh is null", f.getName());
                return false;
            }
            return percentDiff(ltp, f.getLastExpiryHigh()).compareTo(proximity) <= 0;
        }
        
        if ("DOWN".equals(f.getDirection())) {
            if (f.getLastExpiryLow() == null) {
                logger.debug("Skipping {} - lastExpiryLow is null", f.getName());
                return false;
            }
            return percentDiff(ltp, f.getLastExpiryLow()).compareTo(proximity) <= 0;
        }
        
        return false;
    }

    private BigDecimal percentDiff(BigDecimal price, BigDecimal level) {
        if (price == null || level == null || level.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
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

    private void sendBreakoutNotificationsWithConfig(List<FuturesBreakEvent> events) {
        if (events.isEmpty()) return;
        
        // Get all active configs with notification settings
        Map<String, FuturesConfig> configMap = configRepo.findByActive("Y").stream()
                .collect(Collectors.toMap(FuturesConfig::getIndexType, c -> c));
        
        // Group events by index type
        Map<String, List<FuturesBreakEvent>> eventsByIndex = events.stream()
                .collect(Collectors.groupingBy(FuturesBreakEvent::getIndexType));
        
        // Send notifications for each index type if notification is required
        eventsByIndex.forEach((indexType, indexEvents) -> {
            FuturesConfig config = configMap.get(indexType);
            
            // Check if notification is required for this index type
            if (config == null || !"Y".equalsIgnoreCase(config.getNotificationRequired())) {
                logger.info("Notifications disabled for {}. Skipping {} events.", indexType, indexEvents.size());
                return;
            }
            
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