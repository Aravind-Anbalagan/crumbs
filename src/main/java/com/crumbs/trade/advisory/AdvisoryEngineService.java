package com.crumbs.trade.advisory;

import com.crumbs.trade.dto.CandleRequestDto;
import com.crumbs.trade.entity.FuturesBreakEvent;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.PricesIndex;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.service.FuturesStrategyService.HourlyCandle;
import com.crumbs.trade.service.SRService;
import com.crumbs.trade.service.SmcLiteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdvisoryEngineService {

    private final IndexesRepo indexesRepo;
    private final SRService srService;                 // 🚀 Handles candle fetching & CandleCache
    private final AdvisoryOiService oiService;          // 🚀 Samco Open Interest & Option Chain
    private final SmcLiteService smcLiteService;        // 🚀 SMC Structural BOS Leading Oracle
    private final AdvisoryLedgerRepository ledgerRepository;

    public record MultiTimeframeTrend(
            String dailyTrend,   // BULLISH / BEARISH
            String weeklyTrend,  // BULLISH / BEARISH
            boolean isAligned    // True when Macro & Micro agree
    ) {}

    @Transactional
    public OptionRecommendation processAdvisory(String name, String token) {
        log.info("🧠 Running Stateful EOD Advisory Engine for: {} (Token: {})", name, token);

        Indexes indexes = indexesRepo.findByToken(token);
        if (indexes == null) {
            log.warn("Skipping {}: No Index metadata found for token {}", name, token);
            return null;
        }

        String exchange = indexes.getExchange();

        // ---------------------------------------------------------------------
        // 1. Fetch Daily Candles via SRService & Compute MTF Trends
        // ---------------------------------------------------------------------
        CandleRequestDto dailyReq = srService.getCandleTiming("ONE_DAY", exchange);
        List<PricesIndex> dailyCandles = srService.getCandleData(dailyReq, name, indexes.getSymbol());

        if (dailyCandles == null || dailyCandles.size() < 50) {
            log.warn("[{}] Insufficient daily candles (minimum 50 required for SMA50 MTF). Skipping.", name);
            return null;
        }

        BigDecimal spotPrice = dailyCandles.get(dailyCandles.size() - 1).getClose();
        MultiTimeframeTrend mtfTrend = analyzeMultiTimeframeTrend(dailyCandles, spotPrice);
        BigDecimal atr14 = calculateATR(dailyCandles, 14);

        // ---------------------------------------------------------------------
        // 2. Query SMC Lite Oracle via 1-Hour Candles (Leading Structural Radar)
        // ---------------------------------------------------------------------
        Optional<FuturesBreakEvent> smcSignalOpt = evaluateSmcOracle(name, exchange, indexes.getSymbol(), spotPrice);

        // 🚀 SECONDARY SAFETY NET: Just in case
        if (smcSignalOpt == null) {
            smcSignalOpt = Optional.empty();
        }

        // ---------------------------------------------------------------------
        // 3. Fetch Live Samco Option Chain Walls & Greeks
        // ---------------------------------------------------------------------
        AdvisoryOiService.AdvisoryOiData oiData = oiService.fetchLiveOiAndGreeks(
                name, exchange, indexes.getExpiry()
        );

        // ---------------------------------------------------------------------
        // 4. Retrieve Active State from Database Memory
        // ---------------------------------------------------------------------
        Optional<AdvisoryLedger> activeRecordOpt = ledgerRepository
                .findTopBySymbolAndStatusOrderByTimestampDesc(name, "ACTIVE");

        LocalDateTime now = LocalDateTime.now();
        AdvisoryLedger newRecord = AdvisoryLedger.builder()
                .symbol(name)
                .expiryDate(indexes.getExpiry())
                .timestamp(now)
                .status("ACTIVE")
                .spotPrice(spotPrice)
                .dailyTrend(mtfTrend.dailyTrend())
                .atr14(atr14)
                .build();

        // Record Samco Walls
        if (oiData.putWall() != null) {
            newRecord.setPutWallStrike(oiData.putWall().strike());
            newRecord.setPutWallOi(BigDecimal.valueOf(oiData.putWall().openInterest()));
        }
        if (oiData.callWall() != null) {
            newRecord.setCallWallStrike(oiData.callWall().strike());
            newRecord.setCallWallOi(BigDecimal.valueOf(oiData.callWall().openInterest()));
        }

        // Record SMC Signal if detected
        smcSignalOpt.ifPresent(bos -> newRecord.setSmcSignal(bos.getBreakType()));

        // ---------------------------------------------------------------------
        // 5. Evaluate Stateful Comparison Matrix
        // ---------------------------------------------------------------------
        if (activeRecordOpt.isEmpty()) {
            handleNewPosition(newRecord, mtfTrend, oiData, now); // 🚀 NEW: Passed "now"
        } else {
            AdvisoryLedger activeRecord = activeRecordOpt.get();
            evaluateStatefulMatrix(activeRecord, newRecord, spotPrice, mtfTrend, atr14, oiData, smcSignalOpt, now); // 🚀 NEW: Passed "now"
        }

        // Persist new active ledger entry
        ledgerRepository.save(newRecord);

        return OptionRecommendation.builder()
                .symbol(name)
                .timestamp(now)
                .spotPrice(spotPrice)
                .dailyTrend(mtfTrend.dailyTrend())
                .action(newRecord.getActionTaken())
                .recommendedStrike(newRecord.getRecommendedStrike())
                .putOiWallStrike(newRecord.getPutWallStrike())
                .callOiWallStrike(newRecord.getCallWallStrike())
                .atr14(atr14)
                .reasoning(newRecord.getReasoning())
                .smcSignal(newRecord.getSmcSignal())
                // 🚀 PASS TO RESPONSE
                .entryPremium(newRecord.getEntryPremium())
                .entryDelta(newRecord.getEntryDelta())
                .entryIv(newRecord.getEntryIv())
                .build();
    }

    // =========================================================================
    // 🧠 THE COMPARISON MATRIX
    // =========================================================================

    private void evaluateStatefulMatrix(AdvisoryLedger prev, AdvisoryLedger current, BigDecimal spotPrice,
                                        MultiTimeframeTrend mtfTrend, BigDecimal atr14,
                                        AdvisoryOiService.AdvisoryOiData oiData,
                                        Optional<FuturesBreakEvent> smcSignalOpt,
                                        LocalDateTime now) { // 🚀 NEW: Parameter added

        // 🚀 GUARD 0: The Phantom Position Guard
        if ("NO_TRADE".equalsIgnoreCase(prev.getActionTaken()) || prev.getRecommendedStrike() == null) {
            closePreviousState(prev);
            handleNewPosition(current, mtfTrend, oiData, now); // 🚀 NEW: Pass "now"
            return;
        }

        BigDecimal safeBuffer = atr14.multiply(new BigDecimal("1.25"));

        // GUARD 1: Proximity Guard (Emergency Capital Protection)
        boolean ceBreached = "CE".equalsIgnoreCase(prev.getOptionType()) &&
                spotPrice.compareTo(prev.getRecommendedStrike().subtract(safeBuffer)) >= 0;
        boolean peBreached = "PE".equalsIgnoreCase(prev.getOptionType()) &&
                spotPrice.compareTo(prev.getRecommendedStrike().add(safeBuffer)) <= 0;

        if (ceBreached || peBreached) {
            closePreviousState(prev);
            current.setActionTaken("EXIT_PROXIMITY_BREACH");
            // 🚀 NEW: We are completely exiting, so we DO NOT carry over the entry date
            current.setReasoning(String.format("EMERGENCY EXIT: Spot price (%s) breached safety buffer for active %s strike (%s).",
                    spotPrice, prev.getOptionType(), prev.getRecommendedStrike()));
            return;
        }

        // GUARD 2: SMC Structural BOS Guard (Leading Override)
        if (smcSignalOpt.isPresent()) {
            FuturesBreakEvent bos = smcSignalOpt.get();
            boolean peHoldingBreakdown = "PE".equalsIgnoreCase(prev.getOptionType()) && "BREAKDOWN".equalsIgnoreCase(bos.getBreakType());
            boolean ceHoldingBreakout = "CE".equalsIgnoreCase(prev.getOptionType()) && "BREAKOUT".equalsIgnoreCase(bos.getBreakType());

            if (peHoldingBreakdown || ceHoldingBreakout) {
                closePreviousState(prev);
                current.setActionTaken("REVERSE_SMC_BOS");
                current.setReasoning(String.format("SMC STRUCTURAL OVERRIDE: Hourly %s detected at ₹%s. Institutional Demand/Supply broken against active %s position.",
                        bos.getBreakType(), bos.getBreakPrice(), prev.getOptionType()));
                handleNewPosition(current, mtfTrend, oiData, now); // 🚀 NEW: Pass "now" for brand new trade
                return;
            }
        }

        // GUARD 3: Multi-Timeframe Trend Failure Guard
        if (prev.getDailyTrend() != null && !prev.getDailyTrend().equals(mtfTrend.dailyTrend())) {
            closePreviousState(prev);
            current.setActionTaken("REVERSE_TREND_FAIL");
            current.setReasoning(String.format("Trend flipped from %s to %s. Closing active %s position.",
                    prev.getDailyTrend(), mtfTrend.dailyTrend(), prev.getOptionType()));
            handleNewPosition(current, mtfTrend, oiData, now); // 🚀 NEW: Pass "now"
            return;
        }

        // GUARD 4: Institutional Wall Migration Guard
        boolean wallShifted = false;
        if ("PE".equalsIgnoreCase(prev.getOptionType()) && oiData.putWall() != null) {
            if (prev.getPutWallStrike() != null && !prev.getPutWallStrike().equals(oiData.putWall().strike())) {
                wallShifted = true;
            }
        } else if ("CE".equalsIgnoreCase(prev.getOptionType()) && oiData.callWall() != null) {
            if (prev.getCallWallStrike() != null && !prev.getCallWallStrike().equals(oiData.callWall().strike())) {
                wallShifted = true;
            }
        }

        if (wallShifted) {
            closePreviousState(prev);
            current.setActionTaken("ADJUST_WALL_SHIFT");
            current.setReasoning("Institutional OI Wall migrated. Adjusting position to mirror new wall.");
            handleNewPosition(current, mtfTrend, oiData, now); // 🚀 NEW: Pass "now"
            return;
        }

        // GUARD 5: Maintain Active Position
        closePreviousState(prev); // Archives yesterday's row so only today's row stays ACTIVE
        current.setActionTaken("MAINTAIN");
        current.setOptionType(prev.getOptionType());
        current.setRecommendedStrike(prev.getRecommendedStrike());

        // 🚀 NEW: CARRY OVER PREMIUM, GREEKS, AND ORIGINAL ENTRY DATE
        current.setEntryPremium(prev.getEntryPremium());
        current.setEntryDelta(prev.getEntryDelta());
        current.setEntryIv(prev.getEntryIv());
        current.setEntryDate(prev.getEntryDate() != null ? prev.getEntryDate() : prev.getTimestamp());

        current.setReasoning(String.format("Position intact. Holding %s %s wall. Premium decay progressing safely.",
                prev.getRecommendedStrike(), prev.getOptionType()));
    }

    private void handleNewPosition(AdvisoryLedger newRecord, MultiTimeframeTrend mtfTrend, AdvisoryOiService.AdvisoryOiData oiData, LocalDateTime now) {
        BigDecimal minPremium = new BigDecimal("10.0");

        // Require Macro/Micro Alignment for maximum safety
        if (!mtfTrend.isAligned()) {
            newRecord.setActionTaken("HOLD_TIME_FRAME_MISALIGNMENT");
            newRecord.setReasoning(String.format("Trade paused: Daily Trend is %s but Weekly Macro Trend is %s.",
                    mtfTrend.dailyTrend(), mtfTrend.weeklyTrend()));
            return;
        }

        // 🚀 ENHANCED WALL STRENGTH REPORTING
        if ("BULLISH".equals(mtfTrend.dailyTrend())) {
            if (oiData.putWall() == null) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning("Bullish trend detected, but NO Put Wall found to act as support. Market lacks structure.");
            } else if (oiData.putWall().ltp().compareTo(minPremium) < 0) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning(String.format("Bullish trend. Put Wall exists at %s, but premium (₹%s) is too weak/illiquid (< ₹10). Unsafe to deploy capital.",
                        oiData.putWall().strike(), oiData.putWall().ltp()));
            } else {
                newRecord.setActionTaken("NEW_ENTRY");
                newRecord.setOptionType("PE");
                newRecord.setRecommendedStrike(oiData.putWall().strike());
                newRecord.setEntryPremium(oiData.putWall().ltp());
                newRecord.setEntryDelta(BigDecimal.valueOf(oiData.putWall().delta()));
                newRecord.setEntryIv(BigDecimal.valueOf(oiData.putWall().iv()));
                newRecord.setEntryDate(now); // 🚀 NEW: Stamp the initial entry time
                newRecord.setReasoning(String.format("Confirmed MTF Bullish setup. Selling PE at Strong Put Wall %s (Premium: ₹%s)",
                        oiData.putWall().strike(), oiData.putWall().ltp()));
            }
        }
        else if ("BEARISH".equals(mtfTrend.dailyTrend())) {
            if (oiData.callWall() == null) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning("Bearish trend detected, but NO Call Wall found to act as resistance. Market lacks structure.");
            } else if (oiData.callWall().ltp().compareTo(minPremium) < 0) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning(String.format("Bearish trend. Call Wall exists at %s, but premium (₹%s) is too weak/illiquid (< ₹10). Unsafe to deploy capital.",
                        oiData.callWall().strike(), oiData.callWall().ltp()));
            } else {
                newRecord.setActionTaken("NEW_ENTRY");
                newRecord.setOptionType("CE");
                newRecord.setRecommendedStrike(oiData.callWall().strike());
                newRecord.setEntryPremium(oiData.callWall().ltp());
                newRecord.setEntryDelta(BigDecimal.valueOf(oiData.callWall().delta()));
                newRecord.setEntryIv(BigDecimal.valueOf(oiData.callWall().iv()));
                newRecord.setEntryDate(now); // 🚀 NEW: Stamp the initial entry time
                newRecord.setReasoning(String.format("Confirmed MTF Bearish setup. Selling CE at Strong Call Wall %s (Premium: ₹%s)",
                        oiData.callWall().strike(), oiData.callWall().ltp()));
            }
        }
        else {
            newRecord.setActionTaken("NO_TRADE");
            newRecord.setReasoning("Trend is choppy or undefined. Awaiting clear directional structure.");
        }
    }

    // =========================================================================
    // 🛠️ UTILITY & COMPUTATION HELPERS
    // =========================================================================

    private Optional<FuturesBreakEvent> evaluateSmcOracle(String name, String exchange, String symbol, BigDecimal spotPrice) {
        try {
            CandleRequestDto hourlyReq = srService.getCandleTiming("ONE_HOUR", exchange);
            List<PricesIndex> hourlyPrices = srService.getCandleData(hourlyReq, name, symbol);

            if (hourlyPrices == null || hourlyPrices.isEmpty()) {
                return Optional.empty();
            }

            List<HourlyCandle> smcCandles = hourlyPrices.stream().map(p ->
                    new HourlyCandle(
                            p.getTimestamp(),
                            p.getOpen(),
                            p.getHigh(),
                            p.getLow(),
                            p.getClose(),
                            p.getVolume() != null ? p.getVolume().longValue() : 0L
                    )
            ).collect(Collectors.toList());

            // Call the SMC Service
            Optional<FuturesBreakEvent> result = smcLiteService.evaluateAndNotify(name, "NIFTY_50", smcCandles, spotPrice, false);

            // 🚀 DEFENSIVE FIX: If the legacy service returns literal null, catch it and return an empty wrapper.
            return result == null ? Optional.empty() : result;

        } catch (Exception e) {
            log.error("Failed to query SMC Oracle for {}: {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    private MultiTimeframeTrend analyzeMultiTimeframeTrend(List<PricesIndex> candles, BigDecimal spotPrice) {
        // Daily Trend: 20-period SMA
        BigDecimal sum20 = BigDecimal.ZERO;
        for (int i = candles.size() - 20; i < candles.size(); i++) {
            sum20 = sum20.add(candles.get(i).getClose());
        }
        BigDecimal ma20 = sum20.divide(new BigDecimal("20"), 2, RoundingMode.HALF_UP);
        String dailyTrend = spotPrice.compareTo(ma20) >= 0 ? "BULLISH" : "BEARISH";

        // Macro Trend: 50-period SMA (~10 Weeks of daily data)
        BigDecimal sum50 = BigDecimal.ZERO;
        for (int i = candles.size() - 50; i < candles.size(); i++) {
            sum50 = sum50.add(candles.get(i).getClose());
        }
        BigDecimal ma50 = sum50.divide(new BigDecimal("50"), 2, RoundingMode.HALF_UP);
        String weeklyTrend = spotPrice.compareTo(ma50) >= 0 ? "BULLISH" : "BEARISH";

        boolean aligned = dailyTrend.equals(weeklyTrend);

        return new MultiTimeframeTrend(dailyTrend, weeklyTrend, aligned);
    }

    private BigDecimal calculateATR(List<PricesIndex> candles, int period) {
        if (candles.size() <= period) return BigDecimal.TEN;
        BigDecimal trSum = BigDecimal.ZERO;

        for (int i = candles.size() - period; i < candles.size(); i++) {
            BigDecimal high = candles.get(i).getHigh();
            BigDecimal low = candles.get(i).getLow();
            BigDecimal prevClose = candles.get(i - 1).getClose();

            BigDecimal tr1 = high.subtract(low);
            BigDecimal tr2 = high.subtract(prevClose).abs();
            BigDecimal tr3 = low.subtract(prevClose).abs();

            BigDecimal tr = tr1.max(tr2).max(tr3);
            trSum = trSum.add(tr);
        }
        return trSum.divide(new BigDecimal(period), 2, RoundingMode.HALF_UP);
    }

    private void closePreviousState(AdvisoryLedger record) {
        record.setStatus("HISTORY");
        ledgerRepository.save(record);
    }
}