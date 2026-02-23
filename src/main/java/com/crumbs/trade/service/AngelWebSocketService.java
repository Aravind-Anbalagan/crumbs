package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.smartstream.models.Depth;
import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import com.angelbroking.smartapi.smartstream.models.LTP;
import com.angelbroking.smartapi.smartstream.models.Quote;
import com.angelbroking.smartapi.smartstream.models.SmartStreamError;
import com.angelbroking.smartapi.smartstream.models.SmartStreamSubsMode;
import com.angelbroking.smartapi.smartstream.models.SnapQuote;
import com.angelbroking.smartapi.smartstream.models.TokenID;
import com.angelbroking.smartapi.smartstream.ticker.SmartStreamListener;
import com.angelbroking.smartapi.smartstream.ticker.SmartStreamTicker;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.StrategyRepo;

@Service
public class AngelWebSocketService {

    private static final Logger log = LoggerFactory.getLogger(AngelWebSocketService.class);

    private SmartStreamTicker smartStreamTicker;
    private SmartStreamListener listener;

    // Live LTP store: "MCX_FO_467013" → price
    private final Map<String, BigDecimal> latestLtpMap = new ConcurrentHashMap<>();

    // Store ExchangeType + token together so reconnect works for multi-part names like MCX_FO
    // Key: "MCX_FO_467013"  Value: TokenID(MCX_FO, "467013")
    private final Map<String, TokenID> subscribedTokens = new ConcurrentHashMap<>();

    // Track last tick time per key — for staleness detection
    private final Map<String, Long> lastTickTime = new ConcurrentHashMap<>();

    @Autowired
    private AngelOne angelOne;

    @Autowired
    StrategyRepo strategyRepo;

    @Autowired
    @Lazy
    private SseService sseService;

    private final String clientCode = "R705672";

