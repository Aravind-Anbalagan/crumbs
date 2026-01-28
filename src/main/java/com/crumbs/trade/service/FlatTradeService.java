package com.crumbs.trade.service;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.controller.StrangleController;
import com.crumbs.trade.dto.APIResponse;
import com.crumbs.trade.dto.BrokerAuthConfig;
import com.crumbs.trade.dto.FlatTradeLtpResponse;
import com.crumbs.trade.dto.FlatTradeQuoteResponse;
import com.crumbs.trade.dto.JData;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.utility.Utility;
import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;

import org.apache.commons.codec.binary.Base32;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Service
public class FlatTradeService {
	private static final Logger logger = LogManager.getLogger(FlatTradeService.class);
	private static final String BASE_URL = "https://piconnect.flattrade.in/PiConnectTP"; 
	private static final String MARKET_URL =
	        "https://piconnect.flattrade.in/PiConnectAPI";
	private static final String ORDER_API_URL = "/PlaceOrder";
    private static final String API_KEY = "GHUDWU53H32MTHPA536Q32WR";

    private static final String USER_ID = "MALIT158";
    private static final String PASSWORD = "Titanic@123";
    private static final String TOTP_SECRET = "6JY737J3P2ZG25665L37CI3Q3D44RQ5I"; // copied from flattrade site
    private static final String APP_KEY = "24d7ba25364447109e9880c6ae7e0d14";
    private static final String API_SECRET = "2025.7cd53caa0af5444cb084056fd6f5cb91925b3f1f3dd7ff21"; // copied from flattrade site

   
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
	WebClient webClient;
    @Autowired
    private BrokerConfigService brokerConfigService;
	public void PlaceOrderInFlatTrade(Token token) throws SmartAPIException, Exception {

		String key = getTokenForFlatTrade();	
		//Token token= new Token();
		if(key!=null)
		{
			token.setExch_seg(token.getExch_seg());
			token.setSymbol(Utility.normalizeToken(token.getSymbol()));
			token.setTransactionType(token.getTransactionType());
			token.setQuantity(token.getQuantity());
			String url = "https://piconnect.flattrade.in/PiConnectTP/PlaceOrder";
			APIResponse apiResponse=callFlatTrade(setJDataForOrder(token), key, url);
		}
		else
		{
			logger.error("Failed to get the key from FlatTrade");
		}
		
		
		//flatTradeService.placeOrder();
	}
	
	public JData setJDataForSearch(String name,String exch)
	{
		JData jdata = new JData();
		jdata.setUid("MALIT158");
		jdata.setStext(name);
		jdata.setExch(exch);
		return jdata;
	}
	
	public JData setJDataForOrder(Token token)
	{
		JData jdata = new JData();
		jdata.setUid("MALIT158");
		jdata.setActid("MALIT158");
		jdata.setExch(token.getExch_seg());
		jdata.setTsym(token.getSymbol());
		jdata.setQty(String.valueOf(token.getQuantity()));
		//jdata.setMkt_protection("5");
		jdata.setPrc("0");
		jdata.setDscqty("0");
		jdata.setPrd("I"); //Intraday
		jdata.setTrantype(token.getTransactionType());
		jdata.setPrctyp("MKT");
		jdata.setRet("DAY");
		jdata.setOrdersource("API");
		return jdata;
		
	}
	
