package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.util.Set;

import com.crumbs.trade.entity.StraddleIntraday;
import com.crumbs.trade.utility.AlertType;

public class AlertConditionChecker {

    // =====================================================
    // MASTER SWITCH — enable/disable any alert type here
    // =====================================================
    private static final Set<AlertType> ENABLED_ALERTS = Set.of(
        AlertType.CE_PE_CROSSOVER,      // existing
        AlertType.PE_CE_CROSSOVER,      // existing
        AlertType.VWAP_DOMINANCE_CE,    // new
        AlertType.VWAP_DOMINANCE_PE     // new
    );

    public static boolean isEnabled(AlertType type) {
        return ENABLED_ALERTS.contains(type);
    }

    // =====================================================
    // EXISTING — CE crossed above PE (UNCHANGED)
    // =====================================================
    public static boolean isCeCrossoverAbove(StraddleIntraday entity) {
        return Boolean.TRUE.equals(entity.getCeCrossoverAbove());
    }

    // =====================================================
    // EXISTING — PE crossed above CE (UNCHANGED)
    // =====================================================
    public static boolean isPeCrossoverAbove(StraddleIntraday entity) {
        return Boolean.TRUE.equals(entity.getPeCrossoverAbove());
    }

    // =====================================================
    // NEW — CE dominant: CE > CE-VWAP AND PE < PE-VWAP
    // =====================================================
    public static boolean isVwapDominanceCe(StraddleIntraday entity) {
        BigDecimal cePrice = entity.getCePrice();
        BigDecimal pePrice = entity.getPePrice();
        BigDecimal ceVwap  = entity.getCeVwap();
        BigDecimal peVwap  = entity.getPeVwap();

        if (cePrice == null || pePrice == null ||
            ceVwap  == null || peVwap  == null) return false;

        if (ceVwap.compareTo(BigDecimal.ZERO) <= 0 ||
            peVwap.compareTo(BigDecimal.ZERO) <= 0) return false;

        return cePrice.compareTo(ceVwap) > 0    // CE Price > CE VWAP
            && pePrice.compareTo(peVwap) < 0    // PE Price < PE VWAP
            && cePrice.compareTo(pePrice) > 0;  // CE > PE
    }

    // =====================================================
    // NEW — PE dominant: PE > PE-VWAP AND CE < CE-VWAP
    // =====================================================
    public static boolean isVwapDominancePe(StraddleIntraday entity) {
        BigDecimal cePrice = entity.getCePrice();
        BigDecimal pePrice = entity.getPePrice();
        BigDecimal ceVwap  = entity.getCeVwap();
        BigDecimal peVwap  = entity.getPeVwap();

        if (cePrice == null || pePrice == null ||
            ceVwap  == null || peVwap  == null) return false;

        if (ceVwap.compareTo(BigDecimal.ZERO) <= 0 ||
            peVwap.compareTo(BigDecimal.ZERO) <= 0) return false;

        return pePrice.compareTo(peVwap) > 0    // PE Price > PE VWAP
            && cePrice.compareTo(ceVwap) < 0    // CE Price < CE VWAP
            && pePrice.compareTo(cePrice) > 0;  // PE > CE  ← ADD THIS
    }
}