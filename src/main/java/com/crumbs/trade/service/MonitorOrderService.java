package com.crumbs.trade.service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import com.angelbroking.smartapi.utils.Constants;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.RiskConfiguration;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.RiskConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Self-contained risk monitor + closer + PnL/reconciliation engine. Called
 * INLINE by whichever strategy service owns a given group of legs (same
 * thread, same transaction context as that strategy's own tick) - no
 * separate scheduler, no cross-service pessimistic locking.
 *
 * This absorbs everything RiskService used to do independently:
 *  - live PnL calculation (LIVE and PAPER legs)
 *  - one-time-per-leg broker fill-price reconciliation for LIVE legs
 *  - hard max-loss / target-profit
 *  - velocity panic drop (fast single-tick collapse, always active)
 *  - HWM / milestone-protection / trailing (rubber-band) drawdown
 *  - actual broker exit + DB close + Telegram notification
 *
 * DB WRITE POLICY: `pl` (running PnL) is kept in-memory only
 * (liveUiCachePnL) and is NEVER written to the DB on a per-tick basis -
 * only at actual close time. Peak/trailing-floor are persisted onto
 * RiskConfiguration ONLY when they actually change (not every tick).
 * askPrice is corrected in the DB only once per leg, only on a genuine
 * broker-fill discrepancy. This keeps DB writes rare, not per-second.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorOrderService {

    private static final String STATUS_CLOSED = "CLOSED";
    private static final String PHASE_EXIT = "EXIT";
    private static final String PHASE_EXIT_IN_PROGRESS = "EXIT_IN_PROGRESS";
    private static final String PHASE_ENTRY = "ENTRY";
    private static final String SMART_RISK_ACTIVE = "Y";
    private static final String PAPER_ORDER_ID_MARKER = "1";
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    private final OrderRepository ordersRepository;
    private final RiskConfigurationRepository riskConfigRepository;
    private final AngelOneService angelOneService;
    private final AngelWebSocketService webSocketService;
    private final AngelOne angelOne;
    private final TelegramService telegramService;

    // --- Shared memory state across every strategy routed through here ---
    private final Map<Long, BigDecimal> liveUiCachePnL = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> highWaterMarks = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> activeTrailingFloors = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> lastPnlSnapshot = new ConcurrentHashMap<>();
    private final Map<String, Integer> panicStreak = new ConcurrentHashMap<>();

    // One-time-per-leg reconciliation: leg id -> confirmed entry price used for PnL math.
    // Populated once from broker position data (LIVE legs only); never re-queried after that.
    private final Map<Long, BigDecimal> reconciledEntryPriceCache = new ConcurrentHashMap<>();

    public Map<Long, BigDecimal> getLivePnLForUI() {
        return liveUiCachePnL;
    }

    /**
     * Evaluates risk for one strategy group and closes it if a rule fires.
     * Order of checks: hard max-loss -> target-profit -> velocity panic drop
     * -> HWM/milestone -> trailing/rubber-band.
     *
     * @param groupLegs   currently active legs for this strategy/cycle
     * @param strategyKey RiskConfiguration key, e.g. "SHORT_STRADDLE_NIFTY"
     * @param connection  existing broker session if the caller already has one, else null
     *                    (a session will be created lazily here if any live leg needs one)
     * @return true if this call closed the group
     */
    public boolean evaluateAndClose(List<Orders> groupLegs, String strategyKey, SmartConnect connection) {
        if (groupLegs == null || groupLegs.isEmpty()) {
            return false;
        }

        RiskConfiguration config = riskConfigRepository.findById(strategyKey).orElse(null);

        boolean hasLiveLeg = groupLegs.stream().anyMatch(this::isLiveTrade);
        SmartConnect activeConnection = connection;
        if (hasLiveLeg && activeConnection == null) {
            try {
                activeConnection = angelOne.signIn();
            } catch (Exception e) {
                log.error("❌ [MONITOR] Broker auth failed for {}: {}", strategyKey, e.getMessage());
                // Continue - LTP may still resolve via websocket, reconciliation just gets skipped for this tick
            }
        }

        // ---- 1. Price each leg, compute combined PnL ----
        BigDecimal combinedGroupPnL = BigDecimal.ZERO;
        boolean allLegsPriced = true;

        for (Orders leg : groupLegs) {
            BigDecimal legPnL = BigDecimal.ZERO;
            try {
                BigDecimal currentLtp = BigDecimal.ZERO;
                ExchangeType exchangeType = mapExchangeToType(leg.getExchange());

                if (exchangeType != null) {
                    webSocketService.subscribe(exchangeType, leg.getToken());
                    currentLtp = webSocketService.getLatestLTP(exchangeType, leg.getToken());
                }

                if (currentLtp == null || currentLtp.compareTo(BigDecimal.ZERO) == 0) {
                    if (activeConnection != null) {
                        currentLtp = angelOneService.getcurrentPrice(activeConnection, leg.getExchange(), leg.getSymbol(), leg.getToken());
                    }
                }

                // Entry price: LIVE legs reconciled against the broker's actual fill (once per leg,
                // then cached), PAPER legs always use DB askPrice as-is (no broker fill exists).
                BigDecimal entryPrice = isLiveTrade(leg)
                        ? resolveLiveEntryPrice(leg, activeConnection)
                        : leg.getAskPrice();

                if (currentLtp != null && currentLtp.compareTo(BigDecimal.ZERO) > 0
                        && entryPrice != null && entryPrice.compareTo(BigDecimal.ZERO) > 0) {
                    boolean isShort = isShortPosition(leg, config);
                    BigDecimal pointsDiff = isShort
                            ? entryPrice.subtract(currentLtp)   // Sell math: Entry - LTP
                            : currentLtp.subtract(entryPrice);  // Buy math: LTP - Entry
                    legPnL = pointsDiff.multiply(BigDecimal.valueOf(leg.getQuantity()));
                } else {
                    allLegsPriced = false;
                }
            } catch (Exception e) {
                log.error("❌ [MONITOR] PnL calc failed for {}: {}", leg.getSymbol(), e.getMessage());
                allLegsPriced = false;
            }

            combinedGroupPnL = combinedGroupPnL.add(legPnL);
            liveUiCachePnL.put(leg.getId(), legPnL);
            // NOTE: `pl` is intentionally NOT written to the DB here. In-memory
            // liveUiCachePnL is the source of truth for UI/API until actual close.
        }

        // ---- 2. Hard ceilings: max loss / target profit ----
        Orders primaryLeg = groupLegs.get(0);
        BigDecimal totalQuantity = groupLegs.stream()
                .map(Orders::getQuantity)
                .filter(Objects::nonNull)
                .map(BigDecimal::valueOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal maxLossThreshold = null;
        BigDecimal targetProfitThreshold = null;

        if (config != null) {
            maxLossThreshold = config.getMaxLossLimit();
            targetProfitThreshold = config.getTargetProfit();
        } else {
            if (primaryLeg.getSl() != null && primaryLeg.getSl().compareTo(BigDecimal.ZERO) > 0) {
                maxLossThreshold = primaryLeg.getSl().multiply(totalQuantity).negate();
            }
            if (primaryLeg.getTarget() != null && primaryLeg.getTarget().compareTo(BigDecimal.ZERO) > 0) {
                targetProfitThreshold = primaryLeg.getTarget().multiply(totalQuantity);
            }
        }

        if (maxLossThreshold != null) {
            BigDecimal absoluteMaxLoss = maxLossThreshold.abs().negate();
            if (combinedGroupPnL.compareTo(absoluteMaxLoss) <= 0) {
                return closeGroup(groupLegs, strategyKey, "HARD_MAX_LOSS_BREACHED", combinedGroupPnL, activeConnection, config);
            }
        }

        if (targetProfitThreshold != null && targetProfitThreshold.compareTo(BigDecimal.ZERO) > 0) {
            if (combinedGroupPnL.compareTo(targetProfitThreshold) >= 0) {
                return closeGroup(groupLegs, strategyKey, "FIXED_TARGET_PROFIT_HIT", combinedGroupPnL, activeConnection, config);
            }
        }

        if (config == null || !SMART_RISK_ACTIVE.equalsIgnoreCase(config.getSmartRiskFlag())) {
            return false; // smart-risk rules not enabled for this strategy
        }

        // ---- 3. Velocity panic drop: fast single-tick collapse, independent of HWM/trailing state ----
        BigDecimal velocityDrop = config.getVelocityPanicDrop();
        if (velocityDrop != null && velocityDrop.compareTo(BigDecimal.ZERO) > 0) {
            if (!allLegsPriced) {
                log.debug("⚠️ [MONITOR] {} | Skipping velocity check this tick - incomplete pricing.", strategyKey);
            } else {
                BigDecimal lastPnl = lastPnlSnapshot.get(strategyKey);
                if (lastPnl != null) {
                    BigDecimal drop = lastPnl.subtract(combinedGroupPnL);
                    if (drop.compareTo(velocityDrop) >= 0) {
                        int streak = panicStreak.merge(strategyKey, 1, Integer::sum);
                        log.warn("⚡ [MONITOR] {} | Velocity drop detected: {} pts (streak {}/2)",
                                strategyKey, drop.setScale(2, RoundingMode.HALF_UP), streak);
                        if (streak >= 2) {
                            panicStreak.remove(strategyKey);
                            lastPnlSnapshot.remove(strategyKey);
                            return closeGroup(groupLegs, strategyKey, "VELOCITY_PANIC_DROP_BREACHED", combinedGroupPnL, activeConnection, config);
                        }
                    } else {
                        panicStreak.remove(strategyKey);
                    }
                }
                lastPnlSnapshot.put(strategyKey, combinedGroupPnL);
            }
        }

        // ---- 4. HWM tracking (DB write ONLY when peak actually changes) ----
        BigDecimal peakPnL = highWaterMarks.get(strategyKey);
        if (peakPnL == null) {
            peakPnL = config.getCurrentPeakPnl() != null ? config.getCurrentPeakPnl() : combinedGroupPnL;
            highWaterMarks.put(strategyKey, peakPnL);
        }

        if (combinedGroupPnL.compareTo(peakPnL) > 0) {
            peakPnL = combinedGroupPnL;
            highWaterMarks.put(strategyKey, peakPnL);
            config.setCurrentPeakPnl(peakPnL);
            riskConfigRepository.save(config);
        }

        // ---- 5. Milestone / breakeven-floor protection ----
        if (targetProfitThreshold != null && config.getMilestonePercent() != null && config.getBreakevenFloor() != null) {
            BigDecimal milestoneActivation = targetProfitThreshold.multiply(config.getMilestonePercent());
            if (peakPnL.compareTo(milestoneActivation) >= 0) {
                if (combinedGroupPnL.compareTo(config.getBreakevenFloor()) <= 0) {
                    return closeGroup(groupLegs, strategyKey, "MILESTONE_PROFIT_PROTECTION_TRIGGERED", combinedGroupPnL, activeConnection, config);
                }
            }
        }

        // ---- 6. Trailing / rubber-band drawdown (DB write ONLY when floor actually rises) ----
        BigDecimal activation = config.getTrailingActivation();
        BigDecimal drawdownPct = config.getTrailingDrawdownPct();

        if (activation != null && drawdownPct != null && peakPnL.compareTo(activation) >= 0) {
            BigDecimal allowedDrop = peakPnL.multiply(drawdownPct);
            BigDecimal dynamicFloor = peakPnL.subtract(allowedDrop);
            BigDecimal existingFloor = activeTrailingFloors.get(strategyKey);

            if (existingFloor == null) {
                existingFloor = config.getCurrentTrailingFloor();
                if (existingFloor != null) {
                    activeTrailingFloors.put(strategyKey, existingFloor);
                }
            }

            if (existingFloor == null || dynamicFloor.compareTo(existingFloor) > 0) {
                activeTrailingFloors.put(strategyKey, dynamicFloor);
                config.setCurrentTrailingFloor(dynamicFloor);
                riskConfigRepository.save(config);
                log.info("📈 [MONITOR] {} | Peak: {} | Trailing floor raised to: {}",
                        strategyKey, peakPnL.setScale(2, RoundingMode.HALF_UP), dynamicFloor.setScale(2, RoundingMode.HALF_UP));
            }

            BigDecimal currentFloor = activeTrailingFloors.get(strategyKey);
            if (currentFloor != null && combinedGroupPnL.compareTo(currentFloor) <= 0) {
                return closeGroup(groupLegs, strategyKey, "RUBBER_BAND_MAX_DRAWDOWN_BREACHED", combinedGroupPnL, activeConnection, config);
            }
        }

        return false;
    }

    /**
     * ONE-TIME reconciliation per live leg (runs once, then cached in-memory
     * for that leg id for the remainder of the session).
     *
     * Invariant: for a LIVE order, Orders.askPrice MUST equal the broker's
     * executed (avg) fill price - askPrice is expected to be written from the
     * broker fill, not a pre-trade quote. This check exists to CATCH and
     * CORRECT discrepancies (bad write-back, race condition, partial-fill
     * average not saved correctly, etc.), not to arbitrate between two
     * equally-valid prices.
     *
     * On mismatch: the broker price is treated as the source of truth,
     * Orders.askPrice is updated to match it, and the correction is logged.
     * Either way, the resolved price is cached so this leg is never
     * re-checked against the broker again this session.
     */
    private BigDecimal resolveLiveEntryPrice(Orders leg, SmartConnect connection) {
        BigDecimal cached = reconciledEntryPriceCache.get(leg.getId());
        if (cached != null) {
            return cached; // already reconciled - skip broker lookup entirely
        }

        BigDecimal dbAskPrice = leg.getAskPrice();
        BigDecimal resolvedPrice = dbAskPrice != null ? dbAskPrice : BigDecimal.ZERO;

        if (connection == null) {
            // No session available this tick - fall back to DB price for now,
            // but do NOT cache, so reconciliation is retried once a session exists.
            return resolvedPrice;
        }

        try {
            JSONObject positionResponse = connection.getPosition();
            if (positionResponse != null && positionResponse.optBoolean("status", false)) {
                JSONArray positions = positionResponse.optJSONArray("data");
                if (positions != null) {
                    for (int i = 0; i < positions.length(); i++) {
                        JSONObject pos = positions.getJSONObject(i);
                        if (leg.getToken() != null
                                && leg.getToken().equals(pos.optString("symboltoken"))) {

                            String avgPriceStr = pos.optString("avgnetprice",
                                    pos.optString("averageprice", "0"));
                            BigDecimal brokerPrice = new BigDecimal(avgPriceStr);

                            if (brokerPrice.compareTo(BigDecimal.ZERO) > 0) {
                                if (dbAskPrice == null || brokerPrice.compareTo(dbAskPrice) != 0) {
                                    log.warn(
                                        "🚨 [PNL-DISCREPANCY] Leg {} ({}) | DB askPrice={} != Broker fill={} | "
                                      + "Live orders must match - correcting Orders.askPrice to broker value.",
                                        leg.getId(), leg.getSymbol(), dbAskPrice, brokerPrice);

                                    correctAskPriceInDb(leg, brokerPrice);
                                }
                                resolvedPrice = brokerPrice;
                            }
                            break;
                        }
                    }
                }
            }
            reconciledEntryPriceCache.put(leg.getId(), resolvedPrice); // cache only once we actually consulted the broker
        } catch (Exception e) {
            log.error("❌ [MONITOR] Could not fetch broker position for leg {} - falling back to DB askPrice: {}",
                    leg.getId(), e.getMessage());
            // Not cached - retried next tick since we never got a confirmed broker read
        }

        return resolvedPrice;
    }

    /**
     * Corrects Orders.askPrice in the DB to match the broker's executed price.
     * Runs only when a discrepancy is found (at most once per leg).
     */
    @Transactional
    public void correctAskPriceInDb(Orders leg, BigDecimal brokerPrice) {
        leg.setAskPrice(brokerPrice);
        ordersRepository.save(leg);
        log.info(
            "✅ [PNL-SYNC] Leg {} ({}) | Orders.askPrice updated to broker executed price {} | PnL will now be in sync with broker.",
            leg.getId(), leg.getSymbol(), brokerPrice);
    }

    private boolean closeGroup(List<Orders> group, String strategyKey, String exitReason,
                                BigDecimal closurePnL, SmartConnect backupConnection, RiskConfiguration config) {

        log.warn("====================================================");
        log.warn("🛑 [MONITOR] STRATEGY LIQUIDATED: {}", strategyKey);
        log.warn("🛑 Reason     : {}", exitReason);
        log.warn("🛑 Final PnL  : {}", closurePnL.setScale(2, RoundingMode.HALF_UP));
        log.warn("🛑 Leg Count  : {}", group.size());
        log.warn("====================================================");

        SmartConnect connection = backupConnection;
        try {
            if (connection == null) {
                connection = angelOne.signIn();
            }
        } catch (Exception e) {
            log.error("❌ [MONITOR] Broker auth failed during close for {}: {}", strategyKey, e.getMessage());
        }

        int closedCount = 0;
        BigDecimal totalRupeePnL = BigDecimal.ZERO;

        for (Orders leg : group) {
            leg.setTradePhase(PHASE_EXIT_IN_PROGRESS);
            ordersRepository.saveAndFlush(leg);

            boolean isLiveTrade = isLiveTrade(leg);
            boolean orderPlaced = !isLiveTrade; // paper legs need no broker call

            if (isLiveTrade && connection != null) {
                Token exitToken = new Token();
                exitToken.setSymbol(leg.getSymbol());
                exitToken.setToken(leg.getToken());
                exitToken.setExch_seg(leg.getExchange());
                exitToken.setQuantity(leg.getQuantity());
                exitToken.setOrderType(Constants.ORDER_TYPE_MARKET);
                exitToken.setProductType(Constants.PRODUCT_CARRYFORWARD);
                exitToken.setVariety(Constants.VARIETY_NORMAL);

                boolean isShort = isShortPosition(leg, config);
                exitToken.setTransactionType(isShort ? Constants.TRANSACTION_TYPE_BUY : Constants.TRANSACTION_TYPE_SELL);

                int attempt = 0;
                long backoff = 500;
                while (attempt < 3 && !orderPlaced) {
                    try {
                        Token response = angelOneService.placeOrder(connection, exitToken);
                        if (response != null && response.getOrderId() != null) {
                            log.info("   -> ✅ [MONITOR] Live leg exited | {} | OrderID: {}", leg.getSymbol(), response.getOrderId());
                            orderPlaced = true;
                        } else {
                            throw new SmartAPIException("Empty Order ID");
                        }
                    } catch (Exception | SmartAPIException e) {
                        attempt++;
                        log.error("   -> ⚠️ [MONITOR] Exit failed for {} (attempt {}/3): {}", leg.getSymbol(), attempt, e.getMessage());
                        if (attempt >= 3) break;
                        try {
                            Thread.sleep(backoff);
                            backoff *= 2;
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            } else if (!isLiveTrade) {
                log.info("   -> 📝 [MONITOR] Paper leg simulated | {}", leg.getSymbol());
            }

            if (!orderPlaced) {
                leg.setTradePhase(PHASE_ENTRY);
                ordersRepository.saveAndFlush(leg);
                continue;
            }

            BigDecimal finalLegPnL = liveUiCachePnL.getOrDefault(leg.getId(), BigDecimal.ZERO);
            liveUiCachePnL.remove(leg.getId());
            reconciledEntryPriceCache.remove(leg.getId());

            BigDecimal calculatedExitPrice = leg.getAskPrice() != null ? leg.getAskPrice() : BigDecimal.ZERO;
            if (leg.getAskPrice() != null && leg.getQuantity() > 0) {
                BigDecimal qty = BigDecimal.valueOf(leg.getQuantity());
                BigDecimal pnlPerUnit = finalLegPnL.divide(qty, 4, RoundingMode.HALF_UP);
                calculatedExitPrice = isShortPosition(leg, config)
                        ? leg.getAskPrice().subtract(pnlPerUnit)
                        : leg.getAskPrice().add(pnlPerUnit);
                calculatedExitPrice = calculatedExitPrice.setScale(2, RoundingMode.HALF_UP);
            }

            leg.setActive(0);
            leg.setStatus(STATUS_CLOSED);
            leg.setTradePhase(PHASE_EXIT);
            leg.setClosedOn(LocalDateTime.now(MARKET_ZONE));
            leg.setExitReason(exitReason);
            leg.setPl(finalLegPnL); // final PnL persisted HERE ONLY, at actual close
            if (calculatedExitPrice.compareTo(BigDecimal.ZERO) > 0) {
                leg.setExitPrice(calculatedExitPrice);
            }
            ordersRepository.save(leg);

            totalRupeePnL = totalRupeePnL.add(finalLegPnL);
            closedCount++;
        }

        highWaterMarks.remove(strategyKey);
        activeTrailingFloors.remove(strategyKey);
        lastPnlSnapshot.remove(strategyKey);
        panicStreak.remove(strategyKey);

        if (closedCount > 0) {
            String emoji = totalRupeePnL.signum() >= 0 ? "✅" : "❌";
            telegramService.sendMessage(String.format(
                    "%s **RISK EXIT [%s]**\nReason: %s\nPnL: **₹%.2f**\nLegs Closed: %d/%d",
                    emoji, strategyKey, exitReason, totalRupeePnL, closedCount, group.size()));
        }

        return closedCount > 0;
    }

    private boolean isLiveTrade(Orders leg) {
        return leg.getOrderid() != null && !PAPER_ORDER_ID_MARKER.equals(leg.getOrderid());
    }

    /**
     * Determines buy vs. sell math. Config's strategyType is authoritative
     * ("OPTION_SELL" = short) since that's set explicitly per strategy in
     * risk_configurations; leg.getType() is only a fallback for legs whose
     * strategy has no RiskConfiguration row yet. Getting this wrong flips
     * the sign of every PnL/risk calculation for that leg, so this is the
     * single method both PnL calc and broker exit-side selection route through.
     */
    private boolean isShortPosition(Orders leg, RiskConfiguration config) {
        if (config != null && config.getStrategyType() != null) {
            return "OPTION_SELL".equalsIgnoreCase(config.getStrategyType());
        }
        return "SELL".equalsIgnoreCase(leg.getType());
    }

    private ExchangeType mapExchangeToType(String exchange) {
        if (exchange == null) return null;
        switch (exchange.toUpperCase().trim()) {
            case "NFO": return ExchangeType.NSE_FO;
            case "MCX": return ExchangeType.MCX_FO;
            case "NSE": return ExchangeType.NSE_CM;
            case "BSE": return ExchangeType.BSE_CM;
            case "BFO": return ExchangeType.BSE_FO;
            default: return null;
        }
    }
}