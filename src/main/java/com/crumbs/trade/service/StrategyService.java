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
    
    // 🟢 FIX 1: Strangle flags are no longer static global variables
    private boolean timeCheck   = false;
    private boolean firstOrder  = false;
    private boolean secondOrder = false;
    private int strangleMax = 0;
    private int strangleMin = 0;

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
    @Autowired StraddleMarketDataService straddleMarketDataService;
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
    // =========================================================================
    public void executeCPRStrategy() throws SmartAPIException {

        if (skipCPRTakeStraddle.get()) {
            logger.info("🛑 VOLATILITY EXHAUSTION: Big candle day (>100 pts) detected.");
            logger.info("⏭️  Skipping directional ORB strategy. Monitoring Straddle ONLY.");
            cprStraddleService.monitorStraddleSL();
            return; 
        }

        SmartConnect sc = angelOne.signIn();
        com.crumbs.trade.entity.CPR cprEntity = cprRepo.findByName(AppConstant.CPR_STRATEGY);
        
        // 🟢 FIX 2: Fetch strategy and active trade ONCE to eliminate redundant DB calls
        Strategy strategy = strategyRepo.findByName(AppConstant.CPR_STRATEGY);
        Orders activeTrade = orderRepository.findByNameAndActive(AppConstant.CPR_STRATEGY, 1);

        if (cprEntity == null || strategy == null) {
            logger.warn("⚠️ CPR data or Strategy not found in DB — skipping.");
            return;
        }

        getCPRStrategySignal(cprEntity, sc, strategy, activeTrade);
    }

    // =========================================================================
    // EOD EXIT  (called at 15:20)
    // =========================================================================
    public void exitAllCPRPositions() {
        try {
            logger.info("⏰ EOD Exit triggered for CPR Strategy");

            if (skipCPRTakeStraddle.get()) {
                cprStraddleService.exitAllStraddlePositions();
            }

            Orders activeTrade = orderRepository.findByNameAndActive(AppConstant.CPR_STRATEGY, 1);
            if (activeTrade != null) {
                // 🟢 FIX 3: OrderService fetches LTP naturally during exit, no need to pass it here
                exitCurrentTrade("EOD_SQUARE_OFF", BigDecimal.ZERO);
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
        
        // 🟢 FIX 4: Reset Strangle variables cleanly
        timeCheck   = false;
        firstOrder  = false;
        secondOrder = false;
        strangleMax = 0;
        strangleMin = 0;

        cprStraddleService.resetDailyFlags();
        logger.info("🔄 CPR and Strangle daily flags reset successfully.");
    }

    // =========================================================================
    // SIGNAL GENERATION
    // =========================================================================
    public void getCPRStrategySignal(com.crumbs.trade.entity.CPR cprDetails,
                                     SmartConnect smartconnect, 
                                     Strategy strategy, 
                                     Orders activeTrade) {

        BigDecimal topPivot    = cprDetails.getTop();
        BigDecimal bottomPivot = cprDetails.getBottom();
        BigDecimal pivot       = cprDetails.getPivot();
        BigDecimal first5High  = cprDetails.getHigh();
        BigDecimal first5Low   = cprDetails.getLow();

        if (topPivot == null || bottomPivot == null || pivot == null || first5High == null || first5Low == null) {
            logger.warn("⚠️ CPR or First 5-min candle missing — skipping signal.");
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
        
        BigDecimal currentPrice = angelOneService.getcurrentPrice(
                smartconnect, strategy.getExchange(),
                strategy.getTradingsymbol(), strategy.getToken(), "ltp");

        if (currentPrice == null) return;

        logger.info("💹 LTP={} | first5H={} first5L={} | upperBand={} lowerBand={}",
                currentPrice, first5High, first5Low, upperBand, lowerBand);

        // 🟢 Pass the active trade downstream
        checkStoploss(currentPrice, first5High, first5Low, upperBand, lowerBand, marketType, activeTrade);

        String signal = generateSignal(currentPrice, first5High, first5Low, upperBand, lowerBand, marketType);

        logger.info("📶 Signal={} | marketType={}", signal, marketType);

        executeCPRStrategyOrders(signal, currentPrice, first5High, first5Low,
                                 upperBand, lowerBand, pivot, marketType, activeTrade);
    }

    // =========================================================================
    // GAP DETECTION
    // =========================================================================
    private String detectMarketType(BigDecimal todayOpen, BigDecimal pivot) {
        BigDecimal diff = todayOpen.subtract(pivot);
        if (diff.compareTo(BigDecimal.valueOf(GAP_THRESHOLD_POINTS)) > 0) return "GAP_UP";
        else if (diff.compareTo(BigDecimal.valueOf(-GAP_THRESHOLD_POINTS)) < 0) return "GAP_DOWN";
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
        return "WAIT";
    }

    // =========================================================================
    // STOPLOSS CHECK
    // =========================================================================
    private void checkStoploss(BigDecimal price,
                                BigDecimal first5High, BigDecimal first5Low,
                                BigDecimal upperBand,  BigDecimal lowerBand,
                                String marketType, Orders activeTrade) {

        if (activeTrade == null) {
            buySLConfirmCount = 0;
            sellSLConfirmCount = 0;
            return;
        }

        String  activeType      = activeTrade.getType();
        boolean buySLConditionMet  = false;
        boolean sellSLConditionMet = false;

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

        if (buySLConditionMet) {
            buySLConfirmCount++;
            if (buySLConfirmCount >= BREAKOUT_CONFIRM_TICKS) {
                logger.info("🛑 BUY SL Hit @ {}", price);
                exitCurrentTrade("BUY_SL_HIT", price);
                buySLHit.set(true);
                cprBuyTradeTaken.set(true);
                buySLConfirmCount = 0; 
            }
        } else if ("BUY".equalsIgnoreCase(activeType)) {
            buySLConfirmCount = 0; 
        }

        if (sellSLConditionMet) {
            sellSLConfirmCount++;
            if (sellSLConfirmCount >= BREAKOUT_CONFIRM_TICKS) {
                logger.info("🛑 SELL SL Hit @ {}", price);
                exitCurrentTrade("SELL_SL_HIT", price);
                sellSLHit.set(true);
                cprSellTradeTaken.set(true);
                sellSLConfirmCount = 0; 
            }
        } else if ("SELL".equalsIgnoreCase(activeType)) {
            sellSLConfirmCount = 0;
        }
    }

    // =========================================================================
    // ORDER EXECUTION
    // =========================================================================
    public void executeCPRStrategyOrders(String signal,
                                          BigDecimal currentPrice,
                                          BigDecimal first5High, BigDecimal first5Low,
                                          BigDecimal upperBand,  BigDecimal lowerBand,
                                          BigDecimal pivot,      String marketType, 
                                          Orders activeTrade) {
        try {
            if (buySLHit.get() && sellSLHit.get()) return;

            boolean oppositeActive = activeTrade != null && !activeTrade.getType().equalsIgnoreCase(signal);

            if ("BUY".equals(signal)) {
                if (cprBuyTradeTaken.get() || buySLHit.get()) return;
                if (oppositeActive) { exitCurrentTrade("SIGNAL_REVERSAL", currentPrice); }
                
                orderService.orderPlace(AppConstant.CPR_STRATEGY, 0, "BUY",
                        buildOrderMeta(currentPrice, first5High, first5Low, upperBand, lowerBand, pivot, marketType, "BUY"));
                cprBuyTradeTaken.set(true);

            } else if ("SELL".equals(signal)) {
                if (cprSellTradeTaken.get() || sellSLHit.get()) return;
                if (oppositeActive) { exitCurrentTrade("SIGNAL_REVERSAL", currentPrice); }
                
                orderService.orderPlace(AppConstant.CPR_STRATEGY, 0, "SELL",
                        buildOrderMeta(currentPrice, first5High, first5Low, upperBand, lowerBand, pivot, marketType, "SELL"));
                cprSellTradeTaken.set(true);
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
        return meta;
    }

    // =========================================================================
    // EXIT CURRENT TRADE (Delegated to OrderService)
    // =========================================================================
    private void exitCurrentTrade(String reason, BigDecimal indexPrice) {
        try {
            logger.info("⚠️ Initiating trade exit. Reason: {} | Index Price: {}", reason, indexPrice);
            
            // 🟢 FIX 5: OrderService handles P&L calculation and DB commits 
            orderService.exitActiveTrade(AppConstant.CPR_STRATEGY);
            
        } catch (Exception | SmartAPIException e) {
            logger.error("❌ Error delegating exit to OrderService", e);
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

        JSONArray candles = straddleMarketDataService.fetchCandleWithRetry(smartconnect, req, strategy.getToken());

        if (candles == null || candles.isEmpty()) return dto;

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
            logger.error("❌ Failed to parse ONE_DAY candle data", e);
            return dto;
        }

        CPR cpr = priceUtilService.calculateCpr(dto.getHigh(), dto.getLow(), dto.getClose());
        if (cpr != null) {
            dto.setBottom_pivot(cpr.getBottom_pivot());
            dto.setPivot(cpr.getPivot());
            dto.setTop_pivot(cpr.getTop_pivot());
            dto.setCprWidth(cpr.getWidthType());
            dto.setCprType(cpr.getCprType());
        }

        if (dto.getTop_pivot() != null && dto.getBottom_pivot() != null && dto.getPivot() != null) {
            BigDecimal cprWidth   = dto.getTop_pivot().subtract(dto.getBottom_pivot()).abs();
            BigDecimal cprPercent = cprWidth.divide(dto.getPivot(), 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
            dto.setCprPercent(cprPercent);
        }

        return dto;
    }

    // =========================================================================
    // FIRST 5-MIN CANDLE
    // =========================================================================
    public StrangleCprDto getFirstCandleData(SmartConnect smartConnect, Strategy strategy, StrangleCprDto dto) {
        LocalDate today    = LocalDate.now();
        String    fromDate = today + " 09:15";
        String    toDate   = today + " 09:20";

        JSONObject req = new JSONObject();
        req.put("exchange",    strategy.getExchange());
        req.put("symboltoken", strategy.getToken());
        req.put("interval",    "FIVE_MINUTE");
        req.put("fromdate",    fromDate);
        req.put("todate",      toDate);

        JSONArray ohlc = straddleMarketDataService.fetchCandleWithRetry(smartConnect, req, strategy.getToken());

        if (ohlc == null || ohlc.isEmpty()) return null;

        JSONArray  firstCandle = ohlc.getJSONArray(0);
        BigDecimal high        = BigDecimal.valueOf(firstCandle.getDouble(2));
        BigDecimal low         = BigDecimal.valueOf(firstCandle.getDouble(3));
        BigDecimal buffer      = BigDecimal.valueOf(FIRST_CANDLE_BUFFER);

        dto.setFirstFiveMinHigh(high.add(buffer));
        dto.setFirstFiveMinLow(low.subtract(buffer));

        return dto;
    }

    // =========================================================================
    // SAVE CPR TO DB
    // =========================================================================
    public com.crumbs.trade.entity.CPR saveCPR(StrangleCprDto dto, String name, String date) {
        if (dto == null) return null;

        com.crumbs.trade.entity.CPR cpr = new com.crumbs.trade.entity.CPR();
        cpr.setName(name);
        cpr.setDate(date);
        cpr.setPivot(dto.getPivot());
        cpr.setTop(dto.getTop_pivot());
        cpr.setBottom(dto.getBottom_pivot());
        cpr.setHigh(dto.getFirstFiveMinHigh());
        cpr.setLow(dto.getFirstFiveMinLow());

        return cprRepo.save(cpr);
    }

    // =========================================================================
    // STRANGLE
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

        if (!strategy.getActive().equalsIgnoreCase("Y")) return;

     // Inside StrategyService.java -> shortStrangleModified()
        if (order == null && !firstOrder) {
            if (analysePrice(closePrice, openPrice)) {
                niftyPrice = getNiftyPrice("15", "30", strategy, 35);
                signal     = "FLAT";
            } else {
                niftyPrice = getNiftyPrice("40", "45", strategy, 50);
                signal     = "UP or DOWN";
            }
            
            if (niftyPrice > strangleMax && strangleMax > 0 && strangleMin > 0) {
                firstOrder = true;
                logger.info("MAX:{} MIN:{} | First BUY @ {}", strangleMax, strangleMin, niftyPrice);
                
                // 🟢 FIX: Pass strangleMin dynamically as the breakEven parameter
                angelOneService.createStrategy_modified(sc, "NIFTY", 0, "BUY", signal, strangleMin); 
                
            } else if (niftyPrice < strangleMin && strangleMax > 0 && strangleMin > 0) {
                firstOrder = true;
                logger.info("MAX:{} MIN:{} | First SELL @ {}", strangleMax, strangleMin, niftyPrice);
                
                // 🟢 FIX: Pass strangleMax dynamically as the breakEven parameter
                angelOneService.createStrategy_modified(sc, "NIFTY", 0, "SELL", signal, strangleMax); 
            }
        } else if (order != null && !secondOrder) {
            BigDecimal currentPrice = angelOneService.getcurrentPrice(sc, strategy.getExchange(),
                    strategy.getTradingsymbol(), strategy.getToken(), "ltp");
            String tradeType = readPriceFromTable("NIFTY", currentPrice);
            String type      = order.getType();
            
            logger.info("Waiting for Signal | Buy/Sell = {}", tradeType);
            if (tradeType != null && tradeType.equalsIgnoreCase("SELL") && !type.equalsIgnoreCase("SELL")) {
                secondOrder = true;
                
                // 🟢 FIX: Pass strangleMax dynamically
                angelOneService.createStrategy_modified(sc, "NIFTY", 0, "SELL", null, strangleMax); 
                
            } else if (tradeType != null && tradeType.equalsIgnoreCase("BUY") && !type.equalsIgnoreCase("BUY")) {
                secondOrder = true;
                
                // 🟢 FIX: Pass strangleMin dynamically
                angelOneService.createStrategy_modified(sc, "NIFTY", 0, "BUY", null, strangleMin); 
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

            if (now.isAfter(triggerTime) && !jsonArray.isEmpty() && strangleMax == 0) {
                List<Integer> maxList = new ArrayList<>();
                List<Integer> minList = new ArrayList<>();
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONArray inner = (JSONArray) jsonArray.get(i);
                    maxList.add(inner.getInt(2));
                    minList.add(inner.getInt(3));
                }
                Collections.sort(maxList, Collections.reverseOrder());
                Collections.sort(minList);
                strangleMax = maxList.get(0);
                strangleMin = minList.get(0);
            }
        } catch (Exception ex) {
            logger.error("ERROR WHILE GET NIFTY FUTURE PRICE", ex);
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