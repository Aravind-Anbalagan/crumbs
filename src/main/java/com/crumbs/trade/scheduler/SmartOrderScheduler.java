package com.crumbs.trade.scheduler;

import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.FlatTradeService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
public class SmartOrderScheduler {

    private static final Logger logger = LogManager.getLogger(SmartOrderScheduler.class);

    @Autowired
    private StrategyRepo strategyRepo;

    @Autowired
    private FlatTradeService flatTradeService;

    /**
     * Polling job that checks the database for UPPER_CIRCUIT strategies ready to execute.
     * The fixedDelay countdown starts ONLY AFTER all current executions have completed.
     */
    @Scheduled(fixedDelayString = "${scheduler.smartorder.delay:5000}")
    public void processPendingStrategies() {

        // 🛑 MARKET TIMING CHECK: Do not query DB or place orders if NSE is closed
        if (!isMarketOpen()) {
            return; // Silently skip execution outside 9:15 AM - 3:30 PM IST
        }

        // 1. Find strategies where active="Y" AND execute="Y" AND name="UPPER_CIRCUIT"
        List<Strategy> pendingStrategies = strategyRepo.findByActiveAndName("Y", "UPPER_CIRCUIT");

        if (pendingStrategies.isEmpty()) {
            return; // Nothing to execute right now
        }

        // List to hold the background tasks
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Strategy strategy : pendingStrategies) {
            try {
                // FlatTrade GetQuotes strictly requires the instrument token.
                if (strategy.getToken() == null || strategy.getToken().trim().isEmpty()) {
                    logger.error("[SCHEDULER] Missing instrument token in DB for {}. Cannot fetch market depth.", strategy.getTradingsymbol());
                    continue;
                }

                logger.info("[SCHEDULER] Picked up UPPER_CIRCUIT strategy for execution: {}", strategy.getTradingsymbol());

                // 2. IMMEDIATELY update state to prevent duplicate triggers
                strategy.setExecute("PROCESSING");
                strategyRepo.save(strategy);

                // 3. Build the token payload using DB data
                Token token = new Token();

                // Maps to "symbol": "TCS-EQ"
                token.setSymbol(strategy.getTradingsymbol() != null && !strategy.getTradingsymbol().trim().isEmpty()
                        ? strategy.getTradingsymbol()
                        : strategy.getSymbol());

                // Maps to "exch_seg": "NSE"
                token.setExch_seg(strategy.getExchange() != null ? strategy.getExchange() : "NSE");

                // Maps to "quantity": 1
                token.setQuantity(strategy.getQuantity());

                // Maps to "transactionType": "B"
                token.setTransactionType("B");

                // 🌟 NEW: Maps to "productType": "M"
                token.setProductType("C");
                token.setToken(strategy.getToken());
                // Note: "orderType": "LMT" and "price" are automatically set inside
                // the executeSmartOrder() method after it fetches the live quotes.

                // 4. Hand off to the Async Execution Engine and store the future
                CompletableFuture<Void> future = flatTradeService.executeSmartOrder(token, strategy.getToken());
                futures.add(future);

            } catch (Exception e) {
                logger.error("[SCHEDULER] Error processing UPPER_CIRCUIT strategy ID {}: {}", strategy.getId(), e.getMessage());
            }
        }

        // 5. WAIT FOR COMPLETION
        // This blocks the scheduler thread until EVERY background trade execution
        // in the list has completely finished its monitoring loop.
        if (!futures.isEmpty()) {
            logger.info("[SCHEDULER] Waiting for {} pending order execution(s) to finish...", futures.size());

            // .join() stops the method here until all orders either complete fully or time out.
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            logger.info("[SCHEDULER] All order executions have completed. Starting next delay countdown.");
        }
    }

    /**
     * Helper to check if the NSE market is currently open.
     * NSE Hours: 9:15 AM to 3:30 PM IST, Monday - Friday.
     */
    private boolean isMarketOpen() {
        ZoneId istZone = ZoneId.of("Asia/Kolkata");
        ZonedDateTime now = ZonedDateTime.now(istZone);

        // 1. Check if it's a weekend
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }

        // 2. Check if current time is between 9:15 AM and 3:30 PM
        LocalTime currentTime = now.toLocalTime();
        LocalTime marketOpen = LocalTime.of(9, 15);
        LocalTime marketClose = LocalTime.of(15, 30);

        return !currentTime.isBefore(marketOpen) && currentTime.isBefore(marketClose);
    }
}