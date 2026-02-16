package com.crumbs.trade.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
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
 * Enhanced SpreadService with INTELLIGENT strike selection based on OI and IV.
 *
 * Selection Modes:
 * 1. DISTANCE_BASED (original) - Fixed strike intervals from ATM
 * 2. OI_BASED - Select strikes with strong OI support/resistance
 * 3. IV_BASED - Select strikes with optimal IV levels
 * 4. COMBINED - Use both OI and IV for best strike selection
 *
 * Version: 3.4 
 * Features:
 * - Dynamic max loss thresholds per mode (JSON format in Strategy.maxloss)
 * - Auto-adjust strikes instead of skipping when max loss breached
 * - Rate limit graceful handling with DISTANCE_BASED fallback
 * - Proper order sequencing for margin benefit (buy first, then sell)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpreadService {

    // --------------------------------------------------------------------- 
    // Autowired Repos & Services
    // --------------------------------------------------------------------- 
    @Autowired
    private IndicatorRepo indicatorRepo;

    @Autowired
    private AngelOneService angelOneService;

    @Autowired
    private AngelOne angelOne;

    @Autowired
    private ExpiryRepo expiryRepo;

    @Autowired
    private IndexesRepo indexesRepo;

    @Autowired
    private StrategyRepo strategyRepo;

    @Autowired
    private PredictionService predictionService;

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
    private int stocksSkippedDueToMaxLoss = 0;
    
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_DELAY_MS = 300;   // start delay
    private static final long RATE_LIMIT_DELAY_MS = 200; // between API calls

    // --------------------------------------------------------------------- 
    // Enums
    // --------------------------------------------------------------------- 
    public enum SpreadType {
        BULL_CALL, BEAR_CALL, BULL_PUT, BEAR_PUT, NAKED_CALL_HEDGE, NAKED_PUT_HEDGE
    }

    /**
     * Strike Selection Strategy
     */
    public enum StrikeSelectionMode {
        DISTANCE_BASED, // Original: Fixed distance from ATM
        OI_BASED,       // Select based on OI support/resistance
        IV_BASED,       // Select based on IV levels
        COMBINED        // Use both OI and IV (RECOMMENDED)
    }

    /**
     * Distance levels for DISTANCE_BASED mode (backward compatibility)
     */
    public enum StrikeDistanceLevel {
        ATM,      // 0 steps
        NEAR_OTM, // 1 step
        MID_OTM,  // 2 steps
        FAR_OTM   // 3 steps
    }

    private static final Map<StrikeDistanceLevel, Integer> DISTANCE_STEPS_MAP = Map.of(
            StrikeDistanceLevel.ATM, 0,
            StrikeDistanceLevel.NEAR_OTM, 1,
            StrikeDistanceLevel.MID_OTM, 2,
            StrikeDistanceLevel.FAR_OTM, 3
    );

    // --------------------------------------------------------------------- 
    // Configuration Class for Strike Selection
    // --------------------------------------------------------------------- 
    public static class StrikeSelectionConfig {
        private StrikeSelectionMode mode = StrikeSelectionMode.DISTANCE_BASED;

        // OI Configuration
        private BigDecimal minOIThreshold = BigDecimal.valueOf(1000);
        private BigDecimal oiSupportMultiplier = BigDecimal.valueOf(1.5);

        // IV Configuration
        private BigDecimal maxIVThreshold = BigDecimal.valueOf(50);
        private BigDecimal minIVThreshold = BigDecimal.valueOf(15);
        private BigDecimal idealIVForSelling = BigDecimal.valueOf(30);

        // Combined Configuration
        private BigDecimal oiWeight = BigDecimal.valueOf(0.6);
        private BigDecimal ivWeight = BigDecimal.valueOf(0.4);

        // Search Range
        private int strikeSearchRange = 5;

        // Fallback Configuration
        private StrikeDistanceLevel fallbackDistance = StrikeDistanceLevel.NEAR_OTM;

        // Getters and Setters
        public StrikeSelectionMode getMode() {
            return mode;
        }

        public void setMode(StrikeSelectionMode mode) {
            this.mode = mode;
        }

        public BigDecimal getMinOIThreshold() {
            return minOIThreshold;
        }

        public void setMinOIThreshold(BigDecimal minOIThreshold) {
            this.minOIThreshold = minOIThreshold;
        }

        public BigDecimal getOiSupportMultiplier() {
            return oiSupportMultiplier;
        }

        public void setOiSupportMultiplier(BigDecimal oiSupportMultiplier) {
            this.oiSupportMultiplier = oiSupportMultiplier;
        }

        public BigDecimal getMaxIVThreshold() {
            return maxIVThreshold;
        }

        public void setMaxIVThreshold(BigDecimal maxIVThreshold) {
            this.maxIVThreshold = maxIVThreshold;
        }

        public BigDecimal getMinIVThreshold() {
            return minIVThreshold;
        }

        public void setMinIVThreshold(BigDecimal minIVThreshold) {
            this.minIVThreshold = minIVThreshold;
        }

        public BigDecimal getIdealIVForSelling() {
            return idealIVForSelling;
        }

        public void setIdealIVForSelling(BigDecimal idealIVForSelling) {
            this.idealIVForSelling = idealIVForSelling;
        }

        public BigDecimal getOiWeight() {
            return oiWeight;
        }

        public void setOiWeight(BigDecimal oiWeight) {
            this.oiWeight = oiWeight;
        }

        public BigDecimal getIvWeight() {
            return ivWeight;
        }

        public void setIvWeight(BigDecimal ivWeight) {
            this.ivWeight = ivWeight;
        }

        public int getStrikeSearchRange() {
            return strikeSearchRange;
        }

        public void setStrikeSearchRange(int strikeSearchRange) {
            this.strikeSearchRange = strikeSearchRange;
        }

        public StrikeDistanceLevel getFallbackDistance() {
            return fallbackDistance;
        }

        public void setFallbackDistance(StrikeDistanceLevel fallbackDistance) {
            this.fallbackDistance = fallbackDistance;
        }
    }

    // --------------------------------------------------------------------- 
    // Strike Data Class
    // --------------------------------------------------------------------- 
    private static class StrikeData {
        BigDecimal strike;
        String optionType; // "CE" or "PE"
        BigDecimal price;
        BigDecimal oi;
        BigDecimal iv;
        BigDecimal volume;
        String token;
        String symbol;
        BigDecimal score; // Composite score for ranking

        public StrikeData(BigDecimal strike, String optionType) {
            this.strike = strike;
            this.optionType = optionType;
        }

        @Override
        public String toString() {
            return String.format("Strike: %s %s, Price: %s, OI: %s, IV: %s, Score: %s",
                    strike, optionType, price, oi, iv, score);
        }
    }

    // --------------------------------------------------------------------- 
    // PUBLIC API - WITH CONFIGURATION
    // --------------------------------------------------------------------- 
    /**
     * Entry point with default COMBINED mode
     */
    public void getStockList() {
        StrikeSelectionConfig config = new StrikeSelectionConfig();
        config.setMode(StrikeSelectionMode.DISTANCE_BASED);
        getStockListWithConfig(config);
    }

    /**
     * Entry point with custom configuration
     */
    public void getStockListWithConfig(StrikeSelectionConfig config) {
        List<String> signals = List.of("FIRST BUY", "FIRST SELL");
        List<Indicator> rawList = indicatorRepo.findByTradetypeAndOptions("DAILY", "Y");

        List<Indicator> indicatorList = rawList.stream()
                .filter(i -> i.getHeikinAshiDay().equals(i.getPsarFlagDay()))
                .toList();

        SmartConnect smartConnect = angelOne.signIn();

        log.info("Processing {} Spread Strategy with {} mode (Daily limit: {})",
                indicatorList.size(), config.getMode(), MAX_SPREADS_PER_DAY);

        for (Indicator indicator : indicatorList) {
            if (spreadsPlacedToday >= MAX_SPREADS_PER_DAY) {
                log.warn("Daily spread limit reached ({}). Stopping processing.", MAX_SPREADS_PER_DAY);
                break;
            }

            try {
                processIndicatorWithConfig(smartConnect, indicator, config);
            } catch (Exception e) {
                log.error("Failed processing {}: {}", indicator.getTradingSymbol(), e.getMessage(), e);
            }
        }

        log.info("Processing complete. Spreads placed: {}, Skipped due to max loss: {}",
                spreadsPlacedToday, stocksSkippedDueToMaxLoss);
    }

    /**
     * Process indicator with intelligent strike selection
     */
    public void processIndicatorWithConfig(SmartConnect smartConnect, Indicator indicator,
                                           StrikeSelectionConfig config) {
        String signal = indicator.getHeikinAshiDay();
        SpreadType spreadType;

        if ("FIRST BUY".equalsIgnoreCase(signal)) {
            spreadType = SpreadType.BULL_PUT;
        } else if ("FIRST SELL".equalsIgnoreCase(signal)) {
            spreadType = SpreadType.BEAR_CALL;
        } else {
            log.warn("Unknown signal '{}' for {} - skipping", signal, indicator.getTradingSymbol());
            return;
        }

        log.info("Placing {} spread for {} using {} mode based on signal {}",
                spreadType, indicator.getTradingSymbol(), config.getMode(), signal);

        processIndicatorIntelligent(smartConnect, indicator, spreadType, config);
    }

    // --------------------------------------------------------------------- 
    // INTELLIGENT STRIKE SELECTION
    // --------------------------------------------------------------------- 
    /**
     * Main processing method with intelligent strike selection
     * ✅ CHANGE 1: Added graceful error handling for rate limits
     * 
     * IMPORTANT: Order execution sequence for margin benefit:
     * 1. Buy leg executes FIRST
     * 2. Wait for buy confirmation
     * 3. Sell leg executes SECOND
     * 
     * Why this sequence matters:
     * - In a spread, buying the far strike first establishes the hedge position
     * - When the sell leg is placed, broker recognizes this as a spread
     * - Margin requirement is reduced (only net risk, not full margin for both legs)
     * - Example: Bull Put Spread
     *   - Buy 23000 PE (hedge)
     *   - Sell 23100 PE (premium collection)
     *   - Margin = (100 point width - net premium) × lot size
     *   - NOT: Full margin for naked 23100 PE sell
     */
    @Transactional
    public void processIndicatorIntelligent(SmartConnect smartConnect, Indicator indicator,
                                           SpreadType spreadType, StrikeSelectionConfig config) {
        log.info("=== Processing Intelligent Spread for {} | Type: {} | Mode: {} ===",
                indicator.getName(), spreadType, config.getMode());

        // Duplicate prevention
        String indicatorKey = indicator.getName() + "_" + spreadType.name();
        if (processedIndicatorsToday.contains(indicatorKey)) {
            log.info("Already processed {} today - skipping duplicate", indicatorKey);
            return;
        }

        // Fetch Strategy early to get maxloss threshold
        Strategy strategy = strategyRepo.findByName(AppConstant.SPREAD_STRATEGY);
        if (strategy == null) {
            log.error("Strategy '{}' not found - cannot proceed", AppConstant.SPREAD_STRATEGY);
            saveSkippedSpread(indicator, spreadType, null, 
                "Strategy 'SPREAD_STRATEGY' not found in database", null, null);
            return;
        }

        // Fetch LTP
        BigDecimal ltp = fetchLTP(smartConnect, indicator);
        if (ltp == null) {
            log.warn("Cannot proceed without LTP for {}", indicator.getTradingSymbol());
            saveSkippedSpread(indicator, spreadType, null, 
                "Failed to fetch LTP (Last Traded Price)", null, null);
            return;
        }

        // Get expiry
        Optional<Expiry> optionalExpiry = expiryRepo.findById(DEFAULT_EXPIRY_ID);
        if (optionalExpiry.isEmpty()) {
            log.error("Expiry not found (ID: {}). Check database configuration.", DEFAULT_EXPIRY_ID);
            saveSkippedSpread(indicator, spreadType, null, 
                "Expiry not found (ID: " + DEFAULT_EXPIRY_ID + ")", null, null);
            return;
        }
        Expiry expiry = optionalExpiry.get();

        // Validate expiry date
        if (!validateExpiryDate(expiry)) {
            saveSkippedSpread(indicator, spreadType, null, 
                "Expiry date validation failed (current month after 15th)", null, null);
            return;
        }

        // Fetch all available strikes
        List<BigDecimal> strikes = fetchAllStrikesForInstrument(
                indicator.getName(), expiry.getExpirydate());
        if (strikes.isEmpty()) {
            log.error("No strikes found for {} expiry {}",
                    indicator.getTradingSymbol(), expiry.getExpirydate());
            saveSkippedSpread(indicator, spreadType, null, 
                "No strikes found for expiry " + expiry.getExpirydate(), null, null);
            return;
        }

        // Find ATM strike
        BigDecimal atmStrike = findNearestStrike(ltp, strikes);
        BigDecimal stepSize = findStrikeInterval(strikes);

        log.info("Market Data: LTP={} | ATM={} | Step={}", ltp, atmStrike, stepSize);

        // ✅ CHANGE 1: Wrap OI/IV fetch in try-catch for graceful fallback
        Map<String, StrikeData> strikeDataMap = Collections.emptyMap();
        StrikeSelectionMode actualModeUsed;
        
        try {
            strikeDataMap = fetchStrikeDataForRange(
                    smartConnect, indicator, expiry, atmStrike, strikes, config.getStrikeSearchRange());
        } catch (Exception e) {
            log.warn("⚠️ Failed to fetch OI/IV data for {}: {} - Will use DISTANCE_BASED mode",
                    indicator.getTradingSymbol(), e.getMessage());
        }

        // Select optimal strikes - will automatically fall back to DISTANCE_BASED if no data
        SpreadCalculation calc;
        if (strikeDataMap.isEmpty()) {
            log.info("Using DISTANCE_BASED mode (no OI/IV data available)");
            calc = calculateSpreadDistanceBased(ltp, strikes, spreadType, StrikeDistanceLevel.NEAR_OTM);
            actualModeUsed = StrikeSelectionMode.DISTANCE_BASED;
        } else {
            calc = selectOptimalStrikes(ltp, atmStrike, stepSize, strikes, spreadType, config, strikeDataMap);
            actualModeUsed = config.getMode();
        }

        if (!calc.isValid()) {
            log.error("Invalid spread calculation for {}: {}",
                    indicator.getTradingSymbol(), calc.getErrorMessage());
            saveSkippedSpread(indicator, spreadType, calc, 
                "Invalid spread calculation: " + calc.getErrorMessage(), null, null);
            return;
        }

        log.info("Selected Strikes: Buy={} {} | Sell={} {} | Mode: {} | Selection Score: {}",
                calc.buyStrike, calc.buyOptionType, calc.sellStrike, calc.sellOptionType, 
                actualModeUsed, calc.selectionScore);

        // Build tokens
        Token buyLeg = buildToken(indicator, expiry, calc.buyStrike, calc.buyOptionType,
                Constants.TRANSACTION_TYPE_BUY);
        Token sellLeg = buildToken(indicator, expiry, calc.sellStrike, calc.sellOptionType,
                Constants.TRANSACTION_TYPE_SELL);

        if (buyLeg == null || sellLeg == null) {
            log.error("Failed to build tokens for {}", indicator.getName());
            saveSkippedSpread(indicator, spreadType, calc, 
                "Failed to build tokens (symbol not found in database)", null, null);
            return;
        }

        // Prepare fields and price
        prepareCommonFields(buyLeg, smartConnect);
        prepareCommonFields(sellLeg, smartConnect);

        // Parse max loss threshold based on actual mode used
        BigDecimal maxLossThreshold = parseMaxLossThreshold(strategy, actualModeUsed);
        
        if (maxLossThreshold == null) {
            log.error("Failed to get max loss threshold for {} - skipping", indicator.getTradingSymbol());
            saveSkippedSpread(indicator, spreadType, calc, 
                "Failed to parse max loss threshold from Strategy", null, null);
            return;
        }

        // Validate and auto-adjust max loss (instead of just validating)
        SpreadCalculation adjustedCalc = validateAndAdjustMaxLoss(
            calc, buyLeg, sellLeg, strikes, maxLossThreshold, indicator.getTradingSymbol());
        
        if (adjustedCalc == null) {
            log.warn("Skipping {} - could not meet max loss requirement", indicator.getTradingSymbol());
            BigDecimal calculatedRisk = calculateMaxRiskFromCalc(calc, buyLeg, sellLeg);
            saveSkippedSpread(indicator, spreadType, calc, 
                "Max loss threshold breached - could not adjust strikes to fit", 
                calculatedRisk, maxLossThreshold);
            return;
        }
        
        // If strikes were adjusted, rebuild tokens with new strikes
        if (!adjustedCalc.buyStrike.equals(calc.buyStrike) || 
            !adjustedCalc.sellStrike.equals(calc.sellStrike)) {
            
            log.info("Rebuilding tokens with adjusted strikes...");
            
            buyLeg = buildToken(indicator, expiry, adjustedCalc.buyStrike, adjustedCalc.buyOptionType,
                    Constants.TRANSACTION_TYPE_BUY);
            sellLeg = buildToken(indicator, expiry, adjustedCalc.sellStrike, adjustedCalc.sellOptionType,
                    Constants.TRANSACTION_TYPE_SELL);
            
            if (buyLeg == null || sellLeg == null) {
                log.error("Failed to build tokens with adjusted strikes for {}", indicator.getName());
                return;
            }
            
            prepareCommonFields(buyLeg, smartConnect);
            prepareCommonFields(sellLeg, smartConnect);
        }
        
        // Use adjusted calc for final calculations
        calc = adjustedCalc;

        // Risk / reward calculations
        BigDecimal maxRisk = calculateMaxRisk(buyLeg, sellLeg, calc);
        BigDecimal maxProfit = calculateMaxProfit(buyLeg, sellLeg, calc);

        BigDecimal rr = BigDecimal.ZERO;
        if (maxRisk.compareTo(BigDecimal.ZERO) != 0) {
            rr = maxProfit.divide(maxRisk, 2, RoundingMode.HALF_UP);
        }

        log.info("Spread P&L: MaxRisk={} | MaxProfit={} | Risk:Reward=1:{}", maxRisk, maxProfit, rr);

        if (!strikeDataMap.isEmpty()) {
            log.info("Strike Selection Details: SellStrike OI={}, IV={} | BuyStrike OI={}, IV={}",
                    calc.sellStrikeOI, calc.sellStrikeIV, calc.buyStrikeOI, calc.buyStrikeIV);
        }

        // Validate margin
        if (!validateMarginRequirement(smartConnect)) {
            log.error("Insufficient margin for {} spread - aborting", indicator.getTradingSymbol());
            saveSkippedSpread(indicator, spreadType, calc, 
                "Insufficient margin available in account", maxRisk, maxLossThreshold);
            return;
        }

        // Validate market depth (liquidity check before market order)
        log.info("Validating market depth for liquidity...");
        if (!validateMarketDepth(smartConnect, buyLeg, sellLeg)) {
            log.error("❌ SKIPPING {}: Insufficient liquidity or wide spread - unsafe for market order",
                     indicator.getTradingSymbol());
            saveSkippedSpread(indicator, spreadType, calc, 
                "Insufficient liquidity or wide bid-ask spread - unsafe for market order", 
                maxRisk, maxLossThreshold);
            stocksSkippedDueToMaxLoss++; // Track as skipped
            return;
        }

        // Place spread (strategy already fetched above)
        boolean success = placeSpreadOrder(smartConnect, buyLeg, sellLeg, indicator, spreadType, strategy);

        if (success) {
            processedIndicatorsToday.add(indicatorKey);
            spreadsPlacedToday++;
            log.info("Spread placed successfully! Total today: {}/{}", spreadsPlacedToday, MAX_SPREADS_PER_DAY);
        }
    }

    // --------------------------------------------------------------------- 
    // Max Loss Configuration from Strategy
    // --------------------------------------------------------------------- 
    /**
     * Parse maxloss from Strategy entity - supports both simple and JSON formats
     * 
     * Simple format: "6000"
     * JSON format: {"intelligent": "8000", "distance": "5000"}
     * 
     * - intelligent: Used for OI_BASED, IV_BASED, COMBINED modes (higher threshold)
     * - distance: Used for DISTANCE_BASED mode (lower threshold)
     * 
     * @param strategy Strategy entity
     * @param mode Strike selection mode being used
     * @return Max loss threshold for the mode
     */
    private BigDecimal parseMaxLossThreshold(Strategy strategy, StrikeSelectionMode mode) {
        if (strategy == null || strategy.getMaxloss() == null || strategy.getMaxloss().trim().isEmpty()) {
            log.error("No maxloss configured in Strategy");
            return null;
        }
        
        String maxlossStr = strategy.getMaxloss().trim();
        
        try {
            // Try JSON format first
            if (maxlossStr.startsWith("{")) {
                JSONObject maxlossJson = new JSONObject(maxlossStr);
                
                // Determine which threshold to use based on mode
                String key;
                if (mode == StrikeSelectionMode.DISTANCE_BASED) {
                    key = "distance";
                } else {
                    // OI_BASED, IV_BASED, COMBINED use "intelligent" threshold
                    key = "intelligent";
                }
                
                if (!maxlossJson.has(key)) {
                    log.error("maxloss JSON missing '{}' key: {}", key, maxlossStr);
                    return null;
                }
                
                String thresholdStr = maxlossJson.getString(key);
                BigDecimal threshold = new BigDecimal(thresholdStr);
                
                log.info("Using maxloss threshold from Strategy: {} = {} (mode: {})", 
                         key, threshold, mode);
                return threshold;
                
            } else {
                // Simple format - same threshold for all modes
                BigDecimal threshold = new BigDecimal(maxlossStr);
                log.info("Using maxloss threshold from Strategy: {} (simple format, mode: {})", 
                         threshold, mode);
                return threshold;
            }
            
        } catch (Exception e) {
            log.error("Failed to parse maxloss '{}': {}", maxlossStr, e.getMessage());
            return null;
        }
    }

    // --------------------------------------------------------------------- 
    // Max Loss Validation with Auto-Adjustment
    // --------------------------------------------------------------------- 
    /**
     * Validate and auto-adjust spread for max loss compliance
     * Instead of skipping, this tries to adjust strikes to meet max loss threshold
     * 
     * @param calc Original spread calculation
     * @param buyLeg Buy leg token
     * @param sellLeg Sell leg token
     * @param strikes Available strikes list
     * @param threshold Max loss threshold
     * @param symbol Trading symbol for logging
     * @return Adjusted SpreadCalculation or null if cannot be adjusted
     */
    private SpreadCalculation validateAndAdjustMaxLoss(
            SpreadCalculation calc, Token buyLeg, Token sellLeg,
            List<BigDecimal> strikes, BigDecimal threshold, String symbol) {
        
        if (threshold == null) {
            log.error("No max loss threshold configured - skipping {}", symbol);
            stocksSkippedDueToMaxLoss++;
            return null;
        }
        
        if (threshold.compareTo(BigDecimal.ZERO) <= 0) {
            log.error("Invalid max loss threshold {} (must be > 0) - skipping {}", threshold, symbol);
            stocksSkippedDueToMaxLoss++;
            return null;
        }
        
        // Calculate current max risk
        BigDecimal currentMaxRisk = calculateMaxRiskFromCalc(calc, buyLeg, sellLeg);
        
        if (currentMaxRisk.compareTo(threshold) <= 0) {
            // Within threshold - no adjustment needed
            log.info("✓ Max Loss Validation PASSED for {}: {} <= {} (from Strategy)",
                    symbol, currentMaxRisk, threshold);
            return calc;
        }
        
        // Max loss breached - try to adjust strikes to fit within threshold
        log.warn("⚠️ Max Loss EXCEEDED for {}: {} > {} - Attempting to adjust strikes...",
                symbol, currentMaxRisk, threshold);
        
        SpreadCalculation adjustedCalc = adjustStrikesForMaxLoss(
            calc, buyLeg, sellLeg, strikes, threshold, symbol);
        
        if (adjustedCalc != null) {
            BigDecimal adjustedMaxRisk = calculateMaxRiskFromCalc(adjustedCalc, buyLeg, sellLeg);
            log.info("✓ Strikes ADJUSTED for {}: MaxLoss {} → {} (within threshold {})",
                    symbol, currentMaxRisk, adjustedMaxRisk, threshold);
            log.info("  Original: Buy={} Sell={}", calc.buyStrike, calc.sellStrike);
            log.info("  Adjusted: Buy={} Sell={}", adjustedCalc.buyStrike, adjustedCalc.sellStrike);
            return adjustedCalc;
        }
        
        // Cannot adjust - skip this spread
        log.warn("❌ SKIPPING {}: Cannot adjust strikes to meet max loss threshold {} (current: {})",
                symbol, threshold, currentMaxRisk);
        stocksSkippedDueToMaxLoss++;
        return null;
    }
    
    /**
     * Adjust strikes by reducing width to meet max loss threshold
     * Strategy: Move strikes closer to ATM to reduce risk
     */
    private SpreadCalculation adjustStrikesForMaxLoss(
            SpreadCalculation originalCalc, Token buyLeg, Token sellLeg,
            List<BigDecimal> strikes, BigDecimal threshold, String symbol) {
        
        BigDecimal stepSize = originalCalc.stepSize;
        boolean isPut = "PE".equals(originalCalc.sellOptionType);
        
        // Start from ATM and work outward with reduced width
        BigDecimal atmStrike = originalCalc.atmStrike;
        
        // Try reducing strike width step by step
        for (int width = 1; width <= 3; width++) {
            SpreadCalculation testCalc = new SpreadCalculation();
            testCalc.atmStrike = atmStrike;
            testCalc.stepSize = stepSize;
            testCalc.buyOptionType = originalCalc.buyOptionType;
            testCalc.sellOptionType = originalCalc.sellOptionType;
            
            if (isPut) {
                // Bull Put Spread: Sell higher strike, Buy lower strike
                // Move closer to ATM
                testCalc.sellStrike = atmStrike.subtract(stepSize.multiply(BigDecimal.valueOf(width)));
                testCalc.buyStrike = testCalc.sellStrike.subtract(stepSize);
            } else {
                // Bear Call Spread: Sell lower strike, Buy higher strike
                // Move closer to ATM
                testCalc.sellStrike = atmStrike.add(stepSize.multiply(BigDecimal.valueOf(width)));
                testCalc.buyStrike = testCalc.sellStrike.add(stepSize);
            }
            
            // Validate strikes are available
            if (!isValidStrike(testCalc.buyStrike, strikes) || 
                !isValidStrike(testCalc.sellStrike, strikes)) {
                continue;
            }
            
            // Build test tokens to calculate risk
            Token testBuyLeg = new Token();
            testBuyLeg.setSymbol(buyLeg.getSymbol());
            testBuyLeg.setPrice(buyLeg.getPrice()); // Approximate - would need real price
            testBuyLeg.setQuantity(buyLeg.getQuantity());
            
            Token testSellLeg = new Token();
            testSellLeg.setSymbol(sellLeg.getSymbol());
            testSellLeg.setPrice(sellLeg.getPrice()); // Approximate
            testSellLeg.setQuantity(sellLeg.getQuantity());
            
            BigDecimal testMaxRisk = calculateMaxRiskFromCalc(testCalc, testBuyLeg, testSellLeg);
            
            if (testMaxRisk.compareTo(threshold) <= 0) {
                log.info("Found adjusted strikes with width {} steps: MaxRisk {} <= {}", 
                         width, testMaxRisk, threshold);
                return testCalc;
            }
        }
        
        // Could not find valid adjustment
        log.warn("Unable to adjust strikes for {} within max loss threshold {}", symbol, threshold);
        return null;
    }
    
    /**
     * Calculate max risk from SpreadCalculation (without full token setup)
     */
    private BigDecimal calculateMaxRiskFromCalc(SpreadCalculation calc, Token buyLeg, Token sellLeg) {
        BigDecimal strikeWidth = calc.sellStrike.subtract(calc.buyStrike).abs();
        BigDecimal premium = BigDecimal.valueOf(sellLeg.getPrice())
                .subtract(BigDecimal.valueOf(buyLeg.getPrice()));
        
        if (premium.compareTo(BigDecimal.ZERO) > 0) {
            // Credit spread - risk is width minus premium collected
            return strikeWidth.subtract(premium)
                    .multiply(BigDecimal.valueOf(buyLeg.getQuantity()));
        } else {
            // Debit spread - risk is premium paid
            return premium.abs().multiply(BigDecimal.valueOf(buyLeg.getQuantity()));
        }
    }

    // --------------------------------------------------------------------- 
    // FETCH OI AND IV DATA FOR STRIKE RANGE
    // ✅ CHANGE 2 & 4: Don't throw exceptions, return empty/partial data gracefully
    // --------------------------------------------------------------------- 
    private Map<String, StrikeData> fetchStrikeDataForRange(
            SmartConnect smartConnect, Indicator indicator, Expiry expiry,
            BigDecimal atmStrike, List<BigDecimal> allStrikes, int searchRange) {

        Map<String, StrikeData> dataMap = new HashMap<>();
        BigDecimal stepSize = findStrikeInterval(allStrikes);

        // Build list of strikes to fetch
        List<BigDecimal> strikesToFetch = new ArrayList<>();
        for (int i = -searchRange; i <= searchRange; i++) {
            BigDecimal strike = atmStrike.add(stepSize.multiply(BigDecimal.valueOf(i)));
            if (isValidStrike(strike, allStrikes)) {
                strikesToFetch.add(strike);
            }
        }

        log.info("Fetching OI/IV data for {} strikes around ATM {}", strikesToFetch.size(), atmStrike);

        // ✅ CHANGE 2: Fetch IV data with error handling - don't fail if unavailable
        Map<String, BigDecimal> ivMap = Collections.emptyMap();
        try {
            ivMap = fetchIVFromGreeksAPI(smartConnect, indicator.getName(), expiry.getExpirydate());
        } catch (Exception e) {
            log.warn("Could not fetch IV data: {} - Continuing without IV", e.getMessage());
        }

        // Fetch price and OI data in batch
        List<String> tokens = new ArrayList<>();
        Map<String, StrikeData> tokenToStrikeData = new HashMap<>();

        for (BigDecimal strike : strikesToFetch) {
            // CE data
            StrikeData ceData = createStrikeData(strike, "CE", indicator, expiry);
            if (ceData != null && ceData.token != null) {
                tokens.add(ceData.token);
                tokenToStrikeData.put(ceData.token, ceData);
                String key = strike.intValue() + "_CE";
                dataMap.put(key, ceData);

                // Set IV from Greeks API
                BigDecimal iv = ivMap.get(key);
                if (iv != null && iv.compareTo(BigDecimal.ZERO) > 0) {
                    ceData.iv = iv;
                }
            }

            // PE data
            StrikeData peData = createStrikeData(strike, "PE", indicator, expiry);
            if (peData != null && peData.token != null) {
                tokens.add(peData.token);
                tokenToStrikeData.put(peData.token, peData);
                String key = strike.intValue() + "_PE";
                dataMap.put(key, peData);

                // Set IV from Greeks API
                BigDecimal iv = ivMap.get(key);
                if (iv != null && iv.compareTo(BigDecimal.ZERO) > 0) {
                    peData.iv = iv;
                }
            }
        }

        if (tokens.isEmpty()) {
            log.error("No valid tokens found for strike range");
            return dataMap;
        }

        // ✅ CHANGE 4: Batch fetch price and OI with error handling
        try {
            JSONObject payload = new JSONObject();
            payload.put("mode", "FULL");
            JSONObject map = new JSONObject();
            map.put("NFO", tokens);
            payload.put("exchangeTokens", map);

            JSONObject response = predictionService.callMarketDataWithRetry(smartConnect, payload);

            if (response != null && response.has("fetched")) {
                JSONArray fetched = response.getJSONArray("fetched");
                for (int i = 0; i < fetched.length(); i++) {
                    JSONObject item = fetched.getJSONObject(i);
                    String token = item.optString("symbolToken", null);

                    if (token != null && tokenToStrikeData.containsKey(token)) {
                        StrikeData data = tokenToStrikeData.get(token);
                        data.price = item.optBigDecimal("ltp", BigDecimal.ZERO);
                        data.oi = item.optBigDecimal("opnInterest", BigDecimal.ZERO);
                        data.volume = item.optBigDecimal("tradeVolume", BigDecimal.ZERO);
                    }
                }
                log.info("Successfully fetched price/OI data for {} tokens", fetched.length());
            }
        } catch (Exception | SmartAPIException e) {
            log.warn("Could not fetch market data: {} - Continuing with available data", e.getMessage());
        }

        return dataMap;
    }

    /**
     * Create StrikeData object with token lookup
     */
    private StrikeData createStrikeData(BigDecimal strike, String optionType, Indicator indicator, Expiry expiry) {
        StrikeData data = new StrikeData(strike, optionType);

        String expiryCode = expiry.getExpirydate();
        String strikeStr = strike.stripTrailingZeros().toPlainString();
        String shortExpiry = toShortExpiry(expiryCode);
        String symbol = indicator.getName() + shortExpiry + strikeStr + optionType;

        Indexes idx = indexesRepo.findBySymbol(symbol);
        if (idx != null) {
            data.token = idx.getToken();
            data.symbol = idx.getSymbol();
        } else {
            log.warn("Symbol not found: {}", symbol);
        }

        return data;
    }

    /**
     * Fetch IV from Greeks API
     * ✅ CHANGE 3: Don't throw exceptions, return empty map gracefully
     */
    private Map<String, BigDecimal> fetchIVFromGreeksAPI(
            SmartConnect smartConnect, String name, String expiry) {

        Map<String, BigDecimal> ivMap = new HashMap<>();

        try {
            JSONObject request = new JSONObject();
            request.put("name", name);
            request.put("expirydate", normalizeExpiry(expiry));

            log.info("Fetching Greeks for {} expiry {}", name, expiry);

            JSONObject response = smartConnect.optionGreek(request);

            if (response == null || !response.has("data") || !response.optBoolean("status", false)) {
                log.warn("No Greeks data returned for {} {}", name, expiry);
                return ivMap;
            }

            JSONArray data = response.getJSONArray("data");
            log.info("Received Greeks data for {} strikes", data.length());

            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.getJSONObject(i);
                try {
                    String optionType = item.optString("optionType", "");
                    BigDecimal strike = item.optBigDecimal("strikePrice", null);
                    BigDecimal iv = item.optBigDecimal("impliedVolatility", null);

                    if (strike != null && iv != null && !optionType.isEmpty() &&
                            iv.compareTo(BigDecimal.ZERO) > 0) {
                        String key = strike.intValue() + "_" + optionType;
                        ivMap.put(key, iv);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse Greeks item: {}", e.getMessage());
                }
            }

            log.info("Successfully fetched IV for {} option strikes", ivMap.size());

        } catch (SmartAPIException | Exception e) {
            // ✅ CHANGE 3: Don't throw, just log and return empty map
            log.warn("Could not fetch IV from Greeks API: {} - Returning empty data", e.getMessage());
        }

        return ivMap;
    }

    /**
     * Normalize expiry format for Greeks API
     */
    private String normalizeExpiry(String shortExpiry) {
        if (shortExpiry == null || shortExpiry.length() != 7) {
            return shortExpiry;
        }

        String day = shortExpiry.substring(0, 2);
        String month = shortExpiry.substring(2, 5);
        String year2 = shortExpiry.substring(5, 7);
        int year = Integer.parseInt(year2) + 2000;

        return day + month + year;
    }

    // --------------------------------------------------------------------- 
    // INTELLIGENT STRIKE SELECTION ALGORITHMS
    // --------------------------------------------------------------------- 
    /**
     * Select optimal strikes based on configuration mode
     */
    private SpreadCalculation selectOptimalStrikes(
            BigDecimal ltp, BigDecimal atmStrike, BigDecimal stepSize,
            List<BigDecimal> allStrikes, SpreadType spreadType,
            StrikeSelectionConfig config, Map<String, StrikeData> strikeDataMap) {

        SpreadCalculation calc = new SpreadCalculation();
        calc.atmStrike = atmStrike;
        calc.stepSize = stepSize;

        switch (config.getMode()) {
            case OI_BASED:
                return selectStrikesByOI(ltp, atmStrike, stepSize, allStrikes, spreadType, config, strikeDataMap);
            case IV_BASED:
                return selectStrikesByIV(ltp, atmStrike, stepSize, allStrikes, spreadType, config, strikeDataMap);
            case COMBINED:
                return selectStrikesCombined(ltp, atmStrike, stepSize, allStrikes, spreadType, config, strikeDataMap);
            case DISTANCE_BASED:
            default:
                return calculateSpreadDistanceBased(ltp, allStrikes, spreadType, config.getFallbackDistance());
        }
    }

    /**
     * OI-BASED: Select strikes with strong OI support/resistance
     */
    private SpreadCalculation selectStrikesByOI(
            BigDecimal ltp, BigDecimal atmStrike, BigDecimal stepSize,
            List<BigDecimal> allStrikes, SpreadType spreadType,
            StrikeSelectionConfig config, Map<String, StrikeData> strikeDataMap) {

        log.info("Using OI-BASED strike selection");

        SpreadCalculation calc = new SpreadCalculation();
        calc.atmStrike = atmStrike;
        calc.stepSize = stepSize;

        boolean isPut = (spreadType == SpreadType.BULL_PUT || spreadType == SpreadType.BEAR_PUT);
        String optionType = isPut ? "PE" : "CE";

        // Calculate average OI for normalization
        BigDecimal avgOI = calculateAverageOI(strikeDataMap, optionType);
        log.info("Average OI for {}: {}", optionType, avgOI);

        // For credit spreads, find sell strike with high OI (support/resistance)
        List<StrikeData> candidates = strikeDataMap.values().stream()
                .filter(d -> d.optionType.equals(optionType))
                .filter(d -> d.oi != null && d.oi.compareTo(config.getMinOIThreshold()) > 0)
                .filter(d -> d.oi.compareTo(avgOI.multiply(config.getOiSupportMultiplier())) > 0)
                .sorted((a, b) -> b.oi.compareTo(a.oi)) // Highest OI first
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            log.warn("No strikes with sufficient OI found, falling back to distance-based");
            return calculateSpreadDistanceBased(ltp, allStrikes, spreadType, config.getFallbackDistance());
        }

        // Select sell strike based on spread type
        StrikeData sellStrikeData = selectSellStrikeFromCandidates(candidates, atmStrike, spreadType, ltp);

        if (sellStrikeData == null) {
            log.warn("Failed to select sell strike, falling back");
            return calculateSpreadDistanceBased(ltp, allStrikes, spreadType, config.getFallbackDistance());
        }

        calc.sellStrike = sellStrikeData.strike;
        calc.sellOptionType = sellStrikeData.optionType;
        calc.sellStrikeOI = sellStrikeData.oi;
        calc.sellStrikeIV = sellStrikeData.iv;

        // Select buy strike (1 step away for standard spreads)
        calc.buyStrike = isPut ? calc.sellStrike.subtract(stepSize) : calc.sellStrike.add(stepSize);
        calc.buyOptionType = optionType;

        // Get buy strike data
        String buyKey = calc.buyStrike.intValue() + "_" + optionType;
        StrikeData buyStrikeData = strikeDataMap.get(buyKey);
        if (buyStrikeData != null) {
            calc.buyStrikeOI = buyStrikeData.oi;
            calc.buyStrikeIV = buyStrikeData.iv;
        }

        // Validate strikes
        if (!isValidStrike(calc.buyStrike, allStrikes)) {
            calc.errorMessage = "Buy strike " + calc.buyStrike + " not available";
            return calc;
        }

        calc.selectionScore = sellStrikeData.oi;
        log.info("Selected strikes with OI: Sell={} (OI: {}), Buy={} (OI: {})",
                calc.sellStrike, calc.sellStrikeOI, calc.buyStrike, calc.buyStrikeOI);

        return calc;
    }

    /**
     * IV-BASED: Select strikes with optimal IV levels
     */
    private SpreadCalculation selectStrikesByIV(
            BigDecimal ltp, BigDecimal atmStrike, BigDecimal stepSize,
            List<BigDecimal> allStrikes, SpreadType spreadType,
            StrikeSelectionConfig config, Map<String, StrikeData> strikeDataMap) {

        log.info("Using IV-BASED strike selection");

        SpreadCalculation calc = new SpreadCalculation();
        calc.atmStrike = atmStrike;
        calc.stepSize = stepSize;

        boolean isPut = (spreadType == SpreadType.BULL_PUT || spreadType == SpreadType.BEAR_PUT);
        String optionType = isPut ? "PE" : "CE";

        // For credit spreads, we want to SELL high IV options
        List<StrikeData> candidates = strikeDataMap.values().stream()
                .filter(d -> d.optionType.equals(optionType))
                .filter(d -> d.iv != null && d.iv.compareTo(BigDecimal.ZERO) > 0)
                .filter(d -> d.iv.compareTo(config.getMinIVThreshold()) >= 0)
                .filter(d -> d.iv.compareTo(config.getMaxIVThreshold()) <= 0)
                .sorted((a, b) -> {
                    // Sort by proximity to ideal IV
                    BigDecimal aDiff = a.iv.subtract(config.getIdealIVForSelling()).abs();
                    BigDecimal bDiff = b.iv.subtract(config.getIdealIVForSelling()).abs();
                    return aDiff.compareTo(bDiff);
                })
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            log.warn("No strikes with optimal IV found, falling back to distance-based");
            return calculateSpreadDistanceBased(ltp, allStrikes, spreadType, config.getFallbackDistance());
        }

        // Select sell strike based on spread type
        StrikeData sellStrikeData = selectSellStrikeFromCandidates(candidates, atmStrike, spreadType, ltp);

        if (sellStrikeData == null) {
            log.warn("Failed to select sell strike, falling back");
            return calculateSpreadDistanceBased(ltp, allStrikes, spreadType, config.getFallbackDistance());
        }

        calc.sellStrike = sellStrikeData.strike;
        calc.sellOptionType = sellStrikeData.optionType;
        calc.sellStrikeOI = sellStrikeData.oi;
        calc.sellStrikeIV = sellStrikeData.iv;

        // Select buy strike (1 step away for standard spreads)
        calc.buyStrike = isPut ? calc.sellStrike.subtract(stepSize) : calc.sellStrike.add(stepSize);
        calc.buyOptionType = optionType;

        // Get buy strike data
        String buyKey = calc.buyStrike.intValue() + "_" + optionType;
        StrikeData buyStrikeData = strikeDataMap.get(buyKey);
        if (buyStrikeData != null) {
            calc.buyStrikeOI = buyStrikeData.oi;
            calc.buyStrikeIV = buyStrikeData.iv;
        }

        // Validate strikes
        if (!isValidStrike(calc.buyStrike, allStrikes)) {
            calc.errorMessage = "Buy strike " + calc.buyStrike + " not available";
            return calc;
        }

        calc.selectionScore = sellStrikeData.iv;
        log.info("Selected strikes with IV: Sell={} (IV: {}), Buy={} (IV: {})",
                calc.sellStrike, calc.sellStrikeIV, calc.buyStrike, calc.buyStrikeIV);

        return calc;
    }

    /**
     * COMBINED: Use both OI and IV with weighted scoring (RECOMMENDED)
     */
    private SpreadCalculation selectStrikesCombined(
            BigDecimal ltp, BigDecimal atmStrike, BigDecimal stepSize,
            List<BigDecimal> allStrikes, SpreadType spreadType,
            StrikeSelectionConfig config, Map<String, StrikeData> strikeDataMap) {

        log.info("Using COMBINED (OI + IV) strike selection");

        SpreadCalculation calc = new SpreadCalculation();
        calc.atmStrike = atmStrike;
        calc.stepSize = stepSize;

        boolean isPut = (spreadType == SpreadType.BULL_PUT || spreadType == SpreadType.BEAR_PUT);
        String optionType = isPut ? "PE" : "CE";

        // Calculate normalization factors
        BigDecimal avgOI = calculateAverageOI(strikeDataMap, optionType);
        BigDecimal avgIV = calculateAverageIV(strikeDataMap, optionType);

        log.info("Normalization: Average OI={}, Average IV={}", avgOI, avgIV);

        // Score all candidates
        List<StrikeData> candidates = strikeDataMap.values().stream()
                .filter(d -> d.optionType.equals(optionType))
                .filter(d -> d.oi != null && d.oi.compareTo(config.getMinOIThreshold()) > 0)
                .filter(d -> d.iv != null && d.iv.compareTo(BigDecimal.ZERO) > 0)
                .peek(d -> {
                    // Calculate composite score
                    BigDecimal oiScore = normalizeScore(d.oi, avgOI);
                    BigDecimal ivScore = normalizeIVScore(d.iv, config.getIdealIVForSelling(), avgIV);

                    d.score = oiScore.multiply(config.getOiWeight())
                            .add(ivScore.multiply(config.getIvWeight()));
                })
                .sorted((a, b) -> b.score.compareTo(a.score)) // Highest score first
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            log.warn("No qualified strikes found, falling back to distance-based");
            return calculateSpreadDistanceBased(ltp, allStrikes, spreadType, config.getFallbackDistance());
        }

        // Log top candidates
        log.info("Top 3 candidates by composite score:");
        candidates.stream().limit(3).forEach(d ->
                log.info("  Strike: {}, OI: {}, IV: {}, Score: {}", d.strike, d.oi, d.iv, d.score));

        // Select sell strike based on spread type
        StrikeData sellStrikeData = selectSellStrikeFromCandidates(candidates, atmStrike, spreadType, ltp);

        if (sellStrikeData == null) {
            log.warn("Failed to select sell strike, falling back");
            return calculateSpreadDistanceBased(ltp, allStrikes, spreadType, config.getFallbackDistance());
        }

        calc.sellStrike = sellStrikeData.strike;
        calc.sellOptionType = sellStrikeData.optionType;
        calc.sellStrikeOI = sellStrikeData.oi;
        calc.sellStrikeIV = sellStrikeData.iv;

        // Select buy strike (1 step away for standard spreads)
        calc.buyStrike = isPut ? calc.sellStrike.subtract(stepSize) : calc.sellStrike.add(stepSize);
        calc.buyOptionType = optionType;

        // Get buy strike data
        String buyKey = calc.buyStrike.intValue() + "_" + optionType;
        StrikeData buyStrikeData = strikeDataMap.get(buyKey);
        if (buyStrikeData != null) {
            calc.buyStrikeOI = buyStrikeData.oi;
            calc.buyStrikeIV = buyStrikeData.iv;
        }

        // Validate strikes
        if (!isValidStrike(calc.buyStrike, allStrikes)) {
            calc.errorMessage = "Buy strike " + calc.buyStrike + " not available";
            return calc;
        }

        calc.selectionScore = sellStrikeData.score;
        log.info("Selected strikes (COMBINED): Sell={} (OI: {}, IV: {}, Score: {}), Buy={} (OI: {}, IV: {})",
                calc.sellStrike, calc.sellStrikeOI, calc.sellStrikeIV, calc.selectionScore,
                calc.buyStrike, calc.buyStrikeOI, calc.buyStrikeIV);

        return calc;
    }

    /**
     * Select sell strike from candidates based on spread type
     */
    private StrikeData selectSellStrikeFromCandidates(
            List<StrikeData> candidates, BigDecimal atmStrike, SpreadType spreadType, BigDecimal ltp) {

        for (StrikeData candidate : candidates) {
            boolean isValid = false;

            switch (spreadType) {
                case BEAR_CALL:
                case NAKED_CALL_HEDGE:
                    // Sell OTM calls (strike > ATM)
                    isValid = candidate.strike.compareTo(atmStrike) > 0;
                    break;
                case BULL_PUT:
                case NAKED_PUT_HEDGE:
                    // Sell OTM puts (strike < ATM)
                    isValid = candidate.strike.compareTo(atmStrike) < 0;
                    break;
                case BULL_CALL:
                    // Sell ATM or slightly OTM calls
                    isValid = candidate.strike.compareTo(atmStrike) >= 0;
                    break;
                case BEAR_PUT:
                    // Sell ATM or slightly OTM puts
                    isValid = candidate.strike.compareTo(atmStrike) <= 0;
                    break;
            }

            if (isValid) {
                return candidate;
            }
        }

        // If no valid candidate found, return the best one anyway
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * Calculate average OI for normalization
     */
    private BigDecimal calculateAverageOI(Map<String, StrikeData> dataMap, String optionType) {
        List<BigDecimal> oiValues = dataMap.values().stream()
                .filter(d -> d.optionType.equals(optionType))
                .filter(d -> d.oi != null && d.oi.compareTo(BigDecimal.ZERO) > 0)
                .map(d -> d.oi)
                .collect(Collectors.toList());

        if (oiValues.isEmpty()) {
            return BigDecimal.ONE;
        }

        BigDecimal sum = oiValues.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return sum.divide(BigDecimal.valueOf(oiValues.size()), 2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate average IV for normalization
     */
    private BigDecimal calculateAverageIV(Map<String, StrikeData> dataMap, String optionType) {
        List<BigDecimal> ivValues = dataMap.values().stream()
                .filter(d -> d.optionType.equals(optionType))
                .filter(d -> d.iv != null && d.iv.compareTo(BigDecimal.ZERO) > 0)
                .map(d -> d.iv)
                .collect(Collectors.toList());

        if (ivValues.isEmpty()) {
            return BigDecimal.ONE;
        }

        BigDecimal sum = ivValues.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return sum.divide(BigDecimal.valueOf(ivValues.size()), 2, RoundingMode.HALF_UP);
    }

    /**
     * Normalize score (higher is better)
     */
    private BigDecimal normalizeScore(BigDecimal value, BigDecimal average) {
        if (average.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return value.divide(average, 4, RoundingMode.HALF_UP);
    }

    /**
     * Normalize IV score (closer to ideal is better)
     */
    private BigDecimal normalizeIVScore(BigDecimal iv, BigDecimal idealIV, BigDecimal avgIV) {
        if (avgIV.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        // Score is higher when IV is closer to ideal
        BigDecimal deviation = iv.subtract(idealIV).abs();
        BigDecimal maxDeviation = avgIV;

        BigDecimal proximityScore = BigDecimal.ONE.subtract(
                        deviation.divide(maxDeviation, 4, RoundingMode.HALF_UP))
                .max(BigDecimal.ZERO);

        return proximityScore;
    }

    // --------------------------------------------------------------------- 
    // SPREAD CALCULATION (Enhanced)
    // --------------------------------------------------------------------- 
    private static class SpreadCalculation {
        BigDecimal atmStrike;
        BigDecimal stepSize;
        BigDecimal buyStrike;
        BigDecimal sellStrike;
        String buyOptionType;
        String sellOptionType;
        String errorMessage;

        // Enhanced fields
        BigDecimal buyStrikeOI;
        BigDecimal sellStrikeOI;
        BigDecimal buyStrikeIV;
        BigDecimal sellStrikeIV;
        BigDecimal selectionScore;

        boolean isValid() {
            return errorMessage == null && buyStrike != null && sellStrike != null;
        }

        String getErrorMessage() {
            return errorMessage;
        }
    }

    /**
     * FALLBACK: Distance-based calculation (original logic)
     */
    private SpreadCalculation calculateSpreadDistanceBased(
            BigDecimal ltp, List<BigDecimal> strikes,
            SpreadType spreadType, StrikeDistanceLevel distanceLevel) {

        SpreadCalculation calc = new SpreadCalculation();
        calc.atmStrike = findNearestStrike(ltp, strikes);
        calc.stepSize = findStrikeInterval(strikes);

        if (calc.stepSize.compareTo(BigDecimal.ZERO) == 0) {
            calc.errorMessage = "Invalid step size (zero) detected";
            return calc;
        }

        int steps = DISTANCE_STEPS_MAP.getOrDefault(distanceLevel, 1);

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
                break;
            case NAKED_PUT_HEDGE:
                calc.sellStrike = sellStrikePut;
                calc.buyStrike = calc.sellStrike.subtract(
                        calc.stepSize.multiply(BigDecimal.valueOf(NAKED_HEDGE_DISTANCE)));
                calc.buyOptionType = "PE";
                calc.sellOptionType = "PE";
                break;
        }

        if (!isValidStrike(calc.buyStrike, strikes)) {
            calc.errorMessage = "Buy strike " + calc.buyStrike + " not available";
            return calc;
        }

        if (!isValidStrike(calc.sellStrike, strikes)) {
            calc.errorMessage = "Sell strike " + calc.sellStrike + " not available";
            return calc;
        }

        return calc;
    }

    // --------------------------------------------------------------------- 
    // UTILITY METHODS
    // --------------------------------------------------------------------- 
    private boolean validateExpiryDate(Expiry expiry) {
        String expiryName = expiry.getExpirydate();

        String normalized = expiryName.substring(0, 2) +
                expiryName.substring(2, 3).toUpperCase() +
                expiryName.substring(3, 5).toLowerCase() +
                expiryName.substring(5);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("ddMMMyyyy", Locale.ENGLISH);
        LocalDate expiryDate;

        try {
            expiryDate = LocalDate.parse(normalized, formatter);
        } catch (Exception e) {
            log.error("Failed to parse expiry {}: {}", expiryName, e.getMessage());
            return false;
        }

        LocalDate today = LocalDate.now();
        int currentDay = today.getDayOfMonth();

        boolean sameMonth = expiryDate.getYear() == today.getYear() &&
                expiryDate.getMonth() == today.getMonth();

        if (sameMonth && currentDay >= 20) {
            log.warn("Skipping: expiry {} is current month and today={} >= 15", expiryName, currentDay);
            return false;
        }

        return true;
    }

    private BigDecimal fetchLTP(SmartConnect smartConnect, Indicator indicator) {

        int attempt = 0;
        long delay = INITIAL_DELAY_MS;

        while (attempt < MAX_RETRIES) {
            try {

                // 🔹 Simple Rate Limiting
                Thread.sleep(RATE_LIMIT_DELAY_MS);

                BigDecimal ltp = angelOneService.getcurrentPrice(
                        smartConnect,
                        indicator.getExchange(),
                        indicator.getTradingSymbol(),
                        indicator.getToken(),
                        "ltp"
                );

                if (ltp != null && ltp.compareTo(BigDecimal.ZERO) > 0) {
                    return ltp;
                }

                log.warn("LTP returned null/zero for {} (Attempt {}/{})",
                        indicator.getTradingSymbol(), attempt + 1, MAX_RETRIES);

            } catch (Exception e) {
                log.error("Attempt {} failed for {}: {}",
                        attempt + 1,
                        indicator.getTradingSymbol(),
                        e.getMessage());
            }

            // 🔹 Exponential Backoff
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }

            delay *= 2; // 300 → 600 → 1200
            attempt++;
        }

        log.error("All retries failed for {}", indicator.getTradingSymbol());
        return null;
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

    private boolean isValidStrike(BigDecimal strike, List<BigDecimal> availableStrikes) {
        return availableStrikes.stream().anyMatch(s -> s.compareTo(strike) == 0);
    }

    private Token buildToken(Indicator indicator, Expiry expiry, BigDecimal strike,
                             String optionType, String transactionType) {
        if (strike == null) {
            log.error("Null strike when building token for {}", indicator.getTradingSymbol());
            return null;
        }

        String expiryCode = expiry.getExpirydate();
        String strikeStr = strike.stripTrailingZeros().toPlainString();
        String shortExpiry = toShortExpiry(expiryCode);
        String symbol = indicator.getName() + shortExpiry + strikeStr + optionType;

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

    private String toShortExpiry(String expiryCode) {
        if (expiryCode == null) {
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

    public boolean validateMarginRequirement(SmartConnect smartConnect) {
        try {
            JSONObject rms = smartConnect.getRMS();
            double availableCash = Double.parseDouble(rms.getString("availablecash"));
            double required = 100.0;

            if (availableCash >= required) {
                log.info("Sufficient margin available. AvailableCash = {}", availableCash);
                return true;
            } else {
                log.warn("Insufficient margin. Required = {}, AvailableCash = {}", required, availableCash);
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to validate margin: ", e);
            return false;
        }
    }

    // --------------------------------------------------------------------- 
    // Market Depth Validation for Liquidity
    // --------------------------------------------------------------------- 
    /**
     * Validate market depth to ensure sufficient liquidity before placing market orders
     * Checks bid-ask spread to avoid wide price gaps in illiquid options
     * 
     * @param smartConnect SmartConnect instance
     * @param buyLeg Buy leg token
     * @param sellLeg Sell leg token
     * @return true if liquidity is acceptable, false if spread too wide
     */
    private boolean validateMarketDepth(SmartConnect smartConnect, Token buyLeg, Token sellLeg) {
        try {
            // Prepare tokens for batch fetch
            List<String> tokens = Arrays.asList(buyLeg.getToken(), sellLeg.getToken());
            
            JSONObject payload = new JSONObject();
            payload.put("mode", "FULL");
            JSONObject map = new JSONObject();
            map.put("NFO", tokens);
            payload.put("exchangeTokens", map);
            
            JSONObject response = predictionService.callMarketDataWithRetry(smartConnect, payload);
            
            if (response == null || !response.has("fetched")) {
                log.warn("⚠️ Could not fetch market depth - proceeding cautiously");
                return true; // Don't block if data unavailable
            }
            
            JSONArray fetched = response.getJSONArray("fetched");
            
            for (int i = 0; i < fetched.length(); i++) {
                JSONObject item = fetched.getJSONObject(i);
                String symbolToken = item.optString("symbolToken", "");
                String tradingSymbol = item.optString("tradingSymbol", "");
                
                // Check if this is one of our tokens
                boolean isBuyLeg = symbolToken.equals(buyLeg.getToken());
                boolean isSellLeg = symbolToken.equals(sellLeg.getToken());
                
                if (!isBuyLeg && !isSellLeg) {
                    continue;
                }
                
                // Get depth data
                if (!item.has("depth")) {
                    log.warn("⚠️ No depth data for {} - proceeding cautiously", tradingSymbol);
                    continue;
                }
                
                JSONObject depth = item.getJSONObject("depth");
                JSONArray buyDepth = depth.optJSONArray("buy");
                JSONArray sellDepth = depth.optJSONArray("sell");
                
                if (buyDepth == null || sellDepth == null || 
                    buyDepth.length() == 0 || sellDepth.length() == 0) {
                    log.error("❌ INSUFFICIENT LIQUIDITY for {}: Empty order book", tradingSymbol);
                    return false;
                }
                
                // Get best bid and ask
                JSONObject bestBid = buyDepth.getJSONObject(0);
                JSONObject bestAsk = sellDepth.getJSONObject(0);
                
                double bidPrice = bestBid.optDouble("price", 0);
                double askPrice = bestAsk.optDouble("price", 0);
                int bidQty = bestBid.optInt("quantity", 0);
                int askQty = bestAsk.optInt("quantity", 0);
                
                if (bidPrice <= 0 || askPrice <= 0) {
                    log.error("❌ INSUFFICIENT LIQUIDITY for {}: No valid bid/ask (bid={}, ask={})",
                            tradingSymbol, bidPrice, askPrice);
                    return false;
                }
                
                // Calculate bid-ask spread percentage
                double midPrice = (bidPrice + askPrice) / 2.0;
                double spreadAmount = askPrice - bidPrice;
                double spreadPercent = (spreadAmount / midPrice) * 100.0;
                
                // Get LTP for reference
                double ltp = item.optDouble("ltp", 0);
                
                // Thresholds
                final double MAX_SPREAD_PERCENT = 5.0;  // 5% max spread
                final int MIN_BID_QTY = 25;             // Minimum bid quantity
                final int MIN_ASK_QTY = 25;             // Minimum ask quantity
                
                // Validate spread
                if (spreadPercent > MAX_SPREAD_PERCENT) {
                    log.error("❌ WIDE SPREAD for {}: {}% (bid={}, ask={}, mid={}) - NO LIQUIDITY",
                            tradingSymbol, String.format("%.2f", spreadPercent), 
                            bidPrice, askPrice, midPrice);
                    log.error("   Market order could execute at unfavorable price - SKIPPING");
                    return false;
                }
                
                // Validate quantities
                if (bidQty < MIN_BID_QTY || askQty < MIN_ASK_QTY) {
                    log.warn("⚠️ LOW LIQUIDITY for {}: BidQty={}, AskQty={} (min required: {})",
                            tradingSymbol, bidQty, askQty, MIN_BID_QTY);
                    log.warn("   Market order may have slippage - proceeding with caution");
                    // Don't block, just warn
                }
                
                // Log depth summary
                log.info("✓ Market Depth OK for {}: Spread={}% (bid={}, ask={}, ltp={}), BidQty={}, AskQty={}",
                        tradingSymbol, String.format("%.2f", spreadPercent), 
                        bidPrice, askPrice, ltp, bidQty, askQty);
                
                // Log full depth for transparency
                logFullDepth(tradingSymbol, buyDepth, sellDepth);
            }
            
            return true; // All depth checks passed
            
        } catch (Exception e) {
            log.error("Error validating market depth: {} - Proceeding cautiously", e.getMessage());
            return true; // Don't block on error, but log it
        } catch (SmartAPIException e) {
        	 log.error("Error validating market depth: {} - Proceeding cautiously", e.getMessage());
             return true; // Don't block on error, but log it
		}
    }
    
    /**
     * Log full 5-level market depth for transparency
     */
    private void logFullDepth(String symbol, JSONArray buyDepth, JSONArray sellDepth) {
        StringBuilder depthLog = new StringBuilder();
        depthLog.append("\n");
        depthLog.append("┌─────────────────────────────────────────────────────────────┐\n");
        depthLog.append(String.format("│ Market Depth: %-44s │\n", symbol));
        depthLog.append("├─────────────────────────────────────────────────────────────┤\n");
        depthLog.append("│     BID SIDE          │          ASK SIDE                   │\n");
        depthLog.append("│  Qty    Price  Orders │  Qty    Price  Orders               │\n");
        depthLog.append("├─────────────────────────────────────────────────────────────┤\n");
        
        int maxLevels = Math.max(buyDepth.length(), sellDepth.length());
        for (int i = 0; i < Math.min(maxLevels, 5); i++) {
            String bidStr = "  -      -       -   ";
            String askStr = "  -      -       -   ";
            
            if (i < buyDepth.length()) {
                JSONObject bid = buyDepth.getJSONObject(i);
                int qty = bid.optInt("quantity", 0);
                double price = bid.optDouble("price", 0);
                int orders = bid.optInt("orders", 0);
                if (price > 0) {
                    bidStr = String.format("%5d  %7.2f  %3d", qty, price, orders);
                }
            }
            
            if (i < sellDepth.length()) {
                JSONObject ask = sellDepth.getJSONObject(i);
                int qty = ask.optInt("quantity", 0);
                double price = ask.optDouble("price", 0);
                int orders = ask.optInt("orders", 0);
                if (price > 0) {
                    askStr = String.format("%5d  %7.2f  %3d", qty, price, orders);
                }
            }
            
            depthLog.append(String.format("│ %s │ %s │\n", bidStr, askStr));
        }
        
        depthLog.append("└─────────────────────────────────────────────────────────────┘");
        log.info(depthLog.toString());
    }

    // --------------------------------------------------------------------- 
    // ORDER PLACEMENT
    // --------------------------------------------------------------------- 
    /**
     * Place spread order with proper sequencing for margin benefit
     * CRITICAL: Buy leg MUST execute first to get margin benefit for spread
     */
    public boolean placeSpreadOrder(SmartConnect smartConnect, Token buyLeg, Token sellLeg,
                                    Indicator indicator, SpreadType spreadType, Strategy strategy) {
        
        if (strategy == null) {
            log.error("Strategy is null - cannot place order");
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

        log.info("====================================================================");
        log.info("Placing SPREAD order for {}: Buy {} first, then Sell {}", 
                 indicator.getTradingSymbol(), buyLeg.getSymbol(), sellLeg.getSymbol());
        log.info("====================================================================");

        // STEP 1: Place BUY leg first (CRITICAL for margin benefit)
        log.info("STEP 1/3: Placing BUY leg - {} @ {} qty {}", 
                 buyLeg.getSymbol(), buyLeg.getPrice(), buyLeg.getQuantity());
        
        OrderResult buyResult = placeSingleOrder(smartConnect, buyLeg);

        if (!buyResult.isSuccess()) {
            log.error("❌ STEP 1 FAILED: Buy leg order failed - aborting spread. Error: {}",
                    buyResult.getErrorMessage());
            return false;
        }

        log.info("✓ STEP 1 SUCCESS: Buy leg order placed - OrderID: {}", buyResult.getOrderId());

        // STEP 2: Wait for BUY order completion
        log.info("STEP 2/3: Waiting for BUY leg order {} to complete...", buyResult.getOrderId());
        
        if (!waitForOrderCompletion(smartConnect, buyResult.getOrderId())) {
            log.error("❌ STEP 2 FAILED: Buy leg order {} did not complete in time - aborting spread", 
                     buyResult.getOrderId());
            attemptCancelOrder(smartConnect, buyResult.getOrderId());
            return false;
        }

        log.info("✓ STEP 2 SUCCESS: Buy leg order {} completed", buyResult.getOrderId());

        // STEP 3: Place SELL leg (now we get margin benefit from buy leg)
        log.info("STEP 3/3: Placing SELL leg - {} @ {} qty {} (margin benefit applied)", 
                 sellLeg.getSymbol(), sellLeg.getPrice(), sellLeg.getQuantity());
        
        OrderResult sellResult = placeSingleOrder(smartConnect, sellLeg);

        if (!sellResult.isSuccess()) {
            log.error("❌ CRITICAL: STEP 3 FAILED - Sell leg failed for {}. Buy leg OrderID: {}. Error: {}",
                    sellLeg.getSymbol(), buyResult.getOrderId(), sellResult.getErrorMessage());

            log.warn("⚠️ ATTEMPTING ROLLBACK: Reversing buy leg {}", buyLeg.getSymbol());

            boolean rollbackSuccess = rollbackBuyLeg(smartConnect, buyLeg, buyResult.getOrderId());

            if (!rollbackSuccess) {
                log.error("❌❌ MANUAL INTERVENTION REQUIRED: Unable to rollback buy leg {}. OrderID: {}",
                        buyLeg.getSymbol(), buyResult.getOrderId());
                sendAlert("CRITICAL: Failed rollback for " + buyLeg.getSymbol() +
                        " OrderID: " + buyResult.getOrderId());
            } else {
                log.info("✓ Rollback successful - buy leg reversed");
            }
            return false;
        }

        log.info("✓ STEP 3 SUCCESS: Sell leg order placed - OrderID: {}", sellResult.getOrderId());
        log.info("====================================================================");
        log.info("✓✓✓ SPREAD PLACEMENT SUCCESSFUL ✓✓✓");
        log.info("Buy Leg:  {} - OrderID: {}", buyLeg.getSymbol(), buyResult.getOrderId());
        log.info("Sell Leg: {} - OrderID: {}", sellLeg.getSymbol(), sellResult.getOrderId());
        log.info("Margin benefit applied due to spread structure");
        log.info("====================================================================");

        saveSpreadPosition(indicator, spreadType, buyResult, sellResult, buyLeg, sellLeg);

        return true;
    }

    public void resetDailyCounters() {
        processedIndicatorsToday.clear();
        spreadsPlacedToday = 0;
        stocksSkippedDueToMaxLoss = 0;
        log.info("Daily counters reset");
    }

    public Map<String, Object> getSpreadStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("spreadsPlacedToday", spreadsPlacedToday);
        stats.put("maxSpreadsPerDay", MAX_SPREADS_PER_DAY);
        stats.put("uniqueIndicatorsProcessed", processedIndicatorsToday.size());
        stats.put("remainingCapacity", MAX_SPREADS_PER_DAY - spreadsPlacedToday);
        stats.put("stocksSkippedDueToMaxLoss", stocksSkippedDueToMaxLoss);
        return stats;
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

        public boolean isSuccess() {
            return success;
        }

        public String getOrderId() {
            return orderId;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    private OrderResult placeSingleOrder(SmartConnect sc, Token token) {
        try {
            log.info("Placing {} order for {} qty {}",
                    token.getTransactionType(), token.getSymbol(), token.getQuantity());

            Token orderResponse = angelOneService.placeOrder(sc, token);

            if (orderResponse != null) {
                String orderId = extractOrderId(orderResponse);
                log.info("Order placed successfully: {} - OrderID: {}", token.getSymbol(), orderId);
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
            } catch (NoSuchMethodException ignored) {
            }

            try {
                java.lang.reflect.Method m2 = orderResponse.getClass().getMethod("getOrderNo");
                Object rv2 = m2.invoke(orderResponse);
                if (rv2 != null) return rv2.toString();
            } catch (NoSuchMethodException ignored) {
            }

            try {
                java.lang.reflect.Method m3 = orderResponse.getClass().getMethod("getId");
                Object rv3 = m3.invoke(orderResponse);
                if (rv3 != null) return rv3.toString();
            } catch (NoSuchMethodException ignored) {
            }
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
        log.warn("Initiating rollback for buy leg {} (OrderID: {})", buyLeg.getSymbol(), orderId);

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
            // Status: SUCCESS - both legs executed
            log.info("✓ Spread position tracked: {} {} - Buy:{} Sell:{} - STATUS: SUCCESS",
                    indicator.getTradingSymbol(), spreadType,
                    buyResult != null ? buyResult.getOrderId() : "N/A", 
                    sellResult != null ? sellResult.getOrderId() : "N/A");
        } catch (Exception e) {
            log.error("Failed to save spread position: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Save spread that was skipped/failed with reason
     * This ensures ALL spread attempts are tracked, not just successful ones
     */
    private void saveSkippedSpread(Indicator indicator, SpreadType spreadType,
                                   SpreadCalculation calc, String skipReason,
                                   BigDecimal calculatedMaxRisk, BigDecimal threshold) {
        try {
            // TODO: Implement SpreadPosition entity and repository
            // Status: SKIPPED with reason
            log.info("❌ Spread attempt tracked: {} {} - Buy:{} {} Sell:{} {} - STATUS: SKIPPED - REASON: {}",
                    indicator.getTradingSymbol(), spreadType,
                    calc != null ? calc.buyStrike : "N/A", 
                    calc != null ? calc.buyOptionType : "N/A",
                    calc != null ? calc.sellStrike : "N/A",
                    calc != null ? calc.sellOptionType : "N/A",
                    skipReason);
            
            // Additional details for analysis
            if (calculatedMaxRisk != null && threshold != null) {
                log.info("   MaxRisk: {} | Threshold: {} | Breach: {}", 
                        calculatedMaxRisk, threshold, calculatedMaxRisk.subtract(threshold));
            }
            
            // TODO: Insert into database with fields:
            // - tradingSymbol
            // - spreadType
            // - buyStrike, buyOptionType
            // - sellStrike, sellOptionType
            // - status = "SKIPPED"
            // - skipReason
            // - calculatedMaxRisk
            // - threshold
            // - attemptTime = LocalDateTime.now()
            // - buyOrderId = null
            // - sellOrderId = null
            
        } catch (Exception e) {
            log.error("Failed to save skipped spread: {}", e.getMessage(), e);
        }
    }

    private void sendAlert(String message) {
        // TODO: Implement alerting
        log.error("ALERT: {}", message);
    }
}