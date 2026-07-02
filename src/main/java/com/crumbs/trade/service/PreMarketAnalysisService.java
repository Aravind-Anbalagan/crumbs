package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crumbs.trade.dto.SecondMidPointRequest;
import com.crumbs.trade.dto.StraddlePremiumDto;
import com.crumbs.trade.entity.PreMarketAnalysis;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.PreMarketAnalysisRepo;
import com.crumbs.trade.repo.StrategyRepo;

@Service
public class PreMarketAnalysisService {
    
    private static final Logger logger = LoggerFactory.getLogger(PreMarketAnalysisService.class);
    
    @Autowired
    private StraddleIntradayService straddleIntradayService;
    
    @Autowired
    private PreMarketAnalysisRepo preMarketAnalysisRepo;
    
    @Autowired
    private StrategyRepo strategyRepo;
    
    /**
     * Main method: Analyze 9:10 AM data and save to PreMarketAnalysis table
     * Uses in-memory data from getPreMarketLTP() - does NOT query database
     */
    @Transactional
    public void analyzeAndStore(String name) {
        
        try {
            logger.info("=== PRE-MARKET ANALYSIS STARTED FOR {} ===", name);
            preMarketAnalysisRepo.deleteByName(name);
            // 1. Get strategy details
            Strategy strategy = strategyRepo.findByName(name);
            if (strategy == null) {
                logger.error("Strategy not found: {}", name);
                return;
            }
            
            // 2. Get pre-market data (in-memory, NOT from database)
            List<StraddlePremiumDto> strikeList = straddleIntradayService.getPreMarketLTP(name);
            
            if (strikeList == null || strikeList.isEmpty()) {
                logger.error("No pre-market data received for {}", name);
                return;
            }
            
            logger.info("Received {} strike records for pre-market analysis", strikeList.size());
            
            // 3. Find ATM strike with MINIMUM diff
            StraddlePremiumDto selectedAtm = selectATMByMinimumDiff(strikeList);
            
            if (selectedAtm == null) {
                logger.error("Could not select ATM strike for {}", name);
                return;
            }
            
            logger.info("✓ Selected ATM Strike: {} (Diff: {})", 
                selectedAtm.getStrikePrice(), 
                calculateDiff(selectedAtm.getCePrice(), selectedAtm.getPePrice())
            );
            
            // 4. Build PreMarketAnalysis entity
            LocalDateTime timestamp = LocalDateTime.now(ZoneId.of("Asia/Kolkata"))
                .withSecond(0)
                .withNano(0);
            
            PreMarketAnalysis analysis = buildAnalysis(selectedAtm, strategy, timestamp);
            
            // 5. Save to database
            preMarketAnalysisRepo.save(analysis);
            
            logger.info("✓ PRE-MARKET ANALYSIS SAVED: ATM={}, MidPoint={}, Diff={}", 
                analysis.getAtmStrike(),
                analysis.getMidPoint(),
                analysis.getLtpDiff()
            );
            
           
            
        } catch (Exception e) {
            logger.error("Pre-market analysis failed for {}", name, e);
        }
    }
    
    /**
     * Select ATM strike based on MINIMUM |CE - PE| difference
     * Works with DTO objects (in-memory data)
     */
    private StraddlePremiumDto selectATMByMinimumDiff(List<StraddlePremiumDto> dtoList) {
        
        return dtoList.stream()
            .filter(dto -> dto.getCePrice() != null && dto.getPePrice() != null)
            .filter(dto -> dto.getCePrice().compareTo(BigDecimal.ZERO) > 0)
            .filter(dto -> dto.getPePrice().compareTo(BigDecimal.ZERO) > 0)
            .min(Comparator.comparing(dto -> calculateDiff(dto.getCePrice(), dto.getPePrice())))
            .orElse(null);
    }
    
    /**
     * Calculate absolute difference |CE - PE|
     */
    private BigDecimal calculateDiff(BigDecimal ce, BigDecimal pe) {
        if (ce == null || pe == null) {
            return BigDecimal.valueOf(999999); // Invalid record, push to end
        }
        return ce.subtract(pe).abs();
    }
    
    /**
     * Build PreMarketAnalysis entity from selected DTO
     */
    private PreMarketAnalysis buildAnalysis(
        StraddlePremiumDto selected,
        Strategy strategy,
        LocalDateTime timestamp
    ) {
        
        PreMarketAnalysis analysis = new PreMarketAnalysis();
        
        // Basic info
        analysis.setName(strategy.getName());
        analysis.setExpiry(strategy.getExpiry());
        analysis.setTimestamp(timestamp);
        analysis.setTradingDate(LocalDate.now(ZoneId.of("Asia/Kolkata")));
        
        // ATM selection
        analysis.setAtmStrike(selected.getStrikePrice());
        analysis.setLtpDiff(calculateDiff(selected.getCePrice(), selected.getPePrice()));
        
        // 9:10 AM LTP (Post Pre-Market)
        analysis.setCeLtp(selected.getCePrice());
        analysis.setPeLtp(selected.getPePrice());
        
        // Combined LTP = CE + PE
        BigDecimal combinedLtp = selected.getCePrice().add(selected.getPePrice());
        analysis.setCombinedLtp(combinedLtp);
        
        // Mid Point = (CE + PE) / 2
        BigDecimal midPoint = combinedLtp.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        analysis.setMidPoint(midPoint);
        
        // Previous day data (already fetched by getPreMarketLTP method)
        analysis.setCePrevHigh(selected.getCePrevHigh());
        analysis.setCePrevLow(selected.getCePrevLow());
        analysis.setPePrevHigh(selected.getPePrevHigh());
        analysis.setPePrevLow(selected.getPePrevLow());
        analysis.setCeToken(selected.getCeToken().getToken());
        analysis.setPeToken(selected.getPeToken().getToken());
        analysis.setCeSymbol(selected.getCeToken().getSymbol());
        analysis.setPeSymbol(selected.getPeToken().getSymbol());
        return analysis;
    }
    
    /**
     * Get today's pre-market analysis (optional - for reference during trading)
     */
    public Optional<PreMarketAnalysis> getTodayAnalysis(String name) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        return preMarketAnalysisRepo.findByNameAndTradingDate(name, today);
    }
    
  
    
    /**
     * Get the latest pre-market analysis record (regardless of date)
     * Used as fallback when today's data is not available
     */
    public Optional<PreMarketAnalysis> getLatestAnalysis(String name) {
        return preMarketAnalysisRepo.findTopByNameOrderByTimestampDesc(name);
    }
    
    public PreMarketAnalysis updateSecondMidPoint(SecondMidPointRequest request) {
        PreMarketAnalysis analysis = preMarketAnalysisRepo
                .findTopByNameOrderByTimestampDesc(request.getName())
                .orElseThrow(() -> new RuntimeException(
                        "No PreMarketAnalysis found for name: " + request.getName()));

        analysis.setSecondMidPoint(request.getSecondMidPoint());
        return preMarketAnalysisRepo.save(analysis);
    }
}