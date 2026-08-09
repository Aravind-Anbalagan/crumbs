package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.Nifty;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.NiftyRepo;
import com.crumbs.trade.utility.NSEWorkingDays;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FnoScannerService {

    private static final Logger logger = LogManager.getLogger(FnoScannerService.class);

    // ──────────────────────────────────────────────────────────
    //  ⚙️ CONFIGURABLE THRESHOLDS & ALERTS (TOP OF CLASS)
    // ──────────────────────────────────────────────────────────
    private static final BigDecimal THRESHOLD_PERCENTAGE = new BigDecimal("5.0"); // Alert trigger threshold (+/- 5%)
    private static final boolean ENABLE_TELEGRAM_ALERTS = true;                  // Enable/Disable Telegram notifications

    // API & Batch Settings
    private static final int BATCH_SIZE = 50;
    private static final String EXCHANGE = "NSE";
    private static final int BATCH_FETCH_DELAY_MS = 250;
    private static final int RATE_LIMIT_SLEEP_MS = 6000;

    private final NiftyRepo niftyRepo;
    private final IndexesRepo indexesRepo;
    private final PredictionService predictionService;
    private final AngelOne angelOne;
    private final AngelWebSocketService angelWebSocketService;
    private final TelegramService telegramService; // Injected Telegram Service

    // ──────────────────────────────────────────────────────────
    //  STEP 1: Pre-Cache for F&O Previous Close (Runs at 8:30 AM)
    // ──────────────────────────────────────────────────────────
    @Transactional
    public void precacheFnoPreviousClose() {
        logger.info("🌅 Starting Multi-Threaded Pre-Cache for F&O Previous Close...");

        List<Nifty> fnoStocks = niftyRepo.findAll();
        if (fnoStocks.isEmpty()) {
            logger.warn("⚠️ No active F&O stocks found in FO_STOCKS table. Skipping prevClose cache.");
            return;
        }

        SmartConnect smartConnect = angelOne.signIn();
        if (smartConnect == null) {
            logger.error("🛑 Failed to sign in to broker API.");
            return;
        }

        // Build tokens list AND auto-fill missing tokens using existing IndexesRepo methods
        List<String> tokens = new ArrayList<>();
        for (Nifty stock : fnoStocks) {
            String token = stock.getToken();

            if (token == null || token.trim().isEmpty()) {
                token = lookupTokenFromIndexes(stock.getName());
                if (token != null) {
                    stock.setToken(token); // Saved permanently to fo_stocks via saveAll() below
                    logger.info("🔄 Auto-resolved missing token for {}: {}", stock.getName(), token);
                } else {
                    logger.warn("⚠️ Could not resolve token for {} in Indexes table. Skipping.", stock.getName());
                }
            }

            if (token != null && !token.trim().isEmpty()) {
                tokens.add(token);
            }
        }

        if (tokens.isEmpty()) {
            logger.warn("⚠️ No valid tokens found to fetch market data. Aborting.");
            return;
        }

        AtomicInteger updatedCount = new AtomicInteger(0);

        // Calculate last working day via NSEWorkingDays utility
        final LocalDate prevTradingDay = NSEWorkingDays.getLastWorkingDay(LocalDate.now());
        logger.info("📅 Determined Previous Trading Date as: {}", prevTradingDay);

        // Controlled concurrency (3 threads max to prevent 503 rate limits)
        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < tokens.size(); i += BATCH_SIZE) {
            int endIndex = Math.min(i + BATCH_SIZE, tokens.size());
            List<String> batchTokens = tokens.subList(i, endIndex);

            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    JSONObject payload = predictionService.buildMarketDataPayload(batchTokens, EXCHANGE);
                    JSONObject response = predictionService.callMarketDataWithRetry(smartConnect, payload);

                    if (response != null) {
                        JSONArray fetched = extractFetchedArray(response);
                        if (fetched != null) {
                            for (int j = 0; j < fetched.length(); j++) {
                                JSONObject obj = fetched.getJSONObject(j);
                                String responseToken = obj.getString("symbolToken");

                                if (obj.has("close") && !obj.isNull("close")) {
                                    BigDecimal prevClose = new BigDecimal(obj.get("close").toString());

                                    fnoStocks.stream()
                                            .filter(stock -> responseToken.equals(stock.getToken()))
                                            .findFirst()
                                            .ifPresent(stock -> {
                                                stock.setPrevClose(prevClose);
                                                stock.setPrevCloseDate(prevTradingDay);
                                                updatedCount.incrementAndGet();
                                            });
                                }
                            }
                        }
                    }

                    sleepQuietly(BATCH_FETCH_DELAY_MS);

                } catch (Exception e) {
                    boolean isRateLimit = e.getMessage() != null &&
                            (e.getMessage().contains("503") || e.getMessage().contains("Too Many Requests"));

                    if (isRateLimit) {
                        logger.warn("⚠️ Rate limit hit on worker thread. Forcing backoff...");
                        sleepQuietly(RATE_LIMIT_SLEEP_MS);
                    } else {
                        logger.error("Error fetching batch for prev close: {}", e.getMessage());
                    }
                } catch (SmartAPIException e) {
                    throw new RuntimeException(e);
                }
            }, executor));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.shutdown();
        }

        // 1. Persist updated tokens and prevClose values into fo_stocks
        niftyRepo.saveAll(fnoStocks);
        logger.info("✅ Pre-Cache Complete! Saved Previous Close for {}/{} F&O stocks.",
                updatedCount.get(), fnoStocks.size());

        // 2. Automatically bulk subscribe all resolved tokens so WebSocket is active before market open (9:15 AM)
        angelWebSocketService.subscribeAllFnoStocks();
    }

    // ──────────────────────────────────────────────────────────
    //  STEP 2: 30-Min Percentage Change Calculation & Alerting
    // ──────────────────────────────────────────────────────────
    @Transactional
    public void calculateFnoPercentageChange() {
        if (!NSEWorkingDays.isNSEWorkingDay(LocalDate.now())) {
            logger.info("Market holiday or weekend. Skipping percentage calculation.");
            return;
        }

        logger.info("⏱️ Running 30-min F&O Percentage Change Calculation...");

        List<Nifty> stocks = niftyRepo.findAll();
        if (stocks.isEmpty()) {
            logger.warn("⚠️ No stocks found in FO_STOCKS table.");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int updateCount = 0;

        // Container to store stocks exceeding the +/- 5% threshold for Telegram alert
        List<String[]> highMoverRows = new ArrayList<>();
        // Header row aligned with TelegramService.sendStockAlert column widths {12, 8, 8, 8, 6}
        highMoverRows.add(new String[]{"Stock", "LTP", "PrevClose", "Change%", "Move"});

        for (Nifty stock : stocks) {
            String token = stock.getToken();
            BigDecimal prevClose = stock.getPrevClose();

            // Safety check: Skip if token is missing or previous close is zero/null
            if (token == null || token.trim().isEmpty() || prevClose == null || prevClose.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            // Fetch live price from WebSocket memory store (Zero REST API overhead)
            BigDecimal ltp = angelWebSocketService.getLatestLTP(ExchangeType.NSE_CM, token);

            // On-the-Fly Self Healing: If LTP is missing/zero, trigger an immediate subscription!
            if (ltp == null || ltp.compareTo(BigDecimal.ZERO) == 0) {
                logger.warn("⚠️ No live LTP for {} (Token: {}). Triggering dynamic subscription...", stock.getName(), token);
                angelWebSocketService.subscribe(ExchangeType.NSE_CM, token);
                continue; // Skip calculation for this tick; will be populated on next run
            }

            // Formula: ((LTP - PrevClose) / PrevClose) * 100
            BigDecimal difference = ltp.subtract(prevClose);
            BigDecimal percentage = difference
                    .divide(prevClose, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP);

            stock.setPercentageChange(percentage);
            stock.setPercentageUpdatedTime(now);
            updateCount++;

            // 🎯 Check if stock gain/drop exceeds threshold (|percentage| >= THRESHOLD_PERCENTAGE)
            if (percentage.abs().compareTo(THRESHOLD_PERCENTAGE) >= 0) {
                String moveType = percentage.compareTo(BigDecimal.ZERO) >= 0 ? "GAIN" : "DROP";
                String changeStr = (percentage.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "") + percentage + "%";

                highMoverRows.add(new String[]{
                        stock.getName(),
                        ltp.toString(),
                        prevClose.toString(),
                        changeStr,
                        moveType
                });
            }
        }

        if (updateCount > 0) {
            niftyRepo.saveAll(stocks);
            logger.info("✅ Percentage Change updated for {}/{} F&O stocks.", updateCount, stocks.size());

            // 📢 Send Telegram Alert if enabled and high movers are detected
            if (ENABLE_TELEGRAM_ALERTS && highMoverRows.size() > 1) { // > 1 because row 0 is header
                logger.info("📢 Found {} stocks moving > {}%. Sending Telegram alert...",
                        highMoverRows.size() - 1, THRESHOLD_PERCENTAGE);
                telegramService.sendStockAlert(highMoverRows);
            }
        } else {
            logger.warn("⚠️ No stocks updated. Check if market is active and subscriptions are receiving ticks.");
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────

    private String lookupTokenFromIndexes(String name) {
        Indexes eqStock = indexesRepo.findByNameAndExchange(name, EXCHANGE);
        if (eqStock != null && eqStock.getToken() != null) {
            return eqStock.getToken();
        }

        Indexes symbolMatch = indexesRepo.findByNameAndSymbol(name, name + "-EQ");
        if (symbolMatch != null && symbolMatch.getToken() != null) {
            return symbolMatch.getToken();
        }

        List<Indexes> nameMatches = indexesRepo.findByName(name);
        if (nameMatches != null) {
            for (Indexes idx : nameMatches) {
                if (EXCHANGE.equalsIgnoreCase(idx.getExchange()) &&
                        (idx.getExpiry() == null || idx.getExpiry().trim().isEmpty())) {
                    return idx.getToken();
                }
            }
        }

        return null;
    }

    private JSONArray extractFetchedArray(JSONObject response) {
        try {
            if (response.has("data") && !response.isNull("data")) {
                JSONObject data = response.getJSONObject("data");
                if (data.has("fetched")) return data.getJSONArray("fetched");
            }
            if (response.has("fetched")) return response.getJSONArray("fetched");
        } catch (JSONException e) {
            logger.warn("Could not parse fetched array from Market Data response: {}", e.getMessage());
        }
        return null;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}