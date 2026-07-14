package com.crumbs.trade.broker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.models.User;
import com.warrenstrange.googleauth.GoogleAuthenticator;

import java.io.IOException;
import java.net.*;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class AngelOne {

    private static final Logger logger = LoggerFactory.getLogger(AngelOne.class);

    private SmartConnect smartConnect;
    private User user;

    private final String apiKey   = "7d0kJJe7";
    private final String clientID = "R705672";
    private final String clientPass = "8889";
    private final String totpKey  = "A5EK2EGRTGRG7DOKSJI6SZG66Q";

    @Value("${PROXY_HOST:}")
    private String proxyHost;

    @Value("${PROXY_PORT:0}")
    private int proxyPort;

    private void applyProxy() {
        if (proxyHost != null && !proxyHost.isEmpty()) {
            final String host = proxyHost;
            final int port = proxyPort;

            ProxySelector.setDefault(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    return Collections.singletonList(
                        new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port))
                    );
                }

                @Override
                public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                    logger.error("Proxy connection failed to {}: {}", uri, ioe.getMessage());
                }
            });

            logger.info("Angel One proxy set: {}:{}", proxyHost, proxyPort);
        } else {
            logger.info("Angel One using direct connection (no proxy)");
        }
    }

    /**
     * Sets explicit system properties so underlying HTTP clients (like OkHttp/Apache) 
     * abort connection attempts after 10 seconds instead of hanging forever.
     */
    private void applyNetworkTimeouts() {
        System.setProperty("sun.net.client.defaultConnectTimeout", "10000"); // 10 seconds
        System.setProperty("sun.net.client.defaultReadTimeout", "15000");    // 15 seconds
    }

    /**
     * Login with Exponential Backoff (Max 5 retries over ~1 minute)
     */
    public synchronized SmartConnect signIn() {
        try {
            if (smartConnect != null && user != null) {
                return smartConnect;
            }

            applyProxy();
            applyNetworkTimeouts(); // ⬅️ NEW: Prevents infinite proxy/network hangs

            logger.info("Logging in to Angel One...");

            GoogleAuthenticator gAuth = new GoogleAuthenticator();
            smartConnect = new SmartConnect(apiKey);

            int retryCount = 0;
            int maxRetry   = 5;          // ⬅️ CHANGED: Was 120. 5 retries is plenty with backoff.
            long waitTime  = 2000;       // ⬅️ NEW: Start by waiting 2 seconds

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
                } catch (Exception ex) {
                    logger.warn("⚠️ Login attempt {}/{} failed: {}", retryCount + 1, maxRetry, ex.getMessage());
                }

                retryCount++;
                if (retryCount < maxRetry) {
                    logger.info("Waiting {}ms before next retry...", waitTime);
                    try {
                        Thread.sleep(waitTime);
                        waitTime *= 2; // ⬅️ NEW: Exponential backoff (2s -> 4s -> 8s -> 16s -> 32s)
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Login retry interrupted", ie);
                    }
                }
            }
            throw new RuntimeException("Angel session generation failed after " + maxRetry + " attempts.");

        } catch (Exception e) {
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
}