package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    // --- CLOUD MEMORY CACHES ---
    private final Map<Long, BigDecimal> liveUiCachePnL = new ConcurrentHashMap<>();
    private final Map<Long, BigDecimal> highWaterMarks = new ConcurrentHashMap<>();
    private final Map<Long, BigDecimal> activeTrailingFloors = new ConcurrentHashMap<>();

    public Map<Long, BigDecimal> getLivePnLForUI() {
        return liveUiCachePnL;
    }

    /**
     * Master Orchestration Loop called continuously by RiskExecutionScheduler.
     * External API calls are kept OUT of database transactions to prevent connection freezing.
     */
    public void processSystemRiskMatrix() {
        List<Orders> openOrders = orderRepository.findByActive(1);
        if (openOrders == null || openOrders.isEmpty()) return;

        // Fetch running position map array directly through the broker API
        Map<String, BigDecimal> brokerPnLMap = compileLiveBrokerPnLs();

        // Establish ONE connection for the entire scheduler pass to prevent API rate-limiting
        SmartConnect connection = null;
        try {
            connection = angelOne.signIn();
        } catch (Exception e) {
            logger.error("Global broker authentication failed for this pass: {}", e.getMessage());
            return;
        }

        for (Orders order : openOrders) {
            BigDecimal computedPnL = BigDecimal.ZERO;
            boolean isLiveTrade = order.getOrderid() != null && !order.getOrderid().equals("1");

            if (isLiveTrade) {
                // --- ENVIRONMENT A: LIVE TRADING ---
                computedPnL = brokerPnLMap.getOrDefault(order.getToken(), BigDecimal.ZERO);
                evaluateOrderRisk(order, computedPnL, connection);
            } else {
                // --- ENVIRONMENT B: PAPER TRADING ---
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
                        evaluateOrderRisk(order, computedPnL, connection);
                    }
                } catch (Exception e) {
                    logger.error("Error calculating paper trade PnL for Order ID {}: {}", order.getId(), e.getMessage());
                }
            }
        }
    }

    /**
     * Evaluates calculations against safety limits and trailing margins in memory.
     */
    private void evaluateOrderRisk(Orders order, BigDecimal currentLivePnL, SmartConnect connection) {
        liveUiCachePnL.put(order.getId(), currentLivePnL);

        // 1. HARD EMERGENCY STOP LOSS BOUNDS CHECK
        if (order.getSl() != null && order.getSl().compareTo(BigDecimal.ZERO) > 0) {
            if (currentLivePnL.compareTo(order.getSl().negate()) <= 0) {
                terminateTrade(order, "HARD_MAX_LOSS_BREACHED", currentLivePnL, connection);
                return;
            }
        }

        // 2. ABSOLUTE FIXED TARGET PROFIT CEILING CHECK
        if (order.getTarget() != null && order.getTarget().compareTo(BigDecimal.ZERO) > 0) {
            if (currentLivePnL.compareTo(order.getTarget()) >= 0) {
                terminateTrade(order, "FIXED_TARGET_PROFIT_HIT", currentLivePnL, connection);
                return;
            }
        }

// 3. CONFIGURABLE DYNAMIC TRAILING STOP LOSS ENGINE
        
        // Use the EXACT 'name' field from the ORDERS table to find the config
        String strategyIdentifier = order.getName(); 
        
        if (strategyIdentifier == null || strategyIdentifier.isEmpty()) {
            return; // Safety check
        }

        // Look up the strategy parameters from the database
        RiskConfiguration config = riskConfigRepository.findById(strategyIdentifier).orElse(null);
        
        // Prevent NullPointerExceptions if the config doesn't exist or trailing is false/null
        if (config == null || Boolean.FALSE.equals(config.getIsTrailingEnabled())) {
            return;
        }

        BigDecimal activationThreshold = config.getActivationThreshold(); 
        BigDecimal profitStep = config.getProfitStep();       
        BigDecimal trailBy = config.getTrailBy();          

        // ... rest of trailing logic ...         

        // Track local Peak High Water Marks inside RAM structures
        BigDecimal peakPnL = highWaterMarks.getOrDefault(order.getId(), currentLivePnL);
        if (currentLivePnL.compareTo(peakPnL) > 0) {
            peakPnL = currentLivePnL;
            highWaterMarks.put(order.getId(), peakPnL);
        }

        // Lock in lock-step tracking thresholds when the baseline criteria is unlocked
        if (activationThreshold != null && peakPnL.compareTo(activationThreshold) >= 0) {
            BigDecimal surplusProfit = peakPnL.subtract(activationThreshold);
            BigDecimal stepsCount = surplusProfit.divide(profitStep, 0, RoundingMode.FLOOR);
            BigDecimal calculatedFloor = activationThreshold.add(stepsCount.multiply(trailBy));

            BigDecimal existingFloor = activeTrailingFloors.get(order.getId());
            
            // Safe null check to ensure negative floors are mapped correctly
            if (existingFloor == null || calculatedFloor.compareTo(existingFloor) > 0) {
                activeTrailingFloors.put(order.getId(), calculatedFloor);
                logger.info("RiskEngine: Order ID {} Trailing Floor shifted upward to: {}", order.getId(), calculatedFloor);
            }

            BigDecimal currentFloor = activeTrailingFloors.get(order.getId());
            if (currentFloor != null && currentLivePnL.compareTo(currentFloor) <= 0) {
                terminateTrade(order, "TRAILING_SL_TRIGGERED", currentLivePnL, connection);
            }
        }
    }

    /**
     * Executes market liquidation.
     * Network API calls happen here, completely isolated from DB transaction locks.
     */
    private void terminateTrade(Orders order, String exitReason, BigDecimal closurePnL, SmartConnect connection) {
        logger.warn("RiskEngine Executing Termination Routine -> Order ID: {} | Reason: {} | Final PnL: {}", 
            order.getId(), exitReason, closurePnL);

        boolean isLiveTrade = order.getOrderid() != null && !order.getOrderid().equals("1");

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
                    logger.info("Live market exit complete. Assigned Broker ID: {}", responseToken.getOrderId());
                }
            } catch (Exception | SmartAPIException e) {
                logger.error("Failed to execute live market square-off execution for Order ID {}: {}", order.getId(), e.getMessage());
                return; // Prevent DB closure if broker rejects the order
            }
        } else {
            logger.info("Paper position context flagged inactive virtually within risk memory arrays.");
        }

        // Proceed to isolated database update
        persistClosedOrderToDb(order, exitReason, closurePnL);
    }

    /**
     * Isolated database layer logic.
     * @Transactional ensures this specific update is committed cleanly, without holding the connection open during the API calls above.
     */
    @Transactional
    protected void persistClosedOrderToDb(Orders order, String exitReason, BigDecimal closurePnL) {
        order.setActive(0);
        order.setStatus("CLOSED");
        order.setTradePhase("EXIT");
        order.setClosedOn(LocalDateTime.now());
        order.setExitReason(exitReason);
        order.setPl(closurePnL); 

        orderRepository.save(order);

        // Purge tracking references from RAM immediately
        liveUiCachePnL.remove(order.getId());
        highWaterMarks.remove(order.getId());
        activeTrailingFloors.remove(order.getId());
    }

    private Map<String, BigDecimal> compileLiveBrokerPnLs() {
        Map<String, BigDecimal> tokenPnLMap = new ConcurrentHashMap<>();
        JSONObject rawPositionResponse = angelOneService.getRawPositions();

        if (rawPositionResponse != null && rawPositionResponse.optBoolean("status", false)) {
            JSONArray positions = rawPositionResponse.optJSONArray("data");
            if (positions != null) {
                for (int i = 0; i < positions.length(); i++) {
                    JSONObject pos = positions.getJSONObject(i);
                    
                    // Angel One JSON uses "pnl" as a string, not "m2m"
                    String pnlString = pos.optString("pnl", "0.00");
                    BigDecimal pnl = new BigDecimal(pnlString);
                    
                    tokenPnLMap.put(pos.optString("symboltoken", ""), pnl);
                }
            }
        }
        return tokenPnLMap;
    }
}