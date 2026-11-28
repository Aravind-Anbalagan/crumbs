package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.crumbs.trade.dto.InstrumentTrendDto;
import com.crumbs.trade.dto.StrikeBreakdownDto;
import com.crumbs.trade.dto.TrendChartResponse;
import com.crumbs.trade.dto.TrendPointDto;
import com.crumbs.trade.dto.TrendState;
import com.crumbs.trade.dto.VolRegime;
import com.crumbs.trade.entity.StraddleIntraday;
import com.crumbs.trade.repo.StraddleIntradayRepo;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StraddleTrendService {

    private static final Logger log = LoggerFactory.getLogger(StraddleTrendService.class);

    // =========================================================================
    // TREND CONFIGURATION (Edit here or override per symbol at runtime)
    // =========================================================================
    private volatile int defaultStrikeRadius   = 3;     // ±3 = 7 strikes (±2 = 5, ±4 = 9)
    private static final int    MIN_RADIUS     = 1;
    private static final int    MAX_RADIUS     = 6;
    private static final double CONFIRM_MINS   = 8.0;   // Minutes flip must hold before confirming
    private static final double WARMUP_MINS    = 10.0;  // Opening session noise filter
    private static final double CONSENSUS_PCT  = 0.70;  // 70% strikes aligned (5/7, 4/5, 7/9)
    private static final double OI_WALL_RATIO  = 1.35;  // Static CE vs PE wall dominance ratio
    private static final double OI_FLOW_RATIO  = 1.25;  // Intraday OI flow dominance ratio
    private static final double VOL_EXP_PCT    = 2.0;   // ATM Comb > VWAP +2% => EXPANSION_RISK
    private static final double VOL_DECAY_PCT  = -1.0;  // ATM Comb < VWAP -1% => THETA_DECAY
    private static final boolean ALERT_ENABLED = true;  // Master Telegram toggle
    private static final String STRATEGY_NAME  = "STRADDLE_TREND";

    // Optional per-symbol strike radius overrides (e.g., NIFTY=3, CRUDEOIL=3, SENSEX=2)
    private final Map<String, Integer> symbolRadiusOverrides = new ConcurrentHashMap<>();

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final StraddleIntradayRepo straddleRepo;
    private final TelegramService telegramService;

    // =========================================================================
    // Runtime Strike Radius Controls
    // =========================================================================

    public int getStrikeRadius(String name) {
        if (name == null) return defaultStrikeRadius;
        return symbolRadiusOverrides.getOrDefault(name.toUpperCase(), defaultStrikeRadius);
    }

    public int updateStrikeRadius(String name, int radius) {
        int clamped = clampRadius(radius);
        if (name == null || name.isBlank() || "ALL".equalsIgnoreCase(name)) {
            this.defaultStrikeRadius = clamped;
            log.info("Updated default strikeRadius to ±{} ({} strikes)", clamped, 2 * clamped + 1);
        } else {
            symbolRadiusOverrides.put(name.toUpperCase(), clamped);
            log.info("Updated strikeRadius for {} to ±{} ({} strikes)", name.toUpperCase(), clamped, 2 * clamped + 1);
        }
        return clamped;
    }

    private int clampRadius(int r) {
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, r));
    }

    // =========================================================================
    // 1. Dynamic Active Instrument Discovery (CRUDEOIL, GOLDM, NIFTY, SENSEX...)
    // =========================================================================

    public List<InstrumentTrendDto> getActiveInstruments(LocalDate requestedDate) {
        LocalDate tradeDate = resolveTradeDate(requestedDate);
        LocalDateTime start = tradeDate.atStartOfDay();
        LocalDateTime end   = tradeDate.atTime(LocalTime.MAX);

        List<Object[]> pairs = straddleRepo.findActiveInstruments(start, end);
        List<InstrumentTrendDto> result = new ArrayList<>();

        for (Object[] pair : pairs) {
            String name   = (String) pair[0];
            String expiry = (String) pair[1];
            TrendChartResponse chart = evaluateSession(name, expiry, tradeDate, null);
            TrendPointDto latest = chart.latest();

            result.add(new InstrumentTrendDto(
                    name,
                    expiry,
                    chart.strikeStep(),
                    chart.strikeRadius(),
                    chart.totalStrikes(),
                    latest != null ? latest.spot() : BigDecimal.ZERO,
                    latest != null ? latest.confirmedState() : TrendState.SIDEWAYS,
                    chart.consolidatedMessage(),
                    latest != null ? latest.timestamp() : null
            ));
        }
        return result;
    }

    // =========================================================================
    // 2. Full Session Evaluation (Used by REST Chart API & 1-Min Scheduler)
    // =========================================================================

    public TrendChartResponse evaluateSession(
            String name,
            String expiry,
            LocalDate requestedDate,
            Integer customRadius) {

        LocalDate tradeDate = resolveTradeDate(requestedDate);
        LocalDateTime start = tradeDate.atStartOfDay();
        LocalDateTime end   = tradeDate.atTime(LocalTime.MAX);

        int radius = (customRadius != null) ? clampRadius(customRadius) : getStrikeRadius(name);
        int expectedTotalStrikes = 2 * radius + 1;

        List<BigDecimal> distinctStrikes = straddleRepo.findDistinctStrikesForSession(name, expiry, start, end);
        BigDecimal strikeStep = detectStrikeStep(distinctStrikes);

        List<StraddleIntraday> rows = straddleRepo.findSessionRows(name, expiry, start, end);
        if (rows.isEmpty()) {
            return new TrendChartResponse(
                    name, expiry, tradeDate, strikeStep, radius, expectedTotalStrikes, CONFIRM_MINS,
                    "No intraday rows available for " + name + " (" + expiry + ")",
                    null, List.of(), List.of()
            );
        }

        // Group rows preserving timestamp order
        Map<LocalDateTime, List<StraddleIntraday>> byTimestamp = rows.stream()
                .collect(Collectors.groupingBy(
                        StraddleIntraday::getTimestamp,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        // Track session-open OI baseline per strike
        Map<BigDecimal, BigDecimal[]> openOiByStrike = new HashMap<>();

        // State machine trackers
        LocalDateTime sessionStart   = null;
        TrendState    confirmedState = TrendState.SIDEWAYS;
        LocalDateTime stateSince     = null;
        TrendState    pendingState   = null;
        LocalDateTime pendingSince   = null;

        List<TrendPointDto> timeline = new ArrayList<>();
        List<StrikeBreakdownDto> latestBreakdown = List.of();

        for (Map.Entry<LocalDateTime, List<StraddleIntraday>> entry : byTimestamp.entrySet()) {
            LocalDateTime ts = entry.getKey();
            List<StraddleIntraday> snap = entry.getValue();
            if (snap.isEmpty() || snap.get(0).getSpot() == null) continue;

            if (sessionStart == null) {
                sessionStart = ts;
                stateSince = ts;
            }

            // Record first seen OI per strike as session baseline
            for (StraddleIntraday r : snap) {
                if (r.getStrike() != null && r.getCeOi() != null && r.getPeOi() != null) {
                    openOiByStrike.putIfAbsent(r.getStrike(), new BigDecimal[]{ r.getCeOi(), r.getPeOi() });
                }
            }

            BigDecimal spot = snap.get(0).getSpot();
            BigDecimal atm  = computeAtmStrike(spot, strikeStep);
            BigDecimal bandSpan = strikeStep.multiply(BigDecimal.valueOf(radius));
            BigDecimal lowStrike  = atm.subtract(bandSpan);
            BigDecimal highStrike = atm.add(bandSpan);

            List<StraddleIntraday> band = snap.stream()
                    .filter(r -> r.getStrike() != null
                            && r.getStrike().compareTo(lowStrike) >= 0
                            && r.getStrike().compareTo(highStrike) <= 0)
                    .sorted(Comparator.comparing(StraddleIntraday::getStrike))
                    .toList();

            List<StraddleIntraday> valid = band.stream()
                    .filter(this::hasValidPriceAndVwap)
                    .toList();

            int minRequiredValid = Math.max(3, radius + 1);
            if (valid.size() < minRequiredValid) {
                continue;
            }

            // ---------------- Pillar 1: Price VWAP Consensus ----------------
            int nVal = valid.size();
            int bearCnt = 0, bullCnt = 0, decayCnt = 0, spikeCnt = 0;
            double synthVwapSum = 0.0;

            for (StraddleIntraday r : valid) {
                boolean ceAbove = r.getCePrice().compareTo(r.getCeVwap()) > 0;
                boolean peAbove = r.getPePrice().compareTo(r.getPeVwap()) > 0;

                if (!ceAbove && peAbove) bearCnt++;
                else if (ceAbove && !peAbove) bullCnt++;
                else if (!ceAbove && !peAbove) decayCnt++;
                else spikeCnt++;

                // Synthetic VWAP = strike + ceVwap - peVwap
                synthVwapSum += r.getStrike().doubleValue()
                        + r.getCeVwap().doubleValue()
                        - r.getPeVwap().doubleValue();
            }

            BigDecimal syntheticVwap = BigDecimal.valueOf(synthVwapSum / nVal)
                    .setScale(2, RoundingMode.HALF_UP);

            int req = (int) Math.ceil(CONSENSUS_PCT * nVal);
            String priceSignal;
            int alignedStrikes;
            if (bearCnt >= req) {
                priceSignal = "BEARISH";
                alignedStrikes = bearCnt;
            } else if (bullCnt >= req) {
                priceSignal = "BULLISH";
                alignedStrikes = bullCnt;
            } else if (decayCnt >= req) {
                priceSignal = "DECAY";
                alignedStrikes = decayCnt;
            } else if (spikeCnt >= req) {
                priceSignal = "VOL_SPIKE";
                alignedStrikes = spikeCnt;
            } else {
                priceSignal = "SPLIT";
                alignedStrikes = Math.max(bearCnt, bullCnt);
            }

            // ---------------- Pillar 2A: Static OI Wall (Support vs Resistance) ----------------
            BigDecimal peWall = BigDecimal.ZERO; // Support Zone (< ATM): sum(PE - CE)
            BigDecimal ceWall = BigDecimal.ZERO; // Resistance Zone (> ATM): sum(CE - PE)

            for (StraddleIntraday r : band) {
                if (r.getCeOi() == null || r.getPeOi() == null) continue;
                int cmp = r.getStrike().compareTo(atm);
                if (cmp < 0) {
                    peWall = peWall.add(r.getPeOi().subtract(r.getCeOi()));
                } else if (cmp > 0) {
                    ceWall = ceWall.add(r.getCeOi().subtract(r.getPeOi()));
                }
            }

            String oiWallSignal = classifyOiWall(peWall.doubleValue(), ceWall.doubleValue());

            // ---------------- Pillar 2B: Price-Qualified Intraday OI Flow ----------------
            double bearOiFlow = 0.0;
            double bullOiFlow = 0.0;

            for (StraddleIntraday r : valid) {
                BigDecimal[] base = openOiByStrike.get(r.getStrike());
                if (base == null || r.getCeOi() == null || r.getPeOi() == null) continue;

                double dCe = r.getCeOi().subtract(base[0]).doubleValue();
                double dPe = r.getPeOi().subtract(base[1]).doubleValue();
                boolean ceUp = r.getCePrice().compareTo(r.getCeVwap()) > 0;
                boolean peUp = r.getPePrice().compareTo(r.getPeVwap()) > 0;

                // Fresh CE writing (!ceUp & dCe>0) + PE expansion/covering (peUp)
                if (!ceUp && dCe > 0) bearOiFlow += dCe;
                if (peUp)             bearOiFlow += Math.abs(dPe);

                // Fresh PE writing (!peUp & dPe>0) + CE expansion/covering (ceUp)
                if (!peUp && dPe > 0) bullOiFlow += dPe;
                if (ceUp)             bullOiFlow += Math.abs(dCe);
            }

            String oiFlowSignal;
            if ((bearOiFlow + bullOiFlow) > 0 && bearOiFlow > bullOiFlow * OI_FLOW_RATIO) {
                oiFlowSignal = "BEARISH";
            } else if ((bearOiFlow + bullOiFlow) > 0 && bullOiFlow > bearOiFlow * OI_FLOW_RATIO) {
                oiFlowSignal = "BULLISH";
            } else {
                oiFlowSignal = "NEUTRAL";
            }

            // ---------------- Combined Target State ----------------
            TrendState targetState;
            if ("BEARISH".equals(priceSignal)
                    && ("BEARISH".equals(oiWallSignal) || "BEARISH".equals(oiFlowSignal))) {
                targetState = TrendState.BEARISH;
            } else if ("BULLISH".equals(priceSignal)
                    && ("BULLISH".equals(oiWallSignal) || "BULLISH".equals(oiFlowSignal))) {
                targetState = TrendState.BULLISH;
            } else {
                targetState = TrendState.SIDEWAYS;
            }

            // ---------------- Stability State Machine (Warmup + 8m Hold + No Direct Jump) ----------------
            boolean stateChanged = false;
            String transitionLabel = null;
            double pendingMins = 0.0;
            double elapsedSessionMins = Duration.between(sessionStart, ts).toSeconds() / 60.0;

            if (elapsedSessionMins >= WARMUP_MINS) {
                TrendState desired = targetState;
                // Enforce step-through SIDEWAYS (no instant BULLISH <-> BEARISH flip)
                if (confirmedState == TrendState.BEARISH && desired == TrendState.BULLISH) {
                    desired = TrendState.SIDEWAYS;
                } else if (confirmedState == TrendState.BULLISH && desired == TrendState.BEARISH) {
                    desired = TrendState.SIDEWAYS;
                }

                if (desired != confirmedState) {
                    if (pendingState == desired && pendingSince != null) {
                        pendingMins = Duration.between(pendingSince, ts).toSeconds() / 60.0;
                        if (pendingMins >= CONFIRM_MINS) {
                            TrendState prev = confirmedState;
                            confirmedState = desired;
                            stateSince = ts;
                            pendingState = null;
                            pendingSince = null;
                            pendingMins = 0.0;
                            stateChanged = true;
                            transitionLabel = buildTransitionLabel(prev, confirmedState);
                        }
                    } else {
                        pendingState = desired;
                        pendingSince = ts;
                        pendingMins = 0.0;
                    }
                } else {
                    pendingState = null;
                    pendingSince = null;
                    pendingMins = 0.0;
                }
            }

            // ---------------- ATM Combined Premium Volatility Overlay ----------------
            StraddleIntraday atmRow = band.stream()
                    .min(Comparator.comparing(r -> r.getStrike().subtract(spot).abs()))
                    .orElse(valid.get(0));

            double atmCombDiffPct = 0.0;
            if (atmRow.getCombinedPremium() != null
                    && atmRow.getCombinedVwap() != null
                    && atmRow.getCombinedVwap().compareTo(BigDecimal.ZERO) > 0) {
                atmCombDiffPct = atmRow.getCombinedPremium()
                        .subtract(atmRow.getCombinedVwap())
                        .doubleValue() / atmRow.getCombinedVwap().doubleValue() * 100.0;
            }

            VolRegime volRegime = atmCombDiffPct > VOL_EXP_PCT ? VolRegime.EXPANSION_RISK
                    : atmCombDiffPct < VOL_DECAY_PCT ? VolRegime.THETA_DECAY
                    : VolRegime.NEUTRAL_VOL;

            String bannerMsg = buildBannerMessage(
                    confirmedState, stateSince, radius, nVal,
                    bearCnt, bullCnt, priceSignal,
                    ceWall, peWall, oiFlowSignal,
                    pendingState, pendingMins
            );

            // Use ZoneOffset.UTC on IST LocalDateTime so Lightweight Charts renders exact IST HH:mm labels
            long chartEpochSec = ts.toEpochSecond(ZoneOffset.UTC);

            timeline.add(new TrendPointDto(
                    ts,
                    chartEpochSec,
                    ts.format(TIME_FMT),
                    spot,
                    atm,
                    syntheticVwap,
                    confirmedState,
                    pendingState,
                    round1(pendingMins),
                    stateChanged,
                    transitionLabel,
                    volRegime,
                    round2(atmCombDiffPct),
                    alignedStrikes,
                    nVal,
                    priceSignal,
                    oiWallSignal,
                    oiFlowSignal,
                    peWall,
                    ceWall,
                    atmRow.getCePrice(),
                    atmRow.getPePrice(),
                    atmRow.getCeVwap(),
                    atmRow.getPeVwap(),
                    bannerMsg
            ));

            latestBreakdown = buildStrikeBreakdown(band, atm, openOiByStrike);
        }

        TrendPointDto latest = timeline.isEmpty() ? null : timeline.get(timeline.size() - 1);
        String topMessage = latest != null ? latest.consolidatedMessage() : "Waiting for valid strike VWAP data...";

        return new TrendChartResponse(
                name,
                expiry,
                tradeDate,
                strikeStep,
                radius,
                expectedTotalStrikes,
                CONFIRM_MINS,
                topMessage,
                latest,
                timeline,
                latestBreakdown
        );
    }

    // =========================================================================
    // 3. Telegram Flip Alert Builder & Dispatcher (sendToNewChat + Alert Audit)
    // =========================================================================

    public void sendFlipAlert(TrendChartResponse chart, TrendState prevState, TrendPointDto point) {
        if (!ALERT_ENABLED || point == null || prevState == null || prevState == point.confirmedState()) {
            return;
        }

        String label = buildTransitionLabel(prevState, point.confirmedState());
        String icon  = switch (point.confirmedState()) {
            case BULLISH  -> "🟢";
            case BEARISH  -> "🔴";
            case SIDEWAYS -> "⚪";
        };

        String priceLine = switch (point.priceSignal()) {
            case "BEARISH" -> String.format("%d/%d Bearish (CE &lt; VWAP, PE &gt; VWAP)",
                    point.alignedStrikes(), point.totalStrikes());
            case "BULLISH" -> String.format("%d/%d Bullish (CE &gt; VWAP, PE &lt; VWAP)",
                    point.alignedStrikes(), point.totalStrikes());
            case "DECAY"   -> String.format("%d/%d Dual Decay (CE &amp; PE &lt; VWAP)",
                    point.alignedStrikes(), point.totalStrikes());
            default        -> String.format("Split (%d of %d aligned)",
                    point.alignedStrikes(), point.totalStrikes());
        };

        String oiLine = String.format("CE Wall %s vs PE Wall %s | Flow: %s",
                formatCompact(point.ceWall()),
                formatCompact(point.peWall()),
                capitalize(point.oiFlowSignal()));

        String htmlMessage = icon + " <b>" + chart.name() + " (" + chart.expiry() + ") — " + label + "</b>\n"
                + "Spot: <code>" + formatNum(point.spot()) + "</code>"
                + " | ATM: <code>" + formatNum(point.atmStrike()) + "</code>"
                + " | Window: ±" + chart.strikeRadius() + " (" + point.totalStrikes() + " Strikes)\n"
                + "Price: " + priceLine + "\n"
                + "OI: " + oiLine + "\n"
                + "Time: " + point.timeLabel();

        boolean sent = telegramService.sendToNewChat(htmlMessage);

        telegramService.saveAlertIfEnabled(
                STRATEGY_NAME,
                chart.name(),
                htmlMessage,
                point.confirmedState().name(),
                sent,
                point.atmStrike() != null ? point.atmStrike().intValue() : null,
                toDouble(point.atmCePrice()),
                toDouble(point.atmPePrice()),
                toDouble(point.atmCeVwap()),
                toDouble(point.atmPeVwap())
        );

        log.info("Trend flip alert dispatched: {} ({}) {} -> {} [sent={}]",
                chart.name(), chart.expiry(), prevState, point.confirmedState(), sent);
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    public LocalDate resolveTradeDate(LocalDate requestedDate) {
        if (requestedDate != null) return requestedDate;
        LocalDate today = LocalDate.now(IST);
        List<Object[]> todayActive = straddleRepo.findActiveInstruments(
                today.atStartOfDay(), today.atTime(LocalTime.MAX));
        if (!todayActive.isEmpty()) {
            return today;
        }
        return straddleRepo.findLatestTimestamp()
                .map(LocalDateTime::toLocalDate)
                .orElse(today);
    }

    public BigDecimal detectStrikeStep(List<BigDecimal> sortedStrikes) {
        if (sortedStrikes == null || sortedStrikes.size() < 2) {
            return BigDecimal.valueOf(50);
        }
        BigDecimal minDiff = null;
        for (int i = 1; i < sortedStrikes.size(); i++) {
            BigDecimal diff = sortedStrikes.get(i).subtract(sortedStrikes.get(i - 1));
            if (diff.compareTo(BigDecimal.ZERO) > 0
                    && (minDiff == null || diff.compareTo(minDiff) < 0)) {
                minDiff = diff;
            }
        }
        return minDiff != null ? minDiff : BigDecimal.valueOf(50);
    }

    private BigDecimal computeAtmStrike(BigDecimal spot, BigDecimal step) {
        BigDecimal units = spot.divide(step, 0, RoundingMode.HALF_UP);
        return units.multiply(step).stripTrailingZeros();
    }

    private boolean hasValidPriceAndVwap(StraddleIntraday r) {
        return r.getCePrice() != null && r.getCePrice().compareTo(BigDecimal.ZERO) > 0
                && r.getPePrice() != null && r.getPePrice().compareTo(BigDecimal.ZERO) > 0
                && r.getCeVwap() != null && r.getCeVwap().compareTo(BigDecimal.ZERO) > 0
                && r.getPeVwap() != null && r.getPeVwap().compareTo(BigDecimal.ZERO) > 0;
    }

    private String classifyOiWall(double peWall, double ceWall) {
        if (ceWall > 0 && (peWall <= 0 || ceWall > peWall * OI_WALL_RATIO)) {
            return "BEARISH";
        }
        if (peWall > 0 && (ceWall <= 0 || peWall > ceWall * OI_WALL_RATIO)) {
            return "BULLISH";
        }
        return "BALANCED";
    }

    private String buildTransitionLabel(TrendState prev, TrendState next) {
        if (next == TrendState.SIDEWAYS) {
            return prev.name() + " ENDED -> (SIDEWAYS)";
        }
        return next.name() + " STARTED";
    }

    private String buildBannerMessage(
            TrendState state, LocalDateTime since, int radius, int nVal,
            int bearCnt, int bullCnt, String priceSignal,
            BigDecimal ceWall, BigDecimal peWall, String oiFlowSignal,
            TrendState pendingState, double pendingMins) {

        String icon = switch (state) {
            case BULLISH  -> "🟢";
            case BEARISH  -> "🔴";
            case SIDEWAYS -> "⚪";
        };

        String pricePart = switch (priceSignal) {
            case "BEARISH" -> bearCnt + "/" + nVal + " Bearish";
            case "BULLISH" -> bullCnt + "/" + nVal + " Bullish";
            case "DECAY"   -> "Dual Decay";
            default        -> bullCnt + " Bull / " + bearCnt + " Bear of " + nVal;
        };

        StringBuilder sb = new StringBuilder();
        sb.append(icon).append(" ").append(state.name())
                .append(" (since ").append(since != null ? since.format(TIME_FMT) : "--:--").append(")")
                .append(" • ±").append(radius).append(" (").append(nVal).append(" Strikes): ").append(pricePart)
                .append(" • CE Wall ").append(formatCompact(ceWall))
                .append(" vs PE ").append(formatCompact(peWall))
                .append(" • OI Flow: ").append(capitalize(oiFlowSignal));

        if (pendingState != null && pendingMins > 0) {
            sb.append(" • ⏳ Moving -> ").append(pendingState.name())
                    .append(" (").append((int) Math.floor(pendingMins)).append("m / ")
                    .append((int) CONFIRM_MINS).append("m)");
        }
        return sb.toString();
    }

    private List<StrikeBreakdownDto> buildStrikeBreakdown(
            List<StraddleIntraday> band,
            BigDecimal atm,
            Map<BigDecimal, BigDecimal[]> openOiByStrike) {

        List<StrikeBreakdownDto> list = new ArrayList<>();
        for (StraddleIntraday r : band) {
            int cmp = r.getStrike().compareTo(atm);
            String zone = cmp < 0 ? "SUPPORT" : (cmp > 0 ? "RESISTANCE" : "ATM");

            String sig = "N/A";
            if (hasValidPriceAndVwap(r)) {
                boolean ceUp = r.getCePrice().compareTo(r.getCeVwap()) > 0;
                boolean peUp = r.getPePrice().compareTo(r.getPeVwap()) > 0;
                if (!ceUp && peUp) sig = "BEARISH";
                else if (ceUp && !peUp) sig = "BULLISH";
                else if (!ceUp && !peUp) sig = "DECAY";
                else sig = "VOL_SPIKE";
            }

            BigDecimal ceOi = r.getCeOi() != null ? r.getCeOi() : BigDecimal.ZERO;
            BigDecimal peOi = r.getPeOi() != null ? r.getPeOi() : BigDecimal.ZERO;
            BigDecimal[] base = openOiByStrike.get(r.getStrike());
            BigDecimal dCe = base != null ? ceOi.subtract(base[0]) : BigDecimal.ZERO;
            BigDecimal dPe = base != null ? peOi.subtract(base[1]) : BigDecimal.ZERO;

            list.add(new StrikeBreakdownDto(
                    r.getStrike(),
                    zone,
                    r.getCePrice(),
                    r.getCeVwap(),
                    r.getPePrice(),
                    r.getPeVwap(),
                    sig,
                    ceOi,
                    peOi,
                    ceOi.subtract(peOi),
                    dCe,
                    dPe
            ));
        }
        return list;
    }

    private String formatCompact(BigDecimal val) {
        if (val == null) return "0";
        double d = val.doubleValue();
        String sign = d > 0 ? "+" : "";
        double abs = Math.abs(d);
        if (abs >= 1_000_000) return String.format("%s%.2fM", sign, d / 1_000_000.0);
        if (abs >= 1_000)     return String.format("%s%.0fK", sign, d / 1_000.0);
        return String.format("%s%.0f", sign, d);
    }

    private String formatNum(BigDecimal val) {
        if (val == null) return "-";
        return String.format("%,.0f", val.doubleValue());
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return "-";
        return s.charAt(0) + s.substring(1).toLowerCase();
    }

    private Double toDouble(BigDecimal val) {
        return val != null ? val.doubleValue() : null;
    }

    private double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}