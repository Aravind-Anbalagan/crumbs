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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
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

    // =========================================================================
    // 🛡️ PER-SYMBOL LOCK (Defense in Depth)
    // =========================================================================
    // The scheduler now dedupes the Nifty table before dispatching, so this
    // should never be contended in normal operation. It exists as a hard
    // safety net: if a duplicate active-token row ever slips back into the
    // data, a manual re-run overlaps a scheduled run, or a retry fires
    // concurrently with the original attempt, this guarantees only one
    // processAdvisory() call per symbol can be evaluating/writing the ledger
    // at a time. Without this, two concurrent calls for the same symbol can
    // both read "no ACTIVE record" and both write NEW_ENTRY, or one can read
    // stale state mid-write by the other — which is what produced the
    // same-millisecond NEW_ENTRY + EXIT_PROXIMITY_BREACH pairs in the ledger.
    private static final Map<String, Lock> SYMBOL_LOCKS = new ConcurrentHashMap<>();

    public record MultiTimeframeTrend(String dailyTrend, String weeklyTrend, boolean isAligned) {}

    public OptionRecommendation processAdvisory(String name, String token) {
        Lock lock = SYMBOL_LOCKS.computeIfAbsent(name, k -> new ReentrantLock());

        boolean acquired;
        try {
            acquired = lock.tryLock(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ Interrupted while waiting for lock on {}. Skipping this run.", name);
            return null;
        }

        if (!acquired) {
            log.error("⚠️ Could not acquire lock for {} within 30s — another evaluation is already in " +
                    "progress for this symbol. Skipping to avoid a ledger race.", name);
            return null;
        }

        try {
            return processAdvisoryInternal(name, token);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.error("🛡️ DB constraint blocked a duplicate ACTIVE row for {} — a cross-instance race " +
                            "was caught at the database level. Skipping this run; existing ACTIVE record stands.",
                    name, e);
            return null;
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    protected OptionRecommendation processAdvisoryInternal(String name, String token) {
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
            handleNewPosition(newRecord, mtfTrend, oiData, now, spotPrice);
        } else {
            AdvisoryLedger activeRecord = activeRecordOpt.get();
            evaluateStatefulMatrix(activeRecord, newRecord, spotPrice, mtfTrend, atr14, oiData, smcSignalOpt, now, exchange);
        }

        // 🚨 The Safe Cleanup Block
        String finalAction = newRecord.getActionTaken() != null ? newRecord.getActionTaken() : "";
        if (finalAction.contains("NO_TRADE") ||
                finalAction.contains("EXIT") ||
                finalAction.contains("MISALIGNMENT") ||
                newRecord.getRecommendedStrike() == null) {

            newRecord.setStatus("HISTORY");
        }

        // =========================================================================
        // 🚨 Calculate Daily Unrealized PnL (Floating PnL) BEFORE saving
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
    // 🧠 THE COMPARISON MATRIX (Strict One-Action-Per-Day)
    // =========================================================================

    private void evaluateStatefulMatrix(AdvisoryLedger prev, AdvisoryLedger current, BigDecimal spotPrice,
                                        MultiTimeframeTrend mtfTrend, BigDecimal atr14,
                                        AdvisoryOiService.AdvisoryOiData oiData,
                                        Optional<FuturesBreakEvent> smcSignalOpt,
                                        LocalDateTime now, String exchange) {

        // 🚨 CRITICAL FIX: Transfer the ID immediately so we UPDATE the active record
        current.setId(prev.getId());

        // Lock in Original Option Specs so we don't lose the trade context
        current.setOptionType(prev.getOptionType());
        current.setRecommendedStrike(prev.getRecommendedStrike());
        current.setExpiryDate(prev.getExpiryDate());
        current.setEntryPremium(prev.getEntryPremium());
        current.setEntryDelta(prev.getEntryDelta());
        current.setEntryIv(prev.getEntryIv());
        current.setEntryDate(prev.getEntryDate() != null ? prev.getEntryDate() : prev.getTimestamp());

        // GUARD 0: Phantom Position Failsafe
        if (prev.getActionTaken() != null && prev.getActionTaken().contains("NO_TRADE") || prev.getRecommendedStrike() == null) {
            executeExit(current, exchange, "EXIT_PHANTOM_STATE", "Invalid previous state. Clearing ledger.");
            return;
        }

        BigDecimal safeBuffer = atr14.multiply(new BigDecimal("1.25"));
        boolean ceBreached = "CE".equalsIgnoreCase(prev.getOptionType()) &&
                spotPrice.compareTo(prev.getRecommendedStrike().subtract(safeBuffer)) >= 0;
        boolean peBreached = "PE".equalsIgnoreCase(prev.getOptionType()) &&
                spotPrice.compareTo(prev.getRecommendedStrike().add(safeBuffer)) <= 0;

        // GUARD 1: Proximity Stop Loss
        if (ceBreached || peBreached) {
            executeExit(current, exchange, "EXIT_PROXIMITY_BREACH",
                    String.format("EMERGENCY EXIT: Spot price (%s) breached safety buffer for active %s strike (%s).",
                            spotPrice, prev.getOptionType(), prev.getRecommendedStrike()));
            return;
        }

        // GUARD 2: SMC Structural Reversal
        if (smcSignalOpt.isPresent()) {
            FuturesBreakEvent bos = smcSignalOpt.get();
            boolean peHoldingBreakdown = "PE".equalsIgnoreCase(prev.getOptionType()) && "BREAKDOWN".equalsIgnoreCase(bos.getBreakType());
            boolean ceHoldingBreakout = "CE".equalsIgnoreCase(prev.getOptionType()) && "BREAKOUT".equalsIgnoreCase(bos.getBreakType());

            if (peHoldingBreakdown || ceHoldingBreakout) {
                executeExit(current, exchange, "EXIT_SMC_REVERSAL",
                        "SMC Macro structure reversed against our position. Exiting trade.");
                return;
            }
        }

        // GUARD 3: Daily Trend Flip
        if (prev.getDailyTrend() != null && !prev.getDailyTrend().equals(mtfTrend.dailyTrend())) {
            executeExit(current, exchange, "EXIT_TREND_FAIL",
                    String.format("Daily trend flipped from %s to %s. Exiting trade.", prev.getDailyTrend(), mtfTrend.dailyTrend()));
            return;
        }

        // GUARD 4: Wall Migration
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
            executeExit(current, exchange, "EXIT_WALL_SHIFT",
                    "Highest OI Wall migrated. Market structure changed. Exiting trade.");
            return;
        }

        // GUARD 5: ALL CLEAR -> MAINTAIN POSITION
        current.setActionTaken("MAINTAIN");
        current.setStatus("ACTIVE");
        current.setReasoning(String.format("Position intact. Holding %s %s wall. Premium decay progressing safely.",
                prev.getRecommendedStrike(), prev.getOptionType()));
    }

    // =========================================================================
    // 🛡️ UNIFIED EXIT HELPER
    // =========================================================================

    private void executeExit(AdvisoryLedger current, String exchange, String action, String reason) {
        current.setActionTaken(action);
        current.setReasoning(reason);
        current.setStatus("HISTORY");

        BigDecimal exitPremium = safelyFetchExitPremium(current, exchange);

        if (exitPremium != null && current.getEntryPremium() != null) {
            current.setExitPremium(exitPremium);
            BigDecimal pnl = current.getEntryPremium().subtract(exitPremium); // Sell Entry - Buy Exit
            current.setRealizedPnl(pnl);

            log.info("💰 Executed {} on {} | Entry: ₹{} | Exit: ₹{} | Realized PnL: ₹{}",
                    action, current.getSymbol(), current.getEntryPremium(), exitPremium, pnl);
        } else {
            current.setReasoning(reason + " | ⚠️ WARNING: WebSocket failed to fetch Exit Premium. Manual PnL verification required.");
            log.warn("⚠️ Executed {} on {}, but Exit Premium was unavailable.", action, current.getSymbol());
        }
    }

    // =========================================================================
    // 🛡️ NEW POSITION HANDLER (With Moneyness, Same-Day Guard & Cooldown)
    // =========================================================================

    private void handleNewPosition(AdvisoryLedger newRecord, MultiTimeframeTrend mtfTrend, AdvisoryOiService.AdvisoryOiData oiData, LocalDateTime now, BigDecimal spotPrice) {
        BigDecimal minPremium = new BigDecimal("10.0");

        // Fetch the last closed trade to check for recent stop-outs (Cooldown Logic)
        // and same-day re-entry (Lifecycle Logic).
        Optional<AdvisoryLedger> lastClosedOpt = ledgerRepository
                .findTopBySymbolAndStatusOrderByTimestampDesc(newRecord.getSymbol(), "HISTORY");

        // =====================================================================
        // 🚨 SAME-DAY RE-ENTRY GUARD
        // =====================================================================
        // Lifecycle rule: NEW_ENTRY -> MAINTAIN/EXIT -> once a position closes,
        // no new position for that symbol until the NEXT scheduled run (i.e.
        // the next calendar day this engine runs). This is intentionally
        // unconditional — unlike isRecentlyBreached() below, it does not care
        // WHY the prior trade closed (proximity breach, wall shift, trend
        // flip, phantom state, etc.) or what strike it was on. It exists as
        // a safety net independent of the scheduler-level dedup fix: even if
        // two calls for this symbol ever race again, this stops the second
        // one from stacking a fresh NEW_ENTRY on top of an exit that just
        // happened in the same session.
        if (lastClosedOpt.isPresent()) {
            LocalDate lastCloseDate = lastClosedOpt.get().getTimestamp().toLocalDate();
            if (lastCloseDate.equals(now.toLocalDate())) {
                newRecord.setActionTaken("NO_TRADE_SAME_DAY_EXIT");
                newRecord.setReasoning(String.format(
                        "Position already closed today (%s) for this symbol. Re-entry deferred to next session.",
                        lastClosedOpt.get().getActionTaken()));
                return;
            }
        }

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
                return;
            }

            BigDecimal putStrike = oiData.putWall().strike();

            // 🚨 MONEYNESS CHECK: Do not sell In-The-Money (ITM) Puts
            if (putStrike.compareTo(spotPrice) > 0) {
                newRecord.setActionTaken("NO_TRADE_ITM_RISK");
                newRecord.setReasoning(String.format("Spot (%s) is below Put Wall (%s). Selling ITM Puts is high risk.", spotPrice, putStrike));
                return;
            }

            // 🚨 COOLDOWN CHECK: Did we just get stopped out of this exact strike?
            if (isRecentlyBreached(lastClosedOpt, putStrike, now)) {
                newRecord.setActionTaken("NO_TRADE_COOLDOWN");
                newRecord.setReasoning(String.format("Cooling down. Strike %s was recently breached. Awaiting structure reset.", putStrike));
                return;
            }

            if (oiData.putWall().ltp().compareTo(minPremium) < 0) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning(String.format("Bullish trend. Put Wall exists at %s, but premium (₹%s) is illiquid (< ₹10).",
                        oiData.putWall().strike(), oiData.putWall().ltp()));
            } else {
                newRecord.setActionTaken("NEW_ENTRY");
                newRecord.setOptionType("PE");
                newRecord.setRecommendedStrike(putStrike);
                newRecord.setEntryPremium(oiData.putWall().ltp());
                newRecord.setEntryDelta(BigDecimal.valueOf(oiData.putWall().delta()));
                newRecord.setEntryIv(BigDecimal.valueOf(oiData.putWall().iv()));
                newRecord.setEntryDate(now);
                newRecord.setReasoning(String.format("Confirmed MTF Bullish setup. Selling OTM PE at Put Wall %s (Premium: ₹%s)",
                        putStrike, oiData.putWall().ltp()));
            }
        }
        else if ("BEARISH".equals(mtfTrend.dailyTrend())) {
            if (oiData.callWall() == null) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning("Bearish trend detected, but NO Call Wall found. Market lacks structure.");
                return;
            }

            BigDecimal callStrike = oiData.callWall().strike();

            // 🚨 MONEYNESS CHECK: Do not sell In-The-Money (ITM) Calls
            if (callStrike.compareTo(spotPrice) < 0) {
                newRecord.setActionTaken("NO_TRADE_ITM_RISK");
                newRecord.setReasoning(String.format("Spot (%s) is above Call Wall (%s). Selling ITM Calls is high risk.", spotPrice, callStrike));
                return;
            }

            // 🚨 COOLDOWN CHECK
            if (isRecentlyBreached(lastClosedOpt, callStrike, now)) {
                newRecord.setActionTaken("NO_TRADE_COOLDOWN");
                newRecord.setReasoning(String.format("Cooling down. Strike %s was recently breached. Awaiting structure reset.", callStrike));
                return;
            }

            if (oiData.callWall().ltp().compareTo(minPremium) < 0) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning(String.format("Bearish trend. Call Wall exists at %s, but premium (₹%s) is illiquid (< ₹10).",
                        oiData.callWall().strike(), oiData.callWall().ltp()));
            } else {
                newRecord.setActionTaken("NEW_ENTRY");
                newRecord.setOptionType("CE");
                newRecord.setRecommendedStrike(callStrike);
                newRecord.setEntryPremium(oiData.callWall().ltp());
                newRecord.setEntryDelta(BigDecimal.valueOf(oiData.callWall().delta()));
                newRecord.setEntryIv(BigDecimal.valueOf(oiData.callWall().iv()));
                newRecord.setEntryDate(now);
                newRecord.setReasoning(String.format("Confirmed MTF Bearish setup. Selling OTM CE at Call Wall %s (Premium: ₹%s)",
                        callStrike, oiData.callWall().ltp()));
            }
        }
        else {
            newRecord.setActionTaken("NO_TRADE");
            newRecord.setReasoning("Trend is choppy or undefined. Awaiting clear directional structure.");
        }
    }

    // Helper Method for Cooldown Verification (Prevents Revenge Trading)
    private boolean isRecentlyBreached(Optional<AdvisoryLedger> lastClosedOpt, BigDecimal proposedStrike, LocalDateTime now) {
        if (lastClosedOpt.isPresent()) {
            AdvisoryLedger lastTrade = lastClosedOpt.get();

            // Check if the last trade was on the exact same strike
            boolean isSameStrike = proposedStrike.compareTo(lastTrade.getRecommendedStrike()) == 0;
            // Check if it was closed via a Stop Loss breach
            boolean isBreachExit = lastTrade.getActionTaken() != null && lastTrade.getActionTaken().contains("EXIT_PROXIMITY_BREACH");
            // Check if this happened within the last 2 days
            boolean isRecent = lastTrade.getTimestamp().isAfter(now.minusDays(2));

            return isSameStrike && isBreachExit && isRecent;
        }
        return false;
    }

    // =========================================================================
    // 🛡️ SAFE FETCH HELPERS
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