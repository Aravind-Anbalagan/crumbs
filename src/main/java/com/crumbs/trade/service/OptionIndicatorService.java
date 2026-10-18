package com.crumbs.trade.service;

import com.angelbroking.smartapi.SmartConnect;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.ScannedContractDto;
import com.crumbs.trade.entity.StrategyConfig;
import com.crumbs.trade.utility.MaCalculation;
import com.crumbs.trade.utility.NSEWorkingDays;
import com.crumbs.trade.utility.RsiCalculation;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@RequiredArgsConstructor
public class OptionIndicatorService {

    private static final Logger logger = LogManager.getLogger(OptionIndicatorService.class);

    private static final int RSI_PERIOD = 14;
    private static final int MINIMUM_RSI_WARMUP = 28;
    private static final int RATE_LIMIT_SLEEP_MS = 6000;
    private static final DateTimeFormatter ANGEL_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final long MIN_API_DELAY_MS = 500;
    private static final int MA_PERIOD = 20;
    private long lastApiCallTime = 0;
    private final AngelOne angelOne;
    private final StrategyConfigService configService;

    public List<ScannedContractDto> evaluateIndicatorsForContracts(List<ScannedContractDto> contracts) {
        return evaluateIndicatorsForContracts(contracts, "ONE_HOUR");
    }

    public List<ScannedContractDto> evaluateIndicatorsForContracts(List<ScannedContractDto> contracts, String interval) {
        if (contracts == null || contracts.isEmpty()) return contracts;

        SmartConnect smartConnect = angelOne.signIn();
        if (smartConnect == null) return contracts;

        StrategyConfig config = configService.getActiveConfig();
        int dynamicMaPeriod = config.getMaPeriod();
        int dynamicRsiPeriod = config.getRsiPeriod();

        String normalizedInterval = interval != null ? interval.trim().toUpperCase() : config.getDefaultInterval();

        ExecutorService executor = Executors.newFixedThreadPool(5);

        try {
            List<CompletableFuture<Void>> futures = contracts.stream().map(dto ->
                    CompletableFuture.runAsync(() -> {
                        try {
                            dto.setTimeFrame(normalizedInterval);
                            LocalDateTime[] window = resolveMarketWindow(normalizedInterval, dto.getExchange());
                            List<Double> closes = fetchHistoricalClosePrices(smartConnect, dto, window, normalizedInterval);

                            if (closes == null || closes.isEmpty()) {
                                logger.warn("⚠️ No candles for {}: RSI calculation skipped", dto.getSymbol());
                                return;
                            }

                            if (closes.size() < MINIMUM_RSI_WARMUP) {
                                logger.warn("⚠️ Insufficient candles for {} RSI (have {}, need {}). Skipping.",
                                        dto.getSymbol(), closes.size(), MINIMUM_RSI_WARMUP);
                                return;
                            }

                            Double latestClose = closes.get(closes.size() - 1);
                            dto.setCurrentLtp(BigDecimal.valueOf(latestClose));

                            if (closes.size() >= dynamicMaPeriod) {
                                Double currentMa = MaCalculation.calculateSMA(closes, dynamicMaPeriod);
                                if (currentMa != null) {
                                    dto.setCurrentMa(currentMa);
                                    dto.setPriceAboveMa(latestClose > currentMa);
                                }
                            }

                            if (closes.size() >= dynamicRsiPeriod + 1) {
                                Double currentRsi = RsiCalculation.calculate(closes, dynamicRsiPeriod);
                                if (currentRsi != null) {
                                    logger.info(
                                            "📊 RSI[{}] sym={} closes_count={} rsi={:.2f} ltp={:.2f} ma={:.2f}",
                                            normalizedInterval,
                                            dto.getSymbol(),
                                            closes.size(),
                                            currentRsi,
                                            latestClose,
                                            dto.getCurrentMa() != null ? dto.getCurrentMa() : 0
                                    );
                                    updateRSIState(dto, currentRsi, config);
                                } else {
                                    logger.warn("⚠️ RSI calculation returned null for {} (algorithm issue?)",
                                            dto.getSymbol());
                                }
                            }
                        } catch (Exception e) {
                            logger.error("🛑 Error processing indicators for {}: {}", dto.getSymbol(), e.getMessage(), e);
                        }
                    }, executor)
            ).toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.shutdown();
        }
        return contracts;
    }

