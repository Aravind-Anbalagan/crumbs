package com.crumbs.trade.broker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.models.User;
import com.warrenstrange.googleauth.GoogleAuthenticator;

@Component
public class AngelOne {

    private static final Logger logger =
            LoggerFactory.getLogger(AngelOne.class);

    private SmartConnect smartConnect;
    private User user;

    // 🔐 Credentials kept inside class
    //private final String apiKey = "7HklvkfL";
    private final String apiKey = "7d0kJJe7";
    private final String clientID = "R705672";
    private final String clientPass = "8889";
    private final String totpKey = "A5EK2EGRTGRG7DOKSJI6SZG66Q";

    /**
     * Login and return authenticated SmartConnect
     */
    public synchronized SmartConnect signIn() {

        try {

            // Reuse existing session if available
            if (smartConnect != null && user != null) {
                return smartConnect;
            }

            logger.info("Logging in to Angel One...");

            GoogleAuthenticator gAuth = new GoogleAuthenticator();
            smartConnect = new SmartConnect(apiKey);

            int retryCount = 0;
            int maxRetry = 120;

            while (retryCount < maxRetry) {

                String code = String.valueOf(
                        gAuth.getTotpPassword(totpKey)
                );

                try {
                    user = smartConnect.generateSession(
                            clientID,
                            clientPass,
                            code
                    );

                    if (user != null) {

                        smartConnect.setAccessToken(user.getAccessToken());
                        smartConnect.setUserId(user.getUserId());

                        logger.info("Angel Login Successful after {} attempt(s)", retryCount + 1);
                        return smartConnect;
                    }

                } catch (Exception ex) {
                    logger.warn("Login attempt {} failed: {}", retryCount + 1, ex.getMessage());
                }

                retryCount++;

                try {
                    Thread.sleep(1000); // wait 1 sec before retry
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Login retry interrupted", ie);
                }
            }

            throw new RuntimeException("Angel session generation failed after " + maxRetry + " attempts");

        } catch (Exception e) {
            logger.error("Angel Login Failed", e);
            throw new RuntimeException("Angel Login Failed", e);
        }
    }


    /**
     * Force fresh login (use during WebSocket reconnect)
     */
    public synchronized void forceReLogin() {
        logger.warn("Forcing Angel re-login...");
        smartConnect = null;
        user = null;
        signIn();
    }

    /**
     * Get Feed Token for WebSocket
     */
    public synchronized String getFeedToken() {

        if (user == null) {
            signIn();
        }

        return user.getFeedToken();
    }

    /**
     * Get authenticated SmartConnect safely
     */
    public synchronized SmartConnect getSmartConnect() {

        if (smartConnect == null || user == null) {
            signIn();
        }

        return smartConnect;
    }
}
