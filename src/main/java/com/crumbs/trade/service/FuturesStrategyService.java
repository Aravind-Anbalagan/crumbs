package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.FuturesConfigDto;
import com.crumbs.trade.entity.Futures;
import com.crumbs.trade.entity.FuturesConfig;
import com.crumbs.trade.entity.FuturesFilter;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.repo.FuturesConfigRepo;
import com.crumbs.trade.repo.FuturesFilterRepo;
import com.crumbs.trade.repo.FuturesRepo;
import com.crumbs.trade.repo.Nifty500Repo;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.utility.NSEWorkingDays;
import com.crumbs.trade.utility.NiftyIndexType;

@Service
public class FuturesStrategyService {

    private static final Logger logger =
            LogManager.getLogger(FuturesStrategyService.class);

    private static final String EXCHANGE = "NSE";

    @Autowired private FuturesRepo futuresRepo;
    @Autowired private Nifty500Repo nifty500Repo;
    @Autowired private FuturesConfigRepo configRepo;
    @Autowired private FuturesFilterRepo filterRepo;
    @Autowired private IndexesRepo indexesRepo;
    @Autowired private PredictionService predictionService;
    @Autowired private AngelOne angelOne;
    @Autowired private TelegramService telegramService;

    /**
     * ✅ Execute for ALL active configs (based on active flag)
     * Executes for NIFTY50 and/or NIFTY500 depending on which configs are active
     */
    @Transactional
    public void executeAll() {

        FuturesConfig masterConfig = configRepo
                .findByIndexType("NIFTY_500")
                .filter(c -> "Y".equalsIgnoreCase(c.getActive()))
                .orElse(null);

        if (masterConfig == null) {
            logger.warn("NIFTY_500 config not active. Skipping execution.");
            return;
        }

        logger.info("Running master execution using NIFTY_500");
        executeMaster(masterConfig);
    }

