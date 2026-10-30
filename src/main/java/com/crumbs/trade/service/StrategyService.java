package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.crumbs.trade.repo.*;
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
import com.crumbs.trade.utility.AppConstant;
import com.crumbs.trade.utility.NSEWorkingDays;

import jakarta.transaction.Transactional;

@Service
public class StrategyService {

    Logger logger = LoggerFactory.getLogger(StrategyService.class);

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    // =========================================================================
    // CONFIGURABLE CONSTANTS
    // =========================================================================
    private static final int  GAP_THRESHOLD_POINTS   = 50;  // pts diff open vs pivot -> gap day
    private static final int  FIRST_CANDLE_BUFFER    = 5;   // buffer added to first 5-min high/low
    private static final int  BREAKOUT_CONFIRM_TICKS = 5;   // consecutive 1-min ticks to confirm breakout
    private static final int  EOD_EXIT_RETRIES       = 3;   // attempts for the 15:20 square-off
    private static final long EOD_RETRY_SLEEP_MS     = 2_000;

    // =========================================================================
    // DAY FLAGS  (reset at 09:00 AM daily)
    // =========================================================================
    private final AtomicBoolean cprBuyTradeTaken  = new AtomicBoolean(false);
    private final AtomicBoolean cprSellTradeTaken = new AtomicBoolean(false);
    private final AtomicBoolean buySLHit          = new AtomicBoolean(false);
    private final AtomicBoolean sellSLHit         = new AtomicBoolean(false);

    /** Set at 15:20 so the 1-min monitor can never open a trade during/after the EOD exit. */
    private final AtomicBoolean eodDone       = new AtomicBoolean(false);
    /** True once today's flags were rebuilt from the DB (restart safety). */
    private final AtomicBoolean flagsRestored = new AtomicBoolean(false);

    private int buyConfirmCount    = 0;
    private int sellConfirmCount   = 0;
    private int buySLConfirmCount  = 0;
    private int sellSLConfirmCount = 0;

    // Strangle flags
    private boolean timeCheck   = false;
    private boolean firstOrder  = false;
    private boolean secondOrder = false;
    private int     strangleMax = 0;
    private int     strangleMin = 0;

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
    @Autowired StraddleMarketDataService straddleMarketDataService;
    @Autowired MonitorOrderService monitorOrderService;
    @Autowired
    IndexesRepo indexesRepo;
    // =========================================================================
    // STEP 1 — Fetch CPR + First 5-min candle  (called at 09:20:10)
    // FIX: CPR values are validated before persisting, so a failed getCPR()
    //      can no longer save "today's" row with null pivot/top/bottom.
    // =========================================================================
    public void getCPRDetails() throws IOException, SmartAPIException {

        StrangleCprDto dto      = new StrangleCprDto();
        Strategy       strategy = strategyRepo.findByName(AppConstant.CPR_STRATEGY);
        if (strategy == null) {
            logger.error("❌ Strategy {} not found in DB.", AppConstant.CPR_STRATEGY);
            return;
        }

        SmartConnect sc = angelOne.signIn();

        LocalTime now = LocalTime.now(IST_ZONE);
        if (now.isBefore(LocalTime.of(9, 20))) {
            logger.info("⏰ Wait until 09:20 for first 5-min candle to close.");
            return;
        }

        dto = getCPR(sc, strategy, dto);
        dto = getFirstCandleData(sc, strategy, dto);

        if (dto == null || dto.getFirstFiveMinHigh() == null || dto.getFirstFiveMinLow() == null) {
            logger.error("❌ Unable to fetch first 5-min candle");
            return;
        }

        // CPR must be complete, otherwise do NOT persist (next tick will retry via self-heal)
        if (dto.getPivot() == null || dto.getTop_pivot() == null || dto.getBottom_pivot() == null) {
            logger.error("❌ CPR incomplete (pivot={} top={} bottom={}) — NOT saving; next tick will retry.",
                    dto.getPivot(), dto.getTop_pivot(), dto.getBottom_pivot());
            return;
        }

        BigDecimal rawRange = dto.getFirstFiveMinHigh()
                .subtract(dto.getFirstFiveMinLow())
                .subtract(BigDecimal.valueOf(FIRST_CANDLE_BUFFER * 2L));

        logger.info("📏 First 5-min candle raw range = {} pts | High(buf)={} Low(buf)={}",
                rawRange, dto.getFirstFiveMinHigh(), dto.getFirstFiveMinLow());

        String dateTime = LocalDateTime.now(IST_ZONE).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        saveCPR(dto, strategy.getName(), dateTime);
    }

