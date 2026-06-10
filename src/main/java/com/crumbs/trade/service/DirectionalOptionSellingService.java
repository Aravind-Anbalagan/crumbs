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

    private static final String STRATEGY_SIGNAL = "DIRECTIONAL_SELL"; 
    private static final String NAME_PREFIX = "DIR_SELL_"; 

    // --- Rule 1: Strict Timeframes ---
    private static final LocalTime NIFTY_START = LocalTime.of(9, 20);
    private static final LocalTime NIFTY_ENTRY_CUTOFF = LocalTime.of(15, 0);
    private static final LocalTime NIFTY_SQUARE_OFF = LocalTime.of(15, 20);

    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_INACTIVE = 0;
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String PHASE_ENTRY = "ENTRY";
    private static final String PHASE_EXIT = "EXIT";

    private final PreMarketAnalysisRepo preMarketRepo;
    private final OrderRepository ordersRepository;
    private final StrategyRepo strategyRepo;
    private final OrderService orderService;
    private final TelegramService telegramService;
    private final AngelWebSocketService angelWebSocketService;

    // Hit Trackers
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
        if ("NIFTY".equalsIgnoreCase(instrumentName) && now.isBefore(NIFTY_START)) return;

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
        BigDecimal ceLtp = getLivePrice(data.getCeToken());
        BigDecimal peLtp = getLivePrice(data.getPeToken());
        BigDecimal midPoint = (data.getSecondMidPoint() != null) ? data.getSecondMidPoint() : data.getMidPoint();

        if (ceLtp == null || peLtp == null || midPoint == null) return;

        int reqHits = sourceConfig.getEntryHitsRequired() > 0 ? sourceConfig.getEntryHitsRequired() : 10;
        String ceKey = tradeName + "_CE_ENTRY";
        String peKey = tradeName + "_PE_ENTRY";

        // CE Condition: LTP drops below midpoint (Short CE)
        if (ceLtp.compareTo(midPoint) < 0) {
            int hits = hitCounters.merge(ceKey, 1, Integer::sum);
            log.info("🎯 [{}] CE ENTRY HITS: ({}/{}) | LTP: {} | MID: {}", tradeName, hits, reqHits, ceLtp, midPoint);
            hitCounters.put(peKey, 0); // Reset opposite side
            
            if (hits >= reqHits) {
                log.info("⚡ [{}] CE HITS MET! Triggering SELL.", tradeName);
                executeTrade(tradeName, data.getCeToken(), data.getCeSymbol(), data.getAtmStrike(), ceLtp, "CE", strategyConfig, sourceConfig);
                hitCounters.put(ceKey, 0); // Reset after entry
            }
        } 
        // PE Condition: LTP drops below midpoint (Short PE)
        else if (peLtp.compareTo(midPoint) < 0) {
            int hits = hitCounters.merge(peKey, 1, Integer::sum);
            log.info("🎯 [{}] PE ENTRY HITS: ({}/{}) | LTP: {} | MID: {}", tradeName, hits, reqHits, peLtp, midPoint);
            hitCounters.put(ceKey, 0); // Reset opposite side
            
            if (hits >= reqHits) {
                log.info("⚡ [{}] PE HITS MET! Triggering SELL.", tradeName);
                executeTrade(tradeName, data.getPeToken(), data.getPeSymbol(), data.getAtmStrike(), peLtp, "PE", strategyConfig, sourceConfig);
                hitCounters.put(peKey, 0); // Reset after entry
            }
        } 
        // Zig-Zag Reset: Neither is below midpoint
        else {
            if (hitCounters.getOrDefault(ceKey, 0) > 0 || hitCounters.getOrDefault(peKey, 0) > 0) {
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
        BigDecimal ltp = getLivePrice(token);
        
        if (ltp == null) return;

        // Force Exit Bypass (EOD 3:20 PM)
        if (forceExit) {
            closeTrade(activeTrade, ltp, triggerReason, strategyConfig, sourceConfig);
            return;
        }

        BigDecimal entryPrice = activeTrade.getAskPrice();
        BigDecimal slPoints = sourceConfig.getSlPoints() != null ? sourceConfig.getSlPoints() : BigDecimal.valueOf(10);
        BigDecimal targetPoints = sourceConfig.getTargetPoints() != null ? sourceConfig.getTargetPoints() : BigDecimal.valueOf(20);

        BigDecimal currentSl = entryPrice.add(slPoints);      // We are short, so SL is above entry
        BigDecimal targetPrice = entryPrice.subtract(targetPoints); // Target is below entry

        int reqSlHits = sourceConfig.getExitHitsRequired() > 0 ? sourceConfig.getExitHitsRequired() : 5;
        String exitKey = activeTrade.getName() + "_EXIT_HITS";

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
                
                // Broker call generates the DB row, we fetch it to upgrade it
                order = ordersRepository.findByNameAndTokenAndActive(sourceConfig.getName(), tokenStr, STATUS_ACTIVE).orElse(null);
                
            } else if (isPaper) {
                log.info("📄 [{}][{}] PAPER MODE: Simulating broker...", tradeName, type);
                order = new Orders();
                order.setToken(tokenStr);
                order.setSymbol(symbol);
                order.setQuantity(sourceConfig.getQuantity()); 
                order.setExchange(sourceConfig.getExchange()); 
                order.setActive(STATUS_ACTIVE);
            } else {
                log.warn("⚠️ [{}] Execution skipped. Both LIVE and PAPER flags are 'N' in config.", tradeName);
                return;
            }

            // Upgrade the DB row with strategy-specific details
            if (order != null) {
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
            }
        } catch (Exception | SmartAPIException e) {
            log.error("❌ [{}][LEG] System error during execution: {}", tradeName, e.getMessage());
        }
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

    private boolean isSquareOffTime(String symbol, LocalTime now) {
        if ("NIFTY".equalsIgnoreCase(symbol)) return !now.isBefore(NIFTY_SQUARE_OFF);
        return false;
    }
    
    private boolean isWithinEntryWindow(String symbol, LocalTime now) {
        if ("NIFTY".equalsIgnoreCase(symbol)) return now.isBefore(NIFTY_ENTRY_CUTOFF);
        return true; 
    }

    private BigDecimal getLivePrice(String token) {
        BigDecimal price = angelWebSocketService.getLatestLTP(ExchangeType.NSE_FO, token);
        return price == null ? null : price.setScale(2, RoundingMode.HALF_UP);
    }
}