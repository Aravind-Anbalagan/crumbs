package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
    @Lazy
    private RiskService self;

    // --- SPLIT MEMORY CACHES ---
    // 1. UI Cache: Keeps the Long ID for individual rows so the frontend DOES NOT BREAK
    private final Map<Long, BigDecimal> liveUiCachePnL = new ConcurrentHashMap<>();
    
    // 2. Risk Engine Caches: Uses String (trade_cycle_id) to track the combined strategy peaks
    private final Map<String, BigDecimal> highWaterMarks = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> activeTrailingFloors = new ConcurrentHashMap<>();

    // Expose only the UI cache to the controller
    public Map<Long, BigDecimal> getLivePnLForUI() {
        return liveUiCachePnL;
    }

    @Scheduled(fixedDelay = 1000)
    public void processSystemRiskMatrix() {
        LocalDateTime now = LocalDateTime.now(MARKET_ZONE);
        int currentHour = now.getHour();
        int currentMinute = now.getMinute();

        List<Orders> rawOpenOrders = orderRepository.findByActive(1);
        if (rawOpenOrders == null || rawOpenOrders.isEmpty()) {
            clearAllMemoryCaches();
            return;
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

        if (openOrders.isEmpty()) return; 

        // Batch Config Fetch
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

        SmartConnect connection = null;
        try {
            connection = angelOne.signIn(); 
        } catch (Exception e) {
            logger.error("Broker authentication failed, skipping risk pass: {}", e.getMessage());
            return;
        }
        
        Map<String, BigDecimal> brokerPnLMap = compileLiveBrokerPnLs();

        // GROUPING: Combine orders by trade_cycle_id to protect multi-leg strategies
        Function<Orders, String> cycleIdClassifier = order -> 
                order.getTradeCycleId() != null ? order.getTradeCycleId() : "SINGLE_" + order.getId();

        Map<String, List<Orders>> strategyGroups = openOrders.stream()
                .collect(Collectors.groupingBy(cycleIdClassifier));

        // Purge dead memory
        Set<Long> activeOrderIds = openOrders.stream().map(Orders::getId).collect(Collectors.toSet());
        liveUiCachePnL.keySet().retainAll(activeOrderIds);
        highWaterMarks.keySet().retainAll(strategyGroups.keySet());
        activeTrailingFloors.keySet().retainAll(strategyGroups.keySet());

        // EVALUATE GROUPS
        for (Map.Entry<String, List<Orders>> entry : strategyGroups.entrySet()) {
            String cycleKey = entry.getKey();
            List<Orders> groupLegs = entry.getValue();
            
            RiskConfiguration config = configMap.get(groupLegs.get(0).getName());
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
                    if (connection == null) {
                        skipGroupEvaluation = true;
                        break;
                    }
                    try {
                        BigDecimal currentLtp = angelOneService.getcurrentPrice(connection, leg.getExchange(), leg.getSymbol(), leg.getToken());
                        if (currentLtp != null && leg.getAskPrice() != null) {
                            BigDecimal pointsDiff = SIDE_BUY.equalsIgnoreCase(leg.getType())
                                ? currentLtp.subtract(leg.getAskPrice())
                                : leg.getAskPrice().subtract(currentLtp);
                            legPnL = pointsDiff.multiply(BigDecimal.valueOf(leg.getQuantity()));
                        }
                    } catch (Exception e) {
                        logger.error("Error calculating paper PnL for Leg ID {}: {}", leg.getId(), e.getMessage());
                    }
                }
                
                // Add individual leg PnL to the combined total
                combinedGroupPnL = combinedGroupPnL.add(legPnL);
                calculatedLegPnLs.add(legPnL);
                
                // Update UI Map with Individual Leg PnL (Keeps frontend happy!)
                liveUiCachePnL.put(leg.getId(), legPnL);
            }

            if (!skipGroupEvaluation) {
                self.syncActiveLegPnLsToDb(groupLegs, calculatedLegPnLs);
                evaluateGroupRisk(groupLegs, cycleKey, combinedGroupPnL, connection, config);
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

    private void evaluateGroupRisk(List<Orders> group, String cycleKey, BigDecimal currentCombinedPnL, 
                                   SmartConnect connection, RiskConfiguration config) {
        
        Orders primaryLeg = group.get(0);
        String strategyName = primaryLeg.getName();
        BigDecimal totalQuantity = group.stream()
                .map(Orders::getQuantity)
                .map(BigDecimal::valueOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal maxLossThreshold = null;
        BigDecimal targetProfitThreshold = null;

        // ENSURE CONFIG TABLE WORKS: Apply limits to the combined P&L
        if (config != null) {
            maxLossThreshold = config.getMaxLossLimit(); 
            targetProfitThreshold = config.getTargetProfit(); 
        } else {
            if (primaryLeg.getSl() != null && primaryLeg.getSl().compareTo(BigDecimal.ZERO) > 0) {
                maxLossThreshold = primaryLeg.getSl().multiply(totalQuantity).negate();
            }
            if (primaryLeg.getTarget() != null && primaryLeg.getTarget().compareTo(BigDecimal.ZERO) > 0) {
                targetProfitThreshold = primaryLeg.getTarget().multiply(totalQuantity);
            }
        }

        // 1. HARD STOP LOSS CHECK (Combined PnL vs Config Table Limit)
        if (maxLossThreshold != null) {
            BigDecimal absoluteMaxLoss = maxLossThreshold.abs().negate();
            if (currentCombinedPnL.compareTo(absoluteMaxLoss) <= 0) {
                terminateEntireGroup(group, cycleKey, "HARD_MAX_LOSS_BREACHED", currentCombinedPnL, connection);
                return;
            }
        }

        // 2. HARD TARGET PROFIT CHECK
        if (targetProfitThreshold != null && targetProfitThreshold.compareTo(BigDecimal.ZERO) > 0) {
            if (currentCombinedPnL.compareTo(targetProfitThreshold) >= 0) {
                terminateEntireGroup(group, cycleKey, "FIXED_TARGET_PROFIT_HIT", currentCombinedPnL, connection);
                return;
            }
        }

        if (config == null || !SMART_RISK_ACTIVE.equalsIgnoreCase(config.getSmartRiskFlag())) {
            return; 
        }

        // 3. VELOCITY SPIKE PROTECTION
        BigDecimal peakPnL = highWaterMarks.getOrDefault(cycleKey, currentCombinedPnL);
        if (currentCombinedPnL.compareTo(peakPnL) > 0) {
            peakPnL = currentCombinedPnL;
            highWaterMarks.put(cycleKey, peakPnL);
        }

        // 4. MILESTONE "NEVER GO RED" PROTECTOR
        if (targetProfitThreshold != null && config.getMilestonePercent() != null && config.getBreakevenFloor() != null) {
            BigDecimal milestoneActivation = targetProfitThreshold.multiply(config.getMilestonePercent()); 
            
            if (peakPnL.compareTo(milestoneActivation) >= 0) {
                if (currentCombinedPnL.compareTo(config.getBreakevenFloor()) <= 0) {
                    terminateEntireGroup(group, cycleKey, "MILESTONE_PROFIT_PROTECTION_TRIGGERED", currentCombinedPnL, connection);
                    return;
                }
            }
        }

        // 5. TRAILING STOP DRAWDOWN ENGINE
        BigDecimal activation = config.getTrailingActivation();
        BigDecimal drawdownPct = config.getTrailingDrawdownPct(); 

        if (activation != null && drawdownPct != null && peakPnL.compareTo(activation) >= 0) {
            BigDecimal allowedDrop = peakPnL.multiply(drawdownPct);
            BigDecimal dynamicFloor = peakPnL.subtract(allowedDrop);

            BigDecimal existingFloor = activeTrailingFloors.get(cycleKey);
            if (existingFloor == null || dynamicFloor.compareTo(existingFloor) > 0) {
                activeTrailingFloors.put(cycleKey, dynamicFloor);
                logger.info("   [↑] Floor Raised! {} trailing stop moved up to {}", strategyName, dynamicFloor);
            }

            BigDecimal currentFloor = activeTrailingFloors.get(cycleKey);
            if (currentFloor != null && currentCombinedPnL.compareTo(currentFloor) <= 0) {
                terminateEntireGroup(group, cycleKey, "RUBBER_BAND_MAX_DRAWDOWN_BREACHED", currentCombinedPnL, connection);
                return;
            }
        }
    }

    private void terminateEntireGroup(List<Orders> group, String cycleKey, String exitReason, 
                                      BigDecimal closurePnL, SmartConnect connection) {
        
        String strategyName = group.get(0).getName();
        logger.warn("🚨 [LIQUIDATED] ========================================");
        logger.warn("🚨 Strategy: {} | Cycle: {}", strategyName, cycleKey);
        logger.warn("🚨 Reason:   {}", exitReason);
        logger.warn("🚨 Final Combined PnL: {}", closurePnL);
        logger.warn("🚨 ====================================================");

        for (Orders leg : group) {
            boolean isLiveTrade = leg.getOrderid() != null && !PAPER_ORDER_ID_MARKER.equals(leg.getOrderid());

            if (isLiveTrade && connection != null) {
                try {
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

                    Token responseToken = angelOneService.placeOrder(connection, exitToken);
                    if (responseToken != null && responseToken.getOrderId() != null) {
                        logger.info("   => Broker exit success for leg: {} | Order ID: {}", leg.getSymbol(), responseToken.getOrderId());
                    }
                } catch (Exception | SmartAPIException e) {
                    logger.error("   => CRITICAL ERROR: Broker exit failed for Leg {}: {}", leg.getId(), e.getMessage());
                    return; 
                }
            } else {
                logger.info("   => Paper Trade leg terminated: {}", leg.getSymbol());
            }
        }

        highWaterMarks.remove(cycleKey);
        activeTrailingFloors.remove(cycleKey);

        for (Orders leg : group) {
            liveUiCachePnL.remove(leg.getId());
            self.persistClosedOrderToDb(leg, exitReason, closurePnL);
        }
    }

    @Transactional
    public void persistClosedOrderToDb(Orders order, String exitReason, BigDecimal closurePnL) {
        order.setActive(0);
        order.setStatus(STATUS_CLOSED);
        order.setTradePhase(PHASE_EXIT);
        order.setClosedOn(LocalDateTime.now(MARKET_ZONE));
        order.setExitReason(exitReason);
        
        // Save the leg's final individual PnL value back to the DB upon closure
        BigDecimal finalLegPnL = liveUiCachePnL.getOrDefault(order.getId(), BigDecimal.ZERO);
        order.setPl(finalLegPnL); 

        orderRepository.save(order);
    }

    private void clearAllMemoryCaches() {
        if (!liveUiCachePnL.isEmpty()) {
            liveUiCachePnL.clear();
            highWaterMarks.clear();
            activeTrailingFloors.clear();
            logger.info("--- Risk Engine Idle: Memory flushed. Zero live trades detected. ---");
        }
    }

    private Map<String, BigDecimal> compileLiveBrokerPnLs() {
        Map<String, BigDecimal> tokenPnLMap = new ConcurrentHashMap<>();
        try {
            JSONObject rawPositionResponse = angelOneService.getRawPositions();
            if (rawPositionResponse != null && rawPositionResponse.optBoolean("status", false)) {
                JSONArray positions = rawPositionResponse.optJSONArray("data");
                if (positions != null) {
                    for (int i = 0; i < positions.length(); i++) {
                        JSONObject pos = positions.getJSONObject(i);
                        String pnlString = pos.optString("pnl", "0.00");
                        BigDecimal pnl = new BigDecimal(pnlString);
                        tokenPnLMap.put(pos.optString("symboltoken", ""), pnl);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching live position book from broker API: {}", e.getMessage());
        }
        return tokenPnLMap;
    }
}