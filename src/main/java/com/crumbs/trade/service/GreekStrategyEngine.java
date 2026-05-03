package com.crumbs.trade.service;

import com.crumbs.trade.entity.OptionsGreeks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class GreekStrategyEngine {

    private static final Logger log = LoggerFactory.getLogger(GreekStrategyEngine.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    private final ObjectMapper objectMapper; // Used to parse the JSON string columns for Bids/Asks

    private static final double MAX_BID_ASK_SPREAD = 2.0; 
    private static final double EDGE_DISCOUNT_THRESHOLD = 0.85; 

    public boolean evaluateEntry(OptionsGreeks callData, OptionsGreeks putData) {
        
        try {
            double spotPrice = callData.getSpotPrice();
            
            // 1. Average the IV (Convert percentage to decimal)
            double averageIvDecimal = ((callData.getImpliedVolatility() + putData.getImpliedVolatility()) / 2.0) / 100.0;

            // 2. Calculate DTE
            LocalDate expiryDate = LocalDate.parse(callData.getExpiryDate(), DATE_FORMATTER);
            long dte = ChronoUnit.DAYS.between(LocalDate.now(), expiryDate);
            double effectiveDte = (dte == 0) ? 0.5 : (double) dte;

            // 3. Calculate 1 Standard Deviation Expected Move
            double expectedMove = spotPrice * averageIvDecimal * Math.sqrt(effectiveDte / 365.0);

            // 4. Extract Top of Book (L2) Pricing from the JSON Text Columns
            double callAsk = extractTopDepthPrice(callData.getBestAsks());
            double callBid = extractTopDepthPrice(callData.getBestBids());
            double putAsk  = extractTopDepthPrice(putData.getBestAsks());
            double putBid  = extractTopDepthPrice(putData.getBestBids());

            // 5. Slippage Protection
            double callSpread = callAsk - callBid;
            double putSpread = putAsk - putBid;

            if (callSpread > MAX_BID_ASK_SPREAD || putSpread > MAX_BID_ASK_SPREAD) {
                log.warn("STANDBY: Spreads too wide. CE Spread: {}, PE Spread: {}", callSpread, putSpread);
                return false;
            }

            // 6. The Edge Calculation
            double combinedAskCost = callAsk + putAsk;
            double requiredDiscountPrice = expectedMove * EDGE_DISCOUNT_THRESHOLD;

            log.info("Expected Move: {}, Straddle Cost: {}, Target Price: {}", 
                     expectedMove, combinedAskCost, requiredDiscountPrice);

            return combinedAskCost < requiredDiscountPrice;

        } catch (Exception e) {
            log.error("Math engine failed during edge calculation: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Helper to extract the highest/lowest price from the JSON String stored in the DB
     */
    private double extractTopDepthPrice(String depthJson) {
        try {
            if (depthJson == null || depthJson.isEmpty()) return 0.0;
            JsonNode rootArray = objectMapper.readTree(depthJson);
            if (rootArray.isArray() && !rootArray.isEmpty()) {
                // Gets the first element in the depth array (Top Bid or Top Ask)
                return rootArray.get(0).get("price").asDouble();
            }
        } catch (Exception e) {
            log.error("Failed to parse depth JSON: {}", e.getMessage());
        }
        return 0.0;
    }
}