    // =========================================================================
    // STEP 2 — Execute directional CPR strategy  (called every 1 min 09:21 -> 15:19)
    // FIX: skips after EOD; restores today's flags from DB on first tick (restart safety)
    // =========================================================================
    public void executeCPRStrategy() throws IOException, SmartAPIException {
        if (eodDone.get()) return;
        restoreFlagsFromDbIfNeeded();

        SmartConnect sc = angelOne.signIn();
        com.crumbs.trade.entity.CPR cprEntity = cprRepo.findByName(AppConstant.CPR_STRATEGY);

        String todayPrefix = LocalDate.now(IST_ZONE).toString();

        // Self-heal: If 09:20:10 fetch failed or DB still holds yesterday's CPR, re-fetch now
        if (cprEntity == null || cprEntity.getDate() == null || !cprEntity.getDate().startsWith(todayPrefix)) {
            logger.warn("⚠️ Today's CPR data missing or stale (found date={}). Attempting recovery fetch...",
                    cprEntity != null ? cprEntity.getDate() : "null");
            getCPRDetails();
            cprEntity = cprRepo.findByName(AppConstant.CPR_STRATEGY);
            if (cprEntity == null || cprEntity.getDate() == null || !cprEntity.getDate().startsWith(todayPrefix)) {
                logger.error("❌ Recovery fetch failed — skipping tick.");
                return;
            }
        }

        Strategy strategy  = strategyRepo.findByName(AppConstant.CPR_STRATEGY);
        Orders activeTrade = orderRepository.findByNameAndActive(AppConstant.CPR_STRATEGY, 1);

        if (strategy == null) {
            logger.warn("⚠️ Strategy not found in DB — skipping.");
            return;
        }

        getCPRStrategySignal(cprEntity, sc, strategy, activeTrade);
    }

