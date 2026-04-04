package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.crumbs.trade.entity.Alert;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.repo.AlertRepo;
import com.crumbs.trade.repo.OrderRepository;

@Slf4j
@Service
public class DominanceService {

    // =========================
    // ⚙️ TUNING
    // =========================
    private static final String STRATEGY            = "VWAP_OPTION_SELLER";
    private static final int    CONFIRM_TICKS        = 3;    // consecutive same-bias ticks to enter
    private static final int    SL_TICKS             = 2;    // consecutive broken-thesis ticks to exit
    private static final int    CROSSOVER_WINDOW_MIN = 60;   // noise check window (minutes)
    private static final int    MAX_CROSSOVERS       = 5;    // sideways threshold
    private static final int    MAX_ENTRIES_PER_DAY  = 3;    // hard daily safety cap

    @Autowired
    private AlertRepo alertRepo;

    @Autowired
    private OrderRepository ordersRepo;

    // =========================
    // 🚀 ENTRY POINT
    // =========================
    public void process() {
        LocalTime now = LocalTime.now();
        if (isNiftyTime(now))  processSymbol("NIFTY",    now);
        if (isCrudeTime(now))  processSymbol("CRUDEOIL", now);
    }

    // =========================
    // 🧠 TIME GUARDS
    // =========================
    private boolean isNiftyTime(LocalTime t) {
        return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
    }

    private boolean isCrudeTime(LocalTime t) {
        return !t.isBefore(LocalTime.of(16, 0)) && !t.isAfter(LocalTime.of(23, 30));
    }

