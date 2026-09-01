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
import com.crumbs.trade.utility.CycleUtils;
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
            log.error("⚠️ Could not acquire lock for {} within 30s. Skipping to avoid race.", name);
            return null;
        }

        try {
            return processAdvisoryInternal(name, token);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.error("🛡️ DB constraint blocked duplicate ACTIVE row for {}", name, e);
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

        CandleRequestDto dailyReq = srService.getCandleTiming("ONE_DAY", exchange);
        List<PricesIndex> dailyCandles = srService.getCandleData(dailyReq, name, indexes.getSymbol());

        if (dailyCandles == null || dailyCandles.size() < 50) {
            log.warn("[{}] Insufficient daily candles. Skipping.", name);
            return null;
        }

        BigDecimal spotPrice = dailyCandles.get(dailyCandles.size() - 1).getClose();
        MultiTimeframeTrend mtfTrend = analyzeMultiTimeframeTrend(dailyCandles, spotPrice);
        BigDecimal atr14 = calculateATR(dailyCandles, 14);

        Optional<FuturesBreakEvent> smcSignalOpt = evaluateSmcOracle(name, exchange, indexes.getSymbol(), spotPrice);

        AdvisoryOiService.AdvisoryOiData oiData;
        try {
            oiData = oiService.fetchLiveOiAndGreeks(name, exchange, indexes.getExpiry());
        } catch (Exception e) {
            log.warn("⚠️ Skipping Advisory for {}: Failed to fetch live OI & Greeks - {}", name, e.getMessage());
            return null;
        }

        String resolvedExpiry = (oiData.expiry() != null && !oiData.expiry().trim().isEmpty())
                ? oiData.expiry() : indexes.getExpiry();

        // 🚀 FIX: Always create NEW record (don't overwrite previous day's)
        Optional<AdvisoryLedger> previousRecordOpt = ledgerRepository
                .findTopBySymbolOrderByTimestampDesc(name);  // Changed query

        LocalDateTime now = LocalDateTime.now();
        CycleUtils.CycleBoundary cycle = CycleUtils.getCurrentCycleBoundary(LocalDate.now());
        AdvisoryLedger newRecord = AdvisoryLedger.builder()
                .symbol(name)
                .expiryDate(resolvedExpiry)
                .timestamp(now)
                .status("ACTIVE")  // Default status
                .cycleStartDate(cycle.startDate())
                .cycleEndDate(cycle.endDate())
                .spotPrice(spotPrice)
                .dailyTrend(mtfTrend.dailyTrend())
                .atr14(atr14)
                .isNewDay(true)  // 🚀 NEW: Mark as new record
                .build();

        // 🚀 NEW: Link to previous day's record
        if (previousRecordOpt.isPresent()) {
            AdvisoryLedger prevRecord = previousRecordOpt.get();
            newRecord.setPreviousRecordId(prevRecord.getId());
            newRecord.setPreviousStatus(prevRecord.getStatus());
            newRecord.setPreviousAction(prevRecord.getActionTaken());
        }

        if (oiData.putWall() != null) {
            newRecord.setPutWallStrike(oiData.putWall().strike());
            newRecord.setPutWallOi(BigDecimal.valueOf(oiData.putWall().openInterest()));
        }
        if (oiData.callWall() != null) {
            newRecord.setCallWallStrike(oiData.callWall().strike());
            newRecord.setCallWallOi(BigDecimal.valueOf(oiData.callWall().openInterest()));
        }

        smcSignalOpt.ifPresent(bos -> newRecord.setSmcSignal(bos.getBreakType()));

        // DECIDE: New position or hold/exit existing?
        AdvisoryLedger prevRecord = previousRecordOpt.orElse(null);

        // 🎯 If expiry changed (new month), treat as fresh entry regardless of previous
        boolean expiryChanged = prevRecord != null &&
                !prevRecord.getExpiryDate().equals(resolvedExpiry);

        // 🔧 FIX 1: Also check if previous position is CLOSED (HISTORY status)
        // This prevents evaluateHoldOrExit() from being called on already-closed positions
        boolean prevPositionClosed = prevRecord != null && "HISTORY".equals(prevRecord.getStatus());

        if (previousRecordOpt.isEmpty() || expiryChanged || prevPositionClosed || "NO_TRADE".equals(prevRecord.getActionTaken())) {
            // New month OR no previous position OR previous position already closed OR blocked entry → Fresh entry evaluation
            log.info("🔄 FRESH ENTRY PATH for {}: ", name);
            if (previousRecordOpt.isEmpty()) log.info("  └─ No previous record");
            else if (expiryChanged) log.info("  └─ Expiry changed from {} to {}", prevRecord.getExpiryDate(), resolvedExpiry);
            else if (prevPositionClosed) log.info("  └─ Previous position closed (Status: HISTORY)");
            else if ("NO_TRADE".equals(prevRecord.getActionTaken())) log.info("  └─ Previous was NO_TRADE");

            evaluateNewEntry(newRecord, mtfTrend, oiData, now, spotPrice);
        } else {
            // Same expiry + ACTIVE position with valid entry → Hold or exit
            log.info("🔄 HOLD/EXIT PATH for {}: Previous action={}", name, prevRecord.getActionTaken());
            evaluateHoldOrExit(prevRecord, newRecord, spotPrice, mtfTrend, atr14, oiData, smcSignalOpt, now, exchange);
        }

        // 🚀 FIX: Save ALL records (not conditional)
        boolean shouldSave = false;
        String saveReason = "";

        if ("ACTIVE".equals(newRecord.getStatus())) {
            // Save ACTIVE records that have: NEW_ENTRY, MAINTAIN, or NO_TRADE
            if ("NEW_ENTRY".equals(newRecord.getActionTaken()) ||
                    "MAINTAIN".equals(newRecord.getActionTaken()) ||
                    "NO_TRADE".equals(newRecord.getActionTaken())) {
                shouldSave = true;
                saveReason = newRecord.getActionTaken();

                // Calculate MTM for active trades
                if (newRecord.getEntryPremium() != null &&
                        (newRecord.getActionTaken().equals("NEW_ENTRY") ||
                                newRecord.getActionTaken().equals("MAINTAIN"))) {
                    BigDecimal liveLtp = safelyFetchExitPremium(newRecord, exchange);
                    if (liveLtp != null) {
                        BigDecimal unrealizedPnl = newRecord.getEntryPremium().subtract(liveLtp);
                        newRecord.setCurrentPremium(liveLtp);
                        newRecord.setUnrealizedPnl(unrealizedPnl);
                        log.info("📈 Daily MTM for {}: Entry ₹{} | Current ₹{} | Unrealized PnL: ₹{}",
                                newRecord.getSymbol(), newRecord.getEntryPremium(), liveLtp, unrealizedPnl);
                    }
                }
            }
        } else if ("HISTORY".equals(newRecord.getStatus())) {
            // Always save exits (SL or TARGET)
            shouldSave = true;
            saveReason = newRecord.getActionTaken();
        }

        if (shouldSave) {
            ledgerRepository.save(newRecord);
            log.info("✅ SAVED RECORD ({}): {} - {}", saveReason, newRecord.getSymbol(), newRecord.getActionTaken());
        } else {
            log.warn("⊘ SKIPPED SAVE: {} - {} (no valid action)", newRecord.getSymbol(), newRecord.getActionTaken());
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
                .unrealizedPnl(newRecord.getUnrealizedPnl())
                .entryPremium(newRecord.getEntryPremium())
                .entryDelta(newRecord.getEntryDelta())
                .entryIv(newRecord.getEntryIv())
                .build();
    }

    // =========================================================================
    // PATH 1: NEW POSITION ENTRY
    // =========================================================================
    private void evaluateNewEntry(AdvisoryLedger newRecord, MultiTimeframeTrend mtfTrend,
                                  AdvisoryOiService.AdvisoryOiData oiData,
                                  LocalDateTime now, BigDecimal spotPrice) {

        BigDecimal minPremium = new BigDecimal("10.0");

        Optional<AdvisoryLedger> lastClosedOpt = ledgerRepository
                .findTopBySymbolAndStatusOrderByTimestampDesc(newRecord.getSymbol(), "HISTORY");

        // 🔧 FIX 3: Same-day re-entry guard - Block if ANY position closed today (SL or TARGET)
        if (lastClosedOpt.isPresent()) {
            LocalDate lastCloseDate = lastClosedOpt.get().getTimestamp().toLocalDate();
            if (lastCloseDate.equals(now.toLocalDate())) {
                String closureReason = lastClosedOpt.get().getActionTaken(); // SL or TARGET
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning(String.format("Position closed today (%s). Re-entry deferred to next session.", closureReason));
                newRecord.setStatus("ACTIVE");
                log.info("🛑 Same-day re-entry blocked for {} (Previous {})", newRecord.getSymbol(), closureReason);
                return;
            }
        }

        if (!mtfTrend.isAligned()) {
            newRecord.setActionTaken("NO_TRADE");
            newRecord.setReasoning(String.format("Timeframe misalignment: Daily %s, Weekly %s",
                    mtfTrend.dailyTrend(), mtfTrend.weeklyTrend()));
            newRecord.setStatus("ACTIVE");
            return;
        }

        // === BULLISH: Sell Puts (PE) ===
        if ("BULLISH".equals(mtfTrend.dailyTrend())) {
            if (oiData.putWall() == null) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning("Bullish but NO Put Wall. Market lacks structure.");
                newRecord.setStatus("ACTIVE");
                return;
            }

            BigDecimal putStrike = oiData.putWall().strike();

            if (putStrike.compareTo(spotPrice) > 0) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning(String.format("Spot (₹%s) below Put Wall (₹%s). ITM risk too high.", spotPrice, putStrike));
                newRecord.setStatus("ACTIVE");
                return;
            }

            if (isRecentlyBreached(lastClosedOpt, putStrike, now)) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning("Cooling down. This strike was recently breached.");
                newRecord.setStatus("ACTIVE");
                return;
            }

            if (oiData.putWall().ltp().compareTo(minPremium) < 0) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning(String.format("Put premium (₹%s) too low. Illiquid.", oiData.putWall().ltp()));
                newRecord.setStatus("ACTIVE");
                return;
            }

            // ✅ ENTER: Sell Put
            newRecord.setActionTaken("NEW_ENTRY");
            newRecord.setOptionType("PE");
            newRecord.setRecommendedStrike(putStrike);
            newRecord.setEntryPremium(oiData.putWall().ltp());
            newRecord.setEntryDelta(BigDecimal.valueOf(oiData.putWall().delta()));
            newRecord.setEntryIv(BigDecimal.valueOf(oiData.putWall().iv()));
            newRecord.setEntryDate(now);
            newRecord.setDaysInPosition(1);  // 🚀 NEW: Track days held
            newRecord.setStatus("ACTIVE");
            newRecord.setReasoning(String.format("BULLISH: Selling PE at ₹%s (Premium: ₹%s)", putStrike, oiData.putWall().ltp()));
        }
        // === BEARISH: Sell Calls (CE) ===
        else if ("BEARISH".equals(mtfTrend.dailyTrend())) {
            if (oiData.callWall() == null) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning("Bearish but NO Call Wall. Market lacks structure.");
                newRecord.setStatus("ACTIVE");
                return;
            }

            BigDecimal callStrike = oiData.callWall().strike();

            if (callStrike.compareTo(spotPrice) < 0) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning(String.format("Spot (₹%s) above Call Wall (₹%s). ITM risk too high.", spotPrice, callStrike));
                newRecord.setStatus("ACTIVE");
                return;
            }

            if (isRecentlyBreached(lastClosedOpt, callStrike, now)) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning("Cooling down. This strike was recently breached.");
                newRecord.setStatus("ACTIVE");
                return;
            }

            if (oiData.callWall().ltp().compareTo(minPremium) < 0) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning(String.format("Call premium (₹%s) too low. Illiquid.", oiData.callWall().ltp()));
                newRecord.setStatus("ACTIVE");
                return;
            }

            // ✅ ENTER: Sell Call
            newRecord.setActionTaken("NEW_ENTRY");
            newRecord.setOptionType("CE");
            newRecord.setRecommendedStrike(callStrike);
            newRecord.setEntryPremium(oiData.callWall().ltp());
            newRecord.setEntryDelta(BigDecimal.valueOf(oiData.callWall().delta()));
            newRecord.setEntryIv(BigDecimal.valueOf(oiData.callWall().iv()));
            newRecord.setEntryDate(now);
            newRecord.setDaysInPosition(1);  // 🚀 NEW: Track days held
            newRecord.setStatus("ACTIVE");
            newRecord.setReasoning(String.format("BEARISH: Selling CE at ₹%s (Premium: ₹%s)", callStrike, oiData.callWall().ltp()));
        }
        else {
            newRecord.setActionTaken("NO_TRADE");
            newRecord.setReasoning("Trend undefined. Waiting for clarity.");
            newRecord.setStatus("ACTIVE");
        }
    }

    // =========================================================================
    // PATH 2: HOLD OR EXIT EXISTING POSITION
    // =========================================================================
    private void evaluateHoldOrExit(AdvisoryLedger prev, AdvisoryLedger current, BigDecimal spotPrice,
                                    MultiTimeframeTrend mtfTrend, BigDecimal atr14,
                                    AdvisoryOiService.AdvisoryOiData oiData,
                                    Optional<FuturesBreakEvent> smcSignalOpt,
                                    LocalDateTime now, String exchange) {

        // 🔧 FIX 2: Defensive check - position must have valid entry details
        // This prevents SL logic from executing on NO_TRADE records or records with missing data
        if (prev.getEntryPremium() == null || prev.getRecommendedStrike() == null || prev.getOptionType() == null) {
            log.warn("⚠️ Invalid position state for {}. Entry details missing (EntryPremium={}, Strike={}, Type={}). " +
                            "Treating as fresh entry.",
                    prev.getSymbol(), prev.getEntryPremium(), prev.getRecommendedStrike(), prev.getOptionType());
            current.setActionTaken("NO_TRADE");
            current.setStatus("ACTIVE");
            current.setReasoning("Previous position has no valid entry details. Cannot evaluate hold/exit.");
            return;
        }

        // 🚀 FIX: DON'T copy ID - create new record!
        // current.setId(prev.getId());  ❌ REMOVED

        // Lock in original position specs
        current.setOptionType(prev.getOptionType());
        current.setRecommendedStrike(prev.getRecommendedStrike());
        current.setExpiryDate(prev.getExpiryDate());
        current.setEntryPremium(prev.getEntryPremium());
        current.setEntryDelta(prev.getEntryDelta());
        current.setEntryIv(prev.getEntryIv());
        current.setEntryDate(prev.getEntryDate() != null ? prev.getEntryDate() : prev.getTimestamp());

        // 🚀 NEW: Track days held
        int daysHeld = (int) java.time.temporal.ChronoUnit.DAYS.between(
                current.getEntryDate().toLocalDate(),
                now.toLocalDate()
        ) + 1;
        current.setDaysInPosition(daysHeld);

        // ===================================================================
        // GUARD 1: Proximity Stop Loss
        // ===================================================================
        BigDecimal safeBuffer = atr14.multiply(new BigDecimal("1.25"));
        boolean ceBreached = "CE".equalsIgnoreCase(prev.getOptionType()) &&
                spotPrice.compareTo(prev.getRecommendedStrike().subtract(safeBuffer)) >= 0;
        boolean peBreached = "PE".equalsIgnoreCase(prev.getOptionType()) &&
                spotPrice.compareTo(prev.getRecommendedStrike().add(safeBuffer)) <= 0;

        if (ceBreached || peBreached) {
            executeExit(current, exchange, "SL", "Proximity stop loss hit!");
            return;
        }

        // ===================================================================
        // GUARD 2: SMC Structural Reversal
        // ===================================================================
        if (smcSignalOpt.isPresent()) {
            FuturesBreakEvent bos = smcSignalOpt.get();
            boolean reversal = ("PE".equalsIgnoreCase(prev.getOptionType()) && "BREAKDOWN".equalsIgnoreCase(bos.getBreakType())) ||
                    ("CE".equalsIgnoreCase(prev.getOptionType()) && "BREAKOUT".equalsIgnoreCase(bos.getBreakType()));

            if (reversal) {
                executeExit(current, exchange, "SL", "SMC reversal signal!");
                return;
            }
        }

        // ===================================================================
        // GUARD 3: Daily Trend Flip
        // ===================================================================
        if (prev.getDailyTrend() != null && !prev.getDailyTrend().equals(mtfTrend.dailyTrend())) {
            executeExit(current, exchange, "SL", "Trend flipped!");
            return;
        }

        // ===================================================================
        // GUARD 4: Wall Migration (Smart SL vs TARGET)
        // ===================================================================
        boolean wallMigrated = false;
        String wallExitReason = "";

        if ("PE".equalsIgnoreCase(prev.getOptionType()) && oiData.putWall() != null) {
            if (prev.getPutWallStrike() != null &&
                    prev.getPutWallStrike().compareTo(oiData.putWall().strike()) != 0) {
                wallMigrated = true;

                // Wall moved UP (away from spot) = FAVORABLE = TARGET
                if (oiData.putWall().strike().compareTo(prev.getPutWallStrike()) > 0) {
                    wallExitReason = "TARGET";
                    log.info("🎯 TARGET HIT on {}: Put Wall moved UP from ₹{} to ₹{} (favorable)",
                            prev.getSymbol(), prev.getPutWallStrike(), oiData.putWall().strike());
                } else {
                    // Wall moved DOWN (toward spot) = UNFAVORABLE = SL
                    wallExitReason = "SL";
                    log.info("🛑 SL on {}: Put Wall moved DOWN from ₹{} to ₹{} (unfavorable)",
                            prev.getSymbol(), prev.getPutWallStrike(), oiData.putWall().strike());
                }
            }
        } else if ("CE".equalsIgnoreCase(prev.getOptionType()) && oiData.callWall() != null) {
            if (prev.getCallWallStrike() != null &&
                    prev.getCallWallStrike().compareTo(oiData.callWall().strike()) != 0) {
                wallMigrated = true;

                // Wall moved DOWN (away from spot) = FAVORABLE = TARGET
                if (oiData.callWall().strike().compareTo(prev.getCallWallStrike()) < 0) {
                    wallExitReason = "TARGET";
                    log.info("🎯 TARGET HIT on {}: Call Wall moved DOWN from ₹{} to ₹{} (favorable)",
                            prev.getSymbol(), prev.getCallWallStrike(), oiData.callWall().strike());
                } else {
                    // Wall moved UP (toward spot) = UNFAVORABLE = SL
                    wallExitReason = "SL";
                    log.info("🛑 SL on {}: Call Wall moved UP from ₹{} to ₹{} (unfavorable)",
                            prev.getSymbol(), prev.getCallWallStrike(), oiData.callWall().strike());
                }
            }
        }

        if (wallMigrated) {
            String reason = String.format("Wall migrated - %s", wallExitReason);
            executeExit(current, exchange, wallExitReason, reason);
            return;
        }

        // ===================================================================
        // ALL GUARDS PASSED: MAINTAIN POSITION
        // ===================================================================
        current.setActionTaken("MAINTAIN");
        current.setStatus("ACTIVE");
        current.setReasoning(String.format("Holding %s %s (Day %d). Premium decay progressing safely.",
                prev.getRecommendedStrike(), prev.getOptionType(), daysHeld));
    }

    // =========================================================================
    // EXIT HANDLER (SL or TARGET)
    // =========================================================================
    private void executeExit(AdvisoryLedger current, String exchange, String action, String reason) {
        current.setActionTaken(action);
        current.setReasoning(reason);
        current.setStatus("HISTORY");

        // 🎯 At the exact moment SL is hit, fetch current LTP
        BigDecimal exitPremium = safelyFetchExitPremium(current, exchange);

        // Fallback: If fetch fails, use last known MTM (from this morning's update)
        if (exitPremium == null && current.getCurrentPremium() != null) {
            log.warn("⚠️ Real-time LTP failed. Using MTM from morning: ₹{}", current.getCurrentPremium());
            exitPremium = current.getCurrentPremium();
        }

        // Calculate PnL immediately
        if (exitPremium != null && current.getEntryPremium() != null) {
            current.setExitPremium(exitPremium);
            BigDecimal pnl = current.getEntryPremium().subtract(exitPremium);
            current.setRealizedPnl(pnl);
            log.info("💰 {} on {}: Entry ₹{} | Exit ₹{} | PnL ₹{}",
                    action, current.getSymbol(), current.getEntryPremium(), exitPremium, pnl);
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private boolean isRecentlyBreached(Optional<AdvisoryLedger> lastClosedOpt, BigDecimal proposedStrike, LocalDateTime now) {
        if (lastClosedOpt.isPresent()) {
            AdvisoryLedger lastTrade = lastClosedOpt.get();
            boolean isSameStrike = proposedStrike.compareTo(lastTrade.getRecommendedStrike()) == 0;
            boolean isBreachExit = lastTrade.getActionTaken() != null && lastTrade.getActionTaken().equals("SL");
            boolean isRecent = lastTrade.getTimestamp().isAfter(now.minusDays(2));
            return isSameStrike && isBreachExit && isRecent;
        }
        return false;
    }

    private BigDecimal safelyFetchExitPremium(AdvisoryLedger prev, String exchange) {
        if (prev.getRecommendedStrike() == null || prev.getOptionType() == null) {
            return null;
        }

        try {
            String strikeStr = prev.getRecommendedStrike().toPlainString();
            String suffix = "%" + strikeStr + prev.getOptionType();
            String optionToken = indexesRepo.findTokenByNameAndExpiryAndSymbolLike(
                    prev.getSymbol(), prev.getExpiryDate(), suffix);

            if (optionToken == null) {
                log.warn("⚠️ Token not found for {}", prev.getSymbol());
                return null;
            }

            ExchangeType exType = exchange.contains("MCX") ? ExchangeType.MCX_FO : ExchangeType.NSE_FO;
            BigDecimal exitLtp = webSocketService.getLatestLTP(exType, optionToken);

            if (exitLtp != null && exitLtp.compareTo(BigDecimal.ZERO) > 0) {
                log.info("✅ Exit LTP fetched for {}: ₹{}", prev.getSymbol(), exitLtp);
                return exitLtp;
            }

            log.warn("⚠️ Invalid LTP for {}: {}", prev.getSymbol(), exitLtp);
            return null;

        } catch (Exception e) {
            log.error("❌ Exception fetching exit LTP for {}: {}", prev.getSymbol(), e.getMessage(), e);
            return null;
        }
    }

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