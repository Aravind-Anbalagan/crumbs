package com.crumbs.trade.service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import com.angelbroking.smartapi.utils.Constants;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.RiskConfiguration;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.RiskConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorOrderService {

    private static final String STATUS_CLOSED = "CLOSED";
    private static final String PHASE_EXIT = "EXIT";
    private static final String PHASE_EXIT_IN_PROGRESS = "EXIT_IN_PROGRESS";
    private static final String PHASE_ENTRY = "ENTRY";
    private static final String SMART_RISK_ACTIVE = "Y";
    private static final String PAPER_ORDER_ID_MARKER = "1";
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    private final OrderRepository ordersRepository;
    private final RiskConfigurationRepository riskConfigRepository;
    private final AngelOneService angelOneService;
    private final AngelWebSocketService webSocketService;
    private final AngelOne angelOne;
    private final TelegramService telegramService;

    private final Map<Long, BigDecimal> liveUiCachePnL = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> highWaterMarks = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> activeTrailingFloors = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> lastPnlSnapshot = new ConcurrentHashMap<>();
    private final Map<String, Integer> panicStreak = new ConcurrentHashMap<>();
    private final Map<Long, BigDecimal> reconciledEntryPriceCache = new ConcurrentHashMap<>();

    // Debounce state to prevent spamming Angel One API rate limits
    private volatile JSONArray cachedTradeBook = null;
    private volatile long lastTradeBookFetchTime = 0;

    public Map<Long, BigDecimal> getLivePnLForUI() {
        return liveUiCachePnL;
    }

    /**
     * Safely fetches the Trade Book with a 2-second debounce to protect API limits.
     * Parses raw JSON to eliminate SDK casting discrepancies across versions.
     */
    private JSONArray getTradeBookDebounced(SmartConnect connection) {
        long now = System.currentTimeMillis();
        if (now - lastTradeBookFetchTime > 2000) {
            synchronized (this) {
                if (System.currentTimeMillis() - lastTradeBookFetchTime > 2000) {
                    try {
                        JSONObject response = connection.getTrades();
                        if (response != null && response.optBoolean("status", false)) {
                            JSONArray data = response.optJSONArray("data");
                            cachedTradeBook = data != null ? data : new JSONArray();
                            lastTradeBookFetchTime = System.currentTimeMillis();
                        }
                    } catch (Exception e) {
                        log.error("❌ Failed to fetch Trade Book: {}", e.getMessage());
                    }
                }
            }
        }
        return cachedTradeBook;
    }

    public boolean evaluateAndClose(List<Orders> groupLegs, String strategyKey, SmartConnect connection) {
        if (groupLegs == null || groupLegs.isEmpty()) {
            return false;
        }

        RiskConfiguration config = riskConfigRepository.findById(strategyKey).orElse(null);

        boolean hasLiveLeg = groupLegs.stream().anyMatch(this::isLiveTrade);
        SmartConnect activeConnection = connection;
        if (hasLiveLeg && activeConnection == null) {
            try {
                activeConnection = angelOne.signIn();
            } catch (Exception e) {
                log.error("❌ [MONITOR] Broker auth failed for {}: {}", strategyKey, e.getMessage());
            }
        }

        BigDecimal combinedGroupPnL = BigDecimal.ZERO;
        boolean allLegsPriced = true;

        for (Orders leg : groupLegs) {
            BigDecimal legPnL = BigDecimal.ZERO;
            try {
                BigDecimal currentLtp = BigDecimal.ZERO;
                ExchangeType exchangeType = mapExchangeToType(leg.getExchange());

                if (leg.getToken() == null || leg.getToken().isBlank()) {
                    log.error("❌ [MONITOR] Leg {} has NO TOKEN — cannot price.", leg.getId());
                } else if (exchangeType != null) {
                    webSocketService.subscribe(exchangeType, leg.getToken());
                    currentLtp = webSocketService.getLatestLTP(exchangeType, leg.getToken());
                }

                if (currentLtp == null || currentLtp.compareTo(BigDecimal.ZERO) == 0) {
                    if (activeConnection == null) {
                        try { activeConnection = angelOne.signIn(); } catch (Exception ignored) {}
                    }
                    if (activeConnection != null) {
                        currentLtp = angelOneService.getcurrentPrice(activeConnection, leg.getExchange(), leg.getSymbol(), leg.getToken());
                    }
                }

                // Resolves the true unblended broker execution price from TradeBook
                BigDecimal entryPrice = isLiveTrade(leg)
                        ? resolveLiveEntryPrice(leg, activeConnection)
                        : leg.getAskPrice();

                if (currentLtp != null && currentLtp.compareTo(BigDecimal.ZERO) > 0
                        && entryPrice != null && entryPrice.compareTo(BigDecimal.ZERO) > 0) {
                    boolean isShort = isShortPosition(leg, config);
                    BigDecimal pointsDiff = isShort
                            ? entryPrice.subtract(currentLtp)
                            : currentLtp.subtract(entryPrice);
                    legPnL = pointsDiff.multiply(BigDecimal.valueOf(leg.getQuantity()));
                } else {
                    allLegsPriced = false;
                }
            } catch (Exception e) {
                log.error("❌ [MONITOR] PnL calc failed for {}: {}", leg.getSymbol(), e.getMessage());
                allLegsPriced = false;
            }

            combinedGroupPnL = combinedGroupPnL.add(legPnL);
            liveUiCachePnL.put(leg.getId(), legPnL);
        }

        // 🛑 MASTER TOGGLE: IF FLAG IS 'N', TURN OFF ALL RISK FUNCTIONS
        if (config == null || !SMART_RISK_ACTIVE.equalsIgnoreCase(config.getSmartRiskFlag())) {
            return false;
        }

        Orders primaryLeg = groupLegs.get(0);
        BigDecimal totalQuantity = groupLegs.stream()
                .map(Orders::getQuantity).filter(Objects::nonNull).map(BigDecimal::valueOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal maxLossThreshold = config.getMaxLossLimit();
        BigDecimal targetProfitThreshold = config.getTargetProfit();

        if (maxLossThreshold == null && primaryLeg.getSl() != null && primaryLeg.getSl().compareTo(BigDecimal.ZERO) > 0) {
            maxLossThreshold = primaryLeg.getSl().multiply(totalQuantity).negate();
        }
        if (targetProfitThreshold == null && primaryLeg.getTarget() != null && primaryLeg.getTarget().compareTo(BigDecimal.ZERO) > 0) {
            targetProfitThreshold = primaryLeg.getTarget().multiply(totalQuantity);
        }

        if (maxLossThreshold != null) {
            BigDecimal absoluteMaxLoss = maxLossThreshold.abs().negate();
            if (combinedGroupPnL.compareTo(absoluteMaxLoss) <= 0) {
                return closeGroup(groupLegs, strategyKey, "HARD_MAX_LOSS_BREACHED", combinedGroupPnL, activeConnection, config);
            }
        }

        if (targetProfitThreshold != null && targetProfitThreshold.compareTo(BigDecimal.ZERO) > 0) {
            if (combinedGroupPnL.compareTo(targetProfitThreshold) >= 0) {
                return closeGroup(groupLegs, strategyKey, "FIXED_TARGET_PROFIT_HIT", combinedGroupPnL, activeConnection, config);
            }
        }

        BigDecimal velocityDrop = config.getVelocityPanicDrop();
        if (velocityDrop != null && velocityDrop.compareTo(BigDecimal.ZERO) > 0) {
            if (!allLegsPriced) {
                log.debug("⚠️ [MONITOR] {} | Skipping velocity check this tick.", strategyKey);
            } else {
                BigDecimal lastPnl = lastPnlSnapshot.get(strategyKey);
                if (lastPnl != null) {
                    BigDecimal drop = lastPnl.subtract(combinedGroupPnL);
                    if (drop.compareTo(velocityDrop) >= 0) {
                        int streak = panicStreak.merge(strategyKey, 1, Integer::sum);
                        if (streak >= 2) {
                            panicStreak.remove(strategyKey);
                            lastPnlSnapshot.remove(strategyKey);
                            return closeGroup(groupLegs, strategyKey, "VELOCITY_PANIC_DROP_BREACHED", combinedGroupPnL, activeConnection, config);
                        }
                    } else {
                        panicStreak.remove(strategyKey);
                    }
                }
                lastPnlSnapshot.put(strategyKey, combinedGroupPnL);
            }
        }

        BigDecimal peakPnL = highWaterMarks.get(strategyKey);
        if (peakPnL == null) {
            peakPnL = config.getCurrentPeakPnl() != null ? config.getCurrentPeakPnl() : combinedGroupPnL;
            highWaterMarks.put(strategyKey, peakPnL);
        }

        if (combinedGroupPnL.compareTo(peakPnL) > 0) {
            peakPnL = combinedGroupPnL;
            highWaterMarks.put(strategyKey, peakPnL);
            config.setCurrentPeakPnl(peakPnL);
            riskConfigRepository.save(config);
        }

        if (targetProfitThreshold != null && config.getMilestonePercent() != null && config.getBreakevenFloor() != null) {
            BigDecimal milestoneActivation = targetProfitThreshold.multiply(config.getMilestonePercent());
            if (peakPnL.compareTo(milestoneActivation) >= 0) {
                if (combinedGroupPnL.compareTo(config.getBreakevenFloor()) <= 0) {
                    return closeGroup(groupLegs, strategyKey, "MILESTONE_PROFIT_PROTECTION_TRIGGERED", combinedGroupPnL, activeConnection, config);
                }
            }
        }

        BigDecimal activation = config.getTrailingActivation();
        BigDecimal drawdownPct = config.getTrailingDrawdownPct();

        if (activation != null && drawdownPct != null && peakPnL.compareTo(activation) >= 0) {
            BigDecimal allowedDrop = peakPnL.multiply(drawdownPct);
            BigDecimal dynamicFloor = peakPnL.subtract(allowedDrop);
            BigDecimal existingFloor = activeTrailingFloors.get(strategyKey);

            if (existingFloor == null) {
                existingFloor = config.getCurrentTrailingFloor();
                if (existingFloor != null) {
                    activeTrailingFloors.put(strategyKey, existingFloor);
                }
            }

            if (existingFloor == null || dynamicFloor.compareTo(existingFloor) > 0) {
                activeTrailingFloors.put(strategyKey, dynamicFloor);
                config.setCurrentTrailingFloor(dynamicFloor);
                riskConfigRepository.save(config);
            }

            BigDecimal currentFloor = activeTrailingFloors.get(strategyKey);
            if (currentFloor != null && combinedGroupPnL.compareTo(currentFloor) <= 0) {
                return closeGroup(groupLegs, strategyKey, "RUBBER_BAND_MAX_DRAWDOWN_BREACHED", combinedGroupPnL, activeConnection, config);
            }
        }

        return false;
    }

    /**
     * Fetch TRUE execution price matching the exact orderID from the TRADE BOOK.
     * Automatically handles partial fills by calculating weighted average fill price.
     */
    private BigDecimal resolveLiveEntryPrice(Orders leg, SmartConnect connection) {
        BigDecimal cached = reconciledEntryPriceCache.get(leg.getId());
        if (cached != null) return cached;

        BigDecimal dbAskPrice = leg.getAskPrice();
        BigDecimal resolvedPrice = dbAskPrice != null ? dbAskPrice : BigDecimal.ZERO;

        if (connection == null || leg.getOrderid() == null) return resolvedPrice;

        try {
            JSONArray trades = getTradeBookDebounced(connection);
            if (trades != null && trades.length() > 0) {
                BigDecimal totalValue = BigDecimal.ZERO;
                int totalQty = 0;

                for (int i = 0; i < trades.length(); i++) {
                    JSONObject trade = trades.getJSONObject(i);
                    String tOrderId = trade.optString("orderid", trade.optString("order_id"));

                    if (leg.getOrderid().equals(tOrderId)) {
                        String priceStr = trade.optString("fillprice", trade.optString("average_price", trade.optString("averageprice", "0")));
                        String qtyStr = trade.optString("fillsize", trade.optString("quantity", "0"));

                        BigDecimal fillPrice = new BigDecimal(priceStr);
                        int fillQty = Integer.parseInt(qtyStr);

                        totalValue = totalValue.add(fillPrice.multiply(BigDecimal.valueOf(fillQty)));
                        totalQty += fillQty;
                    }
                }

                if (totalQty > 0) {
                    BigDecimal brokerPrice = totalValue.divide(BigDecimal.valueOf(totalQty), 2, RoundingMode.HALF_UP);

                    if (dbAskPrice == null || brokerPrice.compareTo(dbAskPrice) != 0) {
                        log.info("✅ [PNL-SYNC] Leg {} | DB AskPrice: {} -> Real Broker Fill (TradeBook): {}",
                                leg.getSymbol(), dbAskPrice, brokerPrice);
                        correctAskPriceInDb(leg, brokerPrice);
                    }
                    resolvedPrice = brokerPrice;
                    reconciledEntryPriceCache.put(leg.getId(), resolvedPrice);
                }
            }
        } catch (Exception e) {
            log.error("❌ [MONITOR] Could not reconcile entry price for leg {}: {}", leg.getId(), e.getMessage());
        }

        return resolvedPrice;
    }

    @Transactional
    public void correctAskPriceInDb(Orders leg, BigDecimal brokerPrice) {
        leg.setAskPrice(brokerPrice);
        ordersRepository.save(leg);
    }

    private boolean closeGroup(List<Orders> group, String strategyKey, String exitReason,
                               BigDecimal closurePnL, SmartConnect backupConnection, RiskConfiguration config) {

        log.warn("====================================================");
        log.warn("🛑 [MONITOR] STRATEGY LIQUIDATED: {}", strategyKey);
        log.warn("🛑 Reason     : {}", exitReason);
        log.warn("====================================================");

        SmartConnect connection = backupConnection;
        try {
            if (connection == null) connection = angelOne.signIn();
        } catch (Exception e) {
            log.error("❌ [MONITOR] Broker auth failed during close: {}", e.getMessage());
        }

        Map<Long, String> exitOrderIds = new HashMap<>();

        for (Orders leg : group) {
            leg.setTradePhase(PHASE_EXIT_IN_PROGRESS);
            ordersRepository.saveAndFlush(leg);

            boolean isLiveTrade = isLiveTrade(leg);
            boolean orderPlaced = !isLiveTrade;

            if (isLiveTrade && connection != null) {
                Token exitToken = new Token();
                exitToken.setSymbol(leg.getSymbol());
                exitToken.setToken(leg.getToken());
                exitToken.setExch_seg(leg.getExchange());
                exitToken.setQuantity(leg.getQuantity());
                exitToken.setOrderType(Constants.ORDER_TYPE_MARKET);
                exitToken.setProductType(Constants.PRODUCT_CARRYFORWARD);
                exitToken.setVariety(Constants.VARIETY_NORMAL);

                boolean isShort = isShortPosition(leg, config);
                exitToken.setTransactionType(isShort ? Constants.TRANSACTION_TYPE_BUY : Constants.TRANSACTION_TYPE_SELL);

                int attempt = 0;
                long backoff = 500;
                while (attempt < 3 && !orderPlaced) {
                    try {
                        Token response = angelOneService.placeOrder(connection, exitToken);
                        if (response != null && response.getOrderId() != null) {
                            log.info("   -> ✅ [MONITOR] Live leg exited | {} | OrderID: {}", leg.getSymbol(), response.getOrderId());
                            orderPlaced = true;
                            exitOrderIds.put(leg.getId(), response.getOrderId());
                        } else {
                            throw new SmartAPIException("Empty Order ID");
                        }
                    } catch (Exception | SmartAPIException e) {
                        attempt++;
                        if (attempt >= 3) break;
                        try { Thread.sleep(backoff); backoff *= 2; } catch (InterruptedException ie) { break; }
                    }
                }
            }

            if (!orderPlaced) {
                leg.setTradePhase(PHASE_ENTRY);
                ordersRepository.saveAndFlush(leg);
                continue;
            }

            // Fallback Mathematical Exit (Used only if real broker sync fails)
            BigDecimal finalLegPnL = liveUiCachePnL.getOrDefault(leg.getId(), BigDecimal.ZERO);
            BigDecimal calculatedExitPrice = leg.getAskPrice() != null ? leg.getAskPrice() : BigDecimal.ZERO;
            if (leg.getAskPrice() != null && leg.getQuantity() > 0) {
                BigDecimal qty = BigDecimal.valueOf(leg.getQuantity());
                BigDecimal pnlPerUnit = finalLegPnL.divide(qty, 4, RoundingMode.HALF_UP);
                calculatedExitPrice = isShortPosition(leg, config)
                        ? leg.getAskPrice().subtract(pnlPerUnit)
                        : leg.getAskPrice().add(pnlPerUnit);
                calculatedExitPrice = calculatedExitPrice.setScale(2, RoundingMode.HALF_UP);
            }

            liveUiCachePnL.remove(leg.getId());
            reconciledEntryPriceCache.remove(leg.getId());

            leg.setActive(0);
            leg.setStatus(STATUS_CLOSED);
            leg.setTradePhase(PHASE_EXIT);
            leg.setClosedOn(LocalDateTime.now(MARKET_ZONE));
            leg.setExitReason(exitReason);
            leg.setPl(finalLegPnL);
            leg.setExitPrice(calculatedExitPrice);
        }

        // ---- POST-EXIT REAL BROKER RECONCILIATION VIA TRADE BOOK ----
        if (!exitOrderIds.isEmpty() && connection != null) {
            try {
                log.info("⏳ Waiting 1.5s for broker to fill exit market orders...");
                Thread.sleep(1500);

                lastTradeBookFetchTime = 0; // Force refresh
                JSONArray trades = getTradeBookDebounced(connection);

                if (trades != null && trades.length() > 0) {
                    for (Orders leg : group) {
                        String eOrderId = exitOrderIds.get(leg.getId());
                        if (eOrderId != null) {
                            BigDecimal totalExitValue = BigDecimal.ZERO;
                            int totalExitQty = 0;

                            for (int i = 0; i < trades.length(); i++) {
                                JSONObject trade = trades.getJSONObject(i);
                                String tOrderId = trade.optString("orderid", trade.optString("order_id"));

                                if (eOrderId.equals(tOrderId)) {
                                    String priceStr = trade.optString("fillprice", trade.optString("average_price", trade.optString("averageprice", "0")));
                                    String qtyStr = trade.optString("fillsize", trade.optString("quantity", "0"));

                                    BigDecimal fillPrice = new BigDecimal(priceStr);
                                    int fillQty = Integer.parseInt(qtyStr);

                                    totalExitValue = totalExitValue.add(fillPrice.multiply(BigDecimal.valueOf(fillQty)));
                                    totalExitQty += fillQty;
                                }
                            }

                            if (totalExitQty > 0) {
                                BigDecimal brokerExit = totalExitValue.divide(BigDecimal.valueOf(totalExitQty), 2, RoundingMode.HALF_UP);
                                leg.setExitPrice(brokerExit);

                                boolean isShort = isShortPosition(leg, config);
                                BigDecimal realPointsDiff = isShort
                                        ? leg.getAskPrice().subtract(brokerExit)
                                        : brokerExit.subtract(leg.getAskPrice());
                                BigDecimal realPnL = realPointsDiff.multiply(BigDecimal.valueOf(leg.getQuantity()));

                                leg.setPl(realPnL);
                                log.info("🎯 [REAL-EXIT] Leg {} synced to broker exit: {} | True PnL: {}",
                                        leg.getSymbol(), brokerExit, realPnL);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("❌ Failed real exit reconciliation: {}", e.getMessage());
            }
        }

        // FINAL DB SAVE AND TELEGRAM
        int closedCount = 0;
        BigDecimal totalRupeePnL = BigDecimal.ZERO;

        for (Orders leg : group) {
            if (leg.getStatus().equals(STATUS_CLOSED)) {
                ordersRepository.save(leg);
                totalRupeePnL = totalRupeePnL.add(leg.getPl() != null ? leg.getPl() : BigDecimal.ZERO);
                closedCount++;
            }
        }

        highWaterMarks.remove(strategyKey);
        activeTrailingFloors.remove(strategyKey);
        lastPnlSnapshot.remove(strategyKey);
        panicStreak.remove(strategyKey);

        if (closedCount > 0) {
            String emoji = totalRupeePnL.signum() >= 0 ? "✅" : "❌";
            telegramService.sendMessage(String.format(
                    "%s **RISK EXIT [%s]**\nReason: %s\nPnL: **₹%.2f**\nLegs Closed: %d/%d",
                    emoji, strategyKey, exitReason, totalRupeePnL, closedCount, group.size()));
        }

        return closedCount > 0;
    }

    private boolean isLiveTrade(Orders leg) {
        return leg.getOrderid() != null && !PAPER_ORDER_ID_MARKER.equals(leg.getOrderid());
    }

    private boolean isShortPosition(Orders leg, RiskConfiguration config) {
        if (config != null && config.getStrategyType() != null) return "OPTION_SELL".equalsIgnoreCase(config.getStrategyType());
        if (leg.getType() != null && !leg.getType().isBlank()) return "SELL".equalsIgnoreCase(leg.getType());
        if (leg.getSignal() != null && leg.getSignal().toUpperCase().contains("SHORT")) return true;
        return false;
    }

    private ExchangeType mapExchangeToType(String exchange) {
        if (exchange == null) return null;
        switch (exchange.toUpperCase().trim()) {
            case "NFO": return ExchangeType.NSE_FO;
            case "MCX": return ExchangeType.MCX_FO;
            case "NSE": return ExchangeType.NSE_CM;
            case "BSE": return ExchangeType.BSE_CM;
            case "BFO": return ExchangeType.BSE_FO;
            default: return null;
        }
    }
}