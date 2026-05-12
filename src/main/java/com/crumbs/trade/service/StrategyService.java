package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.cpr.CPRStraddleService;
import com.crumbs.trade.dto.CPR;
import com.crumbs.trade.dto.OrderMeta;
import com.crumbs.trade.dto.StrangleCprDto;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.Stoploss;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.CPRRepo;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.PriceRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.utility.AppConstant;
import com.crumbs.trade.utility.NSEWorkingDays;

import jakarta.transaction.Transactional;

@Service
public class StrategyService {

    Logger logger = LoggerFactory.getLogger(StrategyService.class);

    // =========================================================================
    // CONFIGURABLE CONSTANTS
    // =========================================================================
    private static final int GAP_THRESHOLD_POINTS   = 50;  // pts diff open vs pivot → gap day
    private static final int FIRST_CANDLE_BUFFER    = 5;   // buffer added to first 5-min high/low
    private static final int BIG_CANDLE_THRESHOLD   = 100; // pts — if first candle range > this → straddle day
    private static final int BREAKOUT_CONFIRM_TICKS = 5;   // consecutive 1-min ticks to confirm breakout

    // =========================================================================
    // DAY FLAGS  (reset at 09:00 AM daily)
    // =========================================================================
    private final AtomicBoolean cprBuyTradeTaken    = new AtomicBoolean(false);
    private final AtomicBoolean cprSellTradeTaken   = new AtomicBoolean(false);
    private final AtomicBoolean buySLHit            = new AtomicBoolean(false);
    private final AtomicBoolean sellSLHit           = new AtomicBoolean(false);
    private final AtomicBoolean skipCPRTakeStraddle = new AtomicBoolean(false);

    private int buyConfirmCount  = 0;
    private int sellConfirmCount = 0;
    private int buySLConfirmCount  = 0;
    private int sellSLConfirmCount = 0;
    // Strangle flags
    public static boolean timeCheck   = false;
    public static boolean firstOrder  = false;
    public static boolean secondOrder = false;

    // Strangle range
    public static int MAX = 0;
    public static int MIN = 0;

    // =========================================================================
    // AUTOWIRED
    // =========================================================================
    @Autowired RestTemplate              restTemplate;
    @Autowired AngelOne                  angelOne;
    @Autowired AngelOneService           angelOneService;
    @Autowired StrategyRepo              strategyRepo;
    @Autowired OrderRepository           orderRepository;
    @Autowired PriceRepo                 priceRepo;
    @Autowired TaskService               taskService;
    @Autowired ChartService              chartService;
    @Autowired CPRRepo                   cprRepo;
    @Autowired OrderService              orderService;
    @Autowired PriceUtilService          priceUtilService;
    @Autowired StraddleIntradayService   straddleIntradayService;
    @Autowired CPRStraddleService        cprStraddleService;

