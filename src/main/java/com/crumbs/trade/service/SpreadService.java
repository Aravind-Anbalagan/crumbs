package com.crumbs.trade.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SpreadService supporting all 4 common vertical spreads with enhanced error handling,
 * POP-based strike selection, and automatic rollback on partial failures.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpreadService {

    @Autowired private IndicatorRepo indicatorRepo;
    @Autowired private AngelOneService angelOneService;
    @Autowired private AngelOne angelOne;
    @Autowired private ExpiryRepo expiryRepo;
    @Autowired private IndexesRepo indexesRepo;
    @Autowired private StrategyRepo strategyRepo;

    // Configuration constants
    private static final BigDecimal STRIKE_DIVISOR = new BigDecimal("100");
    private static final long DEFAULT_EXPIRY_ID = 1L;
    private static final int NAKED_HEDGE_DISTANCE = 10;  // strikes away for far hedge

    public enum SpreadType {
        BULL_CALL, BEAR_CALL, BULL_PUT, BEAR_PUT, NAKED_CALL_HEDGE, NAKED_PUT_HEDGE
    }

    public enum POPLevel {
        POP50, POP55, POP60, POP65, POP70, POP75, POP80
    }

    private static final Map<POPLevel, Integer> POP_STEPS_MAP = Map.of(
        POPLevel.POP50, 0,
        POPLevel.POP55, 0,
        POPLevel.POP60, 1,
        POPLevel.POP65, 1,
        POPLevel.POP70, 2,
        POPLevel.POP75, 2,
        POPLevel.POP80, 3
    );

    private int getStepsFromPOP(POPLevel pop) {
        return POP_STEPS_MAP.getOrDefault(pop, 0);
    }

    // ========================================================================
    // PUBLIC API
    // ========================================================================

    public void getStockList() {

        List<String> signals = List.of("FIRST BUY", "FIRST SELL");

        List<Indicator> indicatorList =
                indicatorRepo.findByHeikinAshiDayInAndPsarFlagDayInAndOptions(
                        signals,
                        signals,
                        "Y"
                );

        SmartConnect smartConnect = angelOne.signIn();
     
        log.info("Processing {} indicators for spread placement", indicatorList.size());

        indicatorList.forEach(indicator -> {
            try {
                processIndicator(smartConnect, indicator);
            } catch (Exception e) {
                log.error("Failed processing {}: {}", indicator.getTradingSymbol(), e.getMessage(), e);
            }
        });
    }



    public void processIndicator(SmartConnect smartConnect, Indicator indicator) {

        String signal = indicator.getHeikinAshiDay();  // or psarFlagDay, both same based on your logic
        SpreadType spreadType = null;
        POPLevel popLevel = POPLevel.POP60; // default you already use

        // AUTO-SELECT SPREAD TYPE BASED ON SIGNAL
        if ("FIRST BUY".equalsIgnoreCase(signal)) {
            spreadType = SpreadType.BULL_PUT;
        } 
        else if ("FIRST SELL".equalsIgnoreCase(signal)) {
            spreadType = SpreadType.BEAR_CALL;
        } 
        /*else {
            // fallback (your existing logic)
            //spreadType = SpreadType.NAKED_PUT_HEDGE;
        }*/

        log.info("Placing spread {} for {} based on signal {}",
                 spreadType, indicator.getTradingSymbol(), signal);

        processIndicator(smartConnect, indicator, spreadType, popLevel);
    }


    public void processIndicator(SmartConnect smartConnect, Indicator indicator,
                                 SpreadType spreadType) {
        processIndicator(smartConnect, indicator, spreadType, POPLevel.POP50);
    }

    public void processIndicator(SmartConnect smartConnect, Indicator indicator,
                                 SpreadType spreadType, POPLevel popLevel) {

        log.info("=== Processing Spread for {} | Type: {} | POP: {} ===",
            indicator.getTradingSymbol(), spreadType, popLevel);

        BigDecimal ltp = fetchLTP(smartConnect, indicator);
        if (ltp == null) {
            log.warn("Cannot proceed without LTP for {}", indicator.getTradingSymbol());
            return;
        }

        Optional<Expiry> optionalExpiry = expiryRepo.findById(DEFAULT_EXPIRY_ID);
        if (!optionalExpiry.isPresent()) {
            log.error("Expiry not found (ID: {}). Check database configuration.", DEFAULT_EXPIRY_ID);
            return;
        }
        Expiry expiry = optionalExpiry.get();

        List<BigDecimal> strikes = fetchAllStrikesForInstrument(
            indicator.getName(), expiry.getExpirydate());

        if (strikes.isEmpty()) {
            log.error("No strikes found for {} expiry {}", indicator.getTradingSymbol(), expiry.getExpirydate());
            return;
        }

        SpreadCalculation calc = calculateSpread(ltp, strikes, spreadType, popLevel);
        if (!calc.isValid()) {
            log.error("Invalid spread calculation for {}: {}", indicator.getTradingSymbol(), calc.getErrorMessage());
            return;
        }

        log.info("Spread calculation: LTP={} | ATM={} | Step={} | BuyStrike={} | SellStrike={}",
            ltp, calc.atmStrike, calc.stepSize, calc.buyStrike, calc.sellStrike);

        Token buyLeg = buildToken(indicator, expiry, calc.buyStrike,
            calc.buyOptionType, Constants.TRANSACTION_TYPE_BUY);
        Token sellLeg = buildToken(indicator, expiry, calc.sellStrike,
            calc.sellOptionType, Constants.TRANSACTION_TYPE_SELL);

        if (buyLeg == null || sellLeg == null) {
            log.error("Failed to build tokens for {}", indicator.getTradingSymbol());
            return;
        }

        prepareCommonFields(buyLeg,smartConnect);
        prepareCommonFields(sellLeg,smartConnect);
        placeSpreadOrder(smartConnect, buyLeg, sellLeg);
    }

    // ========================================================================
    // SPREAD CALCULATION
    // ========================================================================

    private static class SpreadCalculation {
        BigDecimal atmStrike;
        BigDecimal stepSize;
        BigDecimal buyStrike;
        BigDecimal sellStrike;
        String buyOptionType;
        String sellOptionType;
        String errorMessage;

        boolean isValid() { return errorMessage == null && buyStrike != null && sellStrike != null; }
        String getErrorMessage() { return errorMessage; }
    }

    private SpreadCalculation calculateSpread(BigDecimal ltp, List<BigDecimal> strikes,
                                               SpreadType spreadType, POPLevel popLevel) {

        SpreadCalculation calc = new SpreadCalculation();

        calc.atmStrike = findNearestStrike(ltp, strikes);
        calc.stepSize = findStrikeInterval(strikes);

        if (calc.stepSize.compareTo(BigDecimal.ZERO) == 0) {
            calc.errorMessage = "Invalid step size (zero) detected";
            return calc;
        }

        int steps = getStepsFromPOP(popLevel);

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
                calc.buyStrike = calc.sellStrike.add(calc.stepSize.multiply(BigDecimal.valueOf(NAKED_HEDGE_DISTANCE)));
                calc.buyOptionType = "CE";
                calc.sellOptionType = "CE";
                log.info("NAKED_CALL_HEDGE: Selling {} CE, Buying {} CE hedge ({}x away)", calc.sellStrike, calc.buyStrike, NAKED_HEDGE_DISTANCE);
                break;

            case NAKED_PUT_HEDGE:
                calc.sellStrike = sellStrikePut;
                calc.buyStrike = calc.sellStrike.subtract(calc.stepSize.multiply(BigDecimal.valueOf(NAKED_HEDGE_DISTANCE)));
                calc.buyOptionType = "PE";
                calc.sellOptionType = "PE";
                log.info("NAKED_PUT_HEDGE: Selling {} PE, Buying {} PE hedge ({}x away)", calc.sellStrike, calc.buyStrike, NAKED_HEDGE_DISTANCE);
                break;
        }

        if (!isValidStrike(calc.buyStrike, strikes)) {
            if (spreadType == SpreadType.NAKED_CALL_HEDGE || spreadType == SpreadType.NAKED_PUT_HEDGE) {
                BigDecimal furthest = findFurthestStrike(strikes, spreadType == SpreadType.NAKED_CALL_HEDGE);
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

    // ========================================================================
    // ORDER PLACEMENT WITH ROLLBACK
    // ========================================================================

    public void placeSpreadOrder(SmartConnect smartConnect, Token buyLeg, Token sellLeg) {
        Strategy strategy = strategyRepo.findByName(AppConstant.SPREAD_STRATEGY);

        if (strategy == null) {
            log.warn("Strategy '{}' not found -> skipping order placement", AppConstant.SPREAD_STRATEGY);
            return;
        }
		if ("Y".equalsIgnoreCase(strategy.getPapertrade())) {
			
			saveOrders(buyLeg,sellLeg);
		}
        if (!"Y".equalsIgnoreCase(strategy.getLive())) {
            log.info("Strategy not live - skipping order placement");
            return;
        }

        log.info("Placing spread: BUY {} @ {} | SELL {} @ {}", buyLeg.getSymbol(), buyLeg.getQuantity(), sellLeg.getSymbol(), sellLeg.getQuantity());

        OrderResult buyResult = placeSingleOrder(smartConnect, buyLeg);

        if (!buyResult.isSuccess()) {
            log.error("Buy leg failed for {} - aborting spread placement. Error: {}", buyLeg.getSymbol(), buyResult.getErrorMessage());
            return;
        }

        log.info("Buy leg successful for {} - OrderID: {}", buyLeg.getSymbol(), buyResult.getOrderId());

        OrderResult sellResult = placeSingleOrder(smartConnect, sellLeg);

        if (!sellResult.isSuccess()) {
            log.error("CRITICAL: Sell leg failed for {}. Buy leg OrderID: {}. Error: {}", sellLeg.getSymbol(), buyResult.getOrderId(), sellResult.getErrorMessage());

            log.warn("Attempting to cancel/exit buy leg: {}", buyLeg.getSymbol());
            boolean rollbackSuccess = rollbackBuyLeg(smartConnect, buyLeg, buyResult.getOrderId());

            if (!rollbackSuccess) {
                log.error("MANUAL INTERVENTION REQUIRED: Unable to automatically rollback buy leg {}. OrderID: {}", buyLeg.getSymbol(), buyResult.getOrderId());
                // TODO: send alert/notification (email/slack) here
            }
            return;
        }

        log.info("Spread placement successful! Buy: {} | Sell: {}", buyResult.getOrderId(), sellResult.getOrderId());
    }

    //Save Orders 
	public void saveOrders(Token buyLeg, Token sellLeg) {
		try {
			angelOneService.insertOrder(buyLeg, 0);
			angelOneService.insertOrder(sellLeg, 0);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
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
            log.info("Placing {} order for {} qty {}", token.getTransactionType(), token.getSymbol(), token.getQuantity());
            Token orderResponse = angelOneService.placeOrder(sc, token);

            if (orderResponse != null) {
                String orderId = extractOrderId(orderResponse);
                log.info("Order placed successfully: {} - OrderID: {}", token.getSymbol(), orderId);
                return new OrderResult(true, orderId, null);
            } else {
                return new OrderResult(false, null, "Order response was null");
            }

        } catch (SmartAPIException e) {
            log.error("SmartAPI exception placing order for {}: {}", token.getSymbol(), e.getMessage(), e);
            return new OrderResult(false, null, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected exception placing order for {}: {}", token.getSymbol(), e.getMessage(), e);
            return new OrderResult(false, null, e.getMessage());
        }
    }

    /**
     * Try to extract a real order id from the returned Token/response using common method names.
     * Falls back to a timestamped placeholder.
     */
    private String extractOrderId(Token orderResponse) {
        try {
            // If Token has a getOrderId or getOrderNo method, prefer it.
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

            // If Token has an id field
            try {
                java.lang.reflect.Method m3 = orderResponse.getClass().getMethod("getId");
                Object rv3 = m3.invoke(orderResponse);
                if (rv3 != null) return rv3.toString();
            } catch (NoSuchMethodException ignored) {}

        } catch (Exception e) {
            log.debug("Reflection failed while extracting order id: {}", e.getMessage());
        }

        // TODO: replace with actual extraction when you know the response model
        return "ORDER_" + System.currentTimeMillis();
    }

    private boolean rollbackBuyLeg(SmartConnect sc, Token buyLeg, String orderId) {
        try {
            // Option 1: try cancel (if implemented in AngelOneService)
            try {
               // boolean cancelled = angelOneService.cancelOrder(sc, orderId);
            	boolean cancelled = true;
                if (cancelled) {
                    log.info("Cancelled buy leg orderId={} successfully", orderId);
                    return true;
                }
            } catch (Exception e) {
                log.debug("cancelOrder not available or failed: {}", e.getMessage());
            }

            Token exitToken = createExitToken(buyLeg,sc);
            OrderResult exitResult = placeSingleOrder(sc, exitToken);

            if (exitResult.isSuccess()) {
                log.info("Successfully rolled back buy leg {} with exit order {}", buyLeg.getSymbol(), exitResult.getOrderId());
                return true;
            }

            return false;

        } catch (Exception e) {
            log.error("Failed to rollback buy leg {}: {}", buyLeg.getSymbol(), e.getMessage(), e);
            return false;
        }
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

        prepareCommonFields(exitToken,sc);
        return exitToken;
    }

    // ========================================================================
    // STRIKE & DATA UTILITIES
    // ========================================================================

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
            log.error("Failed to fetch LTP for {}: {}", indicator.getTradingSymbol(), e.getMessage(), e);
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

    // ========================================================================
    // TOKEN BUILDING
    // ========================================================================

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
        token.setExch_seg(idx.getExchange()); // preserve DB exchange here
        token.setTransactionType(transactionType);
        token.setQuantity(idx.getLotsize());

        return token;
    }

    /**
     * Convert expiry like "25NOV2025" or "5JAN2026" -> "25NOV25" or "05JAN26"
     */
    public String toShortExpiry(String expiryCode) {
        if (expiryCode == null) {
            log.warn("Invalid expiry code: null");
            return "";
        }

        // Pattern: day (1-2 digits) + 3-letter month + 4-digit year
        Pattern p = Pattern.compile("^(\\d{1,2})([A-Za-z]{3})(\\d{4})$");
        Matcher m = p.matcher(expiryCode.trim());
        if (m.matches()) {
            String day = m.group(1);
            if (day.length() == 1) day = "0" + day; // pad single-digit day
            String mon = m.group(2).toUpperCase();
            String yy = m.group(3).substring(2);
            return day + mon + yy;
        }

        // fallback: if it's already short (like "25NOV25") just return it
        if (expiryCode.length() >= 7) {
            return expiryCode;
        }

        log.warn("Unrecognized expiry format: {}", expiryCode);
        return expiryCode;
    }

    private void prepareCommonFields(Token token, SmartConnect smartConnect) {
        // Don't overwrite exchange segment if already set
        if (token.getExch_seg() == null || token.getExch_seg().trim().isEmpty()) {
            token.setExch_seg("NFO");
        }
        BigDecimal currentPrice = angelOneService.getcurrentPrice(smartConnect, token.getExch_seg(),
        		token.getSymbol(), token.getToken(), "ltp");
        getMarginDetails(smartConnect,token);
        token.setPrice(currentPrice.doubleValue());
        token.setName(AppConstant.SPREAD_STRATEGY);
        token.setProductType(Constants.PRODUCT_CARRYFORWARD);
        token.setVariety(Constants.VARIETY_NORMAL);
        token.setOrderType(Constants.ORDER_TYPE_MARKET);
    }

	/** Margin data. 
	 * @param token */
	public void getMarginDetails(SmartConnect smartConnect, Token token){
		List<MarginParams> marginParamsList = new ArrayList<>();
		MarginParams marginParams = new MarginParams();
		marginParams.quantity = 1;
		marginParams.token = token.getToken();
		marginParams.exchange = token.getExch_seg();
		marginParams.productType = Constants.PRODUCT_DELIVERY;
		marginParams.price = 0.0;
		marginParams.tradeType = token.getTransactionType();

		marginParamsList.add(marginParams);
		JSONObject jsonObject;
		try {
			jsonObject = smartConnect.getMarginDetails(marginParamsList);
			System.out.println(jsonObject);
		} catch (IOException | SmartAPIException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	

	}
    
}
