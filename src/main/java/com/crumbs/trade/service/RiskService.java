package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.utils.Constants;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.RiskConfiguration;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.RiskConfigurationRepository;

@Service
public class RiskService {

    private static final Logger logger = LoggerFactory.getLogger(RiskService.class);

    private static final String EXCHANGE_NFO = "NFO";
    private static final String EXCHANGE_BSE = "BSE";
    private static final String EXCHANGE_NSE = "NSE";
    private static final String EXCHANGE_MCX = "MCX";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String PHASE_EXIT = "EXIT";
    private static final String SIDE_BUY = "BUY";
    private static final String SMART_RISK_ACTIVE = "Y";
    private static final String PAPER_ORDER_ID_MARKER = "1";
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    @Autowired
    private AngelOneService angelOneService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private RiskConfigurationRepository riskConfigRepository;

    @Autowired
    private AngelOne angelOne;

    @Autowired
    private AngelWebSocketService webSocketService;

    @Autowired
    @Lazy
    private RiskService self;

    // --- MEMORY CACHES ---
    private final Map<Long, BigDecimal> liveUiCachePnL = new ConcurrentHashMap<>();
    
    // Tracks HWM and Floors using Strategy Name as the key
    private final Map<String, BigDecimal> highWaterMarks = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> activeTrailingFloors = new ConcurrentHashMap<>();
    
    // Decoupled global broker cache
    private final Map<String, BigDecimal> globalLiveBrokerPnL = new ConcurrentHashMap<>();

    public Map<Long, BigDecimal> getLivePnLForUI() {
        return liveUiCachePnL;
    }

