package com.crumbs.trade.service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.models.Order;
import com.angelbroking.smartapi.models.OrderParams;
import com.angelbroking.smartapi.utils.Constants;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.builder.OptionScannerConfig;
import com.crumbs.trade.dto.ScannedContractDto;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.StrategyRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * ENTRY LOGIC ONLY. Every signal now produces a TWO-LEG hedged position (short CE + short PE).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptionPriceOrderService {

    private static final String ZONE = "Asia/Kolkata";
    private static final String PAPER_ORDER_ID_MARKER = "1";
    private static final long CHASE_WAIT_MS = 10_000;
    private static final int MAX_CHASE_ATTEMPTS = 6;      // 60s per pair (was 30 x 10s = 5 min per leg)
    private static final int MIN_DAYS_TO_EXPIRY = 1;      // skip expiry-day gamma when selling premium
    private static final String SELL = Constants.TRANSACTION_TYPE_SELL;
    private static final String BUY = Constants.TRANSACTION_TYPE_BUY;

    private final AngelOne angelOne;
    private final OrderRepository orderRepository;
    private final StrategyRepo strategyRepo;
    private final MonitorOrderService monitorOrderService;
    private final TelegramService telegramService;          // NEW: alert if a leg cannot be unwound

    // Initialised final fields are ignored by @RequiredArgsConstructor
    private final Set<String> entriesInFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService entryExecutor = Executors.newFixedThreadPool(2); // one task per symbol entry
    private final ExecutorService legExecutor = Executors.newFixedThreadPool(4);   // CE and PE chase in parallel

    // ==========================================
    // BIAS -> SHORT STRADDLE SHAPE (always both legs)
    // Offsets are in strikes from ATM (+1 = one strike higher, -1 = one lower).
    // Skewed legs use ITM strikes only, because the scanner loads ATM + ITM contracts.
    //   PE ITM = higher strike, CE ITM = lower strike.
    // ==========================================
    private enum Bias {
        PURE_STRADDLE(0, 0),      // ATM CE + ATM PE
        BULLISH_STRADDLE(0, 1),   // ATM CE + PE one strike up (ITM PE)
        BEARISH_STRADDLE(-1, 0),  // CE one strike down (ITM CE) + ATM PE
        BULLISH(0, 1),            // same as BULLISH_STRADDLE
        BEARISH(-1, 0);           // same as BEARISH_STRADDLE

        final int ceOffset;
        final int peOffset;

        Bias(int ceOffset, int peOffset) {
            this.ceOffset = ceOffset;
            this.peOffset = peOffset;
        }

        static Bias from(String label) {
            if (label == null) return null;
            if (label.contains("PURE STRADDLE/STRANGLE")) return PURE_STRADDLE;
            if (label.contains("BULLISH STRADDLE")) return BULLISH_STRADDLE;
            if (label.contains("BEARISH STRADDLE")) return BEARISH_STRADDLE;
            if (label.startsWith("BULLISH")) return BULLISH;
            if (label.startsWith("BEARISH")) return BEARISH;
            return null; // NEUTRAL etc.
        }
    }

    private record LegFill(ScannedContractDto dto, String orderId, BigDecimal avgPrice,
                           int requestedQty, int filledQty) {
        boolean complete() { return requestedQty > 0 && filledQty >= requestedQty; }
        boolean exposed()  { return filledQty > 0; }
    }

    private record OrderState(String status, int filledQty, BigDecimal avg) {
        BigDecimal avgOr(BigDecimal fallback) { return avg != null ? avg : fallback; }
    }

    // ==========================================
    // ENTRY POINT (non-blocking: returns immediately, scanner thread is free)
    // ==========================================
    public void processAiSignalOrder(String symbol, String biasLabel, List<ScannedContractDto> contracts) {
        if (contracts == null || contracts.isEmpty()) return;

        Bias bias = Bias.from(biasLabel);
        if (bias == null) return;

        String strategyName = "AI_BIAS_" + symbol;

        // Orders are only saved after fills, so the DB lock alone cannot stop a second scan
        // from entering while the first is still chasing. This in-memory guard closes that gap.
        if (!entriesInFlight.add(strategyName)) {
            log.info("🔐 [IN-FLIGHT] Entry already running for {}. Skipping.", symbol);
            return;
        }

        List<ScannedContractDto> snapshot = List.copyOf(contracts);
        try {
            entryExecutor.submit(() -> {
                try {
                    runEntry(symbol, strategyName, bias, biasLabel, snapshot);
                } catch (Exception e) {
                    log.error("❌ Entry failed for {}: {}", symbol, e.getMessage(), e);
                } finally {
                    entriesInFlight.remove(strategyName);
                }
            });
        } catch (RejectedExecutionException e) {
            entriesInFlight.remove(strategyName);
            throw e;
        }
    }

    private void runEntry(String symbol, String strategyName, Bias bias, String biasLabel,
                          List<ScannedContractDto> contracts) {
        if (hasActiveTradeTodayForSymbol(symbol)) {
            log.info("🔐 [DAILY LOCK] Trade already active today for {}. Skipping AI order.", symbol);
            return;
        }

        // 1. One expiry for BOTH legs (the scan loads weekly + monthly)
        LocalDate expiry = pickExpiry(contracts);
        if (expiry == null) {
            log.warn("⚠️ No eligible expiry for {}. Skipping.", symbol);
            return;
        }
        List<ScannedContractDto> sameExpiry = contracts.stream()
                .filter(c -> expiry.equals(c.getExpiryDate()))
                .toList();

        // 2. Strike geometry
        double step = strikeStep(sameExpiry);
        if (step <= 0) {
            log.warn("⚠️ Cannot derive strike step for {}. Skipping.", symbol);
            return;
        }
        double atm = atmStrike(sameExpiry);
        double ceStrike = atm + bias.ceOffset * step;
        double peStrike = atm + bias.peOffset * step;

        // 3. Resolve BOTH legs first. Never trade one leg of a hedge.
        ScannedContractDto ce = findLeg(sameExpiry, ceStrike, "CE");
        ScannedContractDto pe = findLeg(sameExpiry, peStrike, "PE");
        if (ce == null || pe == null) {
            log.error("🛑 [{}] {} aborted: leg not in scanned contracts (CE {} -> {}, PE {} -> {}). No orders placed.",
                    symbol, bias, ceStrike, ce != null, peStrike, pe != null);
            return;
        }

        log.info("🎯 [{}] {} | expiry {} | SELL CE {} + SELL PE {} (ATM {}, step {})",
                symbol, biasLabel, expiry, ceStrike, peStrike, atm, step);

        executePair(strategyName, UUID.randomUUID().toString(), ce, pe, isLiveMode());
    }

    // ==========================================
    // CONTRACT SELECTION HELPERS
    // ==========================================
    private LocalDate pickExpiry(List<ScannedContractDto> contracts) {
        LocalDate earliest = LocalDate.now(ZoneId.of(ZONE)).plusDays(MIN_DAYS_TO_EXPIRY);
        return contracts.stream()
                .map(ScannedContractDto::getExpiryDate)   // assumes LocalDate; adapt if it is a String
                .filter(Objects::nonNull)
                .filter(d -> !d.isBefore(earliest))
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private double strikeStep(List<ScannedContractDto> contracts) {
        List<Double> strikes = contracts.stream()
                .map(ScannedContractDto::getStrike)
                .filter(s -> s > 0)
                .distinct()
                .sorted()
                .toList();
        double min = Double.MAX_VALUE;
        for (int i = 1; i < strikes.size(); i++) {
            min = Math.min(min, strikes.get(i) - strikes.get(i - 1));
        }
        return min == Double.MAX_VALUE ? 0 : min;
    }

    private double atmStrike(List<ScannedContractDto> contracts) {
        ScannedContractDto atm = contracts.stream()
                .filter(c -> c.getMoneyness() == OptionScannerConfig.Moneyness.ATM)
                .findFirst().orElse(null);
        if (atm != null) return atm.getStrike();

        BigDecimal spot = contracts.get(0).getSpotPrice();
        List<Double> strikes = contracts.stream().map(ScannedContractDto::getStrike)
                .filter(s -> s > 0).distinct().sorted().toList();
        if (spot != null) {
            return strikes.stream()
                    .min(Comparator.comparingDouble(s -> Math.abs(s - spot.doubleValue())))
                    .orElse(strikes.get(strikes.size() / 2));
        }
        return strikes.get(strikes.size() / 2);
    }

    private ScannedContractDto findLeg(List<ScannedContractDto> sameExpiry, double strike, String type) {
        return sameExpiry.stream()
                .filter(c -> Math.abs(c.getStrike() - strike) < 1e-6 && type.equalsIgnoreCase(c.getOptionType()))
                .findFirst().orElse(null);
    }

    // ==========================================
    // PAIR EXECUTION: both legs fill, or nothing is left open
    // ==========================================
    private void executePair(String strategyName, String cycleId,
                             ScannedContractDto ce, ScannedContractDto pe, boolean isLive) {
        int ceQty = quantityOf(ce);
        int peQty = quantityOf(pe);

        if (!isLive) {
            saveLeg(strategyName, cycleId, ce, SELL, ceQty, priceOrTen(ce), PAPER_ORDER_ID_MARKER);
            saveLeg(strategyName, cycleId, pe, SELL, peQty, priceOrTen(pe), PAPER_ORDER_ID_MARKER);
            log.info("📄 [PAPER] Hedged pair saved for {}", strategyName);
            return;
        }

        final SmartConnect sc = signInOrNull();
        if (sc == null) return;

        CompletableFuture<LegFill> ceF = CompletableFuture.supplyAsync(() -> placeAndChase(sc, ce, SELL, ceQty), legExecutor);
        CompletableFuture<LegFill> peF = CompletableFuture.supplyAsync(() -> placeAndChase(sc, pe, SELL, peQty), legExecutor);
        LegFill ceFill = ceF.join();
        LegFill peFill = peF.join();

        if (ceFill.complete() && peFill.complete()) {
            saveLeg(strategyName, cycleId, ce, SELL, ceFill.filledQty(), ceFill.avgPrice(), ceFill.orderId());
            saveLeg(strategyName, cycleId, pe, SELL, peFill.filledQty(), peFill.avgPrice(), peFill.orderId());
            log.info("✅ Hedged pair FILLED for {} | CE @ {} | PE @ {}", strategyName, ceFill.avgPrice(), peFill.avgPrice());
            return;
        }

        log.error("🚨 Pair incomplete for {} (CE filled {}/{}, PE filled {}/{}). Unwinding.",
                strategyName, ceFill.filledQty(), ceFill.requestedQty(), peFill.filledQty(), peFill.requestedQty());
        unwind(sc, strategyName, ceFill);
        unwind(sc, strategyName, peFill);
    }

    private LegFill placeAndChase(SmartConnect sc, ScannedContractDto dto, String side, int qty) {
        String exchange = exchangeOf(dto);
        String tradingSymbol = tradingSymbolOf(dto);
        BigDecimal price = dto.getCurrentLtp();
        String orderId = null;

        // Throwable: SmartAPIException is not always an Exception subclass in the SDK
        try {
            price = getBestDepthPrice(sc, exchange, dto.getToken(), dto.getCurrentLtp(), side);
            OrderParams params = buildParams(exchange, dto.getToken(), tradingSymbol, side, qty, price, false);

            Order placed = sc.placeOrder(params, Constants.VARIETY_NORMAL);
            if (placed == null || placed.orderId == null || placed.orderId.isBlank()) {
                log.error("❌ Order placement returned no id for {}", tradingSymbol);
                return new LegFill(dto, null, price, qty, 0);
            }
            orderId = placed.orderId;
            log.info("🎯 Placed {} {} @ ₹{} | OrderID: {}", side, tradingSymbol, price, orderId);

            for (int attempt = 1; attempt <= MAX_CHASE_ATTEMPTS; attempt++) {
                sleepQuietly(CHASE_WAIT_MS);
                OrderState st = fetchOrderState(sc, orderId);

                if ("COMPLETE".equalsIgnoreCase(st.status())) {
                    return new LegFill(dto, orderId, st.avgOr(price), qty, qty);
                }
                if ("REJECTED".equalsIgnoreCase(st.status()) || "CANCELLED".equalsIgnoreCase(st.status())) {
                    log.warn("🚨 Order {} {}", orderId, st.status());
                    return new LegFill(dto, orderId, st.avgOr(price), qty, st.filledQty());
                }

                BigDecimal best = getBestDepthPrice(sc, exchange, dto.getToken(), price, side);
                if (best.compareTo(price) != 0) {
                    params.price = best.doubleValue();
                    try {
                        sc.modifyOrder(orderId, params, Constants.VARIETY_NORMAL);
                        price = best;
                    } catch (Throwable t) {
                        log.warn("⚠️ Could not modify order {}: {}", orderId, t.getMessage());
                    }
                }
            }

            // Timeout: CANCEL (the old code left it live and recorded it as OPEN)
            try { sc.cancelOrder(orderId, Constants.VARIETY_NORMAL); }
            catch (Throwable t) { log.warn("⚠️ Cancel failed for {}: {}", orderId, t.getMessage()); }
            sleepQuietly(1_000);

            OrderState fin = fetchOrderState(sc, orderId);
            int filled = "COMPLETE".equalsIgnoreCase(fin.status()) ? qty : fin.filledQty();
            return new LegFill(dto, orderId, fin.avgOr(price), qty, filled);

        } catch (Throwable t) {
            log.error("❌ Leg failed for {}: {}", tradingSymbol, t.getMessage(), t);
            int filled = orderId != null ? fetchOrderState(sc, orderId).filledQty() : 0;
            return new LegFill(dto, orderId, price, qty, filled);
        }
    }

    /** Buys back whatever got filled on a leg when its partner leg did not fill. */
    private void unwind(SmartConnect sc, String strategyName, LegFill leg) {
        if (!leg.exposed()) return;
        ScannedContractDto dto = leg.dto();
        try {
            OrderParams p = buildParams(exchangeOf(dto), dto.getToken(), tradingSymbolOf(dto),
                    BUY, leg.filledQty(), BigDecimal.ZERO, true);
            sc.placeOrder(p, Constants.VARIETY_NORMAL);
            log.warn("↩️ Unwound {} x{} for {}", tradingSymbolOf(dto), leg.filledQty(), strategyName);
        } catch (Throwable t) {
            alert("🚨 MANUAL ACTION: could not unwind " + tradingSymbolOf(dto) + " x" + leg.filledQty()
                    + " (" + strategyName + "): " + t.getMessage());
        }
    }

    // ==========================================
    // SMALL HELPERS
    // ==========================================
    private OrderParams buildParams(String exchange, String token, String tradingSymbol, String side,
                                    int qty, BigDecimal price, boolean market) {
        OrderParams p = new OrderParams();
        p.variety = Constants.VARIETY_NORMAL;
        p.quantity = qty;
        p.symboltoken = token;
        p.exchange = exchange;
        p.ordertype = market ? Constants.ORDER_TYPE_MARKET : Constants.ORDER_TYPE_LIMIT;
        p.tradingsymbol = tradingSymbol;
        p.producttype = Constants.PRODUCT_CARRYFORWARD;
        p.duration = Constants.DURATION_DAY;
        p.transactiontype = side;
        p.price = market ? 0.0 : price.doubleValue();
        p.squareoff = "0";
        p.stoploss = "0";
        return p;
    }

    private OrderState fetchOrderState(SmartConnect sc, String orderId) {
        try {
            JSONObject r = sc.getIndividualOrderDetails(orderId);
            if (r != null && r.optBoolean("status", false)) {
                JSONObject d = r.optJSONObject("data");
                if (d != null) {
                    double avg = d.optDouble("averageprice", 0.0);   // verify field names against your responses
                    return new OrderState(d.optString("status", "OPEN"),
                            d.optInt("filledshares", 0),
                            avg > 0 ? BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP) : null);
                }
            }
        } catch (Throwable ignored) { }
        return new OrderState("OPEN", 0, null);
    }

    private void saveLeg(String strategyName, String cycleId, ScannedContractDto dto, String side,
                         int qty, BigDecimal price, String orderId) {
        Orders leg = new Orders();
        leg.setName(strategyName);
        leg.setSymbol(tradingSymbolOf(dto));
        leg.setToken(dto.getToken());
        leg.setStrike(BigDecimal.valueOf(dto.getStrike()));
        leg.setOptionType(dto.getOptionType());
        leg.setSide(dto.getOptionType());
        leg.setQuantity(qty);
        leg.setExchange(exchangeOf(dto));
        leg.setSignal(strategyName);
        leg.setType(side);
        leg.setTradeCycleId(cycleId);
        finalizeOrderInDb(leg, price, orderId);
    }

    private boolean isLiveMode() {
        try {
            Strategy strat = strategyRepo.findByName("OPTION_PRICE_SCANNER");
            return strat != null && "Y".equalsIgnoreCase(strat.getLive());
        } catch (Exception e) {
            log.warn("⚠️ Could not fetch strategy config for OPTION_PRICE_SCANNER: {}", e.getMessage());
            return false;
        }
    }

    private SmartConnect signInOrNull() {
        try { return angelOne.signIn(); }
        catch (Exception e) { log.error("❌ Broker auth failed: {}", e.getMessage()); return null; }
    }

    private String exchangeOf(ScannedContractDto dto) { return "MCX".equalsIgnoreCase(dto.getExchange()) ? "MCX" : "NFO"; }
    private int quantityOf(ScannedContractDto dto) { return dto.getLotsize() > 0 ? dto.getLotsize() : 1; }
    private BigDecimal priceOrTen(ScannedContractDto dto) { return dto.getCurrentLtp() != null ? dto.getCurrentLtp() : BigDecimal.TEN; }
    private String tradingSymbolOf(ScannedContractDto dto) {
        return dto.getSymbol() != null ? dto.getSymbol()
                : dto.getName() + dto.getRawExpiry() + (int) dto.getStrike() + dto.getOptionType();
    }

    private void alert(String msg) {
        log.error(msg);
        try { telegramService.sendToNewChat(msg); } catch (Exception ignored) { }
    }

    // ==========================================
    // EXISTING HELPERS (carried over, now included in full)
    // ==========================================
    private BigDecimal getBestDepthPrice(SmartConnect smartConnect, String exchange, String token,
                                         BigDecimal fallbackLtp, String side) {
        String safeExchange = "MCX".equalsIgnoreCase(exchange) ? "MCX" : "NFO";

        try {
            JSONObject payload = new JSONObject();
            payload.put("mode", "FULL");
            JSONObject exchangeTokens = new JSONObject();
            exchangeTokens.put(safeExchange, new JSONArray(List.of(token)));
            payload.put("exchangeTokens", exchangeTokens);

            JSONObject response = smartConnect.marketData(payload);
            if (response != null && response.has("data")) {
                JSONArray fetched = response.getJSONObject("data").optJSONArray("fetched");
                if (fetched != null && !fetched.isEmpty()) {
                    JSONObject depth = fetched.getJSONObject(0).optJSONObject("depth");
                    if (depth != null) {
                        String depthKey = Constants.TRANSACTION_TYPE_BUY.equalsIgnoreCase(side) ? "sell" : "buy";
                        JSONArray book = depth.optJSONArray(depthKey);
                        if (book != null && !book.isEmpty()) {
                            double price = book.getJSONObject(0).optDouble("price", 0.0);
                            if (price > 0) return BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP);
                        }
                    }
                    double ltp = fetched.getJSONObject(0).optDouble("ltp", 0.0);
                    if (ltp > 0) return BigDecimal.valueOf(ltp).setScale(2, RoundingMode.HALF_UP);
                }
            }
        } catch (Throwable ignored) {
            // fall through to fallback price
        }
        return fallbackLtp != null ? fallbackLtp : BigDecimal.valueOf(100.0);
    }

    private boolean hasActiveTradeTodayForSymbol(String symbol) {
        try {
            return orderRepository.countActiveTradesToday("AI_BIAS_" + symbol) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Transactional
    public void finalizeOrderInDb(Orders leg, BigDecimal fillPrice, String orderId) {
        leg.setAskPrice(fillPrice);
        leg.setOrderid(orderId);
        leg.setStatus("OPEN");
        leg.setTradePhase("ENTRY");
        leg.setActive(1);
        leg.setCreatedOn(LocalDateTime.now(ZoneId.of(ZONE)));
        orderRepository.save(leg);
    }

    // ==========================================
    // 3:15 PM AUTO SQUARE OFF LOGIC (unchanged)
    // ==========================================
    public void closeAllOpenTrades() {
        List<Orders> openOrders = orderRepository.findAll().stream()
                .filter(o -> o.getActive() == 1 && "OPEN".equalsIgnoreCase(o.getStatus()))
                .filter(o -> o.getName() != null && o.getName().startsWith("AI_BIAS_"))
                .collect(Collectors.toList());

        if (openOrders.isEmpty()) {
            log.info("✅ No open AI_BIAS trades found to square off at 3:15 PM.");
            return;
        }

        log.info("🔄 Auto Squaring off {} AI_BIAS trades via MonitorOrderService...", openOrders.size());

        Map<String, List<Orders>> groupedOrders = openOrders.stream()
                .collect(Collectors.groupingBy(o -> o.getTradeCycleId() != null ? o.getTradeCycleId() : o.getName()));

        for (Map.Entry<String, List<Orders>> entry : groupedOrders.entrySet()) {
            try {
                List<Orders> groupLegs = entry.getValue();
                String strategyName = groupLegs.get(0).getName();
                monitorOrderService.forceExit(groupLegs, strategyName, "3:15_AUTO_SQUARE_OFF");
            } catch (Exception e) {
                log.error("❌ Exception during auto square-off for group {}: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    private void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}