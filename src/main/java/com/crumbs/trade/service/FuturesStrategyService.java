package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.controller.FuturesStrategyController;
import com.crumbs.trade.dto.FuturesConfigDto;
import com.crumbs.trade.entity.Futures;
import com.crumbs.trade.entity.FuturesConfig;
import com.crumbs.trade.entity.FuturesFilter;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.repo.FuturesConfigRepo;
import com.crumbs.trade.repo.FuturesFilterRepo;
import com.crumbs.trade.repo.FuturesRepo;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.utility.NSEWorkingDays;

import jakarta.transaction.Transactional;

@Service
public class FuturesStrategyService {
	private static final org.apache.logging.log4j.Logger logger = LogManager.getLogger(FuturesStrategyService.class);
    @Autowired
    private FuturesRepo futuresRepo;

    @Autowired
    private FuturesConfigRepo configRepo;

    @Autowired
    private FuturesFilterRepo filterRepo;

    @Autowired
    private IndexesRepo indexesRepo;

    @Autowired
    private PredictionService predictionService;

    @Autowired
    private AngelOne angelOne;
    
    @Autowired
    private TelegramService telegramService;

    private static final String EXCHANGE = "NSE";

    public List<FuturesFilter> execute() {

    	
        FuturesConfig config = configRepo.findActive()
                .orElseThrow(() ->
                        new IllegalStateException("No ACTIVE FUTURES_CONFIG found"));
 
        // ✅ Last completed expiry
        LocalDate expiryDate = resolveExecutionDate(config);

        // 1️⃣ Futures → Indexes
        List<Indexes> indexesList = futuresRepo.findAll().stream()
                .map(f -> indexesRepo.findByNameAndExchange(f.getName(), EXCHANGE))
                .filter(Objects::nonNull)
                .toList();

        if (indexesList.isEmpty()) {
            return Collections.emptyList();
        }

        // 2️⃣ Tokens for TODAY price (bulk)
        List<String> tokens = indexesList.stream()
                .map(Indexes::getToken)
                .filter(Objects::nonNull)
                .toList();

        // ✅ TODAY price (marketData – reused service)
        Map<String, BigDecimal> todayPriceMap =
                fetchTodayPriceUsingPredictionService(tokens);

        List<FuturesFilter> result = new ArrayList<>();

        for (Indexes idx : indexesList) {

            BigDecimal todayPrice = todayPriceMap.get(idx.getToken());
            if (todayPrice == null) continue;

            // ✅ EXPIRY CLOSE (historical candle)
            BigDecimal expiryClose =
                    fetchExpiryClosePrice(idx, expiryDate);

            if (expiryClose == null || expiryClose.compareTo(BigDecimal.ZERO) == 0)
            {
            	logger.error("Expiry Price is empty for {}" , idx.getName());
            	continue;   // ✅ THIS WAS MISSING
            }
              

            BigDecimal percentMove = todayPrice
                    .subtract(expiryClose)
                    .divide(expiryClose, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            if (percentMove.abs()
                    .compareTo(config.getMovementPercent()) < 0) {
                //continue;
            }

            FuturesFilter ff = new FuturesFilter();
            ff.setName(idx.getName());
            ff.setLastExpiryPrice(expiryClose);
            ff.setLastTradedPrice(todayPrice);
            ff.setPercentMove(percentMove);
            ff.setDirection(percentMove.signum() > 0 ? "UP" : "DOWN");

            if (percentMove.compareTo(config.getProfitPercent()) >= 0) {
                ff.setStatus("PROFIT");
            } else if (percentMove.compareTo(
                    config.getLossPercent().negate()) <= 0) {
                ff.setStatus("LOSS");
            } else {
                ff.setStatus("NEUTRAL");
            }

            ff.setLastExpiryDate(expiryDate);
            ff.setLastTradedDate(LocalDate.now());
            result.add(ff);
        }
       // 🔔 Notify before persisting
        sendNotificationIfRequired(config, result);
        //Save to DB
        return filterRepo.saveAll(result);
    }

    private void sendNotificationIfRequired(
            FuturesConfig config,
            List<FuturesFilter> result
    ) {

        if (!"Y".equalsIgnoreCase(config.getNotificationRequired())) {
            return;
        }

        BigDecimal threshold = config.getMovementPercent();
        if (threshold == null || threshold.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        List<FuturesFilter> alertRows = result.stream()
                .filter(Objects::nonNull)
                .filter(f -> f.getPercentMove() != null)
                .filter(f -> f.getPercentMove().abs().compareTo(threshold) <= 0)
                .sorted(Comparator.comparing(FuturesFilter::getPercentMove).reversed())
                .collect(Collectors.toList());


        if (alertRows.isEmpty()) return;

        sendTelegramInBatches(alertRows, threshold);
    }

    private void sendTelegramInBatches(
            List<FuturesFilter> rows,
            BigDecimal threshold
    ) {

        final int TELEGRAM_LIMIT = 3800; // safe buffer

        String header = buildTableHeader(threshold);
        String footer = "-----------------------------------------------------\n```";

        StringBuilder batch = new StringBuilder(header);

        for (FuturesFilter f : rows) {

            String row = buildTableRow(f);

            // ❗ Check size BEFORE adding row
            if (batch.length() + row.length() + footer.length() > TELEGRAM_LIMIT) {

                batch.append(footer);
                telegramService.sendBroadcast(batch.toString());

                // 🔄 Start new batch
                batch = new StringBuilder(header);
            }

            batch.append(row);
        }

        // 🚀 Send last batch
        if (batch.length() > header.length()) {
            batch.append(footer);
            telegramService.sendBroadcast(batch.toString());
        }
    }
    
    private String buildTableRow(FuturesFilter f) {

        return String.format(
            "%-18s | %18.2f | %18.2f%n",
            f.getName(),
            f.getLastExpiryPrice(),
            f.getLastTradedPrice()
        );
    }





    private String fixedWidth(String value, int width) {
        if (value == null) return " ".repeat(width);
        return value.length() > width
                ? value.substring(0, width)
                : String.format("%-" + width + "s", value);
    }

    private String buildTableHeader(BigDecimal threshold) {

        return new StringBuilder()
            .append("📊 *NIFTY 50 – Near Expiry Price* (±")
            .append(threshold)
            .append("%)\n\n")
            .append("```\n")
            .append("--------------------------------------------------------------------------------\n")
            .append(String.format(
                "%-18s | %18s | %18s%n",
                "NAME", "EXPIRY CLOSE", "NOW"
            ))
            .append("--------------------------------------------------------------------------------\n")
            .toString();
    }







    /**
     * TODAY price via PredictionService (bulk marketData)
     */
    private Map<String, BigDecimal> fetchTodayPriceUsingPredictionService(
            List<String> tokens) {

        try {
            SmartConnect smartconnect = angelOne.signIn();

            JSONObject payload =
                    predictionService.buildMarketDataPayload(tokens, EXCHANGE);

            JSONObject response =
                    predictionService.callMarketDataWithRetry(smartconnect, payload);

            Map<String, BigDecimal> priceMap = new HashMap<>();

            if (response.has("fetched")) {
                JSONArray fetched = response.getJSONArray("fetched");

                for (int i = 0; i < fetched.length(); i++) {
                    JSONObject obj = fetched.getJSONObject(i);
                    String token = obj.get("symbolToken").toString();

                    if (obj.has("ltp") && !obj.isNull("ltp")) {
                        priceMap.put(
                                token,
                                new BigDecimal(obj.get("ltp").toString())
                        );
                    }
                }
            }

            return priceMap;

        } catch (Exception | SmartAPIException e) {
            return Map.of();
        }
    }

    /**
     * EXPIRY close price (historical – ONE_DAY candle)
     */
    private BigDecimal fetchExpiryClosePrice(Indexes idx, LocalDate expiryDate) {

        int maxRetries = 3;
        int retryDelayMs = 2000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                SmartConnect smartconnect = angelOne.signIn();

                LocalDate tradingDate =
                        NSEWorkingDays.isNSEWorkingDay(expiryDate)
                            ? expiryDate
                            : NSEWorkingDays.getLastWorkingDay(expiryDate);

                LocalDate fromDate = tradingDate.minusDays(1);

                JSONObject req = new JSONObject();
                req.put("exchange", idx.getExchange());
                req.put("symboltoken", idx.getToken());
                req.put("interval", "ONE_DAY");
                req.put("fromdate", fromDate + " 15:30");
                req.put("todate", tradingDate + " 15:30");

                JSONArray candles = smartconnect.candleData(req);

                if (candles != null && !candles.isEmpty()) {
                    JSONArray lastCandle = candles.getJSONArray(candles.length() - 1);
                    return lastCandle.getBigDecimal(4); // close
                }

                logger.warn(
                    "Empty candle data (attempt {}/{}) for {}",
                    attempt, maxRetries, idx.getName()
                );

            } catch (Exception e) {
                logger.warn(
                    "Error fetching expiry price (attempt {}/{}) for {} : {}",
                    attempt, maxRetries, idx.getName(), e.getMessage()
                );
            }

            // ⏳ Backoff before retry
            try {
                Thread.sleep(retryDelayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        logger.error("Failed to fetch expiry price after retries for {}", idx.getName());
        return null;
    }

    private LocalDate resolveExecutionDate(FuturesConfig config) {

        if ("Y".equalsIgnoreCase(config.getUseNiftyExpiry())) {

            LocalDate today = LocalDate.now();
            LocalDate thisMonthExpiry =
                    today.with(TemporalAdjusters.lastInMonth(DayOfWeek.TUESDAY));

            if (today.isBefore(thisMonthExpiry)) {
                return today.minusMonths(1)
                        .with(TemporalAdjusters.lastInMonth(DayOfWeek.TUESDAY));
            }

            return thisMonthExpiry;
        }

        if (config.getExecutionDate() == null) {
            throw new IllegalStateException(
                    "execution_date must be set when use_nifty_expiry = N");
        }

        return config.getExecutionDate();
    }
    
    // ✅ Fetch config
    public FuturesConfig fetch() {
        FuturesConfig config = configRepo.getConfig();
        if (config == null) {
            throw new IllegalStateException("FUTURES_CONFIG not initialized");
        }
        return config;
    }

    @Transactional
    public FuturesConfig partialUpdate(FuturesConfigDto dto) {

        FuturesConfig config = fetch();

        // ✅ Update only if value is present
        if (dto.getExpiryDate() != null) {
            config.setExecutionDate(dto.getExpiryDate());
        }
        if (dto.getMovementPercent() != null) {
            config.setMovementPercent(dto.getMovementPercent());
        }
        if (dto.getProfitPercent() != null) {
            config.setProfitPercent(dto.getProfitPercent());
        }
        if (dto.getLossPercent() != null) {
            config.setLossPercent(dto.getLossPercent());
        }
        if (dto.getUseNiftyExpiry() != null) {
            config.setUseNiftyExpiry(dto.getUseNiftyExpiry());
        }
        if (dto.getActive() != null) {
            config.setActive(dto.getActive());
        }

        return configRepo.save(config);
    }
}