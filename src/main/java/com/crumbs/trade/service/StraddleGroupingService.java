package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.NameExpiryStrikeGroupedDto;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.StraddleIntradayRepo;
import com.crumbs.trade.repo.StrategyRepo;

@Service
public class StraddleGroupingService {
    
    private static final Logger logger = LoggerFactory.getLogger(StraddleGroupingService.class);
    
    @Autowired StraddleIntradayRepo straddleIntradayRepo;
    @Autowired StrategyRepo strategyRepo;
    @Autowired StraddleIntradayService straddleIntradayService;
    @Autowired AngelOne angelOne;
    @Autowired AngelOneService angelOneService;
    
    /**
     * Groups straddle data by name, expiry, and strikes
     * Returns data AS-IS from the database
     */
    public List<NameExpiryStrikeGroupedDto> getGrouped() {
        // Fetch raw data from database
        List<Object[]> raw = straddleIntradayRepo.fetchNameExpiryStrikeRaw();
        
        logger.info("Fetched {} raw records from database", raw.size());
        
        // Group by: name -> expiry -> Set<strikes>
        Map<String, Map<String, Set<BigDecimal>>> grouped = new LinkedHashMap<>();
        
        for (Object[] row : raw) {
            String name = (String) row[0];
            String expiry = (String) row[1];
            BigDecimal strike = (BigDecimal) row[2];
            
            logger.info("Processing: name={}, expiry={}, strike={}", name, expiry, strike);
            
            grouped
                .computeIfAbsent(name, n -> new LinkedHashMap<>())
                .computeIfAbsent(expiry, e -> new TreeSet<>())
                .add(strike);
        }
        
        logger.info("Grouped into {} instruments", grouped.size());
        
        // Build result list
        List<NameExpiryStrikeGroupedDto> result = new ArrayList<>();
        SmartConnect smartconnect = angelOne.signIn();
        
        for (Map.Entry<String, Map<String, Set<BigDecimal>>> entry : grouped.entrySet()) {
            String instrumentName = entry.getKey();
            
            logger.info("Processing instrument: {}", instrumentName);
            
            NameExpiryStrikeGroupedDto dto = new NameExpiryStrikeGroupedDto();
            dto.setName(instrumentName);
            
            // Get strategy for THIS instrument (not hardcoded NIFTY_INDEX)
            Strategy strategy = getStrategyForInstrument(instrumentName);
            
            if (strategy != null) {
                try {
                    // Get current spot price for THIS instrument
                    BigDecimal spotPrice = angelOneService.getcurrentPrice(
                        smartconnect,
                        strategy.getExchange(),
                        strategy.getTradingsymbol(),
                        strategy.getToken()
                    );
                    
                    logger.info("Spot price for {}: {}", instrumentName, spotPrice);
                    
                    // Calculate ATM strike for THIS instrument
                    BigDecimal atmStrike = straddleIntradayService.getATMStrike(
                        instrumentName,
                        strategy,
                        spotPrice
                    );
                    
                    dto.setAtmStrike(atmStrike);
                    logger.info("ATM strike for {}: {}", instrumentName, atmStrike);
                    
                } catch (Exception e) {
                    logger.error("Error getting price/ATM for {}: {}", instrumentName, e.getMessage(), e);
                    // Don't set ATM if we can't calculate it
                }
            } else {
                logger.warn("No strategy found for instrument: {}", instrumentName);
            }
            
            // Convert Set<BigDecimal> to List<BigDecimal> for each expiry
            Map<String, List<BigDecimal>> expiryToStrikes = new LinkedHashMap<>();
            
            for (Map.Entry<String, Set<BigDecimal>> expiryEntry : entry.getValue().entrySet()) {
                String expiry = expiryEntry.getKey();
                List<BigDecimal> strikes = new ArrayList<>(expiryEntry.getValue());
                
                logger.info("  Expiry {}: {} strikes (range: {} to {})", 
                    expiry, 
                    strikes.size(),
                    strikes.isEmpty() ? "N/A" : strikes.get(0),
                    strikes.isEmpty() ? "N/A" : strikes.get(strikes.size() - 1)
                );
                
                expiryToStrikes.put(expiry, strikes);
            }
            
            dto.setExpiries(expiryToStrikes);
            result.add(dto);
        }
        
        logger.info("Returning {} instruments", result.size());
        return result;
    }
    
    /**
     * Get the correct strategy for each instrument
     * THIS WAS THE BUG: You were using NIFTY_INDEX for everything!
     */
    private Strategy getStrategyForInstrument(String instrumentName) {
        String strategyName;
        
        // Map instrument name to strategy name
        switch (instrumentName.toUpperCase()) {
            case "NIFTY":
                strategyName = "NIFTY_INDEX";
                break;
            case "BANKNIFTY":
                strategyName = "BANKNIFTY_INDEX";
                break;
            case "FINNIFTY":
                strategyName = "FINNIFTY_INDEX";
                break;
            case "CRUDEOIL":
                strategyName = "CRUDEOIL_INDEX";  // NOT NIFTY_INDEX!
                break;
            case "MIDCPNIFTY":
                strategyName = "MIDCPNIFTY_INDEX";
                break;
            case "SENSEX":
                strategyName = "SENSEX_INDEX";
                break;
            default:
                // Default pattern: instrumentName + "_INDEX"
                strategyName = instrumentName.toUpperCase() + "_INDEX";
                break;
        }
        
        logger.info("Looking up strategy: {} for instrument: {}", strategyName, instrumentName);
        
        Strategy strategy = strategyRepo.findByName(strategyName);
        
        if (strategy == null) {
            // Fallback: try without _INDEX suffix
            logger.debug("Strategy not found, trying without _INDEX suffix");
            strategy = strategyRepo.findByName(instrumentName.toUpperCase());
        }
        
        if (strategy == null) {
            logger.warn("No strategy found for instrument: {} (tried: {} and {})", 
                instrumentName, strategyName, instrumentName.toUpperCase());
        }
        
        return strategy;
    }
}