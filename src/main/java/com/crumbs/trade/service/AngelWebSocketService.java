package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

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

    @Autowired
    private org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler;
    /**
     * ⬅️ CHANGED: Replaced @PostConstruct with @Async + @EventListener
     * This tells Spring to let the application fully start up and open port 8080,
     * then executes the broker connection safely in a background thread.
     */
    @org.springframework.scheduling.annotation.Async
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void startWebSocket() {
        
        if (!isTradingDay()) {
            log.info("Today is weekend. Skipping Angel WebSocket startup.");
            return;
        }
        try {
            log.info("🚀 Container booted successfully! Starting Angel WebSocket in background...");

            SmartConnect smartConnect = angelOne.getSmartConnect();
            String feedToken = angelOne.getFeedToken();
            log.info("FeedToken available: {}", feedToken != null);

            listener = new SmartStreamListener() {

                @Override
                public void onConnected() {
                    log.info("SmartStream CONNECTED successfully");
                    subscribeDefaultInstruments();
                }

                @Override
                public void onLTPArrival(LTP ltp) {
                    try {
                        String exchange = ltp.getExchangeType().name();
                        String token    = normalizeToken(ltp.getToken().getToken());
                        String key      = exchange + "_" + token;
                        long rawPrice   = ltp.getLastTradedPrice();

                        if (rawPrice == 0) {
                            log.warn("Ignoring zero LTP tick | {}", key);
                            return;
                        }

                        BigDecimal price = BigDecimal.valueOf(rawPrice, 2);
                        latestLtpMap.put(key, price);
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

            // ⬅️ CHANGED: Swapped unmanaged raw thread for Spring's taskScheduler 
            // This avoids creating stray platform threads that leak memory inside the container.
            taskScheduler.schedule(() -> {
                log.info("Running initial safety subscription check...");
                subscribeDefaultInstruments();
            }, java.time.Instant.now().plusSeconds(2));

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
            //log.info("Already subscribed | {}", key);
            return;
        }

        TokenID tokenId = new TokenID(exchangeType, normalizedToken);
        Set<TokenID> tokenSet = new HashSet<>();
        tokenSet.add(tokenId);

        smartStreamTicker.subscribe(SmartStreamSubsMode.LTP, tokenSet);

        // Store TokenID object so reconnect can re-subscribe correctly
        subscribedTokens.put(key, tokenId);

        //log.info("Subscribed successfully | {}", key);
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

        BigDecimal price = latestLtpMap.get(key);
        if (price == null) {
            log.warn("LTP not available yet | {}", key);
            return BigDecimal.ZERO;
        }

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
                    //log.info("Resubscribed | {}", key);
                } catch (Exception e) {
                    log.error("Resubscribe failed for {} | {}", key, e.getMessage());
                }
            } else {
                log.debug("Tick freshness OK | {} | last tick {}ms ago", key, now - last);
            }
        }
    }

    // Reconnect on disconnect
    private int retryCount = 0;

    private void reconnect() {
        try {
            retryCount++;
            // Calculate wait time: 3s, 10s, 30s, 60s...
            long waitTime = Math.min((long)Math.pow(3, retryCount) * 1000, 60000);
            log.warn("Rate limited. Retrying in {}ms (Attempt {})", waitTime, retryCount);
            
            Thread.sleep(waitTime);

            angelOne.forceReLogin();
            String feedToken = angelOne.getFeedToken();

            smartStreamTicker = new SmartStreamTicker(clientCode, feedToken, listener);
            smartStreamTicker.connect();
            
            // Reset retry count on success
            retryCount = 0; 
            log.info("Reconnected successfully");
            
            Thread.sleep(2000);
            // ... (resubscribe logic)
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
            // 1. NIFTY (NSE Derivatives)
            Strategy nifty = strategyRepo.findByName("NIFTY");
            if (nifty != null) {
                subscribe(ExchangeType.NSE_FO, nifty.getToken());
            }

            // 2. CRUDEOIL (MCX Derivatives)
            Strategy crude = strategyRepo.findByName("CRUDEOIL");
            if (crude != null) {
                subscribe(ExchangeType.MCX_FO, crude.getToken());
            }
            
            // 3. NIFTY INDEX (NSE Cash/Index)
            Strategy nifty_index = strategyRepo.findByName("NIFTY_INDEX");
            if (nifty_index != null) {
                subscribe(ExchangeType.NSE_CM, nifty_index.getToken());
            }

            // 4. SENSEX (BSE Derivatives)
            Strategy sensex = strategyRepo.findByName("SENSEX");
            if (sensex != null) {
                subscribe(ExchangeType.BSE_FO, sensex.getToken()); 
            }

            // 5. SENSEX INDEX (BSE Cash/Index)
            Strategy sensex_index = strategyRepo.findByName("SENSEX_INDEX");
            if (sensex_index != null) {
                subscribe(ExchangeType.BSE_CM, sensex_index.getToken());
            }

            // 6. NATURALGAS (MCX Derivatives)
            Strategy naturalgas = strategyRepo.findByName("NATURALGAS");
            if (naturalgas != null) {
                subscribe(ExchangeType.MCX_FO, naturalgas.getToken());
            }

            log.info("Default instruments subscribed | Tokens in map: {}", subscribedTokens.keySet());
        } catch (Exception e) {
            log.error("Default subscription failed", e);
        }
    }
    
    private boolean isTradingDay() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        DayOfWeek day = today.getDayOfWeek();
        return !(day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY);
    }
    
    public synchronized void unsubscribe(ExchangeType exchangeType, String token) {
        String normalizedToken = normalizeToken(token);
        String key = exchangeType.name() + "_" + normalizedToken;

        if (!subscribedTokens.containsKey(key)) return;

        TokenID tokenId = subscribedTokens.get(key);
        Set<TokenID> tokenSet = new HashSet<>();
        tokenSet.add(tokenId);

        smartStreamTicker.unsubscribe(SmartStreamSubsMode.LTP, tokenSet);
        
        subscribedTokens.remove(key);
        latestLtpMap.remove(key);
        lastTickTime.remove(key);
        
        log.info("Unsubscribed successfully | {}", key);
    }
}