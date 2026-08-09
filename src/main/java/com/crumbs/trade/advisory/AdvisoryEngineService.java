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
    private final AngelWebSocketService webSocketService;

    public record MultiTimeframeTrend(String dailyTrend, String weeklyTrend, boolean isAligned) {}

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
        AdvisoryOiService.AdvisoryOiData oiData;
        try {
            oiData = oiService.fetchLiveOiAndGreeks(name, exchange, indexes.getExpiry());
        } catch (Exception e) {
            log.warn("⚠️ Skipping Advisory for {}: Failed to fetch live OI & Greeks - {}", name, e.getMessage());
            return null;
        }

        String resolvedExpiry = (oiData.expiry() != null && !oiData.expiry().trim().isEmpty())
                ? oiData.expiry() : indexes.getExpiry();

        // 4. Retrieve Active State
        Optional<AdvisoryLedger> activeRecordOpt = ledgerRepository
                .findTopBySymbolAndStatusOrderByTimestampDesc(name, "ACTIVE");

        LocalDateTime now = LocalDateTime.now();

        // Prepare today's foundational data
        AdvisoryLedger newRecord = AdvisoryLedger.builder()
                .symbol(name)
                .expiryDate(resolvedExpiry)
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

        // 🚨 FIX 1: The Safe Cleanup Block
        // We use .contains() to catch compound actions like "REVERSE_SMC_BOS -> NO_TRADE"
        String finalAction = newRecord.getActionTaken() != null ? newRecord.getActionTaken() : "";
        if (finalAction.contains("NO_TRADE") ||
                finalAction.contains("EXIT") ||
                finalAction.contains("MISALIGNMENT") ||
                newRecord.getRecommendedStrike() == null) {

            newRecord.setStatus("HISTORY");
        }
