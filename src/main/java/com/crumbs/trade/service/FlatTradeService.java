package com.crumbs.trade.service;

import com.crumbs.trade.controller.StrangleController;
import com.crumbs.trade.dto.APIResponse;
import com.crumbs.trade.dto.JData;
import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

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

@Service
public class FlatTradeService {
	private static final Logger logger = LogManager.getLogger(FlatTradeService.class);
	private static final String BASE_URL = "https://piconnect.flattrade.in/PiConnectTP"; 
	private static final String ORDER_API_URL = "/PlaceOrder";
    private static final String API_KEY = "GHUDWU53H32MTHPA536Q32WR";

    private static final String USER_ID = "MALIT158";
    private static final String PASSWORD = "Athiran*2020";
    private static final String TOTP_SECRET = "6JY737J3P2ZG25665L37CI3Q3D44RQ5I"; // copied from flattrade site
    private static final String APP_KEY = "24d7ba25364447109e9880c6ae7e0d14";
    private static final String API_SECRET = "2025.7cd53caa0af5444cb084056fd6f5cb91925b3f1f3dd7ff21"; // copied from flattrade site

   
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
	WebClient webClient;
    public String getTokenForFlatTrade() throws Exception
    {
    	  String hashedPassword = generateSHA256(PASSWORD);
          String totp = generateTOTP(TOTP_SECRET);
          String generatedToken = null;
          String sid = fetchSessionId();
          if (sid == null || sid.isEmpty()) {
              throw new RuntimeException("❌ Failed to get session ID.");
          }

          System.out.println("✅ SID: " + sid);

          String setSessionUrl = "https://auth.flattrade.in/?app_key=" + APP_KEY + "&sid=" + sid;
          sendGet(setSessionUrl);

          JSONObject authPayload = new JSONObject();
          authPayload.put("UserName", USER_ID);
          authPayload.put("Password", hashedPassword);
          authPayload.put("PAN_DOB", totp);
          authPayload.put("App", "");
          authPayload.put("ClientID", "");
          authPayload.put("Key", "");
          authPayload.put("APIKey", APP_KEY);
          authPayload.put("Sid", sid);
          authPayload.put("Override", "");
          authPayload.put("Source", "AUTHPAGE");

          String response = sendPost("https://authapi.flattrade.in/ftauth", authPayload.toString());
          JSONObject jsonResponse = new JSONObject(response);

          String redirectURL = jsonResponse.optString("RedirectURL", "");
          String request_code = null;

          if (!redirectURL.isEmpty()) {
              System.out.println("🔁 Redirecting to: " + redirectURL);
              String[] parts = redirectURL.split("code=");
              if (parts.length > 1) {
                  request_code = parts[1].split("&")[0];
                  request_code = URLDecoder.decode(request_code, StandardCharsets.UTF_8);
                  System.out.println("🔑 Request Code: " + request_code);
              }
          } else {
              System.out.println("❌ No redirect URL received.");
          }

          // Step 4: Exchange request_code for access token
          if (request_code != null) {
              String apiSecretHash = generateSHA256(APP_KEY + request_code + API_SECRET);

              JSONObject tokenPayload = new JSONObject();
              tokenPayload.put("api_key", APP_KEY);
              tokenPayload.put("request_code", request_code);
              tokenPayload.put("api_secret", apiSecretHash);

              String tokenResponse = sendPost("https://authapi.flattrade.in/trade/apitoken", tokenPayload.toString());
              JSONObject tokenJson = new JSONObject(tokenResponse);

              String token = tokenJson.optString("token", null);
              String client = tokenJson.optString("client", null);
              String status = tokenJson.optString("stat", "Fail");
              String emsg = tokenJson.optString("emsg", "");

              if ("Ok".equalsIgnoreCase(status)) {
                  System.out.println("✅ Token: " + token);
                  generatedToken = token;
                  FlatTradeService flatTradeService = new FlatTradeService();
              } else {
                  System.out.println("❌ Token generation failed: " + emsg);
              }
          }
		return generatedToken;
    }
    
    public APIResponse placeOrder(JData jData,String jKey) throws JsonProcessingException, UnsupportedEncodingException {
        String url = "https://piconnect.flattrade.in/PiConnectTP/PlaceOrder";

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
            return response;

        } catch (Exception e) {
        	logger.error("Order placement failed: {}" ,e.getMessage());
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
}
