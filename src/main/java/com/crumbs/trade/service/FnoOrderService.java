package com.crumbs.trade.service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.models.Order;
import com.angelbroking.smartapi.models.OrderParams;
import com.angelbroking.smartapi.utils.Constants;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.repo.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FnoOrderService {

    private static final String ZONE = "Asia/Kolkata";
    private static final String PAPER_ORDER_ID_MARKER = "1";
    private static final long CHASE_WAIT_MS = 10_000; // 10-second inspection interval
    private static final int MAX_CHASE_ATTEMPTS = 30; // Chases for up to 5 minutes strictly on LIMIT

    private final AngelOne angelOne;
    private final OrderRepository orderRepository;

    /**
     * Entry point to execute Call and Put legs asynchronously in parallel.
     */
    public void executeStrategyPair(String strategyName,
                                    String symbol,
                                    String ceToken,
                                    String ceTradingSymbol,
                                    BigDecimal ceStrike,
                                    BigDecimal ceLtp,
                                    String peToken,
                                    String peTradingSymbol,
                                    BigDecimal peStrike,
                                    BigDecimal peLtp,
                                    int quantity,
                                    String exchange,
                                    boolean isLive) {

        String tradeCycleId = UUID.randomUUID().toString();
        log.info("🚀 Initiating Depth-Sniper [{}] for {} | Cycle: {} | Live: {}", strategyName, symbol, tradeCycleId, isLive);

        // Execute Call (CE) Leg
        executeLegAsync(strategyName, symbol, ceToken, ceTradingSymbol, ceStrike, "CE", ceLtp, quantity, exchange, tradeCycleId, isLive);

        // Execute Put (PE) Leg
        executeLegAsync(strategyName, symbol, peToken, peTradingSymbol, peStrike, "PE", peLtp, quantity, exchange, tradeCycleId, isLive);
    }

    /**
     * Asynchronous Limit Worker per Option Leg.
     */
    @Async
    public void executeLegAsync(String strategyName,
                                String symbol,
                                String token,
                                String tradingSymbol,
                                BigDecimal strike,
                                String optionType,
                                BigDecimal initialLtp,
                                int quantity,
                                String exchange,
                                String tradeCycleId,
                                boolean isLive) {

        Orders legOrder = new Orders();
        legOrder.setName(strategyName);
        legOrder.setSymbol(tradingSymbol);
        legOrder.setToken(token);
        legOrder.setStrike(strike);
        legOrder.setOptionType(optionType);
        legOrder.setSide(optionType);
        legOrder.setQuantity(quantity);
        legOrder.setExchange(exchange);
        legOrder.setSignal(strategyName);
        legOrder.setType("SELL"); // Option Writing
        legOrder.setTradeCycleId(tradeCycleId);

        // =========================================================================
        // 1. PAPER TRADING
        // =========================================================================
        if (!isLive) {
            log.info("📄 [PAPER] Order registered for {} {} @ ₹{}", strategyName, tradingSymbol, initialLtp);
            finalizeOrderInDb(legOrder, initialLtp, PAPER_ORDER_ID_MARKER);
            return;
        }

        // =========================================================================
        // 2. LIVE BROKER EXECUTION (100% Pure Limit via Market Depth)
        // =========================================================================
        SmartConnect smartConnect = null;
        try {
            smartConnect = angelOne.signIn();
        } catch (Exception e) {
            log.error("❌ Broker sign-in exception for {}: {}", tradingSymbol, e.getMessage());
        }

        if (smartConnect == null) {
            log.error("❌ Broker sign-in returned null. Aborting live order for {}", tradingSymbol);
            return;
        }

        try {
            // Read Level-2 depth to find best buyer price
            BigDecimal limitPrice = getBestBidFromFullDepth(smartConnect, exchange, token, initialLtp);

            OrderParams orderParams = new OrderParams();
            orderParams.variety = Constants.VARIETY_NORMAL;
            orderParams.quantity = quantity;
            orderParams.symboltoken = token;
            orderParams.exchange = exchange;
            orderParams.ordertype = Constants.ORDER_TYPE_LIMIT;
            orderParams.tradingsymbol = tradingSymbol;
            orderParams.producttype = Constants.PRODUCT_CARRYFORWARD;
            orderParams.duration = Constants.DURATION_DAY;
            orderParams.transactiontype = Constants.TRANSACTION_TYPE_SELL;
            orderParams.price = limitPrice.doubleValue();
            orderParams.squareoff = "0";
            orderParams.stoploss = "0";

            Order placedOrder = smartConnect.placeOrder(orderParams, Constants.VARIETY_NORMAL);
            if (placedOrder == null || placedOrder.orderId == null || placedOrder.orderId.isBlank()) {
                log.error("❌ Failed to place initial limit order for {}", tradingSymbol);
                return;
            }

            String orderId = placedOrder.orderId;
            log.info("🎯 Initial Pure Limit Order placed for {} @ ₹{} | OrderID: {}", tradingSymbol, limitPrice, orderId);

            // Start Pure Limit Order Chaser
            chaseLimitOrderByDepth(smartConnect, legOrder, orderParams, orderId, limitPrice);

        } catch (Exception e) {
            log.error("💥 General error placing order for {}: {}", tradingSymbol, e.getMessage(), e);
        }
    }

    /**
     * Pure Limit Chaser:
     * - Polls every 10 seconds.
     * - Checks if filled.
     * - If not filled, reads complete Level-2 market depth.
     * - Adjusts order for ANY price difference (0.05 paise to 10+ Rs).
     * - NEVER converts to Market Order.
     */
    private void chaseLimitOrderByDepth(SmartConnect smartConnect,
                                        Orders legOrder,
                                        OrderParams orderParams,
                                        String orderId,
                                        BigDecimal initialPrice) {

        BigDecimal activePrice = initialPrice;

        for (int attempt = 1; attempt <= MAX_CHASE_ATTEMPTS; attempt++) {
            sleepQuietly(CHASE_WAIT_MS);

            // 1. Check if filled
            String status = fetchOrderStatus(smartConnect, orderId);

            if ("COMPLETE".equalsIgnoreCase(status)) {
                log.info("✅ Order {} FILLED at ₹{} after {} attempts ({}s)",
                        orderId, activePrice, attempt, attempt * 10);
                finalizeOrderInDb(legOrder, activePrice, orderId);
                return;
            }

            if ("REJECTED".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status)) {
                log.warn("🚨 Order {} was {}. Terminating depth chaser.", orderId, status);
                return;
            }

            // 2. Read full 5-level market depth again
            BigDecimal newBestBid = getBestBidFromFullDepth(smartConnect, orderParams.exchange, orderParams.symboltoken, activePrice);

            // 3. Adjust price if any difference exists (even 5 paise or 10 Rs)
            if (newBestBid.compareTo(activePrice) != 0) {
                BigDecimal diff = newBestBid.subtract(activePrice);
                log.info("🔄 [DEPTH UPDATE] Price moved from ₹{} to ₹{} (Diff: ₹{}). Modifying order {}...",
                        activePrice, newBestBid, diff, orderId);

                orderParams.price = newBestBid.doubleValue();
                orderParams.ordertype = Constants.ORDER_TYPE_LIMIT;
                modifyOrderInBroker(smartConnect, orderId, orderParams);
                activePrice = newBestBid;
            } else {
                log.info("⏳ [DEPTH UNCHANGED] Best bid remains ₹{}. Retrying in 10s... (Attempt {}/{})",
                        activePrice, attempt, MAX_CHASE_ATTEMPTS);
            }
        }

        log.warn("⚠️ Chase limit duration reached ({} attempts) for {}. Order {} remains active as LIMIT in broker queue.",
                MAX_CHASE_ATTEMPTS, legOrder.getSymbol(), orderId);
        finalizeOrderInDb(legOrder, activePrice, orderId);
    }

    /**
     * Reads the complete 5-level Market Depth to find the top active buyer bid price.
     */
    private BigDecimal getBestBidFromFullDepth(SmartConnect smartConnect, String exchange, String token, BigDecimal fallbackLtp) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("mode", "FULL");
            JSONObject exchangeTokens = new JSONObject();
            JSONArray tokens = new JSONArray();
            tokens.put(token);
            exchangeTokens.put(exchange, tokens);
            payload.put("exchangeTokens", exchangeTokens);

            JSONObject response = smartConnect.marketData(payload);
            if (response != null && response.has("data")) {
                JSONObject data = response.getJSONObject("data");
                JSONArray fetched = data.optJSONArray("fetched");
                if (fetched != null && fetched.length() > 0) {
                    JSONObject depth = fetched.getJSONObject(0).optJSONObject("depth");
                    if (depth != null && depth.has("buy")) {
                        JSONArray buyBids = depth.getJSONArray("buy");

                        // Scan top of book
                        for (int i = 0; i < buyBids.length(); i++) {
                            JSONObject bid = buyBids.getJSONObject(i);
                            double price = bid.optDouble("price", 0.0);
                            int quantity = bid.optInt("quantity", 0);

                            if (price > 0 && quantity > 0) {
                                return BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP);
                            }
                        }
                    }
                    // Fallback to LTP if depth book is momentarily empty
                    double ltp = fetched.getJSONObject(0).optDouble("ltp", 0.0);
                    if (ltp > 0) {
                        return BigDecimal.valueOf(ltp).setScale(2, RoundingMode.HALF_UP);
                    }
                }
            }
        } catch (Exception | SmartAPIException e) {
            log.warn("⚠️ Market depth lookup failed for token {}: {}", token, e.getMessage());
        }
        return fallbackLtp;
    }

    /**
     * Queries order status from Angel One API.
     */
    private String fetchOrderStatus(SmartConnect smartConnect, String orderId) {
        try {
            JSONObject response = smartConnect.getIndividualOrderDetails(orderId);
            if (response != null && response.optBoolean("status", false)) {
                JSONObject data = response.optJSONObject("data");
                if (data != null) {
                    return data.optString("status", "OPEN");
                }
            }
        } catch (Exception | SmartAPIException e) {
            log.warn("⚠️ getIndividualOrderDetails failed for {}: {}. Checking order history...", orderId, e.getMessage());
            try {
                JSONObject history = smartConnect.getOrderHistory(smartConnect.getUserId());
                if (history != null && history.optBoolean("status", false)) {
                    JSONArray data = history.optJSONArray("data");
                    if (data != null) {
                        for (int i = 0; i < data.length(); i++) {
                            JSONObject ord = data.getJSONObject(i);
                            if (orderId.equals(ord.optString("orderid"))) {
                                return ord.optString("status", "OPEN");
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                log.error("❌ Failed to query order history for {}: {}", orderId, ex.getMessage());
            }
        }
        return "OPEN";
    }

    /**
     * Modifies limit price on the open broker order.
     */
    private void modifyOrderInBroker(SmartConnect smartConnect, String orderId, OrderParams orderParams) {
        try {
            smartConnect.modifyOrder(orderId, orderParams, Constants.VARIETY_NORMAL);
        } catch (Exception e) {
            log.error("❌ Failed to modify limit order {} in broker: {}", orderId, e.getMessage());
        }
    }

    /**
     * Persists order in DB so MonitorOrderService can track PnL and manage risk.
     */
    @Transactional
    public void finalizeOrderInDb(Orders leg, BigDecimal fillPrice, String orderId) {
        leg.setAskPrice(fillPrice);
        leg.setOrderid(orderId);
        leg.setStatus("OPEN");
        leg.setTradePhase("ENTRY");
        leg.setActive(1);
        leg.setCreatedOn(LocalDateTime.now(ZoneId.of(ZONE)));
        orderRepository.save(leg);
        log.info("💾 Order saved to DB -> ID: {} | Token: {} | OrderID: {} | Status: OPEN | Active: 1",
                leg.getId(), leg.getToken(), orderId);
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}