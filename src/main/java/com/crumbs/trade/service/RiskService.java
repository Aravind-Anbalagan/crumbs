package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.angelbroking.smartapi.SmartConnect;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.RiskConfiguration;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.RiskConfigurationRepository;

/**
 * PNL SERVICE: Continuously recalculates and persists live PnL for all
 * active order legs and their strategy groups. Exit / risk-termination
 * logic has intentionally been removed from this class - it is
 * PnL-tracking only.
 *
 * ENTRY PRICE RECONCILIATION:
 * For LIVE legs, Orders.askPrice is expected to equal the broker's actual
 * executed (average) fill price. The first time this engine sees a live
 * leg, it fetches the broker's position data once, compares it against
 * Orders.askPrice, and if they differ:
 *   - Corrects Orders.askPrice in the DB to match the broker (source of truth)
 *   - Logs the discrepancy and the correction
 * The resolved price is then cached in memory for that leg id, so this
 * check runs exactly once per leg - not on every 1-second tick.
 * Paper trade legs are unaffected and continue to use Orders.askPrice as-is.
 */
@Service
public class RiskService {

    private static final Logger logger = LoggerFactory.getLogger(RiskService.class);

    private static final String EXCHANGE_NFO = "NFO";
    private static final String EXCHANGE_BSE = "BSE";
    private static final String EXCHANGE_NSE = "NSE";
    private static final String EXCHANGE_MCX = "MCX";
    private static final String PAPER_ORDER_ID_MARKER = "1";
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    @Autowired
    private AngelOneService angelOneService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private RiskConfigurationRepository riskConfigRepository;

    @Autowired
    private AngelOne angelOne;

    @Autowired
    private AngelWebSocketService webSocketService;

    // --- MEMORY CACHES ---
    private final Map<Long, BigDecimal> liveUiCachePnL = new ConcurrentHashMap<>();

    // One-time-per-leg reconciliation: leg id -> confirmed entry price used for PnL math.
    // Populated once from broker position data (live legs only); never re-queried after that.
    private final Map<Long, BigDecimal> reconciledEntryPriceCache = new ConcurrentHashMap<>();

    public Map<Long, BigDecimal> getLivePnLForUI() {
        return liveUiCachePnL;
    }

