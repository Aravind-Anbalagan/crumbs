package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
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
    // TREND CONFIGURATION (Option-Seller Cadence: Start-of-Day + Major Shift)
    // =========================================================================
    public static final int ALL_STRIKES          = 0;     // 0 = Full chain (all stored strikes)
    public static final int MAX_RADIUS           = 10;    // Supports ±1..±10 or 0 (ALL)

    private volatile int defaultStrikeRadius     = 3;     // ±3 (7 liquid strikes) default; 0 (ALL) on demand
    private static final double CONFIRM_MINS     = 15.0;  // Minutes flip must hold before confirming
    private static final double MIN_HOLD_MINS    = 30.0;  // Minimum regime lock before allowing another flip
    private static final double WARMUP_MINS      = 15.0;  // Opening session noise filter
    private static final double CONSENSUS_PCT    = 0.70;  // 70% aligned to ENTER a trend (5/7 or 7/9)
    private static final double EXIT_HOLD_PCT    = 0.45;  // Stay in trend while >= 45% aligned (4/7 or 5/9)
    private static final double OI_WALL_RATIO    = 1.35;  // Static CE vs PE wall dominance ratio
    private static final double OI_FLOW_RATIO    = 1.25;  // Intraday OI flow dominance ratio
    private static final double VOL_EXP_PCT      = 2.0;   // ATM Comb > VWAP +2% => EXPANSION_RISK
    private static final double VOL_DECAY_PCT    = -1.0;  // ATM Comb < VWAP -1% => THETA_DECAY
    private static final BigDecimal MIN_LIQUID_LTP = BigDecimal.valueOf(5.0); // Exclude dead deep-OTM strikes from VWAP vote
    private static final boolean ALERT_ENABLED   = true;  // Master Telegram toggle
    private static final String STRATEGY_NAME    = "STRADDLE_TREND";

    // Optional per-symbol strike radius overrides (0 = ALL, or 1..10)
    private final Map<String, Integer> symbolRadiusOverrides = new ConcurrentHashMap<>();

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final StraddleIntradayRepo straddleRepo;
    private final TelegramService telegramService;

    // =========================================================================
    // Runtime Strike Radius Controls
    // =========================================================================

    public int getStrikeRadius(String name) {
        if (name == null || name.isBlank()) {
            return defaultStrikeRadius;
        }
        return symbolRadiusOverrides.getOrDefault(normalizeSymbol(name), defaultStrikeRadius);
    }

    public int updateStrikeRadius(String name, int radius) {
        int clamped = clampRadius(radius);
        String label = (clamped == ALL_STRIKES)
                ? "ALL strikes"
                : "±" + clamped + " (" + (2 * clamped + 1) + " strikes)";

        if (name == null || name.isBlank() || "ALL".equalsIgnoreCase(name.trim())) {
            this.defaultStrikeRadius = clamped;
            symbolRadiusOverrides.clear();
            log.info("Updated default strikeRadius to {}", label);
        } else {
            String key = normalizeSymbol(name);
            symbolRadiusOverrides.put(key, clamped);
            log.info("Updated strikeRadius for {} to {}", key, label);
        }
        return clamped;
    }

    public int clampRadius(int r) {
        if (r <= ALL_STRIKES) {
            return ALL_STRIKES;
        }
        return Math.min(MAX_RADIUS, r);
    }

    private String normalizeSymbol(String name) {
        return name.trim().toUpperCase();
    }

    // =========================================================================
    // 1. Dynamic Active Instrument Discovery (CRUDEOIL, GOLDM, NIFTY, SENSEX...)
    // =========================================================================

    public List<InstrumentTrendDto> getActiveInstruments(LocalDate requestedDate) {
        LocalDate tradeDate = resolveTradeDate(requestedDate);
        LocalDateTime start = tradeDate.atStartOfDay();
        LocalDateTime end   = tradeDate.atTime(LocalTime.MAX);

        List<Object[]> pairs = straddleRepo.findActiveInstruments(start, end);
        if (pairs == null || pairs.isEmpty()) {
            return List.of();
        }

        List<InstrumentTrendDto> result = new ArrayList<>(pairs.size());
        for (Object[] pair : pairs) {
            if (pair == null || pair.length < 2 || pair[0] == null || pair[1] == null) {
                continue;
            }
            String name   = pair[0].toString().trim();
            String expiry = pair[1].toString().trim();
            TrendChartResponse chart = evaluateSession(name, expiry, tradeDate, null);
            TrendPointDto latest = chart.latest();

            result.add(new InstrumentTrendDto(
                    name,
                    expiry,
                    chart.strikeStep(),
                    chart.strikeRadius(),
                    chart.totalStrikes(),
                    latest != null && latest.spot() != null ? latest.spot() : BigDecimal.ZERO,
                    latest != null && latest.confirmedState() != null ? latest.confirmedState() : TrendState.SIDEWAYS,
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

        List<BigDecimal> distinctStrikes = straddleRepo.findDistinctStrikesForSession(name, expiry, start, end);
        BigDecimal strikeStep = detectStrikeStep(distinctStrikes);
        int expectedTotalStrikes = (radius == ALL_STRIKES)
                ? (distinctStrikes != null ? distinctStrikes.size() : 0)
                : (2 * radius + 1);

        List<StraddleIntraday> rows = straddleRepo.findSessionRows(name, expiry, start, end);
        if (rows == null || rows.isEmpty()) {
            return new TrendChartResponse(
                    name, expiry, tradeDate, strikeStep, radius, expectedTotalStrikes, CONFIRM_MINS,
                    "No intraday rows available for " + name + " (" + expiry + ")",
                    null, List.of(), List.of()
            );
        }

        // Filter out incomplete records and group rows preserving timestamp order
        Map<LocalDateTime, List<StraddleIntraday>> byTimestamp = rows.stream()
                .filter(r -> r != null && r.getTimestamp() != null)
                .sorted(Comparator.comparing(StraddleIntraday::getTimestamp))
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
            if (snap == null || snap.isEmpty()) {
                continue;
            }

            BigDecimal spot = snap.stream()
                    .map(StraddleIntraday::getSpot)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            if (spot == null) {
                continue;
            }

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

            BigDecimal atm = computeAtmStrike(spot, strikeStep);

            // Full chain when radius == 0 (ALL), otherwise ATM ± radius band
            List<StraddleIntraday> band;
            if (radius == ALL_STRIKES) {
                band = snap.stream()
                        .filter(r -> r.getStrike() != null)
                        .sorted(Comparator.comparing(StraddleIntraday::getStrike))
                        .toList();
            } else {
                BigDecimal bandSpan   = strikeStep.multiply(BigDecimal.valueOf(radius));
                BigDecimal lowStrike  = atm.subtract(bandSpan);
                BigDecimal highStrike = atm.add(bandSpan);
                band = snap.stream()
                        .filter(r -> r.getStrike() != null
                                && r.getStrike().compareTo(lowStrike) >= 0
                                && r.getStrike().compareTo(highStrike) <= 0)
                        .sorted(Comparator.comparing(StraddleIntraday::getStrike))
                        .toList();
            }

            List<StraddleIntraday> valid = band.stream()
                    .filter(this::hasValidPriceAndVwap)
                    .toList();

            int minRequiredValid = (radius == ALL_STRIKES)
                    ? 3
                    : Math.min(Math.max(3, radius + 1), Math.max(3, (band.size() / 2) + 1));
            if (valid.size() < minRequiredValid) {
                continue;
            }

            // ---------------- Pillar 1: Price VWAP Consensus ----------------
            int nVal = valid.size();
            int bearCnt = 0, bullCnt = 0, decayCnt = 0, spikeCnt = 0;
            BigDecimal synthVwapSum = BigDecimal.ZERO;

            for (StraddleIntraday r : valid) {
                boolean ceAbove = r.getCePrice().compareTo(r.getCeVwap()) > 0;
                boolean peAbove = r.getPePrice().compareTo(r.getPeVwap()) > 0;

                if (!ceAbove && peAbove) {
                    bearCnt++;
                } else if (ceAbove && !peAbove) {
                    bullCnt++;
                } else if (!ceAbove && !peAbove) {
                    decayCnt++;
                } else {
                    spikeCnt++;
                }

                // Synthetic VWAP = strike + ceVwap - peVwap
                BigDecimal synth = r.getStrike()
                        .add(r.getCeVwap())
                        .subtract(r.getPeVwap());
                synthVwapSum = synthVwapSum.add(synth);
            }

            BigDecimal syntheticVwap = synthVwapSum
                    .divide(BigDecimal.valueOf(nVal), 2, RoundingMode.HALF_UP);

            int req = Math.max(1, (int) Math.ceil(CONSENSUS_PCT * nVal));
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
                if (r.getCeOi() == null || r.getPeOi() == null) {
                    continue;
                }
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
                if (base == null || r.getCeOi() == null || r.getPeOi() == null) {
                    continue;
                }

                double dCe = r.getCeOi().subtract(base[0]).doubleValue();
                double dPe = r.getPeOi().subtract(base[1]).doubleValue();
                boolean ceUp = r.getCePrice().compareTo(r.getCeVwap()) > 0;
                boolean peUp = r.getPePrice().compareTo(r.getPeVwap()) > 0;

                // Fresh CE writing (!ceUp & dCe>0) + PE expansion/covering (peUp)
                if (!ceUp && dCe > 0) {
                    bearOiFlow += dCe;
                }
                if (peUp) {
                    bearOiFlow += Math.abs(dPe);
                }

                // Fresh PE writing (!peUp & dPe>0) + CE expansion/covering (ceUp)
                if (!peUp && dPe > 0) {
                    bullOiFlow += dPe;
                }
                if (ceUp) {
                    bullOiFlow += Math.abs(dCe);
                }
            }

            double totalOiFlow = bearOiFlow + bullOiFlow;
            String oiFlowSignal;
            if (totalOiFlow > 0 && bearOiFlow > bullOiFlow * OI_FLOW_RATIO) {
                oiFlowSignal = "BEARISH";
            } else if (totalOiFlow > 0 && bullOiFlow > bearOiFlow * OI_FLOW_RATIO) {
                oiFlowSignal = "BULLISH";
            } else {
                oiFlowSignal = "NEUTRAL";
            }

            // ---------------- Combined Target State (With Hysteresis) ----------------
            int holdReq = Math.max(1, (int) Math.ceil(EXIT_HOLD_PCT * nVal));

            TrendState targetState;
            if (confirmedState == TrendState.BEARISH) {
                // Stay BEARISH while >= 45% of liquid strikes remain bearish and no bullish flip
                boolean stillBearish = bearCnt >= holdReq && !"BULLISH".equals(priceSignal);
                targetState = stillBearish ? TrendState.BEARISH : TrendState.SIDEWAYS;
            } else if (confirmedState == TrendState.BULLISH) {
                // Stay BULLISH while >= 45% of liquid strikes remain bullish and no bearish flip
                boolean stillBullish = bullCnt >= holdReq && !"BEARISH".equals(priceSignal);
                targetState = stillBullish ? TrendState.BULLISH : TrendState.SIDEWAYS;
            } else {
                // From SIDEWAYS, require full 70% VWAP consensus + OI confirmation to start a trend
                if ("BEARISH".equals(priceSignal)
                        && ("BEARISH".equals(oiWallSignal) || "BEARISH".equals(oiFlowSignal))) {
                    targetState = TrendState.BEARISH;
                } else if ("BULLISH".equals(priceSignal)
                        && ("BULLISH".equals(oiWallSignal) || "BULLISH".equals(oiFlowSignal))) {
                    targetState = TrendState.BULLISH;
                } else {
                    targetState = TrendState.SIDEWAYS;
                }
            }

            // ---------------- Stability State Machine (Warmup + 30m Min Hold + 15m Confirm) ----------------
            boolean stateChanged = false;
            String transitionLabel = null;
            double pendingMins = 0.0;
            double elapsedSessionMins = Duration.between(sessionStart, ts).toSeconds() / 60.0;
            double elapsedInStateMins = (stateSince != null)
                    ? Duration.between(stateSince, ts).toSeconds() / 60.0
                    : 0.0;

            if (elapsedSessionMins >= WARMUP_MINS) {
                TrendState desired = targetState;
                // Enforce step-through SIDEWAYS (no instant BULLISH <-> BEARISH jump)
                if ((confirmedState == TrendState.BEARISH && desired == TrendState.BULLISH)
                        || (confirmedState == TrendState.BULLISH && desired == TrendState.BEARISH)) {
                    desired = TrendState.SIDEWAYS;
                }

                // Allow opening call out of initial SIDEWAYS right after warmup;
                // enforce MIN_HOLD_MINS after any confirmed transition
                boolean holdSatisfied = (confirmedState == TrendState.SIDEWAYS && Objects.equals(stateSince, sessionStart))
                        || elapsedInStateMins >= MIN_HOLD_MINS;

                if (desired != confirmedState && holdSatisfied) {
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
                double combVwap = atmRow.getCombinedVwap().doubleValue();
                atmCombDiffPct = atmRow.getCombinedPremium()
                        .subtract(atmRow.getCombinedVwap())
                        .doubleValue() / combVwap * 100.0;
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

            // Preserve local IST wall-clock time as epoch seconds for chart libraries expecting local timestamps
            long chartEpochSec = ts.atZone(IST).toLocalDateTime().toEpochSecond(java.time.ZoneOffset.UTC);

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
        int resolvedTotalStrikes = (radius == ALL_STRIKES)
                ? (latest != null ? latest.totalStrikes() : expectedTotalStrikes)
                : expectedTotalStrikes;

        return new TrendChartResponse(
                name,
                expiry,
                tradeDate,
                strikeStep,
                radius,
                resolvedTotalStrikes,
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
        if (!ALERT_ENABLED
                || chart == null
                || point == null
                || prevState == null
                || point.confirmedState() == null
                || prevState == point.confirmedState()) {
            return;
        }

        String label = buildTransitionLabel(prevState, point.confirmedState());
        String icon = switch (point.confirmedState()) {
            case BULLISH  -> "🟢";
            case BEARISH  -> "🔴";
            case SIDEWAYS -> "⚪";
        };

        String signal = point.priceSignal() != null ? point.priceSignal() : "SPLIT";
        String priceLine = switch (signal) {
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

        String windowLabel = formatWindowLabel(chart.strikeRadius(), point.totalStrikes());

        String htmlMessage = icon + " <b>" + escapeHtml(chart.name()) + " (" + escapeHtml(chart.expiry()) + ") — " + escapeHtml(label) + "</b>\n"
                + "Spot: <code>" + formatNum(point.spot()) + "</code>"
                + " | ATM: <code>" + formatNum(point.atmStrike()) + "</code>"
                + " | Window: " + escapeHtml(windowLabel) + "\n"
                + "Price: " + priceLine + "\n"
                + "OI: " + oiLine + "\n"
                + "Time: " + (point.timeLabel() != null ? escapeHtml(point.timeLabel()) : "--:--");

        boolean sent = false;
        try {
            sent = telegramService.sendToNewChat(htmlMessage);
        } catch (Exception ex) {
            log.error("Failed to send Telegram flip alert for {} ({}): {}",
                    chart.name(), chart.expiry(), ex.getMessage(), ex);
        }

        try {
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
        } catch (Exception ex) {
            log.error("Failed to persist alert audit for {} ({}): {}",
                    chart.name(), chart.expiry(), ex.getMessage(), ex);
        }

        log.info("Trend flip alert dispatched: {} ({}) {} -> {} [sent={}]",
                chart.name(), chart.expiry(), prevState, point.confirmedState(), sent);
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    public LocalDate resolveTradeDate(LocalDate requestedDate) {
        if (requestedDate != null) {
            return requestedDate;
        }
        LocalDate today = LocalDate.now(IST);
        List<Object[]> todayActive = straddleRepo.findActiveInstruments(
                today.atStartOfDay(), today.atTime(LocalTime.MAX));
        if (todayActive != null && !todayActive.isEmpty()) {
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
        TreeSet<BigDecimal> uniqueSorted = sortedStrikes.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
        if (uniqueSorted.size() < 2) {
            return BigDecimal.valueOf(50);
        }

        List<BigDecimal> strikes = new ArrayList<>(uniqueSorted);
        BigDecimal minDiff = null;
        for (int i = 1; i < strikes.size(); i++) {
            BigDecimal diff = strikes.get(i).subtract(strikes.get(i - 1));
            if (diff.compareTo(BigDecimal.ZERO) > 0
                    && (minDiff == null || diff.compareTo(minDiff) < 0)) {
                minDiff = diff;
            }
        }
        return minDiff != null ? minDiff : BigDecimal.valueOf(50);
    }

    private BigDecimal computeAtmStrike(BigDecimal spot, BigDecimal step) {
        BigDecimal safeStep = (step != null && step.compareTo(BigDecimal.ZERO) > 0)
                ? step
                : BigDecimal.valueOf(50);
        BigDecimal units = spot.divide(safeStep, 0, RoundingMode.HALF_UP);
        return units.multiply(safeStep);
    }

    private boolean hasValidPriceAndVwap(StraddleIntraday r) {
        return r != null
                && r.getStrike() != null
                && r.getCePrice() != null && r.getCePrice().compareTo(MIN_LIQUID_LTP) >= 0
                && r.getPePrice() != null && r.getPePrice().compareTo(MIN_LIQUID_LTP) >= 0
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

    private String formatWindowLabel(int radius, int strikeCount) {
        return (radius == ALL_STRIKES)
                ? "ALL (" + strikeCount + " Strikes)"
                : "±" + radius + " (" + strikeCount + " Strikes)";
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
                .append(" • ").append(formatWindowLabel(radius, nVal)).append(": ").append(pricePart)
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

        List<StrikeBreakdownDto> list = new ArrayList<>(band.size());
        for (StraddleIntraday r : band) {
            if (r.getStrike() == null) {
                continue;
            }
            int cmp = r.getStrike().compareTo(atm);
            String zone = cmp < 0 ? "SUPPORT" : (cmp > 0 ? "RESISTANCE" : "ATM");

            String sig = "N/A";
            if (hasValidPriceAndVwap(r)) {
                boolean ceUp = r.getCePrice().compareTo(r.getCeVwap()) > 0;
                boolean peUp = r.getPePrice().compareTo(r.getPeVwap()) > 0;
                if (!ceUp && peUp) {
                    sig = "BEARISH";
                } else if (ceUp && !peUp) {
                    sig = "BULLISH";
                } else if (!ceUp && !peUp) {
                    sig = "DECAY";
                } else {
                    sig = "VOL_SPIKE";
                }
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

    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String formatCompact(BigDecimal val) {
        if (val == null) {
            return "0";
        }
        double d = val.doubleValue();
        String sign = d > 0 ? "+" : "";
        double abs = Math.abs(d);
        if (abs >= 1_000_000) {
            return String.format("%s%.2fM", sign, d / 1_000_000.0);
        }
        if (abs >= 1_000) {
            return String.format("%s%.0fK", sign, d / 1_000.0);
        }
        return String.format("%s%.0f", sign, d);
    }

    private String formatNum(BigDecimal val) {
        if (val == null) {
            return "-";
        }
        return String.format("%,.0f", val.doubleValue());
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return "-";
        }
        return s.charAt(0) + s.substring(1).toLowerCase();
    }

    private Double toDouble(BigDecimal val) {
        return val != null ? val.doubleValue() : null;
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}