    private void updateRSIState(ScannedContractDto dto, double currentRsi, StrategyConfig config) {
        LocalDateTime now = LocalDateTime.now();

        double overboughtLevel = config.getRsiOverbought() != null ? config.getRsiOverbought() : 80.0;
        double oversoldLevel = config.getRsiOversold() != null ? config.getRsiOversold() : 20.0;

        dto.setPreviousRsi(dto.getCurrentRsi());
        dto.setCurrentRsi(currentRsi);
        dto.setLastEvaluatedAt(now);

        // ==========================================
        // OVERBOUGHT LOGIC (e.g., >= 80)
        // ==========================================
        if (currentRsi >= overboughtLevel) {
            if (!dto.isRSIAbove80()) {
                dto.setRSIAbove80(true);
                dto.setAboveRSI80At(now);
                dto.setExtremePeakRsi(currentRsi);
            }
            dto.setAboveRSI80Count(dto.getAboveRSI80Count() + 1);
            dto.setExtremePeakRsi(Math.max(dto.getExtremePeakRsi() == null ? 0 : dto.getExtremePeakRsi(), currentRsi));
            dto.setRsiAction(ScannedContractDto.RsiAction.TRACKING_OVERBOUGHT);
        }
        else if (dto.isRSIAbove80() && currentRsi < overboughtLevel) {
            dto.setRsiAction(ScannedContractDto.RsiAction.TRIGGER_OVERBOUGHT_HOOK);
            logger.info("📉 HOOK DOWN TRIGGERED for {}: RSI dropped from overbought to {}", dto.getSymbol(), currentRsi);
            dto.setRSIAbove80(false);
        }
        else {
            resetOverboughtState(dto);
        }

        // ==========================================
        // OVERSOLD LOGIC (e.g., <= 20)
        // ==========================================
        if (currentRsi <= oversoldLevel) {
            if (!dto.isRSIBelow20()) {
                dto.setRSIBelow20(true);
                dto.setBelowRSI20At(now);
                dto.setExtremeTroughRsi(currentRsi);
            }
            dto.setBelowRSI20Count(dto.getBelowRSI20Count() + 1);
            dto.setExtremeTroughRsi(Math.min(dto.getExtremeTroughRsi() == null ? 100 : dto.getExtremeTroughRsi(), currentRsi));
            dto.setRsiAction(ScannedContractDto.RsiAction.TRACKING_OVERSOLD);
        }
        else if (dto.isRSIBelow20() && currentRsi > oversoldLevel) {
            dto.setRsiAction(ScannedContractDto.RsiAction.TRIGGER_OVERSOLD_HOOK);
            logger.info("📈 HOOK UP TRIGGERED for {}: RSI popped from oversold to {}", dto.getSymbol(), currentRsi);
            dto.setRSIBelow20(false);
        }
        else {
            resetOversoldState(dto);
        }

        // Neutral state
        if (currentRsi > oversoldLevel && currentRsi < overboughtLevel
                && dto.getRsiAction() != ScannedContractDto.RsiAction.TRIGGER_OVERBOUGHT_HOOK
                && dto.getRsiAction() != ScannedContractDto.RsiAction.TRIGGER_OVERSOLD_HOOK) {
            dto.setRsiAction(ScannedContractDto.RsiAction.NONE);
        }
    }

    private void resetOverboughtState(ScannedContractDto dto) {
        dto.setRSIAbove80(false);
        dto.setAboveRSI80Count(0);
        dto.setAboveRSI80At(null);
        dto.setExtremePeakRsi(null);

    }

    private void resetOversoldState(ScannedContractDto dto) {
        dto.setRSIBelow20(false);
        dto.setBelowRSI20Count(0);
        dto.setBelowRSI20At(null);
        dto.setExtremeTroughRsi(null);

    }

    private synchronized void throttleApi() {
        long timeSinceLastCall = System.currentTimeMillis() - lastApiCallTime;
        if (timeSinceLastCall < MIN_API_DELAY_MS) {
            sleepQuietly(MIN_API_DELAY_MS - timeSinceLastCall);
        }
        lastApiCallTime = System.currentTimeMillis();
    }

    private List<Double> fetchHistoricalClosePrices(SmartConnect smartConnect, ScannedContractDto dto, LocalDateTime[] window, String interval) {
        int maxRetries = 5;
        long delay = 2000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 1) {
                    logger.warn("⏳ Retrying fetch for {} (Attempt {}/{})... Sleeping {}ms",
                            dto.getSymbol(), attempt, maxRetries, delay);
                    sleepQuietly(delay);
                    delay *= 2;
                }

                JSONObject req = new JSONObject();
                req.put("exchange", dto.getExchange());
                req.put("symboltoken", dto.getToken());
                req.put("interval", interval);
                req.put("fromdate", window[0].format(ANGEL_DATE_FMT));
                req.put("todate", window[1].format(ANGEL_DATE_FMT));

                throttleApi();
                JSONArray candles = smartConnect.candleData(req);

                if (candles == null || candles.isEmpty()) {
                    logger.debug("⚠️ API returned empty candles for {} (attempt {}/{}). Token: {}",
                            dto.getSymbol(), attempt, maxRetries, dto.getToken());
                    continue;
                }

                List<Double> closePrices = new ArrayList<>();
                int validCount = 0;
                int invalidCount = 0;

