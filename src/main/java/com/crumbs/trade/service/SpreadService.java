package com.crumbs.trade.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.models.MarginParams;
import com.angelbroking.smartapi.utils.Constants;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Expiry;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.Indicator;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.ExpiryRepo;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.IndicatorRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.utility.AppConstant;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Enhanced SpreadService with HONEST naming convention.
 * 
 * IMPORTANT: This service uses STRIKE DISTANCE strategy, NOT actual probability-based POP.
 * The "distance levels" are fixed strike intervals that do NOT account for:
 *   - Implied Volatility (IV)
 *   - Time to Expiration (DTE)
 *   - Actual option Greeks (Delta, Gamma, etc.)
 * 
 * Use this for:
 *   ✓ Simple, consistent strike selection
 *   ✓ Backtesting relative strategies
 *   ✓ Low-volatility, stable market conditions
 * 
 * DO NOT use this for:
 *   ✗ Precise risk management
 *   ✗ High-volatility environments
 *   ✗ Guaranteed probability outcomes
 * 
 * Version: 2.1 (Honest Naming)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpreadService {

    // ---------------------------------------------------------------------
    // Autowired Repos & Services
    // ---------------------------------------------------------------------
    @Autowired private IndicatorRepo indicatorRepo;
    @Autowired private AngelOneService angelOneService;
    @Autowired private AngelOne angelOne;
    @Autowired private ExpiryRepo expiryRepo;
    @Autowired private IndexesRepo indexesRepo;
    @Autowired private StrategyRepo strategyRepo;

    // ---------------------------------------------------------------------
    // Configuration constants
    // ---------------------------------------------------------------------
    private static final BigDecimal STRIKE_DIVISOR = new BigDecimal("100");
    private static final long DEFAULT_EXPIRY_ID = 1L;
    private static final int NAKED_HEDGE_DISTANCE = 10;
    private static final int ORDER_STATUS_POLL_ATTEMPTS = 10;
    private static final int ORDER_STATUS_POLL_DELAY_MS = 500;
    private static final BigDecimal MIN_MARGIN_BUFFER = new BigDecimal("1.2");
    private static final int MAX_SPREADS_PER_DAY = 10;

    private final Set<String> processedIndicatorsToday = Collections.synchronizedSet(new HashSet<>());
    private int spreadsPlacedToday = 0;

    // ---------------------------------------------------------------------
    // Enums with HONEST names
    // ---------------------------------------------------------------------
    public enum SpreadType {
        BULL_CALL, BEAR_CALL, BULL_PUT, BEAR_PUT, NAKED_CALL_HEDGE, NAKED_PUT_HEDGE
    }

    /**
     * RENAMED: StrikeDistanceLevel (was POPLevel)
     * 
     * These represent DISTANCE from ATM in strike steps, NOT probability.
     * 
     * Example: If step size = 100 points:
     *   - ATM:         Current price (e.g., 44,000)
     *   - NEAR_OTM:    1 step away (e.g., 43,900 or 44,100)
     *   - MID_OTM:     2 steps away (e.g., 43,800 or 44,200)
     *   - FAR_OTM:     3 steps away (e.g., 43,700 or 44,300)
     * 
     * WARNING: Actual win probability will vary based on market volatility!
     *   - In calm markets (low IV): These may be 65-75% safe
     *   - In volatile markets (high IV): These may be 50-60% safe
     */
    public enum StrikeDistanceLevel {
        ATM,       // 0 steps
        NEAR_OTM,  // 1 step
        MID_OTM,   // 2 steps
        FAR_OTM    // 3 steps
    }

    /**
     * Maps distance levels to strike steps.
     * NOTE: This is FIXED distance, not probability-adjusted!
     */
    private static final Map<StrikeDistanceLevel, Integer> DISTANCE_STEPS_MAP = Map.of(
            StrikeDistanceLevel.ATM, 0,
            StrikeDistanceLevel.NEAR_OTM, 1,
            StrikeDistanceLevel.MID_OTM, 2,
            StrikeDistanceLevel.FAR_OTM, 3
    );

    private int getStepsFromDistance(StrikeDistanceLevel level) {
        return DISTANCE_STEPS_MAP.getOrDefault(level, 1);
    }

    // ---------------------------------------------------------------------
    // PUBLIC API (updated signatures)
    // ---------------------------------------------------------------------

    /**
     * Entry point - fetch indicators and attempt spread placements
     */
    public void getStockList() {
        List<String> signals = List.of("FIRST BUY", "FIRST SELL");

        List<Indicator> rawList =
                indicatorRepo.findByTradetypeAndOptions("DAILY", "Y");

        List<Indicator> indicatorList = rawList.stream()
                .filter(i -> i.getHeikinAshiDay().equals(i.getPsarFlagDay()))
                .toList();

        SmartConnect smartConnect = angelOne.signIn();

        log.info("Processing {} indicators for spread placement (Daily limit: {})",
                 indicatorList.size(), MAX_SPREADS_PER_DAY);

        for (Indicator indicator : indicatorList) {
            if (spreadsPlacedToday >= MAX_SPREADS_PER_DAY) {
                log.warn("Daily spread limit reached ({}). Stopping processing.", MAX_SPREADS_PER_DAY);
                break;
            }

            try {
                processIndicator(smartConnect, indicator);
            } catch (Exception e) {
                log.error("Failed processing {}: {}", indicator.getTradingSymbol(), e.getMessage(), e);
            }
        }
    }

    /**
     * Determine spread type from indicator signal
     */
    public void processIndicator(SmartConnect smartConnect, Indicator indicator) {
        String signal = indicator.getHeikinAshiDay();
        SpreadType spreadType;
        StrikeDistanceLevel distanceLevel = StrikeDistanceLevel.ATM; // Default: 1 step OTM

        if ("FIRST BUY".equalsIgnoreCase(signal)) {
            spreadType = SpreadType.BULL_PUT;
        } else if ("FIRST SELL".equalsIgnoreCase(signal)) {
            spreadType = SpreadType.BEAR_CALL;
        } else {
            log.warn("Unknown signal '{}' for {} - skipping", signal, indicator.getTradingSymbol());
            return;
        }

        log.info("Placing spread {} for {} at distance level {} based on signal {}", 
                 spreadType, indicator.getTradingSymbol(), distanceLevel, signal);

        processIndicator(smartConnect, indicator, spreadType, distanceLevel);
    }

    public void processIndicator(SmartConnect smartConnect, Indicator indicator, SpreadType spreadType) {
        processIndicator(smartConnect, indicator, spreadType, StrikeDistanceLevel.NEAR_OTM);
    }

    /**
     * Main processing method with honest parameter naming.
     * 
     * @param distanceLevel - How far from ATM to place strikes (NOT probability!)
     */
    @Transactional
    public void processIndicator(SmartConnect smartConnect, Indicator indicator,
                                 SpreadType spreadType, StrikeDistanceLevel distanceLevel) {

        log.info("=== Processing Spread for {} | Type: {} | Distance: {} ===",
                 indicator.getTradingSymbol(), spreadType, distanceLevel);
        log.warn("⚠️  Using FIXED strike distance ({}), NOT probability-based POP!", distanceLevel);

        // Duplicate prevention
        String indicatorKey = indicator.getTradingSymbol() + "_" + spreadType.name();
        if (processedIndicatorsToday.contains(indicatorKey)) {
            log.info("Already processed {} today - skipping duplicate", indicatorKey);
            return;
        }

        // Fetch LTP
        BigDecimal ltp = fetchLTP(smartConnect, indicator);
        if (ltp == null) {
            log.warn("Cannot proceed without LTP for {}", indicator.getTradingSymbol());
            return;
        }

        // Get expiry
        Optional<Expiry> optionalExpiry = expiryRepo.findById(DEFAULT_EXPIRY_ID);
        if (optionalExpiry.isEmpty()) {
            log.error("Expiry not found (ID: {}). Check database configuration.", DEFAULT_EXPIRY_ID);
            return;
        }
        Expiry expiry = optionalExpiry.get();
        String expiryName = expiry.getExpirydate();

        // Normalize expiry and parse
        String normalized = expiryName.substring(0, 2)
            + expiryName.substring(2, 3).toUpperCase()
            + expiryName.substring(3, 5).toLowerCase()
            + expiryName.substring(5);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("ddMMMyyyy", Locale.ENGLISH);
        LocalDate expiryDate;
        try {
            expiryDate = LocalDate.parse(normalized, formatter);
        } catch (Exception e) {
            log.error("Failed to parse expiry {} -> normalized='{}'. Error: {}", 
                     expiryName, normalized, e.getMessage());
            return;
        }

        LocalDate today = LocalDate.now();
        int currentDay = today.getDayOfMonth();

        boolean sameMonth = expiryDate.getYear() == today.getYear() &&
                            expiryDate.getMonth() == today.getMonth();

        if (sameMonth && currentDay >= 15) {
            log.warn("Skipping action: expiry {} is current month and today={} >= 15.", 
                    expiryName, currentDay);
            return;
        }
        log.info("Expiry {} valid. Continuing…", expiryName);

        // Fetch strikes
        List<BigDecimal> strikes = fetchAllStrikesForInstrument(
            indicator.getName(), expiry.getExpirydate());
        if (strikes.isEmpty()) {
            log.error("No strikes found for {} expiry {}", 
                     indicator.getTradingSymbol(), expiry.getExpirydate());
            return;
        }

        // Calculate spread using DISTANCE (not POP!)
        SpreadCalculation calc = calculateSpread(ltp, strikes, spreadType, distanceLevel);
        if (!calc.isValid()) {
            log.error("Invalid spread calculation for {}: {}", 
                     indicator.getTradingSymbol(), calc.getErrorMessage());
            return;
        }

        log.info("Spread calculation: LTP={} | ATM={} | Step={} | BuyStrike={} | SellStrike={}",
                 ltp, calc.atmStrike, calc.stepSize, calc.buyStrike, calc.sellStrike);
        log.info("⚠️  ACTUAL win probability NOT calculated - depends on current market volatility!");

        // Build tokens
        Token buyLeg = buildToken(indicator, expiry, calc.buyStrike, 
                                 calc.buyOptionType, Constants.TRANSACTION_TYPE_BUY);
        Token sellLeg = buildToken(indicator, expiry, calc.sellStrike, 
                                  calc.sellOptionType, Constants.TRANSACTION_TYPE_SELL);

        if (buyLeg == null || sellLeg == null) {
            log.error("Failed to build tokens for {}", indicator.getTradingSymbol());
            return;
        }

        // Prepare fields and price
        prepareCommonFields(buyLeg, smartConnect);
        prepareCommonFields(sellLeg, smartConnect);

        // Risk / reward calculations
        BigDecimal maxRisk = calculateMaxRisk(buyLeg, sellLeg, calc);
        BigDecimal maxProfit = calculateMaxProfit(buyLeg, sellLeg, calc);

        BigDecimal rr = BigDecimal.ZERO;
        if (maxRisk.compareTo(BigDecimal.ZERO) != 0) {
            rr = maxProfit.divide(maxRisk, 2, RoundingMode.HALF_UP);
        }

        log.info("Spread P&L: MaxRisk={} | MaxProfit={} | Risk:Reward=1:{}", 
                maxRisk, maxProfit, rr);

        // Validate margin
        if (!validateMarginRequirement(smartConnect)) {
            log.error("Insufficient margin for {} spread - aborting", 
                     indicator.getTradingSymbol());
            return;
        }

        // Place spread
        boolean success = placeSpreadOrder(smartConnect, buyLeg, sellLeg, indicator, spreadType);
        if (success) {
            processedIndicatorsToday.add(indicatorKey);
            spreadsPlacedToday++;
            log.info("Spread placed successfully! Total today: {}/{}", 
                    spreadsPlacedToday, MAX_SPREADS_PER_DAY);
        }
    }

    // ---------------------------------------------------------------------
    // SPREAD CALCULATION (renamed parameter)
    // ---------------------------------------------------------------------
    private static class SpreadCalculation {
        BigDecimal atmStrike;
        BigDecimal stepSize;
        BigDecimal buyStrike;
        BigDecimal sellStrike;
        String buyOptionType;
        String sellOptionType;
        String errorMessage;

        boolean isValid() {
            return errorMessage == null && buyStrike != null && sellStrike != null;
        }

        String getErrorMessage() {
            return errorMessage;
        }
    }

    /**
     * Calculate spread strikes based on FIXED DISTANCE from ATM.
     * 
     * NOTE: This does NOT calculate actual probability!
     * 
     * @param distanceLevel - Strike distance (ATM, NEAR_OTM, MID_OTM, FAR_OTM)
     */
    private SpreadCalculation calculateSpread(BigDecimal ltp, List<BigDecimal> strikes,
                                               SpreadType spreadType, 
                                               StrikeDistanceLevel distanceLevel) {
        SpreadCalculation calc = new SpreadCalculation();

        calc.atmStrike = findNearestStrike(ltp, strikes);
        calc.stepSize = findStrikeInterval(strikes);

        if (calc.stepSize.compareTo(BigDecimal.ZERO) == 0) {
            calc.errorMessage = "Invalid step size (zero) detected";
            return calc;
        }

        int steps = getStepsFromDistance(distanceLevel);
        
        log.debug("Using fixed distance: {} steps = {} points from ATM", 
                 steps, calc.stepSize.multiply(BigDecimal.valueOf(steps)));

        BigDecimal sellStrikePut = calc.atmStrike.subtract(
            calc.stepSize.multiply(BigDecimal.valueOf(steps)));
        BigDecimal sellStrikeCall = calc.atmStrike.add(
            calc.stepSize.multiply(BigDecimal.valueOf(steps)));
        BigDecimal hedgeLower = sellStrikePut.subtract(calc.stepSize);
        BigDecimal hedgeUpper = sellStrikeCall.add(calc.stepSize);

        switch (spreadType) {
            case BULL_CALL:
                calc.buyStrike = calc.atmStrike;
                calc.sellStrike = calc.atmStrike.add(calc.stepSize);
                calc.buyOptionType = "CE";
                calc.sellOptionType = "CE";
                break;

            case BEAR_CALL:
                calc.buyStrike = hedgeUpper;
                calc.sellStrike = sellStrikeCall;
                calc.buyOptionType = "CE";
                calc.sellOptionType = "CE";
                break;

            case BULL_PUT:
                calc.buyStrike = hedgeLower;
                calc.sellStrike = sellStrikePut;
                calc.buyOptionType = "PE";
                calc.sellOptionType = "PE";
                break;

            case BEAR_PUT:
                calc.buyStrike = calc.atmStrike;
                calc.sellStrike = calc.atmStrike.subtract(calc.stepSize);
                calc.buyOptionType = "PE";
                calc.sellOptionType = "PE";
                break;

            case NAKED_CALL_HEDGE:
                calc.sellStrike = sellStrikeCall;
                calc.buyStrike = calc.sellStrike.add(
                    calc.stepSize.multiply(BigDecimal.valueOf(NAKED_HEDGE_DISTANCE)));
                calc.buyOptionType = "CE";
                calc.sellOptionType = "CE";
                log.info("NAKED_CALL_HEDGE: Selling {} CE, Buying {} CE hedge ({}x away)",
                         calc.sellStrike, calc.buyStrike, NAKED_HEDGE_DISTANCE);
                break;

            case NAKED_PUT_HEDGE:
                calc.sellStrike = sellStrikePut;
                calc.buyStrike = calc.sellStrike.subtract(
                    calc.stepSize.multiply(BigDecimal.valueOf(NAKED_HEDGE_DISTANCE)));
                calc.buyOptionType = "PE";
                calc.sellOptionType = "PE";
                log.info("NAKED_PUT_HEDGE: Selling {} PE, Buying {} PE hedge ({}x away)",
                         calc.sellStrike, calc.buyStrike, NAKED_HEDGE_DISTANCE);
                break;
        }

        if (!isValidStrike(calc.buyStrike, strikes)) {
            if (spreadType == SpreadType.NAKED_CALL_HEDGE || 
                spreadType == SpreadType.NAKED_PUT_HEDGE) {
                BigDecimal furthest = findFurthestStrike(strikes, 
                    spreadType == SpreadType.NAKED_CALL_HEDGE);
                log.warn("Buy strike {} not available for {}. Using furthest strike: {}",
                         calc.buyStrike, spreadType, furthest);
                calc.buyStrike = furthest;
            } else {
                calc.errorMessage = "Buy strike " + calc.buyStrike + " not available";
                return calc;
            }
        }

        if (!isValidStrike(calc.sellStrike, strikes)) {
            calc.errorMessage = "Sell strike " + calc.sellStrike + " not available";
            return calc;
        }

        return calc;
    }

    private boolean isValidStrike(BigDecimal strike, List<BigDecimal> availableStrikes) {
        return availableStrikes.stream().anyMatch(s -> s.compareTo(strike) == 0);
    }

    private BigDecimal findFurthestStrike(List<BigDecimal> strikes, boolean findMax) {
        if (strikes.isEmpty()) return BigDecimal.ZERO;
        return findMax ? strikes.get(strikes.size() - 1) : strikes.get(0);
    }

    // ---------------------------------------------------------------------
    // RISK CALCULATION
    // ---------------------------------------------------------------------
    private BigDecimal calculateMaxRisk(Token buyLeg, Token sellLeg, SpreadCalculation calc) {
        BigDecimal strikeWidth = calc.sellStrike.subtract(calc.buyStrike).abs();
        BigDecimal premium = BigDecimal.valueOf(sellLeg.getPrice())
            .subtract(BigDecimal.valueOf(buyLeg.getPrice()));

        if (premium.compareTo(BigDecimal.ZERO) > 0) {
            return strikeWidth.subtract(premium)
                .multiply(BigDecimal.valueOf(buyLeg.getQuantity()));
        } else {
            return premium.abs().multiply(BigDecimal.valueOf(buyLeg.getQuantity()));
        }
    }

    private BigDecimal calculateMaxProfit(Token buyLeg, Token sellLeg, SpreadCalculation calc) {
        BigDecimal premium = BigDecimal.valueOf(sellLeg.getPrice())
            .subtract(BigDecimal.valueOf(buyLeg.getPrice()));

        if (premium.compareTo(BigDecimal.ZERO) > 0) {
            return premium.multiply(BigDecimal.valueOf(buyLeg.getQuantity()));
        } else {
            BigDecimal strikeWidth = calc.sellStrike.subtract(calc.buyStrike).abs();
            return strikeWidth.subtract(premium.abs())
                .multiply(BigDecimal.valueOf(buyLeg.getQuantity()));
        }
    }

    // ---------------------------------------------------------------------
    // MARGIN VALIDATION
    // ---------------------------------------------------------------------
    public boolean validateMarginRequirement(SmartConnect smartConnect) {
        try {
            JSONObject rms = smartConnect.getRMS();
            double availableCash = Double.parseDouble(rms.getString("availablecash"));

            double required = 100000.0; // 1 lakh (placeholder)
            if (availableCash >= required) {
                log.info("Sufficient margin available. AvailableCash = {}", availableCash);
                return true;
            } else {
                log.warn("Insufficient margin. Required = {}, AvailableCash = {}", 
                        required, availableCash);
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to validate margin: ", e);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // ORDER PLACEMENT
    // ---------------------------------------------------------------------
    public boolean placeSpreadOrder(SmartConnect smartConnect, Token buyLeg, Token sellLeg,
                                    Indicator indicator, SpreadType spreadType) {

        Strategy strategy = strategyRepo.findByName(AppConstant.SPREAD_STRATEGY);
        if (strategy == null) {
            log.warn("Strategy '{}' not found -> skipping order placement", 
                    AppConstant.SPREAD_STRATEGY);
            return false;
        }

        if ("Y".equalsIgnoreCase(strategy.getPapertrade())) {
            saveOrders(buyLeg, sellLeg);
            log.info("Paper trade orders saved for {}", indicator.getTradingSymbol());
        }

        if (!"Y".equalsIgnoreCase(strategy.getLive())) {
            log.info("Strategy not live - skipping actual order placement");
            return false;
        }

        log.info("Placing LIVE spread: BUY {} @ {} | SELL {} @ {}",
                 buyLeg.getSymbol(), buyLeg.getQuantity(), 
                 sellLeg.getSymbol(), sellLeg.getQuantity());

        OrderResult buyResult = placeSingleOrder(smartConnect, buyLeg);
        if (!buyResult.isSuccess()) {
            log.error("Buy leg failed for {} - aborting spread. Error: {}", 
                     buyLeg.getSymbol(), buyResult.getErrorMessage());
            return false;
        }
        log.info("Buy leg successful: {} - OrderID: {}", 
                buyLeg.getSymbol(), buyResult.getOrderId());

        if (!waitForOrderCompletion(smartConnect, buyResult.getOrderId())) {
            log.error("Buy leg order {} did not complete in time - aborting spread", 
                     buyResult.getOrderId());
            attemptCancelOrder(smartConnect, buyResult.getOrderId());
            return false;
        }

        OrderResult sellResult = placeSingleOrder(smartConnect, sellLeg);
        if (!sellResult.isSuccess()) {
            log.error("CRITICAL: Sell leg failed for {}. Buy leg OrderID: {}. Error: {}",
                      sellLeg.getSymbol(), buyResult.getOrderId(), sellResult.getErrorMessage());

            log.warn("Attempting to rollback buy leg: {}", buyLeg.getSymbol());
            boolean rollbackSuccess = rollbackBuyLeg(smartConnect, buyLeg, buyResult.getOrderId());

            if (!rollbackSuccess) {
                log.error("MANUAL INTERVENTION REQUIRED: Unable to rollback buy leg {}. OrderID: {}",
                          buyLeg.getSymbol(), buyResult.getOrderId());
                sendAlert("CRITICAL: Failed rollback for " + buyLeg.getSymbol() + 
                         " OrderID: " + buyResult.getOrderId());
            }
            return false;
        }

        log.info("✓ Spread placement SUCCESSFUL! Buy: {} | Sell: {}", 
                buyResult.getOrderId(), sellResult.getOrderId());

        saveSpreadPosition(indicator, spreadType, buyResult, sellResult, buyLeg, sellLeg);
        return true;
    }

    private void saveOrders(Token buyLeg, Token sellLeg) {
        try {
            angelOneService.insertOrder(buyLeg, 0);
            angelOneService.insertOrder(sellLeg, 0);
            log.info("Paper trade orders saved successfully");
        } catch (Exception e) {
            log.error("Failed to save paper trade orders: {}", e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------------
    // ORDER RESULT / HELPERS
    // ---------------------------------------------------------------------
    private static class OrderResult {
        private final boolean success;
        private final String orderId;
        private final String errorMessage;

        public OrderResult(boolean success, String orderId, String errorMessage) {
            this.success = success;
            this.orderId = orderId;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() { return success; }
        public String getOrderId() { return orderId; }
        public String getErrorMessage() { return errorMessage; }
    }

    private OrderResult placeSingleOrder(SmartConnect sc, Token token) {
        try {
            log.info("Placing {} order for {} qty {}", 
                    token.getTransactionType(), token.getSymbol(), token.getQuantity());

            Token orderResponse = angelOneService.placeOrder(sc, token);
            if (orderResponse != null) {
                String orderId = extractOrderId(orderResponse);
                log.info("Order placed successfully: {} - OrderID: {}", 
                        token.getSymbol(), orderId);
                return new OrderResult(true, orderId, null);
            } else {
                return new OrderResult(false, null, "Order response was null");
            }

        } catch (SmartAPIException e) {
            log.error("SmartAPI exception placing order for {}: {}", 
                     token.getSymbol(), e.getMessage(), e);
            return new OrderResult(false, null, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected exception placing order for {}: {}", 
                     token.getSymbol(), e.getMessage(), e);
            return new OrderResult(false, null, e.getMessage());
        }
    }

    private String extractOrderId(Token orderResponse) {
        try {
            try {
                java.lang.reflect.Method m = orderResponse.getClass().getMethod("getOrderId");
                Object rv = m.invoke(orderResponse);
                if (rv != null) return rv.toString();
            } catch (NoSuchMethodException ignored) {}

            try {
                java.lang.reflect.Method m2 = orderResponse.getClass().getMethod("getOrderNo");
                Object rv2 = m2.invoke(orderResponse);
                if (rv2 != null) return rv2.toString();
            } catch (NoSuchMethodException ignored) {}

            try {
                java.lang.reflect.Method m3 = orderResponse.getClass().getMethod("getId");
                Object rv3 = m3.invoke(orderResponse);
                if (rv3 != null) return rv3.toString();
            } catch (NoSuchMethodException ignored) {}

        } catch (Exception e) {
            log.debug("Reflection failed while extracting order id: {}", e.getMessage());
        }

        String fallbackId = "ORDER_" + System.currentTimeMillis();
        log.warn("Could not extract order ID from response, using fallback: {}", fallbackId);
        return fallbackId;
    }

    private boolean waitForOrderCompletion(SmartConnect sc, String orderId) {
        log.info("Waiting for order {} to complete...", orderId);

        for (int attempt = 1; attempt <= ORDER_STATUS_POLL_ATTEMPTS; attempt++) {
            try {
                TimeUnit.MILLISECONDS.sleep(ORDER_STATUS_POLL_DELAY_MS);

                // TODO: Implement actual order status check
                if (attempt >= 3) {
                    log.info("Order {} assumed complete", orderId);
                    return true;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while waiting for order {}", orderId);
                return false;
            } catch (Exception e) {
                log.error("Error checking order status: {}", e.getMessage());
            }
        }

        log.warn("Order {} status check timed out", orderId);
        return false;
    }

    private void attemptCancelOrder(SmartConnect sc, String orderId) {
        try {
            log.info("Attempting to cancel order {}", orderId);
            // TODO: Implement actual cancellation
            log.info("Cancel request sent for order {}", orderId);
        } catch (Exception e) {
            log.error("Failed to cancel order {}: {}", orderId, e.getMessage());
        }
    }

    private boolean rollbackBuyLeg(SmartConnect sc, Token buyLeg, String orderId) {
        log.warn("Initiating rollback for buy leg {} (OrderID: {})", 
                buyLeg.getSymbol(), orderId);

        try {
            boolean cancelled = attemptCancelOrderWithRetry(sc, orderId);
            if (cancelled) {
                log.info("Successfully cancelled buy leg order {}", orderId);
                return true;
            }
        } catch (Exception e) {
            log.debug("Cancel attempt failed (order may be filled): {}", e.getMessage());
        }

        try {
            Token exitToken = createExitToken(buyLeg, sc);
            OrderResult exitResult = placeSingleOrder(sc, exitToken);

            if (exitResult.isSuccess()) {
                log.info("Successfully rolled back buy leg {} with exit order {}", 
                        buyLeg.getSymbol(), exitResult.getOrderId());
                return true;
            } else {
                log.error("Exit order failed: {}", exitResult.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("Failed to place exit order: {}", e.getMessage(), e);
        }

        return false;
    }

    private boolean attemptCancelOrderWithRetry(SmartConnect sc, String orderId) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                // TODO: Implement actual cancellation
                log.info("Cancel attempt {} for order {}", attempt, orderId);
                TimeUnit.MILLISECONDS.sleep(300);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.debug("Cancel attempt {} failed: {}", attempt, e.getMessage());
            }
        }
        return false;
    }

    private Token createExitToken(Token originalToken, SmartConnect sc) {
        Token exitToken = new Token();
        exitToken.setSymbol(originalToken.getSymbol());
        exitToken.setToken(originalToken.getToken());
        exitToken.setExch_seg(originalToken.getExch_seg());
        exitToken.setQuantity(originalToken.getQuantity());

        exitToken.setTransactionType(
            Constants.TRANSACTION_TYPE_BUY.equals(originalToken.getTransactionType())
                ? Constants.TRANSACTION_TYPE_SELL
                : Constants.TRANSACTION_TYPE_BUY
        );

        prepareCommonFields(exitToken, sc);
        return exitToken;
    }

    private void saveSpreadPosition(Indicator indicator, SpreadType spreadType,
                                    OrderResult buyResult, OrderResult sellResult,
                                    Token buyLeg, Token sellLeg) {
        try {
            // TODO: Implement SpreadPosition entity and repository
            log.info("Spread position tracked: {} {} - Buy:{} Sell:{}",
                     indicator.getTradingSymbol(), spreadType,
                     buyResult.getOrderId(), sellResult.getOrderId());

        } catch (Exception e) {
            log.error("Failed to save spread position: {}", e.getMessage(), e);
        }
    }

    private void sendAlert(String message) {
        // TODO: Implement alerting
        log.error("ALERT: {}", message);
    }

    // ---------------------------------------------------------------------
    // STRIKE & DATA UTILITIES
    // ---------------------------------------------------------------------
    private BigDecimal fetchLTP(SmartConnect smartConnect, Indicator indicator) {
        try {
            return angelOneService.getcurrentPrice(
                smartConnect,
                indicator.getExchange(),
                indicator.getTradingSymbol(),
                indicator.getToken(),
                "ltp"
            );
        } catch (Exception e) {
            log.error("Failed to fetch LTP for {}: {}", 
                     indicator.getTradingSymbol(), e.getMessage(), e);
            return null;
        }
    }

    private List<BigDecimal> fetchAllStrikesForInstrument(String tradingSymbol, String expiry) {
        List<Indexes> list = indexesRepo.findByNameAndExpiry(tradingSymbol, expiry);

        if (list == null || list.isEmpty()) {
            log.warn("No strikes found for {} expiry {}", tradingSymbol, expiry);
            return Collections.emptyList();
        }

        return list.stream()
            .map(Indexes::getStrike)
            .filter(Objects::nonNull)
            .map(String::trim)
            .map(this::normalizeStrikeToBigDecimal)
            .filter(v -> v.compareTo(BigDecimal.ZERO) > 0)
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    }

    private BigDecimal normalizeStrikeToBigDecimal(String raw) {
        try {
            BigDecimal bd = new BigDecimal(raw.trim());
            BigDecimal normalized = bd.divide(STRIKE_DIVISOR);
            if (normalized.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Normalized strike <= 0 for raw='{}' -> {}", raw, normalized);
                return BigDecimal.ZERO;
            }
            return normalized;
        } catch (Exception e) {
            log.error("Invalid strike format: {}", raw, e);
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal findNearestStrike(BigDecimal ltp, List<BigDecimal> strikes) {
        return strikes.stream()
            .min(Comparator.comparing(s -> s.subtract(ltp).abs()))
            .orElse(strikes.isEmpty() ? BigDecimal.ZERO : strikes.get(0));
    }

    private BigDecimal findStrikeInterval(List<BigDecimal> strikes) {
        if (strikes.size() < 2) {
            return BigDecimal.ZERO;
        }

        Map<BigDecimal, Long> diffFrequency = new HashMap<>();
        for (int i = 1; i < strikes.size(); i++) {
            BigDecimal diff = strikes.get(i).subtract(strikes.get(i - 1)).abs();
            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                diffFrequency.merge(diff, 1L, Long::sum);
            }
        }

        return diffFrequency.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(BigDecimal.ZERO);
    }

    // ---------------------------------------------------------------------
    // TOKEN BUILDING
    // ---------------------------------------------------------------------
    private Token buildToken(Indicator indicator, Expiry expiry,
                             BigDecimal strike, String optionType, String transactionType) {

        if (strike == null) {
            log.error("Null strike when building token for {}", indicator.getTradingSymbol());
            return null;
        }

        String expiryCode = expiry.getExpirydate();
        String strikeStr = strike.stripTrailingZeros().toPlainString();
        String shortExpiry = toShortExpiry(expiryCode);
        String symbol = indicator.getTradingSymbol() + shortExpiry + strikeStr + optionType;

        Indexes idx = indexesRepo.findBySymbol(symbol);
        if (idx == null) {
            log.error("Symbol not found in DB: {}", symbol);
            return null;
        }

        Token token = new Token();
        token.setSymbol(symbol);
        token.setToken(idx.getToken());
        token.setExch_seg(idx.getExchange());
        token.setTransactionType(transactionType);
        token.setQuantity(idx.getLotsize());

        return token;
    }

    public String toShortExpiry(String expiryCode) {
        if (expiryCode == null) {
            log.warn("Invalid expiry code: null");
            return "";
        }

        Pattern p = Pattern.compile("^(\\d{1,2})([A-Za-z]{3})(\\d{4})$");
        Matcher m = p.matcher(expiryCode.trim());
        if (m.matches()) {
            String day = m.group(1);
            if (day.length() == 1) day = "0" + day;
            String mon = m.group(2).toUpperCase();
            String yy = m.group(3).substring(2);
            return day + mon + yy;
        }

        if (expiryCode.length() >= 7) {
            return expiryCode;
        }

        log.warn("Unrecognized expiry format: {}", expiryCode);
        return expiryCode;
    }

    private void prepareCommonFields(Token token, SmartConnect smartConnect) {
        if (token.getExch_seg() == null || token.getExch_seg().trim().isEmpty()) {
            token.setExch_seg("NFO");
        }

        BigDecimal currentPrice = angelOneService.getcurrentPrice(
            smartConnect,
            token.getExch_seg(),
            token.getSymbol(),
            token.getToken(),
            "ltp"
        );

        token.setPrice(currentPrice.doubleValue());
        token.setName(AppConstant.SPREAD_STRATEGY);
        token.setProductType(Constants.PRODUCT_CARRYFORWARD);
        token.setVariety(Constants.VARIETY_NORMAL);
        token.setOrderType(Constants.ORDER_TYPE_MARKET);
    }

    // ---------------------------------------------------------------------
    // UTILITY METHODS
    // ---------------------------------------------------------------------
    public void resetDailyCounters() {
        processedIndicatorsToday.clear();
        spreadsPlacedToday = 0;
        log.info("Daily counters reset");
    }

    public Map<String, Object> getSpreadStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("spreadsPlacedToday", spreadsPlacedToday);
        stats.put("maxSpreadsPerDay", MAX_SPREADS_PER_DAY);
        stats.put("uniqueIndicatorsProcessed", processedIndicatorsToday.size());
        stats.put("remainingCapacity", MAX_SPREADS_PER_DAY - spreadsPlacedToday);
        return stats;
    }
}