	public String getTokenForFlatTrade() throws Exception {

	    BrokerAuthConfig cfg = brokerConfigService.getFlatTradeConfig();

	    String hashedPassword = generateSHA256(cfg.getPassword());
	    String totp = generateTOTP(cfg.getTotpSecret());

	    String sid = fetchSessionId();
	    if (sid == null || sid.isEmpty()) {
	        throw new RuntimeException("Failed to get session ID");
	    }

	    String setSessionUrl =
	            "https://auth.flattrade.in/?app_key=" + cfg.getApiKey() + "&sid=" + sid;
	    sendGet(setSessionUrl);

	    JSONObject authPayload = new JSONObject();
	    authPayload.put("UserName", cfg.getUserId());
	    authPayload.put("Password", hashedPassword);
	    authPayload.put("PAN_DOB", totp);
	    authPayload.put("APIKey", cfg.getApiKey());
	    authPayload.put("Sid", sid);
	    authPayload.put("Source", "AUTHPAGE");

	    String response = sendPost(
	            "https://authapi.flattrade.in/ftauth",
	            authPayload.toString()
	    );

	    JSONObject json = new JSONObject(response);
	    String redirectURL = json.optString("RedirectURL");

	    if (redirectURL == null || !redirectURL.contains("code=")) {
	        throw new RuntimeException("Redirect URL not received");
	    }

	    String requestCode = URLDecoder.decode(
	            redirectURL.split("code=")[1].split("&")[0],
	            StandardCharsets.UTF_8
	    );

	    String apiSecretHash =
	            generateSHA256(cfg.getApiKey() + requestCode + cfg.getApiSecret());

	    JSONObject tokenPayload = new JSONObject();
	    tokenPayload.put("api_key", cfg.getApiKey());
	    tokenPayload.put("request_code", requestCode);
	    tokenPayload.put("api_secret", apiSecretHash);

	    String tokenResponse = sendPost(
	            "https://authapi.flattrade.in/trade/apitoken",
	            tokenPayload.toString()
	    );

	    JSONObject tokenJson = new JSONObject(tokenResponse);

	    if (!"Ok".equalsIgnoreCase(tokenJson.optString("stat"))) {
	        throw new RuntimeException("Token generation failed: " +
	                tokenJson.optString("emsg"));
	    }

	    return tokenJson.getString("token");
	}

    
    public APIResponse callFlatTrade(JData jData,String jKey,String url) throws JsonProcessingException, UnsupportedEncodingException {
       

        try {
            // Convert JData object to raw JSON string
            String jDataJson = objectMapper.writeValueAsString(jData);

            // Construct request body exactly as Flattrade expects
            String requestBody = "jData=" + jDataJson + "&jKey=" + jKey;
            System.out.println("Request Body: " + requestBody); // Debug

            // Call the Flattrade PlaceOrder API
            APIResponse response = webClient.post()
            	    .uri(new URI(url))
            	    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            	    .bodyValue(requestBody)
            	    .retrieve()
            	    .onStatus(HttpStatusCode::isError, clientResponse ->
            	        clientResponse.bodyToMono(String.class)
            	            .map(errorBody -> new RuntimeException("API Error: " + errorBody))
            	            .flatMap(Mono::error)
            	    )
            	    .bodyToMono(APIResponse.class)
            	    .block();
            logger.info("Order place successfully in FlatTrade");
            return response;

        } catch (Exception e) {
        	logger.error("Order placement failed in FlatTrade : {}" ,e.getMessage());
            return null;
        }
    }
    
    private static String fetchSessionId() throws IOException {
        URL url = new URL("https://authapi.flattrade.in/auth/session");
        HttpsURLConnection con = (HttpsURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        setCommonHeaders(con);
        con.setDoOutput(true);

        try (OutputStream os = con.getOutputStream()) {
            byte[] input = "{}".getBytes(StandardCharsets.UTF_8);
            os.write(input);
        }

        return readResponse(con);
    }

    private static void setCommonHeaders(HttpURLConnection con) {
        con.setRequestProperty("User-Agent", "Mozilla/5.0");
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("Accept-Encoding", "gzip, deflate, br");
        con.setRequestProperty("Connection", "keep-alive");
        con.setRequestProperty("Referer", "https://auth.flattrade.in/");
        con.setRequestProperty("Content-Type", "application/json");
    }

    private static String sendGet(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        setCommonHeaders(con);
        return readResponse(con);
    }

    private static String sendPost(String urlStr, String jsonPayload) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        setCommonHeaders(con);
        con.setDoOutput(true);

        try (OutputStream os = con.getOutputStream()) {
            byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
            os.write(input);
        }

        return readResponse(con);
    }

    private static String readResponse(HttpURLConnection con) throws IOException {
        int code = con.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? con.getInputStream() : con.getErrorStream();
        BufferedReader in = new BufferedReader(new InputStreamReader(is));
        StringBuilder content = new StringBuilder();
        String inputLine;
        while ((inputLine = in.readLine()) != null) {
            content.append(inputLine);
        }
        in.close();
        return content.toString();
    }