    // =========================================================================
    // STEP 1 — Fetch CPR + First 5-min candle  (called at 09:20)
    // =========================================================================
    public void getCPRDetails() throws IOException, SmartAPIException {

        StrangleCprDto dto      = new StrangleCprDto();
        Strategy       strategy = strategyRepo.findByName(AppConstant.CPR_STRATEGY);
        SmartConnect   sc       = angelOne.signIn();

        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(9, 20))) {
            logger.info("⏰ Wait until 09:20 for first 5-min candle to close.");
            return;
        }

        dto = getCPR(sc, strategy, dto);
        dto = getFirstCandleData(sc, strategy, dto);

        if (dto == null || dto.getFirstFiveMinHigh() == null || dto.getFirstFiveMinLow() == null) {
            logger.error("❌ Unable to fetch CPR Details");
            return;
        }

        // =====================================================================
        // BIG CANDLE CHECK
        // rawRange = bufferedHigh - bufferedLow - (2 * FIRST_CANDLE_BUFFER)
        // =====================================================================
        BigDecimal rawRange = dto.getFirstFiveMinHigh()
                .subtract(dto.getFirstFiveMinLow())
                .subtract(BigDecimal.valueOf(FIRST_CANDLE_BUFFER * 2));

        logger.info("📏 First candle raw range = {} pts (threshold={})", rawRange, BIG_CANDLE_THRESHOLD);

        // ✅ Always save CPR to DB — needed for getCPRStrategySignal on both day types
        String dateTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        saveCPR(dto, strategy.getName(), dateTime);

        if (rawRange.compareTo(BigDecimal.valueOf(BIG_CANDLE_THRESHOLD)) > 0) {

            skipCPRTakeStraddle.set(true);
            logger.info("🚨 Big candle detected ({} pts > {}) — placing STRADDLE + CPR signal active",
                    rawRange, BIG_CANDLE_THRESHOLD);

            BigDecimal ltp = angelOneService.getcurrentPrice(
                    sc, strategy.getExchange(),
                    strategy.getTradingsymbol(), strategy.getToken(), "ltp");

            if (ltp == null) {
                logger.error("❌ LTP null — cannot place straddle.");
                return;
            }

            cprStraddleService.placeStraddle(sc, ltp,
                    dto.getFirstFiveMinHigh(),
                    dto.getFirstFiveMinLow());
        }
    }

    // =========================================================================
    // STEP 2 — Execute strategy  (called every 1 min 09:21 → 15:19)
    //
    // Straddle day: straddle SL monitor + CPR signal both run every tick
    // Normal day  : CPR signal only
    // =========================================================================
    public void executeCPRStrategy() throws SmartAPIException {

        if (skipCPRTakeStraddle.get()) {
            logger.debug("📡 Big candle day — straddle monitor + CPR signal.");
            cprStraddleService.monitorStraddleSL();
            // ✅ no return — fall through to CPR signal below
        }

        SmartConnect                sc        = angelOne.signIn();
        com.crumbs.trade.entity.CPR cprEntity = cprRepo.findByName(AppConstant.CPR_STRATEGY);

        if (cprEntity == null) {
            logger.warn("⚠️ CPR data not found in DB — skipping.");
            return;
        }

        getCPRStrategySignal(cprEntity, sc);
    }

    // =========================================================================
    // EOD EXIT  (called at 15:20)
    //
    // Straddle day: exit straddle legs + any open directional CPR trade
    // Normal day  : exit directional CPR trade only
    // =========================================================================
    public void exitAllCPRPositions() {
        try {
            logger.info("⏰ EOD Exit triggered for CPR Strategy");

            if (skipCPRTakeStraddle.get()) {
                cprStraddleService.exitAllStraddlePositions();
            }

            // 🟢 UPDATED: Fetch LTP and pass to exit method
            Orders activeTrade = orderRepository.findByNameAndActive(AppConstant.CPR_STRATEGY, 1);
            if (activeTrade != null) {
                Strategy strategy = strategyRepo.findByName(AppConstant.CPR_STRATEGY);
                SmartConnect sc = angelOne.signIn();
                BigDecimal currentPrice = angelOneService.getcurrentPrice(
                        sc, strategy.getExchange(), strategy.getTradingsymbol(), strategy.getToken(), "ltp");
                
                exitCurrentTrade("EOD_SQUARE_OFF", currentPrice != null ? currentPrice : BigDecimal.ZERO);
            }

        } catch (Exception e) {
            logger.error("❌ Error during EOD CPR exit", e);
        }
    }

    // =========================================================================
    // DAILY FLAG RESET  (called at 9:00 AM)
    // =========================================================================
    public void resetDailyFlags() {
        cprBuyTradeTaken.set(false);
        cprSellTradeTaken.set(false);
        buySLHit.set(false);
        sellSLHit.set(false);
        skipCPRTakeStraddle.set(false);
        buyConfirmCount  = 0;
        sellConfirmCount = 0;
        cprStraddleService.resetDailyFlags();
        logger.info("🔄 CPR daily flags reset successfully.");
    }

    // =========================================================================
    // SIGNAL GENERATION
    // =========================================================================
    public void getCPRStrategySignal(com.crumbs.trade.entity.CPR cprDetails,
                                     SmartConnect smartconnect) {

        Strategy strategy = strategyRepo.findByName(AppConstant.CPR_STRATEGY);

        BigDecimal topPivot    = cprDetails.getTop();
        BigDecimal bottomPivot = cprDetails.getBottom();
        BigDecimal pivot       = cprDetails.getPivot();
        BigDecimal first5High  = cprDetails.getHigh();
        BigDecimal first5Low   = cprDetails.getLow();

        if (topPivot == null || bottomPivot == null || pivot == null) {
            logger.warn("⚠️ CPR data missing — skipping signal.");
            return;
        }
        if (first5High == null || first5Low == null) {
            logger.warn("⚠️ First 5-min candle missing — skipping.");
            return;
        }

        BigDecimal upperBand = topPivot.max(bottomPivot);
        BigDecimal lowerBand = topPivot.min(bottomPivot);

        BigDecimal todayOpen = angelOneService.getcurrentPrice(
                smartconnect, strategy.getExchange(),
                strategy.getTradingsymbol(), strategy.getToken(), "open");

        if (todayOpen == null) {
            logger.warn("⚠️ Unable to fetch today open price.");
            return;
        }

        String marketType = detectMarketType(todayOpen, pivot);
        logger.info("📊 Market Type: {} | open={} pivot={}", marketType, todayOpen, pivot);

        BigDecimal currentPrice = angelOneService.getcurrentPrice(
                smartconnect, strategy.getExchange(),
                strategy.getTradingsymbol(), strategy.getToken(), "ltp");

        if (currentPrice == null) {
            logger.warn("⚠️ Current price NULL — skipping.");
            return;
        }

        logger.info("💹 LTP={} | first5H={} first5L={} | upperBand={} lowerBand={}",
                currentPrice, first5High, first5Low, upperBand, lowerBand);

        checkStoploss(currentPrice, first5High, first5Low, upperBand, lowerBand, marketType);

        String signal = generateSignal(currentPrice, first5High, first5Low,
                                       upperBand, lowerBand, marketType);

        logger.info("📶 Signal={} | marketType={}", signal, marketType);

        executeCPRStrategyOrders(signal, currentPrice, first5High, first5Low,
                                 upperBand, lowerBand, pivot, marketType);
    }

    // =========================================================================
    // GAP DETECTION
    // =========================================================================
    private String detectMarketType(BigDecimal todayOpen, BigDecimal pivot) {
        BigDecimal diff = todayOpen.subtract(pivot);
        if (diff.compareTo(BigDecimal.valueOf(GAP_THRESHOLD_POINTS)) > 0) {
            return "GAP_UP";
        } else if (diff.compareTo(BigDecimal.valueOf(-GAP_THRESHOLD_POINTS)) < 0) {
            return "GAP_DOWN";
        }
        return "NORMAL";
    }

    // =========================================================================
    // SIGNAL GENERATION — 5 consecutive ticks to confirm breakout
    // =========================================================================
    private String generateSignal(BigDecimal price,
                                   BigDecimal first5High, BigDecimal first5Low,
                                   BigDecimal upperBand,  BigDecimal lowerBand,
                                   String marketType) {
        if ("NORMAL".equals(marketType)) {
            if (price.compareTo(first5High) > 0 && price.compareTo(upperBand) > 0) {
                sellConfirmCount = 0;
                if (++buyConfirmCount >= BREAKOUT_CONFIRM_TICKS) return "BUY";
            } else if (price.compareTo(first5Low) < 0 && price.compareTo(lowerBand) < 0) {
                buyConfirmCount = 0;
                if (++sellConfirmCount >= BREAKOUT_CONFIRM_TICKS) return "SELL";
            } else {
                buyConfirmCount  = 0;
                sellConfirmCount = 0;
            }
        } else {
            if (price.compareTo(first5High) > 0) {
                sellConfirmCount = 0;
                if (++buyConfirmCount >= BREAKOUT_CONFIRM_TICKS) return "BUY";
            } else if (price.compareTo(first5Low) < 0) {
                buyConfirmCount = 0;
                if (++sellConfirmCount >= BREAKOUT_CONFIRM_TICKS) return "SELL";
            } else {
                buyConfirmCount  = 0;
                sellConfirmCount = 0;
            }
        }
		logger.info(
				"📶 buyConfirmCount={} | sellConfirmCount={} - BREAKOUT_CONFIRM_TICKS={}",
				buyConfirmCount, sellConfirmCount, BREAKOUT_CONFIRM_TICKS);
        return "WAIT";
    }

    // =========================================================================
    // STOPLOSS CHECK
    // =========================================================================
 // =========================================================================
    // STOPLOSS CHECK (Upgraded with 5-Min Fake-Out Protection)
    // =========================================================================
    private void checkStoploss(BigDecimal price,
                                BigDecimal first5High, BigDecimal first5Low,
                                BigDecimal upperBand,  BigDecimal lowerBand,
                                String marketType) {

        Orders activeTrade = orderRepository.findByNameAndActive(AppConstant.CPR_STRATEGY, 1);
        if (activeTrade == null) {
            // Safety: Reset SL counters if no trade is active
            buySLConfirmCount = 0;
            sellSLConfirmCount = 0;
            return;
        }

        String  activeType      = activeTrade.getType();
        boolean buySLConditionMet  = false;
        boolean sellSLConditionMet = false;

        // 1. Calculate if the price is currently breaching the limits
        if ("NORMAL".equals(marketType)) {
            buySLConditionMet  = "BUY".equalsIgnoreCase(activeType)
                               && price.compareTo(first5Low)  < 0
                               && price.compareTo(lowerBand)  < 0;
            sellSLConditionMet = "SELL".equalsIgnoreCase(activeType)
                               && price.compareTo(first5High) > 0
                               && price.compareTo(upperBand)  > 0;
        } else {
            buySLConditionMet  = "BUY".equalsIgnoreCase(activeType)  && price.compareTo(first5Low)  < 0;
            sellSLConditionMet = "SELL".equalsIgnoreCase(activeType) && price.compareTo(first5High) > 0;
        }

        // 2. Process BUY Trade SL Logic
        if (buySLConditionMet) {
            buySLConfirmCount++;
            logger.info("⚠️ BUY SL condition met. Reversal tick: {}/{}", buySLConfirmCount, BREAKOUT_CONFIRM_TICKS);
            
            if (buySLConfirmCount >= BREAKOUT_CONFIRM_TICKS) {
                logger.info("🛑 BUY SL Hit (Confirmed for {} mins) @ {} | first5Low={}", BREAKOUT_CONFIRM_TICKS, price, first5Low);
                exitCurrentTrade("BUY_SL_HIT", price);
                buySLHit.set(true);
                cprBuyTradeTaken.set(true);
                logger.info("🔒 BUY blocked for today. SELL side still open.");
                if (buySLHit.get() && sellSLHit.get()) logger.info("🚫 Both SL hit — No more trades today.");
                buySLConfirmCount = 0; // Reset after exit
            }
        } else {
            // Price recovered into safe zone! Reset the fake-out counter.
            if ("BUY".equalsIgnoreCase(activeType)) {
                buySLConfirmCount = 0; 
            }
        }

        // 3. Process SELL Trade SL Logic
        if (sellSLConditionMet) {
            sellSLConfirmCount++;
            logger.info("⚠️ SELL SL condition met. Reversal tick: {}/{}", sellSLConfirmCount, BREAKOUT_CONFIRM_TICKS);
            
            if (sellSLConfirmCount >= BREAKOUT_CONFIRM_TICKS) {
                logger.info("🛑 SELL SL Hit (Confirmed for {} mins) @ {} | first5High={}", BREAKOUT_CONFIRM_TICKS, price, first5High);
                exitCurrentTrade("SELL_SL_HIT", price);
                sellSLHit.set(true);
                cprSellTradeTaken.set(true);
                logger.info("🔒 SELL blocked for today. BUY side still open.");
                if (buySLHit.get() && sellSLHit.get()) logger.info("🚫 Both SL hit — No more trades today.");
                sellSLConfirmCount = 0; // Reset after exit
            }
        } else {
            // Price recovered into safe zone! Reset the fake-out counter.
            if ("SELL".equalsIgnoreCase(activeType)) {
                sellSLConfirmCount = 0;
            }
        }
    }

    // =========================================================================
    // ORDER EXECUTION
    // =========================================================================
    public void executeCPRStrategyOrders(String signal,
                                          BigDecimal currentPrice,
                                          BigDecimal first5High, BigDecimal first5Low,
                                          BigDecimal upperBand,  BigDecimal lowerBand,
                                          BigDecimal pivot,      String marketType) {
        try {
            if (buySLHit.get() && sellSLHit.get()) {
                logger.info("🚫 Both SL hit — skipping all signals.");
                return;
            }

            Orders  activeTrade    = orderRepository.findByNameAndActive(AppConstant.CPR_STRATEGY, 1);
            boolean oppositeActive = activeTrade != null
                                  && !activeTrade.getType().equalsIgnoreCase(signal);

            if ("BUY".equals(signal)) {
                if (cprBuyTradeTaken.get()) { logger.info("🚫 CPR BUY already taken today."); return; }
                if (buySLHit.get())         { logger.info("🚫 BUY SL already hit today.");     return; }
                if (oppositeActive) { logger.info("🔄 Reversing SELL → BUY"); exitCurrentTrade("SIGNAL_REVERSAL", currentPrice); }
                logger.info("🔥 CPR BUY signal → orderPlace()");
                orderService.orderPlace(AppConstant.CPR_STRATEGY, 0, "BUY",
                        buildOrderMeta(currentPrice, first5High, first5Low,
                                upperBand, lowerBand, pivot, marketType, "BUY"));
                cprBuyTradeTaken.set(true);

            } else if ("SELL".equals(signal)) {
                if (cprSellTradeTaken.get()) { logger.info("🚫 CPR SELL already taken today."); return; }
                if (sellSLHit.get())         { logger.info("🚫 SELL SL already hit today.");    return; }
                if (oppositeActive) { logger.info("🔄 Reversing BUY → SELL"); exitCurrentTrade("SIGNAL_REVERSAL", currentPrice); }
                logger.info("🔥 CPR SELL signal → orderPlace()");
                orderService.orderPlace(AppConstant.CPR_STRATEGY, 0, "SELL",
                        buildOrderMeta(currentPrice, first5High, first5Low,
                                upperBand, lowerBand, pivot, marketType, "SELL"));
                cprSellTradeTaken.set(true);

            } else {
                logger.info("⏸ Signal={} — no trade.", signal);
            }

        } catch (Exception | SmartAPIException e) {
            logger.error("❌ Error placing CPR order", e);
        }
    }

    // =========================================================================
    // BUILD ORDER META
    // =========================================================================
    private OrderMeta buildOrderMeta(BigDecimal currentPrice,
                                      BigDecimal first5High, BigDecimal first5Low,
                                      BigDecimal upperBand,  BigDecimal lowerBand,
                                      BigDecimal pivot,      String marketType,
                                      String signal) {
        OrderMeta meta = new OrderMeta();
        meta.setEntryPrice(currentPrice);
        meta.setFirst5High(first5High);
        meta.setFirst5Low(first5Low);
        meta.setUpperBand(upperBand);
        meta.setLowerBand(lowerBand);
        meta.setPivot(pivot);
        meta.setMarketType(marketType);
        meta.setSignal(signal);
        meta.setSlPrice("BUY".equals(signal)
                ? ("NORMAL".equals(marketType) ? lowerBand : first5Low)
                : ("NORMAL".equals(marketType) ? upperBand : first5High));
        meta.setEntryTime(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        meta.setEntryPrice(currentPrice);
        return meta;
    }

    // =========================================================================
    // EXIT CURRENT TRADE
    // =========================================================================
 // =========================================================================
    // EXIT CURRENT TRADE (Upgraded with DB Tracking)
    // =========================================================================
 // =========================================================================
    // EXIT CURRENT TRADE (Fixed: Fetches Option Premium instead of Index Price)
    // =========================================================================
    private void exitCurrentTrade(String reason, BigDecimal indexPrice) {
        try {
            Orders activeTrade = orderRepository.findByNameAndActive(AppConstant.CPR_STRATEGY, 1);
            if (activeTrade == null) return;

            // 1. Call the broker to close the position
            orderService.exitActiveTrade(AppConstant.CPR_STRATEGY);

            // 2. Fetch the actual Option Premium (LTP) for the traded token
            SmartConnect sc = angelOne.signIn();
            BigDecimal actualExitPremium = angelOneService.getcurrentPrice(
                    sc, 
                    activeTrade.getExchange(), 
                    activeTrade.getSymbol(), 
                    activeTrade.getToken(), 
                    "ltp"
            );

            // Fallback just in case the API call fails during exit
            if (actualExitPremium == null) {
                logger.warn("⚠️ Could not fetch actual exit premium for {}. Using 0.", activeTrade.getSymbol());
                actualExitPremium = BigDecimal.ZERO;
            }

            // 3. Perform DB Updates
            BigDecimal entryPrice = activeTrade.getAskPrice() != null ? activeTrade.getAskPrice() : BigDecimal.ZERO;
            
            // Calculate Directional PnL using the actual option premium
            BigDecimal pnl;
            if ("BUY".equalsIgnoreCase(activeTrade.getType()) || "BUY".equalsIgnoreCase(activeTrade.getOptionType())) {
                pnl = actualExitPremium.subtract(entryPrice); // Long: Exit - Entry
            } else {
                pnl = entryPrice.subtract(actualExitPremium); // Short: Entry - Exit
            }

            activeTrade.setExitPrice(actualExitPremium); // Save the option premium!
            activeTrade.setPl(pnl);
            activeTrade.setClosedOn(LocalDateTime.now());
            activeTrade.setStatus("CLOSED");
            activeTrade.setActive(0);
            activeTrade.setExitReason(reason);

            orderRepository.save(activeTrade);
            
            logger.info("✅ [{}][EXIT] Reason: {} | Symbol: {} | Entry: {} | Exit Premium: {} | PnL: {}", 
                    AppConstant.CPR_STRATEGY, reason, activeTrade.getSymbol(), entryPrice, actualExitPremium, pnl);

        } catch (Exception | SmartAPIException e) {
            logger.error("❌ Error exiting CPR trade", e);
        }
    }

    // =========================================================================
    // CPR CALCULATION
    // =========================================================================
    public StrangleCprDto getCPR(SmartConnect smartconnect, Strategy strategy,
                                  StrangleCprDto dto) throws IOException, SmartAPIException {

        LocalDate today              = LocalDate.now();
        LocalDate lastWorkingDay     = NSEWorkingDays.getLastWorkingDay(today);
        LocalDate previousWorkingDay = NSEWorkingDays.getLastWorkingDay(lastWorkingDay);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        String fromDate = previousWorkingDay.atTime(9, 15).format(formatter);
        String toDate   = lastWorkingDay.atTime(9, 15).format(formatter);

        JSONObject req = new JSONObject();
        req.put("exchange",    strategy.getExchange());
        req.put("symboltoken", strategy.getToken());
        req.put("interval",    "ONE_DAY");
        req.put("fromdate",    fromDate);
        req.put("todate",      toDate);

        JSONArray candles = straddleIntradayService.fetchCandleWithRetry(smartconnect, req, strategy.getToken());

        if (candles == null || candles.isEmpty()) {
            logger.warn("No ONE_DAY candles returned for CPR");
            return dto;
        }

        try {
            Object first = candles.get(0);
            if (first instanceof JSONArray candle) {
                dto.setOpen(BigDecimal.valueOf(candle.getDouble(1)));
                dto.setHigh(BigDecimal.valueOf(candle.getDouble(2)));
                dto.setLow(BigDecimal.valueOf(candle.getDouble(3)));
                dto.setClose(BigDecimal.valueOf(candle.getDouble(4)));
            } else {
                dto.setOpen(BigDecimal.valueOf(candles.getDouble(1)));
                dto.setHigh(BigDecimal.valueOf(candles.getDouble(2)));
                dto.setLow(BigDecimal.valueOf(candles.getDouble(3)));
                dto.setClose(BigDecimal.valueOf(candles.getDouble(4)));
            }
        } catch (Exception e) {
            logger.error("❌ Failed to parse ONE_DAY candle data: {}", e.getMessage());
            return dto;
        }

        logger.info("ONE_DAY OHLC → O={} H={} L={} C={}",
                dto.getOpen(), dto.getHigh(), dto.getLow(), dto.getClose());

        if (dto.getHigh().compareTo(dto.getLow()) < 0) {
            logger.error("❌ HIGH ({}) < LOW ({}) — candle parse is wrong!", dto.getHigh(), dto.getLow());
            return dto;
        }

        CPR cpr = priceUtilService.calculateCpr(dto.getHigh(), dto.getLow(), dto.getClose());
        if (cpr != null) {
            dto.setBottom_pivot(cpr.getBottom_pivot());
            dto.setPivot(cpr.getPivot());
            dto.setTop_pivot(cpr.getTop_pivot());
            dto.setCprWidth(cpr.getWidthType());
            dto.setCprType(cpr.getCprType());
            logger.info("CPR → TOP={} PIVOT={} BOTTOM={}",
                    cpr.getTop_pivot(), cpr.getPivot(), cpr.getBottom_pivot());
        } else {
            logger.warn("calculateCpr returned null");
        }

        if (dto.getTop_pivot() != null && dto.getBottom_pivot() != null && dto.getPivot() != null) {
            BigDecimal cprWidth   = dto.getTop_pivot().subtract(dto.getBottom_pivot()).abs();
            BigDecimal cprPercent = cprWidth
                    .divide(dto.getPivot(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
            dto.setCprPercent(cprPercent);
            logger.info("CPR Width={} pts ({} %)", cprWidth.setScale(2, RoundingMode.HALF_UP), cprPercent);
        }

        return dto;
    }

    // =========================================================================
    // FIRST 5-MIN CANDLE
    // =========================================================================
    public StrangleCprDto getFirstCandleData(SmartConnect smartConnect,
                                              Strategy strategy,
                                              StrangleCprDto dto) {
        LocalDate today    = LocalDate.now();
        String    fromDate = today + " 09:15";
        String    toDate   = today + " 09:20";

        JSONObject req = new JSONObject();
        req.put("exchange",    strategy.getExchange());
        req.put("symboltoken", strategy.getToken());
        req.put("interval",    "FIVE_MINUTE");
        req.put("fromdate",    fromDate);
        req.put("todate",      toDate);

        JSONArray ohlc = straddleIntradayService.fetchCandleWithRetry(smartConnect, req, strategy.getToken());

        if (ohlc == null || ohlc.isEmpty()) {
            logger.error("❌ No FIVE_MINUTE candle returned for first candle");
            return null;
        }

        JSONArray  firstCandle = ohlc.getJSONArray(0);
        BigDecimal high        = BigDecimal.valueOf(firstCandle.getDouble(2));
        BigDecimal low         = BigDecimal.valueOf(firstCandle.getDouble(3));
        BigDecimal buffer      = BigDecimal.valueOf(FIRST_CANDLE_BUFFER);

        dto.setFirstFiveMinHigh(high.add(buffer));
        dto.setFirstFiveMinLow(low.subtract(buffer));

        logger.info("First 5-min (buffered ±{}) → high={} low={}",
                FIRST_CANDLE_BUFFER, dto.getFirstFiveMinHigh(), dto.getFirstFiveMinLow());

        return dto;
    }

    // =========================================================================
    // SAVE CPR TO DB
    // =========================================================================
    public com.crumbs.trade.entity.CPR saveCPR(StrangleCprDto dto, String name, String date) {
        if (dto == null) { logger.error("❌ Unable to save CPR — dto is null"); return null; }

        com.crumbs.trade.entity.CPR cpr = new com.crumbs.trade.entity.CPR();
        cpr.setName(name);
        cpr.setDate(date);
        cpr.setPivot(dto.getPivot());
        cpr.setTop(dto.getTop_pivot());
        cpr.setBottom(dto.getBottom_pivot());
        cpr.setHigh(dto.getFirstFiveMinHigh());
        cpr.setLow(dto.getFirstFiveMinLow());

        logger.info("💾 Saving CPR → pivot={} top={} bottom={} first5H={} first5L={}",
                dto.getPivot(), dto.getTop_pivot(), dto.getBottom_pivot(),
                dto.getFirstFiveMinHigh(), dto.getFirstFiveMinLow());

        return cprRepo.save(cpr);
    }

    // =========================================================================
    // CANDLE DATA HELPER
    // =========================================================================
    public JSONArray getCandleDataByChoice(SmartConnect smartConnect, Strategy strategy,
                                            StrangleCprDto dto, String interval,
                                            String fromDate, String toDate) {
        JSONObject req = new JSONObject();
        req.put("exchange",    strategy.getExchange());
        req.put("symboltoken", strategy.getToken());
        req.put("interval",    interval);
        req.put("fromdate",    fromDate);
        req.put("todate",      toDate);

        JSONArray response = smartConnect.candleData(req);
        if (response != null && !response.isEmpty()) return (JSONArray) response.get(0);
        return null;
    }

    // =========================================================================
    // STRANGLE — unchanged
    // =========================================================================
    public void shortStrangleModified() throws SmartAPIException, Exception {
        Strategy     strategy   = strategyRepo.findByName("STRANGLE");
        Orders       order      = orderRepository.findByNameAndActive("NIFTY", 1);
        SmartConnect sc         = angelOne.signIn();
        int          niftyPrice = 0;
        String       signal;

        BigDecimal closePrice = angelOneService.getcurrentPrice(sc, strategy.getExchange(),
                strategy.getTradingsymbol(), strategy.getToken(), "close");
        BigDecimal openPrice  = angelOneService.getcurrentPrice(sc, strategy.getExchange(),
                strategy.getTradingsymbol(), strategy.getToken(), "open");

        if (!strategy.getActive().equalsIgnoreCase("Y")) {
            logger.info("strangle_920_modified is disabled");
            return;
        }

        if (order == null && !firstOrder) {
            if (analysePrice(closePrice, openPrice)) {
                niftyPrice = getNiftyPrice("15", "30", strategy, 35);
                signal     = "FLAT";
            } else {
                niftyPrice = getNiftyPrice("40", "45", strategy, 50);
                signal     = "UP or DOWN";
            }
            if (niftyPrice > MAX && MAX > 0 && MIN > 0) {
                firstOrder = true;
                logger.info("MAX:{} MIN:{} | First BUY @ {}", MAX, MIN, niftyPrice);
                angelOneService.createStrategy_modified(sc, "NIFTY", 0, "BUY", signal);
            } else if (niftyPrice < MIN && MAX > 0 && MIN > 0) {
                firstOrder = true;
                logger.info("MAX:{} MIN:{} | First SELL @ {}", MAX, MIN, niftyPrice);
                angelOneService.createStrategy_modified(sc, "NIFTY", 0, "SELL", signal);
            }
        } else if (order != null && !secondOrder) {
            BigDecimal currentPrice = angelOneService.getcurrentPrice(sc, strategy.getExchange(),
                    strategy.getTradingsymbol(), strategy.getToken(), "ltp");
            String tradeType = readPriceFromTable("NIFTY", currentPrice);
            String type      = order.getType();
            logger.info("Waiting for Signal | Buy/Sell = {}", tradeType);
            if (tradeType != null && tradeType.equalsIgnoreCase("SELL") && !type.equalsIgnoreCase("SELL")) {
                secondOrder = true;
                angelOneService.createStrategy_modified(sc, "NIFTY", 0, "SELL", null);
            } else if (tradeType != null && tradeType.equalsIgnoreCase("BUY") && !type.equalsIgnoreCase("BUY")) {
                secondOrder = true;
                angelOneService.createStrategy_modified(sc, "NIFTY", 0, "BUY", null);
            }
        }
    }

    public int getNiftyPrice(String startTime, String endTime, Strategy strategy, int triggerMinute) {
        BigDecimal currentPrice = new BigDecimal(0);
        try {
            SmartConnect sc       = angelOne.signIn();
            currentPrice          = angelOneService.getcurrentPrice(sc, strategy.getExchange(),
                    strategy.getTradingsymbol(), strategy.getToken(), "ltp");
            LocalTime now         = LocalTime.now();
            LocalTime triggerTime = LocalTime.of(9, triggerMinute);
            String    today       = LocalDate.now().toString();

            JSONObject req = new JSONObject();
            req.put("exchange",    strategy.getExchange());
            req.put("symboltoken", strategy.getToken());
            req.put("interval",    "FIVE_MINUTE");
            req.put("fromdate",    today + " 09:" + startTime);
            req.put("todate",      today + " 09:" + endTime);

            JSONArray jsonArray = sc.candleData(req);

            if (now.isAfter(triggerTime) && !jsonArray.isEmpty() && MAX == 0) {
                List<Integer> maxList = new ArrayList<>();
                List<Integer> minList = new ArrayList<>();
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONArray inner = (JSONArray) jsonArray.get(i);
                    maxList.add(inner.getInt(2));
                    minList.add(inner.getInt(3));
                }
                Collections.sort(maxList, Collections.reverseOrder());
                Collections.sort(minList);
                MAX = maxList.get(0);
                MIN = minList.get(0);
            }
        } catch (Exception ex) {
            logger.error("ERROR WHILE GET NIFTY FUTURE PRICE: {}", ex.getMessage());
        }
        return currentPrice.intValue();
    }

    public String readPriceFromTable(String name, BigDecimal currentPriceValue) {
        String result = null;
        if (currentPriceValue != null) {
            int            currentPrice = currentPriceValue.intValue();
            List<Stoploss> priceList    = priceRepo.findTop3ByNameOrderByIdDesc(name);
            if (priceList.size() >= 3 && currentPrice != 0) {
                int max = (int) priceList.stream().filter(p -> currentPrice >= p.getMax().intValue()).count();
                int min = (int) priceList.stream().filter(p -> currentPrice <= p.getMin().intValue()).count();
                if (max == 3) result = "BUY";
                if (min == 3) result = "SELL";
            }
        }
        return result;
    }

    public boolean analysePrice(BigDecimal closePrice, BigDecimal openPrice) {
        return Math.abs(closePrice.intValue() - openPrice.intValue()) <= 50;
    }

    @Transactional
    public void updateStrategy() {
        logger.info("Both calls have been taken");
    }
}