    // =========================
    // 🔁 CORE PROCESSING
    // =========================
    private void processSymbol(String symbol, LocalTime now) {

        // ─────────────────────────────────────────────
        // PHASE 1: Manage open position first
        //   EOD / SL always checked before entry logic
        // ─────────────────────────────────────────────
        Orders openOrder = ordersRepo
                .findTopByNameAndSymbolAndStatusOrderByCreatedOnDesc(
                        STRATEGY, symbol, "OPEN")
                .orElse(null);

        if (openOrder != null) {

            // EOD square-off
            if (isEodTime(symbol, now)) {
                closeOrder(openOrder, "SQUAREOFF");
                log(symbol, "🔔 EOD — squared off " + openOrder.getOptionType());
                return;
            }

            // Need at least one recent alert to evaluate SL
            Alert latest = getLatestDominanceAlert(symbol);
            if (latest == null) {
                log(symbol, "⏳ No recent alert — holding position");
                return;
            }

            // SL: thesis broken for SL_TICKS consecutive ticks?
            if (isSlTriggered(symbol, openOrder)) {
                closeOrder(openOrder, "SL_HIT");
                log(symbol, "🛑 SL HIT — "
                        + openOrder.getOptionType()
                        + " price back above its VWAP"
                        + " | entry=" + openOrder.getAskPrice()
                        + " | cycle=" + openOrder.getTradeCycleId());
                // cycle resets — next tick free to look for new entry
                return;
            }

            // Crossover warning — momentum shifting against us?
            checkCrossoverWarning(symbol, openOrder);

            log(symbol, "✋ Holding "
                    + openOrder.getOptionType() + " SELL — thesis intact");
            return;
        }

        // ─────────────────────────────────────────────
        // PHASE 2: Look for new entry
        // ─────────────────────────────────────────────

        // Hard daily cap
        long todayCount = ordersRepo.countByNameAndSymbolAndCreatedOnAfter(
                STRATEGY, symbol,
                LocalDateTime.now().toLocalDate().atStartOfDay());

        if (todayCount >= MAX_ENTRIES_PER_DAY) {
            log(symbol, "🛑 Daily cap reached ("
                    + todayCount + "/" + MAX_ENTRIES_PER_DAY + ")");
            return;
        }

        // ── Noise filter ──────────────────────────────
        // FIX: removed STRATEGY param — alerts saved as strategyName="CROSSOVER"
        //      but STRATEGY constant is "VWAP_OPTION_SELLER" → always 0 results
        List<Alert> crossovers = alertRepo.findRecentCrossoverAlerts(
                symbol,
                LocalDateTime.now().minusMinutes(CROSSOVER_WINDOW_MIN));

        int crossoverCount = crossovers == null ? 0 : crossovers.size();
        log(symbol, "🔀 Crossovers in last " + CROSSOVER_WINDOW_MIN + "min → " + crossoverCount);

        if (crossoverCount > MAX_CROSSOVERS) {
            log(symbol, "❌ Sideways — " + crossoverCount + " crossovers");
            return;
        }

        // ── Confirmation ──────────────────────────────
        // FIX: removed STRATEGY param — alerts saved as strategyName="VWAP_DOMINANCE"
        //      but STRATEGY constant is "VWAP_OPTION_SELLER" → always 0 results
        //      → log always showed "Not enough dominance alerts yet (0/3)"
        List<Alert> recentDominance = alertRepo.findTopDominanceAlerts(
                symbol,
                PageRequest.of(0, CONFIRM_TICKS));

        if (recentDominance == null || recentDominance.size() < CONFIRM_TICKS) {
            log(symbol, "⏳ Not enough dominance alerts yet ("
                    + (recentDominance == null ? 0 : recentDominance.size())
                    + "/" + CONFIRM_TICKS + ")");
            return;
        }

        long ceCount = recentDominance.stream()
                .filter(a -> "VWAP_DOMINANCE_CE".equals(a.getSignalType()))
                .count();

        long peCount = recentDominance.stream()
                .filter(a -> "VWAP_DOMINANCE_PE".equals(a.getSignalType()))
                .count();

        log(symbol, "📊 Last " + CONFIRM_TICKS + " ticks → CE=" + ceCount + " PE=" + peCount);

        // Must be unanimous — any mixed tick = not confirmed
        String bias = null;
        if (ceCount == CONFIRM_TICKS) bias = "UP";
        if (peCount == CONFIRM_TICKS) bias = "DOWN";

        if (bias == null) {
            log(symbol, "⏳ Mixed signals — CE=" + ceCount + " PE=" + peCount
                    + " — waiting for clean " + CONFIRM_TICKS + " ticks");
            return;
        }

        // ─────────────────────────────────────────────
        // ENTRY — prices read directly from Alert row
        //   saved by StraddleIntradayService scheduler
        //   no live market call needed
        // ─────────────────────────────────────────────
        Alert  entryAlert  = recentDominance.get(0); // most recent tick
        int    entryStrike = entryAlert.getStrike()  != null ? entryAlert.getStrike()  : 0;
        double entryPrice  = getEntryPrice(entryAlert, bias); // PE price if UP, CE if DOWN

        String cycleId = STRATEGY + "_" + symbol + "_" + System.currentTimeMillis();

        Orders entry = buildOrder(symbol, cycleId, bias, entryStrike, entryPrice);
        ordersRepo.save(entry);

        log(symbol, "✅ ENTRY"
                + " | bias="   + bias
                + " | sell="   + entry.getOptionType()
                + " | strike=" + entryStrike
                + " | price="  + entryPrice
                + " | cycle="  + cycleId);
    }

    // =========================
    // 🛑 SL CHECK
    //   Sold PE → SL when PE price >= PE VWAP
    //   Sold CE → SL when CE price >= CE VWAP
    //   Must hold for SL_TICKS consecutive alerts
    // =========================
    private boolean isSlTriggered(String symbol, Orders openOrder) {

        // FIX: removed STRATEGY param — same mismatch as above
        List<Alert> lastAlerts = alertRepo.findTopDominanceAlerts(
                symbol,
                PageRequest.of(0, SL_TICKS));

        if (lastAlerts == null || lastAlerts.size() < SL_TICKS) return false;

        String soldOption = openOrder.getOptionType(); // CE or PE

        return lastAlerts.stream()
                .limit(SL_TICKS)
                .allMatch(a -> isThesisBroken(a, soldOption));
    }

    // Thesis broken when the option we sold has recovered above its own VWAP
    private boolean isThesisBroken(Alert a, String soldOption) {

        if (a.getCePrice() == null || a.getPePrice() == null
                || a.getCeVwap() == null || a.getPeVwap() == null) {
            return false; // incomplete data — don't trigger SL on nulls
        }

        if ("PE".equals(soldOption)) {
            // Sold PE because PE < PE VWAP
            // Thesis broken → PE price back >= PE VWAP
            return a.getPePrice() >= a.getPeVwap();
        }

        if ("CE".equals(soldOption)) {
            // Sold CE because CE < CE VWAP
            // Thesis broken → CE price back >= CE VWAP
            return a.getCePrice() >= a.getCeVwap();
        }

        return false;
    }

