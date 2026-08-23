package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private final TokenService tokenService; // 🆕 ATM CE/PE token resolution + strategy signals (fail-safe, never throws)

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

        // 🧹 Reset yesterday's TRANSACTIONAL data only (range-tracking fields).
        // Master data (id, name, token, is_active) is untouched. prevClose/
        // prevCloseDate are about to be overwritten below anyway, so no need
        // to null them separately — only the range-bucket fields need an
        // explicit reset, otherwise today's first breakout would inherit
        // yesterday's rangeEnteredAt and show a bogus multi-hour interval.
        for (Nifty stock : fnoStocks) {
            stock.setCurrentRangeBucket(null);
            stock.setRangeDirection(null);
            stock.setRangeEnteredAt(null);
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

        // Calculate last working day safely without modifying the shared utility
        final LocalDate prevTradingDay = getStrictPreviousTradingDay(LocalDate.now());
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

                } catch (SmartAPIException e) {
                    throw new RuntimeException(e);
                } catch (Exception e) {
                    boolean isRateLimit = e.getMessage() != null &&
                            (e.getMessage().contains("503") || e.getMessage().contains("Too Many Requests"));

                    if (isRateLimit) {
                        logger.warn("⚠️ Rate limit hit on worker thread. Forcing backoff...");
                        sleepQuietly(RATE_LIMIT_SLEEP_MS);
                    } else {
                        logger.error("Error fetching batch for prev close: {}", e.getMessage());
                    }
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
    //  STEP 0: Sync F&O Master List — INSERT-ONLY for new symbols
    // ──────────────────────────────────────────────────────────
    /**
     * Adds any symbol in {@code latestNames} that doesn't already exist in
     * FO_STOCKS. Existing rows are NEVER touched by this method — their
     * token, prevClose, percentageChange, and range-tracking fields
     * (currentRangeBucket / rangeDirection / rangeEnteredAt) are left exactly
     * as-is. Only brand-new symbols get inserted, with tracking fields left
     * null so they start clean the first time they cross the threshold.
     *
     * Comparison is case-insensitive and trims whitespace to avoid duplicate
     * rows caused by formatting differences between the source list and what
     * is already stored.
     *
     * @param latestNames the current/full list of F&O-eligible symbol names
     *                     from whatever your master source is (broker
     *                     instrument master, file, API, etc). Plug in the
     *                     actual fetch wherever this is called from.
     */
    @Transactional
    public void syncFnoMasterList(List<String> latestNames) {
        if (latestNames == null || latestNames.isEmpty()) {
            logger.warn("⚠️ syncFnoMasterList called with empty/null name list. Skipping — refusing to no-op silently against a bad source.");
            return;
        }

        List<Nifty> existingStocks = niftyRepo.findAll();

        Set<String> existingNamesNormalized = new HashSet<>();
        for (Nifty stock : existingStocks) {
            if (stock.getName() != null) {
                existingNamesNormalized.add(stock.getName().trim().toUpperCase());
            }
        }

        List<Nifty> newStocks = new ArrayList<>();
        for (String rawName : latestNames) {
            if (rawName == null || rawName.trim().isEmpty()) {
                continue;
            }
            String normalized = rawName.trim().toUpperCase();

            if (!existingNamesNormalized.contains(normalized)) {
                Nifty newStock = new Nifty();
                newStock.setName(rawName.trim());
                newStock.setIsActive(true);
                // token, prevClose, percentageChange, and range-tracking fields
                // are intentionally left null — they'll be populated by
                // precacheFnoPreviousClose() and calculateFnoPercentageChange()
                // on their normal schedule.
                newStocks.add(newStock);
                existingNamesNormalized.add(normalized); // guard against dupes within latestNames itself
            }
        }

        if (newStocks.isEmpty()) {
            logger.info("✅ F&O master list sync: no new symbols found. {} existing symbols untouched.", existingStocks.size());
            return;
        }

        niftyRepo.saveAll(newStocks);
        logger.info("🆕 F&O master list sync: inserted {} new symbol(s). {} existing symbol(s) left untouched: {}",
                newStocks.size(), existingStocks.size(), newStocks.stream().map(Nifty::getName).toList());
    }

    // ──────────────────────────────────────────────────────────
    //  STEP 2: 15-Min Percentage Change Calculation, Range-Bucket
    //  Tracking & Alerting
    // ──────────────────────────────────────────────────────────
    @Transactional
    public void calculateFnoPercentageChange() {
        if (!NSEWorkingDays.isNSEWorkingDay(LocalDate.now())) {
            logger.info("Market holiday or weekend. Skipping percentage calculation.");
            return;
        }

        logger.info("⏱️ Running 15-min F&O Percentage Change Calculation...");

        List<Nifty> stocks = niftyRepo.findAll();
        if (stocks.isEmpty()) {
            logger.warn("⚠️ No stocks found in FO_STOCKS table.");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int updateCount = 0;

        // 🆕 Broker session for ATM option-chain lookups, signed in LAZILY —
        // only the first time a stock actually enters a tracked bucket this
        // scan. If sign-in fails, optionSignInFailed stays true and every
        // subsequent stock this scan just gets Type=Err without retrying —
        // the % change alert itself is completely unaffected either way.
        SmartConnect[] optionsConnectHolder = new SmartConnect[1];
        boolean[] optionSignInFailed = {false};

        // Container to store stocks currently in a tracked +/- 5% range bucket for Telegram alert
        List<String[]> highMoverRows = new ArrayList<>();
        // header row
        highMoverRows.add(new String[]{"Stock", "Range", "Interval", "Chg%", "Direction", "Type"});

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

            // ── Range bucket + duration tracking ──
            BigDecimal absPct = percentage.abs();
            String rangeBucket = determineRangeBucket(absPct);
            String moveDirection = percentage.compareTo(BigDecimal.ZERO) >= 0 ? "UP" : "DOWN";

            if (rangeBucket == null) {
                // Dropped back below threshold — clear tracking so the next breakout starts a fresh clock
                if (stock.getCurrentRangeBucket() != null) {
                    stock.setCurrentRangeBucket(null);
                    stock.setRangeDirection(null);
                    stock.setRangeEnteredAt(null);
                }
                continue;
            }

            boolean sameRangeAndDirection = rangeBucket.equals(stock.getCurrentRangeBucket())
                    && moveDirection.equals(stock.getRangeDirection());

            if (!sameRangeAndDirection) {
                // Just crossed into this bucket, or reversed direction — reset the clock
                stock.setCurrentRangeBucket(rangeBucket);
                stock.setRangeDirection(moveDirection);
                stock.setRangeEnteredAt(now);
            }

            Duration stayDuration = Duration.between(stock.getRangeEnteredAt(), now);
            String intervalStr = formatDuration(stayDuration);
            String changeStr = (moveDirection.equals("UP") ? "+" : "") + percentage + "%";

            // 🆕 Straddle/Strangle Type — fully guarded, can NEVER break this alert.
            // safeGetOptionStrategyType() below never throws; worst case it returns "Err".
            String strategyType = safeGetOptionStrategyType(
                    optionsConnectHolder, optionSignInFailed, stock.getName(), ltp);

            highMoverRows.add(new String[]{
                    stock.getName(),
                    rangeBucket,
                    intervalStr,
                    changeStr,
                    moveDirection,
                    strategyType
            });
        }

        if (updateCount > 0) {
            niftyRepo.saveAll(stocks);
            logger.info("✅ Percentage Change updated for {}/{} F&O stocks.", updateCount, stocks.size());

            // 📢 Send Telegram Alert if enabled and tracked movers are present
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

    /**
     * Lazily signs in to the broker (once per scan, only if actually needed)
     * and delegates to TokenService for the Straddle/Strangle call.
     * This wrapper is the second layer of protection on top of
     * TokenService's own internal guarding — sign-in itself is the
     * one thing TokenService doesn't own, so it's guarded here.
     * ALWAYS returns a value, NEVER throws — guaranteed not to affect the
     * surrounding % change alert.
     */
    private String safeGetOptionStrategyType(SmartConnect[] connectHolder, boolean[] signInFailed,
                                             String stockName, BigDecimal spotLtp) {
        try {
            if (signInFailed[0]) {
                return TokenService.TYPE_ERROR;
            }

            if (connectHolder[0] == null) {
                connectHolder[0] = angelOne.signIn();
                if (connectHolder[0] == null) {
                    logger.warn("⚠️ Broker sign-in failed for option-strategy lookups this scan. All Type values will be Err.");
                    signInFailed[0] = true;
                    return TokenService.TYPE_ERROR;
                }
            }

            return tokenService.determineStraddleOrStrangle(connectHolder[0], stockName, spotLtp);

        } catch (Exception e) {
            logger.warn("⚠️ Unexpected error resolving option strategy type for {} — Reason: {}", stockName, e.getMessage());
            return TokenService.TYPE_ERROR;
        }
    }

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

    /**
     * Finds the most recent trading day strictly BEFORE the given date.
     * Evaluates backwards day-by-day using the shared utility's boolean check.
     */
    private LocalDate getStrictPreviousTradingDay(LocalDate date) {
        LocalDate currentCheck = date.minusDays(1); // Always step back 1 day first (for 8:30 AM jobs)

        // Keep looping backward until the shared utility confirms it's a working day
        while (!NSEWorkingDays.isNSEWorkingDay(currentCheck)) {
            currentCheck = currentCheck.minusDays(1);
        }

        return currentCheck;
    }

    // ──────────────────────────────────────────────────────────
    //  Range-Bucket Tracking Helpers
    // ──────────────────────────────────────────────────────────

    /**
     * Maps an absolute percentage move to a fixed-width range bucket for
     * tracking purposes. Returns null if the move hasn't reached the alert
     * threshold at all (currently 5%).
     *
     * Buckets: 5-6%, 6-7%, 7-8%, 8-9%, 9-10%, 10%+
     */
    private String determineRangeBucket(BigDecimal absPercentage) {
        double val = absPercentage.doubleValue();
        if (val < THRESHOLD_PERCENTAGE.doubleValue()) return null;
        if (val < 6) return "5-6%";
        if (val < 7) return "6-7%";
        if (val < 8) return "7-8%";
        if (val < 9) return "8-9%";
        if (val < 10) return "9-10%";
        return "10%+";
    }

    /**
     * Formats a Duration as "N min" under an hour, or "N.N hours" beyond that,
     * for display in the Telegram "Interval" column.
     */
    private String formatDuration(Duration duration) {
        long minutes = duration.toMinutes();
        if (minutes < 60) {
            return minutes + " min";
        }
        return String.format("%.1f hours", minutes / 60.0);
    }
}