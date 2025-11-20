package com.crumbs.trade.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
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

import groovy.transform.AutoClone;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SpreadService supporting all 4 common vertical spreads.
 *
 * - Normalizes DB strike strings (e.g. "2600000.000000") by dividing by 100 => BigDecimal strike
 * - Uses BigDecimal for interval detection (2.5, 5, 10, etc.)
 * - Builds symbol as: tradingSymbol + expiryCode + strikePlain + optionType
 * - ALWAYS places BUY first, then SELL (checks buy success)
 *
 * Usage:
 *   processIndicator(smartConnect, indicator, SpreadType.BULL_PUT);
 *
 * Default convenience method processIndicator(smartConnect, indicator) uses BULL_PUT.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpreadService {

    @Autowired private IndicatorRepo indicatorRepo;
    @Autowired private AngelOneService angelOneService; // adapt if different signature
    @Autowired private AngelOne angelOne;
    @Autowired private ExpiryRepo expiryRepo;
    @Autowired private IndexesRepo indexesRepo;
    @Autowired private StrategyRepo strategyRepo;

    public enum SpreadType {
        BULL_CALL,
        BEAR_CALL,
        BULL_PUT,
        BEAR_PUT
    }

    /**
     * POP levels (Option A: enum-based). Default will be POP50 (ATM).
     * Mapping of steps (see getStepsFromPOP).
     */
    public enum POPLevel {
        POP50,
        POP55,
        POP60,
        POP65,
        POP70,
        POP75,
        POP80
    }

    private int getStepsFromPOP(POPLevel pop) {
        switch (pop) {
            case POP50: return 0;   // ATM sell
            case POP55: return 0;   // treat as near-ATM
            case POP60: return 1;
            case POP65: return 1;
            case POP70: return 2;
            case POP75: return 2;
            case POP80: return 3;
            default: return 0;
        }
    }

    /**
     * STEP 1: Fetch all stocks eligible for NEW BUY
     */
    public void getStockList() {

        List<Indicator> indicatorList =
                indicatorRepo.findByHeikinAshiDayAndPsarFlagDay("FIRST BUY", "FIRST BUY");

        SmartConnect smartConnect = angelOne.signIn();

        indicatorList.forEach(indicator -> {
            try {
                processIndicator(smartConnect, indicator); // default behaviour (BULL_PUT + POP50)
            } catch (Exception e) {
                log.error("Failed processing {}: {}", indicator.getTradingSymbol(), e.getMessage());
            }
        });
    }
    
    /**
     * Convenience: default strategy = BULL_PUT (as requested).
     * Kept signature unchanged; delegates to new overload with default POP50.
     */
    public void processIndicator(SmartConnect smartConnect, Indicator indicator) {
        processIndicator(smartConnect, indicator, SpreadType.BULL_PUT, POPLevel.POP50);
    }

    /**
     * Kept signature unchanged; delegates to new overload with default POP50.
     */
    public void processIndicator(SmartConnect smartConnect, Indicator indicator, SpreadType spreadType) {
        processIndicator(smartConnect, indicator, spreadType, POPLevel.POP55);
    }

    /**
     * Main entry: process indicator with explicit spread type and POP level.
     * Always attempts BUY first then SELL (buy-first for margin benefits).
     */
    public void processIndicator(SmartConnect smartConnect, Indicator indicator, SpreadType spreadType, POPLevel popLevel) {

        // 1) get LTP
        BigDecimal ltp = angelOneService.getcurrentPrice(
                smartConnect,
                indicator.getExchange(),
                indicator.getTradingSymbol(),
                indicator.getToken(),
                "ltp"
        );

        if (ltp == null) {
            log.warn("No LTP for {}", indicator.getTradingSymbol());
            return;
        }

        // 2) expiry (your code used id=1 previously; adjust if needed)
        Expiry expiry = expiryRepo.findById(1L).orElse(null);
        if (expiry == null) {
            log.warn("Expiry not found (ID 1)");
            return;
        }

        // 3) fetch strikes as BigDecimal (normalized)
        List<BigDecimal> strikes = fetchAllStrikesForInstrument(indicator.getName(), expiry.getExpirydate());
        if (strikes.isEmpty()) {
            log.error("No strikes found in DB for {}", indicator.getTradingSymbol());
            return;
        }

        // 4) find ATM and step
        BigDecimal atmStrike = findNearestStrike(ltp, strikes);
        BigDecimal stepSize = findStrikeInterval(strikes);
        if (stepSize.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("StepSize detected as 0 for {} — aborting", indicator.getTradingSymbol());
            return;
        }

        // Determine POP steps
        int steps = getStepsFromPOP(popLevel);

        // Derive POP-aware sell strikes
        BigDecimal sellStrikePut = atmStrike.subtract(stepSize.multiply(BigDecimal.valueOf(steps)));
        BigDecimal sellStrikeCall = atmStrike.add(stepSize.multiply(BigDecimal.valueOf(steps)));

        // Default hedge (1-step protective leg relative to the sell strike)
        BigDecimal hedgeLower = sellStrikePut.subtract(stepSize); // for put spreads (buy lower)
        BigDecimal hedgeUpper = sellStrikeCall.add(stepSize);    // for call spreads (buy higher)

        Token buyLeg = null;
        Token sellLeg = null;

        // Build appropriate legs based on spreadType.
        switch (spreadType) {
            case BULL_CALL:
                // BUY ATM CE, SELL OTM CE  (debit) — preserve original behaviour
                buyLeg  = buildToken(indicator, expiry, atmStrike, "CE", Constants.TRANSACTION_TYPE_BUY);
                sellLeg = buildToken(indicator, expiry, atmStrike.add(stepSize),   "CE", Constants.TRANSACTION_TYPE_SELL);
                log.info("Preparing BULL_CALL: BUY {} CE, SELL {} CE", atmStrike, atmStrike.add(stepSize));
                break;

            case BEAR_CALL:
                // BEAR CALL (credit spread): SELL OTM CE, BUY OTM protection (buy-first)
                // We use POP-aware sellStrikeCall (which will be ATM when pop=50)
                buyLeg  = buildToken(indicator, expiry, hedgeUpper, "CE", Constants.TRANSACTION_TYPE_BUY); // buy protection
                sellLeg = buildToken(indicator, expiry, sellStrikeCall, "CE", Constants.TRANSACTION_TYPE_SELL);
                log.info("Preparing BEAR_CALL: BUY {} CE, SELL {} CE (buy-first order) [POP={}]", hedgeUpper, sellStrikeCall, popLevel);
                break;

            case BULL_PUT:
                // BULL PUT (credit spread): SELL OTM PE, BUY lower PE (buy-first for margin)
                // Use POP-aware sellStrikePut (ATM if pop=50)
                buyLeg  = buildToken(indicator, expiry, hedgeLower, "PE", Constants.TRANSACTION_TYPE_BUY);  // lower strike protection
                sellLeg = buildToken(indicator, expiry, sellStrikePut,"PE", Constants.TRANSACTION_TYPE_SELL); // sold strike
                log.info("Preparing BULL_PUT: BUY {} PE, SELL {} PE (buy-first order) [POP={}]", hedgeLower, sellStrikePut, popLevel);
                break;

            case BEAR_PUT:
                // BEAR PUT (debit spread): BUY ATM PE, SELL LOWER PE — preserve original behaviour
                buyLeg  = buildToken(indicator, expiry, atmStrike, "PE", Constants.TRANSACTION_TYPE_BUY);
                sellLeg = buildToken(indicator, expiry, atmStrike.subtract(stepSize),   "PE", Constants.TRANSACTION_TYPE_SELL);
                log.info("Preparing BEAR_PUT: BUY {} PE, SELL {} PE", atmStrike, atmStrike.subtract(stepSize));
                break;
        }

        if (buyLeg == null || sellLeg == null) {
            log.error("Could not build spread legs for {} / {}", indicator.getTradingSymbol(), spreadType);
            return;
        }

        prepareCommonFields(buyLeg);
        prepareCommonFields(sellLeg);
        placeOrder(smartConnect,buyLeg,sellLeg);

    
    }
    
	public void placeOrder(SmartConnect smartConnect, Token buyLeg, Token sellLeg) {
		Strategy strategy = strategyRepo.findByName(AppConstant.CPR_STRATEGY);

		if ("Y".equalsIgnoreCase(strategy.getLive())) {
			// Place BUY first, ensure it succeeded before SELL.
			boolean buyOk = placeSingleOrderChecked(smartConnect, buyLeg);
			if (!buyOk) {
				log.error("Buy leg failed for {} - aborting spread placement", buyLeg.getSymbol());
				return;
			}

			boolean sellOk = placeSingleOrderChecked(smartConnect, sellLeg);
			if (!sellOk) {
				log.error("Sell leg failed for {}. Consider manual intervention or cancel buy leg: {}",
						sellLeg.getSymbol(), buyLeg.getSymbol());
				// Optional: consider cancelling the buy leg if your workflow requires it.
			}
		}

	}

    // ------------------------------------------------------------------------
    // Fetch strikes as BigDecimal and normalize (divide by 100)
    // ------------------------------------------------------------------------
    private List<BigDecimal> fetchAllStrikesForInstrument(String tradingSymbol, String expiry) {

        List<Indexes> list = indexesRepo.findByNameAndExpiry(tradingSymbol, expiry);
        if (list == null || list.isEmpty()) return Collections.emptyList();

        return list.stream()
                .map(Indexes::getStrike)
                .filter(Objects::nonNull)
                .map(String::trim)
                .map(this::normalizeStrikeToBigDecimal)   // normalize and convert
                .filter(v -> v.compareTo(BigDecimal.ZERO) > 0)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Normalize DB strike string to BigDecimal strike.
     * Example: "2600000.000000" -> divide by 100 -> 26000
     *          "372500.000000"  -> divide by 100 -> 3725  (if db uses that)
     */
    private BigDecimal normalizeStrikeToBigDecimal(String raw) {
        try {
            BigDecimal bd = new BigDecimal(raw.trim());
            // ALWAYS divide by 100 per your DB pattern
            return bd.divide(new BigDecimal("100"));
        } catch (Exception e) {
            log.error("Invalid strike format: {}", raw, e);
            return BigDecimal.ZERO;
        }
    }

    // ------------------------------------------------------------------------
    // Find nearest strike to LTP (BigDecimal)
    // ------------------------------------------------------------------------
    private BigDecimal findNearestStrike(BigDecimal ltp, List<BigDecimal> strikes) {
        return strikes.stream()
                .min(Comparator.comparing(s -> s.subtract(ltp).abs()))
                .orElse(strikes.get(0));
    }

    // ------------------------------------------------------------------------
    // Find smallest positive step interval between strikes (BigDecimal)
    // ------------------------------------------------------------------------
    private BigDecimal findStrikeInterval(List<BigDecimal> strikes) {
        BigDecimal minDiff = null;
        for (int i = 1; i < strikes.size(); i++) {
            BigDecimal diff = strikes.get(i).subtract(strikes.get(i - 1)).abs();
            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                if (minDiff == null || diff.compareTo(minDiff) < 0) {
                    minDiff = diff;
                }
            }
        }
        return (minDiff != null) ? minDiff : BigDecimal.ZERO;
    }

    /**
     * Build Token from BigDecimal strike.
     * Uses plain string formatting so "372.50" -> "372.5" (stripTrailingZeros()).
     */
    private Token buildToken(Indicator indicator, Expiry expiry,
                             BigDecimal strike, String optionType, String transactionType) {

        if (strike == null) {
            log.error("Null strike when building token for {}", indicator.getTradingSymbol());
            return null;
        }

        String expiryCode = expiry.getExpirydate();

        // Format strike text: remove trailing zeros where not needed but keep decimal if fractional.
        String strikeStr = strike.stripTrailingZeros().toPlainString();

        String shortExpiry = toShortExpiry(expiryCode);

        String symbol = indicator.getTradingSymbol()
                        + shortExpiry
                        + strikeStr
                        + optionType;

        Indexes idx = indexesRepo.findBySymbol(symbol);
        if (idx == null) {
            log.error("Symbol not found in DB → {}", symbol);
            return null;
        }

        Token t = new Token();
        t.setSymbol(symbol);
        t.setToken(idx.getToken());
        t.setExch_seg(idx.getExchange());
        t.setTransactionType(transactionType);
        t.setQuantity(idx.getLotsize());
        return t;
    }
    
    public String toShortExpiry(String expiryCode) {
        if (expiryCode == null || expiryCode.length() < 7) {
            return expiryCode; // invalid format, return as-is
        }

        // First 5 characters = DDMMM (like 25NOV)
        String ddMMM = expiryCode.substring(0, 5);

        // Last 2 characters of the year
        String yy = expiryCode.substring(expiryCode.length() - 2);

        return ddMMM + yy;   // Example: 25NOV25
    }

    // Prepare token common fields for AngelOne
    private void prepareCommonFields(Token token) {
        token.setProductType(Constants.PRODUCT_CARRYFORWARD);
        token.setVariety(Constants.VARIETY_NORMAL);
        token.setOrderType(Constants.ORDER_TYPE_MARKET);
        token.setExch_seg("NFO");
    }

    /**
     * Place a single order and return success/failure.
     *
     * NOTE: Adjust this method to your actual AngelOneService.placeOrder(...) signature.
     * Currently it assumes angelOneService exposes a method that either returns an orderId or throws an exception.
     */
    private boolean placeSingleOrderChecked(SmartConnect sc, Token token) {
        try {
            log.info("Placing order {} {}", token.getTransactionType(), token.getSymbol());

            // === Replace the next line with your real order call and success detection ===
            // Example (pseudocode):
            // String orderId = angelOneService.placeOrder(sc, token);
            // return orderId != null;

            // For now attempt to call a likely method name; adapt if different.
            Token orderResponse = angelOneService.placeOrder(sc, token); // adapt signature
           
            log.info("Order response for {} -> {}", token.getSymbol(), orderResponse);
            // If your service returns null or throws, handle accordingly. We'll assume non-null = success.
            return orderResponse != null;
        } catch (Exception | SmartAPIException e) {
            log.error("Order placement failed for {} : {}", token.getSymbol(), e.getMessage(), e);
            return false;
        }
    }
}