    /**
     * PNL EVALUATOR: Runs every 1 second, reading from memory caches.
     */
    @Scheduled(fixedDelay = 1000)
    public void processSystemRiskMatrix() {
        // 🛑 Guard: Execute only on weekdays between 09:15 AM and 11:30 PM IST
        if (!isMarketHours())
            return;

        LocalDateTime now = LocalDateTime.now(MARKET_ZONE);
        int currentHour = now.getHour();
        int currentMinute = now.getMinute();

        // ONLY pulls legs where active = 1 (Ignores morning trades that are
        // already closed)
        List<Orders> rawOpenOrders = orderRepository.findByActive(1);
        if (rawOpenOrders == null || rawOpenOrders.isEmpty()) {
            clearAllMemoryCaches();
            return;
        }

        // Heartbeat log
        if (now.getSecond() == 0) {
            logger.info(
                    "⚙️ [SYSTEM] PnL Engine Active | Monitoring {} live DB rows | UI Cache Size: {}",
                    rawOpenOrders.size(), liveUiCachePnL.size());
        }

        List<Orders> openOrders = rawOpenOrders.stream().filter(order -> {
            String exch = order.getExchange() != null
                    ? order.getExchange().toUpperCase()
                    : "";
            if ((EXCHANGE_NFO.equals(exch) || EXCHANGE_BSE.equals(exch)
                    || EXCHANGE_NSE.equals(exch))
                    && (currentHour >= 16
                            || (currentHour == 15 && currentMinute > 30))) {
                return false;
            }
            if (EXCHANGE_MCX.equals(exch) && currentHour < 9) {
                return false;
            }
            return true;
        }).collect(Collectors.toList());

        if (openOrders.isEmpty()) {
            if (now.getSecond() == 0) {
                logger.info(
                        "💤 [SYSTEM] Market Idle | {} rows found, but 0 are active in this trading session.",
                        rawOpenOrders.size());
            }
            return;
        }

        Set<String> strategyIdentifiers = openOrders.stream()
                .map(Orders::getName).filter(Objects::nonNull)
                .filter(name -> !name.isEmpty()).collect(Collectors.toSet());

        Map<String, RiskConfiguration> configMap = new ConcurrentHashMap<>();
        if (!strategyIdentifiers.isEmpty()) {
            List<RiskConfiguration> configs = riskConfigRepository
                    .findAllById(strategyIdentifiers);
            configMap = configs.stream().collect(Collectors.toMap(
                    RiskConfiguration::getStrategyName, Function.identity()));
        }

        // GROUPING: Combine all active legs under their unique Strategy Name
        Function<Orders, String> strategyNameClassifier = order -> (order
                .getName() != null && !order.getName().trim().isEmpty())
                        ? order.getName().trim()
                        : "ORPHAN_" + order.getId();

        Map<String, List<Orders>> strategyGroups = openOrders.stream()
                .collect(Collectors.groupingBy(strategyNameClassifier));

        // Purge dead memory
        Set<Long> activeOrderIds = openOrders.stream().map(Orders::getId)
                .collect(Collectors.toSet());
        liveUiCachePnL.keySet().retainAll(activeOrderIds);
        reconciledEntryPriceCache.keySet().retainAll(activeOrderIds);

        SmartConnect connection = null;
        try {
            connection = angelOne.signIn();
        } catch (Exception e) {
            logger.error("❌ [SYSTEM] Broker Auth Failed: {}", e.getMessage());
            return; // Needs connection for live pricing
        }

        // EVALUATE GROUPS (1 leg, 2 legs, 4 legs)
        for (Map.Entry<String, List<Orders>> entry : strategyGroups.entrySet()) {
            String strategyKey = entry.getKey();
            List<Orders> groupLegs = entry.getValue();

            RiskConfiguration config = configMap.get(strategyKey);
            BigDecimal combinedGroupPnL = BigDecimal.ZERO;

            for (Orders leg : groupLegs) {
                try {
                    boolean isLiveTrade = leg.getOrderid() != null
                            && !PAPER_ORDER_ID_MARKER.equals(leg.getOrderid());
                    BigDecimal legPnL = BigDecimal.ZERO;

                    if (isLiveTrade) {

                        BigDecimal currentLtp = BigDecimal.ZERO;

                        com.angelbroking.smartapi.smartstream.models.ExchangeType exchangeType = mapExchangeToType(
                                leg.getExchange());

                        if (exchangeType != null) {
                            webSocketService.subscribe(exchangeType,
                                    leg.getToken());

                            currentLtp = webSocketService
                                    .getLatestLTP(exchangeType, leg.getToken());
                        }

                        if (currentLtp == null
                                || currentLtp.compareTo(BigDecimal.ZERO) == 0) {

                            currentLtp = angelOneService.getcurrentPrice(
                                    connection, leg.getExchange(),
                                    leg.getSymbol(), leg.getToken());
                        }

                        // Entry price for LIVE legs is reconciled against the broker
                        // exactly once per leg (see resolveLiveEntryPrice javadoc).
                        BigDecimal entryPrice = resolveLiveEntryPrice(leg, connection);

                        if (currentLtp != null
                                && currentLtp.compareTo(BigDecimal.ZERO) > 0
                                && entryPrice != null
                                && entryPrice.compareTo(BigDecimal.ZERO) > 0) {

                            BigDecimal pointsDiff;

                            boolean isShort = isShortPosition(leg, config);
                            if (isShort) {
                                pointsDiff = entryPrice.subtract(currentLtp); // Sell math: Entry - LTP
                            } else {
                                pointsDiff = currentLtp.subtract(entryPrice); // Buy math: LTP - Entry
                            }

                            legPnL = pointsDiff.multiply(
                                    BigDecimal.valueOf(leg.getQuantity()));
                        }
                    } else {
                        try {
                            BigDecimal currentLtp = BigDecimal.ZERO;
                            com.angelbroking.smartapi.smartstream.models.ExchangeType exchangeType = mapExchangeToType(
                                    leg.getExchange());

                            if (exchangeType != null) {
                                webSocketService.subscribe(exchangeType,
                                        leg.getToken());
                                currentLtp = webSocketService.getLatestLTP(
                                        exchangeType, leg.getToken());
                            }

                            if (currentLtp == null || currentLtp
                                    .compareTo(BigDecimal.ZERO) == 0) {
                                if (connection != null) {
                                    currentLtp = angelOneService
                                            .getcurrentPrice(connection,
                                                    leg.getExchange(),
                                                    leg.getSymbol(),
                                                    leg.getToken());
                                }
                            }

                            // Paper trades: no broker fill exists, keep using DB askPrice as-is.
                            if (currentLtp != null
                                    && currentLtp.compareTo(BigDecimal.ZERO) > 0
                                    && leg.getAskPrice() != null) {

                                boolean isShort = isShortPosition(leg, config);
                                BigDecimal pointsDiff = isShort
                                        ? leg.getAskPrice().subtract(currentLtp)
                                        : currentLtp.subtract(leg.getAskPrice());

                                legPnL = pointsDiff.multiply(
                                        BigDecimal.valueOf(leg.getQuantity()));
                            }
                        } catch (Exception e) {
                            logger.error(
                                    "❌ [SYSTEM] Math Error on Paper Leg {}: {}",
                                    leg.getId(), e.getMessage());
                        }
                    }

                    combinedGroupPnL = combinedGroupPnL.add(legPnL);
                    liveUiCachePnL.put(leg.getId(), legPnL);
                }

                catch (Exception e) {
                    logger.error("Failed PnL calculation for {}",
                            leg.getSymbol(), e);

                    liveUiCachePnL.put(leg.getId(), BigDecimal.ZERO);
                    continue;
                }
            }

            // NOTE: PnL is intentionally NOT persisted to the DB every tick.
            // liveUiCachePnL (in-memory) is the source of truth for the API/UI
            // response - writing 'pl' to Orders every second for every open leg
            // would add unnecessary DB load. Only askPrice corrections (one-time,
            // on mismatch) touch the DB from this service.

            if (now.getSecond() == 0) {
                logger.info("📊 [PNL] Strategy '{}' | Combined PnL: {}",
                        strategyKey, combinedGroupPnL.setScale(2, java.math.RoundingMode.HALF_UP));
            }
        }
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
                                    logger.warn(
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
        } catch (Exception e) {
            logger.error("❌ [PNL] Could not fetch broker position for leg {} - falling back to DB askPrice: {}",
                    leg.getId(), e.getMessage());
            // resolvedPrice already defaults to dbAskPrice above
        }

        reconciledEntryPriceCache.put(leg.getId(), resolvedPrice); // cache regardless - never re-checked
        return resolvedPrice;
    }

