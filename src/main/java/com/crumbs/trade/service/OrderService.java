package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.MessagingException;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.utils.Constants;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.OrderMeta;
import com.crumbs.trade.dto.StrategyDTO;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.StrategyRepo;
import jakarta.mail.internet.AddressException;

@Service
public class OrderService {

    static Logger logger = LoggerFactory.getLogger(OrderService.class);

    @Autowired AngelOne        angelOne;
    @Autowired StrategyRepo    strategyRepo;
    @Autowired AngelOneService angelOneService;
    @Autowired OrderRepository ordersRepo;
    @Autowired TaskService     taskService;
    @Autowired ChartService    chartService;
    @Autowired IndexesRepo     indexesRepo;

    // =========================================================================
    // ENTRY — without meta (backward compatible for other strategies)
    // =========================================================================
    public void orderPlace(String strategyName, int spotPrice, String signal)
            throws SmartAPIException, Exception {
        orderPlace(strategyName, spotPrice, signal, null);
    }

    // =========================================================================
    // ENTRY — with meta (CPR strategy carries full context)
    // =========================================================================
    public void orderPlace(String strategyName, int spotPrice, String signal, OrderMeta meta)
            throws SmartAPIException, Exception {

        logger.info("Order Trigger → Strategy={} | Signal={} | Meta={}",
                strategyName, signal, meta);

        SmartConnect sc = angelOne.signIn();
        if (sc == null) throw new Exception("AngelOne login failed");

        Strategy strategy = strategyRepo.findByName(strategyName);
        if (strategy == null) throw new Exception("Strategy not found: " + strategyName);

        // ------------------------------------------------------------------
        // 1. Check active trade
        // ------------------------------------------------------------------
        Orders activeTrade = ordersRepo.findByNameAndActive(strategyName, 1);

        if (activeTrade != null) {
            logger.info("Active Trade Found → {}", activeTrade.getSymbol());

            // Same direction → skip
            if (activeTrade.getType().equalsIgnoreCase(signal)) {
                logger.info("Same signal already active → SKIP");
                return;
            }

            // Opposite → exit only, do not lose control
            logger.info("Opposite Signal → EXIT current trade");
            if ("Y".equalsIgnoreCase(strategy.getLive())) {
                placeExitOrder(activeTrade);
            }

            activeTrade.setActive(0);
            ordersRepo.save(activeTrade);

            // Return — let next cycle place the new entry
            return;
        }

        // ------------------------------------------------------------------
        // 2. Place new entry
        // ------------------------------------------------------------------
        placeNewEntry(strategyName, spotPrice, signal, sc, strategy, meta);
    }

