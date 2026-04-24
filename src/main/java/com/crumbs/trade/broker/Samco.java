package com.crumbs.trade.broker;

import com.crumbs.trade.entity.Brokers;
import com.crumbs.trade.repo.BrokersRepo;

import in.samco.api.QuoteApi;
import in.samco.api.update.MultiQuoteAPI;
import in.samco.model.IndexDetailsResponse;
import in.samco.model.MultiQuoteResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class Samco {

    private static final Logger logger         = LoggerFactory.getLogger(Samco.class);
    private static final String BASE_URL       = "https://tradeapi.samco.in";
    private static final String BROKER_NAME    = "SAMCO";
    private static final int    MAX_RETRIES    = 3;
    private static final int    RETRY_DELAY_MS = 2000;

    @Autowired
    private BrokersRepo brokersRepository;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    // -------------------------------------------------------------------------
    // LOAD BROKER CONFIG FROM DB
    // -------------------------------------------------------------------------

    private Brokers getBrokerConfig() {
        return brokersRepository.findByBrokername(BROKER_NAME)
                .orElseThrow(() -> new RuntimeException(
                        "Broker config not found in DB for: " + BROKER_NAME));
    }

    // -------------------------------------------------------------------------
    // ONE-TIME SETUP  (human-assisted, done once per account)
    //
    //  Step 1: POST /admin/samco/request-otp
    //          → Samco sends OTP to your registered mobile/email
    //
    //  Step 2: POST /admin/samco/request-secret-key?otp=XXXX
    //          → Samco emails the Secret Key to your registered email
    //
    //  Step 3: Check email, copy the secret key
    //          POST /admin/samco/save-secret-key?key=YYYY
    //          → Saved to Brokers.apikey in DB
    //
    //  Step 4: POST /admin/samco/register-ip?primaryIp=X.X.X.X&secondaryIp=Y.Y.Y.Y
    //          → Registers your static IPs with Samco (required for order APIs)
    //
    //  After Step 4, daily auth is fully automatic — no human input needed.
    // -------------------------------------------------------------------------

    /**
     * Step 1 — Triggers an OTP to your registered mobile/email.
     */
    public void generateOtp() {
        Brokers broker = getBrokerConfig();
        String body = new JSONObject()
                .put("uid", broker.getUsername())
                .toString();
        JSONObject response = post("/otp/generateOtp", body, null);
        logger.info("OTP generation response: {}", response);
    }

    /**
     * Step 2 — Submits OTP; Samco emails the Secret Key to your registered email.
     * Does NOT return the key — check your inbox after calling this.
     */
    public void requestSecretKey(String otp) {
        Brokers broker = getBrokerConfig();
        String body = new JSONObject()
                .put("uid", broker.getUsername())
                .put("otp", otp)
                .toString();
        JSONObject response = post("/otp/secretKeyGenerator", body, null);
        if (!"Success".equals(response.optString("status"))) {
            throw new RuntimeException("Secret key request failed: " + response);
        }
        // Samco emails the key — nothing to parse from this response
        logger.info("Secret key dispatched to registered email. Check inbox and call saveSecretKey().");
    }

    /**
     * Step 3 — Saves the Secret Key (received via email) into Brokers.apikey in DB.
     * Call this once after receiving the key in your email.
     */
    public void saveSecretKey(String secretKey) {
        if (secretKey == null || secretKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Secret key must not be blank");
        }
        Brokers broker = getBrokerConfig();
        broker.setApikey(secretKey.trim());
        brokersRepository.save(broker);
        logger.info("Secret key saved to DB for broker: {}", BROKER_NAME);
    }

    /**
     * Step 4 — Registers static IP(s) with Samco. One-time activity.
     * Required for order placement APIs.
     *
     * @param primaryIp   your primary static IP (required)
     * @param secondaryIp your secondary/backup static IP (required)
     */
    public void registerStaticIp(String primaryIp, String secondaryIp) {
        if (primaryIp == null || primaryIp.trim().isEmpty()) {
            throw new IllegalArgumentException("Primary IP must not be blank");
        }
        Brokers broker = getBrokerConfig();

        JSONObject body = new JSONObject()
                .put("clientId", broker.getUsername())
                .put("primaryIp", primaryIp.trim())
                .put("secondaryIp", secondaryIp.trim())
                .put("password", broker.getPassword());

        JSONObject response = post("/ip/ipRegistration", body.toString(), null);

        if (!"Success".equals(response.optString("status"))) {
            throw new RuntimeException("IP registration failed: " + response);
        }
        logger.info("Static IP registered successfully. primaryIp={}, secondaryIp={}",
                primaryIp, secondaryIp);
    }

    /**
     * Updates registered static IP(s). Can only be done once per calendar week.
     *
     * @param primaryIp   new primary static IP (required)
     * @param secondaryIp new secondary/backup static IP (required)
     */
    public void updateStaticIp(String primaryIp, String secondaryIp) {
        if (primaryIp == null || primaryIp.trim().isEmpty()) {
            throw new IllegalArgumentException("Primary IP must not be blank");
        }
        Brokers broker = getBrokerConfig();

        JSONObject body = new JSONObject()
                .put("clientId", broker.getUsername())
                .put("primaryIp", primaryIp.trim())
                .put("secondaryIp", secondaryIp.trim())
                .put("password", broker.getPassword());

        JSONObject response = post("/ip/ipUpdate", body.toString(), null);

        if (!"Success".equals(response.optString("status"))) {
            throw new RuntimeException("IP update failed: " + response);
        }
        logger.info("Static IP updated successfully. primaryIp={}, secondaryIp={}",
                primaryIp, secondaryIp);
    }

    // -------------------------------------------------------------------------
    // DAILY AUTH  (call once every trading day on startup)
    // -------------------------------------------------------------------------

    /**
     * Generates a fresh Access Token using the stored Secret Key (Brokers.apikey),
     * then logs in and returns a session token valid for one trading day.
     *
     * Requires one-time setup to be complete (Brokers.apikey must be populated).
     *
     * @return sessionToken — pass this to all subsequent market-data / order APIs
     */
    public String getSamcoSession() {
        Brokers broker = getBrokerConfig();

        if (broker.getApikey() == null || broker.getApikey().isEmpty()) {
            throw new RuntimeException(
                    "Secret key not set. Complete one-time setup: " +
                    "POST /admin/samco/request-otp → /admin/samco/request-secret-key → /admin/samco/save-secret-key");
        }

        // Step 3 (daily): Generate Access Token
        String accessTokenBody = new JSONObject()
                .put("uid", broker.getUsername())
                .put("secretApiKey", broker.getApikey())
                .toString();
        JSONObject tokenResponse = post("/accessToken/token", accessTokenBody, null);
        String accessToken = tokenResponse.optString("accessToken");
        if (accessToken == null || accessToken.isEmpty()) {
            throw new RuntimeException("Access token generation failed: " + tokenResponse);
        }
        logger.info("Access token generated for {}", BROKER_NAME);

        // Step 4 (daily): Login
        String loginBody = new JSONObject()
                .put("userId", broker.getUsername())
                .put("password", broker.getPassword())
                .put("accessToken", accessToken)
                .toString();
        JSONObject loginResponse = post("/login", loginBody, null);
        String sessionToken = loginResponse.optString("sessionToken");
        if (sessionToken == null || sessionToken.isEmpty()) {
            throw new RuntimeException("Samco login failed: " + loginResponse);
        }

        logger.info("Samco session created successfully for {}", BROKER_NAME);
        return sessionToken;
    }

    // -------------------------------------------------------------------------
    // MARKET DATA
    // -------------------------------------------------------------------------

    public BigDecimal getNifty50Price(String sessionToken) {
        QuoteApi quoteApi = new QuoteApi();
        IndexDetailsResponse indexQuote = quoteApi.getIndexQuote(sessionToken, "NIFTY 50");
        if (indexQuote == null)
            throw new RuntimeException("IndexQuote API returned null response");
        if (!"Success".equals(indexQuote.getStatus()))
            throw new RuntimeException("IndexQuote API failed: " + indexQuote.getStatusMessage());
        if (indexQuote.getIndexDetails() == null || indexQuote.getIndexDetails().isEmpty())
            throw new RuntimeException("No index details in response");
        BigDecimal price = BigDecimal.valueOf(indexQuote.getIndexDetails().get(0).getSpotPrice());
        logger.info("Nifty 50 spot price: {}", price);
        return price;
    }

    public BigDecimal getLtp(String sessionToken, String exchange, String symbol) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                Map<String, List<String>> req = new HashMap<>();
                req.put(exchange, Arrays.asList(symbol));
                MultiQuoteResponse response = new MultiQuoteAPI().postMultiQuote(sessionToken, req);
                if (response == null)
                    throw new RuntimeException("MultiQuote API returned null response");
                BigDecimal ltp = extractLtpFromJson(response.toString());
                logger.info("LTP [{}/{}] {}:{} = {}", attempt, MAX_RETRIES, exchange, symbol, ltp);
                return ltp;
            } catch (Exception e) {
                lastException = e;
                logger.warn("getLtp attempt {}/{} failed for {}:{} — {}",
                        attempt, MAX_RETRIES, exchange, symbol, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    try { Thread.sleep(RETRY_DELAY_MS); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        throw new RuntimeException(
                "getLtp failed for " + exchange + ":" + symbol + " after " + MAX_RETRIES + " attempts",
                lastException);
    }

    // -------------------------------------------------------------------------
    // PRIVATE HELPERS
    // -------------------------------------------------------------------------

    private JSONObject post(String path, String jsonBody, String sessionToken) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            if (sessionToken != null)
                builder.header("Authorization", sessionToken);
            HttpResponse<String> resp =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 && resp.statusCode() != 201)
                throw new RuntimeException(
                        "HTTP " + resp.statusCode() + " from " + path + ": " + resp.body());
            return new JSONObject(resp.body());
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("POST " + path + " failed: " + e.getMessage(), e);
        }
    }

    private BigDecimal extractLtpFromJson(String jsonResponse) {
        JSONObject root = new JSONObject(jsonResponse);
        JSONArray quotes = root.getJSONArray("multiQuotes");
        if (quotes.length() == 0)
            throw new RuntimeException("No quote data available");
        BigDecimal ltp = new BigDecimal(quotes.getJSONObject(0).getString("lastTradePrice"));
        logger.info("LTP fetched: {}", ltp);
        return ltp;
    }
    
   
    /**
     * Fetches Intraday OHLC Candle Data directly via REST to support custom timeframes.
     * Supported intervals: "1", "5", "10", "15", "30", "60"
     */
    public String getIntradayCandleData(String sessionToken, String symbol, String exchange, 
                                        String fromDate, String toDate, String interval) {
        
        // 1. Log the exact raw inputs being passed into the method
        logger.info("Initiating Samco Candle fetch -> Symbol: {}, Exchange: {}, From: {}, To: {}, Interval: {}", 
                    symbol, exchange, fromDate, toDate, interval);

        try {
            // Dynamically build and securely encode URL parameters
            StringBuilder queryParams = new StringBuilder();
            queryParams.append("?symbolName=").append(URLEncoder.encode(symbol, StandardCharsets.UTF_8));
            
            if (exchange != null && !exchange.trim().isEmpty()) {
                queryParams.append("&exchange=").append(URLEncoder.encode(exchange, StandardCharsets.UTF_8));
            }
            if (fromDate != null && !fromDate.trim().isEmpty()) {
                // CRITICAL FIX: Replace '+' with '%20' for Samco's strict URL parser
                queryParams.append("&fromDate=").append(URLEncoder.encode(fromDate, StandardCharsets.UTF_8).replace("+", "%20"));
            }
            if (toDate != null && !toDate.trim().isEmpty()) {
                // CRITICAL FIX: Replace '+' with '%20' for Samco's strict URL parser
                queryParams.append("&toDate=").append(URLEncoder.encode(toDate, StandardCharsets.UTF_8).replace("+", "%20"));
            }
            if (interval != null && !interval.trim().isEmpty()) {
                queryParams.append("&interval=").append(URLEncoder.encode(interval, StandardCharsets.UTF_8));
            }

            String endpoint = BASE_URL + "/intraday/candleData" + queryParams.toString();

            // 2. Log the final URL. If it breaks, copy this exact string to Postman/Browser
            logger.info("🚀 Constructed Samco URL: {}", endpoint);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Accept", "application/json")
                    .header("x-session-token", sessionToken) 
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                // 3. Log the exact error body returned by Samco
                logger.error("❌ Samco API HTTP {} Error. Body: {}", response.statusCode(), response.body());
                throw new RuntimeException("HTTP " + response.statusCode() + " from candle API: " + response.body());
            }

            // 4. Log success with the payload size
            logger.info("✅ Successfully fetched {}-min candles for {}:{}. Response length: {} chars", 
                        interval, exchange, symbol, response.body().length());
            
            return response.body(); 

        } catch (Exception e) {
            logger.error("💥 REST getIntradayCandleData failed for {}: {}", symbol, e.getMessage());
            throw new RuntimeException("REST getIntradayCandleData failed for " + symbol, e);
        }
    }
}