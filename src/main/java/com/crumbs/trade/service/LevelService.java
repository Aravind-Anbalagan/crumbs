package com.crumbs.trade.service;

import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import com.crumbs.trade.dto.ChartDataDTO;
import com.crumbs.trade.dto.SRLevelDTO;
import com.crumbs.trade.entity.Level;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.LevelRepository;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.StrategyRepo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class LevelService {

    private final SRService srService;
    private final LevelRepository levelRepository;
    private final OrderRepository orderRepository;
    private final AngelWebSocketService webSocketService;
    private final StrategyRepo strategyRepo;

    // =========================================================
    // 🔹 CONSTANTS
    // =========================================================
    private static final String STRATEGY               = "LEVEL";
    private static final String TIMEFRAME              = "FIFTEEN_MINUTE";

    private static final BigDecimal MERGE_BUFFER       = BigDecimal.valueOf(5);
    private static final BigDecimal ENTRY_BUFFER       = BigDecimal.valueOf(2);
    private static final BigDecimal TARGET_POINTS      = BigDecimal.valueOf(20);
    private static final BigDecimal SL_POINTS          = BigDecimal.valueOf(10); // 2:1 R:R

    private static final int LEVEL_COOLDOWN_MINUTES    = 15;

    // =========================================================
    // 🔹 STRATEGY CACHE (avoids DB hit every tick)
    // =========================================================
    private final Map<String, Strategy> strategyCache = new ConcurrentHashMap<>();

    private Strategy getStrategy(String symbol) {
        return strategyCache.computeIfAbsent(symbol, strategyRepo::findByName);
    }

    // =========================================================
    // 🔹 LEVEL GENERATION
    // =========================================================
    public void generateLevels(String symbol) {

        ChartDataDTO data = srService.analyzeIntraday(symbol, TIMEFRAME);

        if (data == null) {
            log.warn("[LEVEL] No chart data returned for symbol: {}", symbol);
            return;
        }

        List<SRLevelDTO> supports    = data.getSupportLevels();
        List<SRLevelDTO> resistances = data.getResistanceLevels();

        if (supports != null)    processLevels(symbol, supports,    "SUPPORT");
        if (resistances != null) processLevels(symbol, resistances, "RESISTANCE");
    }

    private void processLevels(String symbol, List<SRLevelDTO> levels, String type) {

        for (SRLevelDTO dto : levels) {

            BigDecimal price = dto.getPrice();

            Level existing = levelRepository
                    .findMatchingLevel(symbol, TIMEFRAME, price, MERGE_BUFFER);

            if (existing != null) {

                existing.setTouches(dto.getVisited());
                existing.setBounce(dto.getBounce());
                existing.setRejection(dto.getRejection());
                existing.setBreakout(dto.getBreakout());
                existing.setBreakdown(dto.getBreakdown());
                existing.setHeavyVolume(dto.isHeavyVolume());
                existing.setLastTouchedAt(LocalDateTime.now());

                levelRepository.save(existing);
                log.debug("[LEVEL] Updated {} level {} for {}", type, price, symbol);

            } else {

                Level level = Level.builder()
                        .symbol(symbol)
                        .timeframe(TIMEFRAME)
                        .price(price)
                        .type(type)
                        .touches(dto.getVisited())
                        .bounce(dto.getBounce())
                        .rejection(dto.getRejection())
                        .breakout(dto.getBreakout())
                        .breakdown(dto.getBreakdown())
                        .heavyVolume(dto.isHeavyVolume())
                        .lastTouchedAt(LocalDateTime.now())
                        .active(true)
                        .build();

                levelRepository.save(level);
                log.info("[LEVEL] Created new {} level {} for {}", type, price, symbol);
            }
        }
    }

    // =========================================================
    // 🔹 TRADE ENGINE
    // =========================================================
    public void processSymbol(String symbol, boolean allowEntry) {

        Strategy strategy = getStrategy(symbol);

        BigDecimal ltp = webSocketService.getLatestLTP(ExchangeType.NSE_FO, strategy.getToken());

        if (ltp == null) {
            log.warn("[LEVEL] LTP unavailable for symbol: {}", symbol);
            return;
        }

        monitorOpenTrades(symbol, ltp);

        if (allowEntry) {
            checkNewEntries(symbol, ltp);
        }
    }

    // =========================================================
    // 🔹 MONITOR OPEN TRADES
    // =========================================================
    private void monitorOpenTrades(String symbol, BigDecimal ltp) {

        List<Orders> openOrders = orderRepository.findOpenOrders(symbol, STRATEGY);

        for (Orders order : openOrders) {

            boolean hitTarget;
            boolean hitSL;

            if ("BUY".equals(order.getSide())) {
                hitTarget = ltp.compareTo(order.getPl()) >= 0;
                hitSL     = ltp.compareTo(order.getSl()) <= 0;
            } else {
                hitTarget = ltp.compareTo(order.getPl()) <= 0;
                hitSL     = ltp.compareTo(order.getSl()) >= 0;
            }

            if (hitTarget) {
                log.info("[LEVEL] TARGET hit — {} {} | LTP: {}", order.getSide(), symbol, ltp);
                closeOrder(order, ltp);
            } else if (hitSL) {
                log.info("[LEVEL] SL hit — {} {} | LTP: {}", order.getSide(), symbol, ltp);
                closeOrder(order, ltp);
            }
        }
    }

    // =========================================================
    // 🔹 ENTRY LOGIC
    // =========================================================
    private void checkNewEntries(String symbol, BigDecimal ltp) {

        // 🔒 One active trade per symbol at a time — symbol-level guard
        boolean anyOpen = orderRepository
                .existsBySymbolAndNameAndStatus(symbol, STRATEGY, "OPEN");
        if (anyOpen) {
            log.debug("[LEVEL] Trade already active for {}, skipping entries", symbol);
            return;
        }

        List<Level> levels = levelRepository.findActiveLevels(symbol);

        for (Level level : levels) {

            BigDecimal lower = level.getPrice().subtract(ENTRY_BUFFER);
            BigDecimal upper = level.getPrice().add(ENTRY_BUFFER);

            if (ltp.compareTo(lower) < 0 || ltp.compareTo(upper) > 0) continue;

            if (level.getLastTradedAt() != null &&
                level.getLastTradedAt().isAfter(
                    LocalDateTime.now().minusMinutes(LEVEL_COOLDOWN_MINUTES))) {
                log.debug("[LEVEL] Cooldown active for level {} on {}", level.getPrice(), symbol);
                continue;
            }

            createOrder(symbol, level, ltp);
            break; // stop after first valid entry — prevents double entry in same tick
        }
    }

    // =========================================================
    // 🔹 CREATE ORDER
    // =========================================================
    private void createOrder(String symbol, Level level, BigDecimal ltp) {

        String side = "RESISTANCE".equals(level.getType()) ? "SELL" : "BUY";

        BigDecimal target;
        BigDecimal sl;

        if ("BUY".equals(side)) {
            target = ltp.add(TARGET_POINTS);
            sl     = ltp.subtract(SL_POINTS);
        } else {
            target = ltp.subtract(TARGET_POINTS);
            sl     = ltp.add(SL_POINTS);
        }

        Orders order = new Orders();
        order.setSymbol(symbol);
        order.setStrike(level.getPrice());
        order.setSide(side);
        order.setAskPrice(ltp);
        order.setPl(target);
        order.setSl(sl);
        order.setName(STRATEGY);
        order.setStatus("OPEN");
        order.setActive(1);
        order.setCreatedOn(LocalDateTime.now());

        orderRepository.save(order);

        level.setLastTradedAt(LocalDateTime.now());
        levelRepository.save(level);

        log.info("[LEVEL] ORDER CREATED — {} {} @ {} | Target: {} SL: {}",
                side, symbol, ltp, target, sl);
    }

    // =========================================================
    // 🔹 CLOSE ORDER
    // =========================================================
    private void closeOrder(Orders order, BigDecimal ltp) {

        order.setExitPrice(ltp);
        order.setClosedOn(LocalDateTime.now());
        order.setStatus("CLOSED");

        BigDecimal pnl;

        if ("BUY".equals(order.getSide())) {
            pnl = ltp.subtract(order.getAskPrice());
        } else {
            pnl = order.getAskPrice().subtract(ltp);
        }

        order.setPl(pnl);

        orderRepository.save(order);

        log.info("[LEVEL] ORDER CLOSED — {} {} | Entry: {} Exit: {} PnL: {}",
                order.getSide(), order.getSymbol(),
                order.getAskPrice(), ltp, pnl);
    }
}