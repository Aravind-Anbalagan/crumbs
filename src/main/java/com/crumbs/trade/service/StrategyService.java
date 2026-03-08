package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

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
    private static final int    GAP_THRESHOLD_POINTS = 50;   // pts diff open vs pivot → gap day
    private static final int    FIRST_CANDLE_BUFFER  = 5;    // buffer added to first 5-min high/low

    // =========================================================================
    // DAY FLAGS  (reset at 09:00 AM daily)
    // =========================================================================
    private boolean cprBuyTradeTaken  = false;
    private boolean cprSellTradeTaken = false;
    private boolean buySLHit          = false;
    private boolean sellSLHit         = false;

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
    @Autowired RestTemplate    restTemplate;
    @Autowired AngelOne        angelOne;
    @Autowired AngelOneService angelOneService;
    @Autowired StrategyRepo    strategyRepo;
    @Autowired OrderRepository orderRepository;
    @Autowired PriceRepo       priceRepo;
    @Autowired TaskService     taskService;
    @Autowired ChartService    chartService;
    @Autowired CPRRepo         cprRepo;
    @Autowired OrderService    orderService;

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

        if (dto != null && dto.getFirstFiveMinHigh() != null && dto.getFirstFiveMinLow() != null) {
            String dateTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            saveCPR(dto, strategy.getName(), dateTime);
        } else {
            logger.error("❌ Unable to fetch CPR Details");
        }
    }

    // =========================================================================
    // STEP 2 — Execute strategy  (called every 1 min 09:21 → 15:20)
    // =========================================================================
    public void executeCPRStrategy() {
        SmartConnect              sc         = angelOne.signIn();
        com.crumbs.trade.entity.CPR cprEntity = cprRepo.findByName(AppConstant.CPR_STRATEGY);

        if (cprEntity == null) {
            logger.warn("⚠️ CPR data not found in DB — skipping.");
            return;
        }

        getCPRStrategySignal(cprEntity, sc);
    }

    // =========================================================================
    // SIGNAL GENERATION
    // =========================================================================
    public void getCPRStrategySignal(com.crumbs.trade.entity.CPR cprDetails,
                                     SmartConnect smartconnect) {

        Strategy strategy = strategyRepo.findByName(AppConstant.CPR_STRATEGY);

        // -- CPR bands --
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

        // canonical bands
        BigDecimal upperBand = topPivot.max(bottomPivot);
        BigDecimal lowerBand = topPivot.min(bottomPivot);

        // -- Today open --
        BigDecimal todayOpen = angelOneService.getcurrentPrice(
                smartconnect, strategy.getExchange(),
                strategy.getTradingsymbol(), strategy.getToken(), "open");

        if (todayOpen == null) {
            logger.warn("⚠️ Unable to fetch today open price.");
            return;
        }

        // -- Detect gap --
        String marketType = detectMarketType(todayOpen, pivot);
        logger.info("📊 Market Type: {} | open={} pivot={}", marketType, todayOpen, pivot);

        // -- Current price --
        BigDecimal currentPrice = angelOneService.getcurrentPrice(
                smartconnect, strategy.getExchange(),
                strategy.getTradingsymbol(), strategy.getToken(), "ltp");

        if (currentPrice == null) {
            logger.warn("⚠️ Current price NULL — skipping.");
            return;
        }

        logger.info("💹 LTP={} | first5H={} first5L={} | upperBand={} lowerBand={}",
                currentPrice, first5High, first5Low, upperBand, lowerBand);

        // -- Check SL on active trade FIRST --
        checkStoploss(currentPrice, first5High, first5Low, upperBand, lowerBand, marketType);

        // -- Generate entry signal --
        String signal = generateSignal(currentPrice, first5High, first5Low,
                                       upperBand, lowerBand, marketType);

        logger.info("📶 Signal={} | marketType={}", signal, marketType);

        // -- Execute --
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
    // SIGNAL GENERATION — NORMAL vs GAP
    // =========================================================================
    private String generateSignal(BigDecimal price,
                                  BigDecimal first5High, BigDecimal first5Low,
                                  BigDecimal upperBand,  BigDecimal lowerBand,
                                  String marketType) {
        if ("NORMAL".equals(marketType)) {
            // BUY  → above first5High AND above upperBand
            if (price.compareTo(first5High) > 0 && price.compareTo(upperBand) > 0) {
                return "BUY";
            }
            // SELL → below first5Low AND below lowerBand
            if (price.compareTo(first5Low) < 0 && price.compareTo(lowerBand) < 0) {
                return "SELL";
            }

        } else {
            // GAP UP / GAP DOWN — only first 5-min candle matters
            if (price.compareTo(first5High) > 0) {
                return "BUY";
            }
            if (price.compareTo(first5Low) < 0) {
                return "SELL";
            }
        }

        return "WAIT";
    }

    // =========================================================================
    // STOPLOSS CHECK  (runs every cycle before entry check)
    // =========================================================================
    private void checkStoploss(BigDecimal price,
                                BigDecimal first5High, BigDecimal first5Low,
                                BigDecimal upperBand,  BigDecimal lowerBand,
                                String marketType) {

        Orders activeTrade = orderRepository.findByNameAndActive(AppConstant.CPR_STRATEGY, 1);
        if (activeTrade == null) return;

        String activeType = activeTrade.getType();

        boolean buySLTriggered  = false;
        boolean sellSLTriggered = false;

        if ("NORMAL".equals(marketType)) {
            // BUY SL  → price < first5Low AND price < lowerBand
            buySLTriggered  = "BUY".equalsIgnoreCase(activeType)
                           && price.compareTo(first5Low) < 0
                           && price.compareTo(lowerBand) < 0;
            // SELL SL → price > first5High AND price > upperBand
            sellSLTriggered = "SELL".equalsIgnoreCase(activeType)
                           && price.compareTo(first5High) > 0
                           && price.compareTo(upperBand) > 0;
        } else {
            // GAP day — only first 5-min candle
            buySLTriggered  = "BUY".equalsIgnoreCase(activeType)
                           && price.compareTo(first5Low) < 0;
            sellSLTriggered = "SELL".equalsIgnoreCase(activeType)
                           && price.compareTo(first5High) > 0;
        }

        if (buySLTriggered) {
            logger.info("🛑 BUY SL Hit @ {} | first5Low={}", price, first5Low);
            exitCurrentTrade();
            buySLHit         = true;
            cprBuyTradeTaken = true;   // block BUY re-entry
            logger.info("🔒 BUY blocked for today. SELL side still open.");

            if (buySLHit && sellSLHit) {
                logger.info("🚫 Both SL hit — No more trades today.");
            }
        }

        if (sellSLTriggered) {
            logger.info("🛑 SELL SL Hit @ {} | first5High={}", price, first5High);
            exitCurrentTrade();
            sellSLHit         = true;
            cprSellTradeTaken = true;  // block SELL re-entry
            logger.info("🔒 SELL blocked for today. BUY side still open.");

            if (buySLHit && sellSLHit) {
                logger.info("🚫 Both SL hit — No more trades today.");
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

            // Both SL hit — no trades
            if (buySLHit && sellSLHit) {
                logger.info("🚫 Both SL hit — skipping all signals.");
                return;
            }

            Orders activeTrade  = orderRepository.findByNameAndActive(AppConstant.CPR_STRATEGY, 1);
            boolean oppositeActive = activeTrade != null
                                  && !activeTrade.getType().equalsIgnoreCase(signal);

            if ("BUY".equals(signal)) {

                if (buySLHit) {
                    logger.info("🚫 BUY SL already hit today — skipping BUY.");
                    return;
                }

                if (cprBuyTradeTaken && !oppositeActive) {
                    logger.info("🚫 CPR BUY already executed today — skipping.");
                    return;
                }

                logger.info("🔥 CPR BUY signal → orderPlace()");
                orderService.orderPlace(
                        AppConstant.CPR_STRATEGY, 0, "BUY",
                        buildOrderMeta(currentPrice, first5High, first5Low,
                                       upperBand, lowerBand, pivot, marketType, "BUY")
                );
                cprBuyTradeTaken  = true;
                cprSellTradeTaken = false;

            } else if ("SELL".equals(signal)) {

                if (sellSLHit) {
                    logger.info("🚫 SELL SL already hit today — skipping SELL.");
                    return;
                }

                if (cprSellTradeTaken && !oppositeActive) {
                    logger.info("🚫 CPR SELL already executed today — skipping.");
                    return;
                }

                logger.info("🔥 CPR SELL signal → orderPlace()");
                orderService.orderPlace(
                        AppConstant.CPR_STRATEGY, 0, "SELL",
                        buildOrderMeta(currentPrice, first5High, first5Low,
                                       upperBand, lowerBand, pivot, marketType, "SELL")
                );
                cprSellTradeTaken = true;
                cprBuyTradeTaken  = false;

            } else {
                logger.info("⏸ Signal={} — no trade.", signal);
            }

        } catch (Exception | SmartAPIException e) {
            logger.error("❌ Error placing CPR order", e);
        }
    }

    // =========================================================================
    // BUILD ORDER META  (extra data to store with order)
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

        // Stoploss reference
        if ("BUY".equals(signal)) {
            meta.setSlPrice("NORMAL".equals(marketType) ? lowerBand : first5Low);
        } else {
            meta.setSlPrice("NORMAL".equals(marketType) ? upperBand : first5High);
        }

        meta.setEntryTime(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return meta;
    }

    // =========================================================================
    // EXIT CURRENT TRADE  (SL hit / EOD)
    // =========================================================================
    private void exitCurrentTrade() {
        try {
            orderService.exitActiveTrade(AppConstant.CPR_STRATEGY);
        } catch (Exception | SmartAPIException e) {
            logger.error("❌ Error exiting CPR trade", e);
        }
    }

    // =========================================================================
    // EOD EXIT  (called at 3:20 PM)
    // =========================================================================
    public void exitAllCPRPositions() {
        try {
            logger.info("⏰ EOD Exit triggered for CPR Strategy");
            orderService.exitActiveTrade(AppConstant.CPR_STRATEGY);
            cprBuyTradeTaken  = false;
            cprSellTradeTaken = false;
        } catch (Exception | SmartAPIException e) {
            logger.error("❌ Error during EOD CPR exit", e);
        }
    }

    // =========================================================================
    // DAILY FLAG RESET  (called at 9:00 AM)
    // =========================================================================
    public void resetDailyFlags() {
        cprBuyTradeTaken  = false;
        cprSellTradeTaken = false;
        buySLHit          = false;
        sellSLHit         = false;
        logger.info("🔄 CPR daily flags reset successfully.");
    }

    // =========================================================================
    // CPR CALCULATION
    // =========================================================================
    public StrangleCprDto getCPR(SmartConnect smartconnect, Strategy strategy,
                                 StrangleCprDto dto) throws IOException, SmartAPIException {

        LocalDate today               = LocalDate.now();
        LocalDate lastWorkingDay      = NSEWorkingDays.getLastWorkingDay(today);
        LocalDate previousWorkingDay  = NSEWorkingDays.getLastWorkingDay(lastWorkingDay);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        String fromDate = previousWorkingDay.atTime(9, 15).format(formatter);
        String toDate   = lastWorkingDay.atTime(9, 15).format(formatter);

        JSONArray candles = getCandleDataByChoice(smartconnect, strategy, dto,
                                                  "ONE_DAY", fromDate, toDate);
        if (candles == null || candles.isEmpty()) {
            logger.warn("No ONE_DAY candles returned for CPR");
            return dto;
        }

        Object first = candles.get(0);
        if (first instanceof JSONArray candle) {
            dto.setOpen(BigDecimal.valueOf(candle.getDouble(1)));
            dto.setLow(BigDecimal.valueOf(candle.getDouble(2)));
            dto.setHigh(BigDecimal.valueOf(candle.getDouble(3)));
            dto.setClose(BigDecimal.valueOf(candle.getDouble(4)));
        } else {
            dto.setOpen(BigDecimal.valueOf(candles.getDouble(1)));
            dto.setLow(BigDecimal.valueOf(candles.getDouble(2)));
            dto.setHigh(BigDecimal.valueOf(candles.getDouble(3)));
            dto.setClose(BigDecimal.valueOf(candles.getDouble(4)));
        }

        logger.info("ONE_DAY OHLC → O={} H={} L={} C={}",
                dto.getOpen(), dto.getHigh(), dto.getLow(), dto.getClose());

        CPR cpr = taskService.calculateCpr(dto.getHigh(), dto.getLow(), dto.getClose());
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
            BigDecimal cprWidth   = dto.getTop_pivot().subtract(dto.getBottom_pivot());
            BigDecimal cprPercent = cprWidth
                    .divide(dto.getPivot(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
            dto.setCprPercent(cprPercent);
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

        JSONArray ohlc = getCandleDataByChoice(smartConnect, strategy, dto,
                                               "FIVE_MINUTE", fromDate, toDate);

        if (ohlc == null || ohlc.length() < 4) {
            logger.warn("FIRST 5-min candle not available");
            return null;
        }

        // FIVE_MINUTE format → [time, open, high, low, close, volume]
        BigDecimal high   = BigDecimal.valueOf(ohlc.getDouble(2));
        BigDecimal low    = BigDecimal.valueOf(ohlc.getDouble(3));
        BigDecimal buffer = BigDecimal.valueOf(FIRST_CANDLE_BUFFER);

        dto.setFirstFiveMinHigh(high.add(buffer));
        dto.setFirstFiveMinLow(low.subtract(buffer));

        logger.info("First 5-min (buffered +{}) → high={} low={}",
                FIRST_CANDLE_BUFFER, dto.getFirstFiveMinHigh(), dto.getFirstFiveMinLow());

        return dto;
    }

    // =========================================================================
    // SAVE CPR TO DB
    // =========================================================================
    public com.crumbs.trade.entity.CPR saveCPR(StrangleCprDto dto, String name, String date) {
        if (dto == null) {
            logger.error("❌ Unable to save CPR — dto is null");
            return null;
        }

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
        if (response != null && !response.isEmpty()) {
            return (JSONArray) response.get(0);
        }
        return null;
    }

    // =========================================================================
    // STRANGLE (unchanged)
    // =========================================================================
    public void shortStrangleModified() throws SmartAPIException, Exception {
        Strategy  strategy = strategyRepo.findByName("STRANGLE");
        Orders    order    = orderRepository.findByNameAndActive("NIFTY", 1);
        SmartConnect sc    = angelOne.signIn();
        int niftyPrice     = 0;
        String signal;

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
                logger.info("Second SELL @ {}", niftyPrice);
                angelOneService.createStrategy_modified(sc, "NIFTY", 0, "SELL", null);
            } else if (tradeType != null && tradeType.equalsIgnoreCase("BUY") && !type.equalsIgnoreCase("BUY")) {
                secondOrder = true;
                logger.info("Second BUY @ {}", niftyPrice);
                angelOneService.createStrategy_modified(sc, "NIFTY", 0, "BUY", null);
            }
        }
    }

    public int getNiftyPrice(String startTime, String endTime, Strategy strategy, int triggerValue) {
        BigDecimal currentPrice = new BigDecimal(0);
        try {
            Date         now         = new Date();
            Date         triggerTime = new Date();
            SmartConnect sc          = angelOne.signIn();

            currentPrice = angelOneService.getcurrentPrice(sc, strategy.getExchange(),
                    strategy.getTradingsymbol(), strategy.getToken(), "ltp");

            String format = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            triggerTime.setHours(9);
            triggerTime.setMinutes(triggerValue);

            JSONObject req = new JSONObject();
            req.put("exchange",    strategy.getExchange());
            req.put("symboltoken", strategy.getToken());
            req.put("interval",    "FIVE_MINUTE");
            req.put("fromdate",    format + " 09:" + startTime);
            req.put("todate",      format + " 09:" + endTime);

            JSONArray jsonArray = sc.candleData(req);

            if (now.compareTo(triggerTime) > 0 && !jsonArray.isEmpty() && MAX == 0) {
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
            int              currentPrice = currentPriceValue.intValue();
            List<Stoploss>   priceList    = priceRepo.findTop3ByNameOrderByIdDesc(name);
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
        int diff = Math.abs(closePrice.intValue() - openPrice.intValue());
        return diff <= 50;
    }

    @Transactional
    public void updateStrategy() {
        logger.info("Both calls have been taken");
    }
}