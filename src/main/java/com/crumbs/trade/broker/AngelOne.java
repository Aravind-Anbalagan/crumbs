package com.crumbs.trade.broker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.models.User;
import com.warrenstrange.googleauth.GoogleAuthenticator;

@Component
public class AngelOne {

    private static final Logger logger = LoggerFactory.getLogger(AngelOne.class);

    private SmartConnect smartConnect;
    private User user;

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
     * Login and market-data calls connect DIRECTLY. Angel One only requires the
     * registered static IP for order APIs (place / modify / cancel / GTT).
     * The old JVM-wide ProxySelector.setDefault(...) has been removed because it
     * pushed every HTTP client in the app (including Samco) through the proxy.
     */
    public synchronized SmartConnect signIn() {
        try {
            if (smartConnect != null && user != null) {
                return smartConnect;
            }

            applyNetworkTimeouts();

            logger.info("Logging in to Angel One (direct connection)...");

            GoogleAuthenticator gAuth = new GoogleAuthenticator();
            smartConnect = new SmartConnect(apiKey);

            int retryCount = 0;
            int maxRetry   = 5;
            long waitTime  = 2000;

            while (retryCount < maxRetry) {
                String code = String.valueOf(gAuth.getTotpPassword(totpKey));
                try {
                    user = smartConnect.generateSession(clientID, clientPass, code);
                    if (user != null && user.getAccessToken() != null) {
                        smartConnect.setAccessToken(user.getAccessToken());
                        smartConnect.setUserId(user.getUserId());
                        logger.info("✅ Angel Login Successful on attempt {}", retryCount + 1);
                        return smartConnect;
                    }
                    logger.warn("⚠️ Login attempt {}/{} returned no access token (user={})",
                            retryCount + 1, maxRetry, user);
                } catch (Exception ex) {
                    // Log the real cause (toString includes the exception class) plus stack trace
                    logger.warn("⚠️ Login attempt {}/{} failed: {}",
                            retryCount + 1, maxRetry, ex.toString(), ex);
                }

                retryCount++;
                if (retryCount < maxRetry) {
                    logger.info("Waiting {}ms before next retry...", waitTime);
                    try {
                        Thread.sleep(waitTime);
                        waitTime *= 2; // 2s -> 4s -> 8s -> 16s
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Login retry interrupted", ie);
                    }
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