    // =========================================================================
    // ENTRY — with pre-resolved Token (straddle / options use case)
    //
    // Bypasses symbol resolution entirely — token is already known at call site.
    // Used by CPRStraddleService where CE/PE tokens are resolved from Indexes
    // table ahead of time via StraddleIntradayService.getAllTokenDetails().
    //
    // WHY this is needed:
    //   orderPlace() calls createToken() → getNameAndTradingSymbol() which
    //   looks up the Strategy by name. For straddle legs the strategy name
    //   is CPR_STRATEGY (not CPR_STRATEGY_CE / _PE) and the token is already
    //   in hand — so the lookup is redundant and fragile.
    // =========================================================================
    public void orderPlaceWithToken(Token token, String strategyName, String signal)
            throws SmartAPIException, Exception {

        logger.info("Order Trigger (pre-resolved token) → Strategy={} | Signal={} | Token={} | Symbol={}",
                strategyName, signal, token.getToken(), token.getSymbol());

        SmartConnect sc = angelOne.signIn();
        if (sc == null) throw new Exception("AngelOne login failed");

        Strategy strategy = strategyRepo.findByName(strategyName);
        if (strategy == null) throw new Exception("Strategy not found: " + strategyName);

        // ------------------------------------------------------------------
        // 1. Check active trade — same guard as existing orderPlace
        // ------------------------------------------------------------------
        Orders activeTrade = ordersRepo.findByNameAndActive(strategyName, 1);

        if (activeTrade != null) {
            logger.info("Active Trade Found → {}", activeTrade.getSymbol());

            if (activeTrade.getType().equalsIgnoreCase(signal)) {
                logger.info("Same signal already active → SKIP");
                return;
            }

            logger.info("Opposite Signal → EXIT current trade");
            if ("Y".equalsIgnoreCase(strategy.getLive())) {
                placeExitOrder(activeTrade);
            }

            activeTrade.setActive(0);
            ordersRepo.save(activeTrade);
            return;
        }

        // ------------------------------------------------------------------
        // 2. Resolve lot size from Indexes table and set on token
        //    (quantity comes from Indexes.lotsize — same as createToken())
        // ------------------------------------------------------------------
        if (token.getQuantity() == 0) {
            Indexes idx = indexesRepo.findByNameAndSymbol(
                    strategy.getName(), token.getSymbol());
            if (idx != null && idx.getLotsize() > 0) {
                token.setQuantity(idx.getLotsize());
                logger.info("Lot size resolved from Indexes -> symbol={} qty={}",
                        token.getSymbol(), idx.getLotsize());
            } else {
                logger.error("Lot size not found for symbol={} — order aborted.", token.getSymbol());
                return;
            }
        }

        // ------------------------------------------------------------------
        // 3. Prepare and place using the pre-resolved token directly
        // ------------------------------------------------------------------
        prepareSellOrder(token, strategyName, signal, null);
        placeFinalOrder(sc, token, strategy, 0, null);
    }

    // =========================================================================
    // ENTRY — with pre-resolved Token + skipActiveCheck flag
    //
    // skipActiveCheck = true  → bypasses active trade lookup
    //                           used for straddle legs (CE + PE both under
    //                           same strategy name — active check would skip PE)
    // skipActiveCheck = false → same behaviour as original orderPlaceWithToken
    // =========================================================================
    public void orderPlaceWithToken(Token token, String strategyName,
                                     String signal, boolean skipActiveCheck)
            throws SmartAPIException, Exception {

        logger.info("Order Trigger (token, skipCheck={}) -> Strategy={} | Signal={} | Symbol={}",
                skipActiveCheck, strategyName, signal, token.getSymbol());

        SmartConnect sc = angelOne.signIn();
        if (sc == null) throw new Exception("AngelOne login failed");
        Strategy strategy = strategyRepo.findByName(token.getName());
       
        if (strategy == null) throw new Exception("Strategy not found: " + strategyName);

        if (!skipActiveCheck) {
            Orders activeTrade = ordersRepo.findByNameAndActive(strategyName, 1);
            if (activeTrade != null) {
                logger.info("Active Trade Found -> {}", activeTrade.getSymbol());
                if (activeTrade.getType().equalsIgnoreCase(signal)) {
                    logger.info("Same signal already active -> SKIP");
                    return;
                }
                logger.info("Opposite Signal -> EXIT current trade");
                if ("Y".equalsIgnoreCase(strategy.getLive())) placeExitOrder(activeTrade);
                activeTrade.setActive(0);
                ordersRepo.save(activeTrade);
                return;
            }
        }

        prepareSellOrder(token, strategyName, signal, null);
        placeFinalOrder(sc, token, strategy, 0, null);
    }

    // =========================================================================
    // EXIT BY TOKEN  (straddle use case — two legs share same strategy name,
    //                 identified uniquely by their token)
    // =========================================================================
    public void exitActiveTradeByToken(String token, String strategyName)
            throws IOException, SmartAPIException {

        Strategy strategy    = strategyRepo.findByName(strategyName);
        Orders   activeTrade = ordersRepo.findByTokenAndActive(token, 1);

        if (activeTrade == null) {
            logger.info("No active trade for token -> {}", token);
            return;
        }

        logger.info("Exiting trade by token -> {} | symbol={}",
                token, activeTrade.getSymbol());

        if ("Y".equalsIgnoreCase(strategy.getLive())) {
            placeExitOrder(activeTrade);
        }

        activeTrade.setActive(0);
        ordersRepo.save(activeTrade);
        logger.info("Trade closed by token -> {}", activeTrade.getSymbol());
    }