    /**
     * BACKGROUND DATA THREAD: Polls positions every 3 seconds to avoid 429 Rate Limits.
     */
    @Scheduled(fixedDelay = 3000)
    public void pollBrokerPositions() {
        int maxRetries = 3;
        int attempt = 0;
        long backoffDelay = 1000;

        while (attempt < maxRetries) {
            try {
                SmartConnect connection = angelOne.signIn();
                JSONObject rawPositionResponse = angelOneService.getRawPositions();
                
                if (rawPositionResponse != null && rawPositionResponse.optBoolean("status", false)) {
                    JSONArray positions = rawPositionResponse.optJSONArray("data");
                    if (positions != null) {
                        Map<String, BigDecimal> freshPnL = new HashMap<>();
                        for (int i = 0; i < positions.length(); i++) {
                            JSONObject pos = positions.getJSONObject(i);
                            String pnlString = pos.optString("pnl", "0.00");
                            freshPnL.put(pos.optString("symboltoken", ""), new BigDecimal(pnlString));
                        }
                        globalLiveBrokerPnL.clear();
                        globalLiveBrokerPnL.putAll(freshPnL);
                        return; 
                    }
                } else {
                    throw new RuntimeException("API returned empty data.");
                }
            } catch (Exception e) {
                attempt++;
                if (attempt >= maxRetries) {
                    logger.error("🚨 [SYSTEM] Broker API Unresponsive. Falling back to internal caches.");
                    break;
                }
                try {
                    Thread.sleep(backoffDelay);
                    backoffDelay *= 2; 
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * RISK EVALUATOR: Runs every 1 second, reading from memory caches.
     */
    @Scheduled(fixedDelay = 1000)
    public void processSystemRiskMatrix() {
        LocalDateTime now = LocalDateTime.now(MARKET_ZONE);
        int currentHour = now.getHour();
        int currentMinute = now.getMinute();

        // ONLY pulls legs where active = 1 (Ignores morning trades that are already closed)
        List<Orders> rawOpenOrders = orderRepository.findByActive(1);
        if (rawOpenOrders == null || rawOpenOrders.isEmpty()) {
            clearAllMemoryCaches();
            return;
        }

        // Heartbeat log
        if (now.getSecond() == 0) {
            logger.info("⚙️ [SYSTEM] Risk Engine Active | Monitoring {} live DB rows | UI Cache Size: {}", rawOpenOrders.size(), liveUiCachePnL.size());
        }

        List<Orders> openOrders = rawOpenOrders.stream().filter(order -> {
            String exch = order.getExchange() != null ? order.getExchange().toUpperCase() : "";
            if ((EXCHANGE_NFO.equals(exch) || EXCHANGE_BSE.equals(exch) || EXCHANGE_NSE.equals(exch)) 
                    && (currentHour >= 16 || (currentHour == 15 && currentMinute > 30))) {
                return false; 
            }
            if (EXCHANGE_MCX.equals(exch) && currentHour < 9) {
                return false; 
            }
            return true;
        }).collect(Collectors.toList());

        if (openOrders.isEmpty()) {
            if (now.getSecond() == 0) {
                logger.info("💤 [SYSTEM] Market Idle | {} rows found, but 0 are active in this trading session.", rawOpenOrders.size());
            }
            return; 
        }

        Set<String> strategyIdentifiers = openOrders.stream()
                .map(Orders::getName)
                .filter(Objects::nonNull)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toSet());

        Map<String, RiskConfiguration> configMap = new ConcurrentHashMap<>();
        if (!strategyIdentifiers.isEmpty()) {
            List<RiskConfiguration> configs = riskConfigRepository.findAllById(strategyIdentifiers);
            configMap = configs.stream()
                    .collect(Collectors.toMap(RiskConfiguration::getStrategyName, Function.identity())); 
        }

        Map<String, BigDecimal> brokerPnLMap = new ConcurrentHashMap<>(globalLiveBrokerPnL);

        // GROUPING: Combine all active legs under their unique Strategy Name
        Function<Orders, String> strategyNameClassifier = order -> 
                (order.getName() != null && !order.getName().trim().isEmpty()) 
                    ? order.getName().trim() 
                    : "ORPHAN_" + order.getId();

        Map<String, List<Orders>> strategyGroups = openOrders.stream()
                .collect(Collectors.groupingBy(strategyNameClassifier));

        // Purge dead memory
        Set<Long> activeOrderIds = openOrders.stream().map(Orders::getId).collect(Collectors.toSet());
        liveUiCachePnL.keySet().retainAll(activeOrderIds);
        highWaterMarks.keySet().retainAll(strategyGroups.keySet());
        activeTrailingFloors.keySet().retainAll(strategyGroups.keySet());

        SmartConnect connection = null;
        try {
            connection = angelOne.signIn(); 
        } catch (Exception e) {
            logger.error("❌ [SYSTEM] Broker Auth Failed: {}", e.getMessage());
            return; // Needs connection for live execution safety
        }

        // EVALUATE GROUPS (1 leg, 2 legs, 4 legs)
        for (Map.Entry<String, List<Orders>> entry : strategyGroups.entrySet()) {
            String strategyKey = entry.getKey(); // Example: "SHORT_STRADDLE_NIFTY"
            List<Orders> groupLegs = entry.getValue();
            
            RiskConfiguration config = configMap.get(strategyKey);
            BigDecimal combinedGroupPnL = BigDecimal.ZERO;
            boolean skipGroupEvaluation = false;
            List<BigDecimal> calculatedLegPnLs = new ArrayList<>();

            for (Orders leg : groupLegs) {
                boolean isLiveTrade = leg.getOrderid() != null && !PAPER_ORDER_ID_MARKER.equals(leg.getOrderid());
                BigDecimal legPnL = BigDecimal.ZERO;

                if (isLiveTrade) {
                    if (brokerPnLMap.isEmpty()) {
                        skipGroupEvaluation = true; 
                        break;
                    }
                    legPnL = brokerPnLMap.getOrDefault(leg.getToken(), BigDecimal.ZERO);
                } else {
                    try {
                        BigDecimal currentLtp = BigDecimal.ZERO;
                        com.angelbroking.smartapi.smartstream.models.ExchangeType exchangeType = mapExchangeToType(leg.getExchange());
                        
                        if (exchangeType != null) {
                            webSocketService.subscribe(exchangeType, leg.getToken());
                            currentLtp = webSocketService.getLatestLTP(exchangeType, leg.getToken());
                        }
                        
                        if (currentLtp == null || currentLtp.compareTo(BigDecimal.ZERO) == 0) {
                            if (connection != null) {
                                currentLtp = angelOneService.getcurrentPrice(connection, leg.getExchange(), leg.getSymbol(), leg.getToken());
                            }
                        }

                        if (currentLtp != null && currentLtp.compareTo(BigDecimal.ZERO) > 0 && leg.getAskPrice() != null) {
                            BigDecimal pointsDiff = SIDE_BUY.equalsIgnoreCase(leg.getType())
                                ? currentLtp.subtract(leg.getAskPrice())
                                : leg.getAskPrice().subtract(currentLtp);
                            legPnL = pointsDiff.multiply(BigDecimal.valueOf(leg.getQuantity()));
                        }
                    } catch (Exception e) {
                        logger.error("❌ [SYSTEM] Math Error on Paper Leg {}: {}", leg.getId(), e.getMessage());
                    }
                }
                
                combinedGroupPnL = combinedGroupPnL.add(legPnL);
                calculatedLegPnLs.add(legPnL);
                liveUiCachePnL.put(leg.getId(), legPnL);
            }

            if (!skipGroupEvaluation) {
                self.syncActiveLegPnLsToDb(groupLegs, calculatedLegPnLs);
                evaluateGroupRisk(groupLegs, strategyKey, combinedGroupPnL, connection, config);
            }
        }
    }

    @Transactional
    public void syncActiveLegPnLsToDb(List<Orders> groupLegs, List<BigDecimal> legPnLs) {
        for (int i = 0; i < groupLegs.size(); i++) {
            Orders leg = groupLegs.get(i);
            BigDecimal currentPnL = legPnLs.get(i);
            if (leg.getPl() == null || leg.getPl().compareTo(currentPnL) != 0) {
                leg.setPl(currentPnL);
                orderRepository.save(leg);
            }
        }
    }

    private void evaluateGroupRisk(List<Orders> group, String strategyKey, BigDecimal currentCombinedPnL, 
                                   SmartConnect connection, RiskConfiguration config) {
        
        Orders primaryLeg = group.get(0);
        BigDecimal totalQuantity = group.stream()
                .map(Orders::getQuantity)
                .filter(Objects::nonNull)
                .map(BigDecimal::valueOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal maxLossThreshold = null;
        BigDecimal targetProfitThreshold = null;

        if (config != null) {
            maxLossThreshold = config.getMaxLossLimit(); 
            targetProfitThreshold = config.getTargetProfit(); 
        } else {
            if (LocalDateTime.now(MARKET_ZONE).getSecond() == 0) {
                logger.warn("⚠️ [WARN] Strategy '{}' missing from risk_configuration! Using leg fallback rules.", strategyKey);
            }
            if (primaryLeg.getSl() != null && primaryLeg.getSl().compareTo(BigDecimal.ZERO) > 0) {
                maxLossThreshold = primaryLeg.getSl().multiply(totalQuantity).negate();
            }
            if (primaryLeg.getTarget() != null && primaryLeg.getTarget().compareTo(BigDecimal.ZERO) > 0) {
                targetProfitThreshold = primaryLeg.getTarget().multiply(totalQuantity);
            }
        }

        if (maxLossThreshold != null) {
            BigDecimal absoluteMaxLoss = maxLossThreshold.abs().negate();
            if (currentCombinedPnL.compareTo(absoluteMaxLoss) <= 0) {
                terminateEntireGroup(group, strategyKey, "HARD_MAX_LOSS_BREACHED", currentCombinedPnL, connection);
                return;
            }
        }

        if (targetProfitThreshold != null && targetProfitThreshold.compareTo(BigDecimal.ZERO) > 0) {
            if (currentCombinedPnL.compareTo(targetProfitThreshold) >= 0) {
                terminateEntireGroup(group, strategyKey, "FIXED_TARGET_PROFIT_HIT", currentCombinedPnL, connection);
                return;
            }
        }

        if (config == null || !SMART_RISK_ACTIVE.equalsIgnoreCase(config.getSmartRiskFlag())) {
            return; 
        }

        BigDecimal peakPnL = highWaterMarks.getOrDefault(strategyKey, currentCombinedPnL);
        if (currentCombinedPnL.compareTo(peakPnL) > 0) {
            peakPnL = currentCombinedPnL;
            highWaterMarks.put(strategyKey, peakPnL);
        }

        if (targetProfitThreshold != null && config.getMilestonePercent() != null && config.getBreakevenFloor() != null) {
            BigDecimal milestoneActivation = targetProfitThreshold.multiply(config.getMilestonePercent()); 
            if (peakPnL.compareTo(milestoneActivation) >= 0) {
                if (currentCombinedPnL.compareTo(config.getBreakevenFloor()) <= 0) {
                    terminateEntireGroup(group, strategyKey, "MILESTONE_PROFIT_PROTECTION_TRIGGERED", currentCombinedPnL, connection);
                    return;
                }
            }
        }

        BigDecimal activation = config.getTrailingActivation();
        BigDecimal drawdownPct = config.getTrailingDrawdownPct(); 

        if (activation != null && drawdownPct != null && peakPnL.compareTo(activation) >= 0) {
            BigDecimal allowedDrop = peakPnL.multiply(drawdownPct);
            BigDecimal dynamicFloor = peakPnL.subtract(allowedDrop);
            BigDecimal existingFloor = activeTrailingFloors.get(strategyKey);

            if (existingFloor == null || dynamicFloor.compareTo(existingFloor) > 0) {
                // Log only if floor moved up by at least 1.00 to prevent decimal micro-spam
                if (existingFloor == null || dynamicFloor.subtract(existingFloor).compareTo(BigDecimal.ONE) >= 0) {
                    logger.info("📈 [MONITOR] {} | Peak: {} | Trailing Stop Floor raised to: {}", 
                        strategyKey, 
                        peakPnL.setScale(2, RoundingMode.HALF_UP), 
                        dynamicFloor.setScale(2, RoundingMode.HALF_UP));
                }
                activeTrailingFloors.put(strategyKey, dynamicFloor);
            }

            BigDecimal currentFloor = activeTrailingFloors.get(strategyKey);
            if (currentFloor != null && currentCombinedPnL.compareTo(currentFloor) <= 0) {
                terminateEntireGroup(group, strategyKey, "RUBBER_BAND_MAX_DRAWDOWN_BREACHED", currentCombinedPnL, connection);
                return;
            }
        }
    }

    private void terminateEntireGroup(List<Orders> group, String strategyKey, String exitReason, 
                                      BigDecimal closurePnL, SmartConnect backupConnection) {
        
        logger.warn("====================================================");
        logger.warn("🛑 [ACTION] STRATEGY LIQUIDATED : {}", strategyKey);
        logger.warn("🛑 Reason     : {}", exitReason);
        logger.warn("🛑 Final PnL  : {}", closurePnL.setScale(2, RoundingMode.HALF_UP));
        logger.warn("🛑 Leg Count  : {}", group.size());
        logger.warn("====================================================");

        SmartConnect connection = null;
        try {
            connection = angelOne.signIn();
        } catch (Exception e) {
            connection = backupConnection;
        }

        for (Orders leg : group) {
            boolean isLiveTrade = leg.getOrderid() != null && !PAPER_ORDER_ID_MARKER.equals(leg.getOrderid());

            if (isLiveTrade && connection != null) {
                int maxExitRetries = 3;
                int exitAttempt = 0;
                long exitBackoff = 500; 
                boolean orderPlaced = false;

                Token exitToken = new Token();
                exitToken.setSymbol(leg.getSymbol());
                exitToken.setToken(leg.getToken());
                exitToken.setExch_seg(leg.getExchange());
                exitToken.setQuantity(leg.getQuantity());
                exitToken.setOrderType(Constants.ORDER_TYPE_MARKET);
                exitToken.setProductType(Constants.PRODUCT_CARRYFORWARD); 
                exitToken.setVariety(Constants.VARIETY_NORMAL);
                
                String exitSide = SIDE_BUY.equalsIgnoreCase(leg.getType()) 
                    ? Constants.TRANSACTION_TYPE_SELL 
                    : Constants.TRANSACTION_TYPE_BUY;
                exitToken.setTransactionType(exitSide);

                while (exitAttempt < maxExitRetries && !orderPlaced) {
                    try {
                        Token responseToken = angelOneService.placeOrder(connection, exitToken);
                        if (responseToken != null && responseToken.getOrderId() != null) {
                            logger.info("   -> ✅ [EXECUTION] LIVE Leg Executed | Symbol: {} | OrderID: {}", leg.getSymbol(), responseToken.getOrderId());
                            orderPlaced = true;
                        } else {
                            throw new SmartAPIException("Empty Order ID");
                        }
                    } catch (Exception | SmartAPIException e) {
                        exitAttempt++;
                        logger.error("   -> ⚠️ [WARN] Execution Failed for {} (Attempt {}/{}): {}", leg.getSymbol(), exitAttempt, maxExitRetries, e.getMessage());
                        if (exitAttempt >= maxExitRetries) break;
                        try { Thread.sleep(exitBackoff); exitBackoff *= 2; } 
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                }
                if (!orderPlaced) continue; 

            } else {
                logger.info("   -> 📝 [EXECUTION] PAPER Leg Simulated | Symbol: {} | DB Status Updated", leg.getSymbol());
            }
        }

        highWaterMarks.remove(strategyKey);
        activeTrailingFloors.remove(strategyKey);

        for (Orders leg : group) {
            BigDecimal finalLegPnL = liveUiCachePnL.getOrDefault(leg.getId(), BigDecimal.ZERO);
            liveUiCachePnL.remove(leg.getId());

            BigDecimal calculatedExitPrice = BigDecimal.ZERO;
            if (leg.getAskPrice() != null && leg.getQuantity() > 0) {
                BigDecimal qty = BigDecimal.valueOf(leg.getQuantity());
                BigDecimal pnlPerUnit = finalLegPnL.divide(qty, 4, RoundingMode.HALF_UP);
                
                if (SIDE_BUY.equalsIgnoreCase(leg.getType())) {
                    calculatedExitPrice = leg.getAskPrice().add(pnlPerUnit);
                } else {
                    calculatedExitPrice = leg.getAskPrice().subtract(pnlPerUnit);
                }
                calculatedExitPrice = calculatedExitPrice.setScale(2, RoundingMode.HALF_UP);
            } else if (leg.getAskPrice() != null) {
                calculatedExitPrice = leg.getAskPrice(); 
            }

            self.persistClosedOrderToDb(leg, exitReason, finalLegPnL, calculatedExitPrice);
        }
    }

    @Transactional
    public void persistClosedOrderToDb(Orders order, String exitReason, BigDecimal finalLegPnL, BigDecimal exitPrice) {
        order.setActive(0);
        order.setStatus(STATUS_CLOSED);
        order.setTradePhase(PHASE_EXIT);
        order.setClosedOn(LocalDateTime.now(MARKET_ZONE));
        order.setExitReason(exitReason);
        order.setPl(finalLegPnL); 

        if (exitPrice != null && exitPrice.compareTo(BigDecimal.ZERO) > 0) {
            order.setExitPrice(exitPrice);
        }
        orderRepository.save(order);
    }

    private void clearAllMemoryCaches() {
        if (!liveUiCachePnL.isEmpty()) {
            liveUiCachePnL.clear();
            highWaterMarks.clear();
            activeTrailingFloors.clear();
            globalLiveBrokerPnL.clear();
            logger.info("🧹 [SYSTEM] Engine Flushed | Zero active trades remaining.");
        }
    }

    private com.angelbroking.smartapi.smartstream.models.ExchangeType mapExchangeToType(String exchange) {
        if (exchange == null) return null;
        switch (exchange.toUpperCase().trim()) {
            case "NFO": return com.angelbroking.smartapi.smartstream.models.ExchangeType.NSE_FO;
            case "MCX": return com.angelbroking.smartapi.smartstream.models.ExchangeType.MCX_FO;
            case "NSE": return com.angelbroking.smartapi.smartstream.models.ExchangeType.NSE_CM;
            case "BSE": return com.angelbroking.smartapi.smartstream.models.ExchangeType.BSE_CM;
            case "BFO": return com.angelbroking.smartapi.smartstream.models.ExchangeType.BSE_FO;
            default: return null;
        }
    }
}