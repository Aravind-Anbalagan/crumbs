package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    // Alert Deduplication State
    private final Map<String, LocalDateTime> sentAlertKeys = new HashMap<>();
    private static final int ALERT_COOLDOWN_MINUTES = 5;
    private static final BigDecimal MIN_DOMINANCE_GAP = BigDecimal.valueOf(2);

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

        // ── CE–PE Crossover ─────────────────────────────────────
        if (isCeCrossoverAbove(entity)) {
            sendTelegramAlert(entity, AlertType.CE_PE_CROSSOVER);
        }
        // ── PE–CE Crossover ─────────────────────────────────────
        if (isPeCrossoverAbove(entity)) {
            sendTelegramAlert(entity, AlertType.PE_CE_CROSSOVER);
        }
        // ── VWAP Dominance CE ───────────────────────────────────
        if (isVwapDominanceCe(entity)) {
            sendTelegramAlert(entity, AlertType.VWAP_DOMINANCE_CE);
        }
        // ── VWAP Dominance PE ───────────────────────────────────
        if (isVwapDominancePe(entity)) {
            sendTelegramAlert(entity, AlertType.VWAP_DOMINANCE_PE);
        }
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
    // TELEGRAM DISPATCHER & DEDUPLICATION
    // =========================================================================

    private void sendTelegramAlert(StraddleIntraday entity, AlertType alertType) {
        try {
            if (isAlertAlreadySent(entity.getStrike(), alertType)) return;

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
        } catch (Exception ex) {
            logger.error("Telegram alert failed [{}] for {} {}", alertType, entity.getName(), entity.getStrike(), ex);
        }
    }

    private boolean isAlertAlreadySent(BigDecimal strike, AlertType alertType) {
        if (strike == null || alertType == null) return true;
        String key = strike.toPlainString() + "_" + alertType.name();
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        LocalDateTime lastSent = sentAlertKeys.get(key);

        if (lastSent != null) {
            long minutesSinceLastSent = java.time.Duration.between(lastSent, now).toMinutes();
            if (minutesSinceLastSent < ALERT_COOLDOWN_MINUTES) {
                return true; 
            }
        }
        sentAlertKeys.put(key, now);
        return false;
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