    // =========================================================================
    // CREATE NEW ENTRY
    // =========================================================================
    private void placeNewEntry(String strategyName, int spotPrice, String signal,
                               SmartConnect sc, Strategy strategy, OrderMeta meta)
            throws Exception, SmartAPIException {

        // LTP is fetched internally by createToken() -> getNameAndTradingSymbol()
        // No duplicate API call needed here
        Token token = createToken(strategy, signal);

        if (token.getToken() == null || token.getToken().isEmpty()) {
            throw new Exception("Token resolution failed for strategy: " + strategyName);
        }

        prepareSellOrder(token, strategyName, signal, meta);

        placeFinalOrder(sc, token, strategy,
                signal.equalsIgnoreCase("BUY") ? StrategyService.MIN : StrategyService.MAX,
                meta);
    }

    // =========================================================================
    // CREATE TOKEN
    // =========================================================================
    public Token createToken(Strategy strategy, String signal) {
        Token token = new Token();
        try {
            StrategyDTO dto = taskService.getStrategyDetails(
                    strategy.getName(), strategy.getExchange());
            dto = getNameAndTradingSymbol(dto, signal);

            token.setSymbol(dto.getTradingsymbol());
            token.setToken(dto.getToken());
            token.setExch_seg(dto.getExchange());
            token.setQuantity(dto.getLotSize());
        } catch (AddressException | MessagingException | IOException e) {
            logger.error("❌ Error during Token creation: {}", e.getMessage());
        }
        return token;
    }

    public StrategyDTO getNameAndTradingSymbol(StrategyDTO strategy, String type)
            throws AddressException, MessagingException, IOException {

        if (strategy == null || strategy.getName() == null || strategy.getExchange() == null) {
            logger.warn("Invalid strategy data provided");
            return strategy;
        }

        SmartConnect sc           = angelOne.signIn();
        BigDecimal   currentPrice = angelOneService.getcurrentPrice(sc,
                strategy.getExchange(), strategy.getTradingsymbol(), strategy.getToken());

        if (currentPrice == null) {
            logger.warn("Unable to fetch current price for {}", strategy.getName());
            return strategy;
        }

        String key = strategy.getName().trim().toUpperCase();
        int strikeInterval;

        switch (key) {
            case "NIFTY":
            case "CPR_STRATEGY":
            case "CRUDEOIL":
                strikeInterval = 50;
                break;
            case "SILVERM":
                strikeInterval = 1000;
                return strategy;
            default:
                logger.warn("Unknown symbol: {}", strategy.getName());
                return strategy;
        }

        int    nearestStrike = chartService.findNearestMultiple(currentPrice.intValue(), strikeInterval);
        String optionType    = "BUY".equalsIgnoreCase(type) ? "PE" : "CE";
        String tradingSymbol = String.format("%s%s%d%s",
                strategy.getName(), strategy.getExpiry(), nearestStrike, optionType);

        logger.info("Trading Symbol: {} | LTP={} | Type={} | Strike={}",
                tradingSymbol, currentPrice, optionType, nearestStrike);

        strategy.setTradingsymbol(tradingSymbol);

        Indexes indexes = indexesRepo.findByNameAndSymbol(strategy.getName(), tradingSymbol);
        if (indexes != null) {
            strategy.setToken(indexes.getToken());
            strategy.setExchange(indexes.getExchange());
            strategy.setLotSize(indexes.getLotsize());
        } else {
            logger.error("❌ Index not found for symbol {}", tradingSymbol);
        }

        return strategy;
    }

