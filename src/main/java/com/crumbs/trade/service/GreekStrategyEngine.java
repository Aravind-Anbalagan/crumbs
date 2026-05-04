package com.crumbs.trade.service;

import com.crumbs.trade.entity.OptionsGreeks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class GreekStrategyEngine {

    private static final Logger log = LoggerFactory.getLogger(GreekStrategyEngine.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    private final ObjectMapper objectMapper;

    // Adjusted to Percentage (1.5%) instead of a hardcoded 2.0 value
    private static final double MAX_SLIPPAGE_PERCENT = 0.015; 
    private static final double EDGE_DISCOUNT_THRESHOLD = 0.85; 

    public boolean evaluateEntry(OptionsGreeks callData, OptionsGreeks putData) {
        
        try {
            // FIX: Convert BigDecimal to double for math library compatibility
            double spotPrice = callData.getSpotPrice().doubleValue();
            
            // 1. Average the IV
            double averageIvDecimal = ((callData.getImpliedVolatility() + putData.getImpliedVolatility()) / 2.0) / 100.0;

            // 2. High-Precision DTE Calculation (Minutes based)
            // Note: callData.getExpiryDate() is String, we assume it's YYYY-MM-DD
            // We set expiry time to 15:30 (Market Close) for precise decay math
            LocalDateTime expiryDateTime = LocalDateTime.parse(callData.getExpiryDate() + "T15:30:00");
            long minutesToExpiry = ChronoUnit.MINUTES.between(LocalDateTime.now(), expiryDateTime);
            
            // Convert minutes to fractional years (1440 mins/day * 365 days)
            double effectiveDteYears = Math.max(minutesToExpiry, 30) / (1440.0 * 365.0); 

            // 3. 1-SD Expected Move Calculation
            double expectedMove = spotPrice * averageIvDecimal * Math.sqrt(effectiveDteYears);

            // 4. Extract Top of Book Pricing
            double callAsk = extractTopDepthPrice(callData.getBestAsks());
            double callBid = extractTopDepthPrice(callData.getBestBids());
            double putAsk  = extractTopDepthPrice(putData.getBestAsks());
            double putBid  = extractTopDepthPrice(putData.getBestBids());

            // 5. Dynamic Slippage Protection (Percentage Based)
            double totalStraddleCost = callAsk + putAsk;
            double callSpreadPct = (callAsk - callBid) / callAsk;
            double putSpreadPct = (putAsk - putBid) / putAsk;

            if (callSpreadPct > MAX_SLIPPAGE_PERCENT || putSpreadPct > MAX_SLIPPAGE_PERCENT) {
                log.warn("STANDBY: Spreads too wide. CE: {}%, PE: {}%", 
                          String.format("%.2f", callSpreadPct * 100), 
                          String.format("%.2f", putSpreadPct * 100));
                return false;
            }

            // 6. Edge Calculation
            double requiredDiscountPrice = expectedMove * EDGE_DISCOUNT_THRESHOLD;

            log.info("{} | ExpMove: {} | Cost: {} | Target: {}", 
                     callData.getSymbol(),
                     String.format("%.2f", expectedMove), 
                     String.format("%.2f", totalStraddleCost), 
                     String.format("%.2f", requiredDiscountPrice));

            return totalStraddleCost < requiredDiscountPrice;

        } catch (Exception e) {
            log.error("Math engine failed during edge calculation: {}", e.getMessage());
            return false;
        }
    }

    private double extractTopDepthPrice(String depthJson) {
        try {
            if (depthJson == null || depthJson.isEmpty()) return 0.0;
            JsonNode rootArray = objectMapper.readTree(depthJson);
            if (rootArray.isArray() && !rootArray.isEmpty()) {
                return rootArray.get(0).get("price").asDouble();
            }
        } catch (Exception e) {
            log.error("Failed to parse depth JSON: {}", e.getMessage());
        }
        return 0.0;
    }
}