package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.entity.Vix;
import com.crumbs.trade.entity.StraddleIntraday;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.repo.VixRepo;
import com.crumbs.trade.repo.ShortStraddleRepository;

@Service
public class HeikinPsarExecutionService {

    private static final Logger logger = LogManager.getLogger(HeikinPsarExecutionService.class);

    // =========================================================
    // 🛠️ 1. SYSTEM CONFIGURATION & GLOBAL TOGGLES
    // =========================================================
    private static final String TRADE_MODE = "SCALPING"; // "SCALPING" or "TREND_FOLLOWING"
    private static final boolean IS_OPTION_BUYER = true; // true = Option Buying, false = Option Selling

    private static final BigDecimal SCALP_TARGET_POINTS = new BigDecimal("25.00");
    private static final BigDecimal SCALP_SL_POINTS = new BigDecimal("10.00");

    private static final BigDecimal NIFTY_BIG_CANDLE_THRESHOLD = new BigDecimal("25.00");
    private static final BigDecimal CRUDE_BIG_CANDLE_THRESHOLD = new BigDecimal("35.00");

    private static final BigDecimal NIFTY_RETRACEMENT_PERCENT = new BigDecimal("0.50");
    private static final BigDecimal CRUDE_RETRACEMENT_PERCENT = new BigDecimal("1.00");

    // =========================================================
    // 🏦 2. INSTRUMENT & STATUS CONSTANTS
    // =========================================================
    private static final String NIFTY = "SAMCO_NIFTY";
    private static final String CRUDEOIL = "CRUDEOIL";
    private static final String SAMCO_CRUDEOIL = "SAMCO_CRUDEOIL";

    private static final String EXCHANGE_NSE = "NSE";
    private static final String EXCHANGE_NFO = "NFO";
    private static final String EXCHANGE_MCX = "MCX";

    private static final String TF_FIVE_MIN = "FIVE_MINUTE";
    private static final String ACTIVE_YES = "Y";
    private static final String HEIKIN_SIGNAL = "HEIKIN_PSAR";

    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_INACTIVE = 0;
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String PHASE_ENTRY = "ENTRY";
    private static final String PHASE_EXIT = "EXIT";

    private static final long RETRACEMENT_TIMEOUT_MINUTES = 15L;
    private static final long RETRACEMENT_LOG_THROTTLE_MS = 60000L;

    @Autowired private ChartService chartService;
    @Autowired private StrategyRepo strategyRepo;
    @Autowired private VixRepo vixRepo;
    @Autowired private OrderRepository ordersRepository;
    @Autowired private OrderService orderService;
    @Autowired private TelegramService telegramService;
    @Autowired private ShortStraddleRepository straddleRepository;
    @Autowired private AngelWebSocketService angelWebSocketService;

    // ✅ INJECT MASTER RISK ENGINE TO HANDLE SAFE EXITS
    @Autowired private MonitorOrderService monitorOrderService;

    // =========================================================
    // 🔥 IN-MEMORY CACHES
    // =========================================================
    private final Map<String, Orders> pendingRetracementsCache = new ConcurrentHashMap<>();
    private final Map<String, Orders> activeScalpCache = new ConcurrentHashMap<>();
    private static final long SUBSCRIPTION_RETRY_MS = 30000L;

    private final Map<String, Strategy> strategyCache = new ConcurrentHashMap<>();
    private final Map<String, Long> subscriptionCooldownCache = new ConcurrentHashMap<>();
    private final Map<String, Long> retracementLastLogCache = new ConcurrentHashMap<>();

    public void restoreStateOnStartup() {
        try {
            List<Orders> pending = ordersRepository.findByStatusAndActive("PENDING_RETRACEMENT", STATUS_ACTIVE);
            for (Orders order : pending) pendingRetracementsCache.put(order.getName(), order);

            List<Orders> openTrades = ordersRepository.findByStatusAndActive(STATUS_OPEN, STATUS_ACTIVE);
            for (Orders order : openTrades) activeScalpCache.put(order.getName(), order);

            logger.info("✅ Restored Pending={} Active={}", pending.size(), openTrades.size());
        } catch (Exception e) {
            logger.error("Failed restoring cache", e);
        }
    }

