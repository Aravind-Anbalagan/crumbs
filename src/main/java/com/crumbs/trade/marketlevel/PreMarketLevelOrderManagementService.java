package com.crumbs.trade.marketlevel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.PreMarketAnalysis;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.PreMarketAnalysisRepo;
import com.crumbs.trade.service.AngelWebSocketService;
import com.crumbs.trade.service.TelegramService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PreMarketLevelOrderManagementService {

    private static final Logger log =
            LoggerFactory.getLogger(PreMarketLevelOrderManagementService.class);

    // ============================================================
    // ================= STATIC CONFIG =============================
    // ============================================================

    private static final String STRATEGY_NAME = "Market_Level";
    private static final ExchangeType EXCHANGE = ExchangeType.NSE_FO;

    private static final BigDecimal STOP_LOSS_POINTS = BigDecimal.valueOf(10);
    private static final BigDecimal TRAIL_START_POINTS = BigDecimal.valueOf(20);
    private static final BigDecimal TRAIL_STEP = BigDecimal.valueOf(5);
    private static final BigDecimal BUFFER_PERCENT = BigDecimal.valueOf(0.002);

    private static final int COOLDOWN_MINUTES = 3;
    private static final int FORCE_EXIT_HOUR = 15;
    private static final int FORCE_EXIT_MINUTE = 20;

    private static final String ORDER_TYPE_ENTRY = "ENTRY";
    private static final String ORDER_TYPE_EXIT = "EXIT";
    private static final String ORDER_TYPE_FORCE_EXIT = "FORCE_EXIT";

    // ================= SL MODE =================

    public enum SlMode {
        TRAILING,
        FIXED_PREV_HIGH
    }

    // 🔥 Change here when needed
    private static final SlMode SL_MODE = SlMode.FIXED_PREV_HIGH;
    // private static final SlMode SL_MODE = SlMode.FIXED_PREV_HIGH;

    // ================= TELEGRAM TEMPLATES =================

    private static final String ENTRY_TEMPLATE =
            "🟢 ENTRY\n" +
            "Strike : %s\n" +
            "Entry  : %.2f\n" +
            "SL     : %.2f";

    private static final String EXIT_SL_TEMPLATE =
            "❌ EXIT (SL)\n" +
            "Strike : %s\n" +
            "Exit   : %.2f\n" +
            "PnL    : %.2f";

    private static final String EXIT_TRAIL_TEMPLATE =
            "🎯 EXIT (TRAIL)\n" +
            "Strike : %s\n" +
            "Exit   : %.2f\n" +
            "PnL    : %.2f";

    private static final String EXIT_FORCE_TEMPLATE =
            "⏰ FORCE EXIT (3:20 PM)\n" +
            "Strike : %s\n" +
            "Exit   : %.2f\n" +
            "PnL    : %.2f";

    private static final String TELEGRAM_HEADER =
            "📊 %s | %s\n\n%s\n🕒 %s";

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss");

    // ============================================================
    // ================= DEPENDENCIES =============================
    // ============================================================

    private final AngelWebSocketService webSocketService;
    private final AngelOne angelOne;
    private final PreMarketAnalysisRepo preMarketRepo;
    private final OrderRepository ordersRepo;
    private final TelegramService telegramService;

    // ============================================================
    // ================= RUNTIME STATE ============================
    // ============================================================

    private TradeState state = TradeState.IDLE;
    private TradeDirection direction;
    private TradeDirection lastCompletedDirection;

    private BigDecimal entryPrice;
    private BigDecimal currentSL;
    private BigDecimal highestPrice;

    private String slOrderId;
    private LocalDateTime cooldownUntil;

    private final Set<String> subscribedTokens =
            ConcurrentHashMap.newKeySet();

    // ============================================================
    // ================= MAIN LOOP ================================
    // ============================================================

    public synchronized void runCycle(String instrumentName) {

        LocalDateTime now = LocalDateTime.now();

        // 3:20 PM Force Exit
        if (state == TradeState.ACTIVE &&
                (now.getHour() > FORCE_EXIT_HOUR ||
                 (now.getHour() == FORCE_EXIT_HOUR &&
                  now.getMinute() >= FORCE_EXIT_MINUTE))) {

            forceExit();
            return;
        }

        Optional<PreMarketAnalysis> optional =
                preMarketRepo.findByNameAndTradingDate(
                        instrumentName, LocalDate.now());

        if (optional.isEmpty()) return;

        PreMarketAnalysis data = optional.get();

        ensureSubscribed(data.getCeToken());
        ensureSubscribed(data.getPeToken());

        BigDecimal ce = getLivePrice(data.getCeToken());
        BigDecimal pe = getLivePrice(data.getPeToken());

        if (ce == null || pe == null) return;

        switch (state) {

            case IDLE -> checkEntry(data, ce, pe);

            case ACTIVE -> manageTrade(data, ce, pe);

            case COOLDOWN -> {
                if (LocalDateTime.now().isAfter(cooldownUntil)) {
                    state = TradeState.IDLE;
                }
            }
        }
    }

    // ============================================================
    // ================= ENTRY LOGIC ==============================
    // ============================================================

    private void checkEntry(PreMarketAnalysis data,
                            BigDecimal ce,
                            BigDecimal pe) {

        if (ordersRepo.findTopByNameAndActiveOrderByIdDesc(
                STRATEGY_NAME, 1).isPresent())
            return;

        BigDecimal mid = data.getMidPoint();
        BigDecimal upper =
                mid.multiply(BigDecimal.ONE.add(BUFFER_PERCENT));
        BigDecimal lower =
                mid.multiply(BigDecimal.ONE.subtract(BUFFER_PERCENT));

        TradeDirection signal = null;

        if (ce.compareTo(upper) > 0 && pe.compareTo(lower) < 0)
            signal = TradeDirection.BUY_CE;

        if (pe.compareTo(upper) > 0 && ce.compareTo(lower) < 0)
            signal = TradeDirection.BUY_PE;

        if (signal == null) return;

        if (lastCompletedDirection != null &&
                signal == lastCompletedDirection)
            return;

        direction = signal;
        entryPrice =
                (direction == TradeDirection.BUY_CE) ? ce : pe;

        if (SL_MODE == SlMode.TRAILING) {
            currentSL = entryPrice.subtract(STOP_LOSS_POINTS);
        } else {
            currentSL = getPreviousHigh(data);
        }

        highestPrice = entryPrice;
        state = TradeState.ACTIVE;
        slOrderId = "TEST-" + System.currentTimeMillis();

        saveEntryOrder(data, getToken(data));

        send(data.getName(),
                String.format(ENTRY_TEMPLATE,
                        getStrikeDisplay(data),
                        entryPrice,
                        currentSL));
    }

    // ============================================================
    // ================= TRADE MANAGEMENT =========================
    // ============================================================

    private void manageTrade(PreMarketAnalysis data,
                             BigDecimal ce,
                             BigDecimal pe) {

        BigDecimal currentPrice =
                (direction == TradeDirection.BUY_CE) ? ce : pe;

        if (currentPrice.compareTo(highestPrice) > 0)
            highestPrice = currentPrice;

        if (currentPrice.compareTo(currentSL) <= 0) {

            closeOrder(currentPrice, ORDER_TYPE_EXIT);

            String template =
                    currentSL.compareTo(entryPrice) >= 0
                            ? EXIT_TRAIL_TEMPLATE
                            : EXIT_SL_TEMPLATE;

            send(data.getName(),
                    String.format(template,
                            getStrikeDisplay(data),
                            currentPrice,
                            currentPrice.subtract(entryPrice)));

            lastCompletedDirection = direction;
            resetState();
            return;
        }

        if (SL_MODE == SlMode.TRAILING) {

            BigDecimal peakProfit =
                    highestPrice.subtract(entryPrice);

            if (peakProfit.compareTo(TRAIL_START_POINTS) >= 0) {

                BigDecimal locked =
                        peakProfit.subtract(TRAIL_START_POINTS)
                                .divide(TRAIL_STEP, 0, RoundingMode.DOWN)
                                .multiply(TRAIL_STEP);

                BigDecimal newSL =
                        entryPrice.add(locked);

                if (newSL.compareTo(currentSL) > 0) {
                    currentSL = newSL;
                    updateSLInDb(newSL);
                }
            }
        }
    }

    // ============================================================
    // ================= FORCE EXIT ===============================
    // ============================================================

    private void forceExit() {

        Optional<Orders> optional =
                ordersRepo.findTopByNameAndActiveOrderByIdDesc(
                        STRATEGY_NAME, 1);

        if (optional.isEmpty()) return;

        Orders order = optional.get();

        BigDecimal currentPrice =
                webSocketService.getLatestLTP(
                        EXCHANGE, order.getToken());

        if (currentPrice == null) return;

        closeOrder(currentPrice, ORDER_TYPE_FORCE_EXIT);

        send(order.getSymbol(),
                String.format(EXIT_FORCE_TEMPLATE,
                        order.getSignal(),
                        currentPrice,
                        currentPrice.subtract(
                                BigDecimal.valueOf(order.getAskPrice()))));

        lastCompletedDirection = direction;
        resetState();
    }

    // ============================================================
    // ================= HELPER METHODS ===========================
    // ============================================================

    private BigDecimal getPreviousHigh(PreMarketAnalysis data) {
        return (direction == TradeDirection.BUY_CE)
                ? data.getCePrevHigh()
                : data.getPePrevHigh();
    }

    private String getStrikeDisplay(PreMarketAnalysis data) {

        String strike =
                data.getAtmStrike()
                        .stripTrailingZeros()
                        .toPlainString();

        return (direction == TradeDirection.BUY_CE)
                ? strike + " CE"
                : strike + " PE";
    }

    private void saveEntryOrder(PreMarketAnalysis data,
                                String token) {

        Orders order = new Orders();
        order.setOrderid(slOrderId);
        order.setCreatedOn(LocalDateTime.now().toString());
        order.setName(STRATEGY_NAME);
        order.setSymbol(data.getName());
        order.setToken(token);
        order.setAskPrice(entryPrice.intValue());
        order.setSl(currentSL.intValue());
        order.setActive(1);
        order.setSignal(direction.name());
        order.setExchange(EXCHANGE.name());
        order.setType(ORDER_TYPE_ENTRY);

        ordersRepo.save(order);
    }

    private void updateSLInDb(BigDecimal newSL) {
        ordersRepo.findTopByNameAndActiveOrderByIdDesc(
                STRATEGY_NAME, 1)
                .ifPresent(order -> {
                    order.setSl(newSL.intValue());
                    ordersRepo.save(order);
                });
    }

    private void closeOrder(BigDecimal exitPrice,
                            String exitType) {

        ordersRepo.findTopByNameAndActiveOrderByIdDesc(
                STRATEGY_NAME, 1)
                .ifPresent(order -> {

                    int exit = exitPrice.intValue();
                    int pnl = exit - order.getAskPrice();

                    order.setExitPrice(exit);
                    order.setPl(pnl);
                    order.setActive(0);
                    order.setType(exitType);

                    ordersRepo.save(order);
                });
    }

    private void resetState() {
        state = TradeState.COOLDOWN;
        cooldownUntil =
                LocalDateTime.now().plusMinutes(COOLDOWN_MINUTES);

        direction = null;
        entryPrice = null;
        currentSL = null;
        highestPrice = null;
        slOrderId = null;
    }

    private String getToken(PreMarketAnalysis data) {
        return (direction == TradeDirection.BUY_CE)
                ? data.getCeToken()
                : data.getPeToken();
    }

    private BigDecimal getLivePrice(String token) {
        BigDecimal price =
                webSocketService.getLatestLTP(
                        EXCHANGE, token);

        return price == null
                ? null
                : price.setScale(2, RoundingMode.HALF_UP);
    }

    private void ensureSubscribed(String token) {
        if (subscribedTokens.add(token)) {
            webSocketService.subscribe(EXCHANGE, token);
        }
    }

    private void send(String instrument,
                      String body) {

        try {
            String message =
                    String.format(TELEGRAM_HEADER,
                            STRATEGY_NAME,
                            instrument,
                            body,
                            LocalDateTime.now()
                                    .format(DATE_FORMAT));

            telegramService.sendMessage(message);

        } catch (Exception e) {
            log.error("Telegram failed", e);
        }
    }
}