package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.crumbs.trade.dto.StraddlePremiumDto;
import com.crumbs.trade.entity.StraddleIntraday;
import com.crumbs.trade.repo.StraddleIntradayRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.utility.AlertType;
import com.crumbs.trade.utility.ConditionalLogger;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StraddleAlertService {

    private static final Logger baseLogger = LoggerFactory.getLogger(StraddleAlertService.class);
    private final ConditionalLogger logger = new ConditionalLogger(baseLogger);

    private final StraddleIntradayRepo straddleIntradayRepo;
    private final StrategyRepo strategyRepo;
    private final TelegramService telegramService;

    // Alert Deduplication State (Thread-safe for multi-threaded tick processing)
    private static final BigDecimal MIN_DOMINANCE_GAP = BigDecimal.valueOf(2);
    private final Map<String, Boolean> triggeredAlerts = new ConcurrentHashMap<>();
    
    public void detectCrossoverEvent(StraddlePremiumDto dto, String name, LocalDateTime currentTs) {
        dto.setCeCrossoverAbove(false);
        dto.setPeCrossoverAbove(false);

        List<StraddleIntraday> lastTwo = straddleIntradayRepo.findLastTwo(name, dto.getStrikePrice(), PageRequest.of(0, 2));
        if (lastTwo.size() < 2) return;

        StraddleIntraday prev = lastTwo.get(0);      
        StraddleIntraday beforePrev = lastTwo.get(1); 

        long gapSeconds = Math.abs(java.time.Duration.between(prev.getTimestamp(), currentTs).getSeconds());
        if (gapSeconds > 120) return; 

        BigDecimal p2Ce = beforePrev.getCePrice(); 
        BigDecimal p2Pe = beforePrev.getPePrice(); 
        BigDecimal p1Ce = prev.getCePrice();       
        BigDecimal p1Pe = prev.getPePrice();       
        BigDecimal currCe = dto.getCePrice();      
        BigDecimal currPe = dto.getPePrice();      

        if (p2Ce == null || p2Pe == null || currCe == null || currPe == null) return;

        BigDecimal dominanceGap = currCe.subtract(currPe).abs();
        if (dominanceGap.compareTo(MIN_DOMINANCE_GAP) < 0) return;

        boolean ceCrossedAbove = p1Ce.compareTo(p1Pe) <= 0 && currCe.compareTo(currPe) > 0;
        boolean peCrossedAbove = p1Pe.compareTo(p1Ce) <= 0 && currPe.compareTo(currCe) > 0;

        BigDecimal currCeVwap = dto.getCeVwap();
        BigDecimal currPeVwap = dto.getPeVwap();
        if (currCeVwap == null || currPeVwap == null) return;

        if (ceCrossedAbove && currCe.compareTo(currCeVwap) > 0) {
            dto.setCeCrossoverAbove(true);
            logger.info("🚀 CE Crossover Detected for {} at Strike {}", name, dto.getStrikePrice());
        } else if (peCrossedAbove && currPe.compareTo(currPeVwap) > 0) {
            dto.setPeCrossoverAbove(true);
            logger.info("🚀 PE Crossover Detected for {} at Strike {}", name, dto.getStrikePrice());
        }
    }

    public void checkAndSendAlerts(StraddleIntraday entity) {
        if (entity == null) return;

        // Helper to process alerts
        processAlert(entity, AlertType.CE_PE_CROSSOVER, isCeCrossoverAbove(entity));
        processAlert(entity, AlertType.PE_CE_CROSSOVER, isPeCrossoverAbove(entity));
        processAlert(entity, AlertType.VWAP_DOMINANCE_CE, isVwapDominanceCe(entity));
        processAlert(entity, AlertType.VWAP_DOMINANCE_PE, isVwapDominancePe(entity));
    }

    private void processAlert(StraddleIntraday entity, AlertType type, boolean conditionMet) {
        String alertKey = entity.getStrike().toPlainString() + "_" + type.name();
        
        if (conditionMet) {
            // Only send if we haven't triggered this specific alert for this strike yet
            if (!triggeredAlerts.getOrDefault(alertKey, false)) {
                boolean sent = sendTelegramAlert(entity, type);
                if (sent) {
                    triggeredAlerts.put(alertKey, true); // Lock only if successfully sent
                }
            }
        }
    }

    // Call this at 9:00 AM in your scheduler to clear the memory
    public void resetTriggeredAlerts() {
        triggeredAlerts.clear();
        logger.info("Alert triggers reset for new trading day.");
    }

    // =========================================================================
    // NATIVE CONDITION EVALUATORS (Self-contained, no external utility imports)
    // =========================================================================

    private boolean isCeCrossoverAbove(StraddleIntraday entity) {
        return Boolean.TRUE.equals(entity.getCeCrossoverAbove());
    }

    private boolean isPeCrossoverAbove(StraddleIntraday entity) {
        return Boolean.TRUE.equals(entity.getPeCrossoverAbove());
    }

    private boolean isVwapDominanceCe(StraddleIntraday entity) {
        if (hasMissingVwapOrPrice(entity)) return false;
        return entity.getCePrice().compareTo(entity.getCeVwap()) > 0
            && entity.getPePrice().compareTo(entity.getPeVwap()) < 0
            && entity.getCombinedPremium().compareTo(entity.getCombinedVwap()) > 0;
    }

    private boolean isVwapDominancePe(StraddleIntraday entity) {
        if (hasMissingVwapOrPrice(entity)) return false;
        return entity.getPePrice().compareTo(entity.getPeVwap()) > 0
            && entity.getCePrice().compareTo(entity.getCeVwap()) < 0
            && entity.getCombinedPremium().compareTo(entity.getCombinedVwap()) > 0;
    }

    private boolean hasMissingVwapOrPrice(StraddleIntraday entity) {
        return entity.getCePrice() == null || entity.getPePrice() == null
            || entity.getCeVwap() == null || entity.getPeVwap() == null
            || entity.getCombinedPremium() == null || entity.getCombinedVwap() == null;
    }

    // =========================================================================
    // TELEGRAM DISPATCHER
    // =========================================================================

    private boolean sendTelegramAlert(StraddleIntraday entity, AlertType alertType) {
        try {
            String message = buildTelegramMessage(entity, alertType);
            boolean sent = telegramService.sendMessage(message);

            if (sent) {
                logger.info("✅ Alert sent [{}] for {} strike {}", alertType, entity.getName(), entity.getStrike());
            }

            String strategyName = (alertType == AlertType.CE_PE_CROSSOVER || alertType == AlertType.PE_CE_CROSSOVER) 
                ? "CROSSOVER" : "VWAP_DOMINANCE";

            telegramService.saveAlertIfEnabled(
                strategyName,
                entity.getName(),               
                message,
                alertType.name(),               
                sent,
                entity.getStrike() != null ? entity.getStrike().intValue() : null,
                entity.getCePrice() != null ? entity.getCePrice().doubleValue() : null,
                entity.getPePrice() != null ? entity.getPePrice().doubleValue() : null,
                entity.getCeVwap()  != null ? entity.getCeVwap().doubleValue()  : null,
                entity.getPeVwap()  != null ? entity.getPeVwap().doubleValue()  : null
            );
            return sent;
        } catch (Exception ex) {
            logger.error("Telegram alert failed [{}] for {} {}", alertType, entity.getName(), entity.getStrike(), ex);
            return false;
        }
    }

    private String buildTelegramMessage(StraddleIntraday entity, AlertType alertType) {
        return switch (alertType) {
            case CE_PE_CROSSOVER -> String.format("""
                🚨 CE–PE Crossover Signal 🚨
                📌 Symbol  : %s
                📌 Strike  : %s
                ⏰ Time    : %s
                🟢 CE crossed ABOVE PE
                💰 CE Price : %.2f
                💰 PE Price : %.2f
                📊 Combined : %.2f
                ⚠️ Event-based crossover (one-time)
                """, entity.getName(), entity.getStrike(), entity.getTimestamp(), entity.getCePrice(), entity.getPePrice(), entity.getCombinedPremium());

            case PE_CE_CROSSOVER -> String.format("""
                🚨 PE–CE Crossover Signal 🚨
                📌 Symbol  : %s
                📌 Strike  : %s
                ⏰ Time    : %s
                🔴 PE crossed ABOVE CE
                💰 CE Price : %.2f
                💰 PE Price : %.2f
                📊 Combined : %.2f
                ⚠️ Event-based crossover (one-time)
                """, entity.getName(), entity.getStrike(), entity.getTimestamp(), entity.getCePrice(), entity.getPePrice(), entity.getCombinedPremium());

            case VWAP_DOMINANCE_CE -> String.format("""
                📊 VWAP Dominance Signal 📊
                📌 Symbol  : %s
                📌 Strike  : %s
                ⏰ Time    : %s
                🟢 CE is DOMINANT (CE > CE-VWAP, PE < PE-VWAP)
                💰 CE Price : %.2f  |  CE VWAP : %.2f
                💰 PE Price : %.2f  |  PE VWAP : %.2f
                📊 Combined : %.2f
                """, entity.getName(), entity.getStrike(), entity.getTimestamp(), entity.getCePrice(), entity.getCeVwap(), entity.getPePrice(), entity.getPeVwap(), entity.getCombinedPremium());

            case VWAP_DOMINANCE_PE -> String.format("""
                📊 VWAP Dominance Signal 📊
                📌 Symbol  : %s
                📌 Strike  : %s
                ⏰ Time    : %s
                🔴 PE is DOMINANT (PE > PE-VWAP, CE < CE-VWAP)
                💰 CE Price : %.2f  |  CE VWAP : %.2f
                💰 PE Price : %.2f  |  PE VWAP : %.2f
                📊 Combined : %.2f
                """, entity.getName(), entity.getStrike(), entity.getTimestamp(), entity.getCePrice(), entity.getCeVwap(), entity.getPePrice(), entity.getPeVwap(), entity.getCombinedPremium());
        };
    }
}