    /**
     * Corrects Orders.askPrice in the DB to match the broker's executed price.
     * Runs only when a discrepancy is found (once per leg, at most).
     */
    @Transactional
    public void correctAskPriceInDb(Orders leg, BigDecimal brokerPrice) {
        leg.setAskPrice(brokerPrice);
        orderRepository.save(leg);
        logger.info(
            "✅ [PNL-SYNC] Leg {} ({}) | Orders.askPrice updated to broker executed price {} | PnL will now be in sync with broker.",
            leg.getId(), leg.getSymbol(), brokerPrice);
    }

    private void clearAllMemoryCaches() {
        if (!liveUiCachePnL.isEmpty() || !reconciledEntryPriceCache.isEmpty()) {
            liveUiCachePnL.clear();
            reconciledEntryPriceCache.clear();
            logger.info("🧹 [SYSTEM] Engine Flushed | Zero active trades remaining.");
        }
    }

    private com.angelbroking.smartapi.smartstream.models.ExchangeType mapExchangeToType(String exchange) {
        if (exchange == null) return null;
        switch (exchange.toUpperCase().trim()) {
            case "NFO": return com.angelbroking.smartapi.smartstream.models.ExchangeType.NSE_FO;
            case "MCX": return com.angelbroking.smartapi.smartstream.models.ExchangeType.MCX_FO;
            case "NSE": return com.angelbroking.smartapi.smartstream.models.ExchangeType.NSE_CM;
            case "BSE": return com.angelbroking.smartapi.smartstream.models.ExchangeType.BSE_CM;
            case "BFO": return com.angelbroking.smartapi.smartstream.models.ExchangeType.BSE_FO;
            default: return null;
        }
    }

    /**
     * Evaluates if the current Indian market time is within active trading hours:
     * Monday to Friday, 09:15 AM to 11:30 PM.
     */
    private boolean isMarketHours() {
        ZonedDateTime now = ZonedDateTime.now(MARKET_ZONE);
        DayOfWeek day = now.getDayOfWeek();

        // 1. Block Weekends
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }

        // 2. Check Window: 09:15:00 to 23:30:00
        LocalTime time = now.toLocalTime();
        LocalTime startTime = LocalTime.of(9, 15);
        LocalTime endTime = LocalTime.of(23, 30);

        return !time.isBefore(startTime) && !time.isAfter(endTime);
    }

    private boolean isShortPosition(Orders leg, RiskConfiguration config) {
        if (config != null && config.getStrategyType() != null) {
            return "OPTION_SELL".equalsIgnoreCase(config.getStrategyType());
        }
        return "SELL".equalsIgnoreCase(leg.getType());
    }
}