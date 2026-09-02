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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
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

    // Standard RSI thresholds
    private static final double OVERBOUGHT_LEVEL = 70.0;
    private static final double OVERSOLD_LEVEL = 20.0;

    private final AngelOne angelOne;

    /**
     * Orchestrates fetching generic candles and applying RSI calculation.
     * Uses multi-threading to fetch candles rapidly while respecting rate limits.
     *
     * @param contracts List of recently scanned contracts
     * @return Updated list of contracts
     */
    public List<ScannedContractDto> evaluateIndicatorsForContracts(List<ScannedContractDto> contracts) {
        if (contracts == null || contracts.isEmpty()) return contracts;

        SmartConnect smartConnect = angelOne.signIn();
        if (smartConnect == null) {
            logger.error("❌ Failed to sign in to Angel One. Aborting indicator evaluation.");
            return contracts;
        }

        LocalDateTime[] window = resolveNseOneHourWindow();
        ExecutorService executor = Executors.newFixedThreadPool(5); // 5 concurrent API calls

        try {
            List<CompletableFuture<Void>> futures = contracts.stream().map(dto ->
                    CompletableFuture.runAsync(() -> {
                        try {
                            // 1. GENERIC FETCH: Get 1H Candle Close Prices
                            List<Double> closes = fetchHistoricalClosePrices(smartConnect, dto, window, "ONE_HOUR");

                            // Need at least (RSI_PERIOD + 1) candles to calculate RSI
                            if (closes != null && closes.size() >= RSI_PERIOD + 1) {

                                // 2. CALCULATE RSI: Using the dedicated RsiCalculation utility
                                Double currentRsi = RsiCalculation.calculate(closes, RSI_PERIOD);

                                if (currentRsi != null) {
                                    // 3. Update Stateful DTO with RSI Hooks
                                    updateRSIState(dto, currentRsi);
                                }
                            }
                        } catch (Exception e) {
                            logger.error("🛑 Error processing indicators for {}: {}", dto.getSymbol(), e.getMessage());
                        }
                    }, executor)
            ).toList();

            // Wait for all threads to finish
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

        // Push current to previous for the next cycle comparison
        dto.setPreviousRsi(dto.getCurrentRsi());
        dto.setCurrentRsi(currentRsi);
        dto.setLastEvaluatedAt(now);

        // ==========================================
        // OVERBOUGHT LOGIC (>= 80)
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
        // Hook Down: Was above 80, now crossed below
        else if (dto.isRSIAbove80() && currentRsi < OVERBOUGHT_LEVEL) {
            dto.setSignalAction(ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK);
            logger.info("📉 HOOK DOWN TRIGGERED for {}: TV RSI dropped from overbought to {}", dto.getSymbol(), currentRsi);
            resetOverboughtState(dto);
        }
        else {
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
        // Hook Up: Was below 20, now crossed above
        else if (dto.isRSIBelow20() && currentRsi > OVERSOLD_LEVEL) {
            dto.setSignalAction(ScannedContractDto.SignalAction.TRIGGER_OVERSOLD_HOOK);
            logger.info("📈 HOOK UP TRIGGERED for {}: TV RSI popped from oversold to {}", dto.getSymbol(), currentRsi);
            resetOversoldState(dto);
        }
        else {
            resetOversoldState(dto);
        }

        // If neither tracking nor hooking, set to NONE
        if (currentRsi > OVERSOLD_LEVEL && currentRsi < OVERBOUGHT_LEVEL
                && dto.getSignalAction() != ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK
                && dto.getSignalAction() != ScannedContractDto.SignalAction.TRIGGER_OVERSOLD_HOOK) {
            dto.setSignalAction(ScannedContractDto.SignalAction.NONE);
        }
    }

    private void resetOverboughtState(ScannedContractDto dto) {
        dto.setRSIAbove80(false);
        dto.setAboveRSI80Count(0);
    }

    private void resetOversoldState(ScannedContractDto dto) {
        dto.setRSIBelow20(false);
        dto.setBelowRSI20Count(0);
    }

    // =========================================================
    // 🌐 GENERIC BROKER CANDLE FETCHER
    // =========================================================

    private List<Double> fetchHistoricalClosePrices(SmartConnect smartConnect, ScannedContractDto dto, LocalDateTime[] window, String interval) {
        int maxRetries = 3;
        long delay = 1000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 1) {
                    sleepQuietly(delay);
                    delay *= 2;
                }

                JSONObject req = new JSONObject();
                req.put("exchange", dto.getExchange());
                req.put("symboltoken", dto.getToken());
                req.put("interval", interval);
                req.put("fromdate", window[0].toString().replace("T", " "));
                req.put("todate", window[1].toString().replace("T", " "));

                JSONArray candles = smartConnect.candleData(req);

                if (candles != null && !candles.isEmpty()) {
                    List<Double> closePrices = new ArrayList<>();
                    for (int i = 0; i < candles.length(); i++) {
                        JSONArray c = candles.getJSONArray(i);
                        closePrices.add(c.getDouble(4)); // Index 4 is the Close price
                    }
                    return closePrices;
                }

            } catch (Exception e) {
                boolean is503 = e.getMessage() != null &&
                        (e.getMessage().contains("503") || e.getMessage().contains("Too Many Requests"));
                if (is503) {
                    logger.warn("⚠️ Rate Limit hit for {} (attempt {}/{}). Backing off...", dto.getSymbol(), attempt, maxRetries);
                    sleepQuietly(RATE_LIMIT_SLEEP_MS);
                }
            }
        }
        return null;
    }

    private LocalDateTime[] resolveNseOneHourWindow() {
        ZoneId IST = ZoneId.of("Asia/Kolkata");
        LocalDate today = LocalDate.now(IST);

        // Fetch ~20 days back to guarantee > 100 hourly candles to let TradingView RMA smooth out perfectly
        LocalDate currentTradingDay = NSEWorkingDays.isNSEWorkingDay(today) ? today : NSEWorkingDays.getLastWorkingDay(today);
        LocalDate prevDay = currentTradingDay.minusDays(20);
        LocalDate previousTradingDay = NSEWorkingDays.isNSEWorkingDay(prevDay) ? prevDay : NSEWorkingDays.getLastWorkingDay(prevDay);

        LocalDateTime from = LocalDateTime.of(previousTradingDay, LocalTime.of(9, 15));
        LocalDateTime to   = LocalDateTime.of(currentTradingDay,  LocalTime.of(15, 15));

        return new LocalDateTime[]{from, to};
    }

    private void sleepQuietly(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}