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
    // ============================================================

    private static final String STRATEGY_SIGNAL = "DIRECTIONAL_SELL";
    private static final String NAME_PREFIX = "DIR_SELL_";

    private static final LocalTime NIFTY_START = LocalTime.of(9, 20);
    private static final LocalTime NIFTY_ENTRY_CUTOFF = LocalTime.of(15, 0);
    private static final LocalTime NIFTY_SQUARE_OFF = LocalTime.of(15, 20);

    private static final LocalTime CRUDEOIL_START = LocalTime.of(16, 5);
    private static final LocalTime CRUDEOIL_ENTRY_CUTOFF = LocalTime.of(23, 0);
    private static final LocalTime CRUDEOIL_SQUARE_OFF = LocalTime.of(23, 20);

    private static final BigDecimal MAX_ENTRY_DISTANCE_POINTS = BigDecimal.valueOf(15);

    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_INACTIVE = 0;
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String PHASE_ENTRY = "ENTRY";
    private static final String PHASE_EXIT = "EXIT";

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

    // ✅ INJECTED MASTER RISK ENGINE
    private final MonitorOrderService monitorOrderService;

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

        boolean isLiveFlag = "Y".equalsIgnoreCase(strategyConfig.getLive());
        boolean isPaperFlag = "Y".equalsIgnoreCase(strategyConfig.getPapertrade());
        if (isLiveFlag && isPaperFlag) {
            log.error("❌ [{}] Invalid config: both LIVE and PAPERTRADE are 'Y'. Refusing to trade.", tradeName);
            return;
        }

        Optional<PreMarketAnalysis> optData = preMarketRepo.findByNameAndTradingDate(instrumentName, LocalDate.now());
        if (optData.isEmpty()) {
            log.debug("⏳ [{}] PreMarket data not generated yet.", tradeName);
            return;
        }
        PreMarketAnalysis data = optData.get();

        LocalDateTime startOfDay = LocalDateTime.now().with(LocalTime.MIN);
        long tradesUsed = ordersRepository.countLegsToday(tradeName, STRATEGY_SIGNAL, startOfDay);
        int maxAllowed = sourceConfig.getMaxDailyTrades() > 0 ? sourceConfig.getMaxDailyTrades() : 3;

        if (isBeforeSessionStart(instrumentName, now)) return;

        List<Orders> activeOrders = ordersRepository.findByNameAndSignalAndActive(tradeName, STRATEGY_SIGNAL, STATUS_ACTIVE);

        if (!activeOrders.isEmpty()) {
            Orders activeTrade = activeOrders.get(0);

            // 🕒 1. EOD Square-Off (Time-Based)
            if (isSquareOffTime(instrumentName, now)) {
                log.info("🕒 [{}][EXIT] Square-off time reached.", tradeName);
                String exchange = activeTrade.getExchange() != null ? activeTrade.getExchange() : sourceConfig.getExchange();
                BigDecimal ltp = getLivePrice(activeTrade.getToken(), exchange);

                if (ltp != null) {
                    closeTrade(activeTrade, ltp, "EOD_SQUARE_OFF", strategyConfig, sourceConfig);
                    resetHitCounters(activeTrade.getName());
                }
                return;
            }

            // 🛡️ 2. Smart Risk Engine (Price-Based: SL, Targets, Trailing, Panic Drops, PnL Sync)
            if (monitorOrderService.evaluateAndClose(activeOrders, tradeName, null)) {
                resetHitCounters(activeTrade.getName());
            }

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

        BigDecimal ceDistance = ceLtp.subtract(midPoint).abs();
        BigDecimal peDistance = peLtp.subtract(midPoint).abs();
        boolean ceBelowMid = ceLtp.compareTo(midPoint) < 0;
        boolean peBelowMid = peLtp.compareTo(midPoint) < 0;
        boolean ceWithinBand = ceDistance.compareTo(MAX_ENTRY_DISTANCE_POINTS) <= 0;
        boolean peWithinBand = peDistance.compareTo(MAX_ENTRY_DISTANCE_POINTS) <= 0;

        if (ceBelowMid && ceWithinBand) {
            int hits = hitCounters.merge(ceKey, 1, Integer::sum);
            log.info("🎯 [{}] CE ENTRY HITS: ({}/{}) | LTP: {} | MID: {} | Distance: {} pts (within ±{} band)",
                    tradeName, hits, reqHits, ceLtp, midPoint, ceDistance.setScale(2, RoundingMode.HALF_UP), MAX_ENTRY_DISTANCE_POINTS);
            hitCounters.put(peKey, 0);

            if (hits >= reqHits) {
                log.info("⚡ [{}] CE HITS MET! Triggering SELL.", tradeName);
                executeTrade(tradeName, data.getCeToken(), data.getCeSymbol(), data.getAtmStrike(), ceLtp, "CE", strategyConfig, sourceConfig);
                hitCounters.put(ceKey, 0);
            }
        }
        else if (peBelowMid && peWithinBand) {
            int hits = hitCounters.merge(peKey, 1, Integer::sum);
            log.info("🎯 [{}] PE ENTRY HITS: ({}/{}) | LTP: {} | MID: {} | Distance: {} pts (within ±{} band)",
                    tradeName, hits, reqHits, peLtp, midPoint, peDistance.setScale(2, RoundingMode.HALF_UP), MAX_ENTRY_DISTANCE_POINTS);
            hitCounters.put(ceKey, 0);

            if (hits >= reqHits) {
                log.info("⚡ [{}] PE HITS MET! Triggering SELL.", tradeName);
                executeTrade(tradeName, data.getPeToken(), data.getPeSymbol(), data.getAtmStrike(), peLtp, "PE", strategyConfig, sourceConfig);
                hitCounters.put(peKey, 0);
            }
        }
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
                order.setCreatedOn(LocalDateTime.now());
            } else {
                log.warn("⚠️ [{}] Execution skipped. Both LIVE and PAPER flags are 'N' in config.", tradeName);
                return;
            }

            order.setName(tradeName);
            order.setSignal(STRATEGY_SIGNAL);
            order.setOptionType(type);
            order.setTradeCycleId(cycleId);
            order.setAskPrice(price);
            order.setStrike(strike);
            order.setStatus(STATUS_OPEN);
            order.setTradePhase(PHASE_ENTRY);

            // ✅ BUG FIX: Enforce "SELL" type so MonitorOrderService calculates PnL correctly
            order.setType("SELL");

            ordersRepository.save(order);

            String mode = isLive ? "LIVE" : "PAPER";
            telegramService.sendMessage(String.format("🚀 **ENTRY [%s]: %s**\nSide: SHORT %s\nStrike: %s\nPrice: %.2f",
                    mode, tradeName, type, strike, price));

        } catch (Exception | SmartAPIException e) {
            log.error("❌ [{}][LEG] System error during execution: {}", tradeName, e.getMessage());
        }
    }

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

    // 🕒 USED STRICTLY FOR EOD SQUARE-OFF
    protected void closeTrade(Orders order, BigDecimal exitPrice, String reason, Strategy strategyConfig, Strategy sourceConfig) {
        boolean isLive = "Y".equalsIgnoreCase(strategyConfig.getLive());

        try {
            if (isLive) {
                log.info("🌐 [{}][EXIT] LIVE MODE: Sending explicit BUY to close SHORT...", order.getName());

                // ✅ BUG FIX: Explicit Reverse BUY Payload (Solves Multi-Strategy Collisions)
                Token exitToken = new Token();
                exitToken.setSymbol(order.getSymbol());
                exitToken.setToken(order.getToken());
                exitToken.setExch_seg(order.getExchange());
                exitToken.setQuantity(order.getQuantity());

                orderService.orderPlaceWithToken(exitToken, sourceConfig.getName(), "BUY", true);
            } else {
                log.info("📄 [{}][EXIT] PAPER MODE: Simulating broker exit...", order.getName());
            }

            BigDecimal entryPrice = order.getAskPrice() != null ? order.getAskPrice() : BigDecimal.ZERO;
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

    private ExchangeType mapExchangeToType(String exchange) {
        if (exchange == null) return ExchangeType.NSE_FO;
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

        if (price == null || price.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        return price.setScale(2, RoundingMode.HALF_UP);
    }

    private String buildKey(String tradeName, String suffix) {
        return tradeName + "_" + LocalDate.now() + "_" + suffix;
    }

    private void resetHitCounters(String tradeName) {
        hitCounters.remove(buildKey(tradeName, "CE_ENTRY"));
        hitCounters.remove(buildKey(tradeName, "PE_ENTRY"));
        hitCounters.remove(buildKey(tradeName, "EXIT_HITS"));
    }
}