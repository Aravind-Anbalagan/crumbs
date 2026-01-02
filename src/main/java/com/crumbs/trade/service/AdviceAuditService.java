package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;

import com.crumbs.trade.entity.StraddleIntraday;
import com.crumbs.trade.entity.TradingAdvice;
import com.crumbs.trade.entity.TradingAdviceAudit;

@Service
public class AdviceAuditService {

    public TradingAdviceAudit evaluate(
            TradingAdvice advice,
            List<StraddleIntraday> data,
            MarketPressureService pressureService) {
        
        TradingAdviceAudit audit = new TradingAdviceAudit();
        
        // Basic info
        audit.setAdviceId(advice.getId());
        audit.setSymbol(advice.getSymbol());
        audit.setTradeDate(advice.getTradeDate());
        audit.setAdvisedMode(advice.getRecommendedMode().name());
        audit.setAdviceTime(advice.getAdviceTime());
        audit.setExitTime(advice.getExitTime());
        audit.setEntryPressure(advice.getEntryPressure());
        
        // Find entry point
        BigDecimal entrySpot = data.stream()
                .filter(d -> !d.getTimestamp().isBefore(advice.getAdviceTime()))
                .findFirst()
                .map(StraddleIntraday::getSpot)
                .orElse(BigDecimal.ZERO);
        
        // Track metrics during trade lifetime
        int maxPressure = advice.getEntryPressure();
        int minPressure = advice.getEntryPressure();
        BigDecimal maxAdverse = BigDecimal.ZERO;  // Worst move against trade
        BigDecimal maxFavorable = BigDecimal.ZERO; // Best move for trade
        BigDecimal finalSpot = entrySpot;
        
        for (StraddleIntraday row : data) {
            // Skip before entry
            if (row.getTimestamp().isBefore(advice.getAdviceTime())) {
                continue;
            }
            
            // Stop after exit
            if (advice.getExitTime() != null &&
                    row.getTimestamp().isAfter(advice.getExitTime())) {
                break;
            }
            
            // Track pressure range
            int p = pressureService.calculate(row).getPressure();
            maxPressure = Math.max(maxPressure, p);
            minPressure = Math.min(minPressure, p);
            
            // Track spot movement
            BigDecimal move = row.getSpot().subtract(entrySpot);
            
            // For SELL trades: negative move = favorable, positive = adverse
            // For BUY trades: positive move = favorable, negative = adverse
            if (advice.getDirection().name().equals("SELL")) {
                if (move.compareTo(maxFavorable) < 0) {
                    maxFavorable = move; // More negative = better
                }
                if (move.compareTo(maxAdverse) > 0) {
                    maxAdverse = move; // Positive = bad
                }
            } else if (advice.getDirection().name().equals("BUY")) {
                if (move.compareTo(maxFavorable) > 0) {
                    maxFavorable = move; // More positive = better
                }
                if (move.compareTo(maxAdverse) < 0) {
                    maxAdverse = move; // Negative = bad
                }
            }
            
            finalSpot = row.getSpot();
        }
        
        // Set audit fields
        audit.setMaxPressureAfterEntry(maxPressure);
        audit.setMaxSpotMoveAgainst(maxAdverse.abs());
        
        // NEW: Additional metrics
        audit.setMinPressureAfterEntry(minPressure);
        audit.setMaxFavorableMove(maxFavorable.abs());
        audit.setFinalSpotAtExit(finalSpot);
        
        // Determine if advice survived
        boolean survived = advice.getExitTime() == null;
        audit.setAdviceSurvived(survived);
        
        // Determine if exit was timely (< 50 points adverse move)
        boolean timelyExit = advice.getExitTime() != null &&
                            maxAdverse.abs().compareTo(new BigDecimal("50")) < 0;
        audit.setExitWasTimely(timelyExit);
        
        // NEW: Classify exit quality
        String exitQuality = classifyExitQuality(
            advice, 
            maxAdverse, 
            maxFavorable,
            timelyExit,
            survived
        );
        
        // Enhanced conclusion
        String conclusion = buildConclusion(
            survived,
            timelyExit,
            exitQuality,
            maxAdverse,
            maxFavorable
        );
        
        audit.setAuditConclusion(conclusion);
        audit.setExitQuality(exitQuality);
        
        return audit;
    }
    
    // =====================================================
    // CLASSIFY EXIT QUALITY
    // =====================================================
    private String classifyExitQuality(
            TradingAdvice advice,
            BigDecimal maxAdverse,
            BigDecimal maxFavorable,
            boolean timelyExit,
            boolean survived) {
        
        if (survived) {
            return "NO_EXIT_HELD_TO_EOD";
        }
        
        double adverse = maxAdverse.abs().doubleValue();
        double favorable = maxFavorable.abs().doubleValue();
        
        // Excellent: Exit before 30 points adverse
        if (adverse < 30) {
            return "EXCELLENT_QUICK_EXIT";
        }
        
        // Good: Exit before 50 points adverse
        if (timelyExit) {
            return "GOOD_PROTECTED_CAPITAL";
        }
        
        // Fair: Exit before 75 points adverse
        if (adverse < 75) {
            return "FAIR_ACCEPTABLE_LOSS";
        }
        
        // Poor: Exit after 75-100 points adverse
        if (adverse < 100) {
            return "POOR_LATE_EXIT";
        }
        
        // Very Poor: Exit after 100+ points adverse
        return "VERY_POOR_CATASTROPHIC";
    }
    
    // =====================================================
    // BUILD CONCLUSION
    // =====================================================
    private String buildConclusion(
            boolean survived,
            boolean timelyExit,
            String exitQuality,
            BigDecimal maxAdverse,
            BigDecimal maxFavorable) {
        
        if (survived) {
            return String.format(
                "Trade held entire session. Max favorable: %.0f pts, Max adverse: %.0f pts",
                maxFavorable.abs().doubleValue(),
                maxAdverse.abs().doubleValue()
            );
        }
        
        return String.format(
            "%s. Max adverse: %.0f pts, Max favorable: %.0f pts",
            exitQuality.replace("_", " "),
            maxAdverse.abs().doubleValue(),
            maxFavorable.abs().doubleValue()
        );
    }
}