    @PostConstruct
    public void startWebSocket() {
        try {
            log.info("Starting Angel WebSocket...");

            SmartConnect smartConnect = angelOne.getSmartConnect();
            String feedToken = angelOne.getFeedToken();
            log.info("FeedToken available: {}", feedToken != null);

            listener = new SmartStreamListener() {

                @Override
                public void onConnected() {
                    log.info("SmartStream CONNECTED successfully");
                    // Keep as fallback — subscribeDefaultInstruments also called via thread below
                    subscribeDefaultInstruments();
                }

                @Override
                public void onLTPArrival(LTP ltp) {
                    try {
                        String exchange = ltp.getExchangeType().name();
                        String token    = normalizeToken(ltp.getToken().getToken());
                        String key      = exchange + "_" + token;
                        long rawPrice   = ltp.getLastTradedPrice();

                        log.debug("RAW TICK | Key: {} | RawPrice: {}", key, rawPrice);

                        if (rawPrice == 0) {
                            log.warn("Ignoring zero LTP tick | {}", key);
                            return;
                        }

                        BigDecimal price = BigDecimal.valueOf(rawPrice, 2);
                        latestLtpMap.put(key, price);

                        // Update last tick timestamp for staleness detection
                        lastTickTime.put(key, System.currentTimeMillis());

                        Map<String, Object> payload = new HashMap<>();
                        payload.put("key",   key);
                        payload.put("price", price);
                        sseService.broadcast("ltp", payload);

                    } catch (Exception e) {
                        log.error("Error processing tick", e);
                    }
                }

                @Override
                public void onDisconnected() {
                    log.warn("SmartStream DISCONNECTED");
                    reconnect();
                }

                @Override
                public void onError(SmartStreamError error) {
                    log.error("SmartStream ERROR | {}", error);
                }

                @Override public void onQuoteArrival(Quote quote) {}
                @Override public void onSnapQuoteArrival(SnapQuote snapQuote) {}
                @Override public void onDepthArrival(Depth depth) {}
                @Override public void onPong() { log.debug("PONG received"); }
                @Override public SmartStreamError onErrorCustom() { return null; }
            };

            smartStreamTicker = new SmartStreamTicker(clientCode, feedToken, listener);
            smartStreamTicker.connect();
            log.info("WebSocket connect() invoked");

            // onConnected callback is unreliable in Angel SDK
            // Subscribe on background thread after giving socket 2s to establish
            Thread startupSubscribeThread = new Thread(() -> {
                try {
                    Thread.sleep(2000);
                    log.info("Subscribing default instruments after connect delay...");
                    subscribeDefaultInstruments();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            startupSubscribeThread.setDaemon(true);
            startupSubscribeThread.start();

        } catch (Exception e) {
            log.error("Error starting WebSocket", e);
        }
    }

    // Normalize token — strips prefix like "abc-467013" → "467013"
    private String normalizeToken(String rawToken) {
        if (rawToken == null) return "";
        if (rawToken.contains("-")) {
            rawToken = rawToken.substring(rawToken.indexOf("-") + 1);
        }
        return rawToken.trim();
    }

    // Subscribe dynamically
    public synchronized void subscribe(ExchangeType exchangeType, String token) {
        String normalizedToken = normalizeToken(token);
        String key = exchangeType.name() + "_" + normalizedToken;

        if (subscribedTokens.containsKey(key)) {
            log.info("Already subscribed | {}", key);
            return;
        }

        TokenID tokenId = new TokenID(exchangeType, normalizedToken);
        Set<TokenID> tokenSet = new HashSet<>();
        tokenSet.add(tokenId);

        smartStreamTicker.subscribe(SmartStreamSubsMode.LTP, tokenSet);

        // Store TokenID object so reconnect can re-subscribe correctly
        subscribedTokens.put(key, tokenId);

        log.info("Subscribed successfully | {}", key);
    }
    
    public synchronized void unsubscribeAll() {
        try {
            if (smartStreamTicker == null) {
                log.warn("WebSocket not initialized — cannot unsubscribe");
                return;
            }

            if (subscribedTokens.isEmpty()) {
                log.info("No subscribed tokens to unsubscribe");
                return;
            }

            Set<TokenID> tokenSet = new HashSet<>(subscribedTokens.values());

            smartStreamTicker.unsubscribe(SmartStreamSubsMode.LTP, tokenSet);

            log.info("Unsubscribed all tokens | Count: {}", tokenSet.size());

            // Clear all internal tracking
            subscribedTokens.clear();
            latestLtpMap.clear();
            lastTickTime.clear();

        } catch (Exception e) {
            log.error("Failed to unsubscribe all tokens", e);
        }
    }

    // LTP Getter
    public BigDecimal getLatestLTP(ExchangeType exchangeType, String token) {
        String normalizedToken = normalizeToken(token);
        String key = exchangeType.name() + "_" + normalizedToken;

        log.info("Fetching LTP | Key: {}", key);
        log.debug("Current LTP Map Snapshot | {}", latestLtpMap);

        BigDecimal price = latestLtpMap.get(key);
        if (price == null) {
            log.warn("LTP not available yet | {}", key);
            return BigDecimal.ZERO;
        }

        log.info("Returning LTP | {} | {}", key, price);
        return price;
    }

    // Get instrument SSE key
    public String getInstrumentKey(ExchangeType exchangeType, String token) {
        return exchangeType.name() + "_" + normalizeToken(token);
    }

    // Staleness detector — runs every 60s
    // If no tick received for a subscribed instrument in 60s, force resubscribe
    @Scheduled(fixedRate = 60000)
    public void checkTickFreshness() {
        if (subscribedTokens.isEmpty()) return;

        long now = System.currentTimeMillis();
        for (Map.Entry<String, TokenID> entry : subscribedTokens.entrySet()) {
            String key  = entry.getKey();
            Long   last = lastTickTime.get(key);

            if (last == null || (now - last) > 60000) {
                log.warn("No tick for {} in 60s — resubscribing...", key);
                try {
                    Set<TokenID> tokenSet = new HashSet<>();
                    tokenSet.add(entry.getValue());
                    smartStreamTicker.subscribe(SmartStreamSubsMode.LTP, tokenSet);
                    log.info("Resubscribed | {}", key);
                } catch (Exception e) {
                    log.error("Resubscribe failed for {} | {}", key, e.getMessage());
                }
            } else {
                log.debug("Tick freshness OK | {} | last tick {}ms ago", key, now - last);
            }
        }
    }

    // Reconnect on disconnect
    private void reconnect() {
        try {
            log.warn("Attempting reconnect in 3 seconds...");
            Thread.sleep(3000);

            angelOne.forceReLogin();
            String feedToken = angelOne.getFeedToken();

            smartStreamTicker = new SmartStreamTicker(clientCode, feedToken, listener);
            smartStreamTicker.connect();
            log.info("Reconnected successfully");

            // Wait for connection to establish then re-subscribe
            Thread.sleep(2000);

            // Re-subscribe using stored TokenID objects — correct for all exchange names
            for (Map.Entry<String, TokenID> entry : subscribedTokens.entrySet()) {
                Set<TokenID> tokenSet = new HashSet<>();
                tokenSet.add(entry.getValue());
                smartStreamTicker.subscribe(SmartStreamSubsMode.LTP, tokenSet);
                log.info("Re-subscribed | {}", entry.getKey());
            }

        } catch (Exception e) {
            log.error("Reconnect failed", e);
        }
    }

    @PreDestroy
    public void close() {
        if (smartStreamTicker != null) {
            smartStreamTicker.disconnect();
            log.info("WebSocket disconnected");
        }
    }

    // Subscribe default instruments from DB on connect
    private void subscribeDefaultInstruments() {
        try {
            Strategy nifty = strategyRepo.findByName("NIFTY");
            if (nifty != null) {
                subscribe(ExchangeType.NSE_FO, nifty.getToken());
            } else {
                log.warn("NIFTY strategy not found in DB");
            }

            Strategy crude = strategyRepo.findByName("CRUDEOIL");
            if (crude != null) {
                subscribe(ExchangeType.MCX_FO, crude.getToken());
            } else {
                log.warn("CRUDEOIL strategy not found in DB");
            }

            log.info("Default instruments subscribed | Tokens in map: {}", subscribedTokens.keySet());
        } catch (Exception e) {
            log.error("Default subscription failed", e);
        }
    }
}