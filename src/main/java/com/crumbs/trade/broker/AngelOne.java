package com.crumbs.trade.broker;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.models.User;
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

    private final String apiKey   = "7d0kJJe7";
    private final String clientID = "R705672";
    private final String clientPass = "8889";
    private final String totpKey  = "A5EK2EGRTGRG7DOKSJI6SZG66Q";

    private void applyNetworkTimeouts() {
        System.setProperty("sun.net.client.defaultConnectTimeout", "10000");
        System.setProperty("sun.net.client.defaultReadTimeout", "15000");
    }

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
                    JSONObject loginBody = new JSONObject();
                    loginBody.put("clientcode", clientID);
                    loginBody.put("password", clientPass);
                    loginBody.put("totp", code);

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

                    HttpResponse<String> resp = proxyClient.sendOrder(loginReq);

                    if (resp.body() == null || resp.body().isBlank()) {
                        throw new RuntimeException("Empty response body from Angel One");
                    }

                    JSONObject respJson = new JSONObject(resp.body());

                    if (respJson.optBoolean("status", false)) {
                        JSONObject data = respJson.getJSONObject("data");
                        user = new User();
                        user.setAccessToken(data.getString("jwtToken"));
                        user.setFeedToken(data.getString("feedToken"));
                        user.setRefreshToken(data.getString("refreshToken"));
                        user.setUserId(clientID);

                        smartConnect.setAccessToken(user.getAccessToken());
                        smartConnect.setUserId(clientID);

                        logger.info("✅ Proxied Angel Login Successful on attempt {}", retryCount + 1);
                        return smartConnect;
                    } else {
                        logger.warn("⚠️ Login attempt {}/{} failed from broker: {}", retryCount + 1, maxRetry, respJson.optString("message"));
                    }
                } catch (Exception ex) {
                    logger.warn("⚠️ Login attempt {}/{} failed: {}", retryCount + 1, maxRetry, ex.getMessage());
                }

                retryCount++;
                if (retryCount < maxRetry) {
                    Thread.sleep(waitTime);
                    waitTime *= 2;
                }
            }
            throw new RuntimeException("Angel session generation failed.");

        } catch (Exception e) {
            this.smartConnect = null;
            this.user = null;
            throw new RuntimeException("Angel Login Failed", e);
        }
    }

    public synchronized void forceReLogin() {
        logger.warn("Forcing Angel re-login...");
        this.smartConnect = null;
        this.user = null;
        try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        signIn();
    }

    public synchronized String getFeedToken() { if (user == null) signIn(); return user.getFeedToken(); }
    public synchronized SmartConnect getSmartConnect() { if (smartConnect == null || user == null) signIn(); return smartConnect; }
    public synchronized String getAccessToken() { if (user == null) signIn(); return user.getAccessToken(); }
    public String getApiKey() { return apiKey; }
}