                for (int i = 0; i < candles.length(); i++) {
                    try {
                        JSONArray c = candles.getJSONArray(i);
                        double close = c.getDouble(4);

                        if (close > 0) {
                            closePrices.add(close);
                            validCount++;
                        } else {
                            logger.debug("  ⚠️ Invalid close price {} for candle {}", close, i);
                            invalidCount++;
                        }
                    } catch (Exception e) {
                        logger.debug("  ⚠️ Failed to parse candle {}: {}", i, e.getMessage());
                        invalidCount++;
                    }
                }

                if (!closePrices.isEmpty()) {
                    logger.debug("📊 Candles for {} | Valid: {} | Invalid: {} | Total: {}",
                            dto.getSymbol(), validCount, invalidCount, closePrices.size());
                    return closePrices;
                } else {
                    logger.debug("⚠️ No valid close prices extracted for {} from {} candles",
                            dto.getSymbol(), candles.length());
                }

            } catch (Exception e) {
                boolean isRateLimit = e.getMessage() != null &&
                        (e.getMessage().contains("503") || e.getMessage().contains("Too Many Requests"));

                if (isRateLimit) {
                    logger.warn("🚦 Rate Limit hit for {} (attempt {}/{}). Backing off for {}ms...",
                            dto.getSymbol(), attempt, maxRetries, RATE_LIMIT_SLEEP_MS);
                    sleepQuietly(RATE_LIMIT_SLEEP_MS);
                } else {
                    logger.debug("⚠️ API fetch issue for {} (attempt {}/{}): {}",
                            dto.getSymbol(), attempt, maxRetries, e.getMessage());
                }
            }
        }

        logger.warn("❌ Exhausted {} API attempts for {}. No historical data returned.",
                maxRetries, dto.getSymbol());
        return null;
    }

    private LocalDateTime[] resolveMarketWindow(String interval, String exchange) {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        LocalDate today = LocalDate.now(ist);
        LocalTime now = LocalTime.now(ist);

        boolean isMcx = "MCX".equalsIgnoreCase(exchange);
        LocalTime marketOpen = isMcx ? LocalTime.of(9, 0) : LocalTime.of(9, 15);
        LocalTime marketClose = isMcx ? LocalTime.of(23, 30) : LocalTime.of(15, 30);

        LocalDate currentTradingDay = NSEWorkingDays.isNSEWorkingDay(today) ? today : NSEWorkingDays.getLastWorkingDay(today);

        int calendarDaysBack = switch (interval) {
            case "ONE_MINUTE", "THREE_MINUTE", "FIVE_MINUTE" -> 10;
            case "FIFTEEN_MINUTE", "THIRTY_MINUTE" -> 30;
            case "ONE_HOUR" -> 90;
            case "ONE_DAY" -> 365;
            default -> 60;
        };

        LocalDate prevDay = currentTradingDay.minusDays(calendarDaysBack);
        LocalDate previousTradingDay = NSEWorkingDays.isNSEWorkingDay(prevDay) ? prevDay : NSEWorkingDays.getLastWorkingDay(prevDay);

        LocalDateTime from = LocalDateTime.of(previousTradingDay, marketOpen);

        LocalDateTime to;
        if (currentTradingDay.isEqual(today) && now.isBefore(marketClose)) {
            LocalTime alignedTime = alignToLastCompleteCandle(now, interval);
            to = LocalDateTime.of(today, alignedTime);
        } else {
            to = LocalDateTime.of(currentTradingDay, marketClose);
        }

        logger.debug("🕐 Market window for {} {} | From: {} | To: {} | Days: {}",
                exchange, interval, from, to, calendarDaysBack);

        return new LocalDateTime[]{from, to};
    }

    private LocalTime alignToLastCompleteCandle(LocalTime currentTime, String interval) {
        return switch (interval) {
            case "ONE_MINUTE" -> currentTime.withSecond(0).minusMinutes(1);
            case "THREE_MINUTE" -> {
                int mins = (currentTime.getMinute() / 3) * 3;
                yield currentTime.withMinute(mins).withSecond(0).minusMinutes(3);
            }
            case "FIVE_MINUTE" -> {
                int mins = (currentTime.getMinute() / 5) * 5;
                yield currentTime.withMinute(mins).withSecond(0).minusMinutes(5);
            }
            case "FIFTEEN_MINUTE" -> {
                int mins = (currentTime.getMinute() / 15) * 15;
                yield currentTime.withMinute(mins).withSecond(0).minusMinutes(15);
            }
            case "THIRTY_MINUTE" -> {
                int mins = (currentTime.getMinute() / 30) * 30;
                yield currentTime.withMinute(mins).withSecond(0).minusMinutes(30);
            }
            case "ONE_HOUR" -> currentTime.withMinute(0).withSecond(0).minusHours(1);
            case "ONE_DAY" -> LocalTime.of(9, 15);
            default -> currentTime;
        };
    }

    private void sleepQuietly(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}