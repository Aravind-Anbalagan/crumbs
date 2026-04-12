package com.crumbs.trade.service;

import com.crumbs.trade.dto.APIResponse;
import com.crumbs.trade.dto.BrokerAuthConfig;
import com.crumbs.trade.dto.FlatTradeLtpResponse;
import com.crumbs.trade.dto.FlatTradeQuoteResponse;
import com.crumbs.trade.dto.JData;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.utility.Utility;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class FlatTradeService {
    private static final Logger logger = LogManager.getLogger(FlatTradeService.class);
    private static final String BASE_URL = "https://piconnect.flattrade.in/PiConnectTP";
    
    // In-memory cache for the day
    private String cachedJKey = null;
    private Instant lastFetchTime = null;

    @Autowired
    private WebClient webClient;

    @Autowired
    private BrokerConfigService brokerConfigService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Main entry point for all API calls.
     * Logic: Check Cache -> If expired, look for a new Request Code in DB -> Exchange for Token.
     */
    public synchronized String getTokenForFlatTrade() throws Exception {
        if (isTokenValid()) {
            return cachedJKey;
        }

        logger.info("Token expired. Fetching fresh request_code from Database...");
        
        // 1. Fetch the code you manually saved in the DB
        BrokerAuthConfig cfg = brokerConfigService.getFlatTradeConfig();
        String requestCode = cfg.getRequestCode(); 

        if (requestCode == null || requestCode.isEmpty()) {
            throw new RuntimeException("No Request Code found in DB. Please login via browser first.");
        }

        // 2. Perform the Exchange (MUST happen from Cloud Server IP)
        return performTokenExchange(cfg, requestCode);
    }

    private String performTokenExchange(BrokerAuthConfig cfg, String requestCode) throws Exception {
        // Step 4: SHA-256 of (api_key + request_code + api_secret)
        String hashInput = cfg.getApiKey() + requestCode + cfg.getApiSecret();
        String apiSecretHash = generateSHA256(hashInput);

        JSONObject payload = new JSONObject();
        payload.put("api_key", cfg.getApiKey());
        payload.put("request_code", requestCode);
        payload.put("api_secret", apiSecretHash);

        logger.info("Exchanging code for token from Cloud IP...");
        String response = sendPost("https://authapi.flattrade.in/trade/apitoken", payload.toString());
        JSONObject json = new JSONObject(response);

        if ("Ok".equalsIgnoreCase(json.optString("stat"))) {
            this.cachedJKey = json.getString("token");
            this.lastFetchTime = Instant.now();
            
            // OPTIONAL: Clear the request_code in DB so it's not reused
            brokerConfigService.clearRequestCode(); 
            
            logger.info("Successfully generated JKey for MALIT158");
            return cachedJKey;
        } else {
            logger.error("Exchange failed. Server responded: {}", response);
            throw new RuntimeException("Token exchange failed: " + json.optString("emsg"));
        }
    }

    private boolean isTokenValid() {
        if (cachedJKey == null || lastFetchTime == null) return false;
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        ZonedDateTime lastFetch = lastFetchTime.atZone(ZoneId.of("Asia/Kolkata"));
        
        // Flattrade reset window (6 AM)
        if (now.getHour() >= 6) {
            ZonedDateTime cutoff = now.withHour(6).withMinute(0).withSecond(0).withNano(0);
            if (lastFetch.isBefore(cutoff)) return false;
        }
        return true;
    }

    // --- EXISTING METHODS (LTP, PlaceOrder, Search) ---

    public void PlaceOrderInFlatTrade(Token token) throws Exception {
        String key = getTokenForFlatTrade();
        if (key != null) {
            token.setSymbol(Utility.normalizeToken(token.getSymbol()));
            String url = BASE_URL + "/PlaceOrder";
            callFlatTrade(setJDataForOrder(token), key, url);
        }
    }

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
        } catch (Exception e) { return null; }
    }

    public JData setJDataForOrder(Token token) {
        JData jdata = new JData();
        jdata.setUid("MALIT158");
        jdata.setActid("MALIT158");
        jdata.setExch(token.getExch_seg());
        jdata.setTsym(token.getSymbol());
        jdata.setQty(String.valueOf(token.getQuantity()));
        jdata.setPrc("0");
        jdata.setPrd("I");
        jdata.setTrantype(token.getTransactionType());
        jdata.setPrctyp("MKT");
        jdata.setRet("DAY");
        jdata.setOrdersource("API");
        return jdata;
    }

    public APIResponse callFlatTrade(JData jData, String jKey, String url) throws JsonProcessingException {
        try {
            String body = "jData=" + objectMapper.writeValueAsString(jData) + "&jKey=" + jKey;
            return webClient.post().uri(new URI(url))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).bodyValue(body)
                    .retrieve().bodyToMono(APIResponse.class).block();
        } catch (Exception e) { return null; }
    }

    // --- UTILITIES ---

    private static String sendPost(String urlStr, String jsonPayload) throws IOException {
        URL url = new URL(urlStr);
        HttpsURLConnection con = (HttpsURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        con.setDoOutput(true);
        try (OutputStream os = con.getOutputStream()) { os.write(jsonPayload.getBytes(StandardCharsets.UTF_8)); }
        return readResponse(con);
    }

    private static String readResponse(HttpURLConnection con) throws IOException {
        int code = con.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? con.getInputStream() : con.getErrorStream();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) content.append(line);
            return content.toString();
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
}