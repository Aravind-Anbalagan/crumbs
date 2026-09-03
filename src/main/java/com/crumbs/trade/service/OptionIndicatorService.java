package com.crumbs.trade.service;

import com.angelbroking.smartapi.SmartConnect;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.ScannedContractDto;
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
    private static final int RATE_LIMIT_SLEEP_MS = 6000;
    private static final DateTimeFormatter ANGEL_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // Standard RSI thresholds
    private static final double OVERBOUGHT_LEVEL = 80.0;
    private static final double OVERSOLD_LEVEL = 20.0;
    // Angel One allows ~3 requests per second. 350ms ensures we stay safely under the limit.
    private static final long MIN_API_DELAY_MS = 500;
    private long lastApiCallTime = 0;
    private final AngelOne angelOne;

    /**
     * Backward-compatible overload defaulting to ONE_HOUR.
     */
    public List<ScannedContractDto> evaluateIndicatorsForContracts(List<ScannedContractDto> contracts) {
        return evaluateIndicatorsForContracts(contracts, "ONE_HOUR");
    }

    /**
     * Orchestrates fetching generic candles and applying RSI calculation for any timeframe.
     * Uses multi-threading to fetch candles rapidly while respecting rate limits.
     *
     * @param contracts List of recently scanned contracts
     * @param interval  Angel One interval (e.g., ONE_MINUTE, FIVE_MINUTE, FIFTEEN_MINUTE, ONE_HOUR, ONE_DAY)
     * @return Updated list of contracts
     */
    public List<ScannedContractDto> evaluateIndicatorsForContracts(List<ScannedContractDto> contracts, String interval) {
        if (contracts == null || contracts.isEmpty()) return contracts;

        SmartConnect smartConnect = angelOne.signIn();
        if (smartConnect == null) {
            logger.error("❌ Failed to sign in to Angel One. Aborting indicator evaluation.");
            return contracts;
        }

        String normalizedInterval = interval != null ? interval.trim().toUpperCase() : "ONE_HOUR";

        ExecutorService executor = Executors.newFixedThreadPool(5);

        try {
            List<CompletableFuture<Void>> futures = contracts.stream().map(dto ->
                    CompletableFuture.runAsync(() -> {
                        try {
                            dto.setTimeFrame(normalizedInterval);

                            // ✅ ADD IT HERE INSIDE THE LOOP:
                            // Calculate the window dynamically based on THIS contract's exchange
                            LocalDateTime[] window = resolveMarketWindow(normalizedInterval, dto.getExchange());

                            // 2. Fetch Candle Close Prices
                            List<Double> closes = fetchHistoricalClosePrices(smartConnect, dto, window, normalizedInterval);

                            if (closes != null && !closes.isEmpty()) {
                                // 👈 Set the latest candle's close as the option's actual LTP
                                Double latestClose = closes.get(closes.size() - 1);
                                dto.setCurrentLtp(BigDecimal.valueOf(latestClose));

                                if (closes.size() >= RSI_PERIOD + 1) {
                                    Double currentRsi = RsiCalculation.calculate(closes, RSI_PERIOD);
                                    if (currentRsi != null) {
                                        updateRSIState(dto, currentRsi);
                                    }
                                } else {
                                    logger.debug("📉 Insufficient data for {}: Got {} candles, but need {}. Skipping RSI.",
                                            dto.getSymbol(), closes.size(), RSI_PERIOD + 1);
                                }
                            }
                        } catch (Exception e) {
                            logger.error("🛑 Error processing indicators for {}: {}", dto.getSymbol(), e.getMessage());
                        }
                    }, executor)
            ).toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        } finally {
            executor.shutdown();
        }

        return contracts;
    }

    /**
     * Tracks extreme thresholds and triggers Reversal Hooks.
     */
    private void updateRSIState(ScannedContractDto dto, double currentRsi) {
        LocalDateTime now = LocalDateTime.now();

        // Push current to previous for cycle comparison
        dto.setPreviousRsi(dto.getCurrentRsi());
        dto.setCurrentRsi(currentRsi);
        dto.setLastEvaluatedAt(now);

        // ==========================================
        // OVERBOUGHT LOGIC (>= 70)
        // ==========================================
        if (currentRsi >= OVERBOUGHT_LEVEL) {
            if (!dto.isRSIAbove80()) {
                dto.setRSIAbove80(true);
                dto.setAboveRSI80At(now);
                dto.setExtremePeakRsi(currentRsi);
            }
            dto.setAboveRSI80Count(dto.getAboveRSI80Count() + 1);
            dto.setExtremePeakRsi(Math.max(dto.getExtremePeakRsi() == null ? 0 : dto.getExtremePeakRsi(), currentRsi));
            dto.setSignalAction(ScannedContractDto.SignalAction.TRACKING_OVERBOUGHT);
        }
        // Hook Down: Was overbought, now crossed below
        else if (dto.isRSIAbove80() && currentRsi < OVERBOUGHT_LEVEL) {
            dto.setSignalAction(ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK);
            logger.info("📉 HOOK DOWN TRIGGERED for {}: RSI dropped from overbought to {}", dto.getSymbol(), currentRsi);

            // 👇 FIX: Only turn off the flag to prevent double-alerts, but KEEP the count for the DB!
            dto.setRSIAbove80(false);
        }
        else {
            // Neutral territory: Now it is safe to completely reset the count
            resetOverboughtState(dto);
        }

        // ==========================================
        // OVERSOLD LOGIC (<= 20)
        // ==========================================
        if (currentRsi <= OVERSOLD_LEVEL) {
            if (!dto.isRSIBelow20()) {
                dto.setRSIBelow20(true);
                dto.setBelowRSI20At(now);
                dto.setExtremeTroughRsi(currentRsi);
            }
            dto.setBelowRSI20Count(dto.getBelowRSI20Count() + 1);
            dto.setExtremeTroughRsi(Math.min(dto.getExtremeTroughRsi() == null ? 100 : dto.getExtremeTroughRsi(), currentRsi));
            dto.setSignalAction(ScannedContractDto.SignalAction.TRACKING_OVERSOLD);
        }
        // Hook Up: Was oversold, now crossed above
        else if (dto.isRSIBelow20() && currentRsi > OVERSOLD_LEVEL) {
            dto.setSignalAction(ScannedContractDto.SignalAction.TRIGGER_OVERSOLD_HOOK);
            logger.info("📈 HOOK UP TRIGGERED for {}: RSI popped from oversold to {}", dto.getSymbol(), currentRsi);

            // 👇 FIX: Only turn off the flag to prevent double-alerts, but KEEP the count for the DB!
            dto.setRSIBelow20(false);
        }
        else {
            // Neutral territory: Now it is safe to completely reset the count
            resetOversoldState(dto);
        }

        // Neutral state
        if (currentRsi > OVERSOLD_LEVEL && currentRsi < OVERBOUGHT_LEVEL
                && dto.getSignalAction() != ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK
                && dto.getSignalAction() != ScannedContractDto.SignalAction.TRIGGER_OVERSOLD_HOOK) {
            dto.setSignalAction(ScannedContractDto.SignalAction.NONE);
        }
    }

    private void resetOverboughtState(ScannedContractDto dto) {
        dto.setRSIAbove80(false);
        dto.setAboveRSI80Count(0);
        dto.setAboveRSI80At(null);           // Clear cycle timestamp for the next run
        dto.setExtremePeakRsi(null);         // Clear peak RSI
        dto.setSignalAction(ScannedContractDto.SignalAction.NONE); // Prevent duplicate alerts
    }

    private void resetOversoldState(ScannedContractDto dto) {
        dto.setRSIBelow20(false);
        dto.setBelowRSI20Count(0);
        dto.setBelowRSI20At(null);           // Clear cycle timestamp for the next run
        dto.setExtremeTroughRsi(null);       // Clear trough RSI
        dto.setSignalAction(ScannedContractDto.SignalAction.NONE); // Prevent duplicate alerts
    }
    private synchronized void throttleApi() {
        long timeSinceLastCall = System.currentTimeMillis() - lastApiCallTime;
        if (timeSinceLastCall < MIN_API_DELAY_MS) {
            sleepQuietly(MIN_API_DELAY_MS - timeSinceLastCall);
        }
        lastApiCallTime = System.currentTimeMillis();
    }
    // =========================================================
    // 🌐 BROKER HISTORICAL CANDLE FETCHER
    // =========================================================

    private List<Double> fetchHistoricalClosePrices(SmartConnect smartConnect, ScannedContractDto dto, LocalDateTime[] window, String interval) {
        int maxRetries = 5;
        long delay = 2000; // Start with a 2-second delay between standard retries

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 1) {
                    logger.warn("⏳ Retrying fetch for {} (Attempt {}/{})... Sleeping {}ms",
                            dto.getSymbol(), attempt, maxRetries, delay);
                    sleepQuietly(delay);
                    delay *= 2; // Exponential backoff for subsequent retries
                }

                JSONObject req = new JSONObject();
                req.put("exchange", dto.getExchange());
                req.put("symboltoken", dto.getToken());
                req.put("interval", interval);
                req.put("fromdate", window[0].format(ANGEL_DATE_FMT));
                req.put("todate", window[1].format(ANGEL_DATE_FMT));
                throttleApi();
                JSONArray candles = smartConnect.candleData(req);

                // Check if we actually got valid candle data back
                if (candles != null && !candles.isEmpty()) {
                    List<Double> closePrices = new ArrayList<>();
                    for (int i = 0; i < candles.length(); i++) {
                        JSONArray c = candles.getJSONArray(i);
                        closePrices.add(c.getDouble(4)); // Index 4 is the Close price
                    }
                    return closePrices;
                } else {
                    // 👈 Log the empty response so we know it failed to return candles
                    logger.warn("⚠️ API returned empty candle data for {} (Token: {}).",
                            dto.getSymbol(), dto.getToken());
                }

            } catch (Exception e) {
                boolean isRateLimit = e.getMessage() != null &&
                        (e.getMessage().contains("503") || e.getMessage().contains("Too Many Requests"));

                if (isRateLimit) {
                    logger.warn("🚦 Rate Limit hit for {} (attempt {}/{}). Backing off for {}ms...",
                            dto.getSymbol(), attempt, maxRetries, RATE_LIMIT_SLEEP_MS);
                    sleepQuietly(RATE_LIMIT_SLEEP_MS);
                } else {
                    // Downgrade to debug so it doesn't spam your console
                    logger.debug("⚠️ API fetch issue for {}: {}", dto.getSymbol(), e.getMessage());
                }
            }
        }

        // 👇 DOWNGRADE this from ERROR to INFO or DEBUG
        logger.info("⏭️ Skipping {}: Exhausted 5 API attempts. Historical data unavailable (Likely sparse volume).",
                dto.getSymbol());
        return null;
    }

    /**
     * Calculates an optimal historical lookback window tailored to the specific timeframe
     * so that TradingView's Wilder RMA smoothing receives sufficient warmup candles.
     */
    private LocalDateTime[] resolveMarketWindow(String interval, String exchange) {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        LocalDate today = LocalDate.now(ist);
        LocalTime now = LocalTime.now(ist);

        // 👇 Determine Market Timings based on the exchange
        boolean isMcx = "MCX".equalsIgnoreCase(exchange);
        LocalTime marketOpen = isMcx ? LocalTime.of(9, 0) : LocalTime.of(9, 15);
        LocalTime marketClose = isMcx ? LocalTime.of(23, 30) : LocalTime.of(15, 30);

        LocalDate currentTradingDay = NSEWorkingDays.isNSEWorkingDay(today) ? today : NSEWorkingDays.getLastWorkingDay(today);

        int calendarDaysBack = switch (interval) {
            case "ONE_MINUTE", "THREE_MINUTE", "FIVE_MINUTE" -> 3;
            case "FIFTEEN_MINUTE", "THIRTY_MINUTE" -> 8;
            case "ONE_HOUR" -> 22;
            case "ONE_DAY" -> 90;
            default -> 15;
        };

        LocalDate prevDay = currentTradingDay.minusDays(calendarDaysBack);
        LocalDate previousTradingDay = NSEWorkingDays.isNSEWorkingDay(prevDay) ? prevDay : NSEWorkingDays.getLastWorkingDay(prevDay);

        LocalDateTime from = LocalDateTime.of(previousTradingDay, marketOpen);

        LocalDateTime to;
        // Cap the time at the specific market's close
        if (currentTradingDay.isEqual(today) && now.isBefore(marketClose)) {
            to = LocalDateTime.of(today, now);
        } else {
            to = LocalDateTime.of(currentTradingDay, marketClose);
        }

        return new LocalDateTime[]{from, to};
    }

    private void sleepQuietly(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}