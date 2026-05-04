package com.crumbs.trade.service;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import com.crumbs.trade.broker.Samco;
import com.crumbs.trade.dto.SamcoOptionChainResponse;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.OptionsGreeks;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.OptionsGreeksRepo;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.utility.SamcoSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class GreekStrategyService {

    private static final Logger logger = LoggerFactory.getLogger(GreekStrategyService.class);
    
    private final Samco samco;
    private final OptionsGreeksRepo optionsGreeksRepo;
    private final ObjectMapper objectMapper; 
    private final GreekStrategyEngine greekStrategyEngine; 
    private final StrategyRepo strategyRepo; 
    private final OrderService orderService; 
    private final OrderRepository ordersRepo; 
    private final AngelWebSocketService angelWebSocketService; 
    
    @Autowired
    private SamcoSessionManager sessionManager;

    private double getStrikeGap(String symbol) {
        String safeSymbol = symbol.toUpperCase();
        
        // Future-proofing for broader indexes
        if (safeSymbol.contains("BANKNIFTY") || safeSymbol.contains("SENSEX")) {
            return 100.0;
        }
        
        // NIFTY and CRUDEOIL both operate on 50-point intervals
        return 50.0;
    }

    private int getTrackingRange(String symbol) {
        return symbol.toUpperCase().contains("CRUDEOIL") ? 1000 : 500;
    }

    /**
     * Pulse 1 (:00s) - Ingest ATM Option Chain & Greeks
     */
    public void ingestAtmChain(String strategyName) {
        logger.info("📡 [INGEST] Pulse started for {}", strategyName);
        try {
            Strategy strategy = strategyRepo.findByName(strategyName); 
            if (strategy == null) return;

            String symbol = strategy.getSymbol(); // e.g., "NIFTY"
            BigDecimal spotPrice = getLiveSpotPrice(strategy, sessionManager.getSession());
            
            double strikeGap = getStrikeGap(symbol);
            int range = getTrackingRange(symbol);
            long atmStrike = Math.round(spotPrice.doubleValue() / strikeGap) * (long)strikeGap;
            
            logger.info("✅ [SYNC {}] Spot: {} | ATM: {} | Expiry: {}", 
                        symbol, spotPrice, atmStrike, strategy.getExpiry());

            String fullChainJson = samco.getOptionChain(
                    sessionManager.getSession(), strategy.getExchange(), symbol, strategy.getExpiry(), null, null
            );

         // Change this line in ingestAtmChain:
            saveOptionRangeToDb(fullChainJson, spotPrice, atmStrike - range, atmStrike + range);

        } catch (Exception e) {
            logger.error("🚨 [INGEST FAILURE] {}: {}", strategyName, e.getMessage());
        }
    }

    /**
     * Pulse 2 (:05s) - Evaluate Mathematical Edge & Execute
     */
    public void evaluate(String strategyName) {
        logger.info("--- 🔍 Evaluating Strategy: {} ---", strategyName);
        try {
            Strategy strategy = strategyRepo.findByName(strategyName);
            if (strategy == null) return;

            // 1. Check for active trade
            if (ordersRepo.findByNameAndActive(strategyName, 1) != null) {
                logger.info("⚖️ [MONITOR] {} already has an active trade. Skipping.", strategyName);
                return;
            }

            // 2. Resolve Symbol mapping (Handles "SAMCO_NIFTY" vs "NIFTY" in DB)
            String symbol = strategy.getSymbol(); // "NIFTY"
            BigDecimal spotPrice = getLiveSpotPrice(strategy, sessionManager.getSession());
            
         // 1. Calculate the ATM Strike as a numeric value
            double strikeGap = getStrikeGap(symbol);
            long atmStrikeLong = Math.round(spotPrice.doubleValue() / strikeGap) * (long)strikeGap;

            // 2. Convert to BigDecimal (matching your Entity and Repo types)
            BigDecimal strikeLookup = BigDecimal.valueOf(atmStrikeLong).setScale(4);

            // 3. Query the Repository using the BigDecimal object
            Optional<OptionsGreeks> latestCeOpt = optionsGreeksRepo.findTopBySymbolAndStrikePriceAndOptionTypeOrderByTimestampDesc(
                symbol, 
                strikeLookup, 
                "CE"
            );

            Optional<OptionsGreeks> latestPeOpt = optionsGreeksRepo.findTopBySymbolAndStrikePriceAndOptionTypeOrderByTimestampDesc(
                symbol, 
                strikeLookup, 
                "PE"
            );

            if (latestCeOpt.isEmpty() || latestPeOpt.isEmpty()) {
                logger.warn("⚠️ [DATA MISSING] No ATM data for {} at strike {}", symbol, strikeLookup);
                return;
            }

            OptionsGreeks callLeg = latestCeOpt.get();
            OptionsGreeks putLeg = latestPeOpt.get();

            // 4. Brain Evaluation
            boolean edgeDetected = greekStrategyEngine.evaluateEntry(callLeg, putLeg);

            if (edgeDetected) {
                logger.info("🚀 [OPPORTUNITY] Edge confirmed for {}. Executing Entry...", strategyName);
                
                Token ceToken = mapToOrderToken(callLeg, strategy);
                Token peToken = mapToOrderToken(putLeg, strategy);

                // Live/Paper logic is inside orderService
                orderService.orderPlaceWithToken(ceToken, strategyName, "BUY", true);
                orderService.orderPlaceWithToken(peToken, strategyName, "BUY", true);
                
                logger.info("✅ [EXECUTED] Straddle orders sent for {}", strategyName);
            }

        } catch (Exception | SmartAPIException e) {
            logger.error("💥 [EVAL FAILURE] {}: {}", strategyName, e.getMessage());
        }
    }

    private void saveOptionRangeToDb(String jsonResponse, BigDecimal spotPrice, long lowerBound, long upperBound) {
        try {
            SamcoOptionChainResponse response = objectMapper.readValue(jsonResponse, SamcoOptionChainResponse.class);
            if ("Success".equalsIgnoreCase(response.status()) && response.optionChainDetails() != null) {
                List<OptionsGreeks> batchToSave = new ArrayList<>();

                for (var detail : response.optionChainDetails()) {
                    // Keep strike as BigDecimal for the Entity
                    BigDecimal currentStrike = new BigDecimal(detail.strikePrice());

                    if (currentStrike.longValue() >= lowerBound && currentStrike.longValue() <= upperBound) {
                        OptionsGreeks log = new OptionsGreeks();
                        log.setTimestamp(LocalDateTime.now());
                        log.setSymbol(detail.underLyingSymbol());
                        log.setTradingSymbol(detail.tradingSymbol());
                        log.setToken(detail.instrumentToken());
                        log.setExpiryDate(detail.expiryDate());
                        
                        // Matches your new BigDecimal Entity field
                        log.setStrikePrice(currentStrike); 
                        log.setOptionType(detail.optionType());
                        
                        // Matches your new BigDecimal Entity field
                        log.setSpotPrice(spotPrice); 
                        
                        // Matches your new BigDecimal Entity field
                        log.setLtp(new BigDecimal(detail.lastTradedPrice())); 

                        // Greeks remain Doubles as per our math rule
                        log.setImpliedVolatility(parseDoubleSafely(detail.impliedVolatility()));
                        log.setDelta(parseDoubleSafely(detail.delta()));
                        log.setGamma(parseDoubleSafely(detail.gamma()));
                        log.setTheta(parseDoubleSafely(detail.theta()));
                        log.setVega(parseDoubleSafely(detail.vega()));
                        
                        log.setBestBids(objectMapper.writeValueAsString(detail.bestBids()));
                        log.setBestAsks(objectMapper.writeValueAsString(detail.bestAsks()));

                        batchToSave.add(log);
                    }
                }
                if (!batchToSave.isEmpty()) {
                    optionsGreeksRepo.saveAll(batchToSave);
                    logger.info("💾 [DB] Saved {} option records", batchToSave.size());
                }
            }
        } catch (Exception e) {
            logger.error("❌ [DB SAVE ERROR] {}", e.getMessage());
        }
    }

    private Token mapToOrderToken(OptionsGreeks greeks, Strategy strategy) {
        Token t = new Token();
        t.setSymbol(greeks.getTradingSymbol());
        t.setToken(greeks.getToken());         
        t.setExch_seg(strategy.getExchange()); 
        t.setQuantity(strategy.getQuantity()); 
        return t;
    }

    private BigDecimal getLiveSpotPrice(Strategy strategy, String sessionToken) {
        try {
            if (strategy.getToken() != null && !strategy.getToken().trim().isEmpty()) {
                ExchangeType angelExchange = "MCX".equalsIgnoreCase(strategy.getExchange()) 
                        ? ExchangeType.MCX_FO : ExchangeType.NSE_FO;
                BigDecimal wsPrice = angelWebSocketService.getLatestLTP(angelExchange, strategy.getToken());
                if (wsPrice != null && wsPrice.compareTo(BigDecimal.ZERO) > 0) return wsPrice;
            }
        } catch (Exception e) {}
        return samco.getLtp(sessionToken, strategy.getExchange(), 
               "CRUDEOIL".equalsIgnoreCase(strategy.getSymbol()) ? strategy.getSymbol() : "NIFTY 50");
    }

    private Double parseDoubleSafely(String value) {
        try {
            return (value == null || value.trim().isEmpty() || "NA".equalsIgnoreCase(value)) ? 0.0 : Double.parseDouble(value);
        } catch (NumberFormatException e) { return 0.0; }
    }
}