// =========================================================================
        // 🚨 NEW: Calculate Daily Unrealized PnL (Floating PnL) BEFORE saving
        // =========================================================================
        BigDecimal unrealizedPnl = null;

        if ("ACTIVE".equalsIgnoreCase(newRecord.getStatus()) && newRecord.getEntryPremium() != null) {
            BigDecimal liveLtp = safelyFetchExitPremium(newRecord, exchange);

            if (liveLtp != null) {
                // Math for Option Sellers: Entry Premium - Live Premium
                unrealizedPnl = newRecord.getEntryPremium().subtract(liveLtp);

                // Store today's closing premium and floating PnL into the DB entity
                newRecord.setCurrentPremium(liveLtp);
                newRecord.setUnrealizedPnl(unrealizedPnl);

                log.info("📈 Daily MTM for {}: Entry ₹{} | Current ₹{} | Unrealized PnL: ₹{}",
                        newRecord.getSymbol(), newRecord.getEntryPremium(), liveLtp, unrealizedPnl);
            } else {
                log.warn("⚠️ Could not fetch live LTP for {}, unrealized PnL will be blank for today.", newRecord.getSymbol());
            }
        }
        // Save (Hibernate will UPDATE if ID exists from MAINTAIN, otherwise INSERT)
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
                .unrealizedPnl(unrealizedPnl)
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
                                        LocalDateTime now, String exchange) {

        // GUARD 0: Phantom Position Failsafe
        if (prev.getActionTaken() != null && prev.getActionTaken().contains("NO_TRADE") || prev.getRecommendedStrike() == null) {
            closePreviousState(prev, null, now);
            handleNewPosition(current, mtfTrend, oiData, now);
            return;
        }

        BigDecimal safeBuffer = atr14.multiply(new BigDecimal("1.25"));
        boolean ceBreached = "CE".equalsIgnoreCase(prev.getOptionType()) &&
                spotPrice.compareTo(prev.getRecommendedStrike().subtract(safeBuffer)) >= 0;
        boolean peBreached = "PE".equalsIgnoreCase(prev.getOptionType()) &&
                spotPrice.compareTo(prev.getRecommendedStrike().add(safeBuffer)) <= 0;

        // GUARD 1: Proximity Stop Loss
        if (ceBreached || peBreached) {
            BigDecimal exitPremium = safelyFetchExitPremium(prev, exchange);
            closePreviousState(prev, exitPremium, now);

            current.setActionTaken("EXIT_PROXIMITY_BREACH");
            current.setReasoning(String.format("EMERGENCY EXIT: Spot price (%s) breached safety buffer for active %s strike (%s).",
                    spotPrice, prev.getOptionType(), prev.getRecommendedStrike()));
            return;
        }

        // GUARD 2: SMC Structural Reversal
        if (smcSignalOpt.isPresent()) {
            FuturesBreakEvent bos = smcSignalOpt.get();
            boolean peHoldingBreakdown = "PE".equalsIgnoreCase(prev.getOptionType()) && "BREAKDOWN".equalsIgnoreCase(bos.getBreakType());
            boolean ceHoldingBreakout = "CE".equalsIgnoreCase(prev.getOptionType()) && "BREAKOUT".equalsIgnoreCase(bos.getBreakType());

            if (peHoldingBreakdown || ceHoldingBreakout) {
                BigDecimal exitPremium = safelyFetchExitPremium(prev, exchange);
                closePreviousState(prev, exitPremium, now);

                handleNewPosition(current, mtfTrend, oiData, now);
                current.setActionTaken("REVERSE_SMC_BOS -> " + current.getActionTaken());
                return;
            }
        }

        // GUARD 3: Daily Trend Flip
        if (prev.getDailyTrend() != null && !prev.getDailyTrend().equals(mtfTrend.dailyTrend())) {
            BigDecimal exitPremium = safelyFetchExitPremium(prev, exchange);
            closePreviousState(prev, exitPremium, now);

            handleNewPosition(current, mtfTrend, oiData, now);
            current.setActionTaken("REVERSE_TREND_FAIL -> " + current.getActionTaken());
            return;
        }

        // GUARD 4: Wall Migration (Using compareTo to ignore .00 scale differences)
        boolean wallShifted = false;
        if ("PE".equalsIgnoreCase(prev.getOptionType()) && oiData.putWall() != null) {
            if (prev.getPutWallStrike() != null && prev.getPutWallStrike().compareTo(oiData.putWall().strike()) != 0) {
                wallShifted = true;
            }
        } else if ("CE".equalsIgnoreCase(prev.getOptionType()) && oiData.callWall() != null) {
            if (prev.getCallWallStrike() != null && prev.getCallWallStrike().compareTo(oiData.callWall().strike()) != 0) {
                wallShifted = true;
            }
        }

        if (wallShifted) {
            BigDecimal exitPremium = safelyFetchExitPremium(prev, exchange);
            closePreviousState(prev, exitPremium, now);

            handleNewPosition(current, mtfTrend, oiData, now);
            current.setActionTaken("ADJUST_WALL_SHIFT -> " + current.getActionTaken());
            return;
        }

        // GUARD 5: ALL CLEAR -> MAINTAIN POSITION
        // 🚨 FIX 3: Setting ID guarantees an UPDATE to avoid database duplication
        current.setId(prev.getId());
        current.setActionTaken("MAINTAIN");

        // Lock in Original Option Specs
        current.setOptionType(prev.getOptionType());
        current.setRecommendedStrike(prev.getRecommendedStrike());
        current.setExpiryDate(prev.getExpiryDate());

        // Lock in Original Entry Data (Preserving history!)
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
                newRecord.setReasoning("Bullish trend detected, but NO Put Wall found. Market lacks structure.");
            } else if (oiData.putWall().ltp().compareTo(minPremium) < 0) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning(String.format("Bullish trend. Put Wall exists at %s, but premium (₹%s) is illiquid (< ₹10).",
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
                newRecord.setReasoning("Bearish trend detected, but NO Call Wall found. Market lacks structure.");
            } else if (oiData.callWall().ltp().compareTo(minPremium) < 0) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning(String.format("Bearish trend. Call Wall exists at %s, but premium (₹%s) is illiquid (< ₹10).",
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

    private BigDecimal safelyFetchExitPremium(AdvisoryLedger prev, String exchange) {
        if (prev.getRecommendedStrike() == null || prev.getOptionType() == null || prev.getExpiryDate() == null) {
            return null;
        }

        try {
            String strikeStr = String.valueOf(prev.getRecommendedStrike().intValue());
            String suffix = "%" + strikeStr + prev.getOptionType();
            String optionToken = indexesRepo.findTokenByNameAndExpiryAndSymbolLike(prev.getSymbol(), prev.getExpiryDate(), suffix);

            if (optionToken == null) return null;

            ExchangeType exType = exchange.contains("MCX") ? ExchangeType.MCX_FO : ExchangeType.NSE_FO;
            BigDecimal exitLtp = webSocketService.getLatestLTP(exType, optionToken);

            if (exitLtp != null && exitLtp.compareTo(BigDecimal.ZERO) > 0) return exitLtp;

        } catch (Exception e) {
            log.warn("⚠️ Failed to fetch Exit LTP for {}. Error: {}", prev.getSymbol(), e.getMessage());
        }
        return null;
    }

    private void closePreviousState(AdvisoryLedger record, BigDecimal exitPremium, LocalDateTime now) {
        record.setStatus("HISTORY");
        record.setTimestamp(now); // Mark the exact time the trade was closed

        // 🚨 FIX 2: Gracefully handle missing WebSocket data so we don't lose the exit reasoning
        if (exitPremium != null && record.getEntryPremium() != null) {
            record.setExitPremium(exitPremium);
            BigDecimal pnl = record.getEntryPremium().subtract(exitPremium); // Sell Entry - Buy Exit
            record.setRealizedPnl(pnl);

            log.info("💰 Closed {} Position on {} | Entry: ₹{} | Exit: ₹{} | Realized PnL: ₹{}",
                    record.getOptionType(), record.getSymbol(), record.getEntryPremium(), exitPremium, pnl);
        } else {
            // Write a permanent warning into the DB if we had to exit without knowing the exact exit price
            String existingReason = record.getReasoning() != null ? record.getReasoning() : "";
            record.setReasoning(existingReason + " | ⚠️ WARNING: Trade closed, but WebSocket failed to fetch Exit Premium. Manual PnL verification required.");
            log.warn("⚠️ Closed {} Position on {}, but Exit Premium was unavailable.", record.getOptionType(), record.getSymbol());
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

            return smcLiteService.evaluateAndNotify(name, "NIFTY_50", smcCandles, spotPrice, false);

        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private MultiTimeframeTrend analyzeMultiTimeframeTrend(List<PricesIndex> candles, BigDecimal spotPrice) {
        BigDecimal sum20 = BigDecimal.ZERO;
        for (int i = candles.size() - 20; i < candles.size(); i++) sum20 = sum20.add(candles.get(i).getClose());
        BigDecimal ma20 = sum20.divide(new BigDecimal("20"), 2, RoundingMode.HALF_UP);
        String dailyTrend = spotPrice.compareTo(ma20) >= 0 ? "BULLISH" : "BEARISH";

        BigDecimal sum50 = BigDecimal.ZERO;
        for (int i = candles.size() - 50; i < candles.size(); i++) sum50 = sum50.add(candles.get(i).getClose());
        BigDecimal ma50 = sum50.divide(new BigDecimal("50"), 2, RoundingMode.HALF_UP);
        String weeklyTrend = spotPrice.compareTo(ma50) >= 0 ? "BULLISH" : "BEARISH";

        return new MultiTimeframeTrend(dailyTrend, weeklyTrend, dailyTrend.equals(weeklyTrend));
    }

    private BigDecimal calculateATR(List<PricesIndex> candles, int period) {
        if (candles.size() <= period) return BigDecimal.TEN;
        BigDecimal trSum = BigDecimal.ZERO;

        for (int i = candles.size() - period; i < candles.size(); i++) {
            BigDecimal high = candles.get(i).getHigh();
            BigDecimal low = candles.get(i).getLow();
            BigDecimal prevClose = candles.get(i - 1).getClose();

            BigDecimal tr = high.subtract(low).max(high.subtract(prevClose).abs()).max(low.subtract(prevClose).abs());
            trSum = trSum.add(tr);
        }
        return trSum.divide(new BigDecimal(period), 2, RoundingMode.HALF_UP);
    }
}