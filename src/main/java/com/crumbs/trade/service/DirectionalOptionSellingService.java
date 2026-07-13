package com.crumbs.trade.service;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.PreMarketAnalysis;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.PreMarketAnalysisRepo;
import com.crumbs.trade.repo.StrategyRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class DirectionalOptionSellingService {

    // ============================================================
    // ======================= CONFIGURATION =======================
    // All tunables for this strategy live here. Change values here only -
    // nothing below this block should need editing for routine tuning.
    // ============================================================

    // --- Identity ---
    private static final String STRATEGY_SIGNAL = "DIRECTIONAL_SELL";
    private static final String NAME_PREFIX = "DIR_SELL_";

    // --- Session Timeframes: NIFTY (morning/day session) ---
    private static final LocalTime NIFTY_START = LocalTime.of(9, 20);
    private static final LocalTime NIFTY_ENTRY_CUTOFF = LocalTime.of(15, 0);
    private static final LocalTime NIFTY_SQUARE_OFF = LocalTime.of(15, 20);

    // --- Session Timeframes: CRUDEOIL (evening session, MCX) ---
    private static final LocalTime CRUDEOIL_START = LocalTime.of(16, 5);
    private static final LocalTime CRUDEOIL_ENTRY_CUTOFF = LocalTime.of(23, 0);
    private static final LocalTime CRUDEOIL_SQUARE_OFF = LocalTime.of(23, 20);

    // --- Entry Filter ---
    // Entry only qualifies if |LTP - midpoint| is within this many points.
    // Too close to zero => entries never trigger. Too large => defeats the
    // purpose of filtering out abnormal gaps/spikes at entry time.
    private static final BigDecimal MAX_ENTRY_DISTANCE_POINTS = BigDecimal.valueOf(15);

    // --- Order/DB Status Literals ---
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_INACTIVE = 0;
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String PHASE_ENTRY = "ENTRY";
    private static final String PHASE_EXIT = "EXIT";

    // --- Retry tuning for the post-broker-call DB lookup (live entries only) ---
    private static final int ORDER_LOOKUP_MAX_RETRIES = 3;
    private static final long ORDER_LOOKUP_RETRY_DELAY_MS = 300;

    // ============================================================
    // ======================= DEPENDENCIES ========================
    // ============================================================

    private final PreMarketAnalysisRepo preMarketRepo;
    private final OrderRepository ordersRepository;
    private final StrategyRepo strategyRepo;
    private final OrderService orderService;
    private final TelegramService telegramService;
    private final AngelWebSocketService angelWebSocketService;

    // Hit Trackers - keys are date-scoped (see buildKey) so counts never leak across trading sessions
    private final ConcurrentHashMap<String, Integer> hitCounters = new ConcurrentHashMap<>();

    public void evaluate(String instrumentName) {
        LocalTime now = LocalTime.now();
        String tradeName = NAME_PREFIX + instrumentName;

        Strategy strategyConfig = strategyRepo.findByName(STRATEGY_SIGNAL);
        Strategy sourceConfig = strategyRepo.findByName(instrumentName);

        if (strategyConfig == null || sourceConfig == null) {
            log.error("❌ DB Config Missing! Strategy Present: {}, Source Present: {}",
                    strategyConfig != null, sourceConfig != null);
            return;
        }

        // 🛑 CONFIG GUARD: live and papertrade should never both be enabled for the same
        // strategy - if they are, we bail out rather than risk a real broker exit firing
        // against what was meant to be a paper-only trade (or vice-versa).
        boolean isLiveFlag = "Y".equalsIgnoreCase(strategyConfig.getLive());
        boolean isPaperFlag = "Y".equalsIgnoreCase(strategyConfig.getPapertrade());
        if (isLiveFlag && isPaperFlag) {
            log.error("❌ [{}] Invalid config: both LIVE and PAPERTRADE are 'Y'. Refusing to trade until this is fixed.", tradeName);
            return;
        }

        // --- Fetch PreMarket Data for Midpoints ---
        Optional<PreMarketAnalysis> optData = preMarketRepo.findByNameAndTradingDate(instrumentName, LocalDate.now());
        if (optData.isEmpty()) {
            log.debug("⏳ [{}] PreMarket data not generated yet.", tradeName);
            return;
        }
        PreMarketAnalysis data = optData.get();

        // --- Enforce Daily Limits ---
        LocalDateTime startOfDay = LocalDateTime.now().with(LocalTime.MIN);
        long tradesUsed = ordersRepository.countLegsToday(tradeName, STRATEGY_SIGNAL, startOfDay);
        int maxAllowed = sourceConfig.getMaxDailyTrades() > 0 ? sourceConfig.getMaxDailyTrades() : 3;

        // --- Enforce Time Windows ---
        if (isBeforeSessionStart(instrumentName, now)) return;

        List<Orders> activeOrders = ordersRepository.findByNameAndSignalAndActive(tradeName, STRATEGY_SIGNAL, STATUS_ACTIVE);

        if (!activeOrders.isEmpty()) {
            Orders activeTrade = activeOrders.get(0);

            if (isSquareOffTime(instrumentName, now)) {
                log.info("🕒 [{}][EXIT] Square-off time reached.", tradeName);
                processExitSequence(activeTrade, data, "EOD_SQUARE_OFF", strategyConfig, sourceConfig, true);
                return;
            }
            processExitSequence(activeTrade, data, "SL_OR_TARGET", strategyConfig, sourceConfig, false);

        } else {
            if (tradesUsed >= maxAllowed) {
                log.info("🛑 [{}] Max daily trades reached ({}/{}).", tradeName, tradesUsed, maxAllowed);
                return;
            }

            if (!isWithinEntryWindow(instrumentName, now)) return;

            processEntrySequence(tradeName, data, strategyConfig, sourceConfig);
        }
    }

    // ============================================================
    // ================= ENTRY LOGIC (ZIG-ZAG PROTECTED) ==========
    // ============================================================

    private void processEntrySequence(String tradeName, PreMarketAnalysis data, Strategy strategyConfig, Strategy sourceConfig) {
        String exchange = sourceConfig.getExchange();
        BigDecimal ceLtp = getLivePrice(data.getCeToken(), exchange);
        BigDecimal peLtp = getLivePrice(data.getPeToken(), exchange);
        BigDecimal midPoint = (data.getSecondMidPoint() != null) ? data.getSecondMidPoint() : data.getMidPoint();

        if (ceLtp == null || peLtp == null || midPoint == null) return;

        int reqHits = sourceConfig.getEntryHitsRequired() > 0 ? sourceConfig.getEntryHitsRequired() : 10;
        String ceKey = buildKey(tradeName, "CE_ENTRY");
        String peKey = buildKey(tradeName, "PE_ENTRY");

        // 🛡️ ENTRY DISTANCE FILTER: only qualifies as a valid entry signal if the LTP is
        // within MAX_ENTRY_DISTANCE_POINTS of the midpoint (in either direction). This keeps
        // the strategy from chasing an entry that has already moved too far away from the
        // reference midpoint - e.g. a gap, spike, or stale/bad tick.
        BigDecimal ceDistance = ceLtp.subtract(midPoint).abs();
        BigDecimal peDistance = peLtp.subtract(midPoint).abs();
        boolean ceBelowMid = ceLtp.compareTo(midPoint) < 0;
        boolean peBelowMid = peLtp.compareTo(midPoint) < 0;
        boolean ceWithinBand = ceDistance.compareTo(MAX_ENTRY_DISTANCE_POINTS) <= 0;
        boolean peWithinBand = peDistance.compareTo(MAX_ENTRY_DISTANCE_POINTS) <= 0;

        // CE Condition: LTP below midpoint AND within the allowed distance band (Short CE)
        if (ceBelowMid && ceWithinBand) {
            int hits = hitCounters.merge(ceKey, 1, Integer::sum);
            log.info("🎯 [{}] CE ENTRY HITS: ({}/{}) | LTP: {} | MID: {} | Distance: {} pts (within ±{} band)",
                    tradeName, hits, reqHits, ceLtp, midPoint, ceDistance.setScale(2, RoundingMode.HALF_UP), MAX_ENTRY_DISTANCE_POINTS);
            hitCounters.put(peKey, 0); // Reset opposite side

            if (hits >= reqHits) {
                log.info("⚡ [{}] CE HITS MET! Triggering SELL.", tradeName);
                executeTrade(tradeName, data.getCeToken(), data.getCeSymbol(), data.getAtmStrike(), ceLtp, "CE", strategyConfig, sourceConfig);
                hitCounters.put(ceKey, 0); // Reset after entry
            }
        }
        // PE Condition: LTP below midpoint AND within the allowed distance band (Short PE)
        else if (peBelowMid && peWithinBand) {
            int hits = hitCounters.merge(peKey, 1, Integer::sum);
            log.info("🎯 [{}] PE ENTRY HITS: ({}/{}) | LTP: {} | MID: {} | Distance: {} pts (within ±{} band)",
                    tradeName, hits, reqHits, peLtp, midPoint, peDistance.setScale(2, RoundingMode.HALF_UP), MAX_ENTRY_DISTANCE_POINTS);
            hitCounters.put(ceKey, 0); // Reset opposite side

            if (hits >= reqHits) {
                log.info("⚡ [{}] PE HITS MET! Triggering SELL.", tradeName);
                executeTrade(tradeName, data.getPeToken(), data.getPeSymbol(), data.getAtmStrike(), peLtp, "PE", strategyConfig, sourceConfig);
                hitCounters.put(peKey, 0); // Reset after entry
            }
        }
        // No qualifying signal this tick - either neither side is below midpoint (normal
        // zig-zag), or a side is below midpoint but too far away to count as a valid entry.
        else {
            if (ceBelowMid && !ceWithinBand) {
                log.info("🚧 [{}] CE below midpoint but distance {} pts exceeds max entry band (±{} pts). NO ENTRY.",
                        tradeName, ceDistance.setScale(2, RoundingMode.HALF_UP), MAX_ENTRY_DISTANCE_POINTS);
            }
            if (peBelowMid && !peWithinBand) {
                log.info("🚧 [{}] PE below midpoint but distance {} pts exceeds max entry band (±{} pts). NO ENTRY.",
                        tradeName, peDistance.setScale(2, RoundingMode.HALF_UP), MAX_ENTRY_DISTANCE_POINTS);
            }
            if (!ceBelowMid && !peBelowMid && (hitCounters.getOrDefault(ceKey, 0) > 0 || hitCounters.getOrDefault(peKey, 0) > 0)) {
                log.info("🔄 [{}] CONDITIONS LOST (Zig-Zag). Resetting entry hits.", tradeName);
            }
            hitCounters.put(ceKey, 0);
            hitCounters.put(peKey, 0);
        }
    }

    // ============================================================
    // ================= EXIT LOGIC (SL & TARGETS) ================
    // ============================================================

    private void processExitSequence(Orders activeTrade, PreMarketAnalysis data, String triggerReason, Strategy strategyConfig, Strategy sourceConfig, boolean forceExit) {
        String token = activeTrade.getToken();
        String exchange = activeTrade.getExchange() != null ? activeTrade.getExchange() : sourceConfig.getExchange();
        BigDecimal ltp = getLivePrice(token, exchange);

        if (ltp == null) return;

        // Force Exit Bypass (EOD 3:20 PM)
        if (forceExit) {
            closeTrade(activeTrade, ltp, triggerReason, strategyConfig, sourceConfig);
            resetHitCounters(activeTrade.getName());
            return;
        }

        // 🛡️ NULL SAFETY: a trade should never persist without an entry (ask) price, but if it
        // ever does (e.g. a partially-tracked live order), bail out loudly instead of throwing an
        // NPE that would silently stop this trade from ever being risk-managed again.
        BigDecimal entryPrice = activeTrade.getAskPrice();
        if (entryPrice == null) {
            log.error("❌ [{}][EXIT] Active trade has NULL ask/entry price! Cannot evaluate SL/Target. Flagging for manual review.", activeTrade.getName());
            telegramService.sendMessage(String.format(
                    "⚠️ **DATA ISSUE [%s]**\nActive trade id=%s has no entry price recorded. SL/Target checks are blocked until this is fixed manually.",
                    activeTrade.getName(), activeTrade.getId()));
            return;
        }

        BigDecimal slPoints = sourceConfig.getSlPoints() != null ? sourceConfig.getSlPoints() : BigDecimal.valueOf(10);
        BigDecimal targetPoints = sourceConfig.getTargetPoints() != null ? sourceConfig.getTargetPoints() : BigDecimal.valueOf(20);

        BigDecimal currentSl = entryPrice.add(slPoints);      // We are short, so SL is above entry
        BigDecimal targetPrice = entryPrice.subtract(targetPoints); // Target is below entry

        int reqSlHits = sourceConfig.getExitHitsRequired() > 0 ? sourceConfig.getExitHitsRequired() : 5;
        String exitKey = buildKey(activeTrade.getName(), "EXIT_HITS");

        // Target Check (Immediate hit, no consecutive logic needed for booking profits)
        if (ltp.compareTo(targetPrice) <= 0) {
            log.info("💰 [{}][EXIT] TARGET REACHED! LTP: {} | Target: {}", activeTrade.getName(), ltp, targetPrice);
            closeTrade(activeTrade, ltp, "TARGET_REACHED", strategyConfig, sourceConfig);
            hitCounters.put(exitKey, 0);
            return;
        }

        // SL Check (Consecutive Hits)
        if (ltp.compareTo(currentSl) >= 0) {
            int hits = hitCounters.merge(exitKey, 1, Integer::sum);
            log.warn("🚨 [{}][EXIT] SL THREAT: ({}/{}) | LTP: {} | SL: {}", activeTrade.getName(), hits, reqSlHits, ltp, currentSl);

            if (hits >= reqSlHits) {
                log.warn("❌ [{}][EXIT] SL HITS MET! Closing trade.", activeTrade.getName());
                closeTrade(activeTrade, ltp, "STOP_LOSS_HIT", strategyConfig, sourceConfig);
                hitCounters.put(exitKey, 0);
            }
        } else {
            if (hitCounters.getOrDefault(exitKey, 0) > 0) {
                log.info("🔄 [{}][EXIT] SL Threat averted (Zig-Zag). Resetting exit hits.", activeTrade.getName());
            }
            hitCounters.put(exitKey, 0);
        }
    }

    // ============================================================
    // ================= DB & BROKER EXECUTION ====================
    // ============================================================

    protected void executeTrade(String tradeName, String tokenStr, String symbol, BigDecimal strike, BigDecimal price, String type, Strategy strategyConfig, Strategy sourceConfig) {
        log.info("🚀 [{}][EXECUTE] Opening SHORT {} | Price: {}", tradeName, type, price);
        String cycleId = UUID.randomUUID().toString();
        Orders order = null;

        boolean isLive = "Y".equalsIgnoreCase(strategyConfig.getLive());
        boolean isPaper = "Y".equalsIgnoreCase(strategyConfig.getPapertrade());

        try {
            Token t = new Token();
            t.setToken(tokenStr);
            t.setSymbol(symbol);
            t.setStrike(strike);
            t.setName(sourceConfig.getName());
            t.setExch_seg(sourceConfig.getExchange());
            t.setQuantity(sourceConfig.getQuantity());

            if (isLive) {
                log.info("🌐 [{}][{}] LIVE MODE: Sending to broker...", tradeName, type);
                orderService.orderPlaceWithToken(t, sourceConfig.getName(), "SELL", true);

                // 🛡️ SAFE LOOKUP: the broker call creates the DB row asynchronously/out-of-band.
                // Retry a few times with a short delay before giving up, since a single-shot lookup
                // can race the row being written. If it still can't be found, the position is live
                // at the broker but untracked here - that must never fail silently.
                order = findPlacedOrderWithRetry(sourceConfig.getName(), tokenStr);

                if (order == null) {
                    log.error("❌ [{}][{}] CRITICAL: Broker order was placed but could not be located in DB after {} retries! "
                                    + "Position may be LIVE and UNTRACKED - manual intervention required.",
                            tradeName, type, ORDER_LOOKUP_MAX_RETRIES);
                    telegramService.sendMessage(String.format(
                            "🚨 **CRITICAL [%s]**\nLIVE SELL %s was sent to the broker but the resulting order could not be found in the DB.\n"
                                    + "This position is likely open at the broker but is NOT being risk-managed. Please check manually immediately.",
                            tradeName, type));
                    return;
                }

            } else if (isPaper) {
                log.info("📄 [{}][{}] PAPER MODE: Simulating broker...", tradeName, type);
                order = new Orders();
                order.setToken(tokenStr);
                order.setSymbol(symbol);
                order.setQuantity(sourceConfig.getQuantity());
                order.setExchange(sourceConfig.getExchange());
                order.setActive(STATUS_ACTIVE);

                // FIX 1: Manually set the created on date for paper trades
                order.setCreatedOn(LocalDateTime.now());
            } else {
                log.warn("⚠️ [{}] Execution skipped. Both LIVE and PAPER flags are 'N' in config.", tradeName);
                return;
            }

            // Upgrade the DB row with strategy-specific details
            order.setName(tradeName);
            order.setSignal(STRATEGY_SIGNAL);
            order.setOptionType(type);
            order.setTradeCycleId(cycleId);
            order.setAskPrice(price);
            order.setStrike(strike);
            order.setStatus(STATUS_OPEN);
            order.setTradePhase(PHASE_ENTRY);
            ordersRepository.save(order);

            String mode = isLive ? "LIVE" : "PAPER";
            telegramService.sendMessage(String.format("🚀 **ENTRY [%s]: %s**\nSide: SHORT %s\nStrike: %s\nPrice: %.2f",
                    mode, tradeName, type, strike, price));

        } catch (Exception | SmartAPIException e) {
            log.error("❌ [{}][LEG] System error during execution: {}", tradeName, e.getMessage());
        }
    }

    /**
     * Retries the post-broker-call lookup a few times with a short backoff, since the row
     * that orderPlaceWithToken() creates may not be immediately visible/committed yet.
     */
    private Orders findPlacedOrderWithRetry(String sourceName, String tokenStr) {
        for (int attempt = 1; attempt <= ORDER_LOOKUP_MAX_RETRIES; attempt++) {
            Optional<Orders> found = ordersRepository.findByNameAndTokenAndActive(sourceName, tokenStr, STATUS_ACTIVE);
            if (found.isPresent()) {
                return found.get();
            }
            log.warn("⏳ [{}] Order not yet visible in DB (attempt {}/{}). Retrying...",
                    sourceName, attempt, ORDER_LOOKUP_MAX_RETRIES);
            try {
                Thread.sleep(ORDER_LOOKUP_RETRY_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    protected void closeTrade(Orders order, BigDecimal exitPrice, String reason, Strategy strategyConfig, Strategy sourceConfig) {
        boolean isLive = "Y".equalsIgnoreCase(strategyConfig.getLive());

        try {
            if (isLive) {
                log.info("🌐 [{}][EXIT] LIVE MODE: Sending exit to broker...", order.getName());
                orderService.exitActiveTradeByToken(order.getToken(), sourceConfig.getName(), order.getName());
            } else {
                log.info("📄 [{}][EXIT] PAPER MODE: Simulating broker exit...", order.getName());
            }

            BigDecimal entryPrice = order.getAskPrice() != null ? order.getAskPrice() : BigDecimal.ZERO;

            // Short Selling PnL: Entry (Sell Price) - Exit (Buy Price)
            BigDecimal pointsCollected = entryPrice.subtract(exitPrice);
            BigDecimal quantity = BigDecimal.valueOf(order.getQuantity());
            BigDecimal rupeePnL = pointsCollected.multiply(quantity).setScale(2, RoundingMode.HALF_UP);

            order.setExitPrice(exitPrice);
            order.setPl(rupeePnL);
            order.setClosedOn(LocalDateTime.now());
            order.setTradePhase(PHASE_EXIT);
            order.setStatus(STATUS_CLOSED);
            order.setActive(STATUS_INACTIVE);
            order.setExitReason(reason);
            ordersRepository.save(order);

            String emoji = rupeePnL.signum() >= 0 ? "✅" : "❌";
            String mode = isLive ? "LIVE" : "PAPER";

            telegramService.sendMessage(String.format(
                    "%s **EXIT [%s]: %s**\nReason: %s\nEntry: %.2f | Exit: %.2f\nPnL: **₹%.2f**",
                    emoji, mode, order.getName(), reason, entryPrice, exitPrice, rupeePnL
            ));

        } catch (Exception | SmartAPIException e) {
            log.error("❌ [{}][EXIT] Error closing trade: {}", order.getName(), e.getMessage());
        }
    }

    // ============================================================
    // ================= UTILITIES ================================
    // ============================================================

    private boolean isBeforeSessionStart(String symbol, LocalTime now) {
        if ("NIFTY".equalsIgnoreCase(symbol)) return now.isBefore(NIFTY_START);
        if ("CRUDEOIL".equalsIgnoreCase(symbol)) return now.isBefore(CRUDEOIL_START);
        return false;
    }

    private boolean isSquareOffTime(String symbol, LocalTime now) {
        if ("NIFTY".equalsIgnoreCase(symbol)) return !now.isBefore(NIFTY_SQUARE_OFF);
        if ("CRUDEOIL".equalsIgnoreCase(symbol)) return !now.isBefore(CRUDEOIL_SQUARE_OFF);
        return false;
    }

    private boolean isWithinEntryWindow(String symbol, LocalTime now) {
        if ("NIFTY".equalsIgnoreCase(symbol)) return now.isBefore(NIFTY_ENTRY_CUTOFF);
        if ("CRUDEOIL".equalsIgnoreCase(symbol)) return now.isBefore(CRUDEOIL_ENTRY_CUTOFF);
        return true;
    }

    /**
     * 🛡️ EXCHANGE FIX: CRUDEOIL trades on MCX, not NSE F&O. Hardcoding NSE_FO here would
     * silently make LTP lookups for crude tokens return null/zero forever - the strategy
     * would just never fire for CRUDEOIL, with no error anywhere to point at why.
     */
    private ExchangeType mapExchangeToType(String exchange) {
        if (exchange == null) return ExchangeType.NSE_FO; // preserves prior default behavior
        switch (exchange.toUpperCase().trim()) {
            case "MCX": return ExchangeType.MCX_FO;
            case "NFO": return ExchangeType.NSE_FO;
            case "NSE": return ExchangeType.NSE_CM;
            case "BSE": return ExchangeType.BSE_CM;
            case "BFO": return ExchangeType.BSE_FO;
            default: return ExchangeType.NSE_FO;
        }
    }

    private BigDecimal getLivePrice(String token, String exchange) {
        BigDecimal price = angelWebSocketService.getLatestLTP(mapExchangeToType(exchange), token);

        // FIX 2: Reject null OR zero values so you don't enter/exit at ₹0.00
        if (price == null || price.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        return price.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Builds a date-scoped hit-counter key so counts never silently carry over from a
     * previous trading session into today's evaluation (e.g. a stale SL-hit count).
     */
    private String buildKey(String tradeName, String suffix) {
        return tradeName + "_" + LocalDate.now() + "_" + suffix;
    }

    /**
     * Clears all hit counters (entry + exit) for a given trade name across today's date scope.
     * Called on EOD square-off so tomorrow's session always starts from a clean slate
     * (today's keys simply won't match tomorrow's date-scoped keys either, but this also
     * frees the map entries immediately rather than leaving them to go stale).
     */
    private void resetHitCounters(String tradeName) {
        hitCounters.remove(buildKey(tradeName, "CE_ENTRY"));
        hitCounters.remove(buildKey(tradeName, "PE_ENTRY"));
        hitCounters.remove(buildKey(tradeName, "EXIT_HITS"));
    }
}