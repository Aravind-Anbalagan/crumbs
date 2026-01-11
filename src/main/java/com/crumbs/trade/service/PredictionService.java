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
import com.crumbs.trade.controller.PredictionController.AdvancedPredictionResponse;
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
    
    @Autowired 
    PredictionHistoryRepo predictionHistoryRepo;


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
        List<PredictionHistory> history = fetchPredictionHistoryByDays(days);

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
                0.0, "NONE", null
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
        
        // NEW: Calculate sector impacts
        Map<String, SectorImpact> sectorImpacts = calculateSectorImpacts(data.validStocks);

        return new AdvancedPredictionResult(
            data.currentNifty, predicted, diff, pctMove,
            data.validStocks.size(), data.totalStocks,
            confidence, sentiment, sectorImpacts
        );
    }


    // ========================================
    // SECTOR ANALYSIS (NEW FEATURE)
    // ========================================
    
    /**
     * Calculate sector-wise contribution to Nifty movement
     */
    private Map<String, SectorImpact> calculateSectorImpacts(List<Prediction> validStocks) {
        Map<String, SectorImpact> sectorMap = new HashMap<>();
        
        for (Prediction p : validStocks) {
            String sector = p.getSector() != null ? p.getSector() : "UNKNOWN";
            
            if (p.getPrevclose().compareTo(ZERO) == 0) {
                continue;
            }
            
            // Calculate individual stock's percentage change
            BigDecimal pctChange = p.getLtp()
                .subtract(p.getPrevclose())
                .divide(p.getPrevclose(), SCALE, RoundingMode.HALF_UP)
                .multiply(HUNDRED);
            
            // Calculate impact (weighted percentage change)
            BigDecimal impact = pctChange.multiply(p.getWeight())
                .divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
            
            // Update or create sector impact
            sectorMap.compute(sector, (key, existing) -> {
                if (existing == null) {
                    return new SectorImpact(sector, impact, p.getWeight(), 1, 
                        pctChange.compareTo(ZERO) > 0 ? 1 : 0,
                        pctChange.compareTo(ZERO) < 0 ? 1 : 0);
                } else {
                    return new SectorImpact(
                        sector,
                        existing.totalImpact.add(impact),
                        existing.totalWeight.add(p.getWeight()),
                        existing.stockCount + 1,
                        existing.positiveCount + (pctChange.compareTo(ZERO) > 0 ? 1 : 0),
                        existing.negativeCount + (pctChange.compareTo(ZERO) < 0 ? 1 : 0)
                    );
                }
            });
        }
        
        logger.info("📊 Sector analysis: {} sectors analyzed", sectorMap.size());
        
        return sectorMap;
    }


    // ========================================
    // CROSSOVER DETECTION (NEW FEATURE)
    // ========================================

    /**
     * Detect crossover points from historical data
     */
    public List<CrossoverPoint> detectCrossovers(String days) {
        logger.info("🔍 Detecting crossover points");
        
        List<PredictionHistory> history = fetchPredictionHistoryByDays(days);
        
        if (history.size() < 2) {
            logger.warn("⚠️ Need at least 2 data points to detect crossovers");
            return Collections.emptyList();
        }
        
        // Sort by timestamp ascending
        history.sort(Comparator.comparing(PredictionHistory::getTimestamp));
        
        List<CrossoverPoint> crossovers = new ArrayList<>();
        
        for (int i = 1; i < history.size(); i++) {
            PredictionHistory prev = history.get(i - 1);
            PredictionHistory curr = history.get(i);
            
            CrossoverPoint crossover = detectCrossoverBetween(prev, curr);
            if (crossover != null) {
                crossovers.add(crossover);
                logger.info("✓ Crossover: {} at {}", crossover.type, crossover.timestamp);
            }
        }
        
        logger.info("📊 Found {} crossover events", crossovers.size());
        return crossovers;
    }

    /**
     * Detect if crossover happened between two consecutive points
     */
    private CrossoverPoint detectCrossoverBetween(PredictionHistory prev, PredictionHistory curr) {
        
        BigDecimal prevDiff = prev.getPredictedPrice().subtract(prev.getCurrentPrice());
        BigDecimal currDiff = curr.getPredictedPrice().subtract(curr.getCurrentPrice());
        
        // Check if sign changed
        if (prevDiff.compareTo(ZERO) > 0 && currDiff.compareTo(ZERO) <= 0) {
            // BEARISH CROSSOVER
            return buildCrossoverPoint(
                CrossoverType.BEARISH_CROSSOVER,
                prev, curr,
                "Predicted crossed BELOW current"
            );
            
        } else if (prevDiff.compareTo(ZERO) < 0 && currDiff.compareTo(ZERO) >= 0) {
            // BULLISH CROSSOVER
            return buildCrossoverPoint(
                CrossoverType.BULLISH_CROSSOVER,
                prev, curr,
                "Predicted crossed ABOVE current"
            );
        }
        
        return null;
    }

    /**
     * Build crossover point with details
     */
    private CrossoverPoint buildCrossoverPoint(
            CrossoverType type,
            PredictionHistory prev,
            PredictionHistory curr,
            String description) {
        
        BigDecimal crossoverPrice = estimateCrossoverPrice(
            prev.getCurrentPrice(),
            prev.getPredictedPrice(),
            curr.getCurrentPrice(),
            curr.getPredictedPrice()
        );
        
        LocalDateTime crossoverTime = estimateCrossoverTime(
            prev.getTimestamp(),
            curr.getTimestamp(),
            prev.getCurrentPrice(),
            prev.getPredictedPrice(),
            curr.getCurrentPrice(),
            curr.getPredictedPrice()
        );
        
        return new CrossoverPoint(
            type,
            crossoverTime,
            crossoverPrice,
            prev.getTimestamp(),
            curr.getTimestamp(),
            prev.getCurrentPrice(),
            prev.getPredictedPrice(),
            curr.getCurrentPrice(),
            curr.getPredictedPrice(),
            prev.getSentiment(),
            curr.getSentiment(),
            curr.getConfidenceScore(),
            description
        );
    }

    /**
     * Estimate crossover price using linear interpolation
     */
    private BigDecimal estimateCrossoverPrice(
            BigDecimal prevCurrent,
            BigDecimal prevPredicted,
            BigDecimal currCurrent,
            BigDecimal currPredicted) {
        
        try {
            BigDecimal prevDiff = prevPredicted.subtract(prevCurrent);
            BigDecimal currDiff = currPredicted.subtract(currCurrent);
            BigDecimal totalChange = currDiff.subtract(prevDiff);
            
            if (totalChange.compareTo(ZERO) == 0) {
                return prevCurrent.add(currCurrent).divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
            }
            
            BigDecimal ratio = prevDiff.negate().divide(totalChange, 4, RoundingMode.HALF_UP);
            
            if (ratio.compareTo(ZERO) < 0) ratio = ZERO;
            if (ratio.compareTo(ONE) > 0) ratio = ONE;
            
            BigDecimal priceChange = currCurrent.subtract(prevCurrent);
            return prevCurrent.add(priceChange.multiply(ratio)).setScale(2, RoundingMode.HALF_UP);
            
        } catch (Exception e) {
            return prevCurrent.add(currCurrent).divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
        }
    }

    /**
     * Estimate crossover time using linear interpolation
     */
    private LocalDateTime estimateCrossoverTime(
            LocalDateTime prevTime,
            LocalDateTime currTime,
            BigDecimal prevCurrent,
            BigDecimal prevPredicted,
            BigDecimal currCurrent,
            BigDecimal currPredicted) {
        
        try {
            BigDecimal prevDiff = prevPredicted.subtract(prevCurrent);
            BigDecimal currDiff = currPredicted.subtract(currCurrent);
            BigDecimal totalChange = currDiff.subtract(prevDiff);
            
            if (totalChange.compareTo(ZERO) == 0) {
                long seconds = java.time.Duration.between(prevTime, currTime).getSeconds();
                return prevTime.plusSeconds(seconds / 2);
            }
            
            BigDecimal ratio = prevDiff.negate().divide(totalChange, 4, RoundingMode.HALF_UP);
            
            if (ratio.compareTo(ZERO) < 0) ratio = ZERO;
            if (ratio.compareTo(ONE) > 0) ratio = ONE;
            
            long secondsBetween = java.time.Duration.between(prevTime, currTime).getSeconds();
            long crossoverSeconds = (long) (secondsBetween * ratio.doubleValue());
            
            return prevTime.plusSeconds(crossoverSeconds);
            
        } catch (Exception e) {
            return currTime;
        }
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
        public final Map<String, SectorImpact> sectorImpacts;  // NEW

        private final List<PredictionHistory> predictionList = new ArrayList<>();

        public AdvancedPredictionResult(
                BigDecimal currentPrice,
                BigDecimal predictedPrice,
                BigDecimal difference,
                BigDecimal percentageMove,
                int validStocks,
                int totalStocks,
                double confidenceScore,
                String sentiment,
                Map<String, SectorImpact> sectorImpacts) {

            super(currentPrice, predictedPrice, difference, percentageMove, validStocks, totalStocks);
            this.confidenceScore = confidenceScore;
            this.sentiment = sentiment;
            this.sectorImpacts = sectorImpacts;
        }

        public List<PredictionHistory> getPredictionList() {
            return predictionList;
        }
    }
    
    
    // ========================================
    // NEW DATA CLASSES (SECTOR & CROSSOVER)
    // ========================================
    
    /**
     * Sector-wise impact analysis
     */
    public static class SectorImpact {
        public final String sectorName;
        public final BigDecimal totalImpact;
        public final BigDecimal totalWeight;
        public final int stockCount;
        public final int positiveCount;
        public final int negativeCount;
        
        public SectorImpact(String sectorName, BigDecimal totalImpact, 
                           BigDecimal totalWeight, int stockCount,
                           int positiveCount, int negativeCount) {
            this.sectorName = sectorName;
            this.totalImpact = totalImpact;
            this.totalWeight = totalWeight;
            this.stockCount = stockCount;
            this.positiveCount = positiveCount;
            this.negativeCount = negativeCount;
        }
        
        public String getSentiment() {
            if (positiveCount > negativeCount) return "BULLISH";
            if (negativeCount > positiveCount) return "BEARISH";
            return "NEUTRAL";
        }
        
        public BigDecimal getAverageImpact() {
            return stockCount > 0 
                ? totalImpact.divide(new BigDecimal(stockCount), SCALE, RoundingMode.HALF_UP)
                : ZERO;
        }
    }

    /**
     * Crossover event types
     */
    public enum CrossoverType {
        BULLISH_CROSSOVER,   // Predicted crossed ABOVE current
        BEARISH_CROSSOVER    // Predicted crossed BELOW current
    }

    /**
     * Crossover point in time
     */
    public static class CrossoverPoint {
        public final CrossoverType type;
        public final LocalDateTime timestamp;
        public final BigDecimal crossoverPrice;
        
        public final LocalDateTime beforeTime;
        public final LocalDateTime afterTime;
        public final BigDecimal beforeCurrent;
        public final BigDecimal beforePredicted;
        public final BigDecimal afterCurrent;
        public final BigDecimal afterPredicted;
        
        public final String sentimentBefore;
        public final String sentimentAfter;
        public final BigDecimal confidence;
        public final String description;
        
        public CrossoverPoint(
                CrossoverType type,
                LocalDateTime timestamp,
                BigDecimal crossoverPrice,
                LocalDateTime beforeTime,
                LocalDateTime afterTime,
                BigDecimal beforeCurrent,
                BigDecimal beforePredicted,
                BigDecimal afterCurrent,
                BigDecimal afterPredicted,
                String sentimentBefore,
                String sentimentAfter,
                BigDecimal confidence,
                String description) {
            
            this.type = type;
            this.timestamp = timestamp;
            this.crossoverPrice = crossoverPrice;
            this.beforeTime = beforeTime;
            this.afterTime = afterTime;
            this.beforeCurrent = beforeCurrent;
            this.beforePredicted = beforePredicted;
            this.afterCurrent = afterCurrent;
            this.afterPredicted = afterPredicted;
            this.sentimentBefore = sentimentBefore;
            this.sentimentAfter = sentimentAfter;
            this.confidence = confidence;
            this.description = description;
        }
        
        public String getSignal() {
            return type == CrossoverType.BULLISH_CROSSOVER ? "BUY" : "SELL";
        }
        
        public String getEmoji() {
            return type == CrossoverType.BULLISH_CROSSOVER ? "🟢" : "🔴";
        }
    }


    // ========================================
    // FETCH FROM DATABASE (UPDATED WITH NEW FEATURES)
    // ========================================
    
    /**
     * This method fetches predictions from database without any calculation
     * Used by: UI fetch API
     * UPDATED: Now includes sector impacts and crossover points
     */
    public AdvancedPredictionResponse fetchPredictionsFromDB(String days) {
        
        logger.info("📊 Fetching predictions from database with days filter: {}", days != null ? days : "today");
        
        try {
            // Get the latest prediction from database
            PredictionHistory latest = predictionHistoryRepo.findFirstByOrderByTimestampDesc();
            
            if (latest == null) {
                logger.warn("⚠️ No predictions found in database");
                return buildEmptyResponse();
            }

            // Fetch historical predictions based on filter
            List<PredictionHistory> historicalList = fetchPredictionHistoryByDays(days);

            // Build response
            AdvancedPredictionResponse response = new AdvancedPredictionResponse();
            
            // Set latest prediction data (root level)
            response.setCurrentPrice(latest.getCurrentPrice());
            response.setPredictedPrice(latest.getPredictedPrice());
            response.setDifference(latest.getDifference());
            response.setPercentageMove(latest.getPercentageMove());
            response.setValidStocks(latest.getValidStocks());
            response.setTotalStocks(latest.getTotalStocks());
            response.setConfidenceScore(latest.getConfidenceScore().doubleValue());
            response.setSentiment(latest.getSentiment());
            response.setTimestamp(new Date());
            
            // Set historical predictions list
            response.setPredictionList(historicalList);
            
            // Generate interpretation
            response.setInterpretation(generateInterpretationFromHistory(latest));
            
            // NEW: Add crossover detection
            response.setCrossoverPoints(detectCrossovers(days));
            
            logger.info("✅ Fetched {} historical predictions, latest from: {}", 
                    historicalList.size(), latest.getTimestamp());
            
            return response;
            
        } catch (Exception e) {
            logger.error("❌ Error fetching predictions from database: {}", e.getMessage(), e);
            return buildEmptyResponse();
        }
    }

    /**
     * Helper method to build empty response when no data is available
     */
    private AdvancedPredictionResponse buildEmptyResponse() {
        AdvancedPredictionResponse response = new AdvancedPredictionResponse();
        response.setCurrentPrice(BigDecimal.ZERO);
        response.setPredictedPrice(BigDecimal.ZERO);
        response.setDifference(BigDecimal.ZERO);
        response.setPercentageMove(BigDecimal.ZERO);
        response.setValidStocks(0);
        response.setTotalStocks(50);
        response.setConfidenceScore(0.0);
        response.setSentiment("NONE");
        response.setTimestamp(new Date());
        response.setPredictionList(new ArrayList<>());
        response.setInterpretation("No predictions available. Please wait for market hours or check if scheduler is running.");
        response.setCrossoverPoints(new ArrayList<>());  // NEW
        return response;
    }

    /**
     * Helper method to generate interpretation from PredictionHistory entity
     */
    private String generateInterpretationFromHistory(PredictionHistory hist) {
        StringBuilder sb = new StringBuilder();
        
        // Coverage
        double coverage = (double) hist.getValidStocks() / hist.getTotalStocks() * 100;
        sb.append(String.format("Analysis based on %d out of %d Nifty 50 stocks (%.1f%% coverage). ",
            hist.getValidStocks(), hist.getTotalStocks(), coverage));
        
        // Movement
        if (hist.getDifference().abs().doubleValue() < 10) {
            sb.append("Market showing minimal movement. ");
        } else if (hist.getDifference().doubleValue() > 0) {
            sb.append(String.format("Predicted upward movement of %.2f points. ", 
                hist.getDifference().doubleValue()));
        } else {
            sb.append(String.format("Predicted downward movement of %.2f points. ", 
                Math.abs(hist.getDifference().doubleValue())));
        }
        
        // Confidence
        double confidence = hist.getConfidenceScore().doubleValue();
        if (confidence >= 75) {
            sb.append("High confidence prediction. ");
        } else if (confidence >= 50) {
            sb.append("Moderate confidence prediction. ");
        } else {
            sb.append("Low confidence - mixed signals from constituent stocks. ");
        }
        
        // Sentiment
        sb.append(String.format("Overall market sentiment: %s.", hist.getSentiment()));
        
        return sb.toString();
    }
}