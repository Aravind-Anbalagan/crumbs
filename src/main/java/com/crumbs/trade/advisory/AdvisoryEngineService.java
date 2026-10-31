package com.crumbs.trade.advisory;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.CandleRequestDto;
import com.crumbs.trade.entity.Futures;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.PricesIndex;
import com.crumbs.trade.repo.FuturesRepo;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.service.AngelOneService;
import com.crumbs.trade.service.AngelWebSocketService;
import com.crumbs.trade.service.SRService;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class AdvisoryEngineService {

    private final IndexesRepo indexesRepo;
    private final FuturesRepo futuresRepo; // 👈 Newly added for macro expiry levels
    private final SRService srService;
    private final AdvisoryOiService oiService;
    private final AdvisoryLedgerRepository ledgerRepository;
    private final AngelWebSocketService webSocketService;
    private final AngelOneService angelOneService;
    private final AngelOne angelOne;

    private static final Map<String, Lock> SYMBOL_LOCKS = new ConcurrentHashMap<>();

    @Autowired
    @Lazy
    private AdvisoryEngineService self;

    // =========================================================================
    // THE 3-PILLAR TREND LOGIC
    // =========================================================================
    public record TrendPillars(BigDecimal ma20, BigDecimal ma50, BigDecimal expiryHigh, BigDecimal expiryLow) {

        // EXACT ENTRY: All 3 Must Be True
        public boolean isBullish(BigDecimal spot) {
            return expiryHigh != null
                    && spot.compareTo(ma20) > 0
                    && spot.compareTo(ma50) > 0
                    && spot.compareTo(expiryHigh) > 0;
        }

        public boolean isBearish(BigDecimal spot) {
            return expiryLow != null
                    && spot.compareTo(ma20) < 0
                    && spot.compareTo(ma50) < 0
                    && spot.compareTo(expiryLow) < 0;
        }

        // EXACT STOP LOSS: All 3 Must Be True (Total Reversal)
        public boolean isBullishFailure(BigDecimal spot) {
            return expiryHigh != null
                    && spot.compareTo(ma20) < 0
                    && spot.compareTo(ma50) < 0
                    && spot.compareTo(expiryHigh) < 0;
        }

        public boolean isBearishFailure(BigDecimal spot) {
            return expiryLow != null
                    && spot.compareTo(ma20) > 0
                    && spot.compareTo(ma50) > 0
                    && spot.compareTo(expiryLow) > 0;
        }
    }

    // =========================================================================
    // OVERLOADED METHODS FOR BACKWARD COMPATIBILITY (Prevents Caller Breakage)
    // =========================================================================
    public OptionRecommendation processAdvisory(String name, String token) {
        return processAdvisory(name, token, "INTRADAY");
    }

    @Transactional
    public OptionRecommendation processAdvisoryInternal(String name, String token) {
        return self.processAdvisoryInternal(name, token, "INTRADAY");
    }

    // =========================================================================
    // MAIN ENTRY POINT
    // =========================================================================
    public OptionRecommendation processAdvisory(String name, String token, String scanType) {
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
            return self.processAdvisoryInternal(name, token, scanType);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.warn("🛡️ [DB GUARD] Database constraint blocked duplicate ACTIVE row for {}", name);
            return null;
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public OptionRecommendation processAdvisoryInternal(String name, String token, String scanType) {
        log.info("🧠 ========================================================");
        log.info("🧠 [ENGINE START] Analyzing Stateful Advisory for: {} (Token: {} | Scan: {})", name, token, scanType);

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

        // =====================================================================
        // FETCH MACRO LEVELS & CALCULATE 3 PILLARS
        // =====================================================================
        Futures futureData = futuresRepo.findFirstByName(name);
        TrendPillars pillars = calculatePillars(dailyCandles, futureData);
        BigDecimal atr14 = calculateATR(dailyCandles, 14);

        log.info("📊 [MARKET CONTEXT] Spot: ₹{} | 20MA: {} | 50MA: {} | Expiry H/L: {}/{} | ATR: {}",
                spotPrice, pillars.ma20(), pillars.ma50(), pillars.expiryHigh(), pillars.expiryLow(), atr14);

        AdvisoryOiService.AdvisoryOiData oiData;
        try {
            String fnoExchange = (exchange != null && exchange.contains("MCX")) ? "MCX" : "NFO";
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

        String roughTrend = pillars.isBullish(spotPrice) ? "BULLISH" : (pillars.isBearish(spotPrice) ? "BEARISH" : "CHOP");

        AdvisoryLedger newRecord = AdvisoryLedger.builder()
                .symbol(name)
                .expiryDate(resolvedExpiry)
                .timestamp(now)
                .status("ACTIVE")
                .cycleStartDate(cycle.startDate())
                .cycleEndDate(cycle.endDate())
                .spotPrice(spotPrice)
                .dailyTrend(roughTrend)
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
            evaluateHoldOrExit(prevRecord, newRecord, spotPrice, pillars, atr14, oiData, now, exchange, scanType, futureData);
        } else if (previousRecordOpt.isEmpty() || expiryChanged || prevPositionClosed || "NO_TRADE".equals(prevRecord.getActionTaken())) {
            log.info("✨ [PATH 1: NEW ENTRY] Scanning for fresh 3-Pillar setup on {}...", name);
            evaluateNewEntry(newRecord, pillars, oiData, now, spotPrice);
        } else {
            log.warn("⚠️ [FALLBACK PATH] Unhandled state for {}. Forcing hold/exit evaluation.", name);
            evaluateHoldOrExit(prevRecord, newRecord, spotPrice, pillars, atr14, oiData, now, exchange, scanType, futureData);
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
                .dailyTrend(roughTrend)
                .action(newRecord.getActionTaken())
                .recommendedStrike(newRecord.getRecommendedStrike())
                .putOiWallStrike(newRecord.getPutWallStrike())
                .callOiWallStrike(newRecord.getCallWallStrike())
                .atr14(atr14)
                .reasoning(newRecord.getReasoning())
                .unrealizedPnl(newRecord.getUnrealizedPnl())
                .entryPremium(newRecord.getEntryPremium())
                .entryDelta(newRecord.getEntryDelta())
                .entryIv(newRecord.getEntryIv())
                .build();
    }

    // =========================================================================
    // PATH 1: NEW POSITION ENTRY
    // =========================================================================
    private void evaluateNewEntry(AdvisoryLedger newRecord, TrendPillars pillars,
                                  AdvisoryOiService.AdvisoryOiData oiData,
                                  LocalDateTime now, BigDecimal spotPrice) {

        BigDecimal minPremium = new BigDecimal("10.0");
        List<String> auditTrail = new ArrayList<>();
        boolean canTrade = true;

        Optional<AdvisoryLedger> lastClosedOpt = ledgerRepository
                .findTopBySymbolAndStatusOrderByTimestampDesc(newRecord.getSymbol(), "HISTORY");

        // 1. Cooldown Check
        if (lastClosedOpt.isPresent() && lastClosedOpt.get().getTimestamp().toLocalDate().equals(now.toLocalDate())) {
            auditTrail.add("❌ Cooldown (Same-day re-entry blocked)");
            canTrade = false;
        } else {
            auditTrail.add("✅ Cooldown (Clear)");
        }

        // 2. Trend & OI Execution Logic
        if (canTrade && pillars.isBullish(spotPrice)) {
            auditTrail.add("✅ Trend (3-Pillar Bullish Confirmed)");

            if (oiData == null || oiData.putWall() == null) {
                auditTrail.add("❌ OI Wall (Put Wall Missing)");
                canTrade = false;
            } else if (oiData.putWall().strike().compareTo(spotPrice) >= 0) {
                auditTrail.add(String.format("❌ OI Wall (Put Wall %s is ITM, needs to be OTM)", oiData.putWall().strike()));
                canTrade = false;
            } else if (isRecentlyBreached(lastClosedOpt, now)) {
                auditTrail.add("❌ Execution (Recent SL breach cooldown active)");
                canTrade = false;
            } else if (oiData.putWall().ltp().compareTo(minPremium) < 0) {
                auditTrail.add(String.format("❌ Premium (₹%s < ₹%s min)", oiData.putWall().ltp(), minPremium));
                canTrade = false;
            } else {
                auditTrail.add(String.format("✅ OI Wall (Put Wall at %s confirms support)", oiData.putWall().strike()));

                // Execute Bullish Entry (Sell PE)
                newRecord.setActionTaken("NEW_ENTRY");
                newRecord.setOptionType("PE");
                newRecord.setRecommendedStrike(oiData.putWall().strike());
                Indexes optIndex = getOptionIndexMetadata(newRecord);
                newRecord.setLotSize(optIndex != null ? Integer.valueOf(optIndex.getLotsize()) : 1);
                newRecord.setEntryPremium(oiData.putWall().ltp());
                newRecord.setEntryDelta(BigDecimal.valueOf(oiData.putWall().delta()));
                newRecord.setEntryIv(BigDecimal.valueOf(oiData.putWall().iv()));
                newRecord.setEntryDate(now);
                newRecord.setDaysInPosition(1);
                newRecord.setStatus("ACTIVE");

                // Lock in the macro anchors
                newRecord.setExpiryHighAnchor(pillars.expiryHigh());
                newRecord.setExpiryLowAnchor(pillars.expiryLow());
            }

        } else if (canTrade && pillars.isBearish(spotPrice)) {
            auditTrail.add("✅ Trend (3-Pillar Bearish Confirmed)");

            if (oiData == null || oiData.callWall() == null) {
                auditTrail.add("❌ OI Wall (Call Wall Missing)");
                canTrade = false;
            } else if (oiData.callWall().strike().compareTo(spotPrice) <= 0) {
                auditTrail.add(String.format("❌ OI Wall (Call Wall %s is ITM, needs to be OTM)", oiData.callWall().strike()));
                canTrade = false;
            } else if (isRecentlyBreached(lastClosedOpt, now)) {
                auditTrail.add("❌ Execution (Recent SL breach cooldown active)");
                canTrade = false;
            } else if (oiData.callWall().ltp().compareTo(minPremium) < 0) {
                auditTrail.add(String.format("❌ Premium (₹%s < ₹%s min)", oiData.callWall().ltp(), minPremium));
                canTrade = false;
            } else {
                auditTrail.add(String.format("✅ OI Wall (Call Wall at %s confirms resistance)", oiData.callWall().strike()));

                // Execute Bearish Entry (Sell CE)
                newRecord.setActionTaken("NEW_ENTRY");
                newRecord.setOptionType("CE");
                newRecord.setRecommendedStrike(oiData.callWall().strike());
                Indexes optIndex = getOptionIndexMetadata(newRecord);
                newRecord.setLotSize(optIndex != null ? Integer.valueOf(optIndex.getLotsize()) : 1);
                newRecord.setEntryPremium(oiData.callWall().ltp());
                newRecord.setEntryDelta(BigDecimal.valueOf(oiData.callWall().delta()));
                newRecord.setEntryIv(BigDecimal.valueOf(oiData.callWall().iv()));
                newRecord.setEntryDate(now);
                newRecord.setDaysInPosition(1);
                newRecord.setStatus("ACTIVE");

                // Lock in the macro anchors
                newRecord.setExpiryHighAnchor(pillars.expiryHigh());
                newRecord.setExpiryLowAnchor(pillars.expiryLow());
            }

        } else if (canTrade) {
            auditTrail.add(String.format("❌ Trend (No 3-Pillar Alignment. Spot: %s | 20MA: %s | 50MA: %s | High: %s | Low: %s)",
                    spotPrice, pillars.ma20(), pillars.ma50(), pillars.expiryHigh(), pillars.expiryLow()));
            canTrade = false;
        }

        String combinedAuditLog = String.join(" | ", auditTrail);
        if (!canTrade) {
            newRecord.setActionTaken("NO_TRADE");
            newRecord.setReasoning(combinedAuditLog);
            newRecord.setStatus("ACTIVE");
            log.info("🔎 [SCAN AUDIT] {} -> {}", newRecord.getSymbol(), combinedAuditLog);
        } else {
            newRecord.setReasoning("ENTRY APPROVED | " + combinedAuditLog);
            log.info("✅ [ENTRY OPENED] {} -> Sold {} {} at ₹{} | Audit: {}",
                    newRecord.getSymbol(), newRecord.getRecommendedStrike(), newRecord.getOptionType(), newRecord.getEntryPremium(), combinedAuditLog);
        }
    }

    // =========================================================================
    // PATH 2: HOLD OR EXIT EXISTING POSITION
    // =========================================================================
    private void evaluateHoldOrExit(AdvisoryLedger prev, AdvisoryLedger current, BigDecimal spotPrice,
                                    TrendPillars pillars, BigDecimal atr14,
                                    AdvisoryOiService.AdvisoryOiData oiData,
                                    LocalDateTime now, String exchange, String scanType, Futures futureData) {

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

        // 🔥 IN-FLIGHT TRADE MIGRATION SAFETY CHECK 🔥
        // If this trade was opened BEFORE the code update, it won't have expiry anchors.
        // We seamlessly fall back to the live Futures data so the trade doesn't break.
        BigDecimal lockedExpiryHigh = prev.getExpiryHighAnchor() != null ? prev.getExpiryHighAnchor() : (futureData != null ? futureData.getExpiryHigh() : null);
        BigDecimal lockedExpiryLow = prev.getExpiryLowAnchor() != null ? prev.getExpiryLowAnchor() : (futureData != null ? futureData.getExpiryLow() : null);

        current.setExpiryHighAnchor(lockedExpiryHigh);
        current.setExpiryLowAnchor(lockedExpiryLow);

        int daysHeld = (int) java.time.temporal.ChronoUnit.DAYS.between(current.getEntryDate().toLocalDate(), now.toLocalDate()) + 1;
        current.setDaysInPosition(daysHeld);

        List<String> warnings = new ArrayList<>();

        // Proximity Warning
        BigDecimal safeBuffer = atr14.multiply(new BigDecimal("1.25"));
        boolean ceBreached = "CE".equalsIgnoreCase(prev.getOptionType()) && spotPrice.compareTo(prev.getRecommendedStrike().subtract(safeBuffer)) >= 0;
        boolean peBreached = "PE".equalsIgnoreCase(prev.getOptionType()) && spotPrice.compareTo(prev.getRecommendedStrike().add(safeBuffer)) <= 0;
        if (ceBreached || peBreached) {
            warnings.add("🚨 Proximity breach");
            log.warn("🚨 [WARNING] Proximity buffer breached on {}!", prev.getSymbol());
        }

        BigDecimal exitPremium = safelyFetchExitPremium(current, exchange);
        if (exitPremium == null) {
            exitPremium = current.getCurrentPremium() != null ? current.getCurrentPremium() : current.getEntryPremium();
        }

        // ===================================================================
        // EXIT 1: VALUE TARGET (85% Premium Decay) - CAN trigger anytime
        // ===================================================================
        if (exitPremium != null && prev.getEntryPremium() != null) {
            BigDecimal targetPremium = prev.getEntryPremium().multiply(new BigDecimal("0.15"));
            if (exitPremium.compareTo(targetPremium) <= 0) {
                current.setCurrentPremium(exitPremium);
                executeExit(current, exchange, "TARGET", String.format("TARGET: Premium decayed by >= 85%% (Entry: ₹%s -> Live: ₹%s). Locking in profit.", prev.getEntryPremium(), exitPremium));
                return;
            }
            current.setCurrentPremium(exitPremium); // Update live premium for MTM
        }

        // ===================================================================
        // EXIT 2: STRUCTURAL STOP-LOSS (ALL 3 PILLARS FAILED)
        // Only allowed during PRE_CLOSE_SCAN to prevent intraday fakeouts.
        // ===================================================================
        if ("PRE_CLOSE_SCAN".equals(scanType)) {
            // Reconstruct the pillars using the exact anchors from when the trade was originally placed
            TrendPillars lockedPillars = new TrendPillars(pillars.ma20(), pillars.ma50(), lockedExpiryHigh, lockedExpiryLow);

            if ("PE".equalsIgnoreCase(prev.getOptionType())) {
                if (lockedPillars.isBullishFailure(spotPrice)) {
                    executeExit(current, exchange, "SL", String.format("SL HIT: Full Structural Failure. Spot (₹%s) closed below 20MA, 50MA, and Expiry High.", spotPrice));
                    return;
                }
            } else if ("CE".equalsIgnoreCase(prev.getOptionType())) {
                if (lockedPillars.isBearishFailure(spotPrice)) {
                    executeExit(current, exchange, "SL", String.format("SL HIT: Full Structural Failure. Spot (₹%s) closed above 20MA, 50MA, and Expiry Low.", spotPrice));
                    return;
                }
            }
        } else {
            log.info("⏳ [{}] Skipping Physical SL checks for {}. Evaluating warnings only.", scanType, prev.getSymbol());
        }

        // ===================================================================
        // EXIT 3: WALL MIGRATION (Dynamic Support/Resistance collapse)
        // ===================================================================
        boolean wallMigratedToTarget = false;
        String wallExitReason = "";
        String exitReasoning = "";

        if (oiData != null) {
            BigDecimal prevPutStrike = prev.getPutWallStrike();
            BigDecimal prevCallStrike = prev.getCallWallStrike();

            boolean hasLivePut = oiData.putWall() != null;
            boolean hasLiveCall = oiData.callWall() != null;

            BigDecimal livePutStrike = hasLivePut ? oiData.putWall().strike() : null;
            BigDecimal liveCallStrike = hasLiveCall ? oiData.callWall().strike() : null;

            if (hasLivePut && prevPutStrike != null && hasLiveCall && prevCallStrike != null) {

                if ("PE".equalsIgnoreCase(prev.getOptionType())) {
                    boolean supportRaised = livePutStrike.compareTo(prevPutStrike) > 0;
                    boolean resistanceConceded = liveCallStrike.compareTo(prevCallStrike) >= 0;

                    if (supportRaised && resistanceConceded) {
                        wallMigratedToTarget = true;
                        wallExitReason = "TARGET";
                        exitReasoning = String.format("Wall migrated. TARGET. PE shifted favorably (%s -> %s) and CE conceded.", prevPutStrike, livePutStrike);
                    } else if (livePutStrike.compareTo(prevPutStrike) < 0) {
                        warnings.add("🚨 Support Wall retreating lower");
                    }
                } else if ("CE".equalsIgnoreCase(prev.getOptionType())) {
                    boolean resistanceLowered = liveCallStrike.compareTo(prevCallStrike) < 0;
                    boolean supportConceded = livePutStrike.compareTo(prevPutStrike) <= 0;

                    if (resistanceLowered && supportConceded) {
                        wallMigratedToTarget = true;
                        wallExitReason = "TARGET";
                        exitReasoning = String.format("Wall migrated. TARGET. CE shifted favorably (%s -> %s) and PE conceded.", prevCallStrike, liveCallStrike);
                    } else if (liveCallStrike.compareTo(prevCallStrike) > 0) {
                        warnings.add("🚨 Resistance Wall retreating higher");
                    }
                }
            }
        }

        if (wallMigratedToTarget) {
            executeExit(current, exchange, wallExitReason, exitReasoning);
            return;
        }

        // ===================================================================
        // ALL PILLARS/WALLS INTACT: MAINTAIN POSITION
        // ===================================================================
        current.setActionTaken("MAINTAIN");
        current.setStatus("ACTIVE");

        String reasoning = String.format("Holding %s %s (Day %d).", prev.getRecommendedStrike(), prev.getOptionType(), daysHeld);
        if (!warnings.isEmpty()) {
            reasoning += " [WARNINGS: " + String.join(", ", warnings) + "] Trend intact.";
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
    // HELPER METHODS
    // =========================================================================
    private TrendPillars calculatePillars(List<PricesIndex> candles, Futures futureData) {
        BigDecimal sum20 = BigDecimal.ZERO;
        for (int i = candles.size() - 20; i < candles.size(); i++) sum20 = sum20.add(candles.get(i).getClose());
        BigDecimal ma20 = sum20.divide(new BigDecimal("20"), 2, RoundingMode.HALF_UP);

        BigDecimal sum50 = BigDecimal.ZERO;
        for (int i = candles.size() - 50; i < candles.size(); i++) sum50 = sum50.add(candles.get(i).getClose());
        BigDecimal ma50 = sum50.divide(new BigDecimal("50"), 2, RoundingMode.HALF_UP);

        BigDecimal expHigh = futureData != null ? futureData.getExpiryHigh() : null;
        BigDecimal expLow = futureData != null ? futureData.getExpiryLow() : null;

        return new TrendPillars(ma20, ma50, expHigh, expLow);
    }

    private boolean isRecentlyBreached(Optional<AdvisoryLedger> lastClosedOpt, LocalDateTime now) {
        if (lastClosedOpt.isPresent()) {
            AdvisoryLedger lastTrade = lastClosedOpt.get();
            boolean isBreachExit = "SL".equals(lastTrade.getActionTaken());
            boolean isRecent = lastTrade.getTimestamp().isAfter(now.minusDays(2));

            if (isBreachExit && isRecent) {
                log.warn("⏳ [COOLDOWN ACTIVE] Blocked entry for {}. Last SL hit on {}.", lastTrade.getSymbol(), lastTrade.getTimestamp());
                return true;
            }
        }
        return false;
    }

    private BigDecimal safelyFetchExitPremium(AdvisoryLedger prev, String exchange) {
        if (prev.getRecommendedStrike() == null || prev.getOptionType() == null) return null;

        try {
            Indexes optionIndex = getOptionIndexMetadata(prev);
            if (optionIndex == null || optionIndex.getToken() == null) return null;

            String optionToken = optionIndex.getToken();
            String tradingSymbol = optionIndex.getSymbol();
            ExchangeType exType = exchange.contains("MCX") ? ExchangeType.MCX_FO : ExchangeType.NSE_FO;

            // =========================================================
            // 1. ATTEMPT WEBSOCKET FETCH (Fastest)
            // =========================================================
            BigDecimal exitLtp = webSocketService.getLatestLTP(exType, optionToken);

            if (exitLtp != null && exitLtp.compareTo(BigDecimal.ZERO) > 0) {
                return exitLtp;
            }

            // =========================================================
            // 2. FALLBACK TO REST API (Using AngelOneService.getcurrentPrice)
            // =========================================================
            log.warn("⚠️ WebSocket LTP missing for {}. Falling back to REST API...", tradingSymbol);

            String apiExchange = exchange.contains("MCX") ? "MCX" : "NFO";

            // Generate the SmartConnect session
            com.angelbroking.smartapi.SmartConnect smartConnect = angelOne.signIn();

            if (smartConnect != null) {
                // Call the actual method from your AngelOneService
                BigDecimal restLtp = angelOneService.getcurrentPrice(smartConnect, apiExchange, tradingSymbol, optionToken);

                if (restLtp != null && restLtp.compareTo(BigDecimal.ZERO) > 0) {
                    log.info("✅ REST API successfully returned LTP ₹{} for {}", restLtp, tradingSymbol);
                    return restLtp;
                }
            }

            log.error("❌ Both WebSocket and REST API failed to return a price for {}", tradingSymbol);

        } catch (Exception e) {
            log.error("❌ Exception fetching live premium for {}: {}", prev.getSymbol(), e.getMessage());
        }
        return null;
    }

    private BigDecimal calculateATR(List<PricesIndex> candles, int period) {
        if (candles == null || candles.size() < period) return estimateATRFromRange(candles);

        BigDecimal trSum = BigDecimal.ZERO;
        int validTRs = 0;
        for (int i = candles.size() - period; i < candles.size(); i++) {
            PricesIndex candle = candles.get(i);
            PricesIndex prevCandle = candles.get(i - 1); // 🔥 Subtraction bug fixed here!
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
    // Add this new method to AdvisoryEngineService.java
    @Transactional
    public AdvisoryLedger getLiveTradeDetails(Long recordId) {
        AdvisoryLedger record = ledgerRepository.findById(recordId)
                .orElseThrow(() -> new RuntimeException("Record not found"));

        if ("ACTIVE".equals(record.getStatus()) && record.getEntryPremium() != null) {
            Indexes optionIndex = getOptionIndexMetadata(record);
            String exchange = optionIndex != null ? optionIndex.getExchange() : "NFO";

            // Fetch live price via WS or API fallback
            BigDecimal liveLtp = safelyFetchExitPremium(record, exchange);

            if (liveLtp != null) {
                BigDecimal pointsPnl = record.getEntryPremium().subtract(liveLtp);
                int safeLotSize = record.getLotSize() != null ? record.getLotSize() : 1;
                BigDecimal absolutePnl = pointsPnl.multiply(BigDecimal.valueOf(safeLotSize));

                record.setCurrentPremium(liveLtp);
                record.setUnrealizedPnl(absolutePnl);

                // Save to DB so the timeline matrix is instantly updated for subsequent loads
                ledgerRepository.save(record);
            }
        }
        return record;
    }
}