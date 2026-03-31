package com.crumbs.trade.service;

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
    private static final int    CONFIRM_TICKS        = 3;   // consecutive same-bias ticks to enter
    private static final int    SL_TICKS             = 2;   // consecutive broken-thesis ticks to exit
    private static final int    CROSSOVER_WINDOW_MIN = 60;  // noise check window
    private static final int    MAX_CROSSOVERS       = 5;   // sideways threshold
    private static final int    MAX_ENTRIES_PER_DAY  = 3;   // hard daily safety cap

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
        //   SL / EOD — always checked before entry
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

            // Fetch latest alert to read current prices vs VWAP
            Alert latest = getLatestDominanceAlert(symbol);

            if (latest == null) {
                log(symbol, "⏳ No recent alert — holding position");
                return;
            }

            // SL check — thesis broken for SL_TICKS consecutive ticks?
            if (isSlTriggered(symbol, openOrder)) {
                closeOrder(openOrder, "SL_HIT");
                log(symbol, "🛑 SL HIT — "
                        + openOrder.getOptionType()
                        + " price back above its VWAP"
                        + " | entry=" + openOrder.getAskPrice()
                        + " | cycle=" + openOrder.getTradeCycleId());
                return;
                // cycle resets — next tick is free to look for new entry
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

        // Noise filter — too many crossovers in window = choppy, skip
        List<Alert> crossovers = alertRepo.findRecentCrossoverAlerts(
                STRATEGY, symbol,
                LocalDateTime.now().minusMinutes(CROSSOVER_WINDOW_MIN));

        if (crossovers != null && crossovers.size() > MAX_CROSSOVERS) {
            log(symbol, "❌ Sideways — "
                    + crossovers.size() + " crossovers in last "
                    + CROSSOVER_WINDOW_MIN + "min");
            return;
        }

        // Confirmation — last CONFIRM_TICKS dominance alerts must all agree
        List<Alert> recentDominance = alertRepo.findTopDominanceAlerts(
                STRATEGY, symbol,
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

        // Must be unanimous — any mixed tick = not ready
        String bias = null;
        if (ceCount == CONFIRM_TICKS) bias = "UP";
        if (peCount == CONFIRM_TICKS) bias = "DOWN";

        if (bias == null) {
            log(symbol, "⏳ Mixed signals — CE=" + ceCount + " PE=" + peCount
                    + " — waiting for clean " + CONFIRM_TICKS + " ticks");
            return;
        }

        // ─────────────────────────────────────────────
        // ENTRY — pull entry price directly from the
        // latest alert saved by your existing scheduler
        // ─────────────────────────────────────────────
        Alert entryAlert = recentDominance.get(0); // most recent tick

        int    entryStrike = entryAlert.getStrike()  != null ? entryAlert.getStrike()  : 0;
        double entryPrice  = getEntryPrice(entryAlert, bias); // PE price if UP, CE price if DOWN

        String cycleId = STRATEGY + "_" + symbol + "_"
                + System.currentTimeMillis();

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

        List<Alert> lastAlerts = alertRepo.findTopDominanceAlerts(
                STRATEGY, symbol,
                PageRequest.of(0, SL_TICKS));

        if (lastAlerts == null || lastAlerts.size() < SL_TICKS) return false;

        String soldOption = openOrder.getOptionType(); // CE or PE

        return lastAlerts.stream()
                .limit(SL_TICKS)
                .allMatch(a -> isThesisBroken(a, soldOption));
    }

    // Thesis is broken when the option we sold has recovered above its VWAP
    private boolean isThesisBroken(Alert a, String soldOption) {

        if (a.getCePrice() == null || a.getPePrice() == null
                || a.getCeVwap() == null || a.getPeVwap() == null) {
            return false;
        }

        if ("PE".equals(soldOption)) {
            // We sold PE because PE < PE VWAP
            // Thesis broken → PE price is back >= PE VWAP
            return a.getPePrice() >= a.getPeVwap();
        }

        if ("CE".equals(soldOption)) {
            // We sold CE because CE < CE VWAP
            // Thesis broken → CE price is back >= CE VWAP
            return a.getCePrice() >= a.getCeVwap();
        }

        return false;
    }

    // =========================
    // ⚠️ CROSSOVER WARNING
    //   Log if a crossover adverse to our
    //   position fired in the last 2 min
    // =========================
    private void checkCrossoverWarning(String symbol, Orders openOrder) {

        List<Alert> recent = alertRepo.findRecentCrossoverAlerts(
                STRATEGY, symbol,
                LocalDateTime.now().minusMinutes(2));

        if (recent == null || recent.isEmpty()) return;

        Alert latest = recent.get(0);

        // Holding PE sell → adverse signal is PE crossing above CE
        if ("PE".equals(openOrder.getOptionType())
                && "PE_CE_CROSSOVER".equals(latest.getSignalType())) {
            log(symbol, "⚠️  PE crossover fired — PE gaining strength, watch next tick");
        }

        // Holding CE sell → adverse signal is CE crossing above PE
        if ("CE".equals(openOrder.getOptionType())
                && "CE_PE_CROSSOVER".equals(latest.getSignalType())) {
            log(symbol, "⚠️  CE crossover fired — CE gaining strength, watch next tick");
        }
    }

    // =========================
    // 📍 HELPERS
    // =========================

    // Entry price = the option we are about to sell, from the alert
    private double getEntryPrice(Alert a, String bias) {
        if ("UP".equals(bias)) {
            // selling PE → record PE price as our entry premium
            return a.getPePrice() != null ? a.getPePrice() : 0.0;
        } else {
            // selling CE → record CE price as our entry premium
            return a.getCePrice() != null ? a.getCePrice() : 0.0;
        }
    }

    // Most recent dominance alert for this symbol
    private Alert getLatestDominanceAlert(String symbol) {
        List<Alert> alerts = alertRepo.findTopDominanceAlerts(
                STRATEGY, symbol, PageRequest.of(0, 1));
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
        o.setSignal(bias);
        o.setExchange("NIFTY".equals(symbol) ? "NSE" : "MCX");
        o.setAskPrice((int) entryPrice);   // premium collected at entry

        if ("UP".equals(bias)) {
            o.setOptionType("PE");   // sell PE — UP market, PE is weak
            o.setSide("SELL");
        } else {
            o.setOptionType("CE");   // sell CE — DOWN market, CE is weak
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
        ordersRepo.save(o);
    }

    private void log(String symbol, String msg) {
        log.info("[{}] {}", symbol, msg);
    }
}