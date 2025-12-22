package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.Prediction;
import com.crumbs.trade.entity.PredictionHistory;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.PredictionHistoryRepo;
import com.crumbs.trade.repo.PredictionRepo;
import com.crumbs.trade.repo.StrategyRepo;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PredictionService {

    private static final Logger logger = LogManager.getLogger(PredictionService.class);

    // Constants
    private static final int BATCH_SIZE = 25;
    private static final int MAX_RETRIES = 4;
    private static final long BASE_BACKOFF_MS = 300;
    private static final long MIN_BACKOFF_MS = 200L;
    private static final long BATCH_DELAY_MS = 150L;
    
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    
    private static final double COVERAGE_WEIGHT = 0.6;
    private static final double CONSISTENCY_WEIGHT = 0.4;
    private static final int SCALE = 10;
    private static final int PERCENTAGE_SCALE = 4;

    // Dependencies
    private final PredictionRepo predictionRepo;
    private final IndexesRepo indexesRepo;
    private final StrategyRepo strategyRepo;
    
    @Autowired
    private AngelOneService angelOneService;
    
    @Autowired
    private AngelOne angelOne;
    
    @Autowired PredictionHistoryRepo predictionHistoryRepo;


    // ========================================
    // PUBLIC API METHODS
    // ========================================

    public PredictionResult predictNifty() throws SmartAPIException {
        logger.info("Starting basic Nifty prediction");
        
        PredictionData data = preparePredictionData();
        PredictionResult result = calculateBasicPrediction(data);
        
        logger.info("Basic prediction complete: Current={}, Predicted={}, Diff={}", 
            result.currentPrice, result.predictedPrice, result.difference);
        
        return result;
    }

    public AdvancedPredictionResult predictNiftyAdvanced(String days) throws SmartAPIException {
        logger.info("Starting advanced Nifty prediction");
        
        PredictionData data = preparePredictionData();
        AdvancedPredictionResult result = calculateAdvancedPrediction(data);
        List<PredictionHistory> predictionList = new ArrayList<>();
        List<PredictionHistory> history =
                fetchPredictionHistoryByDays(days);

        result.getPredictionList().addAll(history);
        logger.info("Advanced prediction complete: Current={}, Predicted={}, Confidence={}, Sentiment={}", 
            result.currentPrice, result.predictedPrice, result.confidenceScore, result.sentiment);
        
        return result;
    }
    
    private List<PredictionHistory> fetchPredictionHistoryByDays(String days) {

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startOfToday = now.toLocalDate().atStartOfDay();

        // Default → today
        if (days == null || days.isBlank()) {
            return predictionHistoryRepo.findToday(startOfToday);
        }

        // All data
        if ("all".equalsIgnoreCase(days)) {
            return predictionHistoryRepo.findAll();
        }

        try {
            int noOfDays = Integer.parseInt(days);

            // Example:
            // 5 → last 5 days including today
            LocalDateTime fromDate =
                    startOfToday.minusDays(noOfDays - 1);

            return predictionHistoryRepo.findFromDate(fromDate);

        } catch (NumberFormatException e) {
            logger.warn("Invalid days param: {}. Falling back to today.", days);
            return predictionHistoryRepo.findToday(startOfToday);
        }
    }



    // ========================================
    // CORE PREPARATION LOGIC
    // ========================================

    private PredictionData preparePredictionData() throws SmartAPIException {
        SmartConnect smartconnect = angelOne.signIn();
        Strategy strategy = strategyRepo.findByName("NIFTY_INDEX");
        
        if (strategy == null) {
            throw new IllegalStateException("Strategy 'NIFTY_INDEX' not found");
        }

        BigDecimal currentNifty = angelOneService.getcurrentPrice(
            smartconnect,
            strategy.getExchange(),
            strategy.getTradingsymbol(),
            strategy.getToken(),
            "ltp"
        );

        List<Prediction> stocks = predictionRepo.findAll();
        logger.info("Loaded {} stocks from prediction repository", stocks.size());

        // Build token map efficiently (single DB query)
        Map<String, Prediction> tokenMap = buildTokenMap(stocks);
        logger.info("✔ Token mapped length → {}", tokenMap.size());

        // Bulk fetch market data
        List<String> tokens = new ArrayList<>(tokenMap.keySet());
        Map<String, JSONObject> mdMap = fetchMarketDataForTokens(smartconnect, tokens);
        
        // Update predictions with market data
        updatePredictionsWithMarketData(tokenMap, mdMap);

        // Filter valid stocks
        List<Prediction> validStocks = filterValidStocks(stocks);
        logger.info("Valid stocks after filtering: {}/{}", validStocks.size(), stocks.size());

        return new PredictionData(currentNifty, validStocks, stocks.size());
    }


    // ========================================
    // TOKEN MAPPING (OPTIMIZED - NO N+1)
    // ========================================

    private Map<String, Prediction> buildTokenMap(List<Prediction> stocks) {
        // Fetch all indexes once
        List<Indexes> allIndexes = indexesRepo.findAll();
        
        Map<String, Indexes> indexMap = allIndexes.stream()
            .collect(Collectors.toMap(
                idx -> createIndexKey(idx.getName(), idx.getExchange()),
                idx -> idx,
                (existing, replacement) -> existing
            ));

        Map<String, Prediction> tokenMap = new HashMap<>();

        for (Prediction p : stocks) {
            String key = createIndexKey(p.getName(), p.getExchange());
            Indexes idx = indexMap.get(key);

            if (idx == null) {
                logger.warn("❌ MISSING INDEX ENTRY → name={} exchange={}", p.getName(), p.getExchange());
                continue;
            }

            if (idx.getToken() == null || idx.getToken().isBlank()) {
                logger.warn("❌ MISSING TOKEN in Indexes table → name={} exchange={}", 
                    p.getName(), p.getExchange());
                continue;
            }

            tokenMap.put(idx.getToken(), p);
        }

        return tokenMap;
    }

    private String createIndexKey(String name, String exchange) {
        return name + "_" + exchange;
    }


    // ========================================
    // MARKET DATA FETCHING
    // ========================================

    private Map<String, JSONObject> fetchMarketDataForTokens(SmartConnect smartconnect, List<String> tokens) 
            throws SmartAPIException {
        
        Map<String, JSONObject> result = new HashMap<>();
        Set<String> failedTokens = new HashSet<>();

        for (int start = 0; start < tokens.size(); start += BATCH_SIZE) {
            int end = Math.min(tokens.size(), start + BATCH_SIZE);
            List<String> batch = tokens.subList(start, end);

            try {
                JSONObject payload = buildMarketDataPayload(batch,"NSE");
                JSONObject response = callMarketDataWithRetry(smartconnect, payload);

                if (response.has("fetched")) {
                    JSONArray fetched = response.getJSONArray("fetched");

                    for (int i = 0; i < fetched.length(); i++) {
                        JSONObject item = fetched.getJSONObject(i);
                        String symbolToken = item.get("symbolToken").toString();
                        result.put(symbolToken, item);
                    }
                }

                Thread.sleep(BATCH_DELAY_MS);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Thread interrupted while fetching batch", e);
                throw new SmartAPIException("Interrupted during market data fetch");
                
            } catch (Exception ex) {
                logger.error("Error fetching token batch: {}", ex.getMessage(), ex);
                failedTokens.addAll(batch);
            }
        }

        if (!failedTokens.isEmpty()) {
            logger.warn("Failed to fetch data for {} tokens: {}", failedTokens.size(), failedTokens);
        }

        return result;
    }

    public JSONObject buildMarketDataPayload(List<String> batch,String exchange) {
        JSONObject payload = new JSONObject();
        payload.put("mode", "FULL");

        JSONObject exchangeTokens = new JSONObject();
        JSONArray arr = new JSONArray();
        batch.forEach(arr::put);
        exchangeTokens.put(exchange, arr);
        
        payload.put("exchangeTokens", exchangeTokens);
        return payload;
    }


    // ========================================
    // RETRY LOGIC WITH EXPONENTIAL BACKOFF
    // ========================================

    public JSONObject callMarketDataWithRetry(SmartConnect smartconnect, JSONObject payload)
            throws SmartAPIException, InterruptedException, IOException {

        int attempt = 0;

        while (true) {
            try {
                return smartconnect.marketData(payload);

            } catch (SmartAPIException e) {
                attempt++;
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

                boolean retryable = msg.contains("429") || msg.contains("limit") || msg.contains("rate");

                if (attempt > MAX_RETRIES || !retryable) {
                    logger.error("API call failed after {} attempts", attempt);
                    throw e;
                }

                long backoff = BASE_BACKOFF_MS * (1L << (attempt - 1));
                long jitter = ThreadLocalRandom.current().nextLong(-backoff / 2, backoff / 2);
                long sleepMs = Math.max(MIN_BACKOFF_MS, backoff + jitter);

                logger.warn("RATE LIMIT – retry {}/{}. Sleeping {}ms", attempt, MAX_RETRIES, sleepMs);
                Thread.sleep(sleepMs);
            }
        }
    }


    // ========================================
    // DATA PARSING & VALIDATION
    // ========================================

    private void updatePredictionsWithMarketData(Map<String, Prediction> tokenMap, 
                                                  Map<String, JSONObject> mdMap) {
        mdMap.forEach((token, item) -> {
            try {
                Prediction p = tokenMap.get(token);
                if (p == null) return;

                if (item.has("ltp") && !item.isNull("ltp")) {
                    p.setLtp(new BigDecimal(item.get("ltp").toString()));
                } else {
                    logger.warn("Missing ltp for token: {}", token);
                }

                if (item.has("close") && !item.isNull("close")) {
                    p.setPrevclose(new BigDecimal(item.get("close").toString()));
                } else {
                    logger.warn("Missing close for token: {}", token);
                }

            } catch (Exception ex) {
                logger.error("Parse error for token {}: {}", token, ex.getMessage(), ex);
            }
        });
    }

    private List<Prediction> filterValidStocks(List<Prediction> stocks) {
        return stocks.stream()
            .filter(this::isValidPrediction)
            .collect(Collectors.toList());
    }

    private boolean isValidPrediction(Prediction p) {
        return p.getPrevclose() != null 
            && p.getPrevclose().compareTo(ZERO) > 0
            && p.getLtp() != null 
            && p.getLtp().compareTo(ZERO) > 0
            && p.getWeight() != null
            && p.getWeight().compareTo(ZERO) > 0;
    }


    // ========================================
    // CALCULATION METHODS
    // ========================================

    private PredictionResult calculateBasicPrediction(PredictionData data) {
        if (data.validStocks.isEmpty()) {
            return new PredictionResult(
                data.currentNifty, data.currentNifty,
                ZERO, ZERO, 0, data.totalStocks
            );
        }

        ImpactMetrics metrics = calculateImpactMetrics(data.validStocks);
        BigDecimal normalizedImpact = normalizeImpact(metrics.totalImpact, metrics.totalWeightUsed);
        
        BigDecimal predicted = calculatePredictedPrice(data.currentNifty, normalizedImpact);
        BigDecimal diff = predicted.subtract(data.currentNifty);
        BigDecimal pctMove = calculatePercentageMove(diff, data.currentNifty);

        return new PredictionResult(
            data.currentNifty, predicted, diff, pctMove, 
            data.validStocks.size(), data.totalStocks
        );
    }

    private AdvancedPredictionResult calculateAdvancedPrediction(PredictionData data) {
        if (data.validStocks.isEmpty()) {
            return new AdvancedPredictionResult(
                data.currentNifty, data.currentNifty,
                ZERO, ZERO, 0, data.totalStocks,
                0.0, "NONE"
            );
        }

        AdvancedMetrics metrics = calculateAdvancedMetrics(data.validStocks);
        BigDecimal normalizedImpact = normalizeImpact(metrics.totalImpact, metrics.totalWeightUsed);
        
        BigDecimal predicted = calculatePredictedPrice(data.currentNifty, normalizedImpact);
        BigDecimal diff = predicted.subtract(data.currentNifty);
        BigDecimal pctMove = calculatePercentageMove(diff, data.currentNifty);

        double confidence = calculateConfidence(data.validStocks.size(), data.totalStocks, 
                                                metrics.positive, metrics.negative);
        String sentiment = determineSentiment(metrics.positive, metrics.negative);

        return new AdvancedPredictionResult(
            data.currentNifty, predicted, diff, pctMove,
            data.validStocks.size(), data.totalStocks,
            confidence, sentiment
        );
    }


    // ========================================
    // IMPACT CALCULATION
    // ========================================

    private ImpactMetrics calculateImpactMetrics(List<Prediction> validStocks) {
        BigDecimal totalImpact = ZERO;
        BigDecimal totalWeightUsed = ZERO;

        for (Prediction p : validStocks) {
            if (p.getPrevclose().compareTo(ZERO) == 0) {
                logger.warn("Zero prevclose for stock: {}", p.getName());
                continue;
            }

            BigDecimal pct = p.getLtp()
                .subtract(p.getPrevclose())
                .divide(p.getPrevclose(), SCALE, RoundingMode.HALF_UP);

            BigDecimal impact = pct.multiply(p.getWeight());
            totalImpact = totalImpact.add(impact);
            totalWeightUsed = totalWeightUsed.add(p.getWeight());
        }

        return new ImpactMetrics(totalImpact, totalWeightUsed);
    }

    private AdvancedMetrics calculateAdvancedMetrics(List<Prediction> validStocks) {
        BigDecimal totalImpact = ZERO;
        BigDecimal totalWeightUsed = ZERO;
        int positive = 0;
        int negative = 0;

        for (Prediction p : validStocks) {
            if (p.getPrevclose().compareTo(ZERO) == 0) {
                logger.warn("Zero prevclose for stock: {}", p.getName());
                continue;
            }

            BigDecimal pct = p.getLtp()
                .subtract(p.getPrevclose())
                .divide(p.getPrevclose(), SCALE, RoundingMode.HALF_UP);

            if (pct.compareTo(ZERO) > 0) positive++;
            else if (pct.compareTo(ZERO) < 0) negative++;

            BigDecimal impact = pct.multiply(p.getWeight());
            totalImpact = totalImpact.add(impact);
            totalWeightUsed = totalWeightUsed.add(p.getWeight());
        }

        return new AdvancedMetrics(totalImpact, totalWeightUsed, positive, negative);
    }

    private BigDecimal normalizeImpact(BigDecimal totalImpact, BigDecimal totalWeightUsed) {
        if (totalWeightUsed.compareTo(HUNDRED) != 0 && totalWeightUsed.compareTo(ZERO) > 0) {
            return totalImpact.multiply(HUNDRED)
                .divide(totalWeightUsed, SCALE, RoundingMode.HALF_UP);
        }
        return totalImpact;
    }

    private BigDecimal calculatePredictedPrice(BigDecimal currentPrice, BigDecimal totalImpact) {
        return currentPrice.multiply(
            ONE.add(totalImpact.divide(HUNDRED, SCALE, RoundingMode.HALF_UP))
        ).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculatePercentageMove(BigDecimal diff, BigDecimal currentPrice) {
        if (currentPrice.compareTo(ZERO) == 0) {
            return ZERO;
        }
        return diff.divide(currentPrice, PERCENTAGE_SCALE, RoundingMode.HALF_UP)
            .multiply(HUNDRED);
    }


    // ========================================
    // CONFIDENCE & SENTIMENT
    // ========================================

    private double calculateConfidence(int validCount, int totalCount, int positive, int negative) {
        if (totalCount == 0 || validCount == 0) return 0.0;
        
        double coverage = validCount * 1.0 / totalCount;
        double consistency = Math.abs((positive - negative) * 1.0 / validCount);
        
        return (coverage * COVERAGE_WEIGHT + consistency * CONSISTENCY_WEIGHT) * 100;
    }

    private String determineSentiment(int positive, int negative) {
        if (positive > negative) return "BULLISH";
        if (negative > positive) return "BEARISH";
        return "NEUTRAL";
    }


    // ========================================
    // INTERNAL DATA CLASSES
    // ========================================

    private static class PredictionData {
        final BigDecimal currentNifty;
        final List<Prediction> validStocks;
        final int totalStocks;

        PredictionData(BigDecimal currentNifty, List<Prediction> validStocks, int totalStocks) {
            this.currentNifty = currentNifty;
            this.validStocks = validStocks;
            this.totalStocks = totalStocks;
        }
    }

    private static class ImpactMetrics {
        final BigDecimal totalImpact;
        final BigDecimal totalWeightUsed;

        ImpactMetrics(BigDecimal totalImpact, BigDecimal totalWeightUsed) {
            this.totalImpact = totalImpact;
            this.totalWeightUsed = totalWeightUsed;
        }
    }

    private static class AdvancedMetrics extends ImpactMetrics {
        final int positive;
        final int negative;

        AdvancedMetrics(BigDecimal totalImpact, BigDecimal totalWeightUsed, int positive, int negative) {
            super(totalImpact, totalWeightUsed);
            this.positive = positive;
            this.negative = negative;
        }
    }


    // ========================================
    // PUBLIC RESULT CLASSES
    // ========================================

    public static class PredictionResult {
        public final BigDecimal currentPrice;
        public final BigDecimal predictedPrice;
        public final BigDecimal difference;
        public final BigDecimal percentageMove;
        public final int validStocks;
        public final int totalStocks;

        public PredictionResult(BigDecimal currentPrice, BigDecimal predictedPrice,
                                BigDecimal difference, BigDecimal percentageMove,
                                int validStocks, int totalStocks) {
            this.currentPrice = currentPrice;
            this.predictedPrice = predictedPrice;
            this.difference = difference;
            this.percentageMove = percentageMove;
            this.validStocks = validStocks;
            this.totalStocks = totalStocks;
        }
    }

    public static class AdvancedPredictionResult extends PredictionResult {

        public final double confidenceScore;
        public final String sentiment;

        private final List<PredictionHistory> predictionList = new ArrayList<>();

        public AdvancedPredictionResult(
                BigDecimal currentPrice,
                BigDecimal predictedPrice,
                BigDecimal difference,
                BigDecimal percentageMove,
                int validStocks,
                int totalStocks,
                double confidenceScore,
                String sentiment) {

            super(currentPrice, predictedPrice, difference, percentageMove, validStocks, totalStocks);
            this.confidenceScore = confidenceScore;
            this.sentiment = sentiment;
        }

        public List<PredictionHistory> getPredictionList() {
            return predictionList;
        }
    }

}