    private static String generateSHA256(String text) throws Exception {
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

    private static String generateTOTP(String base32Secret) throws Exception {
        Base32 base32 = new Base32();
        byte[] keyBytes = base32.decode(base32Secret);
        SecretKey secretKey = new SecretKeySpec(keyBytes, "HmacSHA1");
        TimeBasedOneTimePasswordGenerator totp = new TimeBasedOneTimePasswordGenerator();
        Instant now = Instant.now();
        return String.format("%06d", totp.generateOneTimePassword(secretKey, now));
    }
    
    public BigDecimal getCurrentPrice(String exch, String token) {

        try {
            String jKey = getTokenForFlatTrade();
            if (jKey == null) {
                logger.error("Flattrade token is null");
                return null;
            }

            Map<String, String> jData = new HashMap<>();
            jData.put("uid", USER_ID);
            jData.put("exch", exch);
            jData.put("token", token);

            String jDataJson = objectMapper.writeValueAsString(jData);
            String body = "jData=" + jDataJson + "&jKey=" + jKey;

            FlatTradeLtpResponse response = webClient.post()
                    .uri(BASE_URL + "/GetLTP")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(FlatTradeLtpResponse.class)
                    .block();

            if (response != null && "Ok".equalsIgnoreCase(response.getStat())) {
                return new BigDecimal(response.getLtp());
            }

        } catch (Exception e) {
            logger.error("Failed to fetch LTP from Flattrade", e);
        }

        return null;
    }
    
    public Mono<BigDecimal> getLtpFromFlatTradeReactive(String exch, String token) {
        return Mono.fromCallable(() -> {
            String jKey = getTokenForFlatTrade();
            if (jKey == null || jKey.isEmpty()) {
                throw new IllegalStateException("FlatTrade token is null or empty");
            }
            return jKey;
        })
        .flatMap(jKey -> {
            Map<String, String> jData = new HashMap<>();
            jData.put("uid", USER_ID);
            jData.put("exch", exch);
            jData.put("token", token);
            
            try {
                String jDataJson = objectMapper.writeValueAsString(jData);
                String body = "jData=" + jDataJson + "&jKey=" + jKey;
                
                return webClient.post()
                        .uri(BASE_URL + "/GetQuotes")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(FlatTradeQuoteResponse.class)
                        .flatMap(response -> {
                            if ("Ok".equalsIgnoreCase(response.getStat())) {
                                return Mono.just(new BigDecimal(response.getLp()));
                            }
                            logger.error("GetQuotes failed: {}", response.getEmsg());
                            return Mono.error(new RuntimeException("API error: " + response.getEmsg()));
                        });
            } catch (JsonProcessingException e) {
                return Mono.error(new RuntimeException("Failed to serialize request", e));
            }
        })
        .retryWhen(Retry.backoff(5, Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(10))
                .filter(throwable -> 
                    throwable instanceof WebClientResponseException.TooManyRequests
                    || throwable instanceof WebClientResponseException
                    || throwable instanceof IllegalStateException
                    || throwable instanceof RuntimeException
                )
                .doBeforeRetry(retrySignal -> 
                    logger.warn("Retrying getLtpFromFlatTrade... attempt {} - Reason: {}", 
                               retrySignal.totalRetries() + 1, 
                               retrySignal.failure().getMessage())
                )
        )
        .doOnError(e -> logger.error("Exception while fetching LTP for exch={}, token={}", 
                                    exch, token, e))
        .onErrorResume(e -> {
            logger.error("All retries exhausted, returning empty for exch={}, token={}", exch, token);
            return Mono.empty(); // ✅ Returns empty Mono, not null
        });
    }

    public BigDecimal getLtpFromFlatTrade(String exch, String token) {
        BigDecimal result = getLtpFromFlatTradeReactive(exch, token)
                .block(); // Returns null if Mono is empty
        
        if (result == null) {
            logger.error("getLtpFromFlatTrade returned null for exch={}, token={}", exch, token);
        }
        
        return result;
    }

}
