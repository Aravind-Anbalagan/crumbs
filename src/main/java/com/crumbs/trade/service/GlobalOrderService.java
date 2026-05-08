package com.crumbs.trade.service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import com.angelbroking.smartapi.utils.Constants;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.StrategyDTO;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.StrategyRepo;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class GlobalOrderService {

    private static final Logger logger = LoggerFactory.getLogger(GlobalOrderService.class);

    private final String ACTIVE_BROKER = "ANGELONE"; 
    private final String SIG_PREFIX    = "GLB_"; 

    @Autowired private StrategyRepo strategyRepo;
    @Autowired private OrderRepository ordersRepo;
    @Autowired private IndexesRepo indexesRepo;
    @Autowired private TaskService taskService;
    @Autowired private ChartService chartService;
    @Autowired private AngelOne angelOne;
    @Autowired private AngelOneService angelOneService;
    @Autowired private FlatTradeService flatTradeService;
    @Autowired private AngelWebSocketService angelWebSocketService;

    public String processGlobalEntry(String instrument, String action) throws Exception {
        String signalName = SIG_PREFIX + instrument + "_" + action.toUpperCase();

        List<Orders> active = ordersRepo.findAllByNameAndActive(instrument, 1);
        if (active.stream().anyMatch(o -> signalName.equalsIgnoreCase(o.getSignal()))) {
            return "SKIP: Duplicate order restricted. " + signalName + " is already active.";
        }

        Strategy strategy = strategyRepo.findByName(instrument);
        StrategyDTO base = taskService.getStrategyDetails(instrument, strategy.getExchange());
        BigDecimal ltp = fetchLtp(base);
        
        int strikeStep = (instrument.contains("BANKNIFTY") || instrument.contains("SENSEX")) ? 100 : 50;
        int strike = chartService.findNearestMultiple(ltp.intValue(), strikeStep);
        String cycleId = UUID.randomUUID().toString();

        List<String> legs = "STRADDLE".equalsIgnoreCase(action) ? List.of("CE", "PE") : List.of(action.toUpperCase());
        
        for (String optType : legs) {
            String symbol = String.format("%s%s%d%s", strategy.getName(), strategy.getExpiry(), strike, optType);
            Indexes idx = indexesRepo.findByNameAndSymbol(instrument, symbol);
            
            if (idx == null) continue;

            // 1. Prepare Token for broker
            Token t = buildToken(idx, instrument, signalName, "BUY", ltp, strike);
            
            // 2. IMPORTANT: Fetch the actual premium (LTP) of the OPTION leg
            // This replaces the 'ltp' (Future price) with the 'optionPremium'
            BigDecimal optionPremium = "FLATTRADE".equalsIgnoreCase(ACTIVE_BROKER)
                ? flatTradeService.getCurrentPrice(idx.getExchange(), idx.getToken())
                : angelOneService.getcurrentPrice(angelOne.signIn(), idx.getExchange(), idx.getSymbol(), idx.getToken());

            // 3. Execute Trade
            String brokerOrderId = executeBrokerCall(t, strategy.getLive());
            
            // 4. Use execution price from broker if live, else use the optionPremium we just fetched
            BigDecimal finalPrice = getActualOrLtp(brokerOrderId, optionPremium);

            // 5. Record in DB - now 'finalPrice' will be the premium (e.g., 150.00) not the future (8993.00)
            recordInDb(t, cycleId, brokerOrderId, 1, "ENTRY", "OPEN", finalPrice);
            
            // WebSocket Subscription
            angelWebSocketService.subscribe(getExchangeType(idx.getExchange()), idx.getToken());
        }
        return "SUCCESS: Entry placed for " + signalName;
    }

    public String processGlobalExit(String instrument, String action) throws Exception {
        String signalName = SIG_PREFIX + instrument + "_" + action.toUpperCase();
        List<Orders> activeLegs = ordersRepo.findAllByNameAndActive(instrument, 1).stream()
                .filter(o -> signalName.equalsIgnoreCase(o.getSignal()))
                .toList();

        if (activeLegs.isEmpty()) return "SKIP: No active trade for " + signalName;

        Strategy strategy = strategyRepo.findByName(instrument);
        for (Orders o : activeLegs) {
            Token t = new Token();
            t.setToken(o.getToken()); 
            t.setSymbol(o.getSymbol()); 
            t.setQuantity(o.getQuantity());
            t.setExch_seg(o.getExchange()); 
            t.setTransactionType("BUY".equalsIgnoreCase(o.getType()) ? "SELL" : "BUY");
            
            // Populate missing fields for exit to avoid NullPointerException in broker service
            t.setOrderType(Constants.ORDER_TYPE_MARKET); 
            t.setVariety(Constants.VARIETY_NORMAL); 
            t.setProductType(Constants.PRODUCT_CARRYFORWARD); 
            t.setName(o.getName()); 
            t.setSignal("EXIT");

            String exitOrderId = executeBrokerCall(t, strategy.getLive());
            
            // Fetch exit price: Actual from broker if live, else current LTP
            BigDecimal currentLtp = fetchLtpForLeg(o);
            BigDecimal finalExitPrice = getActualOrLtp(exitOrderId, currentLtp);

            // Calculate Final PnL
            BigDecimal qty = BigDecimal.valueOf(o.getQuantity());
            BigDecimal pnl = "BUY".equalsIgnoreCase(o.getType()) 
                    ? finalExitPrice.subtract(o.getAskPrice()).multiply(qty) 
                    : o.getAskPrice().subtract(finalExitPrice).multiply(qty);

            o.setActive(0); 
            o.setStatus("CLOSED"); 
            o.setTradePhase("EXIT");
            o.setExitPrice(finalExitPrice); // Ensure this field exists in your Orders entity
            o.setPl(pnl);             // Ensure this field exists in your Orders entity
            o.setClosedOn(LocalDateTime.now());
            ordersRepo.save(o);
         // Trigger WebSocket Unsubscription
            try {
                angelWebSocketService.unsubscribe(getExchangeType(o.getExchange()), o.getToken());
            } catch (Exception e) {
                logger.error("WebSocket Unsubscription failed for {}", o.getSymbol());
            }
        }
        return "SUCCESS: Exited " + signalName;
    }

    /**
     * Logic: If orderId is not null/SIM, check broker for actual execution price. 
     * Otherwise, fallback to the provided LTP.
     */
    private BigDecimal getActualOrLtp(String orderId, BigDecimal fallbackLtp) {
        if (orderId == null || orderId.isEmpty() || orderId.startsWith("SIM_")) {
            return fallbackLtp;
        }
        try {
            // Wait for broker to process trade execution
            Thread.sleep(800); 
            return fetchRealExecutionPrice(orderId, fallbackLtp);
        } catch (Exception e) {
            logger.error("Error fetching execution price for {}, using LTP", orderId);
            return fallbackLtp;
        }
    }

    public String getLivePnl(String instrument, String action) throws Exception {
        String signalName = SIG_PREFIX + instrument + "_" + action.toUpperCase();
        List<Orders> activeLegs = ordersRepo.findAllByNameAndActive(instrument, 1).stream()
                .filter(o -> signalName.equalsIgnoreCase(o.getSignal()))
                .toList();

        if (activeLegs.isEmpty()) return "0.00";

        BigDecimal netPnl = BigDecimal.ZERO;
        for (Orders leg : activeLegs) {
            // Fetch from WebSocket Cache instead of API
            BigDecimal currentLtp = angelWebSocketService.getLatestLTP(
                    getExchangeType(leg.getExchange()), 
                    leg.getToken()
            );

            // Fallback: If WebSocket hasn't received a tick yet, use entry price to show 0.00 PnL
            if (currentLtp.compareTo(BigDecimal.ZERO) == 0) {
                currentLtp = leg.getAskPrice();
            }

            BigDecimal qty = BigDecimal.valueOf(leg.getQuantity());
            BigDecimal diff = "BUY".equalsIgnoreCase(leg.getType()) 
                    ? currentLtp.subtract(leg.getAskPrice()) 
                    : leg.getAskPrice().subtract(currentLtp);
            
            netPnl = netPnl.add(diff.multiply(qty));
        }
        return netPnl.setScale(2, RoundingMode.HALF_UP).toString();
    }

    private BigDecimal fetchRealExecutionPrice(String orderId, BigDecimal fallback) {
        try {
            JSONObject data = null;
            if ("FLATTRADE".equalsIgnoreCase(ACTIVE_BROKER)) {
                data = flatTradeService.getIndividualOrderDetails(orderId);
            } else {
                SmartConnect sc = angelOne.signIn();
                JSONObject response = sc.getIndividualOrderDetails(orderId);
                if (response != null && response.has("data")) {
                    data = response.getJSONObject("data");
                }
            }

            if (data != null) {
                String priceKey = "FLATTRADE".equalsIgnoreCase(ACTIVE_BROKER) ? "avgprc" : "averageprice";
                String priceStr = data.optString(priceKey, "0");
                BigDecimal price = new BigDecimal(priceStr);
                if (price.compareTo(BigDecimal.ZERO) > 0) return price;
            }
        } catch (Exception | SmartAPIException e) {
            logger.error("Price fetch failed for {}: {}", orderId, e.getMessage());
        }
        return fallback;
    }

    private String executeBrokerCall(Token t, String live) throws Exception {
        if (!"Y".equalsIgnoreCase(live)) return "SIM_" + UUID.randomUUID().toString().substring(0,8);
        
        try {
            Token processedToken;
            if ("FLATTRADE".equalsIgnoreCase(ACTIVE_BROKER)) {
                processedToken = flatTradeService.PlaceOrderInFlatTrade(t);
            } else {
                processedToken = angelOneService.placeOrder(angelOne.signIn(), t);
            }
            return (processedToken != null) ? processedToken.getOrderId() : null;
        } catch (SmartAPIException e) {
            throw new RuntimeException("Broker API Error: " + e.getMessage(), e);
        }
    }

    private BigDecimal fetchLtp(StrategyDTO dto) throws Exception {
        return "FLATTRADE".equalsIgnoreCase(ACTIVE_BROKER)
            ? flatTradeService.getCurrentPrice(dto.getExchange(), dto.getToken())
            : angelOneService.getcurrentPrice(angelOne.signIn(), dto.getExchange(), dto.getTradingsymbol(), dto.getToken());
    }

    private BigDecimal fetchLtpForLeg(Orders o) throws Exception {
        return "FLATTRADE".equalsIgnoreCase(ACTIVE_BROKER)
            ? flatTradeService.getCurrentPrice(o.getExchange(), o.getToken())
            : angelOneService.getcurrentPrice(angelOne.signIn(), o.getExchange(), o.getSymbol(), o.getToken());
    }

    private Token buildToken(Indexes i, String name, String sig, String side, BigDecimal ltp, int strike) {
        Token t = new Token();
        t.setSymbol(i.getSymbol()); t.setToken(i.getToken()); t.setExch_seg(i.getExchange());
        t.setQuantity(i.getLotsize()); t.setTransactionType(side); t.setOrderType(Constants.ORDER_TYPE_MARKET);
        t.setProductType(Constants.PRODUCT_CARRYFORWARD); t.setVariety(Constants.VARIETY_NORMAL);
        t.setName(name); t.setSignal(sig); t.setCurrentPrice(ltp); t.setStrike(BigDecimal.valueOf(strike));
        return t;
    }

    private void recordInDb(Token t, String cycleId, String orderId, int active, String phase, String status, BigDecimal price) {
        Orders o = new Orders();
        o.setActive(active); o.setName(t.getName()); o.setSymbol(t.getSymbol());
        o.setToken(t.getToken()); o.setExchange(t.getExch_seg()); o.setQuantity(t.getQuantity());
        o.setSignal(t.getSignal()); o.setType(t.getTransactionType()); o.setOrderid(orderId);
        o.setOptionType(t.getSymbol().substring(t.getSymbol().length()-2).toUpperCase());
        o.setTradeCycleId(cycleId); o.setTradePhase(phase); o.setStatus(status);
        o.setStrike(t.getStrike()); o.setAskPrice(price); o.setCreatedOn(LocalDateTime.now());
        ordersRepo.save(o);
    }
    
    private ExchangeType getExchangeType(String exchange) {
        return switch (exchange.toUpperCase()) {
            case "NFO", "NSE_FO" -> ExchangeType.NSE_FO;
            case "MCX", "MCX_FO" -> ExchangeType.MCX_FO;
            case "BFO", "BSE_FO" -> ExchangeType.BSE_FO;
            default -> ExchangeType.NSE_CM;
        };
    }
}