package com.crumbs.trade.service;

import com.crumbs.trade.dto.*;
import com.crumbs.trade.utility.Utility;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class FlatTradeService {
    private static final Logger logger = LogManager.getLogger(FlatTradeService.class);
    private static final String BASE_URL = "https://piconnect.flattrade.in/PiConnectAPI";
    private static final String AUTH_URL = "https://authapi.flattrade.in/trade/apitoken";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Value("${PROXY_HOST:}")
    private String proxyHost;

    @Value("${PROXY_PORT:0}")
    private int proxyPort;

    private String cachedJKey = null;
    private Instant lastFetchTime = null;

    @Autowired
    private WebClient webClient;

    @Autowired
    private BrokerConfigService brokerConfigService;

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

        // 2. Setup Proxy for all outbound calls
        applyProxy();

        // 3. Check Database (Handles Railway Restarts)
        logger.info("[FLATTRADE] Token not in memory. Checking Database for persisted token...");
        BrokerAuthConfig cfg = brokerConfigService.getFlatTradeConfig();
        
        if (isTokenStillValid(cfg.getTokenDate()) && cfg.getApiToken() != null) {
            logger.info("[FLATTRADE] Found valid token in DB (Generated at: {}). Loading to memory.", cfg.getTokenDate());
            this.cachedJKey = cfg.getApiToken();
            this.lastFetchTime = cfg.getTokenDate();
            return cachedJKey;
        }

        // 4. If DB token is expired/missing, perform new API Exchange
        logger.warn("[FLATTRADE] No valid token found in DB/Memory. Starting API token exchange...");
        
        // Log the current IP to verify Proxy is working
        logger.info("[PROXY] Verifying outbound IP: {}", getPublicIP());

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

        logger.info("[FLATTRADE-API] Sending POST to {} | Code: {}", AUTH_URL, requestCode);
        
        String responseBody = sendPost(AUTH_URL, payload.toString());
        logger.info("[FLATTRADE-API] RAW RESPONSE: {}", responseBody);

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

    private void applyProxy() {
        if (proxyHost != null && !proxyHost.isEmpty()) {
            System.setProperty("https.proxyHost", proxyHost);
            System.setProperty("https.proxyPort", String.valueOf(proxyPort));
            
            ProxySelector.setDefault(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    return Collections.singletonList(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
                }
                @Override
                public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                    logger.error("[PROXY] Connection failed to {}: {}", uri, ioe.getMessage());
                }
            });
            logger.info("[PROXY] Routing via DigitalOcean Proxy: {}:{}", proxyHost, proxyPort);
        }
    }

    private String getPublicIP() {
        try {
            URL url = new URL("https://api.ipify.org");
            HttpsURLConnection con = (HttpsURLConnection) url.openConnection();
            con.setConnectTimeout(5000);
            try (BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
                return in.readLine();
            }
        } catch (Exception e) {
            return "IP check failed: " + e.getMessage();
        }
    }

    // --- NETWORK HELPERS ---

    private static String sendPost(String urlStr, String jsonPayload) throws IOException {
        URL url = new URL(urlStr);
        HttpsURLConnection con = (HttpsURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        con.setDoOutput(true);
        try (OutputStream os = con.getOutputStream()) {
            os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(con);
    }

    private static String readResponse(HttpURLConnection con) throws IOException {
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

    // --- EXISTING TRADING METHODS (LTP, PlaceOrder) ---

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
     * Updated to return the Token object with norenordno and price populated.
     */
    public Token PlaceOrderInFlatTrade(Token token) throws Exception {
        String key = getTokenForFlatTrade();
        if (key == null) throw new RuntimeException("Token generation failed.");

        // WARNING: Ensure token.getSymbol() contains the hyphen (e.g., "RELIANCE-EQ").
        // If your normalizeToken() removes the hyphen, do not use it here!

        // 1. Create the JSON string (Requires @JsonInclude(JsonInclude.Include.NON_NULL) on JData class)
        String jDataJson = objectMapper.writeValueAsString(setJDataForOrder(token));

        // 2. Build the raw unescaped string EXACTLY as FlatTrade wants it
        String rawPayload = "jData=" + jDataJson + "&jKey=" + key;

        logger.info("[FLATTRADE-ORDER] Sending Dynamic Payload: {}", rawPayload);

        try {
            // 3. Send with the contradictory header they require
            String responseBody = webClient.post().uri(BASE_URL + "/PlaceOrder")
                    .header("Content-Type", "application/json")
                    .bodyValue(rawPayload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

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

        } catch (WebClientResponseException e) {
            // 4. Always catch this so WebClient doesn't hide FlatTrade's error messages
            String actualErrorBody = e.getResponseBodyAsString();
            logger.error("[FLATTRADE-CRITICAL] HTTP {}. Server said: {}", e.getStatusCode(), actualErrorBody);
            throw new RuntimeException("FlatTrade API Rejected Request: " + actualErrorBody);
        } catch (Exception e) {
            logger.error("[FLATTRADE-CRITICAL] Request Failed: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Fetches exact execution details for a specific FlatTrade order.
     * Use this to get the 'avgprc' for your PnL calculations.
     */
    public JSONObject getIndividualOrderDetails(String orderId) {
        try {
            String jKey = getTokenForFlatTrade();
            Map<String, String> jData = new HashMap<>();
            jData.put("uid", "MALIT158"); // Your UID from the service
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
        jdata.setUid("MALIT158"); jdata.setActid("MALIT158");
        jdata.setExch(token.getExch_seg()); jdata.setTsym(token.getSymbol());
        jdata.setQty(String.valueOf(token.getQuantity())); jdata.setPrc("0");
        jdata.setPrd("I"); jdata.setTrantype(token.getTransactionType());
        jdata.setPrctyp("MKT"); jdata.setRet("DAY"); jdata.setOrdersource("API");
        return jdata;
    }
}