    // =========================================================================
    // EXIT ACTIVE TRADE  (public — called from StrategyService)
    // =========================================================================
    public void exitActiveTrade(String strategyName)
            throws IOException, SmartAPIException {

        Strategy strategy    = strategyRepo.findByName(strategyName);
        Orders   activeTrade = ordersRepo.findByNameAndActive(strategyName, 1);

        if (activeTrade == null) {
            logger.info("ℹ️ No active trade for strategy → {}", strategyName);
            return;
        }

        logger.info("🚪 Exiting active trade → {}", activeTrade.getSymbol());

        if ("Y".equalsIgnoreCase(strategy.getLive())) {
            placeExitOrder(activeTrade);
        }

        activeTrade.setActive(0);
        ordersRepo.save(activeTrade);
        logger.info("✅ Trade closed → {}", activeTrade.getSymbol());
    }

    // =========================================================================
    // EXIT ORDER (private)
    // =========================================================================
    private void placeExitOrder(Orders activeTrade)
            throws IOException, SmartAPIException {

        logger.info("Preparing EXIT → {}", activeTrade.getSymbol());

        SmartConnect sc    = angelOne.signIn();
        Token        token = new Token();
        token.setToken(activeTrade.getToken());
        token.setSymbol(activeTrade.getSymbol());
        token.setExch_seg(activeTrade.getExchange());
        token.setQuantity(activeTrade.getQuantity());

        prepareBuyOrder(token, activeTrade.getName());
        angelOneService.placeOrder(sc, token);

        logger.info("✅ EXIT BUY ORDER SENT → {}", activeTrade.getSymbol());
    }

    // =========================================================================
    // ORDER PREPARE HELPERS
    // =========================================================================
    private void prepareBuyOrder(Token token, String strategyName) {
        token.setProductType(Constants.PRODUCT_CARRYFORWARD);
        token.setVariety(Constants.VARIETY_NORMAL);
        token.setOrderType(Constants.ORDER_TYPE_MARKET);
        token.setTransactionType(Constants.TRANSACTION_TYPE_BUY);
        token.setName(strategyName);
        token.setSignal("EXIT");
        logger.info("EXIT Order Prepared → {}", token.getSymbol());
    }

    private void prepareSellOrder(Token token, String strategyName,
                                  String signal, OrderMeta meta) {
        token.setProductType(Constants.PRODUCT_CARRYFORWARD);
        token.setVariety(Constants.VARIETY_NORMAL);
        token.setOrderType(Constants.ORDER_TYPE_MARKET);
        token.setTransactionType(Constants.TRANSACTION_TYPE_SELL);
        token.setName(strategyName);
        token.setType(signal);
        token.setSignal("ENTRY");

        // Enrich token with meta if available
        if (meta != null) {
            token.setEntryPrice(meta.getEntryPrice());
            token.setSlPrice(meta.getSlPrice());
            token.setFirst5High(meta.getFirst5High());
            token.setFirst5Low(meta.getFirst5Low());
            token.setUpperBand(meta.getUpperBand());
            token.setLowerBand(meta.getLowerBand());
            token.setPivot(meta.getPivot());
            token.setMarketType(meta.getMarketType());
            token.setEntryTime(meta.getEntryTime());
            logger.info("📋 Order Meta attached → {}", meta);
        }

        logger.info("ENTRY SELL Prepared → {}", token.getSymbol());
    }

    // =========================================================================
    // EXECUTION (LIVE / PAPER)
    // =========================================================================
    private void placeFinalOrder(SmartConnect sc, Token token,
                                 Strategy strategy, int paperType,
                                 OrderMeta meta)
            throws SmartAPIException, Exception {

        if ("Y".equalsIgnoreCase(strategy.getLive())) {
            angelOneService.placeOrder(sc, token);
            logger.info("LIVE ENTRY ORDER -> {}", token.getSymbol());
        }

        // Always save to DB — required for SL monitoring and exit tracking
        // (findByTokenAndActive / findByNameAndActive depend on this row)
        angelOneService.insertOrder(token, paperType);
        logger.info("Order saved to DB -> symbol={} token={} active=1",
                token.getSymbol(), token.getToken());
    }
}