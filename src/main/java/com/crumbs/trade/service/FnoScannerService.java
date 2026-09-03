package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.repo.OrderRepository;
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
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.NiftyRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.utility.NSEWorkingDays;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FnoScannerService {

    private static final Logger logger = LogManager.getLogger(FnoScannerService.class);

    // Configurable thresholds
    private static final BigDecimal THRESHOLD_PERCENTAGE = new BigDecimal("5.0");
    private static final boolean ENABLE_TELEGRAM_ALERTS = true;
    private static final int BATCH_SIZE = 50;
    private static final String EXCHANGE = "NSE";
    private static final int BATCH_FETCH_DELAY_MS = 250;
    private static final int RATE_LIMIT_SLEEP_MS = 6000;

    // Executor configuration for retry logic
    private static final int MAX_BROKER_SIGNIN_RETRIES = 2;
    private static final long BROKER_SIGNIN_RETRY_DELAY_MS = 500;
    private static final int MAX_JSON_PARSE_RETRIES = 2;
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 60;
    private static final int TELEGRAM_BATCH_SIZE = 20; // Max 20 stocks per message

    private final NiftyRepo niftyRepo;
    private final IndexesRepo indexesRepo;
    private final PredictionService predictionService;
    private final AngelOne angelOne;
    private final AngelWebSocketService angelWebSocketService;
    private final TelegramService telegramService;
    private final TokenService tokenService;
    private final OrderRepository orderRepository;
    // 🚀 INJECTED EXECUTION ENGINE & STRATEGY REPO
    private final FnoOrderService fnoOrderService;
    private final StrategyRepo strategyRepo;

    // Track subscribed tokens to prevent duplicate subscriptions
    private final Set<String> subscribedTokens = ConcurrentHashMap.newKeySet();

    // ──────────────────────────────────────────────────────────
    //  STEP 1: Pre-Cache for F&O Previous Close (Runs at 9:20 AM)
    // ──────────────────────────────────────────────────────────

    // REMOVED @Transactional here to protect DB connection pool during external HTTP calls
    public void precacheFnoPreviousClose() {
        logger.info("🌅 Starting Multi-Threaded Pre-Cache for F&O Previous Close...");

        List<Nifty> fnoStocks = niftyRepo.findAll();
        if (fnoStocks.isEmpty()) {
            logger.warn("⚠️ No active F&O stocks found in FO_STOCKS table. Skipping prevClose cache.");
            return;
        }

        // Reset range-tracking fields for new trading day
        for (Nifty stock : fnoStocks) {
            stock.setCurrentRangeBucket(null);
            stock.setRangeDirection(null);
            stock.setRangeEnteredAt(null);
        }

        SmartConnect smartConnect = angelOne.signIn();
        if (smartConnect == null) {
            logger.error("🛑 Failed to sign in to broker API. Aborting pre-cache.");
            return;
        }

        // Batch fetch missing tokens in single query
        List<String> missingTokenStocks = fnoStocks.stream()
                .filter(s -> s.getToken() == null || s.getToken().trim().isEmpty())
                .map(Nifty::getName)
                .collect(Collectors.toList());

        if (!missingTokenStocks.isEmpty()) {
            resolveMissingTokensInBatch(fnoStocks, missingTokenStocks);
        }

        // Build tokens list (avoiding N+1 queries)
        List<String> tokens = fnoStocks.stream()
                .map(Nifty::getToken)
                .filter(t -> t != null && !t.trim().isEmpty())
                .collect(Collectors.toList());

        if (tokens.isEmpty()) {
            logger.warn("⚠️ No valid tokens found to fetch market data. Aborting.");
            return;
        }

        AtomicInteger updatedCount = new AtomicInteger(0);
        final LocalDate prevTradingDay = getStrictPreviousTradingDay(LocalDate.now());
        logger.info("📅 Determined Previous Trading Date as: {}", prevTradingDay);

        // Use ConcurrentHashMap for thread-safe access
        Map<String, Nifty> stocksByToken = fnoStocks.stream()
                .filter(s -> s.getToken() != null) // Filter out null keys to prevent NPE
                .collect(Collectors.toMap(
                        Nifty::getToken,
                        s -> s,
                        (a, b) -> a,
                        ConcurrentHashMap::new
                ));

        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < tokens.size(); i += BATCH_SIZE) {
                int endIndex = Math.min(i + BATCH_SIZE, tokens.size());
                List<String> batchTokens = tokens.subList(i, endIndex);

                futures.add(CompletableFuture.runAsync(() -> {
                    processPrevCloseBatch(smartConnect, batchTokens, stocksByToken, prevTradingDay, updatedCount);
                }, executor));
            }

            // Properly await executor termination
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        } finally {
            gracefulExecutorShutdown(executor);
        }

        // Save all updated stocks in smaller transactions
        savePrevCloseBatchTransactionally(new ArrayList<>(stocksByToken.values()));
        logger.info("✅ Pre-Cache Complete! Saved Previous Close for {}/{} F&O stocks.",
                updatedCount.get(), fnoStocks.size());

        // Pre-warm WebSocket subscriptions before calculations
        preWarmWebSocketSubscriptions(tokens);
    }

    // Extract batch token resolution to avoid N+1 queries
    private void resolveMissingTokensInBatch(List<Nifty> allStocks, List<String> missingNames) {
        try {
            Map<String, String> nameToToken = indexesRepo.findByNamesAndExchange(missingNames, EXCHANGE)
                    .stream()
                    .collect(Collectors.toMap(Indexes::getName, Indexes::getToken, (a, b) -> a));

            for (Nifty stock : allStocks) {
                if ((stock.getToken() == null || stock.getToken().trim().isEmpty()) &&
                        nameToToken.containsKey(stock.getName())) {
                    stock.setToken(nameToToken.get(stock.getName()));
                    logger.info("🔄 Auto-resolved missing token for {}: {}", stock.getName(), stock.getToken());
                }
            }
        } catch (Exception e) {
            logger.warn("⚠️ Failed to batch-resolve tokens: {}. Will attempt per-stock lookup.", e.getMessage());
            // Fallback to per-stock lookup if batch fails
            for (Nifty stock : allStocks) {
                if (stock.getToken() == null || stock.getToken().trim().isEmpty()) {
                    String token = lookupTokenFromIndexes(stock.getName());
                    if (token != null) {
                        stock.setToken(token);
                    }
                }
            }
        }
    }

    // Extract prev close batch processing with proper error handling
    private void processPrevCloseBatch(SmartConnect smartConnect, List<String> batchTokens,
                                       Map<String, Nifty> stocksByToken, LocalDate prevTradingDay,
                                       AtomicInteger updatedCount) {
        int retryCount = 0;
        while (retryCount < MAX_JSON_PARSE_RETRIES) {
            try {
                JSONObject payload = predictionService.buildMarketDataPayload(batchTokens, EXCHANGE);
                JSONObject response = predictionService.callMarketDataWithRetry(smartConnect, payload);

                if (response != null) {
                    JSONArray fetched = extractFetchedArray(response);
                    if (fetched != null) {
                        for (int j = 0; j < fetched.length(); j++) {
                            try {
                                JSONObject obj = fetched.getJSONObject(j);
                                String responseToken = obj.getString("symbolToken");

                                if (obj.has("close") && !obj.isNull("close")) {
                                    BigDecimal prevClose = new BigDecimal(obj.get("close").toString());

                                    Nifty stock = stocksByToken.get(responseToken);
                                    if (stock != null) {
                                        stock.setPrevClose(prevClose);
                                        stock.setPrevCloseDate(prevTradingDay);
                                        updatedCount.incrementAndGet();
                                    }
                                }
                            } catch (JSONException je) {
                                logger.warn("⚠️ Error parsing individual stock data: {}. Skipping this record.", je.getMessage());
                                // Continue with next record instead of failing entire batch
                            }
                        }
                        break; // Success, exit retry loop
                    } else {
                        logger.warn("⚠️ No fetched array in response. Attempt {}/{}", retryCount + 1, MAX_JSON_PARSE_RETRIES);
                        retryCount++;
                    }
                } else {
                    logger.warn("⚠️ Null response from market data API. Attempt {}/{}", retryCount + 1, MAX_JSON_PARSE_RETRIES);
                    retryCount++;
                }

                if (retryCount < MAX_JSON_PARSE_RETRIES) {
                    sleepQuietly(BATCH_FETCH_DELAY_MS);
                }

            } catch (SmartAPIException e) {
                handleSmartAPIException(e);
                retryCount++;
            } catch (Exception e) {
                logger.error("Unexpected error fetching batch [tokens: {}] for prev close: {}. Attempt {}/{}",
                        batchTokens, e.getMessage(), retryCount + 1, MAX_JSON_PARSE_RETRIES, e);
                retryCount++;
            }
        }

        if (retryCount >= MAX_JSON_PARSE_RETRIES) {
            logger.error("❌ Failed to fetch prev close for batch after {} attempts: {}", MAX_JSON_PARSE_RETRIES, batchTokens);
        }

        sleepQuietly(BATCH_FETCH_DELAY_MS);
    }

    // Handle rate limits consistently
    private void handleSmartAPIException(SmartAPIException e) {
        String message = e.getMessage() != null ? e.getMessage() : "";
        if (message.contains("503") || message.contains("Too Many Requests")) {
            logger.warn("⚠️ Rate limit hit. Enforcing backoff of {}ms", RATE_LIMIT_SLEEP_MS);
            sleepQuietly(RATE_LIMIT_SLEEP_MS);
        } else {
            logger.error("SmartAPI error: {}", message, e);
        }
    }

    // Extract transactional save to smaller chunks
    @Transactional
    private void savePrevCloseBatchTransactionally(List<Nifty> stocks) {
        if (stocks.isEmpty()) return;

        int chunkSize = 50;
        for (int i = 0; i < stocks.size(); i += chunkSize) {
            int endIdx = Math.min(i + chunkSize, stocks.size());
            niftyRepo.saveAll(stocks.subList(i, endIdx));
            logger.debug("Saved prev close for stocks {}-{}/{}", i, endIdx, stocks.size());
        }
    }

    // Idempotent WebSocket subscription
    private void preWarmWebSocketSubscriptions(List<String> tokens) {
        logger.info("🔌 Pre-warming WebSocket subscriptions for {} tokens...", tokens.size());
        for (String token : tokens) {
            if (subscribedTokens.add(token)) { // Only subscribe if new
                try {
                    angelWebSocketService.subscribe(ExchangeType.NSE_CM, token);
                } catch (Exception e) {
                    logger.warn("⚠️ Failed to subscribe to token {}: {}", token, e.getMessage());
                    subscribedTokens.remove(token); // Remove on failure to retry next time
                }
            }
        }
        logger.info("✅ WebSocket pre-warm complete. Total subscribed: {}", subscribedTokens.size());
    }

    // ──────────────────────────────────────────────────────────
    //  STEP 0: Sync F&O Master List — INSERT-ONLY for new symbols
    // ──────────────────────────────────────────────────────────
    @Transactional
    public void syncFnoMasterList(List<String> latestNames) {
        if (latestNames == null || latestNames.isEmpty()) {
            logger.warn("⚠️ syncFnoMasterList called with empty/null name list. Skipping.");
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
                newStocks.add(newStock);
                existingNamesNormalized.add(normalized);
            }
        }

        if (newStocks.isEmpty()) {
            logger.info("✅ F&O master list sync: no new symbols found. {} existing symbols untouched.",
                    existingStocks.size());
            return;
        }

        niftyRepo.saveAll(newStocks);
        logger.info("🆕 F&O master list sync: inserted {} new symbol(s). {} existing symbol(s) left untouched.",
                newStocks.size(), existingStocks.size());
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

        // Check if prevClose data is ready
        long missingPrevClose = stocks.stream()
                .filter(s -> s.getPrevClose() == null)
                .count();

        if (missingPrevClose > stocks.size() * 0.5) { // >50% missing
            logger.error("❌ Too many stocks ({}/{}) missing prevClose. Aborting calculation " +
                            "to avoid false alerts. Pre-cache may still be running.",
                    missingPrevClose, stocks.size());
            return;
        }

        if (missingPrevClose > 0) {
            logger.warn("⚠️ {} stocks missing prevClose. Will skip these in calculation.", missingPrevClose);
        }

        LocalDateTime now = LocalDateTime.now();
        int updateCount = 0;

        // Lazy broker session for ATM option-chain lookups with retry logic
        SmartConnect[] optionsConnectHolder = new SmartConnect[1];
        boolean[] optionSignInFailed = {false};

        // 🚀 FETCH LIVE TRADING FLAG FROM STRATEGY TABLE
        boolean isLiveTrading = false;
        try {
            Strategy strategy = strategyRepo.findByName("FNO_SCANNER");
            if (strategy != null && "Y".equalsIgnoreCase(strategy.getLive())) {
                isLiveTrading = true;
            }
        } catch (Exception e) {
            logger.warn("⚠️ Could not fetch strategy config for FNO_SCANNER: {}", e.getMessage());
        }

        List<String[]> highMoverRows = new ArrayList<>();
        highMoverRows.add(new String[]{"Stock", "Range", "Interval", "Chg%", "Direction", "Type", "Status"});

        for (Nifty stock : stocks) {
            if (!isValidStock(stock)) {
                continue;
            }

            try {
                // Pass isLiveTrading flag down to process method
                processStockPercentageChange(stock, now, optionsConnectHolder, optionSignInFailed, highMoverRows, isLiveTrading);
                updateCount++;
            } catch (Exception e) {
                logger.error("Error processing stock {}: {}. Skipping.", stock.getName(), e.getMessage());
                // Continue with next stock instead of failing entire scan
            }
        }

        if (updateCount > 0) {
            savePercentageChangeBatchTransactionally(stocks);
            logger.info("✅ Percentage Change updated for {}/{} F&O stocks.", updateCount, stocks.size());

            // Paginate Telegram sends
            if (ENABLE_TELEGRAM_ALERTS && highMoverRows.size() > 1) {
                logger.info("📢 Found {} stocks moving > {}%. Sending Telegram alerts...",
                        highMoverRows.size() - 1, THRESHOLD_PERCENTAGE);
                sendTelegramAlertsPaginated(highMoverRows);
            }
        } else {
            logger.warn("⚠️ No stocks updated. Check if market is active and WebSocket subscriptions are receiving ticks.");
        }
    }

    // Validate stock has required fields
    private boolean isValidStock(Nifty stock) {
        String token = Optional.ofNullable(stock.getToken())
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .orElse(null);

        BigDecimal prevClose = stock.getPrevClose();

        if (token == null) {
            logger.warn("⚠️ Stock {} has no token. Skipping.", stock.getName());
            return false;
        }

        if (prevClose == null || prevClose.compareTo(BigDecimal.ZERO) == 0) {
            logger.warn("⚠️ Stock {} has invalid prevClose: {}. Skipping.", stock.getName(), prevClose);
            return false;
        }

        return true;
    }

    // Extract stock processing logic
    private void processStockPercentageChange(Nifty stock, LocalDateTime now,
                                              SmartConnect[] optionsConnectHolder, boolean[] optionSignInFailed,
                                              List<String[]> highMoverRows, boolean isLiveTrading) {
        String token = stock.getToken();
        BigDecimal prevClose = stock.getPrevClose();

        // Fetch live LTP from WebSocket (zero REST API overhead)
        BigDecimal ltp = angelWebSocketService.getLatestLTP(ExchangeType.NSE_CM, token);

        // Self-healing: subscribe if data missing
        if (ltp == null || ltp.compareTo(BigDecimal.ZERO) == 0) {
            logger.warn("⚠️ No live LTP for {} (Token: {}). Triggering dynamic subscription...",
                    stock.getName(), token);
            if (subscribedTokens.add(token)) {
                angelWebSocketService.subscribe(ExchangeType.NSE_CM, token);
            }
            return; // Skip calculation for this tick
        }

        // Calculate percentage: ((LTP - PrevClose) / PrevClose) * 100
        BigDecimal difference = ltp.subtract(prevClose);
        BigDecimal percentage = difference
                .divide(prevClose, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);

        stock.setPercentageChange(percentage);
        stock.setPercentageUpdatedTime(now);

        // Range bucket + duration tracking
        BigDecimal absPct = percentage.abs();
        String rangeBucket = determineRangeBucket(absPct);
        String moveDirection = percentage.compareTo(BigDecimal.ZERO) >= 0 ? "UP" : "DOWN";

        if (rangeBucket == null) {
            // Dropped back below threshold — clear tracking
            if (stock.getCurrentRangeBucket() != null) {
                stock.setCurrentRangeBucket(null);
                stock.setRangeDirection(null);
                stock.setRangeEnteredAt(null);
            }
            return;
        }

        boolean sameRangeAndDirection = rangeBucket.equals(stock.getCurrentRangeBucket())
                && moveDirection.equals(stock.getRangeDirection());

        if (!sameRangeAndDirection) {
            // Just crossed into this bucket or reversed — reset clock
            stock.setCurrentRangeBucket(rangeBucket);
            stock.setRangeDirection(moveDirection);
            stock.setRangeEnteredAt(now);
        }

        Duration stayDuration = Duration.between(stock.getRangeEnteredAt(), now);
        String intervalStr = formatDuration(stayDuration);
        String changeStr = (moveDirection.equals("UP") ? "+" : "") + percentage + "%";

        // Improved option strategy type with error details
        String[] strategyInfo = safeGetOptionStrategyType(
                optionsConnectHolder, optionSignInFailed, stock.getName(), ltp);
        String strategyType = strategyInfo[0];
        String statusMessage = strategyInfo.length > 1 ? strategyInfo[1] : "";

        highMoverRows.add(new String[]{
                stock.getName(),
                rangeBucket,
                intervalStr,
                changeStr,
                moveDirection,
                strategyType,
                statusMessage
        });

        // 🚀 TRIGGER ASYNC ORDER DISPATCH
        dispatchOrderExecution(stock, ltp, strategyType, isLiveTrading);
    }

    /**
     * Resolves ATM/OTM options and triggers asynchronous limit-sniper order execution in FnoOrderService.
     * FIX: Added expiry format validation to prevent RBLBANK29SEP2026410CE malformation
     */
    private void dispatchOrderExecution(Nifty stock, BigDecimal spotLtp, String strategyType, boolean isLiveTrading) {
        if (strategyType == null || TokenService.TYPE_ERROR.equalsIgnoreCase(strategyType)) {
            logger.warn("⚠️ Cannot execute order for {}: Strategy determination returned error.", stock.getName());
            return;
        }

        if (hasActiveTradeTodayForStock(stock.getName())) {
            logger.info("🔐 [DAILY LOCK] Skipping {} - already has active trade today.", stock.getName());
            return;
        }

        try {
            Optional<TokenService.AtmContracts> atmOpt = tokenService.resolveAtmContracts(stock.getName(), spotLtp);
            if (atmOpt.isEmpty()) {
                logger.warn("⚠️ No ATM contracts found for {}. Order dispatch skipped.", stock.getName());
                return;
            }

            TokenService.AtmContracts atm = atmOpt.get();

            // 🔧 FIX #1: VALIDATE EXPIRY FORMAT BEFORE SYMBOL CONSTRUCTION
            // Expected format: DDMMMYY (e.g., "29SEP26" = 7 chars)
            // Bug was producing: "29SEP2026" → resulting in "RBLBANK29SEP2026410CE" (wrong!)
            String expiryFromAtm = atm.expiry();
            if (expiryFromAtm == null || expiryFromAtm.trim().isEmpty()) {
                logger.error("❌ ATM expiry is null/empty for {}. Aborting order dispatch.", stock.getName());
                return;
            }

            // Trim and validate length
            String expiry = expiryFromAtm.trim();
            if (expiry.length() > 7) {  // DDMMMYY = 7 chars max
                logger.error("❌ [MALFORMED EXPIRY] {} returned expiry '{}' (length: {}, expected ≤7). " +
                                "Symbol would be: {}{}{}CE (WRONG!). Aborting order dispatch.",
                        stock.getName(), expiry, expiry.length(),
                        stock.getName(), expiry, atm.strike().intValue());
                return;
            }

            // Fetch lotsize from Indexes metadata (defaults to 1 if not present)
            int quantity = 1;
            Indexes meta = indexesRepo.findByNameAndExchange(stock.getName(), EXCHANGE);
            if (meta != null && meta.getLotsize() > 0) {
                quantity = meta.getLotsize();
            }

            // Standardize format: "RELIANCE28AUG242900CE"
            String ceTradingSymbol = stock.getName() + expiry + atm.strike().intValue() + "CE";
            String peTradingSymbol = stock.getName() + expiry + atm.strike().intValue() + "PE";

            // 🔧 LOG THE BUILT SYMBOLS (CRITICAL for debugging)
            logger.info("✅ [SYMBOL BUILD] {} | Expiry: {} ({}ch) | Strike: {} | CE: {} | PE: {}",
                    stock.getName(), expiry, expiry.length(), atm.strike().intValue(),
                    ceTradingSymbol, peTradingSymbol);

            String strategyKey = "FNO_SCANNER_" + stock.getName();

            logger.info("🚀 [TRIGGER TRADE] {} | CE: {} | PE: {} | Qty: {} | Live: {}",
                    strategyKey, ceTradingSymbol, peTradingSymbol, quantity, isLiveTrading);

            // Hand off to FnoOrderService (Runs asynchronous Limit Chaser)
            fnoOrderService.executeStrategyPair(
                    strategyKey,
                    stock.getName(),
                    atm.ceToken(),
                    ceTradingSymbol,
                    atm.strike(),
                    spotLtp,
                    atm.peToken(),
                    peTradingSymbol,
                    atm.strike(),
                    spotLtp,
                    quantity,
                    "NFO",
                    isLiveTrading
            );

        } catch (Exception e) {
            logger.error("❌ Failed to dispatch order for {}: {}", stock.getName(), e.getMessage(), e);
        }
    }

    // Extract transactional save
    @Transactional
    private void savePercentageChangeBatchTransactionally(List<Nifty> stocks) {
        int chunkSize = 50;
        for (int i = 0; i < stocks.size(); i += chunkSize) {
            int endIdx = Math.min(i + chunkSize, stocks.size());
            niftyRepo.saveAll(stocks.subList(i, endIdx));
        }
    }

    // Paginated Telegram sends
    private void sendTelegramAlertsPaginated(List<String[]> highMoverRows) {
        int dataSize = highMoverRows.size() - 1; // Exclude header
        if (dataSize == 0) {
            return;
        }

        int totalMessages = (dataSize + TELEGRAM_BATCH_SIZE - 1) / TELEGRAM_BATCH_SIZE; // Ceiling division

        for (int page = 0; page < totalMessages; page++) {
            int startIdx = 1 + (page * TELEGRAM_BATCH_SIZE); // +1 to skip header
            int endIdx = Math.min(startIdx + TELEGRAM_BATCH_SIZE, highMoverRows.size());

            List<String[]> pageData = new ArrayList<>();
            pageData.add(highMoverRows.get(0)); // Add header
            pageData.addAll(highMoverRows.subList(startIdx, endIdx));

            try {
                telegramService.sendStockAlert(pageData);
                logger.info("📤 Sent Telegram alert page {}/{} with {} stocks",
                        page + 1, totalMessages, pageData.size() - 1);
            } catch (Exception e) {
                logger.error("Failed to send Telegram alert page {}/{}: {}",
                        page + 1, totalMessages, e.getMessage());
            }

            // Small delay between messages to avoid rate limits
            if (page < totalMessages - 1) {
                sleepQuietly(200);
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────

    /**
     * Broker sign-in with retry logic.
     * Never throws - always returns a value.
     * Attempts sign-in MAX_BROKER_SIGNIN_RETRIES times with backoff.
     */
    private String[] safeGetOptionStrategyType(SmartConnect[] connectHolder, boolean[] signInFailed,
                                               String stockName, BigDecimal spotLtp) {
        try {
            if (signInFailed[0]) {
                return new String[]{TokenService.TYPE_ERROR, "Broker signin disabled for this scan"};
            }

            if (connectHolder[0] == null) {
                connectHolder[0] = signInWithRetry();
                if (connectHolder[0] == null) {
                    logger.warn("⚠️ Broker sign-in failed after {} retries. Option strategy type will be Err.",
                            MAX_BROKER_SIGNIN_RETRIES);
                    signInFailed[0] = true;
                    return new String[]{TokenService.TYPE_ERROR, "Broker unavailable"};
                }
            }

            return new String[]{tokenService.determineStraddleOrStrangle(connectHolder[0], stockName, spotLtp), "OK"};

        } catch (Exception e) {
            logger.warn("⚠️ Unexpected error resolving option strategy type for {} — Reason: {}",
                    stockName, e.getMessage());
            return new String[]{TokenService.TYPE_ERROR, "Exception: " + e.getMessage()};
        }
    }

    // Broker sign-in with retry
    private SmartConnect signInWithRetry() {
        for (int attempt = 0; attempt < MAX_BROKER_SIGNIN_RETRIES; attempt++) {
            try {
                SmartConnect connect = angelOne.signIn();
                if (connect != null) {
                    logger.info("✅ Broker sign-in successful on attempt {}/{}", attempt + 1, MAX_BROKER_SIGNIN_RETRIES);
                    return connect;
                }
                logger.warn("⚠️ Broker sign-in returned null. Attempt {}/{}", attempt + 1, MAX_BROKER_SIGNIN_RETRIES);
            } catch (Exception e) {
                logger.warn("⚠️ Broker sign-in failed on attempt {}/{}: {}", attempt + 1, MAX_BROKER_SIGNIN_RETRIES, e.getMessage());
            }

            if (attempt < MAX_BROKER_SIGNIN_RETRIES - 1) {
                sleepQuietly(BROKER_SIGNIN_RETRY_DELAY_MS);
            }
        }
        return null;
    }

    // Graceful executor shutdown
    private void gracefulExecutorShutdown(ExecutorService executor) {
        try {
            executor.shutdown();
            if (!executor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("⚠️ Executor did not terminate within {}s. Force shutting down.",
                        EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS);
                List<Runnable> remaining = executor.shutdownNow();
                if (!remaining.isEmpty()) {
                    logger.warn("⚠️ {} tasks were cancelled during executor shutdown", remaining.size());
                }
            } else {
                logger.debug("✅ Executor shutdown completed gracefully");
            }
        } catch (InterruptedException ie) {
            logger.warn("⚠️ Interrupted while waiting for executor shutdown");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private String lookupTokenFromIndexes(String name) {
        try {
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
        } catch (Exception e) {
            logger.warn("⚠️ Error looking up token for {}: {}", name, e.getMessage());
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
     */
    private LocalDate getStrictPreviousTradingDay(LocalDate date) {
        LocalDate currentCheck = date.minusDays(1);

        while (!NSEWorkingDays.isNSEWorkingDay(currentCheck)) {
            currentCheck = currentCheck.minusDays(1);
        }

        return currentCheck;
    }

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

    private String formatDuration(Duration duration) {
        long minutes = duration.toMinutes();
        if (minutes < 60) {
            return minutes + " min";
        }
        return String.format("%.1f hours", minutes / 60.0);
    }

    /**
     * Checks if any OPEN/ENTRY order exists for this stock from the FNO_SCANNER strategy.
     * Prevents duplicate order execution.
     */
    private boolean hasActiveTradeTodayForStock(String stockName) {
        try {
            long activeTradesToday = orderRepository.countActiveTradesToday(stockName);

            if (activeTradesToday > 0) {
                logger.info("🔐 [DAILY LOCK] {} already has active trade. Skipping.", stockName);
                return true;
            }
            return false;
        } catch (Exception e) {
            logger.warn("⚠️ Error checking daily lock for {}: {}. Proceeding with caution.",
                    stockName, e.getMessage());
            return false;
        }
    }
}