    // =========================================================
    // 🚀 CORE EXECUTIONS (Triggered by 5-Min Cron)
    // =========================================================
    @Transactional
    public void commonExecutionNifty() {
        try { executeNiftyInternal(); }
        catch (SmartAPIException e) { logApiError(NIFTY, e); }
        catch (Exception e) { logGeneralError(NIFTY, e); }
    }

    @Transactional
    public void commonExecutionMcx() {
        try { executeMcxInternal(); }
        catch (SmartAPIException e) { logApiError(CRUDEOIL, e); }
        catch (Exception e) { logGeneralError(CRUDEOIL, e); }
    }

    private void executeNiftyInternal() throws Exception, SmartAPIException {
        Strategy strategy = getStrategyCached(NIFTY);
        if (!isStrategyActive(strategy)) return;

        String to = chartService.getDate("TO", EXCHANGE_NSE, 1);
        String from;

        Optional<Vix> lastRecord = vixRepo.findFirstByNameOrderByTimestampDesc(NIFTY);
        if (lastRecord.isPresent()) {
            String rawTimestamp = lastRecord.get().getTimestamp();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            from = OffsetDateTime.parse(rawTimestamp).format(formatter);
        } else {
            from = chartService.getDate("FROM", EXCHANGE_NSE, 1);
        }

        chartService.readChartData(TF_FIVE_MIN, EXCHANGE_NFO, false, NIFTY, from, to, strategy.getTradingsymbol(), "SAMCO");
        vixRepo.findFirstByNameOrderByTimestampDesc(NIFTY).ifPresent(candle -> evaluateAndExecuteTrade(candle, strategy));
    }

    private void executeMcxInternal() throws Exception, SmartAPIException {
        Strategy strategy = getStrategyCached(SAMCO_CRUDEOIL);
        if (!isStrategyActive(strategy)) return;

        String to = chartService.getDate("TO", EXCHANGE_MCX, 1);
        String from;

        Optional<Vix> lastRecord = vixRepo.findFirstByNameOrderByTimestampDesc(CRUDEOIL);
        if (lastRecord.isPresent()) {
            from = ChartService.formatDateTime(lastRecord.get().getTimestamp());
        } else {
            from = chartService.getDate("FROM", EXCHANGE_MCX, 1);
        }

        chartService.readChartData(TF_FIVE_MIN, strategy.getExchange(), false, CRUDEOIL, from, to, getStrategyCached(CRUDEOIL).getTradingsymbol(), "SAMCO");
        vixRepo.findFirstByNameOrderByTimestampDesc(CRUDEOIL).ifPresent(candle -> evaluateAndExecuteTrade(candle, strategy));
    }