    private void executeMaster(FuturesConfig masterConfig) {

        LocalDate expiryDate = resolveExecutionDate(masterConfig);

        // 1️⃣ Fetch all NIFTY_500 stocks ONCE
        List<Futures> futuresList = futuresRepo.findByIsNifty500True();

        if (futuresList.isEmpty()) {
            logger.warn("No NIFTY_500 stocks found");
            return;
        }

        // 2️⃣ Load all active configs into map
        Map<String, FuturesConfig> configMap =
                configRepo.findByActive("Y")
                          .stream()
                          .collect(Collectors.toMap(
                                  FuturesConfig::getIndexType,
                                  c -> c
                          ));

        // 3️⃣ Fetch Index master data ONCE
        List<Indexes> indexesList = indexesRepo.findByNameInAndExchange(
                futuresList.stream().map(Futures::getName).toList(),
                EXCHANGE
        );

        Map<String, Indexes> indexByName =
                indexesList.stream()
                           .collect(Collectors.toMap(
                               Indexes::getName,
                               i -> i,
                               (existing, duplicate) -> {
                                   logger.warn(
                                       "Duplicate index entry found for {}. Skipping token {} (keeping {})",
                                       existing.getName(),
                                       duplicate.getToken(),
                                       existing.getToken()
                                   );
                                   return existing; // ✅ keep first, skip duplicate
                               }
                           ));


        // 4️⃣ Fetch prices ONCE
        List<String> tokens = indexesList.stream()
                .map(Indexes::getToken)
                .filter(Objects::nonNull)
                .toList();

        Map<String, BigDecimal> todayPriceMap =
                fetchTodayPriceUsingPredictionService(tokens);

        // 5️⃣ Bucket results per INDEX_TYPE
        Map<String, List<FuturesFilter>> bucket = new HashMap<>();

        for (Futures f : futuresList) {

            Indexes idx = indexByName.get(f.getName());
            if (idx == null) continue;

            BigDecimal todayPrice = todayPriceMap.get(idx.getToken());
            if (todayPrice == null) continue;

            BigDecimal expiryClose =
                    fetchExpiryClosePrice(idx, expiryDate);

            if (expiryClose == null || expiryClose.signum() == 0) continue;

            BigDecimal percentMove = todayPrice
                    .subtract(expiryClose)
                    .divide(expiryClose, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            // 6️⃣ Execute per index_type
            for (String indexType : resolveIndexTypes(f)) {

                FuturesConfig cfg = configMap.get(indexType);
                if (cfg == null) continue;

                FuturesFilter ff = buildFilter(
                        f.getName(),
                        indexType,
                        todayPrice,
                        expiryClose,
                        percentMove,
                        expiryDate,
                        cfg
                );

                bucket
                  .computeIfAbsent(indexType, k -> new ArrayList<>())
                  .add(ff);
            }
        }

        // 7️⃣ Persist & notify PER INDEX
        bucket.forEach((indexType, rows) -> {
            FuturesConfig cfg = configMap.get(indexType);
            updateFilters(cfg, rows);
        });
    }
    private FuturesFilter buildFilter(
            String name,
            String indexType,
            BigDecimal todayPrice,
            BigDecimal expiryClose,
            BigDecimal percentMove,
            LocalDate expiryDate,
            FuturesConfig config) {

        FuturesFilter ff = new FuturesFilter();
        ff.setName(name);
        ff.setIndexType(indexType);
        ff.setLastExpiryPrice(expiryClose);
        ff.setLastTradedPrice(todayPrice);
        ff.setPercentMove(percentMove);
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

        if (Boolean.TRUE.equals(f.getIsNifty50()))
            list.add("NIFTY_50");

        if (Boolean.TRUE.equals(f.getIsNiftyNext50()))
            list.add("NIFTY_NEXT_50");

        if (Boolean.TRUE.equals(f.getIsNifty100()))
            list.add("NIFTY_100");

        if (Boolean.TRUE.equals(f.getIsNifty200()))
            list.add("NIFTY_200");

        if (Boolean.TRUE.equals(f.getIsNifty500()))
            list.add("NIFTY_500");

        return list;
    }


    /**
     * ✅ Core execution logic for a specific config
     */
    private void executeForConfig(FuturesConfig config) {

        String indexType = config.getIndexType();
        LocalDate expiryDate = resolveExecutionDate(config);

        // Get stock names based on index type
        List<String> stockNames = getStockNamesByIndexType(indexType);

        if (stockNames.isEmpty()) {
            logger.warn("No stocks found in repository for {}", indexType);
            return;
        }

        logger.info("Processing {} stocks for {}", stockNames.size(), indexType);

        // ✅ ADD DETAILED LOGGING TO FIND THE PROBLEMATIC STOCK
        List<Indexes> indexesList = new ArrayList<>();
        
        for (String name : stockNames) {
            try {
                logger.debug("Fetching index data for: {}", name);
                Indexes index = indexesRepo.findByNameAndExchange(name, EXCHANGE);
                if (index != null) {
                    indexesList.add(index);
                } else {
                    logger.warn("No index found for stock: {}", name);
                }
            } catch (Exception e) {
                logger.error("Error fetching index for stock: {} - Error: {}", name, e.getMessage());
                // Continue processing other stocks
            }
        }

        if (indexesList.isEmpty()) {
            logger.warn("No index data found for {}", indexType);
            return;
        }

        List<String> tokens = indexesList.stream()
                .map(Indexes::getToken)
                .filter(Objects::nonNull)
                .toList();

        Map<String, BigDecimal> todayPriceMap =
                fetchTodayPriceUsingPredictionService(tokens);

        List<FuturesFilter> result = new ArrayList<>();

        for (Indexes idx : indexesList) {

            BigDecimal todayPrice = todayPriceMap.get(idx.getToken());
            if (todayPrice == null) continue;

            BigDecimal expiryClose = fetchExpiryClosePrice(idx, expiryDate);

            if (expiryClose == null || expiryClose.compareTo(BigDecimal.ZERO) == 0) {
                logger.error("Expiry Price is empty for {}", idx.getName());
                continue;
            }

            BigDecimal percentMove = todayPrice
                    .subtract(expiryClose)
                    .divide(expiryClose, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            FuturesFilter ff = new FuturesFilter();
            ff.setName(idx.getName());
            ff.setIndexType(indexType);
            ff.setLastExpiryPrice(expiryClose);
            ff.setLastTradedPrice(todayPrice);
            ff.setPercentMove(percentMove);
            ff.setDirection(percentMove.signum() > 0 ? "UP" : "DOWN");

            if (percentMove.compareTo(config.getProfitPercent()) >= 0) {
                ff.setStatus("PROFIT");
            } else if (percentMove.compareTo(
                    config.getLossPercent().negate()) <= 0) {
                ff.setStatus("LOSS");
            } else {
                ff.setStatus("NEUTRAL");
            }

            ff.setLastExpiryDate(expiryDate);
            ff.setLastTradedDate(LocalDateTime.now());
            result.add(ff);
        }

        if (!result.isEmpty()) {
            updateFilters(config, result);
        } else {
            logger.warn("No results generated for {}", indexType);
        }
    }

    /**
     * ✅ Get stock names by index type
     */
    private List<String> getStockNamesByIndexType(String indexTypeStr) {

        NiftyIndexType indexType = NiftyIndexType.valueOf(indexTypeStr);

        List<Futures> futures = switch (indexType) {

            case NIFTY_50 -> futuresRepo.findByIsNifty50True();

            case NIFTY_NEXT_50 -> futuresRepo.findByIsNiftyNext50True();

            case NIFTY_100 -> futuresRepo.findByIsNifty100True();

            case NIFTY_200 -> futuresRepo.findByIsNifty200True();

            case NIFTY_500 -> futuresRepo.findByIsNifty500True();
        };

        return futures.stream()
                .map(Futures::getName)
                .filter(Objects::nonNull)
                .toList();
    }


    private void updateFilters(
            FuturesConfig config,
            List<FuturesFilter> result) {

        String indexType = config.getIndexType();
        
        // Delete only filters for this index type
        filterRepo.deleteByIndexType(indexType);
        List<FuturesFilter> saved = filterRepo.saveAll(result);

        logger.info("Saved {} filters for {}", saved.size(), indexType);

        // Schedule notification after transaction commits
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendNotificationIfRequired(config, saved);
                }
            }
        );
    }

    /* ================= NOTIFICATION ================= */

    private void sendNotificationIfRequired(
            FuturesConfig config,
            List<FuturesFilter> result) {

        if (!"Y".equalsIgnoreCase(config.getNotificationRequired())) {
            logger.debug("Notification not required for {}", config.getIndexType());
            return;
        }

        BigDecimal threshold = config.getMovementPercent();
        if (threshold == null || threshold.compareTo(BigDecimal.ZERO) <= 0) {
            logger.warn("Invalid threshold for {}", config.getIndexType());
            return;
        }

        List<FuturesFilter> alertRows = result.stream()
                .filter(f -> f.getPercentMove() != null)
                .filter(f -> f.getPercentMove().abs().compareTo(threshold) <= 0)
                .sorted(Comparator.comparing(FuturesFilter::getPercentMove).reversed())
                .collect(Collectors.toList());

        if (!alertRows.isEmpty()) {
            logger.info("Sending notification for {} stocks in {}", 
                    alertRows.size(), config.getIndexType());
            sendTelegramInBatches(config, alertRows, threshold);
        } else {
            logger.debug("No stocks match notification criteria for {}", config.getIndexType());
        }
    }

    private void sendTelegramInBatches(
            FuturesConfig config,
            List<FuturesFilter> rows,
            BigDecimal threshold) {

        final int TELEGRAM_LIMIT = 3800;
        String header = buildTableHeader(config.getIndexType(), threshold);
        String footer = "-----------------------------------------------------\n```";

        StringBuilder batch = new StringBuilder(header);

        for (FuturesFilter f : rows) {
            String row = buildTableRow(f);

            if (batch.length() + row.length() + footer.length() > TELEGRAM_LIMIT) {
                batch.append(footer);
                telegramService.sendBroadcast(batch.toString());
                batch = new StringBuilder(header);
            }
            batch.append(row);
        }

        if (batch.length() > header.length()) {
            batch.append(footer);
            telegramService.sendBroadcast(batch.toString());
        }
    }

    private String buildTableRow(FuturesFilter f) {
        return String.format(
            "%-18s | %18.2f | %18.2f%n",
            f.getName(),
            f.getLastExpiryPrice(),
            f.getLastTradedPrice()
        );
    }

    private String buildTableHeader(String indexType, BigDecimal threshold) {
        return new StringBuilder()
            .append("📊 *")
            .append(indexType != null ? indexType : "NIFTY")
            .append(" – Near Expiry Price* (±")
            .append(threshold)
            .append("%)\n\n")
            .append("```\n")
            .append("--------------------------------------------------------------------------------\n")
            .append(String.format(
                "%-18s | %18s | %18s%n",
                "NAME", "EXPIRY CLOSE", "NOW"
            ))
            .append("--------------------------------------------------------------------------------\n")
            .toString();
    }

    /* ================= PRICE FETCH ================= */

    private Map<String, BigDecimal> fetchTodayPriceUsingPredictionService(
            List<String> tokens) {

        if (tokens.isEmpty()) {
            return Map.of();
        }

        // ✅ Process in batches to avoid API limits
        final int BATCH_SIZE = 50; // Angel One typically allows 50-100 tokens per request
        Map<String, BigDecimal> priceMap = new HashMap<>();

        logger.info("Fetching prices for {} tokens in batches of {}", tokens.size(), BATCH_SIZE);

        for (int i = 0; i < tokens.size(); i += BATCH_SIZE) {
            int endIndex = Math.min(i + BATCH_SIZE, tokens.size());
            List<String> batch = tokens.subList(i, endIndex);
            
            logger.debug("Processing batch {}/{}: tokens {} to {}", 
                    (i / BATCH_SIZE) + 1, 
                    (tokens.size() + BATCH_SIZE - 1) / BATCH_SIZE,
                    i + 1, 
                    endIndex);

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
                                priceMap.put(
                                        token,
                                        new BigDecimal(obj.get("ltp").toString())
                                );
                            }
                        }
                    }
                } else if (response != null && response.has("fetched")) {
                    // ✅ Alternative structure: response.fetched (direct)
                    JSONArray fetched = response.getJSONArray("fetched");

                    for (int j = 0; j < fetched.length(); j++) {
                        JSONObject obj = fetched.getJSONObject(j);
                        String token = obj.get("symbolToken").toString();

                        if (obj.has("ltp") && !obj.isNull("ltp")) {
                            priceMap.put(
                                    token,
                                    new BigDecimal(obj.get("ltp").toString())
                            );
                        }
                    }
                } else {
                    logger.warn("Empty or null response for batch starting at index {}", i);
                }

                // ✅ Add small delay between batches to avoid rate limiting
                if (endIndex < tokens.size()) {
                    Thread.sleep(200); // 200ms delay between batches
                }

            } catch (JSONException e) {
                logger.error("JSON error processing batch starting at index {}: {}", i, e.getMessage());
            } catch (SmartAPIException e) {
                logger.error("SmartAPI error for batch starting at index {}: {}", i, e.getMessage());
            } catch (Exception e) {
                logger.error("Error fetching prices for batch starting at index {}: {}", i, e.getMessage());
                // ✅ Check if it's InterruptedException and break the loop
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    logger.error("Interrupted while processing batches");
                    break;
                }
            }
        }

        logger.info("Successfully fetched prices for {}/{} tokens", priceMap.size(), tokens.size());
        return priceMap;
    }

    private BigDecimal fetchExpiryClosePrice(
            Indexes idx, LocalDate expiryDate) {

        int maxRetries = 3;
        int retryDelayMs = 2000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                SmartConnect smartconnect = angelOne.signIn();

                LocalDate tradingDate =
                        NSEWorkingDays.isNSEWorkingDay(expiryDate)
                                ? expiryDate
                                : NSEWorkingDays.getLastWorkingDay(expiryDate);

                LocalDate fromDate = tradingDate.minusDays(1);

                JSONObject req = new JSONObject();
                req.put("exchange", idx.getExchange());
                req.put("symboltoken", idx.getToken());
                req.put("interval", "ONE_DAY");
                req.put("fromdate", fromDate + " 09:15");
                req.put("todate", tradingDate + " 15:15");

                JSONArray candles = smartconnect.candleData(req);

                if (candles != null && !candles.isEmpty()) {
                    JSONArray lastCandle =
                            candles.getJSONArray(candles.length() - 1);
                    return lastCandle.getBigDecimal(4);
                }

            } catch (Exception e) {
                logger.warn("Retry {}/{} failed for {}",
                        attempt, maxRetries, idx.getName(), e);
            }

            try {
                Thread.sleep(retryDelayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return null;
    }

    private LocalDate resolveExecutionDate(FuturesConfig config) {

        if ("Y".equalsIgnoreCase(config.getUseNiftyExpiry())) {

            LocalDate today = LocalDate.now();
            LocalDate thisMonthExpiry =
                    today.with(TemporalAdjusters.lastInMonth(DayOfWeek.TUESDAY));

            if (today.isBefore(thisMonthExpiry)) {
                return today.minusMonths(1)
                        .with(TemporalAdjusters.lastInMonth(DayOfWeek.TUESDAY));
            }
            return thisMonthExpiry;
        }

        if (config.getExecutionDate() == null) {
            throw new IllegalStateException(
                    "execution_date must be set when use_nifty_expiry = N");
        }

        return config.getExecutionDate();
    }

    /* ================= CONFIG MANAGEMENT ================= */

    /**
     * ✅ Update config by index type
     */
    @Transactional
    public FuturesConfig partialUpdate(String indexType, FuturesConfigDto dto) {
        FuturesConfig config = configRepo.findByIndexType(indexType)
                .orElseThrow(() -> 
                        new IllegalStateException("Config not found for " + indexType));

        if (dto.getExpiryDate() != null)
            config.setExecutionDate(dto.getExpiryDate());
        if (dto.getMovementPercent() != null)
            config.setMovementPercent(dto.getMovementPercent());
        if (dto.getProfitPercent() != null)
            config.setProfitPercent(dto.getProfitPercent());
        if (dto.getLossPercent() != null)
            config.setLossPercent(dto.getLossPercent());
        if (dto.getUseNiftyExpiry() != null)
            config.setUseNiftyExpiry(dto.getUseNiftyExpiry());
        if (dto.getActive() != null)
            config.setActive(dto.getActive());

        return configRepo.save(config);
    }

    /**
     * ✅ Fetch config by index type
     */
    public FuturesConfig fetch(String indexType) {
        return configRepo.findByIndexType(indexType)
                .orElseThrow(() -> 
                        new IllegalStateException("Config not found for " + indexType));
    }

    /**
     * ✅ Fetch all active configs
     */
    public List<FuturesConfig> fetchAllActive() {
        return configRepo.findByActive("Y");
    }

    /**
     * ✅ Fetch all configs
     */
    public List<FuturesConfig> fetchAll() {
        return configRepo.findAll();
    }

}