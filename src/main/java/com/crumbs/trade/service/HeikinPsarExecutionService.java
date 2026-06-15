package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.entity.Vix;
import com.crumbs.trade.entity.StraddleIntraday;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.repo.VixRepo;
import com.crumbs.trade.repo.ShortStraddleRepository;

@Service
public class HeikinPsarExecutionService {

    private static final Logger logger = LogManager.getLogger(HeikinPsarExecutionService.class);

    // =========================================================
    // 🛠️ 1. SYSTEM CONFIGURATION & GLOBAL TOGGLES 
    // =========================================================
    
    // Trade Mode: "TREND_FOLLOWING" (Wait for reverse signal) OR "SCALPING" (Fixed Target/SL)
    private static final String TRADE_MODE = "TREND_FOLLOWING"; 
    
    // Option Mode: true = Option Buyer (Long CE/PE), false = Option Seller (Short CE/PE)
    private static final boolean IS_OPTION_BUYER = false; 
    
    // Scalping Configuration (Only applies if TRADE_MODE = "SCALPING" and IS_OPTION_BUYER = true)
    private static final BigDecimal SCALP_TARGET_POINTS = new BigDecimal("20.00");
    private static final BigDecimal SCALP_SL_POINTS = new BigDecimal("10.00");


    // =========================================================
    // 🏦 2. INSTRUMENT & EXCHANGE CONSTANTS
    // =========================================================
    
    private static final String NIFTY = "SAMCO_NIFTY";
    private static final String CRUDEOIL = "CRUDEOIL";
    private static final String SAMCO_CRUDEOIL = "SAMCO_CRUDEOIL";
    
    private static final String EXCHANGE_NSE = "NSE";
    private static final String EXCHANGE_NFO = "NFO";
    private static final String EXCHANGE_MCX = "MCX";
    
    private static final String TF_FIVE_MIN = "FIVE_MINUTE";
    private static final String ACTIVE_YES = "Y";

    // Required Repositories & Services
    @Autowired private ChartService chartService;
    @Autowired private StrategyRepo strategyRepo;
    @Autowired private VixRepo vixRepo;
    @Autowired private OrderRepository ordersRepository;
    @Autowired private OrderService orderService;
    @Autowired private TelegramService telegramService;
    @Autowired private ShortStraddleRepository straddleRepository;

    // =========================================================
    // 🚀 CORE EXECUTIONS
    // =========================================================

    public void commonExecutionNifty() {
        try {
            executeNiftyInternal();
        } catch (SmartAPIException e) {
            logApiError(NIFTY, e);
        } catch (Exception e) {
            logGeneralError(NIFTY, e);
        }
    }

    public void commonExecutionMcx() {
        try {
            executeMcxInternal();
        } catch (SmartAPIException e) {
            logApiError(CRUDEOIL, e);
        } catch (Exception e) {
            logGeneralError(CRUDEOIL, e);
        }
    }

    // ================= INTERNAL DATA FETCHING =================

    private void executeNiftyInternal() throws Exception, SmartAPIException {
        Strategy strategy = strategyRepo.findByName(NIFTY);
        if (!isStrategyActive(strategy)) return;

        String to = chartService.getDate("TO", EXCHANGE_NSE, 1);
        String from;

        Optional<Vix> lastRecord = vixRepo.findFirstByNameOrderByTimestampDesc(NIFTY);
        if (lastRecord.isPresent()) {
            String rawTimestamp = lastRecord.get().getTimestamp();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            from = OffsetDateTime.parse(rawTimestamp).format(formatter); 
        } else {
            from = chartService.getDate("FROM", EXCHANGE_NSE, 1);
        }

        chartService.readChartData(TF_FIVE_MIN, EXCHANGE_NFO, false, NIFTY, from, to, strategy.getTradingsymbol(), "SAMCO");
        vixRepo.findFirstByNameOrderByTimestampDesc(NIFTY).ifPresent(candle -> evaluateAndExecuteTrade(candle, strategy));
    }

    private void executeMcxInternal() throws Exception, SmartAPIException {
        Strategy strategy = strategyRepo.findByName(SAMCO_CRUDEOIL);
        if (!isStrategyActive(strategy)) return;

        String to = chartService.getDate("TO", EXCHANGE_MCX, 1);
        String from;

        Optional<Vix> lastRecord = vixRepo.findFirstByNameOrderByTimestampDesc(CRUDEOIL);
        if (lastRecord.isPresent()) {
            from = ChartService.formatDateTime(lastRecord.get().getTimestamp());
        } else {
            from = chartService.getDate("FROM", EXCHANGE_MCX, 1);
        }
        
        chartService.readChartData(TF_FIVE_MIN, strategy.getExchange(), false, CRUDEOIL, from, to, strategyRepo.findByName(CRUDEOIL).getTradingsymbol(), "SAMCO");
        vixRepo.findFirstByNameOrderByTimestampDesc(CRUDEOIL).ifPresent(candle -> evaluateAndExecuteTrade(candle, strategy));
    }

