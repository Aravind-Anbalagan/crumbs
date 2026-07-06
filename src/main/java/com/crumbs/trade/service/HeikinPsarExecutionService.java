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

import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
    private static final String TRADE_MODE = "SCALPING"; 
    private static final boolean IS_OPTION_BUYER = true; 
    
    private static final BigDecimal SCALP_TARGET_POINTS = new BigDecimal("20.00");
    private static final BigDecimal SCALP_SL_POINTS = new BigDecimal("10.00");

    // Adaptive Entry Thresholds
    private static final BigDecimal NIFTY_BIG_CANDLE_THRESHOLD = new BigDecimal("25.00");
    private static final BigDecimal CRUDE_BIG_CANDLE_THRESHOLD = new BigDecimal("35.00");

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

    @Autowired private ChartService chartService;
    @Autowired private StrategyRepo strategyRepo;
    @Autowired private VixRepo vixRepo;
    @Autowired private OrderRepository ordersRepository;
    @Autowired private OrderService orderService;
    @Autowired private TelegramService telegramService;
    @Autowired private ShortStraddleRepository straddleRepository;
    @Autowired private AngelWebSocketService angelWebSocketService;

    // =========================================================
    // 🔥 IN-MEMORY CACHES (Eliminates DB Polling in Fast-Loops)
    // =========================================================
    private final Map<String, Orders> pendingRetracementsCache = new ConcurrentHashMap<>();
    private final Map<String, Orders> activeScalpCache = new ConcurrentHashMap<>();
    private static final long SUBSCRIPTION_RETRY_MS = 30000L;

    private final Map<String, Strategy> strategyCache =
            new ConcurrentHashMap<>();

    private final Map<String, Long> subscriptionCooldownCache =
            new ConcurrentHashMap<>();
    /**
     * Runs ONCE on application startup/restart. 
     * Restores active positions from the database into memory so state is never lost.
     */
    //@PostConstruct
    public void restoreStateOnStartup() {

        try {

            List<Orders> pending =
                    ordersRepository.findByStatusAndActive(
                            "PENDING_RETRACEMENT",
                            STATUS_ACTIVE);

            for (Orders order : pending) {
                pendingRetracementsCache.put(
                        order.getName(),
                        order);
            }

            List<Orders> openTrades =
                    ordersRepository.findByStatusAndActive(
                            STATUS_OPEN,
                            STATUS_ACTIVE);

            for (Orders order : openTrades) {
                activeScalpCache.put(
                        order.getName(),
                        order);
            }

            logger.info(
                    "✅ Restored Pending={} Active={}",
                    pending.size(),
                    openTrades.size());

        } catch (Exception e) {

            logger.error(
                    "Failed restoring cache",
                    e);
        }
    }

    // =========================================================
    // 🚀 CORE EXECUTIONS (Triggered by 5-Min Cron)
    // =========================================================

    public void commonExecutionNifty() {
        try { executeNiftyInternal(); } 
        catch (SmartAPIException e) { logApiError(NIFTY, e); } 
        catch (Exception e) { logGeneralError(NIFTY, e); }
    }

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

        boolean isNiftyValid = instrument.contains("NIFTY") && !now.isBefore(LocalTime.of(9, 30)) && now.isBefore(LocalTime.of(15, 20));
        boolean isCrudeValid = instrument.contains("CRUDEOIL") && !now.isBefore(LocalTime.of(16, 0)) && now.isBefore(LocalTime.of(23, 20));
        boolean isNiftySquareOff = instrument.contains("NIFTY") && !now.isBefore(LocalTime.of(15, 20));
        boolean isCrudeSquareOff = instrument.contains("CRUDEOIL") && !now.isBefore(LocalTime.of(23, 20));

        // ---------------------------------------------------------
        // PHASE 1: EVALUATE OPEN TRADES (EOD / TREND REVERSAL EXITS)
        // ---------------------------------------------------------
        Orders openTrade = ordersRepository.findByNameAndActive(tradeName, STATUS_ACTIVE);
        
        if (openTrade != null && STATUS_OPEN.equalsIgnoreCase(openTrade.getStatus())) {
            boolean tradeClosedThisCycle = false;

            if (isNiftySquareOff || isCrudeSquareOff) {
                logger.info("🕒 [{}][EXIT] Square-off time reached.", tradeName);
                processExit(openTrade, latestCandle, "EOD_SQUARE_OFF", strategy);
                tradeClosedThisCycle = true;
            }
            else if ("TREND_FOLLOWING".equalsIgnoreCase(TRADE_MODE) || !IS_OPTION_BUYER) {
                String currentSignal = latestCandle.getSignal();
                boolean isLongReversal = "CE".equalsIgnoreCase(openTrade.getOptionType()) && "SELL".equalsIgnoreCase(currentSignal);
                boolean isShortReversal = "PE".equalsIgnoreCase(openTrade.getOptionType()) && "BUY".equalsIgnoreCase(currentSignal);
                
                if (isLongReversal || isShortReversal) {
                    logger.info("🔄 [{}][EXIT] Trend Reversal detected. Closing position.", tradeName);
                    processExit(openTrade, latestCandle, "TREND_REVERSAL", strategy);
                    tradeClosedThisCycle = true;
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

        // 🧹 SIGNAL-BASED EXPIRY: CLEAN UP STALE RETRACEMENTS
        Orders stalePendingOrder = ordersRepository.findByNameAndStatusAndActive(tradeName, "PENDING_RETRACEMENT", STATUS_ACTIVE);
        
        if (stalePendingOrder != null) {
            String pendingDirection = stalePendingOrder.getType(); 
            if (!currentSignal.equalsIgnoreCase(pendingDirection)) {
                logger.warn("🚫 [{}][OVERRIDE] Trend Reversed to {}! Abandoning old retracement waiting at ₹{}. Marking as FAILED.", 
                            tradeName, currentSignal, stalePendingOrder.getTargetSpotPrice());
                
                stalePendingOrder.setStatus("FAILED_NEW_SIGNAL");
                stalePendingOrder.setActive(STATUS_INACTIVE);
                ordersRepository.save(stalePendingOrder);
                
                // Remove from in-memory cache
                pendingRetracementsCache.remove(tradeName);
                
                if (telegramService != null) telegramService.sendMessage(String.format("🚫 **[%s] RETRACEMENT ABORTED**\nNew %s signal formed.", tradeName, currentSignal));
            } else {
                logger.info("⏳ [{}][WAITING] Still waiting for {} retracement to ₹{}. Trend unchanged.", 
                            tradeName, currentSignal, stalePendingOrder.getTargetSpotPrice());
                return; // Let the fast-loop keep waiting
            }
        }

        // 🛡️ OVERTRADING GUARD
        if (!canEnterNewTrade(tradeName, currentSignal)) return; 

        // 🚀 ADAPTIVE ENTRY LOGIC: BIG vs SMALL CANDLE
        BigDecimal open = latestCandle.getOpen();
        BigDecimal close = latestCandle.getClose();
        BigDecimal bodySize = close.subtract(open).abs();

        BigDecimal threshold = "NIFTY".equalsIgnoreCase(baseSymbol) ? NIFTY_BIG_CANDLE_THRESHOLD : CRUDE_BIG_CANDLE_THRESHOLD;
        boolean isBigCandle = bodySize.compareTo(threshold) >= 0;

        if (isBigCandle) {
            // --- PATH A: BIG CANDLE (RETRACEMENT SCANNING MODE) ---
            BigDecimal retracementDiscount = bodySize.multiply(new BigDecimal("0.50"));
            BigDecimal targetSpotPrice = "BUY".equalsIgnoreCase(currentSignal) 
                    ? close.subtract(retracementDiscount) 
                    : close.add(retracementDiscount);
                    
            logger.info("⚡ [{}][BIG CANDLE] Body: {} pts >= Threshold ({} pts). Entering Retracement Mode. Target Spot: ₹{}", 
                        tradeName, bodySize, threshold, targetSpotPrice);
                        
            savePendingRetracementOrder(strategy, latestCandle, currentSignal, targetSpotPrice, tradeName);
        } else {
            // --- PATH B: SMALL CANDLE (INSTANT MARKET EXECUTION) ---
            logger.info("🚀 [{}][SMALL CANDLE] Body: {} pts < Threshold. Quiet breakout detected. Executing INSTANT Market Order!", 
                        tradeName, bodySize);
                        
            executeInstantAtmOrder(strategy, currentSignal, tradeName);
        }
    }

    // =========================================================
    // ⚡ FAST-LOOPS (Triggered by Scheduler - NOW CACHE BASED!)
    // =========================================================

    /**
     * Call this via @Scheduled(fixedDelay = 1000) for instant entry polling.
     * Reads directly from memory cache! Zero DB queries while waiting.
     */
    public void monitorPendingRetracements() {
        if (pendingRetracementsCache.isEmpty()) return;

        for (Orders order : pendingRetracementsCache.values()) {
            String tradeName = order.getName();
            BigDecimal liveSpotPrice = chartService.getCurrentPrice(tradeName);
            
            if (liveSpotPrice == null || liveSpotPrice.compareTo(BigDecimal.ZERO) == 0) continue;

            boolean isBuyTriggered = "BUY".equalsIgnoreCase(order.getType()) && liveSpotPrice.compareTo(order.getTargetSpotPrice()) <= 0;
            boolean isSellTriggered = "SELL".equalsIgnoreCase(order.getType()) && liveSpotPrice.compareTo(order.getTargetSpotPrice()) >= 0;

            if (isBuyTriggered || isSellTriggered) {
                // 1. Remove from cache immediately so it doesn't double-fire
                pendingRetracementsCache.remove(tradeName);

                logger.info("🎯 [{}][FILLED] Retracement target (₹{}) reached at live price ₹{}! Firing ATM Market Order.", 
                            tradeName, order.getTargetSpotPrice(), liveSpotPrice);
                
                // 2. Execute trade
                String baseSymbol = getBaseSymbol(tradeName);

				Strategy strategyConfig = "NIFTY".equalsIgnoreCase(baseSymbol)
						? getStrategyCached(NIFTY)
						: getStrategyCached(SAMCO_CRUDEOIL);

				if (strategyConfig == null) {
					logger.error("Strategy not found for {}", tradeName);
					continue;
				}

				executeInstantAtmOrder(strategyConfig, order.getType(),
						tradeName);
                
                // 3. Update DB state
                order.setStatus("PROCESSED_TO_OPEN");
                order.setActive(STATUS_INACTIVE);
                ordersRepository.save(order);
            }
        }
    }

    /**
     * Call this via @Scheduled(fixedDelay = 10000) for profit booking.
     * Reads directly from memory cache! Zero DB queries while waiting.
     */
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
        String baseSymbol = getBaseSymbol(openTrade.getName());
        BigDecimal currentPremium = getCurrentOptionPremium(baseSymbol, openTrade.getStrike(), openTrade.getOptionType());
        BigDecimal entryPremium = openTrade.getAskPrice();

        if (currentPremium != null && entryPremium != null) {
            BigDecimal pointsGained = currentPremium.subtract(entryPremium);

            if (pointsGained.compareTo(SCALP_TARGET_POINTS) >= 0) {
                logger.info("🎯 [{}][FAST-LOOP] Target Hit! Secured: +{} pts (Current: ₹{})", 
                            openTrade.getName(), pointsGained.setScale(2, RoundingMode.HALF_UP), currentPremium);
                processExit(openTrade, null, "SCALP_TARGET_HIT", strategy);
            } 
            else if (pointsGained.compareTo(SCALP_SL_POINTS.negate()) <= 0) {
                logger.info("🛑 [{}][FAST-LOOP] Stop Loss Hit! Loss: {} pts (Current: ₹{})", 
                            openTrade.getName(), pointsGained.setScale(2, RoundingMode.HALF_UP), currentPremium);
                processExit(openTrade, null, "SCALP_SL_HIT", strategy);
            }
        }
    }

    // =========================================================
    // 🧰 BROKER EXECUTION & STATE MANAGEMENT
    // =========================================================

    private void savePendingRetracementOrder(Strategy strategyConfig, Vix candle, String signal, BigDecimal targetSpotPrice, String tradeName) {
        Orders pendingOrder = new Orders();
        pendingOrder.setName(tradeName);
        pendingOrder.setExchange(strategyConfig.getExchange());
        pendingOrder.setSignal(HEIKIN_SIGNAL);
        pendingOrder.setType(signal); 
        
        String optionType = "";
        if ("BUY".equalsIgnoreCase(signal)) optionType = IS_OPTION_BUYER ? "CE" : "PE";
        else if ("SELL".equalsIgnoreCase(signal)) optionType = IS_OPTION_BUYER ? "PE" : "CE";
        
        pendingOrder.setOptionType(optionType);
        pendingOrder.setTargetSpotPrice(targetSpotPrice); 
        pendingOrder.setStrike(BigDecimal.ZERO); 
        pendingOrder.setStatus("PENDING_RETRACEMENT");
        pendingOrder.setTradePhase("SCANNING");
        pendingOrder.setActive(STATUS_ACTIVE);
        pendingOrder.setCreatedOn(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
        
        // Save to DB AND update memory cache
        Orders savedOrder = ordersRepository.save(pendingOrder);
        pendingRetracementsCache.put(tradeName, savedOrder);

        if (telegramService != null) telegramService.sendMessage(String.format("⚡ **[%s] SCANNING**\nSignal: %s\nTarget Spot: ₹%.2f\nWaiting for pullback...", tradeName, signal, targetSpotPrice));
    }

    private void executeInstantAtmOrder(Strategy strategyConfig, String signal, String tradeName) {
        BigDecimal liveSpotPrice = chartService.getCurrentPrice(strategyConfig.getName());
        if (liveSpotPrice == null) {

            logger.warn(
                    "Live spot price unavailable for {}",
                    strategyConfig.getName());

            return;
        }
        String baseSymbol = getBaseSymbol(strategyConfig.getName());
        BigDecimal atmStrike = calculateAtmStrike(baseSymbol, liveSpotPrice);
        
        StraddleIntraday optionData = straddleRepository.findLatestBySymbolAndStrike(baseSymbol, atmStrike).orElse(null);
        if (optionData == null) {
            logger.error("❌ [{}] No Option Data found for Symbol: {} | Strike: {}", tradeName, baseSymbol, atmStrike);
            return;
        }

        String orderType = "BUY".equalsIgnoreCase(signal) ? (IS_OPTION_BUYER ? "BUY" : "SELL") : (IS_OPTION_BUYER ? "BUY" : "SELL");
        String optionType = "BUY".equalsIgnoreCase(signal) ? (IS_OPTION_BUYER ? "CE" : "PE") : (IS_OPTION_BUYER ? "PE" : "CE");

        String optionToken = "CE".equalsIgnoreCase(optionType) ? optionData.getCeToken() : optionData.getPeToken();
        String optionSymbol = "CE".equalsIgnoreCase(optionType) ? optionData.getCeSymbol() : optionData.getPeSymbol();
        
        // Use dynamic WebSocket checker to get the absolute latest live premium for entry
        BigDecimal entryPremium = getCurrentOptionPremium(baseSymbol, atmStrike, optionType);
        if (entryPremium == null || entryPremium.compareTo(BigDecimal.ZERO) == 0) {
            entryPremium = "CE".equalsIgnoreCase(optionType) ? optionData.getCePrice() : optionData.getPePrice();
        }

        int quantity = strategyConfig.getQuantity();
        logger.info("🚀 [{}][EXECUTE] Opening {} {} | Spot: {} | Strike: {} | Premium: ₹{}", 
                    tradeName, orderType, optionType, liveSpotPrice, atmStrike, entryPremium);
        
        boolean isLive = "Y".equalsIgnoreCase(strategyConfig.getLive());
        Orders order = null;

        try {
            Token t = new Token();
            t.setToken(optionToken); 
            t.setSymbol(optionSymbol); 
            t.setStrike(atmStrike);
            t.setName(tradeName); 
            t.setExch_seg(strategyConfig.getExchange()); 
            t.setQuantity(quantity);

            if (isLive) {
                orderService.orderPlaceWithToken(t, tradeName, orderType, true);
                order = ordersRepository.findByNameAndTokenAndActive(tradeName, optionToken, STATUS_ACTIVE).orElse(null);
            }

            if (order == null) {
                order = new Orders();
                order.setToken(optionToken); 
                order.setSymbol(optionSymbol); 
                order.setExchange(strategyConfig.getExchange()); 
            }

            order.setName(tradeName); 
            order.setQuantity(quantity);
            order.setSignal(HEIKIN_SIGNAL);
            order.setType(orderType); 
            order.setOptionType(optionType); 
            order.setSide(optionType);
            order.setTradeCycleId(UUID.randomUUID().toString());
            order.setAskPrice(entryPremium); 
            order.setStrike(atmStrike);
            order.setStatus(STATUS_OPEN); 
            order.setTradePhase(PHASE_ENTRY);
            order.setActive(STATUS_ACTIVE);
            order.setCreatedOn(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            
            // Save to DB AND update active scalp memory cache
            Orders savedOrder = ordersRepository.save(order);
            activeScalpCache.put(tradeName, savedOrder);

            if (telegramService != null) telegramService.sendMessage(String.format("✅ **[%s] FILLED [%s]**\nSide: %s %s\nStrike: %.0f\nPremium: ₹%.2f", TRADE_MODE, (isLive ? "LIVE" : "PAPER"), orderType, optionType, atmStrike, entryPremium));
        
        } catch (Exception | SmartAPIException e) {
            logger.error("❌ Execution Failed {}: {}", tradeName, e.getMessage());
        }
    }

    private void processExit(Orders openTrade, Vix exitCandle, String reason, Strategy strategyConfig) {
        String tradeName = openTrade.getName();
        boolean isLive = "Y".equalsIgnoreCase(strategyConfig.getLive());
        
        try {
            // Remove from memory caches immediately upon exiting
            activeScalpCache.remove(tradeName);
            pendingRetracementsCache.remove(tradeName);

            if (isLive) orderService.exitActiveTradeByToken(openTrade.getToken(), strategyConfig.getName(), tradeName);

            BigDecimal exitPremium = getCurrentOptionPremium(getBaseSymbol(tradeName), openTrade.getStrike(), openTrade.getOptionType());
            if (exitPremium == null) exitPremium = BigDecimal.ZERO; 

            BigDecimal entryPrice = openTrade.getAskPrice() != null ? openTrade.getAskPrice() : BigDecimal.ZERO;
            BigDecimal pointsCollected = "BUY".equalsIgnoreCase(openTrade.getType()) 
                    ? exitPremium.subtract(entryPrice) : entryPrice.subtract(exitPremium); 

            BigDecimal rupeePnL = pointsCollected.multiply(BigDecimal.valueOf(openTrade.getQuantity())).setScale(2, RoundingMode.HALF_UP);

            openTrade.setExitPrice(exitPremium);
            openTrade.setPl(rupeePnL);
            openTrade.setClosedOn(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            openTrade.setTradePhase(PHASE_EXIT);
            openTrade.setStatus(STATUS_CLOSED);
            openTrade.setActive(STATUS_INACTIVE); // UNLOCK
            openTrade.setExitReason(reason);
            
            ordersRepository.save(openTrade); 
            
            String emoji = rupeePnL.signum() >= 0 ? "✅" : "❌";
            if (telegramService != null) telegramService.sendMessage(String.format("%s **EXIT [%s]: %s**\nReason: %s\nStrike: %.0f\nEntry: %.2f | Exit: %.2f\nEst. PnL: **₹%.2f**", emoji, (isLive ? "LIVE" : "PAPER"), tradeName, reason, openTrade.getStrike(), entryPrice, exitPremium, rupeePnL));
            
        } catch (Exception | SmartAPIException e) {
            logger.error("❌ [{}][EXIT] Error closing trade: {}", tradeName, e.getMessage());
        }
    }

    // =========================================================
    // 🛡️ UTILITIES & GUARDS
    // =========================================================

    private boolean canEnterNewTrade(String tradeName, String currentSignal) {
        Optional<Orders> lastOrderOpt = ordersRepository.findTopByNameOrderByClosedOnDesc(tradeName);
        if (!lastOrderOpt.isPresent()) return true;

        Orders lastOrder = lastOrderOpt.get();
        String lastExitReason = lastOrder.getExitReason();
        String lastTradeDirection = IS_OPTION_BUYER ? ("CE".equalsIgnoreCase(lastOrder.getOptionType()) ? "BUY" : "SELL") : ("CE".equalsIgnoreCase(lastOrder.getOptionType()) ? "SELL" : "BUY");

        if ("SCALP_SL_HIT".equalsIgnoreCase(lastExitReason) && currentSignal.equalsIgnoreCase(lastTradeDirection)) {
            logger.info("⏳ [{}][BLOCKED] Ignoring {} signal to prevent whipsaw (SL hit on last trade).", tradeName, currentSignal);
            return false;
        }

        if ("SCALP_TARGET_HIT".equalsIgnoreCase(lastExitReason) && currentSignal.equalsIgnoreCase(lastTradeDirection)) {
            LocalDateTime closedOn = lastOrder.getClosedOn();
            LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
            if (closedOn != null && closedOn.isAfter(now.minusMinutes(5))) {
                logger.info("⏳ [{}][BLOCKED] Target already secured on this candle. Waiting for next signal.", tradeName);
                return false;
            }
        }
        return true;
    }

    /**
     * 🔥 UPGRADED OPTION PREMIUM GETTER WITH LIVE WEBSOCKET SUBSCRIPTION FALLBACK
     * Checks DB for token metadata -> Checks WebSocket cache for live tick -> Subscribes dynamically if missing!
     */
    private BigDecimal getCurrentOptionPremium(String baseSymbol, BigDecimal strike, String optionType) {
        StraddleIntraday optionData = straddleRepository.findLatestBySymbolAndStrike(baseSymbol, strike).orElse(null);
        if (optionData == null) {
            logger.error("❌ No option tokens found in DB for {} Strike {}", baseSymbol, strike);
            return null;
        }

        String optionToken;
        BigDecimal dbFallbackPrice;
        
        if ("CE".equalsIgnoreCase(optionType)) {
            optionToken = optionData.getCeToken();
            dbFallbackPrice = optionData.getCePrice();
        } else {
            optionToken = optionData.getPeToken();
            dbFallbackPrice = optionData.getPePrice();
        }

        if (optionToken == null || optionToken.isEmpty()) return null;

        // Map contract to correct Angel One Exchange Type (NSE_FO for Nifty/BankNifty, MCX_FO for Crude/NatGas)
        ExchangeType exchangeType = "CRUDEOIL".equalsIgnoreCase(baseSymbol) || "NATURALGAS".equalsIgnoreCase(baseSymbol)
                ? ExchangeType.MCX_FO
                : ExchangeType.NSE_FO;

        // Check WebSocket memory cache for sub-second tick
        BigDecimal livePrice = angelWebSocketService.getLatestLTP(exchangeType, optionToken);

        // If price is missing/zero, trigger live subscription on the fly and fallback to DB price temporarily
        if (livePrice == null || livePrice.compareTo(BigDecimal.ZERO) == 0) {

            String subscriptionKey =
                    exchangeType.name() + "_" + optionToken;

            long now = System.currentTimeMillis();

            long lastSubscription =
                    subscriptionCooldownCache.getOrDefault(
                            subscriptionKey,
                            0L);

            if ((now - lastSubscription) > SUBSCRIPTION_RETRY_MS) {

                subscriptionCooldownCache.put(
                        subscriptionKey,
                        now);

                logger.info(
                        "📡 [WS-AUTO] Missing LTP. Subscribing {}",
                        subscriptionKey);

                angelWebSocketService.subscribe(
                        exchangeType,
                        optionToken);
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
        if ("CRUDEOIL".equalsIgnoreCase(baseSymbol)) return spotPrice.divide(new BigDecimal("10"), 0, RoundingMode.HALF_UP).multiply(new BigDecimal("10"));
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
        return strategyCache.computeIfAbsent(
                name,
                strategyRepo::findByName
        );
    }
    
}