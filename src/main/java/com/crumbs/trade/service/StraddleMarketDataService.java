package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.text.ParseException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.dto.StraddlePremiumDto;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.utility.ConditionalLogger;
import com.crumbs.trade.utility.NSEWorkingDays;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StraddleMarketDataService {

    private static final Logger baseLogger = LoggerFactory.getLogger(StraddleMarketDataService.class);
    private final ConditionalLogger logger = new ConditionalLogger(baseLogger);

    private final PredictionService predictionService;

    // Rate Limiting State
    private static final long CANDLE_API_DELAY_MS = 1000;
    private volatile long lastCandleApiCall = 0;
    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long INITIAL_RETRY_DELAY_MS = 2000;

    public List<StraddlePremiumDto> getPriceForAllTheStrikesBatch(List<StraddlePremiumDto> strikeList, SmartConnect smartconnect, String exchange) {
        try {
            List<String> tokens = new ArrayList<>();
            for (StraddlePremiumDto dto : strikeList) {
                if (dto.getCeToken() != null) tokens.add(dto.getCeToken().getToken());
                if (dto.getPeToken() != null) tokens.add(dto.getPeToken().getToken());
            }
            if (tokens.isEmpty()) {
                logger.warn("No tokens to fetch prices for");
                return strikeList;
            }

            logger.info("Fetching prices for {} tokens", tokens.size());
            JSONObject payload = new JSONObject();
            payload.put("mode", "FULL");
            JSONObject map = new JSONObject();
            map.put(exchange, tokens);
            payload.put("exchangeTokens", map);

            JSONObject response = predictionService.callMarketDataWithRetry(smartconnect, payload);
            if (response == null) {
                logger.error("Null response from market data API");
                return strikeList;
            }

            JSONArray fetched = response.optJSONArray("fetched");
            if (fetched == null || fetched.length() == 0) {
                logger.error("No data fetched from market API");
                return strikeList;
            }
            int size = fetched.length();
            logger.info("Received {} price records from API", size);

			Map<String, BigDecimal> ltpMap = new HashMap<>(size);
			Map<String, BigDecimal> openMap = new HashMap<>(size);
			Map<String, BigDecimal> oIMap = new HashMap<>(size);
			Map<String, BigDecimal> volumeMap = new HashMap<>(size);
			Map<String, BigDecimal> highMap = new HashMap<>(size);
			Map<String, BigDecimal> lowMap = new HashMap<>(size);


            for (int i = 0; i < fetched.length(); i++) {
                JSONObject item = fetched.getJSONObject(i);
                String token = item.optString("symbolToken", null);
                if (token != null) {
                    ltpMap.put(token, item.optBigDecimal("ltp", BigDecimal.ZERO));
                    openMap.put(token, item.optBigDecimal("open", BigDecimal.ZERO));
                    oIMap.put(token, item.optBigDecimal("opnInterest", BigDecimal.ZERO));
                    volumeMap.put(token, item.optBigDecimal("tradeVolume", BigDecimal.ZERO));
                    highMap.put(token, item.optBigDecimal("high", BigDecimal.ZERO));
                    lowMap.put(token, item.optBigDecimal("low", BigDecimal.ZERO));
                }
            }

            for (StraddlePremiumDto dto : strikeList) {
                if (dto.getCeToken() != null) {
                    String t = dto.getCeToken().getToken();
                    dto.setCePrice(ltpMap.get(t));
                    dto.setCeOpenPrice(openMap.get(t));
                    dto.setCeOI(oIMap.get(t));
                    dto.setCeVolume(volumeMap.get(t));
                    dto.setCeHigh(highMap.get(t));
                    dto.setCeLow(lowMap.get(t));
                }
                if (dto.getPeToken() != null) {
                    String t = dto.getPeToken().getToken();
                    dto.setPePrice(ltpMap.get(t));
                    dto.setPeOpenPrice(openMap.get(t));
                    dto.setPeOI(oIMap.get(t));
                    dto.setPeVolume(volumeMap.get(t));
                    dto.setPeHigh(highMap.get(t));
                    dto.setPeLow(lowMap.get(t));
                }
            }
        } catch (Exception | SmartAPIException e) {
            logger.error("Batch FULL error", e);
        }
        return strikeList;
    }

    public void fetchPreviousDayDataForAllStrikes(List<StraddlePremiumDto> strikeList, SmartConnect smartConnect, Strategy strategy, Map<String, Map<String, BigDecimal>> prevHighMap, Map<String, Map<String, BigDecimal>> prevLowMap, Map<String, Map<String, BigDecimal>> prevCloseMap) {
        logger.info("=== FETCHING PREVIOUS DAY OHLC (ONE-TIME FOR {}) ===", strategy.getName());

        prevHighMap.putIfAbsent(strategy.getName(), new HashMap<>());
        prevLowMap.putIfAbsent(strategy.getName(), new HashMap<>());
        prevCloseMap.putIfAbsent(strategy.getName(), new HashMap<>());

        Map<String, BigDecimal> highCache = prevHighMap.get(strategy.getName());
        Map<String, BigDecimal> lowCache = prevLowMap.get(strategy.getName());
        Map<String, BigDecimal> closeCache = prevCloseMap.get(strategy.getName());

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate tradingDate = NSEWorkingDays.isNSEWorkingDay(today) ? today : NSEWorkingDays.getLastWorkingDay(today);
        LocalDate previousWD = NSEWorkingDays.getLastWorkingDay(tradingDate);
        LocalDate dayBeforePrevWD = NSEWorkingDays.getLastWorkingDay(previousWD); 

        String fromDate = dayBeforePrevWD + " 15:30"; 
        String toDate = previousWD + " 15:30"; 

        logger.info("Prev day date range -> from={} to={}", fromDate, toDate);
        int successCount = 0, failureCount = 0;

        for (StraddlePremiumDto dto : strikeList) {
            if (dto.getCeToken() != null) {
                boolean ok = fetchAndCacheOHLC(smartConnect, strategy, dto.getCeToken().getToken(), fromDate, toDate, highCache, lowCache, closeCache, dto::setCePrevHigh, dto::setCePrevLow, dto::setCePrevClose, "CE", dto.getStrikePrice());
                if (ok) successCount++; else failureCount++;
            }
            if (dto.getPeToken() != null) {
                boolean ok = fetchAndCacheOHLC(smartConnect, strategy, dto.getPeToken().getToken(), fromDate, toDate, highCache, lowCache, closeCache, dto::setPePrevHigh, dto::setPePrevLow, dto::setPePrevClose, "PE", dto.getStrikePrice());
                if (ok) successCount++; else failureCount++;
            }
            if (dto.getCePrevClose() != null && dto.getPePrevClose() != null) {
                dto.setCombinedPrevClose(dto.getCePrevClose().add(dto.getPePrevClose()));
            }
            if (dto.getCePrevLow() != null && dto.getPePrevLow() != null) {
                dto.setCombinedPrevLow(dto.getCePrevLow().add(dto.getPePrevLow()));
            }
        }
        logger.info("Prev day OHLC fetch complete for {}: Success={}, Failure={}", strategy.getName(), successCount, failureCount);
    }

    private boolean fetchAndCacheOHLC(SmartConnect smartConnect, Strategy strategy, String token, String fromDate, String toDate, Map<String, BigDecimal> highCache, Map<String, BigDecimal> lowCache, Map<String, BigDecimal> closeCache, java.util.function.Consumer<BigDecimal> highSetter, java.util.function.Consumer<BigDecimal> lowSetter, java.util.function.Consumer<BigDecimal> closeSetter, String optionType, BigDecimal strikePrice) {
        try {
            JSONObject req = new JSONObject();
            req.put("exchange", strategy.getExchange());
            req.put("symboltoken", token);
            req.put("interval", "ONE_DAY");
            req.put("fromdate", fromDate);
            req.put("todate", toDate);

            sleepQuietly(350);
            JSONArray candles = fetchCandleWithRetry(smartConnect, req, token);

            if (candles != null && candles.length() > 0) {
                JSONArray last = candles.getJSONArray(candles.length() - 1);
                BigDecimal high = last.getBigDecimal(2);
                BigDecimal low = last.getBigDecimal(3);
                BigDecimal close = last.getBigDecimal(4);

                highCache.put(token, high);
                lowCache.put(token, low);
                closeCache.put(token, close);

                highSetter.accept(high);
                lowSetter.accept(low);
                closeSetter.accept(close);
                return true;
            } else {
                logger.warn("No candle data for {} token: {} (from={} to={})", optionType, token, fromDate, toDate);
                return false;
            }
        } catch (Exception e) {
            logger.error("Failed to fetch {} prev day OHLC for strike {}: {}", optionType, strikePrice, e.getMessage());
            return false;
        }
    }

    public JSONArray fetchLatestOneMinuteCandle(SmartConnect smartConnect, String exchange, String token) throws ParseException {
        int attempt = 0;
        long retryDelay = INITIAL_RETRY_DELAY_MS;

        while (attempt < MAX_RETRY_ATTEMPTS) {
            try {
                enforceRateLimit();
                LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
                String fromDate, toDate;

                if ("MCX".equalsIgnoreCase(exchange)) {
                    fromDate = today + " 09:00"; 
                    toDate = today + " 23:55";     
                } else {
                    fromDate = today + " 09:15";
                    toDate = today + " 15:30";
                }

                JSONObject req = new JSONObject();
                req.put("exchange", exchange);
                req.put("symboltoken", token);
                req.put("interval", "ONE_MINUTE");
                req.put("fromdate", fromDate);
                req.put("todate", toDate);

                JSONArray result = smartConnect.candleData(req);
                if (result == null) {
                    attempt++;
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        Thread.sleep(retryDelay);
                        retryDelay *= 2;
                    } else {
                        return null;
                    }
                } else if (result.length() == 0) {
                    return null;
                } else {
                    return result;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                attempt++;
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    try {
                        Thread.sleep(retryDelay);
                        retryDelay *= 2;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                } else {
                    return null;
                }
            }
        }
        return null;
    }

    public JSONArray fetchCandleWithRetry(SmartConnect smartConnect, JSONObject request, String token) {
        int maxAttempts = 5;
        long delay = 3000;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                JSONArray candles = smartConnect.candleData(request);
                if (candles != null && candles.length() > 0) return candles;
                if (candles != null && candles.length() == 0) return null; 
            } catch (Exception e) {
                logger.warn("Attempt {}/{} failed for token {}: {}", attempt, maxAttempts, token, e.getMessage());
            }

            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(delay);
                    delay *= 2;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    public synchronized void enforceRateLimit() throws InterruptedException {
        long now = System.currentTimeMillis();
        long timeSinceLastCall = now - lastCandleApiCall;
        if (timeSinceLastCall < CANDLE_API_DELAY_MS) {
            long waitTime = CANDLE_API_DELAY_MS - timeSinceLastCall;
            Thread.sleep(waitTime);
        }
        lastCandleApiCall = System.currentTimeMillis();
    }

    public void sleepQuietly(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}