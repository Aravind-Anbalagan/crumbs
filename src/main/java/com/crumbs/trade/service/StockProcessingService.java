package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.PivotResponse;
import com.crumbs.trade.entity.Indicator;
import com.crumbs.trade.repo.IndicatorRepo;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.transaction.Transactional;

/**
 * Manages the full lifecycle of bullish / bearish stock processing:
 * LTP refresh, first-3-candle check, CPR & pivot signals, result saving, and alerting.
 */
@Service
public class StockProcessingService {

    Logger logger = LoggerFactory.getLogger(StockProcessingService.class);

    private static final int THREAD_POOL_SIZE = 5;
    private final ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

    @Autowired IndicatorRepo indicatorRepo;
    @Autowired AngelOne angelOne;
    @Autowired AiService aiService;
    @Autowired ResultService resultService;
    @Autowired ObjectMapper objectMapper;
    @Autowired TelegramService telegramService;
    @Autowired PriceUtilService priceUtilService;

    // =========================================================
    // Main entry: find and process bullish/bearish stocks
    // =========================================================

   
    public void findBullishStocks() {
        SmartConnect smartConnect = angelOne.signIn();

        List<Indicator> bullishList = indicatorRepo.findByPsarFlagDayInAndHeikinAshiDayIn(
                Arrays.asList("FIRST BUY"), Arrays.asList("FIRST BUY"));
        for (Indicator stock : bullishList) {
            BigDecimal ltp = fetchLtp(smartConnect, stock);
            if (ltp != null) stock.setCurrentPrice(ltp);
        }
        logger.info("Bullish Stock (with LTP): {}", bullishList.size());

        List<Indicator> bearishList = indicatorRepo.findByPsarFlagDayInAndHeikinAshiDayIn(
                Arrays.asList("FIRST SELL"), Arrays.asList("FIRST SELL"));
        for (Indicator stock : bearishList) {
            BigDecimal ltp = fetchLtp(smartConnect, stock);
            if (ltp != null) stock.setCurrentPrice(ltp);
        }
        logger.info("Bearish Stock (with LTP): {}", bearishList.size());

        int total = bullishList.size() + bearishList.size();
        CountDownLatch latch = new CountDownLatch(total);

        for (Indicator stock : bullishList) {
            executor.submit(() -> { try { processStockWithRetry(smartConnect, stock, true); } finally { latch.countDown(); } });
        }
        for (Indicator stock : bearishList) {
            executor.submit(() -> { try { processStockWithRetry(smartConnect, stock, false); } finally { latch.countDown(); } });
        }

        try {
            if (!latch.await(2, TimeUnit.HOURS)) logger.warn("Timeout waiting for stock processing");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        sendMsg();   //Heikin-Psar Signals
        sendMAHierarchyAlert();   // ← new: MA hierarchy BUY/SELL stack stocks
    }

    /**
     * Sends a Telegram alert for all stocks in full bull or bear MA stack.
     * Call this after getSupportAndResistance() completes.
     */
    public void sendMAHierarchyAlert() {
        List<Indicator> buyList  = indicatorRepo.findByMaHierarchyFlag("BUY");
        List<Indicator> sellList = indicatorRepo.findByMaHierarchyFlag("SELL");

        logger.info("MA Hierarchy Alert → BUY: {}, SELL: {}", buyList.size(), sellList.size());

        if (buyList.isEmpty() && sellList.isEmpty()) {
            logger.info("No MA hierarchy stocks to alert");
            return;
        }

        // Build rows: header + BUY section + SELL section
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{ "Symbol", "Sector", "Price", "Signal" });

        buyList.forEach(i -> rows.add(new String[]{
            i.getTradingSymbol(),
            i.getSector()       != null ? i.getSector()                       : "Unknown",
            i.getCurrentPrice() != null ? i.getCurrentPrice().toPlainString() : "0",
            "BUY"
        }));

        sellList.forEach(i -> rows.add(new String[]{
            i.getTradingSymbol(),
            i.getSector()       != null ? i.getSector()                       : "Unknown",
            i.getCurrentPrice() != null ? i.getCurrentPrice().toPlainString() : "0",
            "SELL"
        }));

        try {
            telegramService.sendMAHierarchyAlert(rows, buyList.size(), sellList.size());
        } catch (Exception e) {
            logger.error("Error sending MA hierarchy alert: {}", e.getMessage());
        }
    }
    // =========================================================
    // Per-stock processing
    // =========================================================

