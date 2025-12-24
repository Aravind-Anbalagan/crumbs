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

        return filterRepo.saveAll(result);
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

        try {
            SmartConnect smartconnect = angelOne.signIn();

            // ✅ ensure valid trading day
            LocalDate tradingDate =
                    NSEWorkingDays.isNSEWorkingDay(expiryDate)
                        ? expiryDate
                        : NSEWorkingDays.getLastWorkingDay(expiryDate);
            Thread.sleep(5000);
            // ✅ 2-day interval (MANDATORY)
            LocalDate fromDate = tradingDate.minusDays(1);

            JSONObject req = new JSONObject();
            req.put("exchange", idx.getExchange());
            req.put("symboltoken", idx.getToken());
            req.put("interval", "ONE_DAY");
            req.put("fromdate", fromDate + " 15:30");
            req.put("todate", tradingDate + " 15:30");

            JSONArray candles = smartconnect.candleData(req);

            if (candles == null || candles.isEmpty()) {
                return null;
            }

            // ✅ ALWAYS take LAST candle
            JSONArray lastCandle = candles.getJSONArray(candles.length() - 1);

            // [time, open, high, low, close, volume]
            return lastCandle.getBigDecimal(4);

        } catch (Exception e) {
            return null;
        }
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
