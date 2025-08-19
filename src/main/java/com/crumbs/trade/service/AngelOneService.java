package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

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
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.IndicatorRepo;
import com.crumbs.trade.repo.OIRepo;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.PriceHeikinashiMcxRepo;
import com.crumbs.trade.repo.PriceHeikinashiNiftyRepo;
import com.crumbs.trade.repo.PricesIndexRepo;
import com.crumbs.trade.repo.PricesMcxRepo;
import com.crumbs.trade.repo.PricesNiftyRepo;
import com.crumbs.trade.repo.PsarMcxRepo;
import com.crumbs.trade.repo.PsarNiftyRepo;
import com.crumbs.trade.repo.ResultMcxRepo;
import com.crumbs.trade.repo.ResultNiftyRepo;
import com.crumbs.trade.repo.ResultVixRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.repo.VixRepo;
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
	
	/*
	 * Get current price
	 */
	public BigDecimal getcurrentPrice(SmartConnect smartConnect, String exchange, String tradingSymbol,
			String symboltoken) {
		try {
			Thread.sleep(500);
			JSONObject jsonObject = smartConnect.getLTP(exchange, tradingSymbol, symboltoken);
			if (jsonObject != null) {
				return new BigDecimal(String.valueOf(jsonObject.get("ltp")));
			} else {
				return new BigDecimal(0);

			}
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			logger.error("Error while read current price : ", tradingSymbol);
			
		}
		return new BigDecimal(0);
	}
	
	@Transactional
	public void deleteOrders() {
		// TODO Auto-generated method stub
		
		pricesNiftyRepo.deleteAll();
		//indicatorRepo.deleteAll();
		pricesMcxRepo.deleteAll();
		pricesIndexRepo.deleteAll();
		priceHeikinashiNiftyRepo.deleteAll();
		priceHeikinashiMcxRepo.deleteAll();;
		psarMcxRepo.deleteAll();
		psarNiftyRepo.deleteAll();
		resultMcxRepo.deleteAll();
		resultNiftyRepo.deleteAll();
		oiRepo.deleteAll();
		resultVixRepo.deleteAll();
		vixRepo.deleteAll();
	}
	/*
	 * Get current price
	 */
	public BigDecimal getcurrentPrice(SmartConnect smartConnect, String exchange, String tradingSymbol,
			String symboltoken,String keyword) {
		JSONObject jsonObject = smartConnect.getLTP(exchange, tradingSymbol, symboltoken);
        //currentPrice = new BigDecimal(String.valueOf(jsonObject.get(keyword)));
        //System.out.println(currentPrice);
		return new BigDecimal(String.valueOf(jsonObject.get(keyword)));
	}
	
	public void createStrategy_modified(SmartConnect smartConnect, String strategyName, int spotPrice, String type,String signal)
			throws SmartAPIException, Exception {
		try
		{
			// TODO Auto-generated method stub
			// Get Current Price
			smartConnect = angelOne.signIn();
			logger.info("Order placing...");
			Strategy strategy = new Strategy();
			strategy = strategyRepo.findByName("STRANGLE");
			String exchange = strategy.getExchange();
			String tradingSymbol = strategy.getTradingsymbol();
			String symboltoken = strategy.getToken();
			String expiry = strategy.getExpiry();
			BigDecimal currentPrice = getcurrentPrice(smartConnect, exchange, tradingSymbol, symboltoken);

			if (smartConnect != null) {
				String url = null;
				boolean result = false;
				HashMap<Token, Integer> priceMapCE = new HashMap<>();
				HashMap<Token, Integer> priceMapPE = new HashMap<>();
				Token tokenCE = createSymbol(currentPrice, "CE", smartConnect, expiry,spotPrice,strategy,"SELLER");
				Token tokenPE = createSymbol(currentPrice, "PE", smartConnect, expiry,spotPrice,strategy,"SELLER");

				if (type.equalsIgnoreCase("SELL")) {
					// Order for CE
					tokenCE.setProductType(Constants.PRODUCT_CARRYFORWARD);
					tokenCE.setVariety(Constants.VARIETY_NORMAL);
					tokenCE.setOrderType(Constants.ORDER_TYPE_MARKET);
					tokenCE.setTransactionType(Constants.TRANSACTION_TYPE_SELL);
					tokenCE.setName(strategyName);
					tokenCE.setType(type);
					tokenCE.setTriggerPrice(new Double(0));
					tokenCE.setSignal(signal);
					tokenCE.setExch_seg("NFO");
					//Live
					if (strategy.getLive().equalsIgnoreCase("Y")) {
						placeOrder(smartConnect, tokenCE,strategy);
						//insertOrder(tokenCE, StrategyService.MAX);
						if (tokenCE.getPrice() != 0) {
							tokenCE.setTriggerPrice((((double) 45) / 100) * tokenCE.getPrice() + tokenCE.getPrice());
							// SL LEG
							tokenCE.setVariety(Constants.VARIETY_STOPLOSS);
							tokenCE.setOrderType(Constants.ORDER_TYPE_STOPLOSS_LIMIT);
							tokenCE.setTransactionType(Constants.TRANSACTION_TYPE_BUY);
							insertOrder(tokenCE, StrategyService.MAX);
							placeOrder(smartConnect, tokenCE, strategy);
							// Finvasia
							//createStrategy(smartConnect, "NIFTY", 0, "CE");

						} else {
							logger.error("Unable to place order : " + type);
						}
					}
					else
					{
						 if(strategy.getPapertrade().equalsIgnoreCase("Y")) 
							{
								//Paper Trade
								insertOrder(tokenCE, StrategyService.MAX);
							}
					}
				   
				

				} else if (type.equalsIgnoreCase("BUY")) {
					// Order for PE
					tokenPE.setProductType(Constants.PRODUCT_CARRYFORWARD);
					tokenPE.setTransactionType(Constants.TRANSACTION_TYPE_SELL);
					tokenPE.setVariety(Constants.VARIETY_NORMAL);
					tokenPE.setOrderType(Constants.ORDER_TYPE_MARKET);
					tokenPE.setName(strategyName);
					tokenPE.setType(type);
					tokenPE.setTriggerPrice(new Double(0));
					tokenPE.setSignal(signal);
					tokenPE.setExch_seg("NFO");
					//Live
					if (strategy.getLive().equalsIgnoreCase("Y")) {
						placeOrder(smartConnect, tokenPE, strategy);
						//insertOrder(tokenPE, StrategyService.MIN);
						if (tokenPE.getPrice() != 0) {
							tokenPE.setTriggerPrice((((double) 45) / 100) * tokenPE.getPrice() + tokenPE.getPrice());
							// SL-LEG
							tokenPE.setVariety(Constants.VARIETY_STOPLOSS);
							tokenPE.setOrderType(Constants.ORDER_TYPE_STOPLOSS_LIMIT);
							tokenPE.setTransactionType(Constants.TRANSACTION_TYPE_BUY);
							insertOrder(tokenPE, StrategyService.MIN);
							placeOrder(smartConnect, tokenPE, strategy);
							// Finvasia
							//createStrategy(smartConnect, "NIFTY", 0, "PE");

						} else {
							logger.error("Unable to place order : " + type);
						}
					} 
					else
					{
						if (strategy.getPapertrade().equalsIgnoreCase("Y")) {
							//Paper Trade
							insertOrder(tokenPE, StrategyService.MIN);
						}
					}
					
				

				}

			}
			
		}
		catch(Exception ex)
		{
			//sendEmail.sendmail(strategyName + "Error",ex.getMessage(),0);
		}

	}
	
	@Transactional
	public Orders insertOrder(Token token, int breakEven) throws Exception {
		Orders orders = new Orders();
		if(token.getOrderId()!=null)
		{
			orders.setOrderid(token.getOrderId());
		}
		else
		{
			orders.setOrderid("1");
			token.setPrice(new Double("0"));
		}
		
		orders.setCreatedOn(new Date().toString());
		orders.setAskPrice(token.getPrice().intValue());
		orders.setSl(token.getTriggerPrice().intValue());
		orders.setSymbol(token.getSymbol());
		orders.setToken(token.getToken());
		orders.setName(token.getName());
		//orders.setType(token.getSymbol().substring( token.getSymbol().length()-2, token.getSymbol().length()));
		orders.setType(token.getType());
		orders.setActive(1);
		orders.setBreakeven(breakEven);
		
		orderRepository.save(orders);
		return orders;

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
				token.setStrike(indexes.getStrike());
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
	public Token placeOrder(SmartConnect smartConnect, Token token,Strategy strategy) throws SmartAPIException, IOException {
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
			logger.error("Error while creating order");
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
}
