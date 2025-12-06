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

    @Autowired AngelOne angelOne;
    @Autowired StrategyRepo strategyRepo;
    @Autowired AngelOneService angelOneService;
    @Autowired OrderRepository ordersRepo;
    @Autowired TaskService taskService;
    @Autowired ChartService chartService;
    @Autowired IndexesRepo indexesRepo;
    // ========================================================================
    // ENTRY point (strategy signal triggers)
    // ========================================================================
    public void orderPlace(String strategyName, int spotPrice, String signal)
            throws SmartAPIException, Exception {

        logger.info("Order Trigger → Strategy: {} | Signal: {}", strategyName, signal);

        SmartConnect smartConnect = angelOne.signIn();
        if (smartConnect == null) throw new Exception("AngelOne login failed");

        Strategy strategy = strategyRepo.findByName(strategyName);
        if (strategy == null) throw new Exception("Strategy not found: " + strategyName);

        // --------------------------------------------------------------
        // 1. Check ACTIVE trade
        // --------------------------------------------------------------
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
           
            // Mark inactive AFTER exit
            activeTrade.setActive(0);
            ordersRepo.save(activeTrade);
        }

        // --------------------------------------------------------------
        // 2. Place new SELL entry
        // --------------------------------------------------------------
        placeNewEntry(strategyName, spotPrice, signal, smartConnect, strategy);
    }



    // ========================================================================
    // CREATE NEW ENTRY (always SELL)
    // ========================================================================
    private void placeNewEntry(String strategyName, int spotPrice, String signal,
                               SmartConnect smartConnect, Strategy strategy)
            throws Exception, SmartAPIException {

        BigDecimal ltp = angelOneService.getcurrentPrice(
                smartConnect,
                strategy.getExchange(),
                strategy.getTradingsymbol(),
                strategy.getToken()
        );

        if (ltp == null) throw new Exception("LTP fetch failed");

        logger.info("LTP: {}", ltp);

        // SELL CE when SELL signal. SELL PE when BUY signal.
        Token token = createToken(strategy,signal);

        prepareSellOrder(token, strategyName, signal);

        placeFinalOrder(
                smartConnect,
                token,
                strategy,
                signal.equalsIgnoreCase("BUY") ? StrategyService.MIN : StrategyService.MAX
        );
    }

	public Token createToken(Strategy strategy, String signal) {
		// Get Name and Trading Symbol
        Token token = new Token();
		try {
			StrategyDTO strategyModified = taskService.getStrategyDetails(strategy.getName(), strategy.getExchange());
			strategyModified = getNameAndTradingSymbol(strategyModified, signal);
			token.setSymbol(strategyModified.getTradingsymbol());
			token.setToken(strategyModified.getToken());
			token.setExch_seg(strategyModified.getExchange());
		} catch (AddressException | MessagingException | IOException e) {
			// TODO Auto-generated catch block
			logger.error("Error Found during Token Creation");
		}
		return token;
	}

	public StrategyDTO getNameAndTradingSymbol(StrategyDTO strategy, String type)
			throws AddressException, MessagingException, IOException {

		if (strategy == null || strategy.getName() == null || strategy.getExchange() == null) {
			logger.warn("Invalid strategy data provided");
			return strategy;
		}

		SmartConnect smartconnect = angelOne.signIn();
		BigDecimal currentPrice = angelOneService.getcurrentPrice(smartconnect, strategy.getExchange(),
				strategy.getTradingsymbol(), strategy.getToken());

		if (currentPrice == null) {
			logger.warn("Unable to fetch current price for {}", strategy.getName());
			return strategy;
		}

		String name = strategy.getName().toUpperCase();
		int strikeInterval;

		String key = name.trim().toUpperCase();

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
			logger.warn("Unknown symbol name: {}", strategy.getName());
			return strategy;
		}

		int nearestStrike = chartService.findNearestMultiple(currentPrice.intValue(), strikeInterval);
		//BUY mean PE - Option seller perspective
		String optionType = "BUY".equalsIgnoreCase(type) ? "PE" : "CE";


		String tradingSymbol = String.format("%s%s%d%s", strategy.getName(), strategy.getExpiry(), nearestStrike,
				optionType);

		logger.info("Generated Trading Symbol: {} | CurrentPrice: {} | Type: {} | Strike: {}", tradingSymbol,
				currentPrice, optionType, nearestStrike);

		strategy.setTradingsymbol(tradingSymbol);
		Indexes indexes = indexesRepo.findByNameAndSymbol(strategy.getName(), strategy.getTradingsymbol());
		if (indexes != null) {
			strategy.setToken(indexes.getToken());
			strategy.setExchange(indexes.getExchange());
			// symbol added
		} else {
			logger.error("Unable to find the Index Value for symbol {}", tradingSymbol);
		}
		return strategy;
	}
    // ========================================================================
    // EXIT (BUY)
    // ========================================================================
    private void placeExitOrder(Orders activeTrade)
            throws IOException, SmartAPIException {

        logger.info("Preparing EXIT (BUY) → {}", activeTrade.getSymbol());

        SmartConnect smartConnect = angelOne.signIn();

        Token token = new Token();
        token.setToken(activeTrade.getToken());
        token.setSymbol(activeTrade.getSymbol());
        token.setExch_seg(activeTrade.getExchange());
        token.setQuantity(activeTrade.getQuantity());

        prepareBuyOrder(token, activeTrade.getName());

        // BUY to exit SELL entry
        angelOneService.placeOrder(smartConnect, token);

        logger.info("EXIT BUY ORDER SENT → {}", activeTrade.getSymbol());
    }



    // ========================================================================
    // ORDER PREPARE HELPERS
    // ========================================================================
    private void prepareBuyOrder(Token token, String strategyName) {

        token.setProductType(Constants.PRODUCT_CARRYFORWARD);
        token.setVariety(Constants.VARIETY_NORMAL);
        token.setOrderType(Constants.ORDER_TYPE_MARKET);

        token.setTransactionType(Constants.TRANSACTION_TYPE_BUY); // EXIT

        token.setName(strategyName);
        token.setSignal("EXIT");

        logger.info("EXIT Order Prepared → {}", token.getSymbol());
    }


    private void prepareSellOrder(Token token, String strategyName, String signal) {

        token.setProductType(Constants.PRODUCT_CARRYFORWARD);
        token.setVariety(Constants.VARIETY_NORMAL);
        token.setOrderType(Constants.ORDER_TYPE_MARKET);

        token.setTransactionType(Constants.TRANSACTION_TYPE_SELL); // ENTRY

        token.setName(strategyName);
        token.setType(signal);
        token.setSignal("ENTRY");

        // FIXED LOT for NIFTY
        token.setQuantity(75);

        logger.info("ENTRY SELL Prepared → {}", token.getSymbol());
    }



    // ========================================================================
    // EXECUTION (LIVE/PAPER)
    // ========================================================================
    private void placeFinalOrder(SmartConnect smartConnect, Token token, Strategy strategy, int paperType)
            throws SmartAPIException, Exception {

        if ("Y".equalsIgnoreCase(strategy.getLive())) {
            angelOneService.placeOrder(smartConnect, token);
            logger.info("LIVE ENTRY SELL ORDER → {}", token.getSymbol());
        }

        if ("Y".equalsIgnoreCase(strategy.getPapertrade())) {
            angelOneService.insertOrder(token, paperType);
            logger.info("PAPER ENTRY SELL ORDER → {}", token.getSymbol());
        }
    }

}