    // =========================================================
    // 🧠 BRAIN: ORDER EVALUATION & STATE MACHINE
    // =========================================================

    private static final String HEIKIN_SIGNAL = "HEIKIN_PSAR";
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_INACTIVE = 0; 
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String PHASE_ENTRY = "ENTRY";
    private static final String PHASE_EXIT = "EXIT";

    private void evaluateAndExecuteTrade(Vix latestCandle, Strategy strategy) {
        if (latestCandle == null || strategy == null) return;

        String instrument = strategy.getTradingsymbol(); 
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));

        // Generate Unique Trade Name (e.g., HEIKIN_NIFTY, HEIKIN_CRUDEOIL)
        String baseSymbol = getBaseSymbol(strategy.getName());
        String tradeName = "HEIKIN_" + baseSymbol;

        // Timeframes
        boolean isNiftyValid = instrument.contains("NIFTY") && !now.isBefore(LocalTime.of(9, 30)) && now.isBefore(LocalTime.of(15, 20));
        boolean isCrudeValid = instrument.contains("CRUDEOIL") && !now.isBefore(LocalTime.of(16, 0)) && now.isBefore(LocalTime.of(23, 0));
        boolean isNiftySquareOff = instrument.contains("NIFTY") && !now.isBefore(LocalTime.of(15, 20));
        boolean isCrudeSquareOff = instrument.contains("CRUDEOIL") && !now.isBefore(LocalTime.of(23, 0));

        // ---------------------------------------------------------
        // PHASE 1: EVALUATE OPEN TRADES (EXIT LOGIC)
        // ---------------------------------------------------------
        Orders openTrade = ordersRepository.findByNameAndActive(tradeName, STATUS_ACTIVE);
        
        if (openTrade != null) {
            
            // 1. Force EOD Square Off (Always applies)
            if (isNiftySquareOff || isCrudeSquareOff) {
                logger.info("🕒 [{}][EXIT] Square-off time reached.", tradeName);
                processExit(openTrade, latestCandle, "EOD_SQUARE_OFF", strategy);
                return;
            }
            
            // 2. SCALPING EXIT (Only for Buyers with Fixed Targets)
            if ("SCALPING".equalsIgnoreCase(TRADE_MODE) && IS_OPTION_BUYER) {
                BigDecimal currentPremium = getCurrentOptionPremium(baseSymbol, openTrade.getStrike(), openTrade.getOptionType());
                BigDecimal entryPremium = openTrade.getAskPrice();
                
                if (currentPremium != null && entryPremium != null) {
                    BigDecimal pointsGained = currentPremium.subtract(entryPremium); // Buyer Math: Current - Entry
                    
                    if (pointsGained.compareTo(SCALP_TARGET_POINTS) >= 0) {
                        logger.info("🎯 [{}][SCALP] Target Hit! Secured: +{} pts", tradeName, pointsGained.setScale(2, RoundingMode.HALF_UP));
                        processExit(openTrade, latestCandle, "SCALP_TARGET_HIT", strategy);
                        return;
                    }
                    
                    if (pointsGained.compareTo(SCALP_SL_POINTS.negate()) <= 0) { 
                        logger.info("🛑 [{}][SCALP] Stop Loss Hit! Loss: {} pts", tradeName, pointsGained.setScale(2, RoundingMode.HALF_UP));
                        processExit(openTrade, latestCandle, "SCALP_SL_HIT", strategy);
                        return;
                    }
                }
            }
            
            // 3. TREND FOLLOWING EXIT (Applies to Sellers, or if TRADE_MODE is Trend Following)
            if ("TREND_FOLLOWING".equalsIgnoreCase(TRADE_MODE) || !IS_OPTION_BUYER) {
                String currentSignal = latestCandle.getSignal();
                boolean isLongReversal = "CE".equalsIgnoreCase(openTrade.getOptionType()) && "SELL".equalsIgnoreCase(currentSignal);
                boolean isShortReversal = "PE".equalsIgnoreCase(openTrade.getOptionType()) && "BUY".equalsIgnoreCase(currentSignal);
                
                if (isLongReversal || isShortReversal) {
                    logger.info("🔄 [{}][EXIT] Trend Reversal detected. Closing position.", tradeName);
                    processExit(openTrade, latestCandle, "TREND_REVERSAL", strategy);
                }
            }
            
            return; // If trade is open but no exit condition met, keep holding.
        }

        // ---------------------------------------------------------
        // PHASE 2: EVALUATE NEW ENTRIES
        // ---------------------------------------------------------
        if (!isNiftyValid && !isCrudeValid) return; 

        String signal = latestCandle.getSignal();
        if (signal == null || "NONE".equalsIgnoreCase(signal)) return;
        
        String orderType = "";
        String optionType = "";

        if ("BUY".equalsIgnoreCase(signal)) { // BULLISH
            if (IS_OPTION_BUYER) {
                orderType = "BUY"; optionType = "CE"; // Scalper/Buyer goes Long Call
            } else {
                orderType = "SELL"; optionType = "PE"; // Trend Seller goes Short Put
            }
        } else if ("SELL".equalsIgnoreCase(signal)) { // BEARISH
            if (IS_OPTION_BUYER) {
                orderType = "BUY"; optionType = "PE"; // Scalper/Buyer goes Long Put
            } else {
                orderType = "SELL"; optionType = "CE"; // Trend Seller goes Short Call
            }
        }

        if (!orderType.isEmpty()) {
            processEntry(strategy, latestCandle, orderType, optionType, tradeName);
        }
    }

    // =========================================================
    // ⚙️ BROKER EXECUTION & DB UPDATES
    // =========================================================

    private void processEntry(Strategy strategyConfig, Vix candle, String type, String optionType, String tradeName) {
        BigDecimal spotPrice = candle.getClose(); 
        String baseSymbol = getBaseSymbol(strategyConfig.getName());
        BigDecimal atmStrike = calculateAtmStrike(baseSymbol, spotPrice);
        
        // 1. Fetch Option Tokens & Premium
        StraddleIntraday optionData = straddleRepository.findLatestBySymbolAndStrike(baseSymbol, atmStrike).orElse(null);
        if (optionData == null) {
            logger.error("❌ [{}] No Option Data found for Symbol: {} | Strike: {}", tradeName, baseSymbol, atmStrike);
            return;
        }

        String optionToken = "CE".equalsIgnoreCase(optionType) ? optionData.getCeToken() : optionData.getPeToken();
        String optionSymbol = "CE".equalsIgnoreCase(optionType) ? optionData.getCeSymbol() : optionData.getPeSymbol();
        BigDecimal entryPremium = "CE".equalsIgnoreCase(optionType) ? optionData.getCePrice() : optionData.getPePrice();

        // 2. Fetch Quantity Dynamically 
        Strategy sourceConfig = strategyRepo.findByName(baseSymbol);
        int quantity = (sourceConfig != null && sourceConfig.getQuantity() > 0) 
                        ? sourceConfig.getQuantity() 
                        : strategyConfig.getQuantity();

        logger.info("🚀 [{}][EXECUTE] Opening {} {} | Spot: {} | Strike: {} | Premium: {} | Qty: {}", 
                    tradeName, type, optionType, spotPrice, atmStrike, entryPremium, quantity);
        
        boolean isLive = "Y".equalsIgnoreCase(strategyConfig.getLive());
        Orders order = null;

        try {
            Token t = new Token();
            t.setToken(optionToken); 
            t.setSymbol(optionSymbol); 
            t.setStrike(atmStrike);
            t.setName(tradeName); 
            t.setExch_seg(strategyConfig.getExchange()); 
            t.setQuantity(quantity);

            if (isLive) {
                orderService.orderPlaceWithToken(t, tradeName, type, true);
                order = ordersRepository.findByNameAndTokenAndActive(tradeName, optionToken, STATUS_ACTIVE).orElse(null);
            }

            if (order == null) {
                order = new Orders();
                order.setToken(optionToken); 
                order.setSymbol(optionSymbol); 
                order.setExchange(strategyConfig.getExchange()); 
                order.setActive(STATUS_ACTIVE); 
            }

            order.setName(tradeName); 
            order.setQuantity(quantity);
            order.setSignal(HEIKIN_SIGNAL);
            order.setType(type); 
            order.setOptionType(optionType); 
            order.setSide(optionType);
            order.setTradeCycleId(UUID.randomUUID().toString());
            order.setAskPrice(entryPremium); 
            order.setStrike(atmStrike);
            order.setStatus(STATUS_OPEN); 
            order.setTradePhase(PHASE_ENTRY);
            
            // Set Creation Timestamp (Change to setCreatedDate() if your DB entity uses that naming)
            order.setCreatedOn(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            
            ordersRepository.save(order);

            if (telegramService != null) {
                telegramService.sendMessage(String.format(
                    "🚀 **[%s] ENTRY [%s]**\nMode: %s\nSide: %s %s\nStrike: %.0f\nPremium: ₹%.2f\nQty: %d", 
                    TRADE_MODE, (isLive ? "LIVE" : "PAPER"), (IS_OPTION_BUYER ? "BUYER" : "SELLER"), 
                    type, optionType, atmStrike, entryPremium, quantity
                ));
            }
        
        } catch (SmartAPIException e) {
            logger.error("🚨 SmartAPI Error {}: {}", tradeName, e.getMessage());
        } catch (Exception e) {
            logger.error("❌ Execution Failed {}: {}", tradeName, e.getMessage());
        }
    }

    private void processExit(Orders openTrade, Vix exitCandle, String reason, Strategy strategyConfig) {
        String tradeName = openTrade.getName();
        boolean isLive = "Y".equalsIgnoreCase(strategyConfig.getLive());
        
        try {
            if (isLive) {
                orderService.exitActiveTradeByToken(openTrade.getToken(), strategyConfig.getName(), tradeName);
            }

            // Fetch current premium for PnL
            BigDecimal exitPremium = getCurrentOptionPremium(getBaseSymbol(tradeName), openTrade.getStrike(), openTrade.getOptionType());
            if (exitPremium == null) exitPremium = BigDecimal.ZERO; 

            BigDecimal entryPrice = openTrade.getAskPrice() != null ? openTrade.getAskPrice() : BigDecimal.ZERO;
            BigDecimal pointsCollected = "BUY".equalsIgnoreCase(openTrade.getType()) 
                    ? exitPremium.subtract(entryPrice) 
                    : entryPrice.subtract(exitPremium); 

            BigDecimal rupeePnL = pointsCollected.multiply(BigDecimal.valueOf(openTrade.getQuantity())).setScale(2, RoundingMode.HALF_UP);

            openTrade.setExitPrice(exitPremium);
            openTrade.setPl(rupeePnL);
            openTrade.setClosedOn(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            openTrade.setTradePhase(PHASE_EXIT);
            openTrade.setStatus(STATUS_CLOSED);
            openTrade.setActive(STATUS_INACTIVE); // UNLOCK
            openTrade.setExitReason(reason);
            
            ordersRepository.save(openTrade); 
            
            String emoji = rupeePnL.signum() >= 0 ? "✅" : "❌";
            if (telegramService != null) {
                telegramService.sendMessage(String.format(
                    "%s **EXIT [%s]: %s**\nReason: %s\nStrike: %.0f\nEntry: %.2f | Exit: %.2f\nEst. PnL: **₹%.2f**", 
                    emoji, (isLive ? "LIVE" : "PAPER"), tradeName, reason, openTrade.getStrike(), entryPrice, exitPremium, rupeePnL
                ));
            }
            
        } catch (SmartAPIException e) {
            logger.error("🚨 [{}][EXIT] SmartAPI Broker Error: {}", tradeName, e.getMessage());
        } catch (Exception e) {
            logger.error("❌ [{}][EXIT] Error closing trade: {}", tradeName, e.getMessage());
        }
    }

    // =========================================================
    // 🧰 UTILITY HELPER METHODS
    // =========================================================

    private BigDecimal getCurrentOptionPremium(String baseSymbol, BigDecimal strike, String optionType) {
        StraddleIntraday optionData = straddleRepository.findLatestBySymbolAndStrike(baseSymbol, strike).orElse(null);
        if (optionData == null) return null;
        return "CE".equalsIgnoreCase(optionType) ? optionData.getCePrice() : optionData.getPePrice();
    }

    private String getBaseSymbol(String tradeName) {
        if (tradeName.contains("NIFTY")) return "NIFTY";
        if (tradeName.contains("CRUDEOIL")) return "CRUDEOIL";
        if (tradeName.contains("SENSEX")) return "SENSEX";
        return tradeName;
    }

    private BigDecimal calculateAtmStrike(String baseSymbol, BigDecimal spotPrice) {
        if ("NIFTY".equalsIgnoreCase(baseSymbol)) return spotPrice.divide(new BigDecimal("50"), 0, RoundingMode.HALF_UP).multiply(new BigDecimal("50"));
        if ("CRUDEOIL".equalsIgnoreCase(baseSymbol)) return spotPrice.divide(new BigDecimal("10"), 0, RoundingMode.HALF_UP).multiply(new BigDecimal("10"));
        if ("SENSEX".equalsIgnoreCase(baseSymbol)) return spotPrice.divide(new BigDecimal("100"), 0, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
        return spotPrice.setScale(0, RoundingMode.HALF_UP);
    }

    private boolean isStrategyActive(Strategy strategy) {
        if (strategy == null || !ACTIVE_YES.equalsIgnoreCase(strategy.getActive())) return false;
        DayOfWeek today = LocalDate.now(ZoneId.of("Asia/Kolkata")).getDayOfWeek();
        return !(today == DayOfWeek.SATURDAY || today == DayOfWeek.SUNDAY);
    }

    private void logApiError(String tag, SmartAPIException e) { logger.error("🚨 SmartAPI error [{}]", tag, e); }
    private void logGeneralError(String tag, Exception e) { logger.error("❌ Execution error [{}]", tag, e); }
}