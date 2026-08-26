package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.concurrent.ConcurrentHashMap;
import com.angelbroking.smartapi.SmartConnect;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.broker.Samco;
import com.crumbs.trade.dto.StraddlePremiumDto;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.utility.ConditionalLogger;
import com.crumbs.trade.utility.SamcoSessionManager;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StraddleVwapService {

    private static final Logger baseLogger = LoggerFactory.getLogger(StraddleVwapService.class);
    private final ConditionalLogger logger = new ConditionalLogger(baseLogger);

    private final StraddleMarketDataService marketDataService;
    private final StraddleTokenService tokenService;
    private final AngelOne angelOne;
    private final Samco samco;
    private final SamcoSessionManager sessionManager;
    private final ExecutorService executor = Executors.newFixedThreadPool(5);
    // ================= VWAP STATE =================
    private final Map<String, BigDecimal> tpvMap = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> volMap = new ConcurrentHashMap<>();
    private final Map<String, String> lastProcessedTimestamp = new ConcurrentHashMap<>();
    private LocalDate vwapDate = null;

    // =====================================================
    // VWAP RESET
    // =====================================================
    public void resetVwapIfNewDay() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        if (vwapDate == null || !vwapDate.equals(today)) {
            tpvMap.clear();
            volMap.clear();
            lastProcessedTimestamp.clear(); // Clears the String map
            vwapDate = today;
            logger.info("VWAP and Timestamp cache reset for new trading day: {}", today);
        }
    }

    // =====================================================
    // CALCULATE COMBINED IV (Public for Persistence Service)
    // =====================================================
    public BigDecimal calculateCombinedIV(BigDecimal ceIV, BigDecimal peIV) {
        // Treat zero as invalid (IV can't be 0% in practice)
        boolean ceValid = ceIV != null && ceIV.compareTo(BigDecimal.ZERO) > 0;
        boolean peValid = peIV != null && peIV.compareTo(BigDecimal.ZERO) > 0;

        // Both invalid - return null
        if (!ceValid && !peValid) {
            logger.debug("Both CE IV and PE IV are invalid (null or zero)");
            return null;
        }

        // Only CE valid
        if (ceValid && !peValid) {
            logger.debug("Using only CE IV: {}", ceIV);
            return ceIV;
        }

        // Only PE valid
        if (!ceValid && peValid) {
            logger.debug("Using only PE IV: {}", peIV);
            return peIV;
        }

        // Both valid - return average
        BigDecimal combined = ceIV.add(peIV).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        logger.debug("Combined IV: ({} + {}) / 2 = {}", ceIV, peIV, combined);

        return combined;
    }

    // =====================================================
    // FETCH VWAP IN PARALLEL
    // =====================================================
    public void fetchVwapInParallel(List<StraddlePremiumDto> strikesWithPrices, SmartConnect smartConnect, String exchange) {
        // Create thread pool (limit to 5 concurrent requests to avoid rate limits)
       
        List<Future<?>> futures = new ArrayList<>();

        for (StraddlePremiumDto dto : strikesWithPrices) {
            // Submit CE VWAP fetch
            if (dto.getCeToken() != null && dto.getCePrice() != null && dto.getCePrice().compareTo(BigDecimal.ZERO) > 0) {
                futures.add(executor.submit(() -> {
                    try {
                        JSONArray ceCandle = marketDataService.fetchLatestOneMinuteCandle(smartConnect, exchange, dto.getCeToken().getToken());
                        if (ceCandle != null && !ceCandle.isEmpty()) {
                            BigDecimal vwap = updateVwapIncremental(dto.getCeToken().getToken(), ceCandle);
                            dto.setCeVwap(vwap);
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to fetch CE VWAP for strike {}: {}", dto.getStrikePrice(), e.getMessage());
                    }
                }));
            }

            // Submit PE VWAP fetch
            if (dto.getPeToken() != null && dto.getPePrice() != null && dto.getPePrice().compareTo(BigDecimal.ZERO) > 0) {
                futures.add(executor.submit(() -> {
                    try {
                        JSONArray peCandle = marketDataService.fetchLatestOneMinuteCandle(smartConnect, exchange, dto.getPeToken().getToken());
                        if (peCandle != null && !peCandle.isEmpty()) {
                            BigDecimal vwap = updateVwapIncremental(dto.getPeToken().getToken(), peCandle);
                            dto.setPeVwap(vwap);
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to fetch PE VWAP for strike {}: {}", dto.getStrikePrice(), e.getMessage());
                    }
                }));
            }
        }

        // Wait for all tasks to complete
        for (Future<?> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS); // 30 second timeout per task
            } catch (TimeoutException e) {
                logger.error("VWAP fetch timed out");
                future.cancel(true);
            } catch (Exception e) {
                logger.error("VWAP fetch failed: {}", e.getMessage());
            }
        }

    }

    // =====================================================
    // INCREMENTAL VWAP UPDATE
    // =====================================================
    public BigDecimal updateVwapIncremental(String token, JSONArray candleArr) {
        for (int i = 0; i < candleArr.length(); i++) {
            JSONArray c = candleArr.getJSONArray(i);

            // Extract as String and compare alphabetically
            String candleTimestamp = c.getString(0); 
            String lastSeen = lastProcessedTimestamp.getOrDefault(token, "");

            // If current candle is not newer than last processed, skip it
            if (!lastSeen.isEmpty() && candleTimestamp.compareTo(lastSeen) <= 0) {
                continue; 
            }

            BigDecimal high   = c.getBigDecimal(2);
            BigDecimal low    = c.getBigDecimal(3);
            BigDecimal close  = c.getBigDecimal(4);
            BigDecimal volume = c.getBigDecimal(5);

            if (volume.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            BigDecimal tp = high.add(low).add(close).divide(BigDecimal.valueOf(3), 6, RoundingMode.HALF_UP);

            tpvMap.put(token, tpvMap.getOrDefault(token, BigDecimal.ZERO).add(tp.multiply(volume)));
            volMap.put(token, volMap.getOrDefault(token, BigDecimal.ZERO).add(volume));

            // Update the "Last Seen" marker with the String
            lastProcessedTimestamp.put(token, candleTimestamp);
        }

        BigDecimal totalVolume = volMap.get(token);
        if (totalVolume == null || totalVolume.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        return tpvMap.get(token).divide(totalVolume, 2, RoundingMode.HALF_UP);
    }

    // =====================================================
    // VWAP WARM UP
    // =====================================================
    public void warmUpVwap(String name, Strategy strategy) {
        logger.info("🔥 Starting VWAP warm-up for {}", name);
        try {
            String session = sessionManager.getSession();
            BigDecimal spotPrice = null;

            if ("NIFTY".equalsIgnoreCase(name) || "SENSEX".equalsIgnoreCase(name) || name.contains("BANK")) {
                spotPrice = samco.getIndexPrice(session, name);
            } else if ("CRUDEOIL".equalsIgnoreCase(name) || "CRUDEOILM".equalsIgnoreCase(name) || "NATURALGAS".equalsIgnoreCase(name)) {
                spotPrice = samco.getLtp(session, strategy.getExchange(), tokenService.getSymbolByName(name));
            }
            
            if (spotPrice == null) return;

            BigDecimal atmStrike = tokenService.getATMStrike(name, strategy, spotPrice);
            
            int stepInterval = 50; 
            if (name != null) {
                String upperName = name.toUpperCase();
                if (upperName.contains("SENSEX") || upperName.contains("BANK") || upperName.contains("CRUDEOIL")) {
                    stepInterval = 100;
                } else if (upperName.contains("NATURALGAS")) {
                    stepInterval = 5;
                }
            }

            int rangeValue = 600;
            if (name != null) {
                String upperName = name.toUpperCase();
                if (upperName.contains("SENSEX")) {
                    rangeValue = 1000;
                } else if (upperName.contains("NATURALGAS")) {
                    rangeValue = 50; 
                }
            }

            List<StraddlePremiumDto> warmUpList = tokenService.buildStraddleDtos(name, atmStrike, stepInterval, rangeValue);
            warmUpList = tokenService.getAllTokenDetails(warmUpList, strategy);

            SmartConnect smartconnect = angelOne.signIn();

            for (StraddlePremiumDto dto : warmUpList) {
                // Warm up CE
                if (dto.getCeToken() != null) {
                    JSONArray candles = marketDataService.fetchLatestOneMinuteCandle(smartconnect, strategy.getExchange(), dto.getCeToken().getToken());
                    if (candles != null) {
                        updateVwapIncremental(dto.getCeToken().getToken(), candles);
                    }
                }
                // Warm up PE
                if (dto.getPeToken() != null) {
                    JSONArray candles = marketDataService.fetchLatestOneMinuteCandle(smartconnect, strategy.getExchange(), dto.getPeToken().getToken());
                    if (candles != null) {
                        updateVwapIncremental(dto.getPeToken().getToken(), candles);
                    }
                }
            }
            logger.info("✅ VWAP warm-up completed for {}", name);
        } catch (Exception e) {
            logger.error("❌ VWAP warm-up failed for {}", name, e);
        }
    }
}