package com.crumbs.trade.service;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShortStraddleService {

    private static final String STRATEGY_SIGNAL = "SHORT_STRADDLE_VWAP";
    private static final int REQUIRED_CONSECUTIVE_HITS = 3;

    // Fallback constants if DB values are null
    private static final BigDecimal DEFAULT_NIFTY_TARGET = new BigDecimal("25");
    private static final BigDecimal DEFAULT_CRUDE_TARGET = new BigDecimal("50");
    private static final BigDecimal DEFAULT_NIFTY_RISK = new BigDecimal("10");
    private static final BigDecimal DEFAULT_CRUDE_RISK = new BigDecimal("30");

    private static final LocalTime CRUDE_START = LocalTime.of(16, 0);
    private static final LocalTime NIFTY_SQUARE_OFF = LocalTime.of(15, 20);
    private static final LocalTime CRUDE_SQUARE_OFF = LocalTime.of(23, 20);

    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_INACTIVE = 0;
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String PHASE_ENTRY = "ENTRY";
    private static final String PHASE_EXIT = "EXIT";

    private final ShortStraddleRepository straddleRepository;
    private final OrderRepository ordersRepository;
    private final StrategyRepo strategyRepo;
    private final OrderService orderService;
    private final TelegramService telegramService;

    private final ConcurrentHashMap<String, Integer> hitCounters = new ConcurrentHashMap<>();

    /**
     * Main evaluation loop.
     */
    public void evaluate(String symbol) {
        LocalTime now = LocalTime.now();
        String symUpper = symbol.toUpperCase();

        // 1. Time Window Check
        if (symUpper.contains("CRUDE") && now.isBefore(CRUDE_START)) {
            log.debug("⏳ [{}][WAIT] Outside trading window. Starts at 16:00.", symUpper);
            return;
        }

        // 2. Fetch Strategy Configuration from DB
        Strategy strategy = strategyRepo.findByName(symUpper);
        if (strategy == null) {
            log.error("❌ [{}][CONFIG] Strategy record not found in STRATEGY table!", symUpper);
            return;
        }

        String uniqueName = "SHORT_STRADDLE_" + symUpper;
        List<Orders> activeOrders = ordersRepository.findByNameAndSignalAndActive(uniqueName, STRATEGY_SIGNAL, STATUS_ACTIVE);

        // 3. Square-off Logic
        if (!activeOrders.isEmpty() && isSquareOffTime(symUpper, now)) {
            straddleRepository.findLatestByName(symbol).ifPresent(tick -> {
                log.info("⏰ [{}][EOD] Square-off time reached. Closing positions.", symUpper);
                closeAll(activeOrders, tick, "EOD_SQUARE_OFF", strategy);
            });
            return;
        }

        if (activeOrders.isEmpty()) {
            // 4. Scanning Mode
            straddleRepository.findATMBySymbol(symbol).ifPresentOrElse(
                tick -> processEntrySequence(symUpper, tick, strategy),
                () -> log.warn("⚠️ [{}][SCAN] No ATM data found in repository.", symUpper)
            );
        } else {
            // 5. Monitoring Mode
            BigDecimal tradedStrike = activeOrders.get(0).getStrike();
            straddleRepository.findLatestBySymbolAndStrike(symbol, tradedStrike).ifPresentOrElse(
                tick -> processExitSequence(symUpper, tick, activeOrders, strategy),
                () -> log.error("❌ [{}][MONITOR] Data missing for active strike: {}", symUpper, tradedStrike)
            );
        }
    }

    private void processEntrySequence(String symbol, StraddleIntraday tick, Strategy strategy) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        BigDecimal currentGap = cv.subtract(cp);
        
        // Use DB value with hardcoded fallback
        BigDecimal maxAllowed = strategy.getMaxEntryRisk() != null ? strategy.getMaxEntryRisk() : 
                               (symbol.contains("NIFTY") ? DEFAULT_NIFTY_RISK : DEFAULT_CRUDE_RISK);

        if (cp.compareTo(cv) < 0) {
            if (currentGap.compareTo(maxAllowed) <= 0) {
                int count = hitCounters.merge(symbol, 1, Integer::sum);
                log.info("🎯 [{}][SCAN] VALID HIT {}/{} | Strike: {} | Gap: {} (Max: {})", 
                        symbol, count, REQUIRED_CONSECUTIVE_HITS, tick.getStrike(), 
                        currentGap.setScale(2, RoundingMode.HALF_UP), maxAllowed);

                if (count >= REQUIRED_CONSECUTIVE_HITS) {
                    executeShortStraddle(symbol, tick, currentGap, strategy);
                    hitCounters.put(symbol, 0); 
                }
            } else {
                hitCounters.put(symbol, 0);
                log.info("⏩ [{}][SCAN] Crossover active but Gap too wide (Gap: {} > Max: {}). Skipping.", 
                        symbol, currentGap.setScale(2, RoundingMode.HALF_UP), maxAllowed);
            }
        } else {
            hitCounters.put(symbol, 0);
            if ("Y".equalsIgnoreCase(strategy.getEnableLogging())) {
                log.info("⏳ [{}][SCAN] Waiting for Crossover... (Premium: {} | VWAP: {})", 
                        symbol, cp.setScale(2, RoundingMode.HALF_UP), cv.setScale(2, RoundingMode.HALF_UP));
            }
        }
    }

    private void processExitSequence(String symbol, StraddleIntraday tick, List<Orders> activeOrders, Strategy strategy) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        BigDecimal currentGap = cv.subtract(cp);
        
        BigDecimal entryGap = activeOrders.get(0).getBreakeven();
        
        // Use DB value for targetPoints
        BigDecimal ptsToCapture = strategy.getTargetPoints() != null ? strategy.getTargetPoints() : 
                                 (symbol.contains("NIFTY") ? DEFAULT_NIFTY_TARGET : DEFAULT_CRUDE_TARGET);
        
        BigDecimal targetGap = (entryGap != null ? entryGap : BigDecimal.ZERO).add(ptsToCapture);

        log.info("📊 [{}][MONITOR] Strike: {} | CP: {} | Gap: {} | Target: {} | SL Distance: {}",
                symbol, tick.getStrike(), cp.setScale(2, RoundingMode.HALF_UP), 
                currentGap.setScale(2, RoundingMode.HALF_UP), targetGap.setScale(2, RoundingMode.HALF_UP),
                cp.subtract(cv).abs().setScale(2, RoundingMode.HALF_UP));

        if (cp.compareTo(cv) >= 0) {
            log.info("🚨 [{}][EXIT] Stop Loss Triggered! CP ({}) crossed CV ({}).", symbol, cp, cv);
            closeAll(activeOrders, tick, "STOP_LOSS_VWAP_CROSS", strategy);
        } else if (currentGap.compareTo(targetGap) >= 0) {
            log.info("💰 [{}][EXIT] Target Reached! Gap {} >= Target {}.", symbol, currentGap, targetGap);
            closeAll(activeOrders, tick, "TARGET_REACHED", strategy);
        }
    }

    @Transactional
    protected void executeShortStraddle(String symbol, StraddleIntraday tick, BigDecimal entryGap, Strategy strategy) {
        log.info("🚀 [{}][EXECUTE] Placing Short Straddle at Strike: {}", symbol, tick.getStrike());
        
        String cycleId = UUID.randomUUID().toString();
        String uniqueName = "SHORT_STRADDLE_" + symbol.toUpperCase();
        boolean isLive = "Y".equalsIgnoreCase(strategy.getLive());

        BigDecimal ptsToCapture = strategy.getTargetPoints() != null ? strategy.getTargetPoints() : 
                                 (symbol.contains("NIFTY") ? DEFAULT_NIFTY_TARGET : DEFAULT_CRUDE_TARGET);
        BigDecimal targetValue = entryGap.add(ptsToCapture);

        processLeg(tick.getCeToken(), tick.getCeSymbol(), strategy, tick.getCePrice(), 
                   tick.getStrike(), uniqueName, "CE", cycleId, entryGap, targetValue, isLive, symbol);

        processLeg(tick.getPeToken(), tick.getPeSymbol(), strategy, tick.getPePrice(), 
                   tick.getStrike(), uniqueName, "PE", cycleId, entryGap, targetValue, isLive, symbol);

        telegramService.sendMessage(buildEntryMsg(symbol, tick.getStrike(), tick.getCePrice(), tick.getPePrice(), entryGap, ptsToCapture));
    }

    @Transactional
    protected void closeAll(List<Orders> activeOrders, StraddleIntraday tick, String reason, Strategy strategy) {
        String symbol = tick.getName().toUpperCase();
        boolean isLive = strategy != null && "Y".equalsIgnoreCase(strategy.getLive());

        BigDecimal totalEntry = BigDecimal.ZERO;
        BigDecimal totalExit = BigDecimal.ZERO;

        for (Orders order : activeOrders) {
            // Safety Check: Idempotency
            if (order.getActive() == STATUS_INACTIVE) continue;

            try {
                if (isLive) {
                    String tokenToExit = "CE".equals(order.getOptionType()) ? tick.getCeToken() : tick.getPeToken();
                    orderService.exitActiveTradeByToken(tokenToExit, order.getName());
                    log.info("✅ [{}][EXIT] Live exit placed for {} leg.", symbol, order.getOptionType());
                }

                BigDecimal exitPrice = "CE".equals(order.getOptionType()) ? tick.getCePrice() : tick.getPePrice();
                totalEntry = totalEntry.add(order.getAskPrice());
                totalExit = totalExit.add(exitPrice);

                order.setActive(STATUS_INACTIVE); 
                order.setStatus(STATUS_CLOSED);
                order.setTradePhase(PHASE_EXIT);
                order.setClosedOn(LocalDateTime.now());
                order.setExitPrice(exitPrice);
                order.setPl(order.getAskPrice().subtract(exitPrice));
                ordersRepository.save(order);
            } catch (Exception | SmartAPIException e) {
                log.error("❌ [{}][EXIT] Error closing order: {}", symbol, e.getMessage());
            }
        }
        telegramService.sendMessage(buildExitMsg(symbol, tick.getStrike(), totalEntry, totalExit, reason));
    }

    private void processLeg(String tokenStr, String symbol, Strategy strategy, BigDecimal price, 
                            BigDecimal strike, String uniqueName, String type, String cycleId, 
                            BigDecimal gap, BigDecimal targetValue, boolean isLive, String name) {
        if (isLive) {
            try {
                Token t = new Token();
                t.setToken(tokenStr);
                t.setSymbol(symbol);
                t.setExch_seg(strategy.getExchange());
                t.setStrike(strike);
                t.setName(name);
                orderService.orderPlaceWithToken(t, uniqueName, "SELL", true);
            } catch (Exception | SmartAPIException e) {
                log.error("❌ [{}][LEG] Failed live order for {}: {}", symbol, type, e.getMessage());
                return; 
            }
        }
        saveOrder(uniqueName, type, price, strike, cycleId, gap, symbol, tokenStr, targetValue);
    }

    private void saveOrder(String uniqueName, String type, BigDecimal price, BigDecimal strike, 
                           String cycleId, BigDecimal gap, String symbol, String token, BigDecimal target) {
        Orders order = new Orders();
        order.setName(uniqueName);
        order.setSignal(STRATEGY_SIGNAL);
        order.setCreatedOn(LocalDateTime.now());
        order.setOptionType(type);
        order.setSide("SELL");
        order.setAskPrice(price);
        order.setStrike(strike);
        order.setBreakeven(gap); 
        order.setSymbol(symbol); 
        order.setToken(token);
        order.setTarget(target); 
        order.setActive(STATUS_ACTIVE);
        order.setStatus(STATUS_OPEN);
        order.setTradePhase(PHASE_ENTRY);
        order.setTradeCycleId(cycleId);
        ordersRepository.save(order);
    }

    private boolean isSquareOffTime(String symbol, LocalTime now) {
        if ("NIFTY".equalsIgnoreCase(symbol)) return !now.isBefore(NIFTY_SQUARE_OFF);
        if ("CRUDEOIL".equalsIgnoreCase(symbol) || "CRUDEOILM".equalsIgnoreCase(symbol)) 
            return !now.isBefore(CRUDE_SQUARE_OFF);
        return false;
    }

    private String buildEntryMsg(String sym, BigDecimal strike, BigDecimal ce, BigDecimal pe, BigDecimal gap, BigDecimal tgt) {
        String t = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        return String.format("""
            🚀 **STRADDLE ENTRY: %s**
            
            📌 **Strike** : %s
            💰 **CE / PE** : %.2f | %.2f
            📊 **Combined** : **%.2f**
            📉 **Gap to VWAP**: %.2f
            
            🎯 **Target** : +%.2f Points
            ⏰ **Time** : %s
            🟢 *Monitoring for VWAP crossover SL...*
            """, sym, strike, ce, pe, ce.add(pe), gap, tgt, t);
    }

    private String buildExitMsg(String sym, BigDecimal strike, BigDecimal ent, BigDecimal ex, String reason) {
        BigDecimal pnl = ent.subtract(ex);
        String icon = pnl.signum() >= 0 ? "✅" : "❌";
        String t = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        return String.format("""
            %s **STRADDLE EXIT: %s**
            
            📌 **Strike** : %s
            🚪 **Reason** : **%s**
            
            📥 **Entry** : %.2f
            📤 **Exit** : %.2f
            💰 **PnL** : **%.2f Points**
            
            ⏰ **Time** : %s
            🏁 *Cycle complete.*
            """, icon, sym, strike, reason.replace("_", " "), ent, ex, pnl, t);
    }
}