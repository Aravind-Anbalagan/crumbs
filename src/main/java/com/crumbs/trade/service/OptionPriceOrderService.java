package com.crumbs.trade.service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.models.Order;
import com.angelbroking.smartapi.models.OrderParams;
import com.angelbroking.smartapi.utils.Constants;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.builder.OptionScannerConfig;
import com.crumbs.trade.dto.ScannedContractDto;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.StrategyRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OptionPriceOrderService {

    private static final String ZONE = "Asia/Kolkata";
    private static final String PAPER_ORDER_ID_MARKER = "1";
    private static final long CHASE_WAIT_MS = 10_000;
    private static final int MAX_CHASE_ATTEMPTS = 30;

    private final AngelOne angelOne;
    private final OrderRepository orderRepository;
    private final StrategyRepo strategyRepo;
    private final MonitorOrderService monitorOrderService; // ✅ Injected the execution engine

    public void processAiSignalOrder(String symbol, String biasLabel, List<ScannedContractDto> contracts) {
        if (contracts == null || contracts.isEmpty()) return;

        if (hasActiveTradeTodayForSymbol(symbol)) {
            log.info("🔐 [DAILY LOCK] Trade already active today for {}. Skipping AI order.", symbol);
            return;
        }

        boolean isLive = false;
        try {
            Strategy strat = strategyRepo.findByName("OPTION_PRICE_SCANNER");
            if (strat != null && "Y".equalsIgnoreCase(strat.getLive())) {
                isLive = true;
            }
        } catch (Exception e) {
            log.warn("⚠️ Could not fetch strategy config for OPTION_PRICE_SCANNER: {}", e.getMessage());
        }

        List<Double> sortedStrikes = contracts.stream()
                .map(ScannedContractDto::getStrike)
                .filter(s -> s > 0)
                .distinct()
                .sorted()
                .toList();

        if (sortedStrikes.isEmpty()) return;

        ScannedContractDto atmContract = contracts.stream()
                .filter(c -> c.getMoneyness() == OptionScannerConfig.Moneyness.ATM)
                .findFirst()
                .orElse(null);

        double atmStrike = atmContract != null ? atmContract.getStrike() : sortedStrikes.get(sortedStrikes.size() / 2);
        int atmIndex = sortedStrikes.indexOf(atmStrike);
        if (atmIndex < 0) atmIndex = 0;

        String tradeCycleId = UUID.randomUUID().toString();
        String strategyName = "AI_BIAS_" + symbol;

        if (biasLabel.contains("PURE STRADDLE/STRANGLE")) {
            ScannedContractDto ce = findContract(contracts, atmStrike, "CE");
            ScannedContractDto pe = findContract(contracts, atmStrike, "PE");
            log.info("🎯 [PURE STRADDLE] {} -> ATM Strike: {}", symbol, atmStrike);
            dispatchLegs(strategyName, symbol, tradeCycleId, ce, pe, isLive);

        } else if (biasLabel.contains("BULLISH STRADDLE")) {
            int ceIdx = Math.min(atmIndex + 1, sortedStrikes.size() - 1);
            double ceStrike = sortedStrikes.get(ceIdx);
            double peStrike = atmStrike;
            ScannedContractDto ce = findContract(contracts, ceStrike, "CE");
            ScannedContractDto pe = findContract(contracts, peStrike, "PE");
            log.info("🎯 [BULLISH STRADDLE] {} -> CE: {} (OTM) | PE: {} (ATM)", symbol, ceStrike, peStrike);
            dispatchLegs(strategyName, symbol, tradeCycleId, ce, pe, isLive);

        } else if (biasLabel.contains("BEARISH STRADDLE")) {
            int peIdx = Math.max(atmIndex - 0, 0);
            double ceStrike = atmStrike;
            double peStrike = sortedStrikes.get(peIdx);
            ScannedContractDto ce = findContract(contracts, ceStrike, "CE");
            ScannedContractDto pe = findContract(contracts, peStrike, "PE");
            log.info("🎯 [BEARISH STRADDLE] {} -> CE: {} (ATM) | PE: {} (OTM)", symbol, ceStrike, peStrike);
            dispatchLegs(strategyName, symbol, tradeCycleId, ce, pe, isLive);

        } else if (biasLabel.startsWith("BULLISH")) {
            ScannedContractDto pe = findContract(contracts, atmStrike, "PE");
            log.info("🎯 [PURE BULLISH] {} -> Selling PE: {}", symbol, atmStrike);
            dispatchSingleLeg(strategyName, symbol, tradeCycleId, pe, "SELL", isLive);

        } else if (biasLabel.startsWith("BEARISH")) {
            ScannedContractDto ce = findContract(contracts, atmStrike, "CE");
            log.info("🎯 [PURE BEARISH] {} -> Selling CE: {}", symbol, atmStrike);
            dispatchSingleLeg(strategyName, symbol, tradeCycleId, ce, "SELL", isLive);
        }
    }

    private void dispatchLegs(String strategyName, String symbol, String cycleId,
                              ScannedContractDto ce, ScannedContractDto pe, boolean isLive) {
        if (ce != null) dispatchSingleLeg(strategyName, symbol, cycleId, ce, "SELL", isLive);
        if (pe != null) dispatchSingleLeg(strategyName, symbol, cycleId, pe, "SELL", isLive);
    }

    @Async
    public void dispatchSingleLeg(String strategyName, String symbol, String cycleId,
                                  ScannedContractDto dto, String transactionType, boolean isLive) {
        if (dto == null || dto.getToken() == null) {
            log.warn("⚠️ Invalid contract details. Cannot dispatch order.");
            return;
        }

        String safeExchange = "MCX".equalsIgnoreCase(dto.getExchange()) ? "MCX" : "NFO";

        int quantity = dto.getLotsize() > 0 ? dto.getLotsize() : 1;
        String tradingSymbol = dto.getSymbol() != null ? dto.getSymbol()
                : dto.getName() + dto.getRawExpiry() + (int) dto.getStrike() + dto.getOptionType();

        Orders legOrder = new Orders();
        legOrder.setName(strategyName);
        legOrder.setSymbol(tradingSymbol);
        legOrder.setToken(dto.getToken());
        legOrder.setStrike(BigDecimal.valueOf(dto.getStrike()));
        legOrder.setOptionType(dto.getOptionType());
        legOrder.setSide(dto.getOptionType());
        legOrder.setQuantity(quantity);
        legOrder.setExchange(safeExchange);
        legOrder.setSignal(strategyName);
        legOrder.setType(transactionType);
        legOrder.setTradeCycleId(cycleId);

        if (!isLive) {
            BigDecimal paperPrice = dto.getCurrentLtp() != null ? dto.getCurrentLtp() : BigDecimal.TEN;
            log.info("📄 [PAPER] Order saved for {} {} @ ₹{}", strategyName, tradingSymbol, paperPrice);
            finalizeOrderInDb(legOrder, paperPrice, PAPER_ORDER_ID_MARKER);
            return;
        }

        SmartConnect smartConnect;
        try {
            smartConnect = angelOne.signIn();
        } catch (Exception e) {
            log.error("❌ Broker auth failed for {}: {}", tradingSymbol, e.getMessage());
            return;
        }

        if (smartConnect == null) return;

        try {
            BigDecimal limitPrice = getBestDepthPrice(smartConnect, safeExchange, dto.getToken(), dto.getCurrentLtp(), transactionType);

            OrderParams orderParams = new OrderParams();
            orderParams.variety = Constants.VARIETY_NORMAL;
            orderParams.quantity = quantity;
            orderParams.symboltoken = dto.getToken();
            orderParams.exchange = safeExchange;
            orderParams.ordertype = Constants.ORDER_TYPE_LIMIT;
            orderParams.tradingsymbol = tradingSymbol;
            orderParams.producttype = Constants.PRODUCT_CARRYFORWARD;
            orderParams.duration = Constants.DURATION_DAY;
            orderParams.transactiontype = transactionType;
            orderParams.price = limitPrice.doubleValue();
            orderParams.squareoff = "0";
            orderParams.stoploss = "0";

            Order placedOrder = smartConnect.placeOrder(orderParams, Constants.VARIETY_NORMAL);
            if (placedOrder == null || placedOrder.orderId == null || placedOrder.orderId.isBlank()) {
                log.error("❌ Failed placing limit order for {}", tradingSymbol);
                return;
            }

            String orderId = placedOrder.orderId;
            log.info("🎯 Placed Limit Order for {} @ ₹{} | OrderID: {}", tradingSymbol, limitPrice, orderId);
            chaseLimitOrderByDepth(smartConnect, legOrder, orderParams, orderId, limitPrice, transactionType);

        } catch (Exception e) {
            log.error("❌ Exception during order placement for {}: {}", tradingSymbol, e.getMessage(), e);
        }
    }

    private void chaseLimitOrderByDepth(SmartConnect smartConnect, Orders legOrder, OrderParams orderParams,
                                        String orderId, BigDecimal initialPrice, String transactionType) {
        BigDecimal activePrice = initialPrice;

        for (int attempt = 1; attempt <= MAX_CHASE_ATTEMPTS; attempt++) {
            sleepQuietly(CHASE_WAIT_MS);

            String status = fetchOrderStatus(smartConnect, orderId);
            if ("COMPLETE".equalsIgnoreCase(status)) {
                log.info("✅ Order {} FILLED at ₹{} after {} attempts", orderId, activePrice, attempt);
                finalizeOrderInDb(legOrder, activePrice, orderId);
                return;
            }

            if ("REJECTED".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status)) {
                log.warn("🚨 Order {} was {}. Stopping chaser.", orderId, status);
                return;
            }

            BigDecimal newBestPrice = getBestDepthPrice(smartConnect, orderParams.exchange, orderParams.symboltoken, activePrice, transactionType);
            if (newBestPrice.compareTo(activePrice) != 0) {
                orderParams.price = newBestPrice.doubleValue();
                try {
                    smartConnect.modifyOrder(orderId, orderParams, Constants.VARIETY_NORMAL);
                    log.info("🔄 Adjusted order {} price to ₹{}", orderId, newBestPrice);
                    activePrice = newBestPrice;
                } catch (Exception e) {
                    log.warn("⚠️ Could not modify order {}: {}", orderId, e.getMessage());
                }
            }
        }

        log.warn("⚠️ Chase timeout for {}. Order {} left as active limit.", legOrder.getSymbol(), orderId);
        finalizeOrderInDb(legOrder, activePrice, orderId);
    }

    private BigDecimal getBestDepthPrice(SmartConnect smartConnect, String exchange, String token, BigDecimal fallbackLtp, String side) {
        String safeExchange = "MCX".equalsIgnoreCase(exchange) ? "MCX" : "NFO";

        try {
            JSONObject payload = new JSONObject();
            payload.put("mode", "FULL");
            JSONObject exchangeTokens = new JSONObject();
            exchangeTokens.put(safeExchange, new JSONArray(List.of(token)));
            payload.put("exchangeTokens", exchangeTokens);

            JSONObject response = smartConnect.marketData(payload);
            if (response != null && response.has("data")) {
                JSONArray fetched = response.getJSONObject("data").optJSONArray("fetched");
                if (fetched != null && !fetched.isEmpty()) {
                    JSONObject depth = fetched.getJSONObject(0).optJSONObject("depth");
                    if (depth != null) {
                        String depthKey = Constants.TRANSACTION_TYPE_BUY.equalsIgnoreCase(side) ? "sell" : "buy";
                        JSONArray book = depth.optJSONArray(depthKey);
                        if (book != null && !book.isEmpty()) {
                            double price = book.getJSONObject(0).optDouble("price", 0.0);
                            if (price > 0) return BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP);
                        }
                    }
                    double ltp = fetched.getJSONObject(0).optDouble("ltp", 0.0);
                    if (ltp > 0) return BigDecimal.valueOf(ltp).setScale(2, RoundingMode.HALF_UP);
                }
            }
        } catch (Exception ignored) {} catch (SmartAPIException e) {
            throw new RuntimeException(e);
        }
        return fallbackLtp != null ? fallbackLtp : BigDecimal.valueOf(100.0);
    }

    private String fetchOrderStatus(SmartConnect smartConnect, String orderId) {
        try {
            JSONObject response = smartConnect.getIndividualOrderDetails(orderId);
            if (response != null && response.optBoolean("status", false)) {
                JSONObject data = response.optJSONObject("data");
                if (data != null) return data.optString("status", "OPEN");
            }
        } catch (Exception ignored) {} catch (SmartAPIException e) {
            throw new RuntimeException(e);
        }
        return "OPEN";
    }

    private ScannedContractDto findContract(List<ScannedContractDto> contracts, double strike, String type) {
        return contracts.stream()
                .filter(c -> Double.compare(c.getStrike(), strike) == 0 && type.equalsIgnoreCase(c.getOptionType()))
                .findFirst()
                .orElse(null);
    }

    private boolean hasActiveTradeTodayForSymbol(String symbol) {
        try {
            return orderRepository.countActiveTradesToday("AI_BIAS_" + symbol) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Transactional
    public void finalizeOrderInDb(Orders leg, BigDecimal fillPrice, String orderId) {
        leg.setAskPrice(fillPrice);
        leg.setOrderid(orderId);
        leg.setStatus("OPEN");
        leg.setTradePhase("ENTRY");
        leg.setActive(1);
        leg.setCreatedOn(LocalDateTime.now(ZoneId.of(ZONE)));
        orderRepository.save(leg);
    }

    // ==========================================
    // 3:15 PM AUTO SQUARE OFF LOGIC
    // ==========================================

    // ✅ REMOVED @Transactional: monitorOrderService handles its own saves, this prevents DB locks/timeouts.
    public void closeAllOpenTrades() {
        List<Orders> openOrders = orderRepository.findAll().stream()
                .filter(o -> o.getActive() == 1 && "OPEN".equalsIgnoreCase(o.getStatus()))
                .filter(o -> o.getName() != null && o.getName().startsWith("AI_BIAS_"))
                .collect(Collectors.toList());

        if (openOrders.isEmpty()) {
            log.info("✅ No open AI_BIAS trades found to square off at 3:15 PM.");
            return;
        }

        log.info("🔄 Auto Squaring off {} AI_BIAS trades via MonitorOrderService...", openOrders.size());

        // ✅ Group by Cycle ID (or Name) so multi-leg trades exit as a group
        Map<String, List<Orders>> groupedOrders = openOrders.stream()
                .collect(Collectors.groupingBy(o -> o.getTradeCycleId() != null ? o.getTradeCycleId() : o.getName()));

        for (Map.Entry<String, List<Orders>> entry : groupedOrders.entrySet()) {
            try {
                List<Orders> groupLegs = entry.getValue();
                String strategyName = groupLegs.get(0).getName();

                // ✅ MonitorOrderService natively handles BOTH Live and Paper executions + PnL sync
                monitorOrderService.forceExit(groupLegs, strategyName, "3:15_AUTO_SQUARE_OFF");
            } catch (Exception e) {
                log.error("❌ Exception during auto square-off for group {}: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    private void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}