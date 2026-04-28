package com.crumbs.trade.service;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.MarketTrendChartDTO;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.StraddleIntraday;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.entity.Vix;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.ShortStraddleRepository;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.repo.VixRepo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MarketTrendChartService {

    private static final Logger log = LoggerFactory.getLogger(MarketTrendChartService.class);

    // ⚙️ Strategy Config
    private static final String STRATEGY_MODE         = "OPTION_BUYER"; 
    private static final String DB_PLACEHOLDER_PREFIX = "MARKET_TREND_"; 
    private static final String TIMEFRAME             = "FIVE_MINUTE";
    private static final int SIGNAL_LOOKBACK_CANDLES  = 10;   

    // Dependencies
    private final VixRepo vixRepo;
    private final AngelWebSocketService webSocketService;
    private final OrderRepository ordersRepo;
    private final OrderService orderService; 
    private final StrategyRepo strategyRepo;
    private final ShortStraddleRepository shortStraddleRepo;
    private final AngelOneService angelOneService; 
    private final AngelOne angelOne;

    /**
     * 🧠 Main Logic Loop
     */
    public void evaluateStrategy(String symbol, ExchangeType exchange) {
        String dbLookupName = DB_PLACEHOLDER_PREFIX + symbol.toUpperCase();

        try {
            Strategy config = strategyRepo.findByName(dbLookupName);
            if (config == null) return;

            // Fetch Underlying LTP with Fallback
            BigDecimal underlyingLtp = getValidLtp(config.getExchange(), config.getToken(), symbol);
            if (underlyingLtp == null || underlyingLtp.compareTo(BigDecimal.ZERO) <= 0) return;

            List<MarketTrendChartDTO> data = getMarketTrendData(symbol, TIMEFRAME);
            if (data.isEmpty()) return;

            MarketTrendChartDTO latestCandle = data.get(data.size() - 1);
            Orders activeTrade = ordersRepo.findByNameAndActive(dbLookupName, 1);

            if (activeTrade == null) {
                processEntry(dbLookupName, symbol, data, underlyingLtp, config);
            } else {
                // Fetch Option LTP with Fallback
                BigDecimal optionLtp = getValidLtp(activeTrade.getExchange(), activeTrade.getToken(), activeTrade.getSymbol());
                if (optionLtp != null) {
                    processExit(activeTrade, latestCandle, optionLtp, config);
                }
            }
        } catch (Exception | SmartAPIException e) {
            log.error("💥 Strategy System Error [{}]: {}", dbLookupName, e.getMessage());
        }
    }

    /**
     * 🟢 Entry Logic with Post-Enrichment
     * @throws SmartAPIException 
     */
    private void processEntry(String strategyName, String symbol, List<MarketTrendChartDTO> data, BigDecimal underlyingLtp, Strategy config) 
            throws Exception, SmartAPIException {
        
        int size = data.size();
        BigDecimal currentVwap = data.get(size - 1).getVwap();
        
        // Signal Detection
        String foundSignal = "NONE";
        for (int i = size - 1; i >= Math.max(0, size - SIGNAL_LOOKBACK_CANDLES); i--) {
            String event = data.get(i).getCrossoverEvent();
            if ("BUY_CROSS".equals(event) || "SELL_CROSS".equals(event)) {
                foundSignal = event;
                break;
            }
        }

        boolean isBullish = "BUY_CROSS".equals(foundSignal) && underlyingLtp.compareTo(currentVwap) > 0;
        boolean isBearish = "SELL_CROSS".equals(foundSignal) && underlyingLtp.compareTo(currentVwap) < 0;

        if (isBullish || isBearish) {
            Optional<StraddleIntraday> atmData = shortStraddleRepo.findATMBySymbol(symbol);
            if (atmData.isEmpty()) return;
            StraddleIntraday atm = atmData.get();

            Token token = new Token();
            token.setExch_seg(config.getExchange());
            String side, optType;

            if ("OPTION_BUYER".equalsIgnoreCase(STRATEGY_MODE)) {
                side = "BUY";
                if (isBullish) { 
                    optType = "CE"; token.setToken(atm.getCeToken()); token.setSymbol(atm.getCeSymbol()); 
                } else { 
                    optType = "PE"; token.setToken(atm.getPeToken()); token.setSymbol(atm.getPeSymbol()); 
                }
            } else {
                side = "SELL";
                if (isBullish) { 
                    optType = "PE"; token.setToken(atm.getPeToken()); token.setSymbol(atm.getPeSymbol()); 
                } else { 
                    optType = "CE"; token.setToken(atm.getCeToken()); token.setSymbol(atm.getCeSymbol()); 
                }
            }

            // 1. Place order via generic service (creates initial DB row)
            orderService.orderPlaceWithToken(token, strategyName, side);

            // 2. Post-Enrichment: Catch the row and fill strategy details
            Orders newlyCreated = ordersRepo.findByNameAndActive(strategyName, 1);
            if (newlyCreated != null) {
                newlyCreated.setSignal(foundSignal);
                newlyCreated.setStrike(atm.getStrike());
                newlyCreated.setOptionType(optType);
                newlyCreated.setSide(side);
                newlyCreated.setTarget(config.getTargetPoints());
                newlyCreated.setSl(config.getSlPoints());
                newlyCreated.setTradeCycleId(UUID.randomUUID().toString());
                newlyCreated.setTradePhase("ENTRY");
                newlyCreated.setStatus("OPEN");
                newlyCreated.setCreatedOn(LocalDateTime.now());
                
                // Set accurate Option Entry Price
                BigDecimal entryPrice = getValidLtp(newlyCreated.getExchange(), newlyCreated.getToken(), newlyCreated.getSymbol());
                newlyCreated.setAskPrice(entryPrice);
                
                ordersRepo.save(newlyCreated);
                log.info("📝 DB Enriched for {}: {} {} @ {}", strategyName, optType, side, entryPrice);
            }
        }
    }

    /**
     * 🔴 Exit Logic with PnL Calculation
     * @throws SmartAPIException 
     */
    private void processExit(Orders active, MarketTrendChartDTO candle, BigDecimal optionLtp, Strategy config) 
            throws Exception, SmartAPIException {
        
        BigDecimal entryPrice = active.getAskPrice() != null ? active.getAskPrice() : BigDecimal.ZERO;
        BigDecimal targetPoints = active.getTarget() != null ? active.getTarget() : new BigDecimal("25");
        BigDecimal slPoints = active.getSl() != null ? active.getSl() : new BigDecimal("10");

        BigDecimal pnlPerUnit = active.getSide().equalsIgnoreCase("BUY") 
                ? optionLtp.subtract(entryPrice) 
                : entryPrice.subtract(optionLtp);

        boolean exitTriggered = false;
        String reason = "";

        if (pnlPerUnit.compareTo(targetPoints) >= 0) {
            exitTriggered = true;
            reason = "TARGET_REACHED";
        } else if (pnlPerUnit.negate().compareTo(slPoints) >= 0) {
            exitTriggered = true;
            reason = "PRICE_SL_BREACHED";
        }

        if (exitTriggered) {
            active.setExitPrice(optionLtp);
            active.setExitReason(reason);
            active.setTradePhase("EXIT");
            active.setStatus("CLOSED");
            active.setClosedOn(LocalDateTime.now());
            active.setPl(pnlPerUnit.multiply(new BigDecimal(active.getQuantity())));

            ordersRepo.save(active);
            orderService.exitActiveTrade(active.getName());
            log.info("🏁 Exit Recorded: {} | Reason: {} | PL: {}", active.getName(), reason, active.getPl());
        }
    }

    /**
     * 🛡️ Helper: Reliable LTP Fetcher (WS -> HTTP Fallback)
     */
    private BigDecimal getValidLtp(String exchange, String token, String symbol) {
        // Map "MCX" -> MCX_FO to avoid IllegalArgumentException
        ExchangeType wsExch = getWsExchange(exchange);
        
        BigDecimal ltp = webSocketService.getLatestLTP(wsExch, token);
        
        if (ltp == null || ltp.compareTo(BigDecimal.ZERO) <= 0) {
            try {
                log.warn("🔌 WS Cache empty for {}. Calling API...", symbol);
                ltp = angelOneService.getcurrentPrice(angelOne.signIn(), exchange, symbol, token);
            } catch (Exception e) {
                log.error("❌ Failed to get LTP for {}: {}", symbol, e.getMessage());
            }
        }
        return ltp;
    }

    private ExchangeType getWsExchange(String dbExch) {
        if (dbExch == null) return ExchangeType.NSE_CM;
        return switch (dbExch.toUpperCase()) {
            case "MCX" -> ExchangeType.MCX_FO;
            case "NFO" -> ExchangeType.NSE_FO;
            case "NSE" -> ExchangeType.NSE_CM;
            default -> ExchangeType.NSE_CM;
        };
    }

    public List<MarketTrendChartDTO> getMarketTrendData(String symbol, String timeframe) {
        List<Vix> records = vixRepo.findTop200ByNameAndTimeframeOrderByTimestampDesc(symbol, timeframe);
        if (records == null || records.isEmpty()) return new ArrayList<>();

        return records.stream().map(v -> new MarketTrendChartDTO(
                v.getTimestamp(), 
                v.getOpen(), 
                v.getHigh(), 
                v.getLow(), 
                v.getClose(),
                v.getVwap(), 
                v.getFastEma(), 
                v.getSlowEma(),
                v.getCrossoverEvent(), 
                v.getMasignal(),
                v.getSignal() // 👈 Just pull the value saved in the DB
        ))
        .sorted(Comparator.comparing(MarketTrendChartDTO::getTimestamp))
        .collect(Collectors.toList());
    }
    
 // Logic to run BEFORE saving to the DB
    public void processAndSaveVix(Vix vix) {
        String crossover = vix.getCrossoverEvent();
        BigDecimal close = vix.getClose();
        BigDecimal vwap = vix.getVwap();

        if ("BUY_CROSS".equals(crossover) && close.compareTo(vwap) > 0) {
            vix.setSignal("BUY");
        } else if ("SELL_CROSS".equals(crossover) && close.compareTo(vwap) < 0) {
            vix.setSignal("SELL");
        } else {
            vix.setSignal(null); // Keep it clean if conditions aren't met
        }

        vixRepo.save(vix);
    }
}