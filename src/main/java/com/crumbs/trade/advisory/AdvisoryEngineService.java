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
    private AdvisoryEngineService self; // 🚀 Inject the proxy for transactional boundaries

    public record MultiTimeframeTrend(String dailyTrend, String weeklyTrend, boolean isAligned) {}

    public OptionRecommendation processAdvisory(String name, String token) {
        Lock lock = SYMBOL_LOCKS.computeIfAbsent(name, k -> new ReentrantLock());

        boolean acquired;
        try {
            acquired = lock.tryLock(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ [LOCK TIMEOUT] Interrupted while waiting for lock on {}. Skipping run.", name);
            return null;
        }

        if (!acquired) {
            log.warn("⚠️ [RACE AVOIDED] Could not acquire lock for {} within 30s. Skipping to prevent duplicate processing.", name);
            return null;
        }

        try {
            return self.processAdvisoryInternal(name, token);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.warn("🛡️ [DB GUARD] Database constraint blocked duplicate ACTIVE row for {}", name);
            return null;
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public OptionRecommendation processAdvisoryInternal(String name, String token) {
        log.info("🧠 ========================================================");
        log.info("🧠 [ENGINE START] Analyzing Stateful Advisory for: {} (Token: {})", name, token);

        Indexes indexes = indexesRepo.findByToken(token);
        if (indexes == null) {
            log.warn("⏭️ [SKIP] No Index metadata found for token {}", token);
            return null;
        }

        String exchange = indexes.getExchange();

        CandleRequestDto dailyReq = srService.getCandleTiming("ONE_DAY", exchange);
        List<PricesIndex> dailyCandles = srService.getCandleData(dailyReq, name, indexes.getSymbol());

        if (dailyCandles == null || dailyCandles.size() < 50) {
            log.warn("⏭️ [SKIP] Insufficient daily candles for {} (Found: {}).", name, dailyCandles != null ? dailyCandles.size() : 0);
            return null;
        }

        BigDecimal spotPrice = dailyCandles.get(dailyCandles.size() - 1).getClose();
        MultiTimeframeTrend mtfTrend = analyzeMultiTimeframeTrend(dailyCandles, spotPrice);
        BigDecimal atr14 = calculateATR(dailyCandles, 14);

        log.info("📊 [MARKET CONTEXT] Spot: ₹{} | Daily Trend: {} | Weekly Trend: {} | ATR: {}",
                spotPrice, mtfTrend.dailyTrend(), mtfTrend.weeklyTrend(), atr14);

        Optional<FuturesBreakEvent> smcSignalOpt = evaluateSmcOracle(name, exchange, indexes.getSymbol(), spotPrice);

        AdvisoryOiService.AdvisoryOiData oiData;
        try {
            String fnoExchange = exchange.contains("MCX") ? "MCX" : "NFO";
            oiData = oiService.fetchLiveOiAndGreeks(name, fnoExchange, indexes.getExpiry());
        } catch (Exception e) {
            log.error("⚠️ [API FAIL] Failed to fetch Live OI for {}. Error: {}", name, e.getMessage());
            return null;
        }

        String resolvedExpiry = (oiData != null && oiData.expiry() != null && !oiData.expiry().trim().isEmpty())
                ? oiData.expiry() : (indexes.getExpiry() != null ? indexes.getExpiry() : "");

        Optional<AdvisoryLedger> previousRecordOpt = ledgerRepository.findTopBySymbolOrderByTimestampDesc(name);

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

        smcSignalOpt.ifPresent(bos -> {
            newRecord.setSmcSignal(bos.getBreakType());
            log.info("🧭 [SMC ORACLE] {} detected for {}", bos.getBreakType(), name);
        });

        // =====================================================================
        // ROUTING LOGIC
        // =====================================================================
        AdvisoryLedger prevRecord = previousRecordOpt.orElse(null);

        boolean expiryChanged = prevRecord != null && !resolvedExpiry.isEmpty() && !prevRecord.getExpiryDate().equals(resolvedExpiry);
        boolean prevPositionClosed = prevRecord != null && "HISTORY".equals(prevRecord.getStatus());
        boolean isHoldingActiveTrade = prevRecord != null && "ACTIVE".equals(prevRecord.getStatus()) &&
                ("NEW_ENTRY".equals(prevRecord.getActionTaken()) || "MAINTAIN".equals(prevRecord.getActionTaken()));

        if (isHoldingActiveTrade) {
            log.info("🔄 [PATH 2: HOLD/EXIT] Evaluating existing position for {}. Previous action: {}", name, prevRecord.getActionTaken());
            evaluateHoldOrExit(prevRecord, newRecord, spotPrice, mtfTrend, atr14, oiData, smcSignalOpt, now, exchange);
        } else if (previousRecordOpt.isEmpty() || expiryChanged || prevPositionClosed || "NO_TRADE".equals(prevRecord.getActionTaken())) {
            log.info("✨ [PATH 1: NEW ENTRY] Scanning for fresh setup on {}...", name);
            evaluateNewEntry(newRecord, mtfTrend, oiData, now, spotPrice);
        } else {
            log.warn("⚠️ [FALLBACK PATH] Unhandled state for {}. Forcing hold/exit evaluation.", name);
            evaluateHoldOrExit(prevRecord, newRecord, spotPrice, mtfTrend, atr14, oiData, smcSignalOpt, now, exchange);
        }

        // =====================================================================
        // PNL CALCULATION & SAVING
        // =====================================================================
        boolean shouldSave = false;
        String saveReason = "";

        if ("ACTIVE".equals(newRecord.getStatus())) {
            if ("NEW_ENTRY".equals(newRecord.getActionTaken()) || "MAINTAIN".equals(newRecord.getActionTaken()) || "NO_TRADE".equals(newRecord.getActionTaken())) {
                shouldSave = true;
                saveReason = newRecord.getActionTaken();

                // Calculate MTM for active trades
                if (newRecord.getEntryPremium() != null && !newRecord.getActionTaken().equals("NO_TRADE")) {
                    BigDecimal liveLtp = safelyFetchExitPremium(newRecord, exchange);
                    if (liveLtp != null) {
                        BigDecimal pointsPnl = newRecord.getEntryPremium().subtract(liveLtp);
                        int safeLotSize = newRecord.getLotSize() != null ? newRecord.getLotSize() : 1;
                        BigDecimal absolutePnl = pointsPnl.multiply(BigDecimal.valueOf(safeLotSize));

                        newRecord.setCurrentPremium(liveLtp);
                        newRecord.setUnrealizedPnl(absolutePnl);
                        log.info("📈 [MTM PNL] {}: Entry ₹{} | Current ₹{} | Unrealized PnL: ₹{}", newRecord.getSymbol(), newRecord.getEntryPremium(), liveLtp, absolutePnl);
                    }
                }
            }
        } else if ("HISTORY".equals(newRecord.getStatus())) {
            shouldSave = true;
            saveReason = newRecord.getActionTaken();
        }

        if (shouldSave) {
            ledgerRepository.save(newRecord);
            log.info("💾 [DB SAVE] Record saved for {}: Action [{}]", newRecord.getSymbol(), saveReason);
        } else {
            log.warn("⊘ [SKIP SAVE] No valid action generated for {}. Action: {}", newRecord.getSymbol(), newRecord.getActionTaken());
        }

        log.info("🏁 [ENGINE STOP] Completed scan for {}\n", name);

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

        // Same Day Re-entry Block
        if (lastClosedOpt.isPresent() && lastClosedOpt.get().getTimestamp().toLocalDate().equals(now.toLocalDate())) {
            newRecord.setActionTaken("NO_TRADE");
            newRecord.setReasoning("Position closed today. Re-entry deferred to next session.");
            newRecord.setStatus("ACTIVE");
            log.info("🛑 [BLOCKED] Same-day re-entry blocked for {}", newRecord.getSymbol());
            return;
        }

        // Trend Alignment Check
        if (!mtfTrend.isAligned()) {
            newRecord.setActionTaken("NO_TRADE");
            newRecord.setReasoning(String.format("Timeframe misalignment: Daily %s, Weekly %s", mtfTrend.dailyTrend(), mtfTrend.weeklyTrend()));
            newRecord.setStatus("ACTIVE");
            log.info("🛑 [BLOCKED] MTF Misalignment on {}", newRecord.getSymbol());
            return;
        }

        if ("BULLISH".equals(mtfTrend.dailyTrend())) {
            if (oiData == null || oiData.putWall() == null) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning("Bullish but NO Put Wall. Market lacks structure.");
                newRecord.setStatus("ACTIVE");
                return;
            }

            BigDecimal putStrike = oiData.putWall().strike();

            if (putStrike.compareTo(spotPrice) > 0 || isRecentlyBreached(lastClosedOpt, now) || oiData.putWall().ltp().compareTo(minPremium) < 0) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning("Put Wall violates entry conditions (ITM, Illiquid, or Cooling down).");
                newRecord.setStatus("ACTIVE");
                return;
            }

            newRecord.setActionTaken("NEW_ENTRY");
            newRecord.setOptionType("PE");
            newRecord.setRecommendedStrike(putStrike);
            Indexes optIndex = getOptionIndexMetadata(newRecord);
            newRecord.setLotSize(optIndex != null ? Integer.valueOf(optIndex.getLotsize()) : 1);
            newRecord.setEntryPremium(oiData.putWall().ltp());
            newRecord.setEntryDelta(BigDecimal.valueOf(oiData.putWall().delta()));
            newRecord.setEntryIv(BigDecimal.valueOf(oiData.putWall().iv()));
            newRecord.setEntryDate(now);
            newRecord.setDaysInPosition(1);
            newRecord.setStatus("ACTIVE");
            newRecord.setReasoning(String.format("BULLISH: Selling PE at ₹%s | Entry Put OI: %s", putStrike, oiData.putWall().openInterest()));
            log.info("✅ [ENTRY OPENED] Bulls activated on {}. Selling {} PE at ₹{}", newRecord.getSymbol(), putStrike, oiData.putWall().ltp());
        }
        else if ("BEARISH".equals(mtfTrend.dailyTrend())) {
            if (oiData == null || oiData.callWall() == null) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning("Bearish but NO Call Wall. Market lacks structure.");
                newRecord.setStatus("ACTIVE");
                return;
            }

            BigDecimal callStrike = oiData.callWall().strike();

            if (callStrike.compareTo(spotPrice) < 0 || isRecentlyBreached(lastClosedOpt, now) || oiData.callWall().ltp().compareTo(minPremium) < 0) {
                newRecord.setActionTaken("NO_TRADE");
                newRecord.setReasoning("Call Wall violates entry conditions (ITM, Illiquid, or Cooling down).");
                newRecord.setStatus("ACTIVE");
                return;
            }

            newRecord.setActionTaken("NEW_ENTRY");
            newRecord.setOptionType("CE");
            newRecord.setRecommendedStrike(callStrike);
            Indexes optIndex = getOptionIndexMetadata(newRecord);
            newRecord.setLotSize(optIndex != null ? Integer.valueOf(optIndex.getLotsize()) : 1);
            newRecord.setEntryPremium(oiData.callWall().ltp());
            newRecord.setEntryDelta(BigDecimal.valueOf(oiData.callWall().delta()));
            newRecord.setEntryIv(BigDecimal.valueOf(oiData.callWall().iv()));
            newRecord.setEntryDate(now);
            newRecord.setDaysInPosition(1);
            newRecord.setStatus("ACTIVE");
            newRecord.setReasoning(String.format("BEARISH: Selling CE at ₹%s | Entry Call OI: %s", callStrike, oiData.callWall().openInterest()));
            log.info("✅ [ENTRY OPENED] Bears activated on {}. Selling {} CE at ₹{}", newRecord.getSymbol(), callStrike, oiData.callWall().ltp());
        }
    }

    // =========================================================================
    // PATH 2: HOLD OR EXIT EXISTING POSITION (MONTHLY CYCLE GOD-MODE)
    // =========================================================================
    private void evaluateHoldOrExit(AdvisoryLedger prev, AdvisoryLedger current, BigDecimal spotPrice,
                                    MultiTimeframeTrend mtfTrend, BigDecimal atr14,
                                    AdvisoryOiService.AdvisoryOiData oiData,
                                    Optional<FuturesBreakEvent> smcSignalOpt,
                                    LocalDateTime now, String exchange) {

        if (prev.getEntryPremium() == null || prev.getRecommendedStrike() == null || prev.getOptionType() == null) {
            current.setActionTaken("NO_TRADE");
            current.setStatus("ACTIVE");
            current.setReasoning("Invalid previous state details.");
            log.warn("⚠️ [INVALID STATE] Previous active trade for {} has null fields.", prev.getSymbol());
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

        int daysHeld = (int) java.time.temporal.ChronoUnit.DAYS.between(current.getEntryDate().toLocalDate(), now.toLocalDate()) + 1;
        current.setDaysInPosition(daysHeld);

        List<String> warnings = new ArrayList<>();

        // 🚀 GUARD 1: Proximity Alert (Warning)
        BigDecimal safeBuffer = atr14.multiply(new BigDecimal("1.25"));
        boolean ceBreached = "CE".equalsIgnoreCase(prev.getOptionType()) && spotPrice.compareTo(prev.getRecommendedStrike().subtract(safeBuffer)) >= 0;
        boolean peBreached = "PE".equalsIgnoreCase(prev.getOptionType()) && spotPrice.compareTo(prev.getRecommendedStrike().add(safeBuffer)) <= 0;
        if (ceBreached || peBreached) {
            warnings.add("🚨 Proximity breach");
            log.warn("🚨 [WARNING] Proximity buffer breached on {}!", prev.getSymbol());
        }

        // 🚀 GUARD 2: SMC Structural Reversal (Warning)
        if (smcSignalOpt.isPresent()) {
            FuturesBreakEvent bos = smcSignalOpt.get();
            boolean reversal = ("PE".equalsIgnoreCase(prev.getOptionType()) && "BREAKDOWN".equalsIgnoreCase(bos.getBreakType())) ||
                    ("CE".equalsIgnoreCase(prev.getOptionType()) && "BREAKOUT".equalsIgnoreCase(bos.getBreakType()));
            if (reversal) {
                warnings.add("🚨 SMC Reversal");
                log.warn("🚨 [WARNING] SMC Structural Reversal detected on {}!", prev.getSymbol());
            }
        }

        // 🚀 GUARD 3: Daily Trend Flip (Warning)
        if (prev.getDailyTrend() != null && !prev.getDailyTrend().equals(mtfTrend.dailyTrend())) {
            warnings.add("🚨 Trend flipped");
            log.warn("🚨 [WARNING] MTF Trend flipped on {} from {} to {}!", prev.getSymbol(), prev.getDailyTrend(), mtfTrend.dailyTrend());
        }

        // ===================================================================
        // 🚀 GUARD 4: VALUE TARGET (85% Premium Decay)
        // ===================================================================
        BigDecimal exitPremium = safelyFetchExitPremium(current, exchange);
        if (exitPremium == null) {
            exitPremium = current.getCurrentPremium() != null ? current.getCurrentPremium() : current.getEntryPremium();
        }

        if (exitPremium != null && prev.getEntryPremium() != null) {
            BigDecimal targetPremium = prev.getEntryPremium().multiply(new BigDecimal("0.15"));
            if (exitPremium.compareTo(targetPremium) <= 0) {
                current.setCurrentPremium(exitPremium);
                executeExit(current, exchange, "TARGET", String.format("Target hit. Premium decayed by >= 85%% (Entry: ₹%s -> Live: ₹%s). Locking in profit.", prev.getEntryPremium(), exitPremium));
                return;
            }
            current.setCurrentPremium(exitPremium); // Update live premium for MTM
        }

        // ===================================================================
        // 🚀 GUARD 5: STRUCTURAL TARGET & STOP LOSS (DUAL-VALIDATION)
        // ===================================================================
        boolean wallMigrated = false;
        String wallExitReason = "";
        String exitReasoning = "";

        if (oiData != null) {
            BigDecimal prevPutStrike = prev.getPutWallStrike();
            BigDecimal prevCallStrike = prev.getCallWallStrike();

            BigDecimal prevPutOi = prev.getPutWallOi() != null ? prev.getPutWallOi() : BigDecimal.ZERO;
            BigDecimal prevCallOi = prev.getCallWallOi() != null ? prev.getCallWallOi() : BigDecimal.ZERO;

            boolean hasLivePut = oiData.putWall() != null;
            boolean hasLiveCall = oiData.callWall() != null;

            BigDecimal livePutStrike = hasLivePut ? oiData.putWall().strike() : null;
            BigDecimal livePutOi = hasLivePut ? BigDecimal.valueOf(oiData.putWall().openInterest()) : BigDecimal.ZERO;

            BigDecimal liveCallStrike = hasLiveCall ? oiData.callWall().strike() : null;
            BigDecimal liveCallOi = hasLiveCall ? BigDecimal.valueOf(oiData.callWall().openInterest()) : BigDecimal.ZERO;

            // Ensure we have BOTH sides to do Dual-Validation
            if (hasLivePut && prevPutStrike != null && hasLiveCall && prevCallStrike != null) {

                if ("PE".equalsIgnoreCase(prev.getOptionType())) {
                    // --- BULLISH TRADE (Selling PE) ---

                    // TARGET: Support pushes up AND Resistance concedes (stays flat or moves up)
                    boolean supportRaised = livePutStrike.compareTo(prevPutStrike) > 0;
                    boolean resistanceConceded = liveCallStrike.compareTo(prevCallStrike) >= 0;

                    // SL: Support collapses AND Resistance attacks (moves down or builds OI)
                    boolean supportWeakening = livePutStrike.compareTo(prevPutStrike) < 0 || livePutOi.compareTo(prevPutOi) < 0;
                    boolean resistanceStrengthening = liveCallStrike.compareTo(prevCallStrike) < 0 || liveCallOi.compareTo(prevCallOi) > 0;

                    if (supportRaised && resistanceConceded) {
                        wallMigrated = true;
                        wallExitReason = "TARGET";
                        exitReasoning = String.format("Wall migrated. TARGET. PE shifted favorably (%s -> %s) and CE conceded.", prevPutStrike, livePutStrike);
                    } else if (supportWeakening && resistanceStrengthening) {
                        wallMigrated = true;
                        wallExitReason = "SL";
                        exitReasoning = String.format("Wall migrated. SL. PE weakening (OI: %s -> %s) AND CE strengthening (OI: %s -> %s).",
                                prevPutOi, livePutOi, prevCallOi, liveCallOi);
                    }
                } else if ("CE".equalsIgnoreCase(prev.getOptionType())) {
                    // --- BEARISH TRADE (Selling CE) ---

                    // TARGET: Resistance pushes down AND Support concedes (stays flat or moves down)
                    boolean resistanceLowered = liveCallStrike.compareTo(prevCallStrike) < 0;
                    boolean supportConceded = livePutStrike.compareTo(prevPutStrike) <= 0;

                    // SL: Resistance collapses AND Support attacks (moves up or builds OI)
                    boolean resistanceWeakening = liveCallStrike.compareTo(prevCallStrike) > 0 || liveCallOi.compareTo(prevCallOi) < 0;
                    boolean supportStrengthening = livePutStrike.compareTo(prevPutStrike) > 0 || livePutOi.compareTo(prevPutOi) > 0;

                    if (resistanceLowered && supportConceded) {
                        wallMigrated = true;
                        wallExitReason = "TARGET";
                        exitReasoning = String.format("Wall migrated. TARGET. CE shifted favorably (%s -> %s) and PE conceded.", prevCallStrike, liveCallStrike);
                    } else if (resistanceWeakening && supportStrengthening) {
                        wallMigrated = true;
                        wallExitReason = "SL";
                        exitReasoning = String.format("Wall migrated. SL. CE weakening (OI: %s -> %s) AND PE strengthening (OI: %s -> %s).",
                                prevCallOi, liveCallOi, prevPutOi, livePutOi);
                    }
                }
            }
        }

        if (wallMigrated) {
            executeExit(current, exchange, wallExitReason, exitReasoning);
            return;
        }

        // ===================================================================
        // ALL WALLS INTACT: MAINTAIN POSITION & LOG WARNINGS
        // ===================================================================
        current.setActionTaken("MAINTAIN");
        current.setStatus("ACTIVE");

        String reasoning = String.format("Holding %s %s (Day %d).", prev.getRecommendedStrike(), prev.getOptionType(), daysHeld);
        if (!warnings.isEmpty()) {
            reasoning += " [WARNINGS: " + String.join(", ", warnings) + "] Market structure intact.";
        } else {
            reasoning += " Premium decay progressing safely.";
        }
        current.setReasoning(reasoning);
        log.info("⏳ [MAINTAIN] Position held for {} (Day {}). Warnings: {}", current.getSymbol(), daysHeld, warnings.isEmpty() ? "None" : String.join(", ", warnings));
    }

    // =========================================================================
    // EXIT HANDLER
    // =========================================================================
    private void executeExit(AdvisoryLedger current, String exchange, String action, String reason) {
        current.setActionTaken(action);
        current.setReasoning(reason);
        current.setStatus("HISTORY");

        BigDecimal exitPremium = safelyFetchExitPremium(current, exchange);

        if (exitPremium == null) {
            BigDecimal fallbackPrice = current.getCurrentPremium() != null ? current.getCurrentPremium() : current.getEntryPremium();
            log.warn("⚠️ [FALLBACK PRICE] Real-time exit premium failed for {}. Using fallback: ₹{}", current.getSymbol(), fallbackPrice);
            exitPremium = fallbackPrice;
        }

        if (exitPremium != null && current.getEntryPremium() != null) {
            current.setExitPremium(exitPremium);
            BigDecimal pointsPnl = current.getEntryPremium().subtract(exitPremium);
            int safeLotSize = current.getLotSize() != null ? current.getLotSize() : 1;
            BigDecimal absolutePnl = pointsPnl.multiply(BigDecimal.valueOf(safeLotSize));
            current.setRealizedPnl(absolutePnl);

            log.info("💰 [TRADE CLOSED] {}: Action [{}] | PnL: ₹{} (Points: {})",
                    current.getSymbol(), action, absolutePnl, pointsPnl);
        }
    }

    // =========================================================================
    // HELPER: PREVENT SL CHURNING
    // =========================================================================
    private boolean isRecentlyBreached(Optional<AdvisoryLedger> lastClosedOpt, LocalDateTime now) {
        if (lastClosedOpt.isPresent()) {
            AdvisoryLedger lastTrade = lastClosedOpt.get();
            // 🚀 The strike check is removed. If ANY wall collapses and triggers an SL,
            // we pause the entire symbol for 2 days to avoid chasing a falling knife.
            boolean isBreachExit = "SL".equals(lastTrade.getActionTaken());
            boolean isRecent = lastTrade.getTimestamp().isAfter(now.minusDays(2));

            if (isBreachExit && isRecent) {
                log.warn("⏳ [COOLDOWN ACTIVE] Blocked entry for {}. Last SL hit on {}.", lastTrade.getSymbol(), lastTrade.getTimestamp());
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private BigDecimal safelyFetchExitPremium(AdvisoryLedger prev, String exchange) {
        if (prev.getRecommendedStrike() == null || prev.getOptionType() == null) return null;

        try {
            Indexes optionIndex = getOptionIndexMetadata(prev);
            if (optionIndex == null || optionIndex.getToken() == null) return null;

            String optionToken = optionIndex.getToken();
            String tradingSymbol = optionIndex.getSymbol();
            String angelExchange = exchange.contains("MCX") ? "MCX" : "NFO";
            ExchangeType exType = exchange.contains("MCX") ? ExchangeType.MCX_FO : ExchangeType.NSE_FO;

            BigDecimal exitLtp = webSocketService.getLatestLTP(exType, optionToken);
            if (exitLtp != null && exitLtp.compareTo(BigDecimal.ZERO) > 0) return exitLtp;

            SmartConnect smartConnect = angelOne.signIn();
            BigDecimal restLtp = angelOneService.getcurrentPrice(smartConnect, angelExchange, tradingSymbol, optionToken);
            if (restLtp != null && restLtp.compareTo(BigDecimal.ZERO) > 0) return restLtp;

        } catch (Exception e) {
            log.error("❌ Exception fetching exit LTP for {}: {}", prev.getSymbol(), e.getMessage());
        }
        return null;
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
        if (candles == null || candles.size() < period) return estimateATRFromRange(candles);

        BigDecimal trSum = BigDecimal.ZERO;
        int validTRs = 0;
        for (int i = candles.size() - period; i < candles.size(); i++) {
            PricesIndex candle = candles.get(i);
            PricesIndex prevCandle = candles.get(i - 1);
            if (candle.getHigh() == null || candle.getLow() == null || prevCandle.getClose() == null) continue;

            BigDecimal high = candle.getHigh();
            BigDecimal low = candle.getLow();
            BigDecimal prevClose = prevCandle.getClose();

            BigDecimal tr = high.subtract(low).max(high.subtract(prevClose).abs()).max(low.subtract(prevClose).abs());
            trSum = trSum.add(tr);
            validTRs++;
        }
        return validTRs > 0 ? trSum.divide(new BigDecimal(validTRs), 2, RoundingMode.HALF_UP) : estimateATRFromRange(candles);
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

    @Transactional
    public void processEodPnl(String name, String token) {
        Optional<AdvisoryLedger> latestRecordOpt = ledgerRepository.findTopBySymbolOrderByTimestampDesc(name);
        if (latestRecordOpt.isEmpty()) return;
        AdvisoryLedger record = latestRecordOpt.get();
        if (record.getEntryPremium() == null) return;

        boolean needsApiFetch = "ACTIVE".equals(record.getStatus()) || ("HISTORY".equals(record.getStatus()) && record.getExitPremium() == null);
        String exchange = "NFO";
        Indexes optionIndex = getOptionIndexMetadata(record);

        if (optionIndex != null) {
            exchange = optionIndex.getExchange();
            if (record.getLotSize() == null || record.getLotSize() <= 1) {
                try {
                    record.setLotSize(Integer.valueOf(optionIndex.getLotsize()));
                } catch (Exception e) {
                    record.setLotSize(1);
                }
            }
        } else if (needsApiFetch) return;

        if ("ACTIVE".equals(record.getStatus())) {
            BigDecimal closingLtp = safelyFetchExitPremium(record, exchange);
            if (closingLtp != null) {
                BigDecimal pointsPnl = record.getEntryPremium().subtract(closingLtp);
                record.setCurrentPremium(closingLtp);
                record.setUnrealizedPnl(pointsPnl.multiply(BigDecimal.valueOf(record.getLotSize())));
                ledgerRepository.save(record);
            }
        } else if ("HISTORY".equals(record.getStatus())) {
            if (record.getExitPremium() == null) {
                BigDecimal closingLtp = safelyFetchExitPremium(record, exchange);
                if (closingLtp != null) {
                    record.setExitPremium(closingLtp);
                    BigDecimal pointsPnl = record.getEntryPremium().subtract(closingLtp);
                    record.setRealizedPnl(pointsPnl.multiply(BigDecimal.valueOf(record.getLotSize())));
                    ledgerRepository.save(record);
                }
            }
        }
    }

    private Indexes getOptionIndexMetadata(AdvisoryLedger record) {
        if (record.getRecommendedStrike() == null || record.getOptionType() == null || record.getExpiryDate() == null) return null;
        try {
            String rawExpiry = record.getExpiryDate().trim();
            String formattedExpiry = rawExpiry.matches("^\\d{4}-\\d{2}-\\d{2}$") ?
                    LocalDate.parse(rawExpiry).format(java.time.format.DateTimeFormatter.ofPattern("ddMMMyyyy", java.util.Locale.ENGLISH)).toUpperCase() : rawExpiry;

            String strikeStr = record.getRecommendedStrike().stripTrailingZeros().toPlainString();
            String suffix = "%" + strikeStr + record.getOptionType();
            String optionToken = indexesRepo.findNfoTokenByNameAndExpiryAndSymbolLike(record.getSymbol(), formattedExpiry, suffix);

            if (optionToken != null) return indexesRepo.findByToken(optionToken);
        } catch (Exception e) {
            log.error("❌ Error fetching option index for {}: {}", record.getSymbol(), e.getMessage());
        }
        return null;
    }
}