package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.crumbs.trade.dto.PressureInsightDTO;
import com.crumbs.trade.entity.StraddleIntraday;
import com.crumbs.trade.utility.MarketDirection;
import com.crumbs.trade.utility.PressureZone;

@Service
public class MarketPressureService {

    public PressureInsightDTO calculate(StraddleIntraday row) {

        PressureInsightDTO dto = new PressureInsightDTO();
        List<String> reasons = new ArrayList<>();
        int pressure = 0;

        // -------- Premium
        double premiumDelta =
                nz(row.getCombinedPremium())
                        .subtract(nz(row.getCombinedOpenPrice()))
                        .doubleValue();

        if (premiumDelta > 0) {
            pressure += 30;
            reasons.add("Premium expanding");
        } else if (premiumDelta > -10) {
            pressure += 10;
            reasons.add("Premium decay slowing");
        }

        // -------- OI
        double oiRatio =
                nz(row.getCeOi())
                        .subtract(nz(row.getPeOi()))
                        .abs()
                        .divide(
                            nz(row.getCeOi()).add(nz(row.getPeOi())),
                            4,
                            RoundingMode.HALF_UP
                        ).doubleValue();

        if (oiRatio > 0.40) {
            pressure += 30;
            reasons.add("Strong OI imbalance");
        } else if (oiRatio > 0.25) {
            pressure += 15;
            reasons.add("Mild OI imbalance");
        }

        // -------- Price
        double spotDeviation =
                nz(row.getSpot())
                        .subtract(nz(row.getStrike()))
                        .abs()
                        .divide(nz(row.getSpot()), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                        .doubleValue();

        if (spotDeviation > 0.60) {
            pressure += 25;
            reasons.add("Spot moved far from ATM");
        } else if (spotDeviation > 0.35) {
            pressure += 15;
            reasons.add("Spot testing range boundary");
        }

        PressureZone zone =
                pressure >= 60 ? PressureZone.CRITICAL :
                pressure >= 45 ? PressureZone.HIGH :
                pressure >= 25 ? PressureZone.MEDIUM :
                                 PressureZone.LOW;

        dto.setPressure(pressure);
        dto.setZone(zone);
        dto.setReasons(reasons);
        dto.setPremiumDelta(premiumDelta);
        dto.setOiRatio(oiRatio);
        dto.setSpotDeviationPct(spotDeviation);

        return dto;
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
    
    public PressureInsightDTO calculateFromSnapshot(
            List<StraddleIntraday> snapshot) {

        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }

        // 1️⃣ Identify ATM
        BigDecimal spot = snapshot.get(0).getSpot();

        StraddleIntraday atm = snapshot.stream()
                .min((a, b) ->
                    a.getStrike()
                     .subtract(spot).abs()
                     .compareTo(
                        b.getStrike().subtract(spot).abs()
                     )
                )
                .orElse(snapshot.get(0));

        BigDecimal atmStrike = atm.getStrike();

        // 2️⃣ Filter ATM ± 2 strikes
        List<StraddleIntraday> atmBand =
                snapshot.stream()
                    .filter(r ->
                        r.getStrike()
                         .subtract(atmStrike).abs()
                         .compareTo(new BigDecimal("100")) <= 0
                    )
                    .toList();

        // 3️⃣ Calculate pressure per strike
        PressureInsightDTO worst = null;

        for (StraddleIntraday row : atmBand) {

            PressureInsightDTO p = calculate(row);

            if (worst == null ||
                p.getPressure() > worst.getPressure()) {
                worst = p;
            }
        }

        return worst;
    }
    
    public MarketDirection determineMarketDirection(
            List<StraddleIntraday> snapshot) {

        BigDecimal ceOi = BigDecimal.ZERO;
        BigDecimal peOi = BigDecimal.ZERO;

        BigDecimal ceOiChg = BigDecimal.ZERO;
        BigDecimal peOiChg = BigDecimal.ZERO;

        for (StraddleIntraday row : snapshot) {

            if (row.getCeOi() != null) ceOi = ceOi.add(row.getCeOi());
            if (row.getPeOi() != null) peOi = peOi.add(row.getPeOi());

            // OPTIONAL (if you later store OI change)
            // ceOiChg = ceOiChg.add(row.getCeOiChange());
            // peOiChg = peOiChg.add(row.getPeOiChange());
        }

        /*
         * Human logic:
         * - More PE OI → Put writing → Upside support → BUY
         * - More CE OI → Call writing → Downside cap → SELL
         */

        if (peOi.compareTo(ceOi.multiply(new BigDecimal("1.10"))) > 0) {
            return MarketDirection.BUY;
        }

        if (ceOi.compareTo(peOi.multiply(new BigDecimal("1.10"))) > 0) {
            return MarketDirection.SELL;
        }

        return MarketDirection.NEUTRAL;
    }
    
    // ---------------------------------------------
    // 🔥 PRESSURE STABILITY MEMORY (per symbol)
    // ---------------------------------------------
    private static class PressureState {
        PressureZone lastZone;
        LocalDateTime lastSeen;
    }

    private final Map<String, PressureState> pressureMemory =
            new ConcurrentHashMap<>();

    /**
     * Returns true ONLY if pressure zone is stable
     * for 2 consecutive scheduler cycles.
     */
    public boolean isPressureStable(
            String symbol,
            PressureInsightDTO current) {

        if (current == null || current.getZone() == null) {
            return false;
        }

        PressureState state =
                pressureMemory.computeIfAbsent(
                        symbol, k -> new PressureState());

        LocalDateTime now = LocalDateTime.now();

        // First observation
        if (state.lastZone == null) {
            state.lastZone = current.getZone();
            state.lastSeen = now;
            return false;
        }

        // Zone changed → reset memory
        if (state.lastZone != current.getZone()) {
            state.lastZone = current.getZone();
            state.lastSeen = now;
            return false;
        }

        // Same zone seen again → stable
        return true;
    }


}
