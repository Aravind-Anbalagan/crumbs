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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShortStraddleService {

    private static final String STRATEGY_SIGNAL = "SHORT_STRADDLE_VWAP";
    private static final String NAME_PREFIX = "SHORT_STRADDLE_"; 
    
    // --- Rule 1: Strict Timeframes ---
    private static final LocalTime NIFTY_START = LocalTime.of(9, 20);
    private static final LocalTime NIFTY_SQUARE_OFF = LocalTime.of(15, 20);
    private static final LocalTime CRUDE_START = LocalTime.of(16, 0);
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

    public void evaluate(String symbol) {
        LocalTime now = LocalTime.now();
        
        String baseSymbol = symbol.toUpperCase().replace(NAME_PREFIX, "");
        String tradeName = NAME_PREFIX + baseSymbol;

        Strategy tempStrategy = strategyRepo.findByName(tradeName);
        if (tempStrategy == null) {
            tempStrategy = strategyRepo.findByName(baseSymbol); 
            if (tempStrategy == null) return;
        }
        final Strategy strategy = tempStrategy;

        // --- Rule 5: Configured Daily Limit Check ---
        LocalDateTime startOfDay = LocalDateTime.now().with(LocalTime.MIN);
        long totalLegs = ordersRepository.countLegsToday(tradeName, STRATEGY_SIGNAL, startOfDay);
        long straddlesUsed = totalLegs / 2; 
        int maxAllowed = strategy.getMaxDailyTrades() > 0 ? strategy.getMaxDailyTrades() : 3;

        log.info("⏱️ [{}][EVAL] Pulse @ {} | Straddles Today: {}/{}", 
                tradeName, now.format(DateTimeFormatter.ofPattern("HH:mm:ss")), straddlesUsed, maxAllowed);

        // --- Rule 1: Window Start Logic ---
        if ("NIFTY".equalsIgnoreCase(baseSymbol) && now.isBefore(NIFTY_START)) return;
        if (tradeName.contains("CRUDE") && now.isBefore(CRUDE_START)) return;

        List<Orders> activeOrders = ordersRepository.findByNameAndSignalAndActive(tradeName, STRATEGY_SIGNAL, STATUS_ACTIVE);

        if (!activeOrders.isEmpty()) {
            
            // Safety: If somehow 1 leg is active, close the orphan.
            if (activeOrders.size() != 2) {
                log.error("🚨 [{}][SAFETY] Imbalance! Found {} legs. Cleaning up orphan.", tradeName, activeOrders.size());
                BigDecimal tradedStrike = activeOrders.get(0).getStrike();
                straddleRepository.findLatestBySymbolAndStrike(baseSymbol, tradedStrike).ifPresent(tick -> 
                    closeAll(activeOrders, tick, "ORPHAN_LEG_CLEANUP", strategy));
                return;
            }

            // --- Rule 1 & 8: EOD Square Off (Close all active) ---
            if (isSquareOffTime(baseSymbol, now)) {
                log.info("🕒 [{}][EXIT] Square-off time reached.", tradeName);
                BigDecimal tradedStrike = activeOrders.get(0).getStrike();
                straddleRepository.findLatestBySymbolAndStrike(baseSymbol, tradedStrike).ifPresent(tick -> 
                    closeAll(activeOrders, tick, "EOD_SQUARE_OFF", strategy));
                return;
            }

            BigDecimal tradedStrike = activeOrders.get(0).getStrike();
            straddleRepository.findLatestBySymbolAndStrike(baseSymbol, tradedStrike).ifPresentOrElse(
                tick -> processExitSequence(tradeName, tick, activeOrders, strategy),
                () -> log.error("❌ [{}][MONITOR] Missing price data for strike: {}", tradeName, tradedStrike)
            );
            
        } else {
            if (straddlesUsed >= maxAllowed) return;

            straddleRepository.findATMBySymbol(baseSymbol).ifPresent(tick -> 
                processEntrySequence(tradeName, tick, strategy)
            );
        }
    }

    // --- Rule 2: Entry Logic ---
    private void processEntrySequence(String tradeName, StraddleIntraday tick, Strategy strategy) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        BigDecimal currentGap = cv.subtract(cp);
        BigDecimal maxAllowed = strategy.getMaxEntryRisk(); // Max allowed points for the gap
        int reqHits = strategy.getEntryHitsRequired() > 0 ? strategy.getEntryHitsRequired() : 3;

        // Entry Condition: CP < CV AND Gap is not more than configured pts
        if (cp.compareTo(cv) < 0 && (maxAllowed == null || currentGap.compareTo(maxAllowed) <= 0)) {
            int count = hitCounters.merge(tradeName + "_ENTRY", 1, Integer::sum);
            if (count >= reqHits) {
                executeShortStraddle(tradeName, tick, currentGap, strategy);
                hitCounters.put(tradeName + "_ENTRY", 0); // Reset after entry
            }
        } else {
            hitCounters.put(tradeName + "_ENTRY", 0); // Reset if condition breaks
        }
    }

    // --- Rule 3 & 4: Exit & Target Logic ---
    private void processExitSequence(String tradeName, StraddleIntraday tick, List<Orders> activeOrders, Strategy strategy) {
        BigDecimal cp = tick.getCombinedPremium();
        BigDecimal cv = tick.getCombinedVwap();
        BigDecimal currentGap = cv.subtract(cp);
        BigDecimal entryGap = activeOrders.get(0).getBreakeven();
        
        // --- Rule 4: Target Calculation ---
        BigDecimal targetGap = entryGap.add(strategy.getTargetPoints());

        // --- Rule 3: Exit (SL) Logic based on CP crossing above CV ---
        boolean isVwapCrossover = cp.compareTo(cv) > 0;
        int reqSlHits = strategy.getExitHitsRequired() > 0 ? strategy.getExitHitsRequired() : 3;
        
        if (isVwapCrossover) {
            hitCounters.merge(tradeName + "_EXIT", 1, Integer::sum);
        } else {
            hitCounters.put(tradeName + "_EXIT", 0); // Reset if it dips back down
        }
        
        int currentSlHits = hitCounters.getOrDefault(tradeName + "_EXIT", 0);
        boolean isHitsMet = currentSlHits >= reqSlHits;

        // TARGET TRIGGER
        if (currentGap.compareTo(targetGap) >= 0) {
            log.info("💰 [{}][EXIT] TARGET REACHED!", tradeName);
            closeAll(activeOrders, tick, "TARGET_REACHED", strategy);
            hitCounters.put(tradeName + "_EXIT", 0);
            return;
        }

        // SL TRIGGER
        if (isHitsMet) {
            log.warn("🚨 [{}][EXIT] TREND SL TRIGGERED! CP crossed above CV for {} ticks.", tradeName, reqSlHits);
            closeAll(activeOrders, tick, "VWAP_CROSSOVER_SL", strategy);
            hitCounters.put(tradeName + "_EXIT", 0);
        }
    }

    // --- Rule 6: Partial Fill Execution Handling ---
    protected void executeShortStraddle(String tradeName, StraddleIntraday tick, BigDecimal entryGap, Strategy strategy) {
        log.info("🚀 [{}][EXECUTE] Opening positions.", tradeName);
        String cycleId = UUID.randomUUID().toString();
        BigDecimal targetValue = entryGap.add(strategy.getTargetPoints());

        // Process CE Leg
        Orders ceOrder = processLeg(tick.getCeToken(), tick.getCeSymbol(), strategy, tick.getCePrice(), 
                   tick.getStrike(), tradeName, "CE", cycleId, entryGap, targetValue);

        // Process PE Leg
        Orders peOrder = processLeg(tick.getPeToken(), tick.getPeSymbol(), strategy, tick.getPePrice(), 
                   tick.getStrike(), tradeName, "PE", cycleId, entryGap, targetValue);

        boolean ceSuccess = ceOrder != null;
        boolean peSuccess = peOrder != null;

        if (ceSuccess != peSuccess) {
            log.error("🚨 [{}][EXECUTION] Partial entry detected! CE: {}, PE: {}. Rolling back placed leg.", tradeName, ceSuccess, peSuccess);
            
            // Gather the successfully placed leg(s)
            List<Orders> partialOrdersToClose = new ArrayList<>();
            if (ceSuccess) partialOrdersToClose.add(ceOrder);
            if (peSuccess) partialOrdersToClose.add(peOrder);
            
            // Roll it back immediately
            closeAll(partialOrdersToClose, tick, "PARTIAL_FILL_ROLLBACK", strategy);
            
        } else if (ceSuccess) {
            // --- Rule 9: Telegram Logs ---
            String mode = "Y".equalsIgnoreCase(strategy.getLive()) ? "LIVE" : "PAPER";
            telegramService.sendMessage(String.format("🚀 **ENTRY [%s]: %s**\nStrike: %s\nGap: %.2f\nTarget: +%.2f", 
                    mode, tradeName, tick.getStrike(), entryGap, strategy.getTargetPoints()));
        } else {
            log.error("❌ [{}][EXECUTION] Both legs failed to execute. No positions opened.", tradeName);
        }
    }

    // --- Rule 7 & 8: Live vs Paper DB Tracking ---
    private Orders processLeg(String tokenStr, String symbol, Strategy strategy, BigDecimal price, 
                                BigDecimal strike, String tradeName, String type, String cycleId, 
                                BigDecimal gap, BigDecimal targetValue) {
        try {
            Token t = new Token();
            t.setToken(tokenStr); t.setSymbol(symbol); t.setExch_seg(strategy.getExchange());
            t.setStrike(strike); t.setName(tradeName); t.setQuantity(strategy.getQuantity());

            // Mandatory logging of attributes
            log.info("📝 [{}][{}] Preparing -> Symbol: {}, Token: {}, Exchange: {}, Qty: {}", 
                    tradeName, type, t.getSymbol(), t.getToken(), t.getExch_seg(), t.getQuantity());

            if ("Y".equalsIgnoreCase(strategy.getLive())) {
                try {
                    log.info("🌐 [{}][{}] LIVE MODE: Sending to broker...", tradeName, type);
                    orderService.orderPlaceWithToken(t, tradeName, "SELL", true);
                } catch (Exception | SmartAPIException e) {
                    log.error("⚠️ [{}][LEG] Broker failed for {}. Reason: {}", tradeName, type, e.getMessage());
                    return null; // Return null so executeShortStraddle triggers partial rollback
                }
            } else {
                log.info("📄 [{}][{}] PAPER MODE: Monitor via DB.", tradeName, type);
            }

            // Save to DB regardless of live/paper (as long as it didn't fail the live broker check)
            Orders order = ordersRepository.findByTokenAndActive(tokenStr, STATUS_ACTIVE);
            if (order == null) {
                order = new Orders();
                order.setToken(tokenStr); order.setSymbol(symbol);
                order.setActive(STATUS_ACTIVE); order.setName(tradeName);
            }

            order.setQuantity(t.getQuantity()); order.setSignal(STRATEGY_SIGNAL);
            order.setOptionType(type); order.setTradeCycleId(cycleId);
            order.setBreakeven(gap); order.setTarget(targetValue); 
            order.setAskPrice(price); order.setStrike(strike);
            order.setStatus(STATUS_OPEN); order.setTradePhase(PHASE_ENTRY);
            order.setCreatedOn(LocalDateTime.now());
            
            return ordersRepository.save(order);

        } catch (Exception e) {
            log.error("❌ [{}][LEG] Database/System error for {}: {}", tradeName, type, e.getMessage());
            return null;
        }
    }

    // --- Rule 6, 8, 9: DB tracking, Exits, and Telegram ---
    @Transactional
    protected void closeAll(List<Orders> activeOrders, StraddleIntraday tick, String reason, Strategy strategy) {
        BigDecimal totalEntry = BigDecimal.ZERO;
        BigDecimal totalExit = BigDecimal.ZERO;
        boolean allSuccess = true;

        for (Orders order : activeOrders) {
            if (order.getActive() == STATUS_INACTIVE) continue;
            try {
                if ("Y".equalsIgnoreCase(strategy.getLive())) {
                    try {
                        orderService.exitActiveTradeByToken(order.getToken(), strategy.getName());
                    } catch (Exception | SmartAPIException e) {
                        allSuccess = false;
                        log.error("❌ [{}][EXIT] Broker failed to close {}: {}", strategy.getName(), order.getOptionType(), e.getMessage());
                    }
                }

                BigDecimal exitPrice = "CE".equals(order.getOptionType()) ? tick.getCePrice() : tick.getPePrice();
                BigDecimal entryPrice = order.getAskPrice() != null ? order.getAskPrice() : BigDecimal.ZERO;
                
                totalEntry = totalEntry.add(entryPrice);
                totalExit = totalExit.add(exitPrice);

                order.setExitPrice(exitPrice);
                order.setPl(entryPrice.subtract(exitPrice)); // Short strategy: Entry - Exit
                order.setClosedOn(LocalDateTime.now());
                order.setTradePhase(PHASE_EXIT);
                order.setStatus(STATUS_CLOSED);
                order.setActive(STATUS_INACTIVE);
                ordersRepository.save(order); // DB tracks all activity
                
            } catch (Exception e) {
                log.error("❌ [{}][EXIT] DB error closing leg: {}", strategy.getName(), e.getMessage());
            }
        }
        
        // --- Rule 9: Telegram message on action ---
        if (allSuccess && !activeOrders.isEmpty()) {
            BigDecimal finalPnL = totalEntry.subtract(totalExit); 
            String emoji = finalPnL.signum() >= 0 ? "✅" : "❌";
            String mode = "Y".equalsIgnoreCase(strategy.getLive()) ? "LIVE" : "PAPER";
            
            telegramService.sendMessage(String.format(
                "%s **EXIT [%s]: %s**\nReason: %s\nEntry Total: %.2f\nExit Total: %.2f\nPnL: **%.2f pts**", 
                emoji, mode, strategy.getName(), reason, totalEntry, totalExit, finalPnL
            ));
        }
    }

    private boolean isSquareOffTime(String symbol, LocalTime now) {
        if ("NIFTY".equalsIgnoreCase(symbol)) return !now.isBefore(NIFTY_SQUARE_OFF);
        if (symbol.contains("CRUDE")) return !now.isBefore(CRUDE_SQUARE_OFF);
        return false;
    }
}