    private void processStockWithRetry(SmartConnect smartConnect, Indicator stock, boolean isBullish) {
        int attempts = 0; int maxAttempts = 5; long backoff = 5000;
        while (attempts < maxAttempts) {
            try {
                processStock(smartConnect, stock, isBullish);
                return;
            } catch (Exception e) {
                attempts++;
                if ((isRateLimitError(e) || isTransientSmartApiError(e)) && attempts < maxAttempts) {
                    logger.warn("Rate/transient error on {}. Retry {}/{} after {}ms: {}", stock.getName(), attempts, maxAttempts, backoff, e.getMessage());
                    sleep(backoff); backoff *= 2;
                } else {
                    logger.error("Permanent error for {}: {}", stock.getName(), e.getMessage(), e); return;
                }
            }
        }
        logger.error("Failed to process {} after {} attempts", stock.getName(), maxAttempts);
    }

    private void processStock(SmartConnect smartConnect, Indicator stock, boolean isBullish) throws Exception {
        LocalDateTime now = priceUtilService.getCurrentDate();
        BigDecimal currentPrice = getPriceWithRetry(smartConnect, stock);
        stock.setCurrentPrice(currentPrice); // ✅ set BEFORE calling get3DaysHighAndLow
        String first3 = getFirst3FiveMinsCandleWithRetry(smartConnect, stock);
        String prevFlag = setPrevdayclosepriceflag(stock, currentPrice);
        String candleFlag = get3DaysHighAndLow(stock); // now reads correct price
        String cprFlag = getCprFlag(stock);
        String pivotFlag = calculateSignal(currentPrice, stock.getPivot());

        // Only update the fields that actually changed — small targeted query
        indicatorRepo.updateStockProcessingFields(
            stock.getId(), currentPrice, now, first3,
            prevFlag, candleFlag, cprFlag, pivotFlag, stock.getOpenPrice()
        );

        // Update in-memory for the signal check below
        stock.setCurrentPrice(currentPrice);
        stock.setPrevdayclosepriceflag(prevFlag);
        stock.setFirst3FiveMinsCandle(first3);
        stock.setCprflag(cprFlag);

        if ((isBullish && isUpSignal(stock)) || (!isBullish && isDownSignal(stock))) {
            indicatorRepo.updateIntradayFields(stock.getId(), 
                isBullish ? "UP" : "DOWN", "DAILY", now);
            resultService.saveNiftyResult(stock);
        }
    }

    // =========================================================
    // Price fetch with retry
    // =========================================================

    public BigDecimal getPriceWithRetry(SmartConnect smartConnect, Indicator stock) throws Exception {
        int attempts = 0; int maxAttempts = 5; long backoff = 2000;
        while (attempts < maxAttempts) {
            try {
                JSONObject jsonObject = smartConnect.getLTP(stock.getExchange(), stock.getTradingSymbol(), stock.getToken());
                if (jsonObject == null) throw new IllegalStateException("Null JSON from Smart API for " + stock.getName());
                Object ltpObj = jsonObject.opt("ltp");
                if (ltpObj == null) throw new IllegalStateException("'ltp' missing for " + stock.getName());
                return new BigDecimal(String.valueOf(ltpObj));
            } catch (Exception e) {
                attempts++;
                if ((isRateLimitError(e) || isTransientSmartApiError(e)) && attempts < maxAttempts) {
                    logger.warn("Retry {}/{} for {} after {}ms: {}", attempts, maxAttempts, stock.getName(), backoff, e.getMessage());
                    sleep(backoff); backoff *= 2;
                } else { logger.error("Failed to get price for {} after {} attempts", stock.getName(), attempts, e); throw e; }
            }
        }
        throw new RuntimeException("Failed to get price for " + stock.getName());
    }

    // =========================================================
    // First 3 five-min candle
    // =========================================================

