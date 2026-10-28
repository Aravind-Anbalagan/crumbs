package com.crumbs.trade.broker;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.models.User;
import com.crumbs.trade.broker.BrokerProxyClient;
import com.warrenstrange.googleauth.GoogleAuthenticator;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class AngelOne {

    private static final Logger logger = LoggerFactory.getLogger(AngelOne.class);

    private SmartConnect smartConnect;
    private User user;

    @Autowired
    private BrokerProxyClient proxyClient;

    @Value("${STATIC_IP:${PROXY_HOST:127.0.0.1}}")
    private String staticIp;

    // NOTE: placeholder values; move real values to environment variables
    private final String apiKey   = "7d0kJJe7";
    private final String clientID = "R705672";
    private final String clientPass = "8889";
    private final String totpKey  = "A5EK2EGRTGRG7DOKSJI6SZG66Q";

    /**
     * Sets explicit system properties so underlying HTTP clients (like OkHttp/Apache)
     * abort connection attempts after 10 seconds instead of hanging forever.
     */
    private void applyNetworkTimeouts() {
        System.setProperty("sun.net.client.defaultConnectTimeout", "10000"); // 10 seconds
        System.setProperty("sun.net.client.defaultReadTimeout", "15000");    // 15 seconds
    }

    /**
     * Login with exponential backoff (max 5 attempts).
     *
     * Login passes through the BrokerProxyClient to bind the
     * JWT Token to the DigitalOcean IP, preventing AG8003 IP Mismatch errors.
     */
    public synchronized SmartConnect signIn() {
        try {
            if (smartConnect != null && user != null) {
                return smartConnect;
            }

            applyNetworkTimeouts();

            logger.info("Logging in to Angel One (VIA PROXY for IP Binding)...");

            GoogleAuthenticator gAuth = new GoogleAuthenticator();
            smartConnect = new SmartConnect(apiKey);

            int retryCount = 0;
            int maxRetry   = 5;
            long waitTime  = 2000;

            while (retryCount < maxRetry) {
                String code = String.valueOf(gAuth.getTotpPassword(totpKey));

                try {
                    // 1. Build the Manual Login Request
                    JSONObject loginBody = new JSONObject();
                    loginBody.put("clientcode", clientID);
                    loginBody.put("password", clientPass);
                    loginBody.put("totp", code);

                    // USING official angelbroking.com domain AND User-Agent header
                    HttpRequest loginReq = HttpRequest.newBuilder(URI.create("https://apiconnect.angelbroking.com/rest/auth/angelbroking/user/v1/loginByPassword"))
                            .timeout(Duration.ofSeconds(15))
                            .POST(HttpRequest.BodyPublishers.ofString(loginBody.toString()))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .header("X-UserType", "USER")
                            .header("X-SourceID", "WEB")
                            .header("X-ClientLocalIP", "127.0.0.1")
                            .header("X-ClientPublicIP", staticIp)
                            .header("X-MACAddress", "00:00:00:00:00:00")
                            .header("X-PrivateKey", apiKey)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                            .build();

                    // 2. Send Login Request through the Proxy
                    HttpResponse<String> resp = proxyClient.sendOrder(loginReq);
                    JSONObject respJson = new JSONObject(resp.body());

                    if (respJson.optBoolean("status", false)) {
                        JSONObject data = respJson.getJSONObject("data");

                        // 3. Manually populate the SDK User object
                        user = new User();
                        user.setAccessToken(data.getString("jwtToken"));
                        user.setFeedToken(data.getString("feedToken"));
                        user.setRefreshToken(data.getString("refreshToken"));
                        user.setUserId(clientID);

                        // 4. Update SmartConnect State
                        smartConnect.setAccessToken(user.getAccessToken());
                        smartConnect.setUserId(clientID);

                        logger.info("✅ Proxied Angel Login Successful on attempt {}", retryCount + 1);
                        return smartConnect;
                    } else {
                        logger.warn("⚠️ Login attempt {}/{} failed from broker: {}",
                                retryCount + 1, maxRetry, respJson.optString("message"));
                    }
                } catch (Exception ex) {
                    logger.warn("⚠️ Login attempt {}/{} failed: {}", retryCount + 1, maxRetry, ex.getMessage());
                }

                retryCount++;
                if (retryCount < maxRetry) {
                    logger.info("Waiting {}ms before next retry...", waitTime);
                    Thread.sleep(waitTime);
                    waitTime *= 2;
                }
            }
            throw new RuntimeException("Angel session generation failed after " + maxRetry + " attempts.");

        } catch (Exception e) {
            // Reset so the next call starts from a clean state
            smartConnect = null;
            user = null;
            logger.error("❌ Angel Login Failed", e);
            throw new RuntimeException("Angel Login Failed", e);
        }
    }

    public synchronized void forceReLogin() {
        logger.warn("Forcing Angel re-login...");
        smartConnect = null;
        user = null;
        signIn();
    }

    public synchronized String getFeedToken() {
        if (user == null) {
            signIn();
        }
        return user.getFeedToken();
    }

    public synchronized SmartConnect getSmartConnect() {
        if (smartConnect == null || user == null) {
            signIn();
        }
        return smartConnect;
    }

    /** Access token for custom REST calls (e.g. orders through the proxy client). */
    public synchronized String getAccessToken() {
        if (user == null) {
            signIn();
        }
        return user.getAccessToken();
    }

    public String getApiKey() {
        return apiKey;
    }
}