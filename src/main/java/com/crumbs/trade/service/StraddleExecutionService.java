package com.crumbs.trade.service;

import org.slf4j.LoggerFactory;
import com.crumbs.trade.repo.StrategyRepo;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StraddleExecutionService {
    
    private final StraddleIntradayService straddleIntradayService;
    private final PreMarketAnalysisService preMarketAnalysisService;
    private final StrategyRepo strategyRepo;
    
    /**
     * Regular intraday execution (9:15+ onwards)
     */
    public void execute(String name) {
        try {
            if (!isActive("STRADDLE_PREMIUM")) {
                return;
            }
            straddleIntradayService.getCombineStraddlePremium(name);
        } catch (Exception e) {
            // IMPORTANT: never let scheduler die
            LoggerFactory.getLogger(getClass())
                    .error("❌ Straddle execution failed for {}", name, e);
        }
    }
    
    /**
     * Pre-market execution (8 AM)
     * Fetches data, analyzes, and saves to PreMarketAnalysis table
     * Does NOT save to StraddleIntraday
     */
    public void executePreMarket(String name) {
        try {
            if (!isActive("MARKET_LEVEL")) {
                //LoggerFactory.getLogger(getClass())
                   // .info("Strategy MARKET_LEVEL is inactive, skipping pre-market analysis for {}", name);
                return;
            }
            
            LoggerFactory.getLogger(getClass())
                .info("🚀 PRE-MARKET EXECUTION STARTED FOR {}", name);
            
            // Fetch pre-market data, analyze, and save to PreMarketAnalysis table
            // This internally calls straddleIntradayService.getPreMarketLTP()
            preMarketAnalysisService.analyzeAndStore(name);
            
            LoggerFactory.getLogger(getClass())
                .info("✅ PRE-MARKET EXECUTION COMPLETED FOR {}", name);
            
        } catch (Exception e) {
            // IMPORTANT: never let scheduler die
            LoggerFactory.getLogger(getClass())
                    .error("❌ Pre-market execution failed for {}", name, e);
        }
    }
    
    private boolean isActive(String strategy) {
        return "Y".equalsIgnoreCase(
                strategyRepo.findByName(strategy).getActive()
        );
    }
}