    // =========================================================================
    // STEP 3 — EOD EXIT  (called at 15:20)
    // FIX: depends on an OPEN POSITION, not the strategy active flag.
    //      Blocks new entries (eodDone) and retries on failure.
    // =========================================================================
    public void exitAllCPRPositions() {
        eodDone.set(true);   // monitor can no longer enter after this point
        logger.info("⏰ EOD Exit triggered for CPR Strategy");

        for (int attempt = 1; attempt <= EOD_EXIT_RETRIES; attempt++) {
            try {
                Orders activeTrade = orderRepository.findByNameAndActive(AppConstant.CPR_STRATEGY, 1);
                if (activeTrade == null) {
                    logger.info("✅ EOD: no open CPR position.");
                    return;
                }
                if (exitCurrentTrade("EOD_SQUARE_OFF", BigDecimal.ZERO)) {
                    logger.info("✅ EOD exit done (attempt {}).", attempt);
                    return;
                }
            } catch (Exception e) {
                logger.error("❌ EOD exit attempt {} errored", attempt, e);
            }

            try {
                Thread.sleep(EOD_RETRY_SLEEP_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Hook your Telegram / email / SMS alert here.
        logger.error("🚨🚨 EOD EXIT FAILED after {} attempts — SQUARE OFF MANUALLY NOW.", EOD_EXIT_RETRIES);
    }

    // =========================================================================
    // STEP 4 — DAILY FLAG RESET  (called at 09:00 AM, unconditionally)
    // =========================================================================
    public void resetDailyFlags() {
        cprBuyTradeTaken.set(false);
        cprSellTradeTaken.set(false);
        buySLHit.set(false);
        sellSLHit.set(false);
        eodDone.set(false);
        flagsRestored.set(false);

        buyConfirmCount    = 0;
        sellConfirmCount   = 0;
        buySLConfirmCount  = 0;
        sellSLConfirmCount = 0;

        // Reset Strangle variables
        timeCheck   = false;
        firstOrder  = false;
        secondOrder = false;
        strangleMax = 0;
        strangleMin = 0;

        logger.info("🔄 CPR and Strangle daily flags reset successfully.");
    }

    // =========================================================================
    // RESTART SAFETY — rebuild today's "taken" flags from the DB
    //
    // REQUIRES a new method on OrderRepository (adjust the field name to match
    // your Orders entity's creation timestamp: createdAt / orderTime / date ...):
    //
    //   List<Orders> findByNameAndCreatedAtBetween(String name,
    //                                              LocalDateTime from,
    //                                              LocalDateTime to);
    // =========================================================================
    private void restoreFlagsFromDbIfNeeded() {
        if (!flagsRestored.compareAndSet(false, true)) return;
        try {
            LocalDate today = LocalDate.now(IST_ZONE);
            List<Orders> todays = orderRepository.findOrdersByNameAndDateRange(
                    AppConstant.CPR_STRATEGY, today.atStartOfDay(), today.plusDays(1).atStartOfDay());

            for (Orders o : todays) {
                if (isBullishTrade(o)) cprBuyTradeTaken.set(true);
                if (isBearishTrade(o)) cprSellTradeTaken.set(true);
            }
            logger.info("♻️ Flags restored from DB: {} order(s) today | buyTaken={} sellTaken={}",
                    todays.size(), cprBuyTradeTaken.get(), cprSellTradeTaken.get());
        } catch (Exception e) {
            flagsRestored.set(false);   // retry next tick
            logger.error("❌ Could not restore flags from DB — will retry next tick.", e);
        }
    }

    // =========================================================================
    // SIGNAL GENERATION & MONITORING
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

        // 1. Check SL first; if SL exited the trade, clear activeTrade reference so we don't double-exit
        boolean slExited = checkStoploss(currentPrice, first5High, first5Low, upperBand, lowerBand, marketType, activeTrade);
        if (slExited) {
            activeTrade = null;
        }

        // 2. Check for new or reversal signal
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
    // BUY  = Bullish Breakout  (Sell PE)
    // SELL = Bearish Breakdown (Sell CE)
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
    // Returns true ONLY if an active trade was actually exited due to SL hit.
    // FIX: if the exit fails, SL flags are NOT set, the trade reference is kept,
    //      and the confirm counter stays at threshold so the exit retries next tick.
    // =========================================================================
    private boolean checkStoploss(BigDecimal price,
                                  BigDecimal first5High, BigDecimal first5Low,
                                  BigDecimal upperBand,  BigDecimal lowerBand,
                                  String marketType, Orders activeTrade) {

        if (activeTrade == null) {
            buySLConfirmCount  = 0;
            sellSLConfirmCount = 0;
            return false;
        }

        boolean isBullishTrade     = isBullishTrade(activeTrade);
        boolean isBearishTrade     = isBearishTrade(activeTrade);
        boolean buySLConditionMet  = false;
        boolean sellSLConditionMet = false;

        if ("NORMAL".equals(marketType)) {
            buySLConditionMet  = isBullishTrade
                    && price.compareTo(first5Low) < 0
                    && price.compareTo(lowerBand) < 0;
            sellSLConditionMet = isBearishTrade
                    && price.compareTo(first5High) > 0
                    && price.compareTo(upperBand)  > 0;
        } else {
            buySLConditionMet  = isBullishTrade && price.compareTo(first5Low)  < 0;
            sellSLConditionMet = isBearishTrade && price.compareTo(first5High) > 0;
        }

        if (buySLConditionMet) {
            buySLConfirmCount++;
            if (buySLConfirmCount >= BREAKOUT_CONFIRM_TICKS) {
                logger.info("🛑 BULLISH (SHORT PE) SL Hit @ {}", price);
                if (exitCurrentTrade("BUY_SL_HIT", price)) {
                    buySLHit.set(true);
                    cprBuyTradeTaken.set(true);
                    buySLConfirmCount = 0;
                    return true;
                }
                logger.error("🚨 SL exit FAILED for PE short — will retry next tick. CHECK POSITION MANUALLY.");
                return false;
            }
        } else if (isBullishTrade) {
            buySLConfirmCount = 0;
        }

        if (sellSLConditionMet) {
            sellSLConfirmCount++;
            if (sellSLConfirmCount >= BREAKOUT_CONFIRM_TICKS) {
                logger.info("🛑 BEARISH (SHORT CE) SL Hit @ {}", price);
                if (exitCurrentTrade("SELL_SL_HIT", price)) {
                    sellSLHit.set(true);
                    cprSellTradeTaken.set(true);
                    sellSLConfirmCount = 0;
                    return true;
                }
                logger.error("🚨 SL exit FAILED for CE short — will retry next tick. CHECK POSITION MANUALLY.");
                return false;
            }
        } else if (isBearishTrade) {
            sellSLConfirmCount = 0;
        }

        return false;
    }

    // =========================================================================
    // DIRECTIONAL ORDER EXECUTION
    // =========================================================================
    public void executeCPRStrategyOrders(String signal,
                                         BigDecimal currentPrice,
                                         BigDecimal first5High, BigDecimal first5Low,
                                         BigDecimal upperBand,  BigDecimal lowerBand,
                                         BigDecimal pivot,      String marketType,
                                         Orders activeTrade) {
        try {
            if (eodDone.get()) return;
            if (buySLHit.get() && sellSLHit.get()) return;

            // 1. Reversal and Flag Checks
            if ("BUY".equals(signal)) {
                if (activeTrade != null && isBullishTrade(activeTrade)) {
                    cprBuyTradeTaken.set(true); return;
                }
                if (cprBuyTradeTaken.get() || buySLHit.get()) return;
                if (activeTrade != null && isBearishTrade(activeTrade)) {
                    if (!exitCurrentTrade("SIGNAL_REVERSAL", currentPrice)) return;
                }
            } else if ("SELL".equals(signal)) {
                if (activeTrade != null && isBearishTrade(activeTrade)) {
                    cprSellTradeTaken.set(true); return;
                }
                if (cprSellTradeTaken.get() || sellSLHit.get()) return;
                if (activeTrade != null && isBullishTrade(activeTrade)) {
                    if (!exitCurrentTrade("SIGNAL_REVERSAL", currentPrice)) return;
                }
            } else {
                return; // WAIT signal
            }

            // 2. Build the Token
            Strategy strategy = strategyRepo.findByName(AppConstant.CPR_STRATEGY);
            SmartConnect sc = angelOne.signIn();

            int nearestStrike = chartService.findNearestMultiple(currentPrice.intValue(), 50);
            String optionType = "BUY".equals(signal) ? "PE" : "CE";
            String tradingSymbol = String.format("%s%s%d%s", strategy.getSymbol(), strategy.getExpiry(), nearestStrike, optionType);

            com.crumbs.trade.entity.Indexes idx = indexesRepo.findByNameAndSymbol(strategy.getName(), tradingSymbol);
            if (idx == null) {
                logger.error("❌ Index not found for symbol {}", tradingSymbol);
                return;
            }

            com.crumbs.trade.dto.Token token = new com.crumbs.trade.dto.Token();
            token.setSymbol(tradingSymbol);
            token.setToken(idx.getToken());
            token.setExch_seg(idx.getExchange());
            token.setQuantity(idx.getLotsize());
            token.setStrike(BigDecimal.valueOf(nearestStrike));

            // 3. Fetch real Option Premium
            BigDecimal premium = angelOneService.getcurrentPrice(sc, idx.getExchange(), tradingSymbol, idx.getToken(), "ltp");
            if (premium != null) {
                token.setAskPrice(premium);
                token.setEntryPrice(premium);
            }

            OrderMeta meta = buildOrderMeta(currentPrice, first5High, first5Low, upperBand, lowerBand, pivot, marketType, signal);
            token.setSlPrice(meta.getSlPrice());

            // 4. Force Type to SELL for the Risk Engine PnL Math
            token.setType("SELL");
            token.setSignal(signal);

            // 5. Place Order
            orderService.orderPlaceWithToken(token, AppConstant.CPR_STRATEGY, "SELL", false);

            // 6. Update Daily Flags
            if ("BUY".equals(signal)) cprBuyTradeTaken.set(true);
            else cprSellTradeTaken.set(true);

        } catch (Exception | SmartAPIException e) {
            logger.error("❌ Error placing CPR order", e);
        }
    }

    // =========================================================================
    // DIRECTIONAL HELPERS (Aligned with Orders entity: getOptionType() & getSymbol())
    // =========================================================================
    private boolean isBullishTrade(Orders trade) {
        if (trade == null) return false;
        if (trade.getOptionType() != null) {
            if ("PE".equalsIgnoreCase(trade.getOptionType())) return true;
            if ("CE".equalsIgnoreCase(trade.getOptionType())) return false;
        }
        String symbol = trade.getSymbol() != null ? trade.getSymbol().toUpperCase() : "";
        // If symbol ends with PE (Short Put), it is a Bullish trade regardless of whether type="SELL" or "BUY"
        if (symbol.endsWith("PE")) return true;
        if (symbol.endsWith("CE")) return false;
        return "BUY".equalsIgnoreCase(trade.getType()) || "BUY".equalsIgnoreCase(trade.getSignal());
    }

    private boolean isBearishTrade(Orders trade) {
        if (trade == null) return false;
        if (trade.getOptionType() != null) {
            if ("CE".equalsIgnoreCase(trade.getOptionType())) return true;
            if ("PE".equalsIgnoreCase(trade.getOptionType())) return false;
        }
        String symbol = trade.getSymbol() != null ? trade.getSymbol().toUpperCase() : "";
        // If symbol ends with CE (Short Call), it is a Bearish trade regardless of whether type="SELL" or "BUY"
        if (symbol.endsWith("CE")) return true;
        if (symbol.endsWith("PE")) return false;
        return "SELL".equalsIgnoreCase(trade.getType()) || "SELL".equalsIgnoreCase(trade.getSignal());
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
        meta.setEntryTime(LocalDateTime.now(IST_ZONE).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return meta;
    }

    // =========================================================================
    // EXIT CURRENT TRADE (Delegated to OrderService)
    // FIX: returns true ONLY if the exit succeeded. Success = no active CPR order
    //      remains in the DB afterwards. If orderService.exitActiveTrade already
    //      returns a status, combine it with this check.
    // =========================================================================
    private boolean exitCurrentTrade(String reason, BigDecimal indexPrice) {
        try {
            logger.info("⚠️ Initiating trade exit. Reason: {} | Index Price: {}", reason, indexPrice);

            // Fetch the active leg(s)
            Orders activeTrade = orderRepository.findByNameAndActive(AppConstant.CPR_STRATEGY, 1);

            if (activeTrade != null) {
                // Pass it directly to the Monitor Service to process the instant exit and save the reason
                monitorOrderService.forceExit(Collections.singletonList(activeTrade), AppConstant.CPR_STRATEGY, reason);
            }

            Orders stillOpen = orderRepository.findByNameAndActive(AppConstant.CPR_STRATEGY, 1);
            if (stillOpen != null) {
                logger.error("❌ Exit '{}' called but order is still active in DB.", reason);
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.error("❌ Exit '{}' failed", reason, e);
            return false;
        }
    }

    // =========================================================================
    // CPR CALCULATION
    // =========================================================================
    public StrangleCprDto getCPR(SmartConnect smartconnect, Strategy strategy,
                                 StrangleCprDto dto) throws IOException, SmartAPIException {

        LocalDate today              = LocalDate.now(IST_ZONE);
        LocalDate lastWorkingDay     = NSEWorkingDays.getLastWorkingDay(today);
        LocalDate previousWorkingDay = NSEWorkingDays.getLastWorkingDay(lastWorkingDay);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        String fromDate = previousWorkingDay.atTime(9, 15).format(formatter);
        String toDate   = lastWorkingDay.atTime(15, 30).format(formatter);

        JSONObject req = new JSONObject();
        req.put("exchange",    strategy.getExchange());
        req.put("symboltoken", strategy.getToken());
        req.put("interval",    "ONE_DAY");
        req.put("fromdate",    fromDate);
        req.put("todate",      toDate);

        JSONArray candles = straddleMarketDataService.fetchCandleWithRetry(smartconnect, req, strategy.getToken());

        if (candles == null || candles.isEmpty()) return dto;

        try {
            // Pick the latest candle in the returned array (lastWorkingDay)
            Object last = candles.get(candles.length() - 1);
            if (last instanceof JSONArray candle) {
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

        if (dto.getTop_pivot() != null && dto.getBottom_pivot() != null && dto.getPivot() != null
                && dto.getPivot().compareTo(BigDecimal.ZERO) > 0) {
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
        LocalDate today    = LocalDate.now(IST_ZONE);
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
    // SAVE CPR TO DB (Upsert to avoid duplicate rows across days)
    // =========================================================================
    public com.crumbs.trade.entity.CPR saveCPR(StrangleCprDto dto, String name, String date) {
        if (dto == null) return null;

        com.crumbs.trade.entity.CPR cpr = cprRepo.findByName(name);
        if (cpr == null) {
            cpr = new com.crumbs.trade.entity.CPR();
            cpr.setName(name);
        }
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
        Strategy strategy = strategyRepo.findByName("STRANGLE");
        if (strategy == null || !"Y".equalsIgnoreCase(strategy.getActive())) return;

        Orders       order      = orderRepository.findByNameAndActive("NIFTY", 1);
        SmartConnect sc         = angelOne.signIn();
        int          niftyPrice = 0;
        String       signal;

        BigDecimal closePrice = angelOneService.getcurrentPrice(sc, strategy.getExchange(),
                strategy.getTradingsymbol(), strategy.getToken(), "close");
        BigDecimal openPrice  = angelOneService.getcurrentPrice(sc, strategy.getExchange(),
                strategy.getTradingsymbol(), strategy.getToken(), "open");

        if (closePrice == null || openPrice == null) {
            logger.warn("⚠️ Unable to fetch close/open price for STRANGLE.");
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

            if (niftyPrice > strangleMax && strangleMax > 0 && strangleMin > 0) {
                firstOrder = true;
                logger.info("MAX:{} MIN:{} | First BUY @ {}", strangleMax, strangleMin, niftyPrice);
                angelOneService.createStrategy_modified(sc, "NIFTY", 0, "BUY", signal, strangleMin);

            } else if (niftyPrice < strangleMin && strangleMax > 0 && strangleMin > 0) {
                firstOrder = true;
                logger.info("MAX:{} MIN:{} | First SELL @ {}", strangleMax, strangleMin, niftyPrice);
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
                angelOneService.createStrategy_modified(sc, "NIFTY", 0, "SELL", null, strangleMax);

            } else if (tradeType != null && tradeType.equalsIgnoreCase("BUY") && !type.equalsIgnoreCase("BUY")) {
                secondOrder = true;
                angelOneService.createStrategy_modified(sc, "NIFTY", 0, "BUY", null, strangleMin);
            }
        }
    }

    public int getNiftyPrice(String startTime, String endTime, Strategy strategy, int triggerMinute) {
        BigDecimal currentPrice = BigDecimal.ZERO;
        try {
            SmartConnect sc       = angelOne.signIn();
            BigDecimal fetchedLtp = angelOneService.getcurrentPrice(sc, strategy.getExchange(),
                    strategy.getTradingsymbol(), strategy.getToken(), "ltp");
            if (fetchedLtp != null) {
                currentPrice = fetchedLtp;
            }

            LocalTime now         = LocalTime.now(IST_ZONE);
            LocalTime triggerTime = LocalTime.of(9, triggerMinute);
            String    today       = LocalDate.now(IST_ZONE).toString();

            JSONObject req = new JSONObject();
            req.put("exchange",    strategy.getExchange());
            req.put("symboltoken", strategy.getToken());
            req.put("interval",    "FIVE_MINUTE");
            req.put("fromdate",    today + " 09:" + startTime);
            req.put("todate",      today + " 09:" + endTime);

            JSONArray jsonArray = sc.candleData(req);

            if (now.isAfter(triggerTime) && jsonArray != null && !jsonArray.isEmpty() && strangleMax == 0) {
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
        if (closePrice == null || openPrice == null) return false;
        return Math.abs(closePrice.intValue() - openPrice.intValue()) <= 50;
    }

    @Transactional
    public void updateStrategy() {
        logger.info("Both calls have been taken");
    }
}