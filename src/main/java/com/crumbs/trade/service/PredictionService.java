package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.PredictionRepo;
import com.crumbs.trade.repo.StrategyRepo;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PredictionService {

    private static final Logger logger = LogManager.getLogger(PredictionService.class);

    private final PredictionRepo predictionRepo;

    @Autowired
    AngelOneService angelOneService;
    @Autowired
    AngelOne angelOne;
    @Autowired
    IndexesRepo indexesRepo;
    @Autowired
    StrategyRepo strategyRepo;

    private static final int BATCH_SIZE = 25;
    private static final int MAX_RETRIES = 4;
    private static final long BASE_BACKOFF_MS = 300;


    // ----------------------------------------------------
    //  SAFE MARKET DATA CALL WITH BACKOFF (PREVENT LIMIT)
    // ----------------------------------------------------
    private JSONObject callMarketDataWithRetry(SmartConnect smartconnect, JSONObject payload)
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
                    throw e;
                }

                long backoff = BASE_BACKOFF_MS * (1L << (attempt - 1)); // exponential
                long jitter = ThreadLocalRandom.current().nextLong(-backoff / 2, backoff / 2);
                long sleepMs = Math.max(200L, backoff + jitter);

                logger.warn("RATE LIMIT – retry {}/{}. Sleeping {}ms", attempt, MAX_RETRIES, sleepMs);
                Thread.sleep(sleepMs);
            }
        }
    }


    // ----------------------------------------------------
    //  BULK TOKEN FETCH (NO MORE 50 API CALLS)
    // ----------------------------------------------------
    private Map<String, JSONObject> fetchMarketDataForTokens(SmartConnect smartconnect, List<String> tokens) throws SmartAPIException {

        Map<String, JSONObject> result = new HashMap<>();

        for (int start = 0; start < tokens.size(); start += BATCH_SIZE) {

            int end = Math.min(tokens.size(), start + BATCH_SIZE);
            List<String> batch = tokens.subList(start, end);

            try {
                JSONObject payload = new JSONObject();
                payload.put("mode", "FULL");

                JSONObject exchangeTokens = new JSONObject();
                JSONArray arr = new JSONArray();

                batch.forEach(arr::put);
                exchangeTokens.put("NSE", arr);
                payload.put("exchangeTokens", exchangeTokens);

                JSONObject response = callMarketDataWithRetry(smartconnect, payload);

                if (response.has("fetched")) {
                    JSONArray fetched = response.getJSONArray("fetched");

                    for (int i = 0; i < fetched.length(); i++) {
                        JSONObject item = fetched.getJSONObject(i);

                        // ---- CRITICAL FIX ----
                        String symbolToken = item.get("symbolToken").toString();

                        result.put(symbolToken, item);
                    }
                }

                Thread.sleep(150); // polite pause

            } catch (Exception ex) {
                logger.error("Error fetching token batch: {}", ex.getMessage());
            }
        }

        return result;
    }


    // ----------------------------------------------------
    //  MAIN PREDICT NIFTY
    // ----------------------------------------------------
    public PredictionResult predictNifty() throws SmartAPIException {

        SmartConnect smartconnect = angelOne.signIn();
        Strategy strategy = strategyRepo.findByName("NIFTY_OI");

        BigDecimal currentNifty = angelOneService.getcurrentPrice(
                smartconnect,
                strategy.getExchange(),
                strategy.getTradingsymbol(),
                strategy.getToken(),
                "ltp"
        );

        List<Prediction> stocks = predictionRepo.findAll();

        // ----- MAP TOKENS -----
        Map<String, Prediction> tokenMap = new HashMap<>();
        List<String> tokens = new ArrayList<>();

        for (Prediction p : stocks) {
            Indexes idx = indexesRepo.findByNameAndExchange(p.getName(), p.getExchange());
            if (idx == null) continue;

            tokens.add(idx.getToken());
            tokenMap.put(idx.getToken(), p);
        }

        // ----- BULK FETCH -----
        Map<String, JSONObject> mdMap = fetchMarketDataForTokens(smartconnect, tokens);

        mdMap.forEach((token, item) -> {
            try {
                Prediction p = tokenMap.get(token);
                if (p == null) return;

                p.setLtp(new BigDecimal(item.get("ltp").toString()));
                p.setPrevclose(new BigDecimal(item.get("close").toString()));

            } catch (Exception ex) {
                logger.error("Parse error token {} -> {}", token, ex.getMessage());
            }
        });

        // ---- FILTER VALID STOCKS ----
        List<Prediction> valid = stocks.stream()
                .filter(p -> p.getPrevclose() != null &&
                             p.getPrevclose().compareTo(BigDecimal.ZERO) > 0 &&
                             p.getLtp() != null &&
                             p.getLtp().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());

        if (valid.isEmpty()) {
            return new PredictionResult(currentNifty, currentNifty,
                    BigDecimal.ZERO, BigDecimal.ZERO, 0, stocks.size());
        }

        // ---- CALCULATE IMPACT ----
        BigDecimal totalImpact = BigDecimal.ZERO;
        BigDecimal totalWeightUsed = BigDecimal.ZERO;

        for (Prediction p : valid) {

            BigDecimal pct = p.getLtp()
                    .subtract(p.getPrevclose())
                    .divide(p.getPrevclose(), 10, RoundingMode.HALF_UP);

            BigDecimal impact = pct.multiply(p.getWeight());

            totalImpact = totalImpact.add(impact);
            totalWeightUsed = totalWeightUsed.add(p.getWeight());
        }

        // ---- NORMALIZE IF NOT 100 ----
        if (totalWeightUsed.compareTo(new BigDecimal("100")) != 0) {
            totalImpact = totalImpact.multiply(new BigDecimal("100"))
                    .divide(totalWeightUsed, 10, RoundingMode.HALF_UP);
        }

        // ---- PREDICT INDEX ----
        BigDecimal predicted = currentNifty.multiply(
                BigDecimal.ONE.add(totalImpact.divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP))
        ).setScale(2, RoundingMode.HALF_UP);

        BigDecimal diff = predicted.subtract(currentNifty);
        BigDecimal pctMove = diff.divide(currentNifty, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        return new PredictionResult(currentNifty, predicted, diff, pctMove, valid.size(), stocks.size());
    }


    // ----------------------------------------------------
    //  RESULT CLASS
    // ----------------------------------------------------
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
    public AdvancedPredictionResult predictNiftyAdvanced() throws SmartAPIException {

        SmartConnect smartconnect = angelOne.signIn();
        Strategy strategy = strategyRepo.findByName("NIFTY_OI");

        BigDecimal currentNifty = angelOneService.getcurrentPrice(
                smartconnect,
                strategy.getExchange(),
                strategy.getTradingsymbol(),
                strategy.getToken(),
                "ltp"
        );

        List<Prediction> stocks = predictionRepo.findAll();

        // ---------- TOKEN MAP ----------
        Map<String, Prediction> tokenMap = new HashMap<>();
        List<String> tokens = new ArrayList<>();

        for (Prediction p : stocks) {

            Indexes idx = indexesRepo.findByNameAndExchange(p.getName(), p.getExchange());

            if (idx == null) {
            	logger.error("❌ MISSING INDEX ENTRY → name={} exchange={}", 
                        p.getName(), p.getExchange());
                continue;
            }

            if (idx.getToken() == null || idx.getToken().isBlank()) {
            	logger.error("❌ MISSING TOKEN in Indexes table → name={} exchange={} indexRow={}", 
                        p.getName(), p.getExchange(), idx);
                continue;
            }

            tokens.add(idx.getToken());
            tokenMap.put(idx.getToken(), p);

           
        }
        logger.info("✔ Token mapped length → {}",tokens.size());
        // ---------- BULK FETCH ----------
        Map<String, JSONObject> mdMap = fetchMarketDataForTokens(smartconnect, tokens);

        mdMap.forEach((token, item) -> {
            try {
                Prediction p = tokenMap.get(token);
                if (p == null) return;

                p.setLtp(new BigDecimal(item.get("ltp").toString()));
                p.setPrevclose(new BigDecimal(item.get("close").toString()));

            } catch (Exception ex) {
                logger.error("Parse error token {} -> {}", token, ex.getMessage());
            }
        });

        // ---------- FILTER VALID ----------
        List<Prediction> valid = stocks.stream()
                .filter(p -> p.getPrevclose() != null &&
                        p.getPrevclose().compareTo(BigDecimal.ZERO) > 0 &&
                        p.getLtp() != null &&
                        p.getLtp().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());

        if (valid.isEmpty()) {
            return new AdvancedPredictionResult(
                    currentNifty, currentNifty,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    0, stocks.size(),
                    0.0, "NONE"
            );
        }

        // ---------- METRICS ----------
        BigDecimal totalImpact = BigDecimal.ZERO;
        BigDecimal totalWeightUsed = BigDecimal.ZERO;
        int positive = 0;
        int negative = 0;
        BigDecimal variance = BigDecimal.ZERO;

        for (Prediction p : valid) {

            BigDecimal pct = p.getLtp()
                    .subtract(p.getPrevclose())
                    .divide(p.getPrevclose(), 10, RoundingMode.HALF_UP);

            if (pct.compareTo(BigDecimal.ZERO) > 0) positive++;
            else if (pct.compareTo(BigDecimal.ZERO) < 0) negative++;

            BigDecimal impact = pct.multiply(p.getWeight());
            totalImpact = totalImpact.add(impact);
            totalWeightUsed = totalWeightUsed.add(p.getWeight());

            variance = variance.add(pct.multiply(pct).multiply(p.getWeight()));
        }

        // Normalize missing weight
        if (totalWeightUsed.compareTo(new BigDecimal("100")) != 0) {
            totalImpact = totalImpact.multiply(new BigDecimal("100"))
                    .divide(totalWeightUsed, 10, RoundingMode.HALF_UP);
        }

        // ---------- FINAL OUTPUT ----------
        BigDecimal predicted = currentNifty.multiply(
                BigDecimal.ONE.add(totalImpact.divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP))
        ).setScale(2, RoundingMode.HALF_UP);

        BigDecimal diff = predicted.subtract(currentNifty);
        BigDecimal pctMove = diff.divide(currentNifty, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        // CONFIDENCE SCORE
        double coverage = valid.size() * 1.0 / stocks.size();
        double consistency = Math.abs((positive - negative) * 1.0 / valid.size());
        double confidence = (coverage * 0.6 + consistency * 0.4) * 100;

        String sentiment =
                positive > negative ? "BULLISH" :
                negative > positive ? "BEARISH" : "NEUTRAL";

        return new AdvancedPredictionResult(
                currentNifty,
                predicted,
                diff,
                pctMove,
                valid.size(),
                stocks.size(),
                confidence,
                sentiment
        );
    }
 // ----------------------------------------------------
 // ADVANCED PREDICTION RESULT
 // ----------------------------------------------------
 public static class AdvancedPredictionResult extends PredictionResult {

     public final double confidenceScore;
     public final String sentiment;

     public AdvancedPredictionResult(
             BigDecimal currentPrice,
             BigDecimal predictedPrice,
             BigDecimal difference,
             BigDecimal percentageMove,
             int validStocks,
             int totalStocks,
             double confidenceScore,
             String sentiment
     ) {
         super(currentPrice, predictedPrice, difference, percentageMove, validStocks, totalStocks);
         this.confidenceScore = confidenceScore;
         this.sentiment = sentiment;
     }
 }

    
}
