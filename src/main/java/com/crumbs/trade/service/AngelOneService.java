package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import com.crumbs.trade.repo.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.models.Order;
import com.angelbroking.smartapi.models.OrderParams;
import com.angelbroking.smartapi.utils.Constants;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.OptionsGreeks;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.Strategy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.transaction.Transactional;


@Service
public class AngelOneService {

	static Logger logger = LoggerFactory.getLogger(AngelOneService.class);

	
	@Autowired
	PricesNiftyRepo pricesNiftyRepo;
	
	@Autowired
	PricesMcxRepo pricesMcxRepo;
	
	@Autowired
	PricesIndexRepo pricesIndexRepo;
	
	@Autowired
	PriceHeikinashiNiftyRepo priceHeikinashiNiftyRepo;
	
	@Autowired
	PriceHeikinashiMcxRepo priceHeikinashiMcxRepo;
	
	@Autowired
	PsarNiftyRepo psarNiftyRepo;
	
	@Autowired
	PsarMcxRepo psarMcxRepo;
	
	@Autowired
	IndicatorRepo indicatorRepo;
	
	@Autowired
	ResultMcxRepo resultMcxRepo;
	
	@Autowired
	ResultNiftyRepo resultNiftyRepo;
	
	@Autowired
	ResultVixRepo resultVixRepo;
	
	@Autowired
	OIRepo oiRepo;
	
	@Autowired
	VixRepo vixRepo;
	
	@Autowired
	AngelOne angelOne;
	
	@Autowired
    StrategyRepo strategyRepo;
	
	@Autowired
	TaskService taskService;
	
	@Autowired
	OrderRepository orderRepository;
	
	@Autowired
	PriceRepo priceRepo;
	
	@Autowired
	SignalsRepo signalsRepo;
	
	@Autowired
	CPRRepo cprRepo;
	
	@Autowired
	StraddleIntradayRepo straddleIntradayRepo;
	
	@Autowired
	TradeExecutionRepo tradeExecutionRepo;
	
	@Autowired
	PredictionHistoryRepo predictionHistoryRepo;
	
	@Autowired
	PreMarketAnalysisRepo preMarketAnalysisRepo;
	
	@Autowired
	AlertRepo alertRepo;
	
	@Autowired OIResultRepo oiResultRepo;
	@Autowired IntradayTradeRepo intradayTradeRepo;
	@Autowired LevelRepository levelRepository;
	@Autowired OptionsGreeksRepo optionsGreeksRepo;
	@Autowired
    RiskConfigurationRepository riskConfigurationRepository;
    @Autowired OptionPriceRepo optionPriceRepo;
	/*
	 * Get current price
	 */
	private static final int MAX_RETRIES = 3;
	private static final long INITIAL_DELAY_MS = 300;

	private final Map<String, BigDecimal> lastKnownLtp = new ConcurrentHashMap<>();

