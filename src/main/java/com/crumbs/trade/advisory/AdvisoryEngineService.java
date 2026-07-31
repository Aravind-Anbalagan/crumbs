package com.crumbs.trade.advisory;

import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import com.crumbs.trade.dto.CandleRequestDto;
import com.crumbs.trade.entity.FuturesBreakEvent;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.PricesIndex;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.service.AngelWebSocketService;
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
    private final SRService srService;
    private final AdvisoryOiService oiService;
    private final SmcLiteService smcLiteService;
    private final AdvisoryLedgerRepository ledgerRepository;

    // 🚀 NEW: Inject the Auto-Subscribing WebSocket Service
    private final AngelWebSocketService webSocketService;

    public record MultiTimeframeTrend(
            String dailyTrend,
            String weeklyTrend,
            boolean isAligned
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

        // 1. Fetch Daily Candles
        CandleRequestDto dailyReq = srService.getCandleTiming("ONE_DAY", exchange);
        List<PricesIndex> dailyCandles = srService.getCandleData(dailyReq, name, indexes.getSymbol());

        if (dailyCandles == null || dailyCandles.size() < 50) {
            log.warn("[{}] Insufficient daily candles (minimum 50 required). Skipping.", name);
            return null;
        }

        BigDecimal spotPrice = dailyCandles.get(dailyCandles.size() - 1).getClose();
        MultiTimeframeTrend mtfTrend = analyzeMultiTimeframeTrend(dailyCandles, spotPrice);
        BigDecimal atr14 = calculateATR(dailyCandles, 14);

        // 2. Query SMC Oracle
        Optional<FuturesBreakEvent> smcSignalOpt = evaluateSmcOracle(name, exchange, indexes.getSymbol(), spotPrice);
        if (smcSignalOpt == null) smcSignalOpt = Optional.empty();

        // 3. Fetch Live Samco Option Chain Walls
        AdvisoryOiService.AdvisoryOiData oiData = oiService.fetchLiveOiAndGreeks(
                name, exchange, indexes.getExpiry()
        );

        // 4. Retrieve Active State
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

        if (oiData.putWall() != null) {
            newRecord.setPutWallStrike(oiData.putWall().strike());
            newRecord.setPutWallOi(BigDecimal.valueOf(oiData.putWall().openInterest()));
        }
        if (oiData.callWall() != null) {
            newRecord.setCallWallStrike(oiData.callWall().strike());
            newRecord.setCallWallOi(BigDecimal.valueOf(oiData.callWall().openInterest()));
        }

        smcSignalOpt.ifPresent(bos -> newRecord.setSmcSignal(bos.getBreakType()));

        // 5. Evaluate Stateful Matrix
        if (activeRecordOpt.isEmpty()) {
            handleNewPosition(newRecord, mtfTrend, oiData, now);
        } else {
            AdvisoryLedger activeRecord = activeRecordOpt.get();
            evaluateStatefulMatrix(activeRecord, newRecord, spotPrice, mtfTrend, atr14, oiData, smcSignalOpt, now, exchange);
        }

        ledgerRepository.save(newRecord);
// 🚀 NEW: Calculate Unrealized PnL for ACTIVE trades before sending to UI
        BigDecimal unrealizedPnl = null;
        if ("ACTIVE".equalsIgnoreCase(newRecord.getStatus()) && newRecord.getEntryPremium() != null) {
            BigDecimal liveLtp = safelyFetchExitPremium(newRecord, exchange);
            if (liveLtp != null) {
                // Math for Option Sellers: Entry - Live Price
                unrealizedPnl = newRecord.getEntryPremium().subtract(liveLtp);
            }
        }
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
                .unrealizedPnl(unrealizedPnl)
                .entryPremium(newRecord.getEntryPremium())
                .entryDelta(BigDecimal.valueOf(newRecord.getEntryDelta() != null ? newRecord.getEntryDelta().doubleValue() : null))
                .entryIv(BigDecimal.valueOf(newRecord.getEntryIv() != null ? newRecord.getEntryIv().doubleValue() : null))
                .build();
    }

    // =========================================================================
    // 🧠 THE COMPARISON MATRIX
    // =========================================================================

    private void evaluateStatefulMatrix(AdvisoryLedger prev, AdvisoryLedger current, BigDecimal spotPrice,
                                        MultiTimeframeTrend mtfTrend, BigDecimal atr14,
                                        AdvisoryOiService.AdvisoryOiData oiData,
                                        Optional<FuturesBreakEvent> smcSignalOpt,
                                        LocalDateTime now, String exchange) {

        // GUARD 0: Phantom Position
        if ("NO_TRADE".equalsIgnoreCase(prev.getActionTaken()) || prev.getRecommendedStrike() == null) {
            closePreviousState(prev, null);
            handleNewPosition(current, mtfTrend, oiData, now);
            return;
        }

        BigDecimal safeBuffer = atr14.multiply(new BigDecimal("1.25"));

        // GUARD 1: Proximity Guard (Emergency Capital Protection)
        boolean ceBreached = "CE".equalsIgnoreCase(prev.getOptionType()) &&
                spotPrice.compareTo(prev.getRecommendedStrike().subtract(safeBuffer)) >= 0;
        boolean peBreached = "PE".equalsIgnoreCase(prev.getOptionType()) &&
                spotPrice.compareTo(prev.getRecommendedStrike().add(safeBuffer)) <= 0;

        if (ceBreached || peBreached) {
            BigDecimal exitPremium = safelyFetchExitPremium(prev, exchange);
            closePreviousState(prev, exitPremium);

            current.setActionTaken("EXIT_PROXIMITY_BREACH");
            current.setReasoning(String.format("EMERGENCY EXIT: Spot price (%s) breached safety buffer for active %s strike (%s).",
                    spotPrice, prev.getOptionType(), prev.getRecommendedStrike()));
            return;
        }

        // GUARD 2: SMC Structural BOS Guard
        if (smcSignalOpt.isPresent()) {
            FuturesBreakEvent bos = smcSignalOpt.get();
            boolean peHoldingBreakdown = "PE".equalsIgnoreCase(prev.getOptionType()) && "BREAKDOWN".equalsIgnoreCase(bos.getBreakType());
            boolean ceHoldingBreakout = "CE".equalsIgnoreCase(prev.getOptionType()) && "BREAKOUT".equalsIgnoreCase(bos.getBreakType());

            if (peHoldingBreakdown || ceHoldingBreakout) {
                BigDecimal exitPremium = safelyFetchExitPremium(prev, exchange);
                closePreviousState(prev, exitPremium);

                current.setActionTaken("REVERSE_SMC_BOS");
                current.setReasoning(String.format("SMC STRUCTURAL OVERRIDE: Hourly %s detected at ₹%s. Institutional Structure broken.",
                        bos.getBreakType(), bos.getBreakPrice()));
                handleNewPosition(current, mtfTrend, oiData, now);
                return;
            }
        }

        // GUARD 3: Multi-Timeframe Trend Failure
        if (prev.getDailyTrend() != null && !prev.getDailyTrend().equals(mtfTrend.dailyTrend())) {
            BigDecimal exitPremium = safelyFetchExitPremium(prev, exchange);
            closePreviousState(prev, exitPremium);

            current.setActionTaken("REVERSE_TREND_FAIL");
            current.setReasoning(String.format("Trend flipped from %s to %s. Closing active %s position.",
                    prev.getDailyTrend(), mtfTrend.dailyTrend(), prev.getOptionType()));
            handleNewPosition(current, mtfTrend, oiData, now);
            return;
        }

        // GUARD 4: Wall Migration Guard
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
            BigDecimal exitPremium = safelyFetchExitPremium(prev, exchange);
            closePreviousState(prev, exitPremium);

            current.setActionTaken("ADJUST_WALL_SHIFT");
            current.setReasoning("Institutional OI Wall migrated. Adjusting position to mirror new wall.");
            handleNewPosition(current, mtfTrend, oiData, now);
            return;
        }

        // GUARD 5: Maintain Active Position
        closePreviousState(prev, null);
        current.setActionTaken("MAINTAIN");
        current.setOptionType(prev.getOptionType());
        current.setRecommendedStrike(prev.getRecommendedStrike());

        // CARRY OVER ORIGINAL ENTRY DATA
        current.setEntryPremium(prev.getEntryPremium());
        current.setEntryDelta(prev.getEntryDelta());
        current.setEntryIv(prev.getEntryIv());
        current.setEntryDate(prev.getEntryDate() != null ? prev.getEntryDate() : prev.getTimestamp());

        current.setReasoning(String.format("Position intact. Holding %s %s wall. Premium decay progressing safely.",
                prev.getRecommendedStrike(), prev.getOptionType()));
    }

    private void handleNewPosition(AdvisoryLedger newRecord, MultiTimeframeTrend mtfTrend, AdvisoryOiService.AdvisoryOiData oiData, LocalDateTime now) {
        BigDecimal minPremium = new BigDecimal("10.0");

        if (!mtfTrend.isAligned()) {
            newRecord.setActionTaken("HOLD_TIME_FRAME_MISALIGNMENT");
            newRecord.setReasoning(String.format("Trade paused: Daily Trend is %s but Weekly Macro Trend is %s.",
                    mtfTrend.dailyTrend(), mtfTrend.weeklyTrend()));
            return;
        }

        if ("BULLISH".equals(mtfTrend.dailyTrend())) {
            if (oiData.putWall() == null) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning("Bullish trend detected, but NO Put Wall found to act as support. Market lacks structure.");
            } else if (oiData.putWall().ltp().compareTo(minPremium) < 0) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning(String.format("Bullish trend. Put Wall exists at %s, but premium (₹%s) is too weak/illiquid (< ₹10).",
                        oiData.putWall().strike(), oiData.putWall().ltp()));
            } else {
                newRecord.setActionTaken("NEW_ENTRY");
                newRecord.setOptionType("PE");
                newRecord.setRecommendedStrike(oiData.putWall().strike());
                newRecord.setEntryPremium(oiData.putWall().ltp());
                newRecord.setEntryDelta(BigDecimal.valueOf(oiData.putWall().delta()));
                newRecord.setEntryIv(BigDecimal.valueOf(oiData.putWall().iv()));
                newRecord.setEntryDate(now);
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
                newRecord.setReasoning(String.format("Bearish trend. Call Wall exists at %s, but premium (₹%s) is too weak/illiquid (< ₹10).",
                        oiData.callWall().strike(), oiData.callWall().ltp()));
            } else {
                newRecord.setActionTaken("NEW_ENTRY");
                newRecord.setOptionType("CE");
                newRecord.setRecommendedStrike(oiData.callWall().strike());
                newRecord.setEntryPremium(oiData.callWall().ltp());
                newRecord.setEntryDelta(BigDecimal.valueOf(oiData.callWall().delta()));
                newRecord.setEntryIv(BigDecimal.valueOf(oiData.callWall().iv()));
                newRecord.setEntryDate(now);
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
    // 🛡️ SAFE FETCH & LOGGING HELPERS
    // =========================================================================

    // 🚀 NEW: Fault-Tolerant WebSocket Fetcher
    private BigDecimal safelyFetchExitPremium(AdvisoryLedger prev, String exchange) {
        if (prev.getRecommendedStrike() == null || prev.getOptionType() == null || prev.getExpiryDate() == null) {
            return null;
        }

        try {
            // 1. Format the strike into the exact DB Suffix (e.g., "24500CE")
            String strikeStr = String.valueOf(prev.getRecommendedStrike().intValue());
            String suffix = "%" + strikeStr + prev.getOptionType();

            // 2. Look up the exact Angel One Token from Indexes repository
            String optionToken = indexesRepo.findTokenByNameAndExpiryAndSymbolLike(
                    prev.getSymbol(), prev.getExpiryDate(), suffix
            );

            if (optionToken == null) {
                log.warn("⚠️ Token lookup failed for {} {}. Cannot fetch exit LTP via WebSocket.", prev.getSymbol(), suffix);
                return null;
            }

            // 3. Resolve Exchange Type dynamically
            ExchangeType exType = exchange.contains("MCX") ? ExchangeType.MCX_FO : ExchangeType.NSE_FO;

            // 4. 🚀 Ask the Auto-Subscribing WebSocket for the Price!
            log.info("📡 Asking WebSocket for {} Exit Price (Token: {})...", prev.getSymbol(), optionToken);
            BigDecimal exitLtp = webSocketService.getLatestLTP(exType, optionToken);

            if (exitLtp != null && exitLtp.compareTo(BigDecimal.ZERO) > 0) {
                return exitLtp;
            }

        } catch (Exception e) {
            log.warn("⚠️ WebSocket framework failed to fetch Exit LTP for {} {} {}. Exiting trade without logging PnL. Error: {}",
                    prev.getSymbol(), prev.getRecommendedStrike(), prev.getOptionType(), e.getMessage());
        }

        return null;
    }

    private void closePreviousState(AdvisoryLedger record, BigDecimal exitPremium) {
        record.setStatus("HISTORY");

        // If it's a real exit and we have the prices...
        if (exitPremium != null && record.getEntryPremium() != null) {
            record.setExitPremium(exitPremium);

            // Math for Option SELLERS: Entry Price - Exit Price
            BigDecimal pnl = record.getEntryPremium().subtract(exitPremium);
            record.setRealizedPnl(pnl);

            log.info("💰 Closed {} Position on {} | Entry: ₹{} | Exit: ₹{} | Realized PnL: ₹{}",
                    record.getOptionType(), record.getSymbol(),
                    record.getEntryPremium(), exitPremium, pnl);
        }

        ledgerRepository.save(record);
    }

    // =========================================================================
    // 🛠️ UTILITY & COMPUTATION HELPERS
    // =========================================================================

    private Optional<FuturesBreakEvent> evaluateSmcOracle(String name, String exchange, String symbol, BigDecimal spotPrice) {
        try {
            CandleRequestDto hourlyReq = srService.getCandleTiming("ONE_HOUR", exchange);
            List<PricesIndex> hourlyPrices = srService.getCandleData(hourlyReq, name, symbol);

            if (hourlyPrices == null || hourlyPrices.isEmpty()) return Optional.empty();

            List<HourlyCandle> smcCandles = hourlyPrices.stream().map(p ->
                    new HourlyCandle(p.getTimestamp(), p.getOpen(), p.getHigh(), p.getLow(), p.getClose(),
                            p.getVolume() != null ? p.getVolume().longValue() : 0L)
            ).collect(Collectors.toList());

            Optional<FuturesBreakEvent> result = smcLiteService.evaluateAndNotify(name, "NIFTY_50", smcCandles, spotPrice, false);
            return result == null ? Optional.empty() : result;

        } catch (Exception e) {
            log.error("Failed to query SMC Oracle for {}: {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    private MultiTimeframeTrend analyzeMultiTimeframeTrend(List<PricesIndex> candles, BigDecimal spotPrice) {
        BigDecimal sum20 = BigDecimal.ZERO;
        for (int i = candles.size() - 20; i < candles.size(); i++) {
            sum20 = sum20.add(candles.get(i).getClose());
        }
        BigDecimal ma20 = sum20.divide(new BigDecimal("20"), 2, RoundingMode.HALF_UP);
        String dailyTrend = spotPrice.compareTo(ma20) >= 0 ? "BULLISH" : "BEARISH";

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
}