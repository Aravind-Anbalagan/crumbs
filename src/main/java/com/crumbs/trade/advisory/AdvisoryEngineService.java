package com.crumbs.trade.advisory;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.CandleRequestDto;
import com.crumbs.trade.entity.FuturesBreakEvent;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.PricesIndex;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.service.AngelOneService;
import com.crumbs.trade.service.AngelWebSocketService;
import com.crumbs.trade.service.FuturesStrategyService.HourlyCandle;
import com.crumbs.trade.service.SRService;
import com.crumbs.trade.service.SmcLiteService;
import com.crumbs.trade.utility.CycleUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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
    private final AngelOneService angelOneService;
    private final AngelOne angelOne;
    private static final Map<String, Lock> SYMBOL_LOCKS = new ConcurrentHashMap<>();
    @Autowired
    @Lazy
    private AdvisoryEngineService self; // 🚀 Inject the proxy
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
            return self.processAdvisoryInternal(name, token);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.error("🛡️ DB constraint blocked duplicate ACTIVE row for {}", name, e);
            return null;
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public OptionRecommendation processAdvisoryInternal(String name, String token) {
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

        // Safely resolve expiry, falling back to the master index if API failed
        String resolvedExpiry = (oiData != null && oiData.expiry() != null && !oiData.expiry().trim().isEmpty())
                ? oiData.expiry() : (indexes.getExpiry() != null ? indexes.getExpiry() : "");

        Optional<AdvisoryLedger> previousRecordOpt = ledgerRepository
                .findTopBySymbolOrderByTimestampDesc(name);

        LocalDateTime now = LocalDateTime.now();
        CycleUtils.CycleBoundary cycle = CycleUtils.getCurrentCycleBoundary(LocalDate.now());
        AdvisoryLedger newRecord = AdvisoryLedger.builder()
                .symbol(name)
                .expiryDate(resolvedExpiry)
                .timestamp(now)
                .status("ACTIVE")
                .cycleStartDate(cycle.startDate())
                .cycleEndDate(cycle.endDate())
                .spotPrice(spotPrice)
                .dailyTrend(mtfTrend.dailyTrend())
                .atr14(atr14)
                .isNewDay(true)
                // ❌ Removed the Equity Lot Size injection here!
                .build();

        if (previousRecordOpt.isPresent()) {
            AdvisoryLedger prevRecord = previousRecordOpt.get();
            newRecord.setPreviousRecordId(prevRecord.getId());
            newRecord.setPreviousStatus(prevRecord.getStatus());
            newRecord.setPreviousAction(prevRecord.getActionTaken());
        }

        if (oiData != null && oiData.putWall() != null) {
            newRecord.setPutWallStrike(oiData.putWall().strike());
            newRecord.setPutWallOi(BigDecimal.valueOf(oiData.putWall().openInterest()));
        }
        if (oiData != null && oiData.callWall() != null) {
            newRecord.setCallWallStrike(oiData.callWall().strike());
            newRecord.setCallWallOi(BigDecimal.valueOf(oiData.callWall().openInterest()));
        }

        smcSignalOpt.ifPresent(bos -> newRecord.setSmcSignal(bos.getBreakType()));

        // =====================================================================
        // 🚀 ROUTING LOGIC (Fixed for Database constraints & API glitches)
        // =====================================================================
        // DECIDE: New position or hold/exit existing?
        AdvisoryLedger prevRecord = previousRecordOpt.orElse(null);

        // 🎯 SAFEGUARD: Only consider expiry changed if the API actually gave us a valid new expiry
        boolean expiryChanged = prevRecord != null &&
                !resolvedExpiry.isEmpty() &&
                !prevRecord.getExpiryDate().equals(resolvedExpiry);

        boolean prevPositionClosed = prevRecord != null && "HISTORY".equals(prevRecord.getStatus());

        // 🎯 CRITICAL FIX: A strict check to see if we are CURRENTLY holding an open trade
        boolean isHoldingActiveTrade = prevRecord != null && "ACTIVE".equals(prevRecord.getStatus()) &&
                ("NEW_ENTRY".equals(prevRecord.getActionTaken()) || "MAINTAIN".equals(prevRecord.getActionTaken()));

        if (isHoldingActiveTrade) {
            // PATH 2: We have an open position. We MUST route to Hold/Exit to manage it.
            // (Even if the API glitches, we must safely monitor the existing trade)
            log.info("🔄 HOLD/EXIT PATH for {}: Previous action={}", name, prevRecord.getActionTaken());
            evaluateHoldOrExit(prevRecord, newRecord, spotPrice, mtfTrend, atr14, oiData, smcSignalOpt, now, exchange);
        } else if (previousRecordOpt.isEmpty() || expiryChanged || prevPositionClosed || "NO_TRADE".equals(prevRecord.getActionTaken())) {
            // PATH 1: No open position. Look for a fresh entry.
            log.info("🔄 FRESH ENTRY PATH for {}: ", name);
            if (previousRecordOpt.isEmpty()) log.info("  └─ No previous record");
            else if (expiryChanged) log.info("  └─ Expiry changed from {} to {}", prevRecord.getExpiryDate(), resolvedExpiry);
            else if (prevPositionClosed) log.info("  └─ Previous position closed (Status: HISTORY)");
            else if ("NO_TRADE".equals(prevRecord.getActionTaken())) log.info("  └─ Previous was NO_TRADE");

            evaluateNewEntry(newRecord, mtfTrend, oiData, now, spotPrice);
        } else {
            // Fallback (Safe catch-all)
            evaluateHoldOrExit(prevRecord, newRecord, spotPrice, mtfTrend, atr14, oiData, smcSignalOpt, now, exchange);
        }

        // 🚀 MTM PnL & Saving Logic
        boolean shouldSave = false;
        String saveReason = "";

        if ("ACTIVE".equals(newRecord.getStatus())) {
            if ("NEW_ENTRY".equals(newRecord.getActionTaken()) ||
                    "MAINTAIN".equals(newRecord.getActionTaken()) ||
                    "NO_TRADE".equals(newRecord.getActionTaken())) {
                shouldSave = true;
                saveReason = newRecord.getActionTaken();

                // Calculate MTM (Absolute ₹) for active trades
                if (newRecord.getEntryPremium() != null &&
                        (newRecord.getActionTaken().equals("NEW_ENTRY") ||
                                newRecord.getActionTaken().equals("MAINTAIN"))) {

                    BigDecimal liveLtp = safelyFetchExitPremium(newRecord, exchange);
                    if (liveLtp != null) {
                        BigDecimal pointsPnl = newRecord.getEntryPremium().subtract(liveLtp);
                        int safeLotSize = newRecord.getLotSize() != null ? newRecord.getLotSize() : 1;
                        BigDecimal absolutePnl = pointsPnl.multiply(BigDecimal.valueOf(safeLotSize));

                        newRecord.setCurrentPremium(liveLtp);
                        newRecord.setUnrealizedPnl(absolutePnl);

                        log.info("📈 Daily MTM for {}: Entry ₹{} | Current ₹{} | Unrealized PnL: ₹{}",
                                newRecord.getSymbol(), newRecord.getEntryPremium(), liveLtp, absolutePnl);
                    }
                }
            }
        } else if ("HISTORY".equals(newRecord.getStatus())) {
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
            if (oiData == null || oiData.putWall() == null) {
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

            // 🚀 Fetch correct F&O Lot Size immediately
            Indexes optIndex = getOptionIndexMetadata(newRecord);
            newRecord.setLotSize(optIndex != null ? Integer.valueOf(optIndex.getLotsize()) : 1);

            newRecord.setEntryPremium(oiData.putWall().ltp());
            newRecord.setEntryDelta(BigDecimal.valueOf(oiData.putWall().delta()));
            newRecord.setEntryIv(BigDecimal.valueOf(oiData.putWall().iv()));
            newRecord.setEntryDate(now);
            newRecord.setDaysInPosition(1);
            newRecord.setStatus("ACTIVE");
            newRecord.setReasoning(String.format("BULLISH: Selling PE at ₹%s (Premium: ₹%s)", putStrike, oiData.putWall().ltp()));
        }
        // === BEARISH: Sell Calls (CE) ===
        else if ("BEARISH".equals(mtfTrend.dailyTrend())) {
            if (oiData == null || oiData.callWall() == null) {
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

            // 🚀 Fetch correct F&O Lot Size immediately
            Indexes optIndex = getOptionIndexMetadata(newRecord);
            newRecord.setLotSize(optIndex != null ? Integer.valueOf(optIndex.getLotsize()) : 1);

            newRecord.setEntryPremium(oiData.callWall().ltp());
            newRecord.setEntryDelta(BigDecimal.valueOf(oiData.callWall().delta()));
            newRecord.setEntryIv(BigDecimal.valueOf(oiData.callWall().iv()));
            newRecord.setEntryDate(now);
            newRecord.setDaysInPosition(1);
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

        if (prev.getEntryPremium() == null || prev.getRecommendedStrike() == null || prev.getOptionType() == null) {
            log.warn("⚠️ Invalid position state for {}. Entry details missing. Treating as NO_TRADE.", prev.getSymbol());
            current.setActionTaken("NO_TRADE");
            current.setStatus("ACTIVE");
            current.setReasoning("Previous position has no valid entry details. Cannot evaluate hold/exit.");
            return;
        }

        // Lock in original position specs
        current.setOptionType(prev.getOptionType());
        current.setRecommendedStrike(prev.getRecommendedStrike());
        current.setExpiryDate(prev.getExpiryDate());
        current.setEntryPremium(prev.getEntryPremium());
        current.setEntryDelta(prev.getEntryDelta());
        current.setEntryIv(prev.getEntryIv());
        current.setEntryDate(prev.getEntryDate() != null ? prev.getEntryDate() : prev.getTimestamp());
        current.setCurrentPremium(prev.getCurrentPremium());
        current.setLotSize(prev.getLotSize());

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

        if ("PE".equalsIgnoreCase(prev.getOptionType()) && oiData != null && oiData.putWall() != null) {
            if (prev.getPutWallStrike() != null &&
                    prev.getPutWallStrike().compareTo(oiData.putWall().strike()) != 0) {
                wallMigrated = true;

                if (oiData.putWall().strike().compareTo(prev.getPutWallStrike()) > 0) {
                    wallExitReason = "TARGET";
                    log.info("🎯 TARGET HIT on {}: Put Wall moved UP from ₹{} to ₹{}",
                            prev.getSymbol(), prev.getPutWallStrike(), oiData.putWall().strike());
                } else {
                    wallExitReason = "SL";
                    log.info("🛑 SL on {}: Put Wall moved DOWN from ₹{} to ₹{}",
                            prev.getSymbol(), prev.getPutWallStrike(), oiData.putWall().strike());
                }
            }
        } else if ("CE".equalsIgnoreCase(prev.getOptionType()) && oiData != null && oiData.callWall() != null) {
            if (prev.getCallWallStrike() != null &&
                    prev.getCallWallStrike().compareTo(oiData.callWall().strike()) != 0) {
                wallMigrated = true;

                if (oiData.callWall().strike().compareTo(prev.getCallWallStrike()) < 0) {
                    wallExitReason = "TARGET";
                    log.info("🎯 TARGET HIT on {}: Call Wall moved DOWN from ₹{} to ₹{}",
                            prev.getSymbol(), prev.getCallWallStrike(), oiData.callWall().strike());
                } else {
                    wallExitReason = "SL";
                    log.info("🛑 SL on {}: Call Wall moved UP from ₹{} to ₹{}",
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

        BigDecimal exitPremium = safelyFetchExitPremium(current, exchange);

        if (exitPremium == null) {
            BigDecimal fallbackPrice = current.getCurrentPremium() != null
                    ? current.getCurrentPremium()
                    : current.getEntryPremium();

            log.warn("⚠️ Real-time LTP failed. Using fallback price: ₹{}", fallbackPrice);
            exitPremium = fallbackPrice;
        }

        if (exitPremium != null && current.getEntryPremium() != null) {
            current.setExitPremium(exitPremium);

            BigDecimal pointsPnl = current.getEntryPremium().subtract(exitPremium);
            int safeLotSize = current.getLotSize() != null ? current.getLotSize() : 1;
            BigDecimal absolutePnl = pointsPnl.multiply(BigDecimal.valueOf(safeLotSize));

            current.setRealizedPnl(absolutePnl);
            log.info("💰 {} on {}: Entry ₹{} | Exit ₹{} | PnL ₹{} (Points: {})",
                    action, current.getSymbol(), current.getEntryPremium(), exitPremium, absolutePnl, pointsPnl);
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
            Indexes optionIndex = getOptionIndexMetadata(prev);

            if (optionIndex == null || optionIndex.getToken() == null) {
                log.warn("⚠️ Token/Metadata not found for {}", prev.getSymbol());
                return null;
            }

            String optionToken = optionIndex.getToken();
            String tradingSymbol = optionIndex.getSymbol();

            String angelExchange = exchange.contains("MCX") ? "MCX" : "NFO";
            ExchangeType exType = exchange.contains("MCX") ? ExchangeType.MCX_FO : ExchangeType.NSE_FO;

            BigDecimal exitLtp = webSocketService.getLatestLTP(exType, optionToken);

            if (exitLtp != null && exitLtp.compareTo(BigDecimal.ZERO) > 0) {
                log.info("✅ Exit LTP fetched via WebSocket for {}: ₹{}", tradingSymbol, exitLtp);
                return exitLtp;
            }

            log.warn("⏳ WebSocket LTP missing for {}. Forcing REST API fetch...", tradingSymbol);
            try {
                SmartConnect smartConnect = angelOne.signIn();
                BigDecimal restLtp = angelOneService.getcurrentPrice(smartConnect, angelExchange, tradingSymbol, optionToken);

                if (restLtp != null && restLtp.compareTo(BigDecimal.ZERO) > 0) {
                    log.info("✅ REST API successfully retrieved LTP for {}: ₹{}", tradingSymbol, restLtp);
                    return restLtp;
                }
            } catch (Exception restEx) {
                log.error("❌ REST API LTP fetch failed for {}: {}", tradingSymbol, restEx.getMessage());
            }

            log.warn("⚠️ Invalid LTP from both WebSocket and REST API for {}.", tradingSymbol);
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
        if (candles == null || candles.isEmpty()) {
            log.warn("❌ Empty candle list. Returning default ATR.");
            return BigDecimal.TEN;
        }

        if (candles.size() < period) {
            log.warn("⚠️ Only {} candles available. Need {}. Using range-based estimate.", candles.size(), period);
            return estimateATRFromRange(candles);
        }

        BigDecimal trSum = BigDecimal.ZERO;
        int validTRs = 0;

        for (int i = candles.size() - period; i < candles.size(); i++) {
            PricesIndex candle = candles.get(i);
            PricesIndex prevCandle = candles.get(i - 1);

            // Validate data
            if (candle.getHigh() == null || candle.getLow() == null ||
                    prevCandle.getClose() == null) {
                log.warn("⚠️ Null OHLC at index {}. Skipping.", i);
                continue;
            }

            BigDecimal high = candle.getHigh();
            BigDecimal low = candle.getLow();
            BigDecimal prevClose = prevCandle.getClose();

            // Sanity check
            if (high.compareTo(low) < 0) {
                log.error("❌ Corrupted candle at {}: High={}, Low={}. Skipping.", i, high, low);
                continue;
            }

            BigDecimal tr = high.subtract(low)
                    .max(high.subtract(prevClose).abs())
                    .max(low.subtract(prevClose).abs());

            trSum = trSum.add(tr);
            validTRs++;
        }

        if (validTRs == 0) {
            log.warn("Using range-based fallback...");
            return estimateATRFromRange(candles);  // Better than hardcoded ₹10
        }

        return trSum.divide(new BigDecimal(validTRs), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal estimateATRFromRange(List<PricesIndex> candles) {
        BigDecimal sumRange = BigDecimal.ZERO;
        int count = 0;
        for (PricesIndex c : candles) {
            if (c.getHigh() != null && c.getLow() != null) {
                sumRange = sumRange.add(c.getHigh().subtract(c.getLow()));
                count++;
            }
        }
        return count > 0 ? sumRange.divide(new BigDecimal(count), 2, RoundingMode.HALF_UP) : BigDecimal.TEN;
    }

    // =========================================================================
    // END OF DAY (EOD) PNL SETTLEMENT (Optimized Lazy Loading & Auto-Repair)
    // =========================================================================
    @Transactional
    public void processEodPnl(String name, String token) {

        Optional<AdvisoryLedger> latestRecordOpt = ledgerRepository.findTopBySymbolOrderByTimestampDesc(name);
        if (latestRecordOpt.isEmpty()) {
            return;
        }

        AdvisoryLedger record = latestRecordOpt.get();

        if (record.getEntryPremium() == null) {
            return;
        }

        boolean needsApiFetch = "ACTIVE".equals(record.getStatus()) ||
                ("HISTORY".equals(record.getStatus()) && record.getExitPremium() == null);

        String exchange = "NFO";

        Indexes optionIndex = getOptionIndexMetadata(record);

        if (optionIndex != null) {
            exchange = optionIndex.getExchange();

            if (record.getLotSize() == null || record.getLotSize() <= 1) {
                try {
                    record.setLotSize(Integer.valueOf(optionIndex.getLotsize()));
                    log.info("🔧 Auto-repaired F&O Lot Size for {} -> {}", name, record.getLotSize());
                } catch (Exception e) {
                    record.setLotSize(1);
                }
            }
        } else if (needsApiFetch) {
            log.warn("⚠️ Cannot fetch EOD API price for {}: Option Index not found.", name);
            return;
        }

        if ("ACTIVE".equals(record.getStatus())) {
            BigDecimal closingLtp = safelyFetchExitPremium(record, exchange);

            if (closingLtp != null) {
                BigDecimal pointsPnl = record.getEntryPremium().subtract(closingLtp);
                BigDecimal absolutePnl = pointsPnl.multiply(BigDecimal.valueOf(record.getLotSize()));

                record.setCurrentPremium(closingLtp);
                record.setUnrealizedPnl(absolutePnl);

                ledgerRepository.save(record);
                log.info("📊 EOD MTM Updated for {}: Close ₹{} | Unrealized PnL: ₹{}",
                        record.getSymbol(), closingLtp, absolutePnl);
            }
        }
        else if ("HISTORY".equals(record.getStatus())) {
            if (record.getExitPremium() == null) {
                BigDecimal closingLtp = safelyFetchExitPremium(record, exchange);

                if (closingLtp != null) {
                    record.setExitPremium(closingLtp);

                    BigDecimal pointsPnl = record.getEntryPremium().subtract(closingLtp);
                    BigDecimal absolutePnl = pointsPnl.multiply(BigDecimal.valueOf(record.getLotSize()));

                    record.setRealizedPnl(absolutePnl);

                    ledgerRepository.save(record);
                    log.info("📊 EOD Fallback Settlement for closed trade {} ({}): Exit ₹{} | Realized PnL: ₹{}",
                            record.getSymbol(), record.getActionTaken(), closingLtp, absolutePnl);
                }
            }
            else if (record.getRealizedPnl() == null || record.getRealizedPnl().abs().compareTo(new BigDecimal("200")) < 0) {
                BigDecimal pointsPnl = record.getEntryPremium().subtract(record.getExitPremium());
                BigDecimal absolutePnl = pointsPnl.multiply(BigDecimal.valueOf(record.getLotSize()));

                record.setRealizedPnl(absolutePnl);
                ledgerRepository.save(record);

                log.info("🔧 Auto-repaired missing/points Realized PnL for {} ({}): ₹{}",
                        record.getSymbol(), record.getActionTaken(), absolutePnl);
            }
        }
    }

    // =========================================================================
    // HELPER: Fetch exact F&O Option Metadata (for actual Lot Size & Exchange)
    // =========================================================================
    private Indexes getOptionIndexMetadata(AdvisoryLedger record) {
        if (record.getRecommendedStrike() == null || record.getOptionType() == null || record.getExpiryDate() == null) {
            return null;
        }
        try {
            String rawExpiry = record.getExpiryDate().trim();
            String formattedExpiry = rawExpiry;

            if (rawExpiry.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                LocalDate parsedDate = LocalDate.parse(rawExpiry);
                formattedExpiry = parsedDate.format(java.time.format.DateTimeFormatter.ofPattern("ddMMMyyyy", java.util.Locale.ENGLISH)).toUpperCase();
            }

            String strikeStr = record.getRecommendedStrike().stripTrailingZeros().toPlainString();
            String suffix = "%" + strikeStr + record.getOptionType();

            // 🚀 Enforce NFO exchange only
            String optionToken = indexesRepo.findNfoTokenByNameAndExpiryAndSymbolLike(
                    record.getSymbol(), formattedExpiry, suffix);

            if (optionToken != null) {
                return indexesRepo.findByToken(optionToken);
            }
        } catch (Exception e) {
            log.error("❌ Error fetching option index for {}: {}", record.getSymbol(), e.getMessage());
        }
        return null;
    }
}