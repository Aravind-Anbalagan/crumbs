package com.crumbs.trade.service;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.entity.StraddleIntraday;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.ShortStraddleRepository;
import com.crumbs.trade.repo.StrategyRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class DirectionalTrendService {

    private static final String STRATEGY_SIGNAL = "DIRECTIONAL_TREND";
    private static final String NAME_PREFIX = "DIRECTIONAL_TREND_";
    
    // Status & State Tracking Constants
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_INACTIVE = 0;
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String PHASE_ENTRY = "ENTRY";
    private static final String PHASE_EXIT = "EXIT";

    // --- Dynamic Time Configuration Matrix ---
    // Equity Indices Limits
    private static final LocalTime INDEX_MORN_START = LocalTime.of(9, 20);
    private static final LocalTime INDEX_MORN_STOP  = LocalTime.of(12, 55);
    private static final LocalTime INDEX_MORN_SQR   = LocalTime.of(13, 00); 

    private static final LocalTime INDEX_NOON_START = LocalTime.of(13, 30);
    private static final LocalTime INDEX_NOON_STOP  = LocalTime.of(15, 10);
    private static final LocalTime INDEX_NOON_SQR   = LocalTime.of(15, 15); 

    // MCX Commodities Limits
    private static final LocalTime COMM_MORN_START = LocalTime.of(16, 00);
    private static final LocalTime COMM_MORN_STOP  = LocalTime.of(18, 25);
    private static final LocalTime COMM_MORN_SQR   = LocalTime.of(18, 30); 

    private static final LocalTime COMM_NOON_START = LocalTime.of(19, 30);
    private static final LocalTime COMM_NOON_STOP  = LocalTime.of(23, 10);
    private static final LocalTime COMM_NOON_SQR   = LocalTime.of(23, 15); 

    private final ShortStraddleRepository straddleRepository;
    private final OrderRepository ordersRepository;
    private final StrategyRepo strategyRepo;
    private final OrderService orderService;
    private final TelegramService telegramService;
    private final AngelWebSocketService angelWebSocketService;

    private final ConcurrentHashMap<String, Integer> hitCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BigDecimal> lastSeenStrikes = new ConcurrentHashMap<>();

    public void evaluate(String symbol) {
        LocalTime now = LocalTime.now();
        String baseSymbol = symbol.toUpperCase().replace(NAME_PREFIX, "");
        String tradeName = NAME_PREFIX + baseSymbol;

        Strategy strategyConfig = strategyRepo.findByName(STRATEGY_SIGNAL);
        Strategy sourceConfig = strategyRepo.findByName(baseSymbol);        
        
        if (strategyConfig == null || sourceConfig == null) {
            log.error("❌ Configuration mapping properties missing for token asset: {}", baseSymbol);
            return;
        }

        // Search exclusively for open legs tagged to this tracking strategy signature
        List<Orders> activeOrders = ordersRepository.findByNameAndSignalAndActive(tradeName, STRATEGY_SIGNAL, STATUS_ACTIVE);

        if (!activeOrders.isEmpty()) {
            // Leg is open -> Handle session-specific risk parameters
            BigDecimal tradedStrike = activeOrders.get(0).getStrike();
            straddleRepository.findLatestBySymbolAndStrike(baseSymbol, tradedStrike).ifPresentOrElse(
                tick -> processDynamicExitSequence(tradeName, baseSymbol, tick, activeOrders.get(0), strategyConfig, sourceConfig, now),
                () -> log.error("❌ [{}][MONITOR] Target ticker feed offline for strike: {}", tradeName, tradedStrike)
            );
        } else {
            // No position active -> Verify timeline parameters before evaluating entry scans
            if (!isWithinValidEntryWindow(baseSymbol, now)) {
                return; 
            }

            Optional<BigDecimal> atmStrikeOpt = Optional.empty();
            
            if ("NIFTY".equalsIgnoreCase(baseSymbol)) {
                atmStrikeOpt = getNiftyIndexAtm(tradeName);
            } else if ("SENSEX".equalsIgnoreCase(baseSymbol)) {
                atmStrikeOpt = getSensexIndexAtm(tradeName);
            } else {
                // Commodities: Query ATM directly using your original framework method
                atmStrikeOpt = straddleRepository.findATMBySymbol(baseSymbol).map(StraddleIntraday::getStrike);
            }

            atmStrikeOpt.ifPresent(atmStrike -> 
                straddleRepository.findLatestBySymbolAndStrike(baseSymbol, atmStrike).ifPresent(tick -> 
                    processEntrySequence(tradeName, tick, strategyConfig, sourceConfig)
                )
            );
        }
    }

    private boolean isWithinValidEntryWindow(String symbol, LocalTime now) {
        if ("NIFTY".equalsIgnoreCase(symbol) || "SENSEX".equalsIgnoreCase(symbol)) {
            boolean isMorning = (!now.isBefore(INDEX_MORN_START)) && now.isBefore(INDEX_MORN_STOP);
            boolean isAfternoon = (!now.isBefore(INDEX_NOON_START)) && now.isBefore(INDEX_NOON_STOP);
            return isMorning || isAfternoon;
        } else {
            boolean isCommMorn = (!now.isBefore(COMM_MORN_START)) && now.isBefore(COMM_MORN_STOP);
            boolean isCommNoon = (!now.isBefore(COMM_NOON_START)) && now.isBefore(COMM_NOON_STOP);
            return isCommMorn || isCommNoon;
        }
    }

    private void processEntrySequence(String tradeName, StraddleIntraday tick, Strategy strategyConfig, Strategy sourceConfig) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        BigDecimal currentStrike = tick.getStrike(); 
        int reqHits = sourceConfig.getEntryHitsRequired() > 0 ? sourceConfig.getEntryHitsRequired() : 3;

        // Entry Logic Metric Trigger: Premium expands above the VWAP ceiling
        if (cp.compareTo(cv) > 0) {
            String strikeKey = tradeName + "_STRIKE";
            BigDecimal lastStrike = lastSeenStrikes.get(strikeKey);
            
            if (lastStrike != null && lastStrike.compareTo(currentStrike) != 0) {
                hitCounters.put(tradeName + "_ENTRY", 1); 
            } else {
                hitCounters.merge(tradeName + "_ENTRY", 1, Integer::sum);
            }
            
            lastSeenStrikes.put(strikeKey, currentStrike);
            int count = hitCounters.getOrDefault(tradeName + "_ENTRY", 0);
            
            if (count >= reqHits) {
                boolean isCeBreakingOut = tick.getCePrice().compareTo(tick.getCeVwap()) > 0;
                boolean isPeBreakingOut = tick.getPePrice().compareTo(tick.getPeVwap()) > 0;

                if (isCeBreakingOut && !isPeBreakingOut) {
                    log.info("⚡ [{}][ENTRY] Bullish trend established on strike {}. Selling PE.", tradeName, currentStrike);
                    executeDirectionalLeg(tradeName, tick, strategyConfig, sourceConfig, "PE");
                    resetCounters(tradeName, strikeKey);
                } else if (isPeBreakingOut && !isCeBreakingOut) {
                    log.info("⚡ [{}][ENTRY] Bearish trend established on strike {}. Selling CE.", tradeName, currentStrike);
                    executeDirectionalLeg(tradeName, tick, strategyConfig, sourceConfig, "CE");
                    resetCounters(tradeName, strikeKey);
                }
            }
        } else {
            hitCounters.put(tradeName + "_ENTRY", 0);
            lastSeenStrikes.remove(tradeName + "_STRIKE");
        }
    }

    private void processDynamicExitSequence(String tradeName, String symbol, StraddleIntraday tick, Orders activeOrder, Strategy strategyConfig, Strategy sourceConfig, LocalTime now) {
        
        // --- ASSET-CLASSIFIED TIMELINE MATRIX ENFORCEMENT ---
        boolean forceSquareOff = false;
        String closingReason = "";

        if ("NIFTY".equalsIgnoreCase(symbol) || "SENSEX".equalsIgnoreCase(symbol)) {
            if (now.isBefore(INDEX_MORN_SQR)) {
                if (!now.isBefore(INDEX_MORN_SQR)) { forceSquareOff = true; closingReason = "INDEX_MORN_TIMEOUT"; }
            } else {
                if (!now.isBefore(INDEX_NOON_SQR)) { forceSquareOff = true; closingReason = "INDEX_NOON_EOD_SQR"; }
            }
        } else {
            // Commodities Scheduler Alignment Check
            if (now.isBefore(COMM_MORN_SQR)) {
                if (!now.isBefore(COMM_MORN_SQR)) { forceSquareOff = true; closingReason = "COMM_MORN_TIMEOUT"; }
            } else {
                if (!now.isBefore(COMM_NOON_SQR)) { forceSquareOff = true; closingReason = "COMM_NOON_EOD_SQR"; }
            }
        }

        if (forceSquareOff) {
            log.warn("🕒 [{}][TIME_EXIT] Operational boundary crossed. Sending force square-off order -> {}", tradeName, closingReason);
            closeDirectionalLeg(activeOrder, tick, closingReason, strategyConfig, sourceConfig);
            return;
        }

        // --- POSITION PERFORMANCE METRICS PROCESSING ---
        BigDecimal currentPrice = "CE".equals(activeOrder.getOptionType()) ? tick.getCePrice() : tick.getPePrice();
        BigDecimal vwapLimit = "CE".equals(activeOrder.getOptionType()) ? tick.getCeVwap() : tick.getPeVwap();
        BigDecimal entryPrice = activeOrder.getAskPrice();

        // Calculate Take-Profit line directly at 50% decay mark
        BigDecimal targetPrice = entryPrice.multiply(new BigDecimal("0.50")).setScale(2, RoundingMode.HALF_UP);
        
        log.info("📊 [{}] Strike: {} | Premium: {} | Target limit: {} | Stop (VWAP): {}", 
                tradeName, activeOrder.getStrike(), currentPrice, targetPrice, vwapLimit);

        // Rule A: Take Profit Target
        if (currentPrice.compareTo(targetPrice) <= 0) {
            log.info("💰 [{}][EXIT] Take-profit achieved. 50% decay captured safely.", tradeName);
            closeDirectionalLeg(activeOrder, tick, "DIR_TARGET_50_PERCENT", strategyConfig, sourceConfig);
            return;
        }

        // Rule B: Sharp Stop Loss (Premium moves above internal VWAP support)
        if (currentPrice.compareTo(vwapLimit) > 0) {
            log.warn("🚨 [{}][EXIT] Momentum failure. Position closed immediately at individual VWAP line.", tradeName);
            closeDirectionalLeg(activeOrder, tick, "DIR_VWAP_CROSSOVER_SL", strategyConfig, sourceConfig);
        }
    }

    private void executeDirectionalLeg(String tradeName, StraddleIntraday tick, Strategy strategyConfig, Strategy sourceConfig, String type) {
        String cycleId = UUID.randomUUID().toString();
        BigDecimal entryPrice = "CE".equals(type) ? tick.getCePrice() : tick.getPePrice();
        String token = "CE".equals(type) ? tick.getCeToken() : tick.getPeToken();
        String symbol = "CE".equals(type) ? tick.getCeSymbol() : tick.getPeSymbol();

        Orders order = new Orders();
        order.setToken(token);
        order.setSymbol(symbol);
        order.setQuantity(sourceConfig.getQuantity()); 
        order.setExchange(sourceConfig.getExchange()); 
        order.setActive(STATUS_ACTIVE);

        // =========================================================================
        // ROUTER MECHANISM (PAPER BY DEFAULT / BROKER EXCLUSIVE TO LIVE FLAG)
        // =========================================================================
        if ("Y".equalsIgnoreCase(strategyConfig.getLive())) {
            log.info("🌐 [{}][{}] LIVE RUNNING: Directing transaction packet to broker API core layer...", tradeName, type);
            try {
                Token t = new Token();
                t.setToken(token);
                t.setSymbol(symbol);
                t.setStrike(tick.getStrike());
                t.setName(sourceConfig.getName());
                t.setExch_seg(sourceConfig.getExchange());
                t.setQuantity(sourceConfig.getQuantity());

                orderService.orderPlaceWithToken(t, sourceConfig.getName(), "SELL", true);
                
                // Track backend matching records built by transaction listener loops
                order = ordersRepository.findByNameAndTokenAndActive(sourceConfig.getName(), token, STATUS_ACTIVE)
                        .orElseThrow(() -> new RuntimeException("Broker trace matching loop map exception."));
            } catch (Exception | SmartAPIException e) {
                log.error("❌ [{}][{}] Execution failure inside live connection framework pipelines: {}", tradeName, type, e.getMessage());
                return;
            }
        } else {
            log.info("📄 [{}][{}] PAPER RUNNING DEFAULT: Core ledger mapping executed via database row mocks.", tradeName, type);
        }

        order.setName(tradeName); 
        order.setSignal(STRATEGY_SIGNAL);
        order.setOptionType(type);
        order.setTradeCycleId(cycleId);
        order.setAskPrice(entryPrice);
        order.setStrike(tick.getStrike());
        order.setStatus(STATUS_OPEN);
        order.setTradePhase(PHASE_ENTRY);
        ordersRepository.save(order);

        String runMode = "Y".equalsIgnoreCase(strategyConfig.getLive()) ? "LIVE" : "PAPER";
        telegramService.sendMessage(String.format("🚀 **DIRECTIONAL ENTRY [%s]: %s**\nType Sold: %s\nStrike: %s\nEntry Price: %.2f", 
                runMode, tradeName, type, tick.getStrike(), entryPrice));
    }

    private void closeDirectionalLeg(Orders order, StraddleIntraday tick, String reason, Strategy strategyConfig, Strategy sourceConfig) {
        try {
            if ("Y".equalsIgnoreCase(strategyConfig.getLive())) {
                log.info("🌐 [{}][EXIT] LIVE RUNNING: Transmitting buy order back to broker core execution blocks...", order.getName());
                try {
                    orderService.exitActiveTradeByToken(order.getToken(), sourceConfig.getName(), order.getName());
                } catch (Exception | SmartAPIException e) {
                    log.error("❌ [{}][EXIT] Transaction execution returned error at broker interface layer: {}", order.getName(), e.getMessage());
                }
            } else {
                log.info("📄 [{}][EXIT] PAPER RUNNING: Clearing mock balance indicators across ledger datasets.", order.getName());
            }

            BigDecimal exitPrice = "CE".equals(order.getOptionType()) ? tick.getCePrice() : tick.getPePrice();
            BigDecimal entryPrice = order.getAskPrice() != null ? order.getAskPrice() : BigDecimal.ZERO;
            BigDecimal pointsPnL = entryPrice.subtract(exitPrice);

            order.setExitPrice(exitPrice);
            order.setPl(pointsPnL); 
            order.setClosedOn(LocalDateTime.now());
            order.setTradePhase(PHASE_EXIT);
            order.setStatus(STATUS_CLOSED);
            order.setActive(STATUS_INACTIVE);
            order.setExitReason(reason);
            ordersRepository.save(order); 

            String emoji = pointsPnL.signum() >= 0 ? "✅" : "❌";
            String runMode = "Y".equalsIgnoreCase(strategyConfig.getLive()) ? "LIVE" : "PAPER"; 

            telegramService.sendMessage(String.format(
                "%s **DIRECTIONAL EXIT [%s]: %s**\nReason: %s\nStrike: %s (%s)\nEntry: %.2f\nExit: %.2f\nPnL: **%.2f pts**", 
                emoji, runMode, order.getName(), reason, order.getStrike(), order.getOptionType(), entryPrice, exitPrice, pointsPnL
            ));

        } catch (Exception e) {
            log.error("❌ [{}][EXIT] Pipeline context error writing entity state log adjustments: {}", order.getName(), e.getMessage());
        }
    }

    private void resetCounters(String tradeName, String strikeKey) {
        hitCounters.put(tradeName + "_ENTRY", 0);
        lastSeenStrikes.remove(strikeKey);
    }

    private Optional<BigDecimal> getNiftyIndexAtm(String tradeName) {
        Strategy indexConfig = strategyRepo.findByName("NIFTY_INDEX");
        if (indexConfig == null || indexConfig.getToken() == null) return Optional.empty();
        BigDecimal indexLtp = angelWebSocketService.getLatestLTP(ExchangeType.NSE_CM, indexConfig.getToken());
        if (indexLtp == null || indexLtp.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();
        return Optional.of(indexLtp.divide(new BigDecimal("50"), 0, RoundingMode.HALF_UP).multiply(new BigDecimal("50")));
    }

    private Optional<BigDecimal> getSensexIndexAtm(String tradeName) {
        Strategy indexConfig = strategyRepo.findByName("SENSEX_INDEX");
        if (indexConfig == null || indexConfig.getToken() == null) return Optional.empty();
        BigDecimal indexLtp = angelWebSocketService.getLatestLTP(ExchangeType.BSE_CM, indexConfig.getToken());
        if (indexLtp == null || indexLtp.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();
        return Optional.of(indexLtp.divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP).multiply(new BigDecimal("100")));
    }
}