	public BigDecimal getcurrentPrice(
	        SmartConnect smartConnect,
	        String exchange,
	        String tradingSymbol,
	        String symboltoken) {

	    int attempt = 0;
	    long delay = INITIAL_DELAY_MS;

	    while (attempt < MAX_RETRIES) {
	        try {

	            JSONObject jsonObject =
	                    smartConnect.getLTP(exchange, tradingSymbol, symboltoken);

	            if (jsonObject == null) {
	                logger.warn("Null response from Angel for {}", tradingSymbol);
	            } else if (!jsonObject.has("ltp")) {
	                logger.warn("LTP missing in response for {}: {}",
	                        tradingSymbol, jsonObject.toString());
	            } else {

	                BigDecimal ltp =
	                        new BigDecimal(jsonObject.get("ltp").toString());

	                if (ltp.compareTo(BigDecimal.ZERO) > 0) {

	                    // ✅ store last known
	                    lastKnownLtp.put(tradingSymbol, ltp);

	                    return ltp;
	                }
	            }

	        } catch (Exception e) {
	            logger.error("Attempt {} failed for {}: {}",
	                    attempt + 1, tradingSymbol, e.getMessage());
	        }

	        // 🔁 Exponential Backoff
	        try {
	            Thread.sleep(delay);
	        } catch (InterruptedException ie) {
	            Thread.currentThread().interrupt();
	            break;
	        }

	        delay *= 2;
	        attempt++;
	    }

	    // ⚡ Fallback to last known
	    BigDecimal fallback = lastKnownLtp.get(tradingSymbol);

	    if (fallback != null) {
	        logger.warn("Using fallback LTP for {}", tradingSymbol);
	        return fallback;
	    }

	    logger.error("All retries failed for {}. Returning null.", tradingSymbol);
	    return null;   // ❗ DO NOT return 0
	}

	
	@Transactional
	public void deleteOrders() {
		// TODO Auto-generated method stub
		
		pricesNiftyRepo.deleteAll();
		//indicatorRepo.deleteAll();
		pricesMcxRepo.deleteAll();
		pricesIndexRepo.deleteAll();
		priceHeikinashiNiftyRepo.deleteAll();
		priceHeikinashiMcxRepo.deleteAll();
		psarMcxRepo.deleteAll();
		psarNiftyRepo.deleteAll();
		resultMcxRepo.deleteAll();
		resultNiftyRepo.deleteAll();
		oiRepo.deleteAll();
		resultVixRepo.deleteAll();
		vixRepo.deleteAll();
		orderRepository.deleteAll();
		priceRepo.deleteAll();
		signalsRepo.deleteAll();
		cprRepo.deleteAll();
		straddleIntradayRepo.deleteAll();
		tradeExecutionRepo.deleteAll();
		predictionHistoryRepo.deleteAll();
		//preMarketAnalysisRepo.deleteAll();
		alertRepo.deleteAll();
		oiResultRepo.deleteAll();
		intradayTradeRepo.deleteAll();
		levelRepository.deleteAll();
		optionsGreeksRepo.deleteAll();
        riskConfigurationRepository.resetAllTrailingData();
        optionPriceRepo.deleteAll();
		}
	/*
	 * Get current price (Dynamic Keyword) - Simple rate limit handling with exponential backoff.
	 * No caching involved.
	 */
	public BigDecimal getcurrentPrice(SmartConnect smartConnect, String exchange, String tradingSymbol, String symboltoken, String keyword) {
		int maxRetries = 3;
		long delay = 300; // start with 300ms delay

		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			try {
				JSONObject jsonObject = smartConnect.getLTP(exchange, tradingSymbol, symboltoken);
				
				if (jsonObject != null && jsonObject.has(keyword)) {
					Object valObj = jsonObject.get(keyword);
					if (valObj != null) {
						return new BigDecimal(valObj.toString());
					}
				}
			} catch (Exception e) {
				logger.warn("Rate limit or error fetching '{}' for {} (Attempt {}/{}): {}", 
						keyword, tradingSymbol, attempt, maxRetries, e.getMessage());
			}

			// Backoff before retrying
			try {
				Thread.sleep(delay);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				break;
			}
			delay *= 2; // Double the delay for the next attempt
		}

