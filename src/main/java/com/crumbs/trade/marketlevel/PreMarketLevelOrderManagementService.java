package com.crumbs.trade.marketlevel;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import com.angelbroking.smartapi.utils.Constants;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.PreMarketAnalysis;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.PreMarketAnalysisRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.AngelOneService;
import com.crumbs.trade.service.AngelWebSocketService;
import com.crumbs.trade.service.TelegramService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PreMarketLevelOrderManagementService {

    private static final Logger log =
            LoggerFactory.getLogger(PreMarketLevelOrderManagementService.class);

    @Autowired AngelOneService  angelOneService;
    @Autowired StrategyRepo     strategyRepo;
    @Autowired IndexesRepo      indexesRepo;
    @Autowired AngelOne         angelOne;

    // ============================================================
    // ================= STRATEGY CONFIG ==========================
    // ============================================================

    private static final String       STRATEGY_NAME = "MARKET_LEVEL";
    private static final ExchangeType EXCHANGE      = ExchangeType.NSE_FO;

    // ============================================================
    // ================= TARGET MODE ==============================
    // ============================================================

    public enum TargetMode {
        FIXED_PREV_HIGH,  // SL=10pts fixed, Target=prevHigh fixed, no trail
        TRAILING          // SL trails up every N pts, no fixed target ceiling
    }

    private static final TargetMode TARGET_MODE = TargetMode.FIXED_PREV_HIGH;

    // ============================================================
    // ================= FIXED MODE PARAMS ========================
    // ============================================================

    private static final BigDecimal FIXED_SL_POINTS = BigDecimal.valueOf(10);

    // ============================================================
    // ================= TRAILING MODE PARAMS =====================
    // ============================================================

    /** Every N points price moves up, SL locks up by same N points */
    private static final BigDecimal TRAIL_LOCK_STEP          = BigDecimal.valueOf(10);
    private static final BigDecimal TRAIL_INITIAL_SL_POINTS  = BigDecimal.valueOf(10);

    // ============================================================
    // ================= TIME CONFIG ==============================
    // ============================================================

    private static final int COOLDOWN_MINUTES  = 3;
    private static final int FORCE_EXIT_HOUR   = 15;
    private static final int FORCE_EXIT_MINUTE = 20;

    // Session cache: re-login every 8 hours instead of on every order
    private static final int SESSION_TTL_HOURS = 8;

    // ============================================================
    // ================= ORDER TYPE LABELS ========================
    // ============================================================

    private static final String TYPE_ENTRY        = "ENTRY";
    private static final String TYPE_EXIT_SL      = "EXIT_SL";
    private static final String TYPE_EXIT_TARGET  = "EXIT_TARGET";
    private static final String TYPE_EXIT_TRAIL   = "EXIT_TRAIL";
    private static final String TYPE_FORCE_EXIT   = "FORCE_EXIT";

    // ============================================================
    // ================= TELEGRAM TEMPLATES =======================
    // ============================================================

    private static final String TELEGRAM_HEADER =
            "📊 *%s | %s*\n\n%s\n\n🕒 %s";

    private static final String TEMPLATE_ENTRY_FIXED =
            "🟢 *ENTRY (FIXED)*\n" +
            "Strike : %s\n"        +
            "Entry  : %.2f\n"      +
            "SL     : %.2f\n"      +
            "Target : %.2f";

    private static final String TEMPLATE_EXIT_SL =
            "❌ *EXIT — SL HIT*\n" +
            "Strike : %s\n"        +
            "Exit   : %.2f\n"      +
            "PnL    : %.2f pts | ₹%.2f";

    private static final String TEMPLATE_EXIT_TARGET =
            "🏆 *EXIT — TARGET HIT*\n" +
            "Strike : %s\n"            +
            "Exit   : %.2f\n"          +
            "PnL    : %.2f pts | ₹%.2f";

    private static final String TEMPLATE_ENTRY_TRAIL =
            "🟢 *ENTRY (TRAILING)*\n" +
            "Strike     : %s\n"       +
            "Entry      : %.2f\n"     +
            "Initial SL : %.2f";

    private static final String TEMPLATE_TRAIL_SL_UPDATE =
            "🔄 *TRAIL SL UPDATED*\n" +
            "Strike : %s\n"           +
            "New SL : %.2f\n"         +
            "High   : %.2f";

    private static final String TEMPLATE_EXIT_TRAIL =
            "🎯 *EXIT — TRAIL SL HIT*\n" +
            "Strike : %s\n"              +
            "Exit   : %.2f\n"            +
            "PnL    : %.2f pts | ₹%.2f";

    private static final String TEMPLATE_FORCE_EXIT =
            "⏰ *FORCE EXIT (3:20 PM)*\n" +
            "Strike : %s\n"               +
            "Exit   : %.2f\n"             +
            "PnL    : %.2f pts | ₹%.2f";

    // ============================================================
    // ================= FORMATTERS ===============================
    // ============================================================

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss");

    // ============================================================
    // ================= DEPENDENCIES =============================
    // ============================================================

    private final AngelWebSocketService webSocketService;
    private final PreMarketAnalysisRepo preMarketRepo;
    private final OrderRepository       ordersRepo;
    private final TelegramService       telegramService;

    // ============================================================
    // ================= RUNTIME STATE ============================
    // ============================================================

    private enum State { IDLE, ACTIVE, COOLDOWN }

    private State          state                  = State.IDLE;
    private TradeDirection direction              = null;
    private TradeDirection lastCompletedDirection = null;

    private BigDecimal entryPrice   = null;
    private BigDecimal currentSL    = null;
    private BigDecimal target       = null;   // fixed mode only
    private BigDecimal highestPrice = null;   // trail mode only
    private int        lotSize      = 1;      // FIX: stored at entry for PnL calc

    private LocalDateTime cooldownUntil = null;

    // FIX: cached SmartConnect session — avoids re-login on every order event
    private SmartConnect cachedSession        = null;
    private LocalDateTime sessionExpiresAt   = null;

    private final Set<String> subscribedTokens = ConcurrentHashMap.newKeySet();

    // ============================================================
    // ================= STARTUP RECOVERY =========================
    // FIX: reconstruct in-memory state from DB after a restart
    // ============================================================

    //@PostConstruct
    public synchronized void recoverState() {
        ordersRepo.findTopByNameAndActiveOrderByIdDesc(STRATEGY_NAME, 1).ifPresent(order -> {
            try {
                state      = State.ACTIVE;
                direction  = TradeDirection.valueOf(order.getSignal());
                entryPrice = order.getAskPrice();
                currentSL  = order.getSl();
                lotSize    = resolveLotSize(order.getToken());

                // Reconstruct target for FIXED mode
                if (TARGET_MODE == TargetMode.FIXED_PREV_HIGH) {
                    // Target is not stored in Orders — recalculate from pre-market data
                    preMarketRepo
                        .findByNameAndTradingDate(order.getSymbol(), LocalDate.now())
                        .ifPresent(data -> target = getPrevHighForDirection(data, direction));
                }

                // Reconstruct highestPrice for TRAILING mode
                if (TARGET_MODE == TargetMode.TRAILING) {
                    highestPrice = entryPrice; // conservative floor; will update on next tick
                }

                // Re-subscribe the token so LTP resumes
                ensureSubscribed(order.getToken());

                log.warn("⚠️ STATE RECOVERED from DB — direction={} entry={} SL={} lotSize={}",
                        direction, entryPrice, currentSL, lotSize);
                sendTelegram(order.getSymbol(),
                        "⚠️ *SERVICE RESTARTED — Active trade recovered*\n" +
                        "Direction : " + direction + "\n" +
                        "Entry     : " + entryPrice + "\n" +
                        "SL        : " + currentSL);

            } catch (Exception e) {
                log.error("State recovery failed — defaulting to IDLE", e);
                state = State.IDLE;
            }
        });

        if (state == State.IDLE) {
            log.info("No active order found — starting in IDLE");
        }
    }

    // ============================================================
    // ================= MAIN CYCLE ===============================
    // ============================================================

    public synchronized void runCycle(String instrumentName) throws IOException, SmartAPIException {

        LocalDateTime now = LocalDateTime.now();

        if (state == State.ACTIVE && isForceExitTime(now)) {
            forceExit(instrumentName);
            return;
        }

        if (state == State.COOLDOWN) {
            if (now.isAfter(cooldownUntil)) {
                state = State.IDLE;
                log.info("Cooldown over — back to IDLE");
            }
            return;
        }

        Optional<PreMarketAnalysis> opt =
                preMarketRepo.findByNameAndTradingDate(instrumentName, LocalDate.now());

        if (opt.isEmpty()) {
            log.warn("No pre-market data for {} today", instrumentName);
            return;
        }

        PreMarketAnalysis data = opt.get();

        ensureSubscribed(data.getCeToken());
        ensureSubscribed(data.getPeToken());

        BigDecimal ce = getLivePrice(data.getCeToken());
        BigDecimal pe = getLivePrice(data.getPeToken());

        if (ce == null || pe == null) {
            log.warn("LTP unavailable — CE:{} PE:{}", ce, pe);
            return;
        }

        log.debug("CE={} PE={} MID={} Mode={} State={}",
                ce, pe, resolveMidPoint(data), TARGET_MODE, state);

        if (state == State.IDLE) {
            checkEntry(data, ce, pe);
        } else if (state == State.ACTIVE) {
            if (TARGET_MODE == TargetMode.FIXED_PREV_HIGH)
                manageFixed(data, ce, pe);
            else
                manageTrailing(data, ce, pe);
        }
    }

    // ============================================================
    // ================= ENTRY ====================================
    // ============================================================

    private void checkEntry(PreMarketAnalysis data,
                            BigDecimal ce,
                            BigDecimal pe) throws IOException, SmartAPIException {

        // FIX: use in-memory state only — no redundant DB check
        if (state == State.ACTIVE) return;

        BigDecimal mid    = resolveMidPoint(data);
        TradeDirection signal = null;

        if (ce.compareTo(mid) > 0 && pe.compareTo(mid) < 0)
            signal = TradeDirection.BUY_CE;

        if (pe.compareTo(mid) > 0 && ce.compareTo(mid) < 0)
            signal = TradeDirection.BUY_PE;

        if (signal == null) return;

        if (signal == lastCompletedDirection) {
            log.info("Skipping {} — same as last completed direction", signal);
            return;
        }

        direction  = signal;
        entryPrice = (direction == TradeDirection.BUY_CE) ? ce : pe;
        lotSize    = resolveLotSize(getToken(data)); // FIX: capture at entry
        state      = State.ACTIVE;

        if (TARGET_MODE == TargetMode.FIXED_PREV_HIGH)
            enterFixed(data);
        else
            enterTrailing(data);
    }

    // ============================================================
    // ================= FIXED MODE ===============================
    // ============================================================

    private void enterFixed(PreMarketAnalysis data) throws IOException, SmartAPIException {

        currentSL    = entryPrice.subtract(FIXED_SL_POINTS);
        target       = getPrevHigh(data);
        highestPrice = null;

        log.info("[FIXED] ENTRY → {} | Entry={} SL={} Target={}",
                direction, entryPrice, currentSL, target);

        Strategy strategy = strategyRepo.findByName(STRATEGY_NAME);
        Token    token    = createOrderRequest(getToken(data), TYPE_ENTRY);

        if ("Y".equalsIgnoreCase(strategy.getLive())) {
            SmartConnect sc = getSession();
            angelOneService.placeOrder(sc, token);
            log.info("LIVE ENTRY BUY ORDER → {}", token.getSymbol());
        }

        if ("Y".equalsIgnoreCase(strategy.getPapertrade())) {
            saveOrder(data, TYPE_ENTRY, entryPrice, null);
            log.info("PAPER ENTRY BUY ORDER → {}", token.getSymbol());
        }

        sendTelegram(data.getName(), String.format(
                TEMPLATE_ENTRY_FIXED,
                getStrikeLabel(data), entryPrice, currentSL, target));
    }

    private void manageFixed(PreMarketAnalysis data,
                             BigDecimal ce,
                             BigDecimal pe) throws IOException, SmartAPIException {

        BigDecimal current = (direction == TradeDirection.BUY_CE) ? ce : pe;
        Strategy   strategy = strategyRepo.findByName(STRATEGY_NAME);

        // ── SL hit ──────────────────────────────────────────────
        if (current.compareTo(currentSL) <= 0) {

            BigDecimal pnlPts = current.subtract(entryPrice);
            BigDecimal pnlRs  = pnlPts.multiply(BigDecimal.valueOf(lotSize)); // FIX: actual ₹ PnL
            log.info("[FIXED] SL hit | Exit={} PnL={}pts ₹{}", current, pnlPts, pnlRs);

            Token token = createOrderRequest(getToken(data), TYPE_EXIT_SL);

            if ("Y".equalsIgnoreCase(strategy.getLive())) {
                SmartConnect sc = getSession();
                angelOneService.placeOrder(sc, token);
                log.info("LIVE EXIT (SL) SELL ORDER → {}", token.getSymbol());
            }

            if ("Y".equalsIgnoreCase(strategy.getPapertrade())) {
                saveOrder(data, TYPE_EXIT_SL, current, pnlPts);
            }

            sendTelegram(data.getName(), String.format(
                    TEMPLATE_EXIT_SL,
                    getStrikeLabel(data), current, pnlPts, pnlRs));

            lastCompletedDirection = direction;
            resetState();
            return;
        }

        // ── Target hit ──────────────────────────────────────────
        // FIX: was missing live order placement entirely
        if (current.compareTo(target) >= 0) {

            BigDecimal pnlPts = current.subtract(entryPrice);
            BigDecimal pnlRs  = pnlPts.multiply(BigDecimal.valueOf(lotSize)); // FIX: actual ₹ PnL
            log.info("[FIXED] Target hit | Exit={} PnL={}pts ₹{}", current, pnlPts, pnlRs);

            Token token = createOrderRequest(getToken(data), TYPE_EXIT_TARGET);

            // FIX: live order was completely absent in original code
            if ("Y".equalsIgnoreCase(strategy.getLive())) {
                SmartConnect sc = getSession();
                angelOneService.placeOrder(sc, token);
                log.info("LIVE EXIT (TARGET) SELL ORDER → {}", token.getSymbol());
            }

            if ("Y".equalsIgnoreCase(strategy.getPapertrade())) {
                saveOrder(data, TYPE_EXIT_TARGET, current, pnlPts);
            }

            sendTelegram(data.getName(), String.format(
                    TEMPLATE_EXIT_TARGET,
                    getStrikeLabel(data), current, pnlPts, pnlRs));

            lastCompletedDirection = direction;
            resetState();
        }
    }

    // ============================================================
    // ================= TRAILING MODE ============================
    // ============================================================

    private void enterTrailing(PreMarketAnalysis data) throws IOException, SmartAPIException {

        currentSL    = entryPrice.subtract(TRAIL_INITIAL_SL_POINTS);
        highestPrice = entryPrice;
        target       = null;

        log.info("[TRAIL] ENTRY → {} | Entry={} InitialSL={}",
                direction, entryPrice, currentSL);

        Strategy strategy = strategyRepo.findByName(STRATEGY_NAME);
        Token    token    = createOrderRequest(getToken(data), TYPE_ENTRY);

        if ("Y".equalsIgnoreCase(strategy.getLive())) {
            SmartConnect sc = getSession();
            angelOneService.placeOrder(sc, token);
            log.info("LIVE ENTRY BUY ORDER → {}", token.getSymbol());
        }

        if ("Y".equalsIgnoreCase(strategy.getPapertrade())) {
            saveOrder(data, TYPE_ENTRY, entryPrice, null);
            log.info("PAPER ENTRY BUY ORDER → {}", token.getSymbol());
        }

        sendTelegram(data.getName(), String.format(
                TEMPLATE_ENTRY_TRAIL,
                getStrikeLabel(data), entryPrice, currentSL));
    }

    private void manageTrailing(PreMarketAnalysis data,
                                BigDecimal ce,
                                BigDecimal pe) throws IOException, SmartAPIException {

        BigDecimal current  = (direction == TradeDirection.BUY_CE) ? ce : pe;
        Strategy   strategy = strategyRepo.findByName(STRATEGY_NAME);

        // Track highest price
        if (current.compareTo(highestPrice) > 0) {
            highestPrice = current;
            log.debug("[TRAIL] New high: {}", highestPrice);
        }

        // ── Trail SL hit ─────────────────────────────────────────
        if (current.compareTo(currentSL) <= 0) {

            BigDecimal pnlPts = current.subtract(entryPrice);
            BigDecimal pnlRs  = pnlPts.multiply(BigDecimal.valueOf(lotSize)); // FIX: actual ₹ PnL
            log.info("[TRAIL] Trail SL hit | Exit={} PnL={}pts ₹{}", current, pnlPts, pnlRs);

            Token token = createOrderRequest(getToken(data), TYPE_EXIT_TRAIL);

            if ("Y".equalsIgnoreCase(strategy.getLive())) {
                SmartConnect sc = getSession();
                angelOneService.placeOrder(sc, token);
                log.info("LIVE EXIT (TRAIL SL) SELL ORDER → {}", token.getSymbol());
            }

            if ("Y".equalsIgnoreCase(strategy.getPapertrade())) {
                saveOrder(data, TYPE_EXIT_TRAIL, current, pnlPts);
            }

            sendTelegram(data.getName(), String.format(
                    TEMPLATE_EXIT_TRAIL,
                    getStrikeLabel(data), current, pnlPts, pnlRs));

            lastCompletedDirection = direction;
            resetState();
            return;
        }

        // ── Update trailing SL ───────────────────────────────────
        // Design: one step lag ensures SL never chases price too tightly
        //   entry=100, step=10:
        //   high=110 (1 step) → SL = entry + (1-1)*10 = breakeven (100)
        //   high=120 (2 steps) → SL = entry + (2-1)*10 = 110
        //   high=130 (3 steps) → SL = entry + (3-1)*10 = 120
        BigDecimal profit = highestPrice.subtract(entryPrice);

        if (profit.compareTo(TRAIL_LOCK_STEP) >= 0) {

            BigDecimal steps = profit.divide(TRAIL_LOCK_STEP, 0, RoundingMode.DOWN);
            BigDecimal newSL = entryPrice.add(
                    steps.subtract(BigDecimal.ONE).multiply(TRAIL_LOCK_STEP));

            if (newSL.compareTo(currentSL) > 0) {
                log.info("[TRAIL] SL updated: {} → {}", currentSL, newSL);
                currentSL = newSL;
                updateSLInDb(newSL);

                sendTelegram(data.getName(), String.format(
                        TEMPLATE_TRAIL_SL_UPDATE,
                        getStrikeLabel(data), currentSL, highestPrice));
            }
        }
    }

    // ============================================================
    // ================= FORCE EXIT ===============================
    // ============================================================

    private void forceExit(String instrumentName) throws IOException, SmartAPIException {

        Optional<Orders> opt =
                ordersRepo.findTopByNameAndActiveOrderByIdDesc(STRATEGY_NAME, 1);

        if (opt.isEmpty()) {
            log.warn("Force exit triggered but no active order found in DB — resetting state");
            resetState();
            return;
        }

        Orders     order   = opt.get();
        BigDecimal current = webSocketService.getLatestLTP(EXCHANGE, order.getToken());

        if (current == null) {
            log.warn("Force exit: LTP unavailable for token {}", order.getToken());
            return;
        }

        BigDecimal pnlPts = current.subtract(order.getAskPrice());
        // FIX: use stored lotSize for ₹ PnL (direction may be null after restart;
        //      fallback to reading lot size from DB token)
        int resolvedLot   = (lotSize > 1) ? lotSize : resolveLotSize(order.getToken());
        BigDecimal pnlRs  = pnlPts.multiply(BigDecimal.valueOf(resolvedLot));

        Strategy strategy = strategyRepo.findByName(STRATEGY_NAME);
        Token    token    = createOrderRequest(order.getToken(), TYPE_FORCE_EXIT);

        if ("Y".equalsIgnoreCase(strategy.getLive())) {
            SmartConnect sc = getSession();
            angelOneService.placeOrder(sc, token);
            log.info("LIVE FORCE EXIT SELL ORDER → {}", token.getSymbol());
        }

        if ("Y".equalsIgnoreCase(strategy.getPapertrade())) {
            // FIX: pass a reconstructed PreMarketAnalysis context so saveOrder()
            // doesn't silently drop the exit record when data=null.
            // We bypass saveOrder() and write directly so token & symbol are available.
            ordersRepo.findTopByNameAndActiveOrderByIdDesc(STRATEGY_NAME, 1)
                .ifPresent(activeOrder -> {
                	activeOrder.setExitPrice(current.setScale(2, RoundingMode.HALF_UP));
                	activeOrder.setPl(pnlPts.setScale(2, RoundingMode.HALF_UP));
                    activeOrder.setActive(0);
                    activeOrder.setType(TYPE_FORCE_EXIT);
                    ordersRepo.save(activeOrder);
                });
            log.info("PAPER FORCE EXIT recorded");
        }

        // FIX: use order.getSignal() as strike label fallback — direction may be null after restart
        String strikeLabel = (direction != null)
                ? order.getSignal()
                : order.getSignal();

        sendTelegram(instrumentName, String.format(
                TEMPLATE_FORCE_EXIT, strikeLabel, current, pnlPts, pnlRs));

        // FIX: guard — direction may be null after a cold-restart recovery where
        // state reconstruction failed; set lastCompletedDirection only when valid
        if (direction != null) {
            lastCompletedDirection = direction;
        }
        resetState();
    }

    // ============================================================
    // ================= SESSION MANAGEMENT =======================
    // FIX: cache SmartConnect — re-login only when session expires
    // ============================================================

    private SmartConnect getSession() throws IOException, SmartAPIException {
        if (cachedSession == null || LocalDateTime.now().isAfter(sessionExpiresAt)) {
            log.info("Creating new SmartConnect session");
            cachedSession      = angelOne.signIn();
            sessionExpiresAt   = LocalDateTime.now().plusHours(SESSION_TTL_HOURS);
        }
        return cachedSession;
    }

    // ============================================================
    // ================= ORDER BUILDER ============================
    // ============================================================

    private Token createOrderRequest(String strikeToken, String entryType) {
        Token token = new Token();

        Indexes indexes = indexesRepo.findByToken(strikeToken);
        if (indexes != null) {
            token.setSymbol(indexes.getSymbol());
            token.setToken(indexes.getToken());
            token.setExch_seg(indexes.getExchange());
            token.setQuantity(indexes.getLotsize());
            token.setProductType(Constants.PRODUCT_CARRYFORWARD);
            token.setVariety(Constants.VARIETY_NORMAL);
            token.setOrderType(Constants.ORDER_TYPE_MARKET);
            token.setTransactionType(
                    TYPE_ENTRY.equalsIgnoreCase(entryType)
                            ? Constants.TRANSACTION_TYPE_BUY
                            : Constants.TRANSACTION_TYPE_SELL
            );
        } else {
            log.error("createOrderRequest: no Indexes record found for token={}", strikeToken);
        }
        return token;
    }

    // ============================================================
    // ================= HELPERS ==================================
    // ============================================================

    private BigDecimal resolveMidPoint(PreMarketAnalysis data) {
        return (data.getSecondMidPoint() != null)
                ? data.getSecondMidPoint()
                : data.getMidPoint();
    }

    private BigDecimal getPrevHigh(PreMarketAnalysis data) {
        return getPrevHighForDirection(data, direction);
    }

    /** Stateless version used during state recovery (direction passed explicitly) */
    private BigDecimal getPrevHighForDirection(PreMarketAnalysis data, TradeDirection dir) {
        return (dir == TradeDirection.BUY_CE)
                ? data.getCePrevHigh()
                : data.getPePrevHigh();
    }

    private String getStrikeLabel(PreMarketAnalysis data) {
        String strike = data.getAtmStrike().stripTrailingZeros().toPlainString();
        return strike + (direction == TradeDirection.BUY_CE ? " CE" : " PE");
    }

    private boolean isForceExitTime(LocalDateTime now) {
        return now.getHour() > FORCE_EXIT_HOUR ||
               (now.getHour() == FORCE_EXIT_HOUR && now.getMinute() >= FORCE_EXIT_MINUTE);
    }

    private void ensureSubscribed(String token) {
        if (subscribedTokens.add(token)) {
            webSocketService.subscribe(EXCHANGE, token);
            log.info("Subscribed token: {}", token);
        }
    }

    private BigDecimal getLivePrice(String token) {
        BigDecimal price = webSocketService.getLatestLTP(EXCHANGE, token);
        return price == null ? null : price.setScale(2, RoundingMode.HALF_UP);
    }

    /** Resolves lot size from Indexes table; returns 1 as a safe fallback. */
    private int resolveLotSize(String token) {
        Indexes idx = indexesRepo.findByToken(token);
        if (idx == null || idx.getLotsize() <= 0) {
            log.warn("Lot size unavailable for token={} — defaulting to 1", token);
            return 1;
        }
        return idx.getLotsize();
    }

    private void saveOrder(PreMarketAnalysis data, String type, BigDecimal price, BigDecimal pnl) {

        ordersRepo.findTopByNameAndActiveOrderByIdDesc(STRATEGY_NAME, 1).ifPresentOrElse(
            order -> {
                // Closing an existing active order
            	order.setExitPrice(scale(price));
                order.setPl(pnl != null ? scale(pnl) : BigDecimal.ZERO);
                order.setActive(0);
                order.setType(type);
                ordersRepo.save(order);
            },
            () -> {
                // Opening a new order (ENTRY)
                if (data == null) {
                    // FIX: previously this silently dropped the record; now we log loudly
                    log.error("saveOrder: cannot create ENTRY record — PreMarketAnalysis is null for type={}", type);
                    return;
                }
                Orders order = new Orders();
                order.setName(STRATEGY_NAME);
                order.setSymbol(data.getName());
                order.setToken(getToken(data));
                order.setAskPrice(scale(price));
                order.setSl(scale(currentSL));
                order.setActive(1);
                order.setSignal(direction.name());
                order.setExchange(EXCHANGE.name());
                order.setType(type);
                order.setCreatedOn(LocalDateTime.now());
                ordersRepo.save(order);
            }
        );
    }

    private BigDecimal scale(BigDecimal value) {
        return value != null ? value.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }
    private void updateSLInDb(BigDecimal newSL) {
        ordersRepo.findTopByNameAndActiveOrderByIdDesc(STRATEGY_NAME, 1).ifPresent(order -> {
        	order.setSl(scale(newSL));
            ordersRepo.save(order);
        });
    }

    private String getToken(PreMarketAnalysis data) {
        return (direction == TradeDirection.BUY_CE) ? data.getCeToken() : data.getPeToken();
    }

    private void resetState() {
        state         = State.COOLDOWN;
        cooldownUntil = LocalDateTime.now().plusMinutes(COOLDOWN_MINUTES);
        direction     = null;
        entryPrice    = null;
        currentSL     = null;
        target        = null;
        highestPrice  = null;
        lotSize       = 1;
        log.info("State → COOLDOWN until {}", cooldownUntil.format(DATE_FMT));
    }

    private void sendTelegram(String instrument, String body) {
        try {
            String msg = String.format(TELEGRAM_HEADER,
                    STRATEGY_NAME, instrument, body,
                    LocalDateTime.now().format(DATE_FMT));
            telegramService.sendMessage(msg);
        } catch (Exception e) {
            log.error("Telegram send failed", e);
        }
    }
}