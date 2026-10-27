package com.crumbs.trade.service;

import com.crumbs.trade.broker.BrokerProxyClient;
import com.crumbs.trade.dto.*;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.StrategyRepo;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class FlatTradeService {
    private static final Logger logger = LogManager.getLogger(FlatTradeService.class);
    private static final String BASE_URL = "https://piconnect.flattrade.in/PiConnectAPI";
    private static final String AUTH_URL = "https://authapi.flattrade.in/trade/apitoken";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private String cachedJKey = null;
    private Instant lastFetchTime = null;

    /** Direct WebClient: used for token-free-IP calls (LTP, quotes, order status). */
    @Autowired
    private WebClient webClient;

    /** Static-IP proxy client: used ONLY for place / modify order calls. */
    @Autowired
    private BrokerProxyClient proxyClient;

    @Autowired
    private BrokerConfigService brokerConfigService;

    @Autowired
    private StrategyRepo strategyRepo;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /**
     * MAIN ENTRY POINT: Gets a valid token by checking Memory -> Database -> API Exchange.
     */
    public synchronized String getTokenForFlatTrade() throws Exception {
        // 1. Check In-Memory Cache (Fastest)
        if (isTokenStillValid(lastFetchTime) && cachedJKey != null) {
            logger.info("[FLATTRADE] Using valid token from memory cache.");
            return cachedJKey;
        }

        // 2. Check Database (Handles Railway Restarts)
        logger.info("[FLATTRADE] Token not in memory. Checking Database for persisted token...");
        BrokerAuthConfig cfg = brokerConfigService.getFlatTradeConfig();

        if (isTokenStillValid(cfg.getTokenDate()) && cfg.getApiToken() != null) {
            logger.info("[FLATTRADE] Found valid token in DB (Generated at: {}). Loading to memory.", cfg.getTokenDate());
            this.cachedJKey = cfg.getApiToken();
            this.lastFetchTime = cfg.getTokenDate();
            return cachedJKey;
        }

        // 3. If DB token is expired/missing, perform new API Exchange (direct connection)
        logger.warn("[FLATTRADE] No valid token found in DB/Memory. Starting API token exchange...");

        String requestCode = cfg.getRequestCode();
        if (requestCode == null || requestCode.isEmpty()) {
            logger.error("[FLATTRADE] Login Required: No request_code found in DB. Please login via browser.");
            throw new RuntimeException("Missing request_code. Login via browser first.");
        }

        return performTokenExchange(cfg, requestCode);
    }

    private String performTokenExchange(BrokerAuthConfig cfg, String requestCode) throws Exception {
        String hashInput = cfg.getApiKey() + requestCode + cfg.getApiSecret();
        String apiSecretHash = generateSHA256(hashInput);

        JSONObject payload = new JSONObject();
        payload.put("api_key", cfg.getApiKey());
        payload.put("request_code", requestCode);
        payload.put("api_secret", apiSecretHash);

        logger.info("[FLATTRADE-API] Sending POST to {}", AUTH_URL);

        String responseBody = sendPost(AUTH_URL, payload.toString());

        JSONObject json = new JSONObject(responseBody);
        String status = json.has("stat") ? json.getString("stat") : json.optString("status");

        if ("Ok".equalsIgnoreCase(status)) {
            this.cachedJKey = json.getString("token");
            this.lastFetchTime = Instant.now();

            // Persist to DB for future restarts
            brokerConfigService.updateApiToken(this.cachedJKey);
            brokerConfigService.clearRequestCode();

            logger.info("[FLATTRADE-SUCCESS] New token generated and saved to DB for user: {}", json.optString("client"));
            return cachedJKey;
        } else {
            String errorMsg = json.optString("emsg", "Unknown API Error");
            logger.error("[FLATTRADE-ERROR] Exchange failed! Status: {}, Message: {}", status, errorMsg);
            throw new RuntimeException("FlatTrade API Error: " + errorMsg);
        }
    }

    /**
     * Logic to determine if a token is valid based on the 6:00 AM IST reset rule.
     */
    private boolean isTokenStillValid(Instant tokenTime) {
        if (tokenTime == null) return false;

        ZonedDateTime now = ZonedDateTime.now(IST);
        ZonedDateTime tokenGeneratedAt = tokenTime.atZone(IST);

        ZonedDateTime todaySixAM = now.withHour(6).withMinute(0).withSecond(0).withNano(0);

        if (now.isAfter(todaySixAM)) {
            // If it's currently after 6 AM, token must be from after 6 AM today
            return tokenGeneratedAt.isAfter(todaySixAM);
        } else {
            // If it's currently before 6 AM, a token from after 6 AM yesterday is still valid
            ZonedDateTime yesterdaySixAM = todaySixAM.minusDays(1);
            return tokenGeneratedAt.isAfter(yesterdaySixAM);
        }
    }

    // --- NETWORK HELPERS ---

    /**
     * Sends an order-type request (place / modify) through the static-IP proxy.
     * Everything else in this class connects directly.
     */
    private String postOrderViaProxy(String url, String rawPayload) throws Exception {
        if (!proxyClient.isProxyEnabled()) {
            logger.warn("[FLATTRADE-ORDER] Proxy is NOT configured; order will leave from a non-whitelisted IP.");
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                // FlatTrade requires this header even though the body is "jData=...&jKey=..."
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(rawPayload))
                .build();

        HttpResponse<String> response = proxyClient.sendOrder(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            logger.error("[FLATTRADE-CRITICAL] HTTP {} from {}. Body: {}",
                    response.statusCode(), url, response.body());
            throw new RuntimeException("FlatTrade API Rejected Request (HTTP "
                    + response.statusCode() + "): " + response.body());
        }
        return response.body();
    }

    private static String sendPost(String urlStr, String jsonPayload) throws IOException {
        URL url = new URL(urlStr);
        HttpsURLConnection con = (HttpsURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        con.setConnectTimeout(10000);
        con.setReadTimeout(15000);
        con.setDoOutput(true);
        try (OutputStream os = con.getOutputStream()) {
            os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(con);
    }

    private static String readResponse(HttpsURLConnection con) throws IOException {
        int code = con.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? con.getInputStream() : con.getErrorStream();
        if (is == null) return "Empty Response Body (HTTP " + code + ")";
        try (BufferedReader in = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private String generateSHA256(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    // --- TRADING METHODS (LTP direct, PlaceOrder / ModifyOrder via proxy) ---

    public BigDecimal getCurrentPrice(String exch, String token) {
        try {
            String jKey = getTokenForFlatTrade();
            Map<String, String> jData = new HashMap<>();
            jData.put("uid", "MALIT158");
            jData.put("exch", exch);
            jData.put("token", token);
            String body = "jData=" + objectMapper.writeValueAsString(jData) + "&jKey=" + jKey;

            FlatTradeLtpResponse res = webClient.post().uri(BASE_URL + "/GetLTP")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).bodyValue(body)
                    .retrieve().bodyToMono(FlatTradeLtpResponse.class).block();

            return (res != null && "Ok".equalsIgnoreCase(res.getStat())) ? new BigDecimal(res.getLtp()) : null;
        } catch (Exception e) {
            logger.error("[FLATTRADE-LTP] Error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Places the order THROUGH THE PROXY (static IP) and returns the Token with orderId populated.
     */
    public Token PlaceOrderInFlatTrade(Token token) throws Exception {
        String key = getTokenForFlatTrade();
        if (key == null) throw new RuntimeException("Token generation failed.");

        // WARNING: Ensure token.getSymbol() contains the hyphen (e.g., "RELIANCE-EQ").

        // 1. Create the JSON string (Requires @JsonInclude(JsonInclude.Include.NON_NULL) on JData class)
        String jDataJson = objectMapper.writeValueAsString(setJDataForOrder(token));

        // 2. Build the raw unescaped string EXACTLY as FlatTrade wants it
        String rawPayload = "jData=" + jDataJson + "&jKey=" + key;

        // Log jData only; never log the session key (jKey)
        logger.info("[FLATTRADE-ORDER] Sending order via proxy. jData: {}", jDataJson);

        try {
            String responseBody = postOrderViaProxy(BASE_URL + "/PlaceOrder", rawPayload);

            if (responseBody != null) {
                JSONObject response = new JSONObject(responseBody);

                if ("Ok".equalsIgnoreCase(response.optString("stat"))) {
                    String orderId = response.getString("norenordno");
                    logger.info("[FLATTRADE-SUCCESS] Order Placed: {}", orderId);

                    token.setOrderId(orderId);
                    return token;
                } else {
                    String error = response.optString("emsg", "Unknown API Error");
                    logger.error("[FLATTRADE-ERROR] Exchange Rejected: {}", error);
                    throw new RuntimeException("FlatTrade Order Error: " + error);
                }
            }
            return token;

        } catch (Exception e) {
            logger.error("[FLATTRADE-CRITICAL] Request Failed: {}", e.toString());
            throw e;
        }
    }

    /**
     * Fetches exact execution details for a specific FlatTrade order (direct connection).
     * Use this to get the 'avgprc' for your PnL calculations.
     */
    public JSONObject getIndividualOrderDetails(String orderId) {
        try {
            String jKey = getTokenForFlatTrade();
            Map<String, String> jData = new HashMap<>();
            jData.put("uid", "MALIT158");
            jData.put("norenordno", orderId);

            String body = "jData=" + objectMapper.writeValueAsString(jData) + "&jKey=" + jKey;

            // FlatTrade SingleOrderHistory returns a JSON array string
            String responseBody = webClient.post().uri(BASE_URL + "/SingleOrderHistory")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (responseBody != null) {
                org.json.JSONArray jsonArray = new org.json.JSONArray(responseBody);
                if (jsonArray.length() > 0) {
                    return jsonArray.getJSONObject(0);
                }
            }
        } catch (Exception e) {
            logger.error("[FLATTRADE-ORDER-DETAILS] Error fetching order {}: {}", orderId, e.getMessage());
        }
        return null;
    }

    private JData setJDataForOrder(Token token) {
        JData jdata = new JData();
        jdata.setUid("MALIT158");
        jdata.setActid("MALIT158");
        jdata.setExch(token.getExch_seg());
        jdata.setTsym(token.getSymbol());
        jdata.setQty(String.valueOf(token.getQuantity()));

        // --- FORCE LIMIT ORDER LOGIC ---
        // Brokers no longer allow "MKT" via API.
        String orderType = (token.getOrderType() != null) ? token.getOrderType() : "LMT";
        if ("MKT".equalsIgnoreCase(orderType)) {
            logger.warn("[FLATTRADE-ORDER] Market orders not allowed by API. Converting to Limit order.");
            orderType = "LMT";
        }
        jdata.setPrctyp(orderType);

        // Set the price for the Limit Order (cannot be 0)
        if (token.getPrice() != null && token.getPrice() > 0) {
            jdata.setPrc(String.valueOf(token.getPrice()));
        } else {
            throw new IllegalArgumentException("API requires Limit Orders. You must provide a valid 'price' in your Token > 0.");
        }

        // Set Product Type dynamically (default to "I" for Intraday/MIS if null)
        jdata.setPrd(token.getProductType() != null ? token.getProductType() : "I");

        jdata.setTrantype(token.getTransactionType());
        jdata.setRet("DAY");
        jdata.setOrdersource("API");

        return jdata;
    }

    /**
     * Retrieves the Market Depth / Quotes (bp1 = best bid, sp1 = best ask). Direct connection.
     */
    public JSONObject getMarketQuotes(String exch, String token) {
        try {
            String jKey = getTokenForFlatTrade();
            Map<String, String> jData = new HashMap<>();
            jData.put("uid", "MALIT158");
            jData.put("exch", exch);
            jData.put("token", token);
            String body = "jData=" + objectMapper.writeValueAsString(jData) + "&jKey=" + jKey;

            String responseBody = webClient.post().uri(BASE_URL + "/GetQuotes")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (responseBody != null) {
                return new JSONObject(responseBody);
            }
        } catch (Exception e) {
            logger.error("[FLATTRADE-QUOTES] Error fetching quotes: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Modifies the price of an existing Limit Order THROUGH THE PROXY (static IP).
     * Note: FlatTrade requires the cumulative total order qty in the qty field.
     */
    public boolean modifyFlatTradeOrder(Token token, double newPrice, String orderId) {
        try {
            String key = getTokenForFlatTrade();

            JData jdata = new JData();
            jdata.setUid("MALIT158");
            jdata.setActid("MALIT158");
            jdata.setNorenordno(orderId);
            jdata.setExch(token.getExch_seg());
            jdata.setTsym(token.getSymbol());
            jdata.setQty(String.valueOf(token.getQuantity())); // Full cumulative quantity
            jdata.setPrctyp("LMT");
            jdata.setPrc(String.valueOf(newPrice));
            jdata.setRet("DAY");

            String jDataJson = objectMapper.writeValueAsString(jdata);
            String rawPayload = "jData=" + jDataJson + "&jKey=" + key;

            String responseBody = postOrderViaProxy(BASE_URL + "/ModifyOrder", rawPayload);

            if (responseBody != null) {
                JSONObject response = new JSONObject(responseBody);
                if ("Ok".equalsIgnoreCase(response.optString("stat"))) {
                    return true;
                } else {
                    logger.error("[FLATTRADE-MODIFY-ERROR] Failed to modify order {}: {}", orderId, response.optString("emsg"));
                }
            }
        } catch (Exception e) {
            logger.error("[FLATTRADE-MODIFY-CRITICAL] Error modifying order {}: {}", orderId, e.toString());
        }
        return false;
    }

    /**
     * SMART EXECUTION ENGINE:
     * 1. Fetches quantity from DB via StrategyRepo.
     * 2. Checks market depth (bp1/sp1) to determine current executable price.
     * 3. Submits limit order (through the proxy).
     * 4. Asynchronously polls order status & adjusts price if market moves away.
     *    Polling and quotes are direct; only place/modify use the proxy.
     */
    @Async
    public CompletableFuture<Void> executeSmartOrder(Token token, String instrumentToken) {
        try {
            // 🛑 MARKET TIMING CHECK: Ensure NSE is open (9:15 AM - 3:30 PM IST)
            if (!isMarketOpen()) {
                logger.warn("[SMART-ORDER] Market is currently closed. Execution aborted for {}.", token.getSymbol());
                return CompletableFuture.completedFuture(null);
            }

            // 1. Fetch configured strategy from DB
            Strategy strategy = strategyRepo.findByName("UPPER_CIRCUIT");
            // 🛑 STRICT CHECK: Ensure the strategy exists and active is "Y"
            if (strategy == null || !"Y".equalsIgnoreCase(strategy.getActive())) {
                logger.warn("[SMART-ORDER] Strategy for {} is NOT active (active='{}'). Aborting execution.",
                        token.getSymbol(), strategy != null ? strategy.getActive() : "null");
                return CompletableFuture.completedFuture(null);
            }

            // 🛑 STRICT CHECK: Ensure we have a valid quantity
            if (strategy.getQuantity() <= 0) {
                logger.error("[SMART-ORDER] Invalid quantity ({}) in DB for {}. Aborting.",
                        strategy.getQuantity(), token.getSymbol());
                return CompletableFuture.completedFuture(null);
            }

            // Lock in the DB quantity for this execution
            token.setQuantity(strategy.getQuantity());
            token.setToken(strategy.getToken());
            logger.info("[SMART-ORDER] Starting execution for {} (DB Total Qty: {})", token.getSymbol(), token.getQuantity());

            // 2. Fetch initial market depth
            JSONObject quotes = getMarketQuotes(token.getExch_seg(), token.getToken());
            if (quotes == null || !quotes.has("bp1") || !quotes.has("sp1")) {
                logger.error("[SMART-ORDER] Depth unavailable for {}. Aborting.", token.getSymbol());
                return CompletableFuture.completedFuture(null);
            }

            double currentTargetPrice = calculateBestPrice(token.getTransactionType(), quotes);
            if (currentTargetPrice <= 0) {
                logger.error("[SMART-ORDER] Invalid target price from quotes (Circuit hit or zero depth) for {}. Aborting.", token.getSymbol());
                return CompletableFuture.completedFuture(null);
            }

            token.setPrice(currentTargetPrice);
            token.setOrderType("LMT");

            // 3. Place Initial Limit Order (uses locked DB quantity)
            Token placedToken = PlaceOrderInFlatTrade(token);
            String orderId = placedToken != null ? placedToken.getOrderId() : null;

            if (orderId == null) {
                logger.error("[SMART-ORDER] Initial placement failed for {}.", token.getSymbol());
                return CompletableFuture.completedFuture(null);
            }

            // 4. Monitoring & Chase Loop
            boolean isFullyExecuted = false;
            int maxAttempts = 120; // 4 minutes timeout window (120 x 2 seconds)
            int attempts = 0;

            while (!isFullyExecuted && attempts < maxAttempts) {
                Thread.sleep(2000);
                attempts++;

                JSONObject orderDetails = getIndividualOrderDetails(orderId);
                if (orderDetails == null) continue;

                String status = orderDetails.optString("status", "").toUpperCase();
                int filledQty = orderDetails.optInt("fillshares", 0);

                // Exit condition 1: filled quantity matches configured DB quantity
                if ("COMPLETE".equals(status) || filledQty >= token.getQuantity()) {
                    logger.info("[SMART-ORDER-SUCCESS] {} fully executed! Total Filled Qty: {}", token.getSymbol(), filledQty);
                    isFullyExecuted = true;
                    break;
                }

                // Exit condition 2: terminal failure/cancellation
                if (List.of("REJECTED", "CANCELED", "CANCELLED").contains(status)) {
                    logger.warn("[SMART-ORDER-ENDED] Order {} ended with status: {}. Halting chase.", orderId, status);
                    break;
                }

                // If still pending/partially filled, check for price divergence
                quotes = getMarketQuotes(token.getExch_seg(), instrumentToken);
                if (quotes != null && quotes.has("bp1") && quotes.has("sp1")) {
                    double newTargetPrice = calculateBestPrice(token.getTransactionType(), quotes);

                    // If market moved, update the order
                    if (newTargetPrice > 0 && newTargetPrice != currentTargetPrice) {
                        logger.info("[SMART-ORDER-MODIFY] Market moved for {}. Modifying Order {} from {} to {}",
                                token.getSymbol(), orderId, currentTargetPrice, newTargetPrice);

                        // modification uses token.getQuantity() which is locked to the DB total
                        boolean modified = modifyFlatTradeOrder(token, newTargetPrice, orderId);
                        if (modified) {
                            currentTargetPrice = newTargetPrice;
                        }
                    }
                }
            }

            if (!isFullyExecuted) {
                logger.warn("[SMART-ORDER-TIMEOUT] Polling expired for order ID {}. Check order book for remaining qty.", orderId);
            }

        } catch (Exception e) {
            logger.error("[SMART-ORDER-CRITICAL] Execution failed for {}: {}", token.getSymbol(), e.getMessage(), e);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Helper to check if the NSE market is currently open.
     * NSE Hours: 9:15 AM to 3:30 PM IST, Monday - Friday.
     */
    private boolean isMarketOpen() {
        ZonedDateTime now = ZonedDateTime.now(IST);

        // 1. Check if it's a weekend
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }

        // 2. Check if current time is between 9:15 AM and 3:30 PM
        LocalTime currentTime = now.toLocalTime();
        LocalTime marketOpen = LocalTime.of(9, 15);
        LocalTime marketClose = LocalTime.of(15, 30);

        return !currentTime.isBefore(marketOpen) && currentTime.isBefore(marketClose);
    }

    /**
     * Helper to resolve the best executable price based on order side:
     * - BUY: match lowest available seller (sp1)
     * - SELL: match highest available buyer (bp1)
     */
    private double calculateBestPrice(String transactionType, JSONObject quotes) {
        String bp1Str = quotes.optString("bp1", "0");
        String sp1Str = quotes.optString("sp1", "0");

        if ("B".equalsIgnoreCase(transactionType) || "BUY".equalsIgnoreCase(transactionType)) {
            return Double.parseDouble(sp1Str);
        } else {
            return Double.parseDouble(bp1Str);
        }
    }
}