		logger.error("Failed to fetch '{}' for {} after {} attempts. Returning 0 to prevent crash.", keyword, tradingSymbol, maxRetries);
		return BigDecimal.ZERO;
	}
	
	// 🟢 CHANGE: Added 'int breakEven' parameter to the method signature
    public void createStrategy_modified(SmartConnect smartConnect, String strategyName, int spotPrice, String type, String signal, int breakEven)
            throws SmartAPIException, Exception {
        try {
            smartConnect = angelOne.signIn();
            logger.info("Order placing...");
            Strategy strategy = strategyRepo.findByName("STRANGLE");
            String exchange = strategy.getExchange();
            String tradingSymbol = strategy.getTradingsymbol();
            String symboltoken = strategy.getToken();
            String expiry = strategy.getExpiry();
            BigDecimal currentPrice = getcurrentPrice(smartConnect, exchange, tradingSymbol, symboltoken);

            if (smartConnect != null) {
                Token tokenCE = createSymbol(currentPrice, "CE", smartConnect, expiry, spotPrice, strategy, "SELLER");
                Token tokenPE = createSymbol(currentPrice, "PE", smartConnect, expiry, spotPrice, strategy, "SELLER");

                if (type.equalsIgnoreCase("SELL")) {
                    tokenCE.setProductType(Constants.PRODUCT_CARRYFORWARD);
                    tokenCE.setVariety(Constants.VARIETY_NORMAL);
                    tokenCE.setOrderType(Constants.ORDER_TYPE_MARKET);
                    tokenCE.setTransactionType(Constants.TRANSACTION_TYPE_SELL);
                    tokenCE.setName("STRANGLE");
                    tokenCE.setType(type);
                    tokenCE.setTriggerPrice(new Double(0));
                    tokenCE.setSignal(signal);
                    tokenCE.setExch_seg("NFO");

                    if (strategy.getLive().equalsIgnoreCase("Y")) {
                        placeOrder(smartConnect, tokenCE);
                        if (tokenCE.getPrice() != 0) {
                            tokenCE.setTriggerPrice((((double) 45) / 100) * tokenCE.getPrice() + tokenCE.getPrice());
                            tokenCE.setVariety(Constants.VARIETY_STOPLOSS);
                            tokenCE.setOrderType(Constants.ORDER_TYPE_STOPLOSS_LIMIT);
                            tokenCE.setTransactionType(Constants.TRANSACTION_TYPE_BUY);
                            
                            // 🟢 FIX: Use breakEven parameter instead of StrategyService.MAX
                            insertOrder(tokenCE, breakEven); 
                            placeOrder(smartConnect, tokenCE);
                        } else {
                            logger.error("Unable to place order : " + type);
                        }
                    } else {
                         if(strategy.getPapertrade().equalsIgnoreCase("Y")) {
                             // 🟢 FIX: Use breakEven parameter instead of StrategyService.MAX
                             insertOrder(tokenCE, breakEven); 
                         }
                    }

                } else if (type.equalsIgnoreCase("BUY")) {
                    tokenPE.setProductType(Constants.PRODUCT_CARRYFORWARD);
                    tokenPE.setTransactionType(Constants.TRANSACTION_TYPE_SELL);
                    tokenPE.setVariety(Constants.VARIETY_NORMAL);
                    tokenPE.setOrderType(Constants.ORDER_TYPE_MARKET);
                    tokenPE.setName("STRANGLE");
                    tokenPE.setType(type);
                    tokenPE.setTriggerPrice(new Double(0));
                    tokenPE.setSignal(signal);
                    tokenPE.setExch_seg("NFO");

                    if (strategy.getLive().equalsIgnoreCase("Y")) {
                        placeOrder(smartConnect, tokenPE);
                        if (tokenPE.getPrice() != 0) {
                            tokenPE.setTriggerPrice((((double) 45) / 100) * tokenPE.getPrice() + tokenPE.getPrice());
                            tokenPE.setVariety(Constants.VARIETY_STOPLOSS);
                            tokenPE.setOrderType(Constants.ORDER_TYPE_STOPLOSS_LIMIT);
                            tokenPE.setTransactionType(Constants.TRANSACTION_TYPE_BUY);
                            
                            // 🟢 FIX: Use breakEven parameter instead of StrategyService.MIN
                            insertOrder(tokenPE, breakEven); 
                            placeOrder(smartConnect, tokenPE);
                        } else {
                            logger.error("Unable to place order : " + type);
                        }
                    } else {
                        if (strategy.getPapertrade().equalsIgnoreCase("Y")) {
                            // 🟢 FIX: Use breakEven parameter instead of StrategyService.MIN
                            insertOrder(tokenPE, breakEven); 
                        }
                    }
                }
            }
        } catch(Exception ex) {
            logger.error("Error in createStrategy_modified", ex);
        }
    }
	
	@Transactional
	public Orders insertOrder(Token token, int breakEven) throws Exception {

		Orders orders = new Orders();

		// ORDER ID
		orders.setOrderid(token.getOrderId() != null ? token.getOrderId() : "1");

		// CREATED DATE
		orders.setCreatedOn(LocalDateTime.now());

		// 🟢 FIX: SAFELY MAP ASK PRICE AND ENTRY PRICE
		// Prioritize getAskPrice/getEntryPrice (which we set in OrderService)
		// Fallback to getPrice() just in case older parts of your system still use it
		BigDecimal actualPremium = token.getAskPrice() != null ? token.getAskPrice() : 
                                   (token.getEntryPrice() != null ? token.getEntryPrice() : 
                                   (token.getPrice() != null ? BigDecimal.valueOf(token.getPrice()) : BigDecimal.ZERO));

		orders.setAskPrice(actualPremium);
		

		// SL
		Double sl = token.getTriggerPrice();
		orders.setSl(sl != null ? BigDecimal.valueOf(sl) : BigDecimal.ZERO);

		// BASIC DETAILS
		orders.setSymbol(safe(token.getSymbol()));
		orders.setToken(safe(token.getToken()));
		orders.setName(safe(token.getName()));

		if ("CPR_STRATEGY".equalsIgnoreCase(token.getType())) {
			orders.setType("SELL");
		} else {
			orders.setType(safe(token.getType()));
		}
		orders.setExchange(safe(token.getExch_seg()));

		// ACTIVE TRADE
		orders.setActive(1);

		// BREAKEVEN
		orders.setBreakeven(BigDecimal.valueOf(breakEven));

		// QUANTITY
		try {
			orders.setQuantity(token.getQuantity());
		} catch (Exception e) {
			orders.setQuantity(0);
		}

		return orderRepository.save(orders);
	}

	// small helper
	private String safe(String value) {
	    return value == null ? "" : value;
	}


	public Token createSymbol(BigDecimal currentPrice, String type, SmartConnect smartConnect, String expiry,int spotPrice, Strategy strategy,String buyer) throws JsonProcessingException, IOException, AddressException, MessagingException
	{
		//Take Complete Option Chain Price
		if (type.equalsIgnoreCase("CE")) {
			currentPrice = currentPrice.add(new BigDecimal("500"));
		} else if (type.equalsIgnoreCase("PE")) {
			currentPrice = currentPrice.subtract(new BigDecimal("500"));
		}
		int slot = 0;
	
		HashMap<Token, Integer> priceMap = new HashMap<>();
		int price =0;
		if (strategy.getName().contains("SENSEX")) {
			slot=100;
			price = currentPrice.intValue() % slot;
		} else {
			slot =50;
			price = currentPrice.intValue() % slot;
		}
	
		int roundPrice = currentPrice.intValue() - price;
		
		for(int i=0;i<20;i++)
		{
			Token token=new Token();
			//NIFTY21NOV2424250CE
			String symbol=strategy.getSymbol() + expiry + roundPrice +type;
			token.setSymbol(symbol);
			token.setExch_seg(strategy.getExchange());
	  		getTokenBasedOnStrikePrice(token);
			String exchange=strategy.getExchange();
			String tradingSymbol=token.getSymbol();
			String symboltoken =token.getToken();
			BigDecimal strikePrice = getcurrentPrice(smartConnect, exchange, tradingSymbol, symboltoken);
			
			priceMap.put(token, strikePrice.intValue());
			if (type.equalsIgnoreCase("CE")) {
				roundPrice = roundPrice - slot;
			} else if (type.equalsIgnoreCase("PE")) {
				roundPrice = roundPrice + slot;
			}
			
		}
		return findNearestPrice(priceMap,spotPrice);
		
		
	}
	private Token findNearestPrice(HashMap<Token, Integer> priceMap,int spotPrice) {
		// TODO Auto-generated method stub
		Map<Double, Token> map = new HashMap<>();
		
		for (Entry<Token, Integer> entry : priceMap.entrySet()) 
		{
			Token token = entry.getKey();
			map.put(entry.getValue().doubleValue(),entry.getKey());
		}
		if(spotPrice==0)
		{
			return nearestKeys(map, new Long(155));
		}
		else
		{
			return nearestKeys(map, new Long(spotPrice));
		}
		
	}
	
	public static Token nearestKeys(Map<Double, Token> map, Long target) {
		double minDiff = Double.MAX_VALUE;
		Double nearest = null;
		for (Double key : map.keySet()) {
			double diff = Math.abs((double) target - (double) key);
			if (diff < minDiff) {
				nearest = key;
				minDiff = diff;
			}
		}
		return map.get(nearest);
		//return nearest;
	}
	
	@SuppressWarnings("unused")
	public Token getTokenBasedOnStrikePrice(Token token) throws JsonProcessingException, IOException {
		// create ObjectMapper instance

		try {
			ObjectMapper objectMapper = new ObjectMapper();
			Indexes indexes = taskService.getIndexChart("NIFTY", token.getSymbol());

			if (indexes != null) {
				token.setToken(indexes.getToken());
				token.setName(indexes.getName());
				token.setExpiry(indexes.getExpiry());
				token.setStrike(new BigDecimal(indexes.getStrike()));
				token.setQuantity(indexes.getLotsize());
			} else {
				logger.error("Unable to get token " + token.getName());
			}
			/*
			 * JsonNode rootNode = objectMapper.readTree(new
			 * File(System.getProperty("user.dir") + "/tokens.txt")); rootNode.forEach(node
			 * -> { //System.out.println(node); if
			 * (node.path("symbol").asText().equalsIgnoreCase(token.getSymbol())) {
			 * 
			 * token.setToken(node.path("token").asText());
			 * token.setName(node.path("name").asText());
			 * token.setExpiry(node.path("expiry").asText());
			 * token.setStrike(node.path("strike").asText());
			 * token.setQuantity(node.path("lotsize").asInt()); }
			 * 
			 * }); logger.info("Got token " + token.getName()); if (token.getName() == null)
			 * { logger.error("Unable to get token " + token.getName()); }
			 */
		} catch (Exception ex) {
			logger.error(ex.getMessage());
		}

		return token;

	}
	public Token placeOrder(SmartConnect smartConnect, Token token) throws SmartAPIException, IOException {
		boolean result = false;
		
		try {
			SmartConnect smartconnect = angelOne.signIn();
			OrderParams orderParams = new OrderParams();
			orderParams.variety = token.getVariety();
			orderParams.quantity = token.getQuantity();
			orderParams.symboltoken = token.getToken();
			orderParams.exchange = token.getExch_seg();
			orderParams.ordertype = token.getOrderType();
			orderParams.tradingsymbol = token.getSymbol();
			orderParams.producttype =token.getProductType();
			orderParams.duration = Constants.DURATION_DAY;
			orderParams.transactiontype = token.getTransactionType();
			if("INTRADAY".equalsIgnoreCase(orderParams.producttype))
			{
				orderParams.squareoff=token.getSquareoff();
				orderParams.stoploss = token.getStoploss();
				orderParams.price = token.getPrice();	
			}
			
			if (token.getVariety().equalsIgnoreCase("STOPLOSS")) {
				DecimalFormat df = new DecimalFormat("###");
				orderParams.price = Double.sum(Double.valueOf(df.format(token.getTriggerPrice())), 0.50);
				orderParams.triggerprice = Double.valueOf(df.format(token.getTriggerPrice())).toString();
			}
			
			if (smartConnect != null ) {
				Order order = smartConnect.placeOrder(orderParams, token.getVariety());
				
				
				if (order.orderId != null) {
					//logger.info("ORDER ID " + order.orderId);
					token.setOrderId(order.orderId);
					token.setPrice(getOrderDetails(order.orderId));
					//token.setPrice(getOrderDetails("230510000818247"));
					result = true;
					//sendEmail.sendmail(token.getName(), "Order Placed",0);
				}
			}
		
			
			
		} catch (Exception e) {
			result = false;
			logger.error("Error while creating order" + e.getMessage());
		}

		return token;
	}
	
	//Get executed price for calculate SL
		public double getOrderDetails(String orderId) {
			double executedPrice = 0;
			try
			{
				Thread.sleep(2000);
				SmartConnect smartConnect = angelOne.signIn();
				JSONObject trades = smartConnect.getTrades();
				JSONArray jsonArray = trades.getJSONArray("data");
				//System.out.println(jsonArray.length());
				
				
				for (Object o : jsonArray) {
					JSONObject jsonLineItem = (JSONObject) o;

					if (jsonLineItem.getString("orderid").equalsIgnoreCase(orderId)) {
						executedPrice = Double.valueOf(jsonLineItem.getInt("fillprice"));
						//logger.info("Executed Price :" + executedPrice);
						return executedPrice;
					}
				}
			}
			catch(Exception ex)
			{
				logger.error("Error while get order details :" + ex.getMessage());
			}
			
			return executedPrice;
		}
		
		/**
		 * PURE BROKER COUPLING: Fetches raw positions directly from AngelOne API.
		 * Returns the raw JSONObject for the Risk Engine to process.
		 */
		public JSONObject getRawPositions() {
			try {
				SmartConnect smartConnectInstance = angelOne.signIn();
				if (smartConnectInstance != null) {
					return smartConnectInstance.getPosition();
				}
			} catch (Exception e) {
				logger.error("Failed to fetch raw positions from AngelOne: {}", e.getMessage());
			}
			return null;
		}
}