    // =========================================================
    // 🧠 THE BRAIN: 5-MIN CANDLE EVALUATION (ENTRIES & EXITS)
    // =========================================================
    private void evaluateAndExecuteTrade(Vix latestCandle, Strategy strategy) {
        if (latestCandle == null || strategy == null) return;

        String instrument = strategy.getTradingsymbol();
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));

        String baseSymbol = getBaseSymbol(strategy.getName());
        String tradeName = "HEIKIN_" + baseSymbol;

        // ✅ FIXED TIMES: Stop entering new trades at 15:15 and 23:00
        boolean isNiftyValid = instrument.contains("NIFTY") && !now.isBefore(LocalTime.of(9, 30)) && now.isBefore(LocalTime.of(15, 15));
        boolean isCrudeValid = instrument.contains("CRUDEOIL") && !now.isBefore(LocalTime.of(16, 0)) && now.isBefore(LocalTime.of(23, 0));

        // EOD triggers for the 5-min loop backup
        boolean isNiftySquareOff = instrument.contains("NIFTY") && !now.isBefore(LocalTime.of(15, 15));
        boolean isCrudeSquareOff = instrument.contains("CRUDEOIL") && !now.isBefore(LocalTime.of(23, 0));// ---------------------------------------------------------
        // PHASE 1: EVALUATE OPEN TRADES
        // ---------------------------------------------------------
        Orders openTrade = ordersRepository.findByNameAndActive(tradeName, STATUS_ACTIVE);

        if (openTrade != null && STATUS_OPEN.equalsIgnoreCase(openTrade.getStatus())) {
            boolean tradeClosedThisCycle = false;

            if (isNiftySquareOff || isCrudeSquareOff) {
                logger.info("🕒 [{}][EXIT] Square-off time reached.", tradeName);
                processExit(openTrade, latestCandle, "EOD_SQUARE_OFF", strategy);
                tradeClosedThisCycle = true;
            }
            else if (!IS_OPTION_BUYER || "TREND_FOLLOWING".equalsIgnoreCase(TRADE_MODE)) {
                String currentSignal = latestCandle.getSignal();

                if (currentSignal != null && !"NONE".equalsIgnoreCase(currentSignal)) {
                    boolean isReversal = IS_OPTION_BUYER
                            ? ("CE".equalsIgnoreCase(openTrade.getOptionType()) && "SELL".equalsIgnoreCase(currentSignal))
                            || ("PE".equalsIgnoreCase(openTrade.getOptionType()) && "BUY".equalsIgnoreCase(currentSignal))
                            : ("CE".equalsIgnoreCase(openTrade.getOptionType()) && "BUY".equalsIgnoreCase(currentSignal))
                            || ("PE".equalsIgnoreCase(openTrade.getOptionType()) && "SELL".equalsIgnoreCase(currentSignal));

                    if (isReversal) {
                        logger.info("🔄 [{}][EXIT] Trend Reversal detected. Closing position.", tradeName);
                        processExit(openTrade, latestCandle, "TREND_REVERSAL", strategy);
                        tradeClosedThisCycle = true;
                    }
                }
            }
            if (!tradeClosedThisCycle) return;
            openTrade = null;
        }

        // ---------------------------------------------------------
        // PHASE 2: EVALUATE NEW ENTRIES & PENDING RETRACEMENTS
        // ---------------------------------------------------------
        if (!isNiftyValid && !isCrudeValid) return;

        String currentSignal = latestCandle.getSignal();
        if (currentSignal == null || "NONE".equalsIgnoreCase(currentSignal)) return;

        Orders stalePendingOrder = ordersRepository.findByNameAndStatusAndActive(tradeName, "PENDING_RETRACEMENT", STATUS_ACTIVE);

        if (stalePendingOrder != null) {
            String pendingDirection = stalePendingOrder.getType();
            if (!currentSignal.equalsIgnoreCase(pendingDirection)) {
                logger.warn("🚫 [{}][OVERRIDE] Trend Reversed to {}! Abandoning old retracement. Marking as FAILED.",
                        tradeName, currentSignal);

                stalePendingOrder.setStatus("FAILED_NEW_SIGNAL");
                stalePendingOrder.setActive(STATUS_INACTIVE);
                ordersRepository.save(stalePendingOrder);

                pendingRetracementsCache.remove(tradeName);
                retracementLastLogCache.remove(tradeName);
                if (telegramService != null) telegramService.sendMessage(String.format("🚫 **[%s] RETRACEMENT ABORTED**\nNew %s signal formed.", tradeName, currentSignal));
            } else {
                return; // Still waiting for valid retracement
            }
        }

        if (!canEnterNewTrade(tradeName, currentSignal)) return;

        BigDecimal open = latestCandle.getOpen();
        BigDecimal close = latestCandle.getClose();
        BigDecimal bodySize = close.subtract(open).abs();

        BigDecimal threshold = baseSymbol.contains("NIFTY") ? NIFTY_BIG_CANDLE_THRESHOLD : CRUDE_BIG_CANDLE_THRESHOLD;
        boolean isBigCandle = bodySize.compareTo(threshold) >= 0;

        if (isBigCandle) {
            BigDecimal retracementPercent = baseSymbol.contains("NIFTY") ? NIFTY_RETRACEMENT_PERCENT : CRUDE_RETRACEMENT_PERCENT;
            BigDecimal retracementDiscount = bodySize.multiply(retracementPercent);

            BigDecimal targetSpotPrice = "BUY".equalsIgnoreCase(currentSignal)
                    ? close.subtract(retracementDiscount)
                    : close.add(retracementDiscount);

            logger.info("⚡ [{}][BIG CANDLE] Body: {} pts >= Threshold. Entering Retracement Mode ({}%). Target Spot: ₹{}",
                    tradeName, bodySize, retracementPercent.multiply(new BigDecimal("100")), targetSpotPrice);

            savePendingRetracementOrder(strategy, latestCandle, currentSignal, targetSpotPrice, tradeName);
        } else {
            logger.info("🚀 [{}][SMALL CANDLE] Executing INSTANT Market Order!", tradeName);
            executeInstantAtmOrder(strategy, currentSignal, tradeName);
        }
    }

    // =========================================================
    // ⚡ FAST-LOOPS (Triggered by Scheduler)
    // =========================================================
    @Transactional
    public void monitorPendingRetracements() {
        if (pendingRetracementsCache.isEmpty()) return;

        LocalDateTime nowIst = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));

        for (Orders order : pendingRetracementsCache.values()) {
            String tradeName = order.getName();

            if (order.getCreatedOn() != null) {
                long waitedMinutes = java.time.Duration.between(order.getCreatedOn(), nowIst).toMinutes();
                if (waitedMinutes >= RETRACEMENT_TIMEOUT_MINUTES) {
                    expirePendingRetracement(order, "FAILED_TIMEOUT",
                            String.format("⌛ **[%s] RETRACEMENT TIMED OUT**\nWaited %d min, target ₹%.2f was never reached.",
                                    tradeName, waitedMinutes, order.getTargetSpotPrice()));
                    continue;
                }
            }

            BigDecimal liveSpotPrice = chartService.getCurrentPrice(tradeName);
            if (liveSpotPrice == null || liveSpotPrice.compareTo(BigDecimal.ZERO) == 0) continue;

            boolean isBuyTriggered = "BUY".equalsIgnoreCase(order.getType()) && liveSpotPrice.compareTo(order.getTargetSpotPrice()) <= 0;
            boolean isSellTriggered = "SELL".equalsIgnoreCase(order.getType()) && liveSpotPrice.compareTo(order.getTargetSpotPrice()) >= 0;

            if (isBuyTriggered || isSellTriggered) {
                pendingRetracementsCache.remove(tradeName);
                retracementLastLogCache.remove(tradeName);

                String baseSymbol = getBaseSymbol(tradeName);
                Strategy strategyConfig = "NIFTY".equalsIgnoreCase(baseSymbol) ? getStrategyCached(NIFTY) : getStrategyCached(SAMCO_CRUDEOIL);

                if (strategyConfig == null) continue;

                BigDecimal finalLivePremium = getCurrentOptionPremium(baseSymbol, order.getStrike(), order.getOptionType());
                if (finalLivePremium != null && finalLivePremium.compareTo(BigDecimal.ZERO) > 0) order.setAskPrice(finalLivePremium);

                order.setStatus("PROCESSED_TO_OPEN");
                order.setActive(STATUS_INACTIVE);
                ordersRepository.save(order);

                executeInstantAtmOrder(strategyConfig, order.getType(), tradeName);
            }
        }
    }

    private void expirePendingRetracement(Orders order, String status, String telegramMessage) {
        String tradeName = order.getName();
        pendingRetracementsCache.remove(tradeName);
        retracementLastLogCache.remove(tradeName);

        order.setStatus(status);
        order.setActive(STATUS_INACTIVE);
        ordersRepository.save(order);

        if (telegramService != null) telegramService.sendMessage(telegramMessage);
    }

    @Transactional
    public void monitorActiveScalpTrades() {
        if (!"SCALPING".equalsIgnoreCase(TRADE_MODE) || !IS_OPTION_BUYER) return;
        if (activeScalpCache.isEmpty()) return;

        Orders openNifty = activeScalpCache.get("HEIKIN_NIFTY");
        if (openNifty != null && STATUS_OPEN.equalsIgnoreCase(openNifty.getStatus())) {
            evaluateScalpExit(openNifty, getStrategyCached(NIFTY));
        }

        Orders openCrude = activeScalpCache.get("HEIKIN_CRUDEOIL");
        if (openCrude != null && STATUS_OPEN.equalsIgnoreCase(openCrude.getStatus())) {
            evaluateScalpExit(openCrude, getStrategyCached(SAMCO_CRUDEOIL));
        }
    }

    private void evaluateScalpExit(Orders openTrade, Strategy strategy) {
        // ✅ 1. GUARANTEED FAST-LOOP EOD SQUARE-OFF
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        String tradeName = openTrade.getName();

        if (tradeName.contains("NIFTY") && !now.isBefore(LocalTime.of(15, 15))) {
            logger.info("🕒 [{}][FAST-LOOP] 3:15 PM Square-off reached.", tradeName);
            processExit(openTrade, null, "EOD_SQUARE_OFF", strategy);
            return;
        }
        if (tradeName.contains("CRUDEOIL") && !now.isBefore(LocalTime.of(23, 0))) {
            logger.info("🕒 [{}][FAST-LOOP] 11:00 PM Square-off reached.", tradeName);
            processExit(openTrade, null, "EOD_SQUARE_OFF", strategy);
            return;
        }

        // 2. Existing PnL Logic
        String baseSymbol = getBaseSymbol(openTrade.getName());
        BigDecimal currentPremium = getCurrentOptionPremium(baseSymbol, openTrade.getStrike(), openTrade.getOptionType());
        BigDecimal entryPremium = openTrade.getAskPrice();

        if (currentPremium != null && entryPremium != null) {
            BigDecimal pointsGained = currentPremium.subtract(entryPremium);

            if (pointsGained.compareTo(SCALP_TARGET_POINTS) >= 0) {
                logger.info("🎯 [{}][FAST-LOOP] Target Hit! Secured: +{} pts", openTrade.getName(), pointsGained);
                processExit(openTrade, null, "SCALP_TARGET_HIT", strategy);
            }
            else if (pointsGained.compareTo(SCALP_SL_POINTS.negate()) <= 0) {
                logger.info("🛑 [{}][FAST-LOOP] Stop Loss Hit! Loss: {} pts", openTrade.getName(), pointsGained);
                processExit(openTrade, null, "SCALP_SL_HIT", strategy);
            }
        }
    }

    // =========================================================
    // 🧰 BROKER EXECUTION & STATE MANAGEMENT
    // =========================================================

    private void savePendingRetracementOrder(Strategy strategyConfig, Vix candle, String signal, BigDecimal targetSpotPrice, String tradeName) {
        String baseSymbol = getBaseSymbol(strategyConfig.getName());
        BigDecimal atmStrike = calculateAtmStrike(baseSymbol, candle.getClose());

        String optionType = "";
        if ("BUY".equalsIgnoreCase(signal)) optionType = IS_OPTION_BUYER ? "CE" : "PE";
        else if ("SELL".equalsIgnoreCase(signal)) optionType = IS_OPTION_BUYER ? "PE" : "CE";

        StraddleIntraday optionData = straddleRepository.findLatestBySymbolAndStrike(baseSymbol, atmStrike).orElse(null);
        String optionToken = null;
        String optionSymbol = null;
        BigDecimal entryPremium = null;

        if (optionData != null) {
            optionToken = "CE".equalsIgnoreCase(optionType) ? optionData.getCeToken() : optionData.getPeToken();
            optionSymbol = "CE".equalsIgnoreCase(optionType) ? optionData.getCeSymbol() : optionData.getPeSymbol();
            entryPremium = "CE".equalsIgnoreCase(optionType) ? optionData.getCePrice() : optionData.getPePrice();
        }

        if (entryPremium == null || entryPremium.compareTo(BigDecimal.ZERO) == 0) {
            entryPremium = getCurrentOptionPremium(baseSymbol, atmStrike, optionType);
        }
        if (entryPremium == null) entryPremium = BigDecimal.ZERO;

        int quantity = getLotSize(baseSymbol, strategyConfig);
        String exchange = strategyConfig.getExchange() != null ? strategyConfig.getExchange() : ("NIFTY".equalsIgnoreCase(baseSymbol) ? "NFO" : "MCX");

        Orders pendingOrder = new Orders();
        pendingOrder.setName(tradeName);
        pendingOrder.setToken(optionToken);
        pendingOrder.setSymbol(optionSymbol);
        pendingOrder.setExchange(exchange);
        pendingOrder.setQuantity(quantity);
        pendingOrder.setSignal(HEIKIN_SIGNAL);
        pendingOrder.setType(signal);
        pendingOrder.setOptionType(optionType);
        pendingOrder.setSide(optionType);
        pendingOrder.setTradeCycleId(UUID.randomUUID().toString());
        pendingOrder.setTargetSpotPrice(targetSpotPrice);
        pendingOrder.setStrike(atmStrike);
        pendingOrder.setAskPrice(entryPremium);

        if (entryPremium.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal slPrice = IS_OPTION_BUYER ? entryPremium.subtract(SCALP_SL_POINTS) : entryPremium.add(SCALP_SL_POINTS);
            BigDecimal targetPrice = IS_OPTION_BUYER ? entryPremium.add(SCALP_TARGET_POINTS) : entryPremium.subtract(SCALP_TARGET_POINTS);
            pendingOrder.setSl(slPrice.setScale(2, RoundingMode.HALF_UP));
            pendingOrder.setTarget(targetPrice.setScale(2, RoundingMode.HALF_UP));
            pendingOrder.setBreakeven(entryPremium.setScale(2, RoundingMode.HALF_UP));
        }

        pendingOrder.setStatus("PENDING_RETRACEMENT");
        pendingOrder.setTradePhase("SCANNING");
        pendingOrder.setActive(STATUS_ACTIVE);
        pendingOrder.setCreatedOn(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));

        Orders savedOrder = ordersRepository.save(pendingOrder);
        pendingRetracementsCache.put(tradeName, savedOrder);

        if (telegramService != null) {
            telegramService.sendMessage(String.format("⚡ **[%s] SCANNING**\nSignal: %s\nTarget Spot: ₹%.2f\nWaiting for pullback...", tradeName, signal, targetSpotPrice));
        }
    }

    private void executeInstantAtmOrder(Strategy strategyConfig, String signal, String tradeName) {
        BigDecimal liveSpotPrice = chartService.getCurrentPrice(strategyConfig.getName());
        if (liveSpotPrice == null) return;

        String baseSymbol = getBaseSymbol(strategyConfig.getName());
        BigDecimal atmStrike = calculateAtmStrike(baseSymbol, liveSpotPrice);

        StraddleIntraday optionData = straddleRepository.findLatestBySymbolAndStrike(baseSymbol, atmStrike).orElse(null);
        if (optionData == null) return;

        String orderType = IS_OPTION_BUYER ? "BUY" : "SELL";
        String optionType = "BUY".equalsIgnoreCase(signal) ? (IS_OPTION_BUYER ? "CE" : "PE") : (IS_OPTION_BUYER ? "PE" : "CE");

        String optionToken = "CE".equalsIgnoreCase(optionType) ? optionData.getCeToken() : optionData.getPeToken();
        String optionSymbol = "CE".equalsIgnoreCase(optionType) ? optionData.getCeSymbol() : optionData.getPeSymbol();

        BigDecimal entryPremium = getCurrentOptionPremium(baseSymbol, atmStrike, optionType);
        if (entryPremium == null || entryPremium.compareTo(BigDecimal.ZERO) == 0) {
            entryPremium = "CE".equalsIgnoreCase(optionType) ? optionData.getCePrice() : optionData.getPePrice();
        }
        if (entryPremium == null) return;

        int quantity = getLotSize(baseSymbol, strategyConfig);
        String exchange = strategyConfig.getExchange() != null ? strategyConfig.getExchange() : ("NIFTY".equalsIgnoreCase(baseSymbol) ? "NFO" : "MCX");

        boolean isLive = "Y".equalsIgnoreCase(strategyConfig.getLive());
        Orders order = null;

        try {
            Token t = new Token();
            t.setToken(optionToken);
            t.setSymbol(optionSymbol);
            t.setStrike(atmStrike);
            t.setName(strategyConfig.getName());
            t.setExch_seg(exchange);
            t.setQuantity(quantity);

            if (isLive) {
                orderService.orderPlaceWithToken(t, strategyConfig.getName(), orderType, true);
                order = ordersRepository.findByNameAndTokenAndActive(strategyConfig.getName(), optionToken, STATUS_ACTIVE).orElse(null);
                if (order == null) order = ordersRepository.findByNameAndTokenAndActive(tradeName, optionToken, STATUS_ACTIVE).orElse(null);
            }

            if (order == null) order = new Orders();

            // ✅ PAPER TRADE FIX: Apply "1" marker if paper trade
            if (!isLive) order.setOrderid("1");

            order.setToken(optionToken);
            order.setSymbol(optionSymbol);
            order.setExchange(exchange);
            order.setName(tradeName);
            order.setQuantity(quantity);
            order.setSignal(HEIKIN_SIGNAL);
            order.setType(orderType);
            order.setOptionType(optionType);
            order.setSide(optionType);
            order.setTradeCycleId(UUID.randomUUID().toString());
            order.setAskPrice(entryPremium);
            order.setStrike(atmStrike);

            if (entryPremium.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal slPrice = IS_OPTION_BUYER ? entryPremium.subtract(SCALP_SL_POINTS) : entryPremium.add(SCALP_SL_POINTS);
                BigDecimal targetPrice = IS_OPTION_BUYER ? entryPremium.add(SCALP_TARGET_POINTS) : entryPremium.subtract(SCALP_TARGET_POINTS);
                order.setSl(slPrice.setScale(2, RoundingMode.HALF_UP));
                order.setTarget(targetPrice.setScale(2, RoundingMode.HALF_UP));
                order.setBreakeven(entryPremium.setScale(2, RoundingMode.HALF_UP));
            }

            order.setStatus(STATUS_OPEN);
            order.setTradePhase(PHASE_ENTRY);
            order.setActive(STATUS_ACTIVE);
            if (order.getCreatedOn() == null) order.setCreatedOn(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));

            Orders savedOrder = ordersRepository.save(order);
            activeScalpCache.put(tradeName, savedOrder);

            if (telegramService != null) {
                telegramService.sendMessage(String.format("✅ **[%s] FILLED [%s]**\nSide: %s %s\nStrike: %.0f\nPremium: ₹%.2f",
                        TRADE_MODE, (isLive ? "LIVE" : "PAPER"), orderType, optionType, atmStrike, entryPremium));
            }

        } catch (Exception | SmartAPIException e) {
            logger.error("❌ Execution Failed: {}", e.getMessage());
        }
    }

    private void processExit(Orders openTrade, Vix exitCandle, String reason, Strategy strategyConfig) {
        String tradeName = openTrade.getName();

        try {
            Orders removedFromCache = activeScalpCache.remove(tradeName);
            if (removedFromCache == null && !STATUS_OPEN.equalsIgnoreCase(openTrade.getStatus())) return;

            pendingRetracementsCache.remove(tradeName);

            logger.info("🛡️ [{}][EXIT] Routing exit through Master Risk Engine...", tradeName);

            // ✅ ROUTE ALL EXITS THROUGH MASTER RISK ENGINE
            // This safely bypasses OrderService, fetches true TradeBook price, updates PnL mathematically correctly, and saves to DB.
            monitorOrderService.forceExit(List.of(openTrade), tradeName, reason);

        } catch (Exception e) {
            logger.error("❌ [{}][EXIT] Error closing trade: {}", tradeName, e.getMessage());
        }
    }

    // =========================================================
    // 🛡️ UTILITIES & GUARDS
    // =========================================================

    private int getLotSize(String baseSymbol, Strategy strategyConfig) {
        if (strategyConfig != null && strategyConfig.getQuantity() > 0) return strategyConfig.getQuantity();
        if (baseSymbol != null && baseSymbol.contains("NIFTY")) return 65;
        if (baseSymbol != null && baseSymbol.contains("CRUDEOIL")) return 100;
        if (baseSymbol != null && baseSymbol.contains("SENSEX")) return 20;
        return 1;
    }

    private boolean canEnterNewTrade(String tradeName, String currentSignal) {
        Optional<Orders> lastOrderOpt = ordersRepository.findTopByNameOrderByClosedOnDesc(tradeName);
        if (!lastOrderOpt.isPresent()) return true;

        Orders lastOrder = lastOrderOpt.get();
        String lastExitReason = lastOrder.getExitReason();
        String lastTradeDirection = IS_OPTION_BUYER ? ("CE".equalsIgnoreCase(lastOrder.getOptionType()) ? "BUY" : "SELL") : ("CE".equalsIgnoreCase(lastOrder.getOptionType()) ? "SELL" : "BUY");

        if (("SCALP_SL_HIT".equalsIgnoreCase(lastExitReason) || "SCALP_TARGET_HIT".equalsIgnoreCase(lastExitReason) || "TREND_REVERSAL".equalsIgnoreCase(lastExitReason))
                && currentSignal.equalsIgnoreCase(lastTradeDirection)) {
            LocalDateTime closedOn = lastOrder.getClosedOn();
            LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
            if (closedOn != null && closedOn.isAfter(now.minusMinutes(3))) {
                logger.info("⏳ [{}][BLOCKED] Ignoring {} signal to prevent whipsaw. Waiting 3 mins...", tradeName, currentSignal);
                return false;
            }
        }
        return true;
    }

    private BigDecimal getCurrentOptionPremium(String baseSymbol, BigDecimal strike, String optionType) {
        StraddleIntraday optionData = straddleRepository.findLatestBySymbolAndStrike(baseSymbol, strike).orElse(null);
        if (optionData == null) return null;

        String optionToken = "CE".equalsIgnoreCase(optionType) ? optionData.getCeToken() : optionData.getPeToken();
        BigDecimal dbFallbackPrice = "CE".equalsIgnoreCase(optionType) ? optionData.getCePrice() : optionData.getPePrice();
        if (optionToken == null || optionToken.isEmpty()) return null;

        ExchangeType exchangeType = "CRUDEOIL".equalsIgnoreCase(baseSymbol) ? ExchangeType.MCX_FO : ExchangeType.NSE_FO;
        BigDecimal livePrice = angelWebSocketService.getLatestLTP(exchangeType, optionToken);

        if (livePrice == null || livePrice.compareTo(BigDecimal.ZERO) == 0) {
            String subscriptionKey = exchangeType.name() + "_" + optionToken;
            long now = System.currentTimeMillis();
            if ((now - subscriptionCooldownCache.getOrDefault(subscriptionKey, 0L)) > SUBSCRIPTION_RETRY_MS) {
                subscriptionCooldownCache.put(subscriptionKey, now);
                angelWebSocketService.subscribe(exchangeType, optionToken);
            }
            return dbFallbackPrice;
        }
        return livePrice;
    }

    private String getBaseSymbol(String tradeName) {
        if (tradeName.contains("NIFTY")) return "NIFTY";
        if (tradeName.contains("CRUDEOIL")) return "CRUDEOIL";
        if (tradeName.contains("SENSEX")) return "SENSEX";
        return tradeName;
    }

    private BigDecimal calculateAtmStrike(String baseSymbol, BigDecimal spotPrice) {
        if ("NIFTY".equalsIgnoreCase(baseSymbol)) return spotPrice.divide(new BigDecimal("50"), 0, RoundingMode.HALF_UP).multiply(new BigDecimal("50"));
        if ("CRUDEOIL".equalsIgnoreCase(baseSymbol)) return spotPrice.divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
        if ("SENSEX".equalsIgnoreCase(baseSymbol)) return spotPrice.divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
        return spotPrice.setScale(0, RoundingMode.HALF_UP);
    }

    private boolean isStrategyActive(Strategy strategy) {
        if (strategy == null || !ACTIVE_YES.equalsIgnoreCase(strategy.getActive())) return false;
        DayOfWeek today = LocalDate.now(ZoneId.of("Asia/Kolkata")).getDayOfWeek();
        return !(today == DayOfWeek.SATURDAY || today == DayOfWeek.SUNDAY);
    }

    private void logApiError(String tag, SmartAPIException e) { logger.error("🚨 SmartAPI error [{}]", tag, e); }
    private void logGeneralError(String tag, Exception e) { logger.error("❌ Execution error [{}]", tag, e); }

    private Strategy getStrategyCached(String name) {
        return strategyCache.computeIfAbsent(name, strategyRepo::findByName);
    }
}