    // =========================
    // ⚠️ CROSSOVER WARNING
    //   Log if an adverse crossover fired
    //   in the last 2 min vs our position
    // =========================
    private void checkCrossoverWarning(String symbol, Orders openOrder) {

        // FIX: removed STRATEGY param
        List<Alert> recent = alertRepo.findRecentCrossoverAlerts(
                symbol,
                LocalDateTime.now().minusMinutes(2));

        if (recent == null || recent.isEmpty()) return;

        Alert latest = recent.get(0);

        // Holding PE sell → adverse = PE crossing above CE
        if ("PE".equals(openOrder.getOptionType())
                && "PE_CE_CROSSOVER".equals(latest.getSignalType())) {
            log(symbol, "⚠️  PE crossover fired — PE gaining strength, watch next tick");
        }

        // Holding CE sell → adverse = CE crossing above PE
        if ("CE".equals(openOrder.getOptionType())
                && "CE_PE_CROSSOVER".equals(latest.getSignalType())) {
            log(symbol, "⚠️  CE crossover fired — CE gaining strength, watch next tick");
        }
    }

    // =========================
    // 📍 HELPERS
    // =========================

    // Entry price = the option we are selling, read from the alert row
    private double getEntryPrice(Alert a, String bias) {
        if ("UP".equals(bias)) {
            return a.getPePrice() != null ? a.getPePrice() : 0.0; // selling PE
        } else {
            return a.getCePrice() != null ? a.getCePrice() : 0.0; // selling CE
        }
    }

    // Most recent dominance alert for this symbol
    // FIX: removed STRATEGY param
    private Alert getLatestDominanceAlert(String symbol) {
        List<Alert> alerts = alertRepo.findTopDominanceAlerts(
                symbol, PageRequest.of(0, 1));
        return (alerts != null && !alerts.isEmpty()) ? alerts.get(0) : null;
    }

    private boolean isEodTime(String symbol, LocalTime now) {
        if ("NIFTY".equals(symbol))
            return !now.isBefore(LocalTime.of(15, 25));
        if ("CRUDEOIL".equals(symbol))
            return !now.isBefore(LocalTime.of(23, 25));
        return false;
    }

    // =========================
    // 🧾 ORDER BUILDER
    // =========================
    private Orders buildOrder(String symbol, String cycleId,
                              String bias, int strike, double entryPrice) {
        Orders o = new Orders();
        o.setName(STRATEGY);
        o.setSymbol(symbol);
        o.setTradeCycleId(cycleId);
        o.setCreatedOn(LocalDateTime.now());
        o.setStatus("OPEN");
        o.setTradePhase("ENTRY");
        o.setReversal(false);
        o.setStrike(new BigDecimal(strike));   // ← add this line
        o.setSignal(bias);
        o.setExchange("NIFTY".equals(symbol) ? "NSE" : "MCX");
        o.setAskPrice(new BigDecimal(entryPrice)); // premium collected at entry

        // Conservative seller: UP = sell PE (weak side), DOWN = sell CE (weak side)
        if ("UP".equals(bias)) {
            o.setOptionType("PE");
            o.setSide("SELL");
        } else {
            o.setOptionType("CE");
            o.setSide("SELL");
        }

        return o;
    }

    // =========================
    // 🔒 CLOSE ORDER
    // =========================
    private void closeOrder(Orders o, String phase) {
    	 o.setStatus("CLOSED");
    	    o.setTradePhase(phase);
    	    o.setClosedOn(LocalDateTime.now());                     // ← exit timestamp

    	    // Capture exit price from the latest alert
    	    Alert latest = getLatestDominanceAlert(o.getSymbol());
    	    if (latest != null) {
    	        double exitPx = getEntryPrice(latest, o.getSignal()); // same side logic
    	        o.setExitPrice(new BigDecimal(exitPx));
    	    }

    	    ordersRepo.save(o);
    }

    private void log(String symbol, String msg) {
        log.info("[{}] {}", symbol, msg);
    }
}