    private String getFirst3FiveMinsCandleWithRetry(SmartConnect smartConnect, Indicator stock) throws Exception {
        int attempts = 0; int maxAttempts = 10; long backoff = 5000;
        while (attempts < maxAttempts) {
            try {
                return getFirst3FiveMinsCandleWithSession(smartConnect, stock);
            } catch (Exception e) {
                attempts++;
                if ((isRateLimitError(e) || isTransientSmartApiError(e)) && attempts < maxAttempts) {
                    logger.warn("Retry {}/{} getFirst3FiveMins for {} after {}ms: {}", attempts, maxAttempts, stock.getName(), backoff, e.getMessage());
                    sleep(backoff); backoff *= 2;
                } else { throw e; }
            }
        }
        throw new RuntimeException("Failed to get first 3 five mins candle for " + stock.getName());
    }

    public String getFirst3FiveMinsCandleWithSession(SmartConnect smartConnect, Indicator stock) {
        try {
            BigDecimal currentPrice = getPriceWithRetry(smartConnect, stock);
            String format = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            JSONObject requestObject = new JSONObject();
            requestObject.put("exchange", stock.getExchange()); requestObject.put("symboltoken", stock.getToken());
            requestObject.put("interval", "FIVE_MINUTE");
            requestObject.put("fromdate", format + " 09:15"); requestObject.put("todate", format + " 09:25");
            JSONArray jsonArray = smartConnect.candleData(requestObject);

            if (jsonArray != null && !jsonArray.isEmpty()) {
                List<BigDecimal> MAX_List = new ArrayList<>(), MIN_List = new ArrayList<>();
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONArray inner = (JSONArray) jsonArray.get(i);
                    MAX_List.add(inner.getBigDecimal(2));
                    MIN_List.add(inner.getBigDecimal(3));
                    if (i == 0) stock.setOpenPrice(inner.getBigDecimal(1));
                }
                Collections.sort(MAX_List, Collections.reverseOrder());
                Collections.sort(MIN_List);
                if      (currentPrice.compareTo(MAX_List.get(0)) > 0) return "UP";
                else if (currentPrice.compareTo(MIN_List.get(0)) < 0) return "DOWN";
            }
        } catch (Exception e) {
            logger.error("{} Error in getFirst3FiveMinsCandleWithSession: {}", stock.getName(), e.getMessage());
        }
        return null;
    }

    // =========================================================
    // Result computation
    // =========================================================

    @Transactional
    public void getResult() {
        List<Indicator> indicatorList = indicatorRepo.findByIntradayIsNotNullAndTradetypeIn(Arrays.asList("DAILY", "HOURLY"));
        logger.info("Getting result count: {}", indicatorList.size());
        SmartConnect smartConnect = angelOne.signIn();
        indicatorList.forEach(stock -> {
            BigDecimal ltp = priceUtilService.getPrice(smartConnect, stock, "ltp");
            String result = null;
            if ("UP".equalsIgnoreCase(stock.getIntraday())) {
                result = ltp.compareTo(stock.getExecutedPrice()) > 0 ? "SUCCESS" : "FAIL";
            } else if ("DOWN".equalsIgnoreCase(stock.getIntraday())) {
                result = ltp.compareTo(stock.getExecutedPrice()) < 0 ? "SUCCESS" : "FAIL";
            }
            stock.setResult(result);
            resultService.saveNiftyResult(stock);
            indicatorRepo.updateResult(stock.getId(), result); // ✅ targeted update
        });
    }

    // =========================================================
    // AI analysis
    // =========================================================

    public void callAI() {
        List<Indicator> indicatorList = indicatorRepo.findByIntradayIsNotNullOrderByIntradayAsc();
        CountDownLatch latch = new CountDownLatch(indicatorList.size());
        for (Indicator stock : indicatorList) {
            executor.submit(() -> { try { aiService.analyzeStockCombined(stock); } finally { latch.countDown(); } });
        }
        try { latch.await(2, TimeUnit.HOURS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // =========================================================
    // Other indicator helpers
    // =========================================================

    public List<Indicator> addOtherIndicator(String input) {
        return indicatorRepo.findByLast3daycandleflag(input);
    }

    // =========================================================
    // Alert / reporting
    // =========================================================

    public void sendMsg() {
        List<String[]> rows = getEmailData();
        try {
            telegramService.sendStockAlert(rows);
        } catch (Exception e) {
            logger.error("Error sending alert: {}", e.getMessage());
        }
    }

    public List<String[]> getEmailData() {
        List<Indicator> stockList = indicatorRepo.findByIntradayIsNotNullAndTradetypeIn(Arrays.asList("DAILY"));
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{ "Stock Name", "Price", "Option", "Signal", "Type" });
        if (stockList != null) {
            stockList.forEach(stock -> rows.add(new String[]{
                stock.getName(), stock.getCurrentPrice().toString(),
                stock.getOptions(), stock.getIntraday(), stock.getTradetype()
            }));
        }
        return rows;
    }

    // =========================================================
    // LTP helper
    // =========================================================

    public BigDecimal fetchLtp(SmartConnect smartConnect, Indicator stock) {
        try {
            JSONObject response = smartConnect.getLTP(stock.getExchange(), stock.getTradingSymbol(), stock.getToken());
            return BigDecimal.valueOf(response.getDouble("ltp"));
        } catch (Exception e) {
            logger.error("Failed to fetch LTP for {}", stock.getTradingSymbol(), e);
            return null;
        }
    }

    // =========================================================
    // Signal / flag helpers
    // =========================================================

    public String setPrevdayclosepriceflag(Indicator stock, BigDecimal currentPrice) {
        if (currentPrice.compareTo(stock.getPrevdaycloseprice()) > 0) return "UP";
        if (currentPrice.compareTo(stock.getPrevdaycloseprice()) < 0) return "DOWN";
        return null;
    }

    public String get3DaysHighAndLow(Indicator stock) {
        String result = null;
        List<Integer> highs = Arrays.stream(stock.getLast3daycandlehigh().replaceAll("\\[|\\]", "").split(","))
                .map(String::trim).map(Integer::parseInt).collect(Collectors.toList());
        List<Integer> lows = Arrays.stream(stock.getLast3daycandlelow().replaceAll("\\[|\\]", "").split(","))
                .map(String::trim).map(Integer::parseInt).collect(Collectors.toList());
        if (stock.getCurrentPrice().compareTo(new BigDecimal(highs.get(0))) > 0) result = "UP";
        if (stock.getCurrentPrice().compareTo(new BigDecimal(lows.get(0)))  < 0) result = "DOWN";
        return result;
    }

    public String getCprFlag(Indicator stock) {
        String cpr = stock.getCpr();
        if (cpr == null) return null;
        List<BigDecimal> values = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\d+\\.\\d+").matcher(cpr);
        while (matcher.find()) values.add(new BigDecimal(matcher.group()));
        if (values.size() < 3) { logger.error("CPR parse error for {}: {}", stock.getName(), cpr); return null; }
        int cmp = stock.getCurrentPrice().compareTo(values.get(0));
        if (cmp > 0) return "UP";
        if (cmp < 0) return "DOWN";
        return "EQUAL";
    }

    private String calculateSignal(BigDecimal currentPrice, String pivotString)
            throws JsonMappingException, JsonProcessingException {
        if (pivotString == null) return null;
        PivotResponse pivot = objectMapper.readValue(pivotString, PivotResponse.class);
        BigDecimal tc = pivot.getTc(), bc = pivot.getBc(), r1 = pivot.getR1(), s1 = pivot.getS1();
        if (tc == null || bc == null || r1 == null || s1 == null) return "INVALID - Missing CPR/Pivot Data";
        if      (currentPrice.compareTo(tc) > 0 && currentPrice.compareTo(r1) < 0) return "BUY - Above CPR";
        else if (currentPrice.compareTo(r1) >= 0)                                  return "STRONG BUY - Above R1";
        else if (currentPrice.compareTo(bc) < 0 && currentPrice.compareTo(s1) > 0) return "SELL - Below CPR";
        else if (currentPrice.compareTo(s1) <= 0)                                  return "STRONG SELL - Below S1";
        else                                                                        return "HOLD - Inside CPR";
    }

    private boolean isUpSignal(Indicator stock) {
        return (
            "UP".equalsIgnoreCase(stock.getPrevdayclosepriceflag())
            || "UP".equalsIgnoreCase(stock.getFirst3FiveMinsCandle())
            || "UP".equalsIgnoreCase(stock.getCprflag())
        ) && "ABOVE".equalsIgnoreCase(stock.getLastExpiryLevel());
    }

    private boolean isDownSignal(Indicator stock) {
        return (
            "DOWN".equalsIgnoreCase(stock.getPrevdayclosepriceflag())
            || "DOWN".equalsIgnoreCase(stock.getFirst3FiveMinsCandle())
            || "DOWN".equalsIgnoreCase(stock.getCprflag())
        ) && "BELOW".equalsIgnoreCase(stock.getLastExpiryLevel());
    }


    // =========================================================
    // Error classification + sleep
    // =========================================================

    private boolean isRateLimitError(Exception e) {
        if (e == null) return false;
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains("rate limit");
    }

    private boolean isTransientSmartApiError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return msg.contains("null json") || msg.contains("missing 'data'")
            || msg.contains("timeout") || msg.contains("temporarily unavailable");
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    
    /**
     * Returns a grouped summary of all stocks bucketed by their MA hierarchy signal.
     * Safe to call after getSupportAndResistance() — maHierarchyFlag is already persisted.
     *
     * Result map keys : "BUY", "SELL", "NEUTRAL"
     * Each row        : [tradingSymbol, sector, currentPrice, maHierarchyFlag]
     */
    public Map<String, List<String[]>> getMAHierarchyReport() {

        Map<String, List<String[]>> report = new LinkedHashMap<>();
        report.put("BUY",     new ArrayList<>());
        report.put("SELL",    new ArrayList<>());
        report.put("NEUTRAL", new ArrayList<>());

        indicatorRepo.findByMaHierarchyFlag("BUY")    .forEach(i -> report.get("BUY")    .add(toMARow(i)));
        indicatorRepo.findByMaHierarchyFlag("SELL")   .forEach(i -> report.get("SELL")   .add(toMARow(i)));
        indicatorRepo.findByMaHierarchyFlag("NEUTRAL").forEach(i -> report.get("NEUTRAL").add(toMARow(i)));

        logger.info("MA Hierarchy Report → BUY: {}, SELL: {}, NEUTRAL: {}",
                report.get("BUY").size(),
                report.get("SELL").size(),
                report.get("NEUTRAL").size());

        return report;
    }

    /**
     * Highest-conviction LONG candidates.
     * MA hierarchy BUY  +  Heikin-Ashi BUY  +  PSAR BUY — all three aligned.
     */
    public List<Indicator> getTripleConfirmedBuys() {
        List<Indicator> result = indicatorRepo
                .findByMaHierarchyFlagAndHeikinAshiDayAndPsarFlagDay("BUY", "BUY", "BUY");
        logger.info("Triple-confirmed BUY stocks: {}", result.size());
        return result;
    }

    /**
     * Highest-conviction SHORT candidates.
     * MA hierarchy SELL  +  Heikin-Ashi SELL  +  PSAR SELL — all three aligned.
     */
    public List<Indicator> getTripleConfirmedSells() {
        List<Indicator> result = indicatorRepo
                .findByMaHierarchyFlagAndHeikinAshiDayAndPsarFlagDay("SELL", "SELL", "SELL");
        logger.info("Triple-confirmed SELL stocks: {}", result.size());
        return result;
    }

    /**
     * F&O-eligible BUY stocks in full bull stack — ready for options trading.
     */
    public List<Indicator> getFnOBullStackStocks() {
        return indicatorRepo.findByMaHierarchyFlagAndOptions("BUY", "Y");
    }

    // ── private helper ────────────────────────────────────────────────────────

    private String[] toMARow(Indicator i) {
        return new String[]{
            i.getTradingSymbol(),
            i.getSector()       != null ? i.getSector()                    : "Unknown",
            i.getCurrentPrice() != null ? i.getCurrentPrice().toPlainString() : "0",
            i.getMaHierarchyFlag()
        };
    }
}