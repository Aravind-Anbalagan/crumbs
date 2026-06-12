package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
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

    @Autowired
    private AngelOneService angelOneService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private RiskConfigurationRepository riskConfigRepository;

    @Autowired
    private AngelOne angelOne;

    // Self-injection to properly route @Transactional calls inside the same class
    @Autowired
    @Lazy
    private RiskService self;

    // --- CLOUD MEMORY CACHES (Preserved as Long to keep UI completely unbroken) ---
    private final Map<Long, BigDecimal> liveUiCachePnL = new ConcurrentHashMap<>();
    private final Map<Long, BigDecimal> highWaterMarks = new ConcurrentHashMap<>();
    private final Map<Long, BigDecimal> activeTrailingFloors = new ConcurrentHashMap<>();

    public Map<Long, BigDecimal> getLivePnLForUI() {
        return liveUiCachePnL;
    }

    /**
     * Master Orchestration Loop called continuously.
     * Scheduled added to ensure it automatically fires every 1 second.
     */
    @Scheduled(fixedDelay = 1000)
    public void processSystemRiskMatrix() {
        LocalDateTime now = LocalDateTime.now();
        int currentHour = now.getHour();
        int currentMinute = now.getMinute();

        // 1. Fetch & Filter active orders based on market hours
        List<Orders> rawOpenOrders = orderRepository.findByActive(1);
        if (rawOpenOrders == null || rawOpenOrders.isEmpty()) {
            clearMemoryCaches();
            return;
        }

        List<Orders> openOrders = rawOpenOrders.stream().filter(order -> {
            String exch = order.getExchange() != null ? order.getExchange().toUpperCase() : "";
            if ((exch.equals("NFO") || exch.equals("BSE") || exch.equals("NSE")) 
                    && (currentHour >= 16 || (currentHour == 15 && currentMinute > 30))) {
                return false; 
            }
            if (exch.equals("MCX") && currentHour < 9) {
                return false; 
            }
            return true;
        }).collect(Collectors.toList());

        if (openOrders.isEmpty()) {
            return; 
        }

        // 2. OPTIMIZATION: Batch Fetch Risk Configurations (O(1) Database Query)
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

        // Clean up memory cache for deleted/closed orders
        Set<Long> activeOrderIds = openOrders.stream().map(Orders::getId).collect(Collectors.toSet());
        liveUiCachePnL.keySet().retainAll(activeOrderIds);
        highWaterMarks.keySet().retainAll(activeOrderIds);
        activeTrailingFloors.keySet().retainAll(activeOrderIds);

        // 3. Establish Angel One connection & fetch live broker positions
        SmartConnect connection = null;
        try {
            connection = angelOne.signIn();
        } catch (Exception e) {
            logger.error("Global broker authentication failed for this pass: {}", e.getMessage());
            return;
        }
        
        Map<String, BigDecimal> brokerPnLMap = compileLiveBrokerPnLs();

        // 4. Evaluate each order
        for (Orders order : openOrders) {
            BigDecimal computedPnL = BigDecimal.ZERO;
            boolean isLiveTrade = order.getOrderid() != null && !order.getOrderid().equals("1");
            RiskConfiguration config = configMap.get(order.getName());

            if (isLiveTrade) {
                computedPnL = brokerPnLMap.getOrDefault(order.getToken(), BigDecimal.ZERO);
                
                // NEW: Update Real-time PnL to Database safely
                self.syncLivePnLToDb(order, computedPnL);
                
                evaluateOrderRisk(order, computedPnL, connection, config);
            } else {
                if (connection == null) continue;
                try {
                    BigDecimal currentLtp = angelOneService.getcurrentPrice(
                        connection, order.getExchange(), order.getSymbol(), order.getToken()
                    );

                    if (currentLtp != null && order.getAskPrice() != null) {
                        BigDecimal pointsDiff = "BUY".equalsIgnoreCase(order.getType())
                            ? currentLtp.subtract(order.getAskPrice())
                            : order.getAskPrice().subtract(currentLtp);

                        computedPnL = pointsDiff.multiply(BigDecimal.valueOf(order.getQuantity()));
                        
                        // NEW: Update Real-time PnL to Database safely
                        self.syncLivePnLToDb(order, computedPnL);
                        
                        evaluateOrderRisk(order, computedPnL, connection, config);
                    }
                } catch (Exception e) {
                    logger.error("Error calculating paper PnL for Order {}: {}", order.getId(), e.getMessage());
                }
            }
        }
    }

    /**
     * NEW: Syncs live floating P&L directly to the DB so external queries see the exact value.
     */
    @Transactional
    public void syncLivePnLToDb(Orders order, BigDecimal currentLivePnL) {
        if (order.getPl() == null || order.getPl().compareTo(currentLivePnL) != 0) {
            order.setPl(currentLivePnL);
            orderRepository.save(order);
        }
    }

    /**
     * Evaluates calculations against safety limits, velocity checks, and dynamic rubber-band trailing.
     */
    private void evaluateOrderRisk(Orders order, BigDecimal currentLivePnL, SmartConnect connection, RiskConfiguration config) {
        BigDecimal previousTickPnL = liveUiCachePnL.getOrDefault(order.getId(), currentLivePnL);
        liveUiCachePnL.put(order.getId(), currentLivePnL);

        BigDecimal quantity = BigDecimal.valueOf(order.getQuantity());

        // --- NEW: DYNAMIC CONFIG FALLBACK ---
        BigDecimal maxLossThreshold = null;
        BigDecimal targetProfitThreshold = null;

        if (config != null) {
            maxLossThreshold = config.getMaxLossLimit(); // Values from config table
            targetProfitThreshold = config.getTargetProfit();
        } else {
            // Fallback to order-specific settings if config is removed/missing
            if (order.getSl() != null && order.getSl().compareTo(BigDecimal.ZERO) > 0) {
                maxLossThreshold = order.getSl().multiply(quantity).negate();
            }
            if (order.getTarget() != null && order.getTarget().compareTo(BigDecimal.ZERO) > 0) {
                targetProfitThreshold = order.getTarget().multiply(quantity);
            }
        }

        // 1. HARD EMERGENCY STOP LOSS BOUNDS CHECK
        if (maxLossThreshold != null) {
            BigDecimal absoluteMaxLoss = maxLossThreshold.abs().negate();
            if (currentLivePnL.compareTo(absoluteMaxLoss) <= 0) {
                terminateTrade(order, "HARD_MAX_LOSS_BREACHED", currentLivePnL, connection);
                return;
            }
        }

        // 2. ABSOLUTE FIXED TARGET PROFIT CEILING CHECK
        if (targetProfitThreshold != null && targetProfitThreshold.compareTo(BigDecimal.ZERO) > 0) {
            if (currentLivePnL.compareTo(targetProfitThreshold) >= 0) {
                terminateTrade(order, "FIXED_TARGET_PROFIT_HIT", currentLivePnL, connection);
                return;
            }
        }

        // --- DYNAMIC BYPASS CHECK ---
        if (config == null || !"Y".equalsIgnoreCase(config.getSmartRiskFlag())) {
            // Log basic monitoring heartbeat for non-smart trades
            logger.info("[MONITORING] Order: {} | Symbol: {} | Live PnL: {} | Target: {} | SmartRisk: OFF", 
                order.getId(), order.getSymbol(), currentLivePnL, targetProfitThreshold);
            return; 
        }

        // 3. VELOCITY / GAMMA SPIKE PROTECTION
        if (config.getVelocityPanicDrop() != null && config.getVelocityPanicDrop().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal pnlDropThisTick = previousTickPnL.subtract(currentLivePnL);
            if (pnlDropThisTick.compareTo(config.getVelocityPanicDrop()) > 0) {
                terminateTrade(order, "VELOCITY_SPIKE_DETECTED", currentLivePnL, connection);
                return;
            }
        }

        BigDecimal peakPnL = highWaterMarks.getOrDefault(order.getId(), currentLivePnL);
        if (currentLivePnL.compareTo(peakPnL) > 0) {
            peakPnL = currentLivePnL;
            highWaterMarks.put(order.getId(), peakPnL);
        }

        // 4. THE "NEVER GO RED" MILESTONE PROTECTION
        if (targetProfitThreshold != null && config.getMilestonePercent() != null && config.getBreakevenFloor() != null) {
            BigDecimal milestoneActivation = targetProfitThreshold.multiply(config.getMilestonePercent()); 
            
            if (peakPnL.compareTo(milestoneActivation) >= 0) {
                if (currentLivePnL.compareTo(config.getBreakevenFloor()) <= 0) {
                    terminateTrade(order, "MILESTONE_PROFIT_PROTECTION_TRIGGERED", currentLivePnL, connection);
                    return;
                }
            }
        }

        // 5. RUBBER-BAND PEAK DRAWDOWN TRAILING ENGINE
        BigDecimal activation = config.getTrailingActivation();
        BigDecimal drawdownPct = config.getTrailingDrawdownPct(); 

        if (activation != null && drawdownPct != null && peakPnL.compareTo(activation) >= 0) {
            BigDecimal allowedDrop = peakPnL.multiply(drawdownPct);
            BigDecimal dynamicFloor = peakPnL.subtract(allowedDrop);

            BigDecimal existingFloor = activeTrailingFloors.get(order.getId());
            if (existingFloor == null || dynamicFloor.compareTo(existingFloor) > 0) {
                activeTrailingFloors.put(order.getId(), dynamicFloor);
                logger.info("   [↑] Floor Raised! Order {} trailing floor pulled up to {}", order.getId(), dynamicFloor);
            }

            BigDecimal currentFloor = activeTrailingFloors.get(order.getId());
            if (currentFloor != null && currentLivePnL.compareTo(currentFloor) <= 0) {
                terminateTrade(order, "RUBBER_BAND_MAX_DRAWDOWN_BREACHED", currentLivePnL, connection);
                return;
            }
        }

        // --- ACTIVE MONITORING LOG ---
        BigDecimal activeFloor = activeTrailingFloors.getOrDefault(order.getId(), BigDecimal.ZERO);
        logger.info("[MONITORING] Order: {} | Symbol: {} | Live PnL: {} | Peak: {} | Active Floor: {}", 
                order.getId(), order.getSymbol(), currentLivePnL, peakPnL, activeFloor);
    }

    private void terminateTrade(Orders order, String exitReason, BigDecimal closurePnL, SmartConnect connection) {
        boolean isLiveTrade = order.getOrderid() != null && !order.getOrderid().equals("1");

        logger.warn("🚨 [LIQUIDATED] ========================================");
        logger.warn("🚨 Order ID: {} | Strategy: {}", order.getId(), order.getName());
        logger.warn("🚨 Reason:   {}", exitReason);
        logger.warn("🚨 Final PnL: {}", closurePnL);
        logger.warn("🚨 ====================================================");

        if (isLiveTrade && connection != null) {
            try {
                Token exitToken = new Token();
                exitToken.setSymbol(order.getSymbol());
                exitToken.setToken(order.getToken());
                exitToken.setExch_seg(order.getExchange());
                exitToken.setQuantity(order.getQuantity());
                exitToken.setOrderType(Constants.ORDER_TYPE_MARKET);
                exitToken.setProductType(Constants.PRODUCT_CARRYFORWARD); 
                exitToken.setVariety(Constants.VARIETY_NORMAL);
                
                String exitSide = "BUY".equalsIgnoreCase(order.getType()) 
                    ? Constants.TRANSACTION_TYPE_SELL 
                    : Constants.TRANSACTION_TYPE_BUY;
                exitToken.setTransactionType(exitSide);

                Token responseToken = angelOneService.placeOrder(connection, exitToken);
                if (responseToken != null && responseToken.getOrderId() != null) {
                    logger.info("   => Live market exit success. Broker ID: {}", responseToken.getOrderId());
                }
            } catch (Exception | SmartAPIException e) {
                logger.error("   => CRITICAL: Live market exit failed for Order {}: {}", order.getId(), e.getMessage());
                return; // Fail-safe: Do not update DB if broker order fails
            }
        } else if (!isLiveTrade) {
            logger.info("   => Paper Trade Exit Simulated successfully.");
        }

        // Delegate to self to trigger @Transactional proxy
        self.persistClosedOrderToDb(order, exitReason, closurePnL);
    }

    @Transactional
    public void persistClosedOrderToDb(Orders order, String exitReason, BigDecimal closurePnL) {
        order.setActive(0);
        order.setStatus("CLOSED");
        order.setTradePhase("EXIT");
        order.setClosedOn(LocalDateTime.now());
        order.setExitReason(exitReason);
        order.setPl(closurePnL); 

        orderRepository.save(order);

        liveUiCachePnL.remove(order.getId());
        highWaterMarks.remove(order.getId());
        activeTrailingFloors.remove(order.getId());
    }

    private void clearMemoryCaches() {
        if (!liveUiCachePnL.isEmpty()) {
            liveUiCachePnL.clear();
            highWaterMarks.clear();
            activeTrailingFloors.clear();
            logger.info("--- Risk Engine Idle: Memory caches purged. Zero active trades. ---");
        }
    }

    private Map<String, BigDecimal> compileLiveBrokerPnLs() {
        Map<String, BigDecimal> tokenPnLMap = new ConcurrentHashMap<>();
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
        return tokenPnLMap;
    }
}