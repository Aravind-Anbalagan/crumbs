package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Service;

import com.crumbs.trade.dto.PressureInsightDTO;
import com.crumbs.trade.entity.StraddleIntraday;
import com.crumbs.trade.entity.TradingAdvice;
import com.crumbs.trade.repo.StraddleIntradayRepo;
import com.crumbs.trade.utility.AdviceStatus;
import com.crumbs.trade.utility.MarketDirection;
import com.crumbs.trade.utility.PressureZone;
import com.crumbs.trade.utility.TradingMode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdviceObserverService {

    private final StraddleIntradayRepo straddleRepo;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    
    // =====================================================
    // CONFIGURATION - TUNE THESE BASED ON BACKTESTING
    // =====================================================
    private static final double NIFTY_HARD_STOP = 100.0;      // Points
    private static final double CRUDE_HARD_STOP = 20.0;       // Points
    private static final double NIFTY_TRAILING_STOP = 75.0;   // Points
    private static final double CRUDE_TRAILING_STOP = 15.0;   // Points
    private static final double PROFIT_LOCK_THRESHOLD = 150.0; // Points for Nifty
    private static final int MAX_TRADE_AGE_MINUTES = 120;     // 2 hours
    private static final double PREMIUM_DECAY_THRESHOLD = 0.30; // 30% decay
    
    // =====================================================
    // MAIN EXIT DECISION (CALLED EVERY MINUTE BY SCHEDULER)
    // =====================================================
    public boolean shouldExit(
            TradingAdvice advice,
            PressureInsightDTO currentPressure) {
        
        if (advice.getStatus() != AdviceStatus.ACTIVE) {
            return false;
        }
        
        // Get current market snapshot
        List<StraddleIntraday> snapshot = 
            straddleRepo.findLatestSnapshot(advice.getSymbol());
        
        if (snapshot == null || snapshot.isEmpty()) {
            return false;
        }
        
        StraddleIntraday current = snapshot.get(0);
        
        // =====================================================
        // EXIT PRIORITY 1: HARD STOP-LOSS (IMMEDIATE)
        // Most important - prevents catastrophic losses
        // =====================================================
        String stopReason = checkHardStopLoss(advice, current);
        if (stopReason != null) {
            advice.setExitReason(stopReason);
            return true;
        }
        
        // =====================================================
        // EXIT PRIORITY 2: TIME-BASED (RISK MANAGEMENT)
        // Force exit near close or when trade ages
        // =====================================================
        String timeReason = checkTimeBasedExit(advice);
        if (timeReason != null) {
            advice.setExitReason(timeReason);
            return true;
        }
        
        // =====================================================
        // EXIT PRIORITY 3: TRAILING STOP (PROFIT PROTECTION)
        // Lock in profits when trade moves favorably
        // =====================================================
        String trailingReason = checkTrailingStop(advice, current);
        if (trailingReason != null) {
            advice.setExitReason(trailingReason);
            return true;
        }
        
        // =====================================================
        // EXIT PRIORITY 4: PRESSURE REVERSAL (SIGNAL QUALITY)
        // Original pressure-based logic
        // =====================================================
        String pressureReason = checkPressureReversal(
            advice, 
            currentPressure
        );
        if (pressureReason != null) {
            advice.setExitReason(pressureReason);
            return true;
        }
        
        // =====================================================
        // EXIT PRIORITY 5: PREMIUM DECAY (OPTIONS SPECIFIC)
        // Detect when option value decays despite favorable move
        // =====================================================
        String premiumReason = checkPremiumDecay(advice, current);
        if (premiumReason != null) {
            advice.setExitReason(premiumReason);
            return true;
        }
        
        return false;
    }
    
    // =====================================================
    // EXIT CHECK 1: HARD STOP-LOSS
    // =====================================================
    private String checkHardStopLoss(
            TradingAdvice advice,
            StraddleIntraday current) {
        
        if (advice.getEntrySpot() == null || current.getSpot() == null) {
            return null;
        }
        
        // Instrument-specific stop loss
        double stopLoss = advice.getSymbol().equals("CRUDEOIL") 
            ? CRUDE_HARD_STOP 
            : NIFTY_HARD_STOP;
        
        BigDecimal spotMove = current.getSpot()
            .subtract(advice.getEntrySpot());
        
        double move = spotMove.doubleValue();
        
        // SELL/SHORT: Exit if market rallies against us
        if (advice.getDirection() == MarketDirection.SELL) {
            if (move > stopLoss) {
                return String.format(
                    "STOP_LOSS_HIT_UP_%.0fPTS", 
                    move
                );
            }
        }
        
        // BUY/LONG: Exit if market drops against us
        if (advice.getDirection() == MarketDirection.BUY) {
            if (move < -stopLoss) {
                return String.format(
                    "STOP_LOSS_HIT_DOWN_%.0fPTS", 
                    Math.abs(move)
                );
            }
        }
        
        return null;
    }
    
    // =====================================================
    // EXIT CHECK 2: TIME-BASED
    // =====================================================
    private String checkTimeBasedExit(TradingAdvice advice) {
        
        LocalTime now = LocalTime.now(IST);
        
        // Near market close - force exit
        if (advice.getSymbol().equals("NIFTY") 
                && now.isAfter(LocalTime.of(15, 20))) {
            return "TIME_EXIT_NEAR_CLOSE_1520";
        }
        
        if (advice.getSymbol().equals("CRUDEOIL") 
                && now.isAfter(LocalTime.of(23, 20))) {
            return "TIME_EXIT_NEAR_CLOSE_2320";
        }
        
        // Trade aging without momentum
        long minutesActive = Duration.between(
            advice.getAdviceTime(),
            LocalDateTime.now(IST)
        ).toMinutes();
        
        if (minutesActive > MAX_TRADE_AGE_MINUTES) {
            return String.format(
                "TRADE_AGED_OUT_%dMIN", 
                minutesActive
            );
        }
        
        return null;
    }
    
    // =====================================================
    // EXIT CHECK 3: TRAILING STOP (PROFIT LOCK)
    // =====================================================
    private String checkTrailingStop(
            TradingAdvice advice,
            StraddleIntraday current) {
        
        if (advice.getEntrySpot() == null || current.getSpot() == null) {
            return null;
        }
        
        BigDecimal spotMove = current.getSpot()
            .subtract(advice.getEntrySpot());
        
        double move = spotMove.doubleValue();
        
        // Instrument-specific trailing stop
        double trailingStop = advice.getSymbol().equals("CRUDEOIL") 
            ? CRUDE_TRAILING_STOP 
            : NIFTY_TRAILING_STOP;
        
        // SELL direction: Profit when market drops
        if (advice.getDirection() == MarketDirection.SELL) {
            // Currently in profit (market moved down)
            if (move < -PROFIT_LOCK_THRESHOLD) {
                // Get max favorable move from historical data
                List<StraddleIntraday> history = 
                    straddleRepo.findSinceAdviceTime(
                        advice.getSymbol(), 
                        advice.getAdviceTime()
                    );
                
                if (history != null && !history.isEmpty()) {
                    double maxFavorable = history.stream()
                        .mapToDouble(s -> advice.getEntrySpot()
                            .subtract(s.getSpot()).doubleValue())
                        .max()
                        .orElse(0);
                    
                    // If market rebounded from peak by trailing stop amount
                    if (maxFavorable - Math.abs(move) > trailingStop) {
                        return String.format(
                            "TRAILING_STOP_PROFIT_LOCK_%.0fPTS", 
                            Math.abs(move)
                        );
                    }
                }
            }
        }
        
        // BUY direction: Profit when market rises
        if (advice.getDirection() == MarketDirection.BUY) {
            // Currently in profit (market moved up)
            if (move > PROFIT_LOCK_THRESHOLD) {
                List<StraddleIntraday> history = 
                    straddleRepo.findSinceAdviceTime(
                        advice.getSymbol(), 
                        advice.getAdviceTime()
                    );
                
                if (history != null && !history.isEmpty()) {
                    double maxFavorable = history.stream()
                        .mapToDouble(s -> s.getSpot()
                            .subtract(advice.getEntrySpot()).doubleValue())
                        .max()
                        .orElse(0);
                    
                    // If market pulled back from peak by trailing stop amount
                    if (maxFavorable - move > trailingStop) {
                        return String.format(
                            "TRAILING_STOP_PROFIT_LOCK_%.0fPTS", 
                            move
                        );
                    }
                }
            }
        }
        
        return null;
    }
    
    // =====================================================
    // EXIT CHECK 4: PRESSURE REVERSAL (YOUR ORIGINAL LOGIC)
    // =====================================================
    private String checkPressureReversal(
            TradingAdvice advice,
            PressureInsightDTO currentPressure) {
        
        TradingMode mode = advice.getRecommendedMode();
        PressureZone currentZone = currentPressure.getZone();
        int pressureChange = currentPressure.getPressure() 
                           - advice.getEntryPressure();
        
        // OPTION_SELL_RANGE: Exit if pressure no longer LOW
        if (mode == TradingMode.OPTION_SELL_RANGE) {
            if (currentZone != PressureZone.LOW) {
                return String.format(
                    "PRESSURE_REVERSAL_RANGE_BROKEN_TO_%s", 
                    currentZone
                );
            }
        }
        
        // OPTION_SELL_DIRECTIONAL: Exit if pressure becomes HIGH/CRITICAL
        if (mode == TradingMode.OPTION_SELL_DIRECTIONAL) {
            if (currentZone == PressureZone.HIGH 
                    || currentZone == PressureZone.CRITICAL) {
                return String.format(
                    "PRESSURE_REVERSAL_BEARISH_TO_%s", 
                    currentZone
                );
            }
        }
        
        // OPTION_BUY_DIRECTIONAL: Exit if pressure drops significantly
        // FIXED: Was exiting on LOW (wrong), now exits on weakening
        if (mode == TradingMode.OPTION_BUY_DIRECTIONAL) {
            if (pressureChange < -20) {
                return String.format(
                    "PRESSURE_WEAKENED_FROM_%d_TO_%d", 
                    advice.getEntryPressure(),
                    currentPressure.getPressure()
                );
            }
        }
        
        return null;
    }
    
    // =====================================================
    // EXIT CHECK 5: PREMIUM DECAY
    // =====================================================
    private String checkPremiumDecay(
            TradingAdvice advice,
            StraddleIntraday current) {
        
        if (advice.getEntryPremium() == null 
                || current.getCombinedPremium() == null) {
            return null;
        }
        
        BigDecimal premiumChange = current.getCombinedPremium()
            .subtract(advice.getEntryPremium());
        
        double decayPct = premiumChange
            .divide(advice.getEntryPremium(), 4, BigDecimal.ROUND_HALF_UP)
            .doubleValue();
        
        // If premium decayed by 30%+ despite favorable spot move
        if (decayPct < -PREMIUM_DECAY_THRESHOLD) {
            
            BigDecimal spotMove = current.getSpot()
                .subtract(advice.getEntrySpot());
            
            // Check if spot moved favorably but premium still decayed
            boolean spotFavorable = false;
            
            if (advice.getDirection() == MarketDirection.SELL) {
                spotFavorable = spotMove.doubleValue() < 0; // Down is good
            } else if (advice.getDirection() == MarketDirection.BUY) {
                spotFavorable = spotMove.doubleValue() > 0; // Up is good
            }
            
            // Divergence detected
            if (spotFavorable) {
                return String.format(
                    "PREMIUM_DECAY_%.0fPCT_DESPITE_FAVORABLE_MOVE", 
                    Math.abs(decayPct * 100)
                );
            }
        }
        
        return null;
    }
}