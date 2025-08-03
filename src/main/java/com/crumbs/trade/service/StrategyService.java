package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.CPR;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.Stoploss;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.PriceRepo;
import com.crumbs.trade.repo.StrategyRepo;

import jakarta.transaction.Transactional;

@Service
public class StrategyService {

	Logger logger = LoggerFactory.getLogger(StrategyService.class);
	@Autowired
	RestTemplate restTemplate;

	@Autowired
	AngelOne angelOne;

	@Autowired
	AngelOneService angelOneService;

	@Autowired
	StrategyRepo strategyRepo;

	@Autowired
	OrderRepository orderRepository;

	@Autowired
	PriceRepo priceRepo;
	
	@Autowired
	TaskService taskService;
	
	public static int MAX;
	public static int MIN;
	
	
	public static boolean timeCheck = false;
	public static boolean firstOrder = false;
	public static boolean secondOrder = false;
	
	//@Scheduled(fixedDelay=5000)
	public void shortStrangleModified() throws SmartAPIException, Exception {
		
		String result = null;
		int niftyPrice =0;
		Strategy strategy = new Strategy();
		strategy = strategyRepo.findByName("STRANGLE");
		String signal;
		//getNiftyPrice();
		List<Orders> orderList = orderRepository.findByNameAndActive("NIFTY", 1);
		SmartConnect smartconnect = angelOne.signIn();
		//CPR
		//CPR cpr = calculate_CPR(smartconnect,strategy);
		BigDecimal nifty_ClosePrice = angelOneService.getcurrentPrice(smartconnect,
				strategy.getExchange(), strategy.getTradingsymbol(),
				strategy.getToken(),"close");
		BigDecimal nifty_OpenPrice = angelOneService.getcurrentPrice(smartconnect,
				strategy.getExchange(), strategy.getTradingsymbol(),
				strategy.getToken(),"open");
		
		if(strategy.getActive().equalsIgnoreCase("Y"))
		{
			if (orderList.size() == 0 && !firstOrder) {
				// Based on gap up and gap down, order trigger time will be changed
				if (analysePrice(nifty_ClosePrice, nifty_OpenPrice)) {
					niftyPrice = getNiftyPrice("15","30",strategy,35);// first 4 candle
					signal = "FLAT";
				} else {
					//Gap or Down
					niftyPrice = getNiftyPrice("40","45",strategy,50); // 4 and 5 Candle
					signal = "UP or DOWN";
				}

				
				if (niftyPrice > MAX && MAX > 0 && MIN > 0 && !firstOrder) {
					firstOrder = true;
					logger.info("MAX : " + MAX + " - MIN : " + MIN);
					logger.info("First Buy Order Triggered @  " + niftyPrice);
					// Angelone
					angelOneService.createStrategy_modified(smartconnect, "NIFTY", 0, "BUY",signal);
				   /*	if (niftyPrice > cpr.getTop_pivot().intValue()) {
						angelOneService.createStrategy_modified(smartconnect, "NIFTY", 0, "BUY");
					} else {
						System.out.println("Current Nifty Price :" + niftyPrice + " is lesser than Top CPR : "
								+ cpr.getTop_pivot().intValue());
					}
					*/

				} else if (niftyPrice < MIN && MAX > 0 && MIN > 0 && !firstOrder) {
					firstOrder = true;
					logger.info("MAX : " + MAX + " - MIN : " + MIN);
					logger.info("First Sell Order Triggered @ " + niftyPrice);
					// AngelOne
					angelOneService.createStrategy_modified(smartconnect, "NIFTY", 0, "SELL",signal);
					/*if (niftyPrice < cpr.getBottom_pivot().intValue()) {
					
					} else {
						System.out.println("Current Nifty Price :" + niftyPrice + " is greater than Bottom CPR : "
								+ cpr.getBottom_pivot().intValue());
					}*/
				}
				
			} else if (orderList.size() == 1 && !secondOrder) {

				BigDecimal currentPrice = angelOneService.getcurrentPrice(smartconnect, strategy.getExchange(),
						strategy.getTradingsymbol(), strategy.getToken(), "ltp");
				String tradeType = readPriceFromTable("NIFTY", currentPrice);
				
				String type = orderList.get(0).getType();
				logger.info("Waiting for Signal @ " + " :  Buy/Sell = " + tradeType);
				
				if (tradeType != null && tradeType.equalsIgnoreCase("SELL") && !type.equalsIgnoreCase("SELL")) {

					secondOrder = true;
					logger.info("Second Sell Order Triggered @  " + niftyPrice);
					angelOneService.createStrategy_modified(smartconnect, "NIFTY", 0, "SELL",null);
				} else if (tradeType != null && tradeType.equalsIgnoreCase("BUY") && !type.equalsIgnoreCase("BUY")) {

					secondOrder = true;
					logger.info("Second Buy Order Triggered @  " + niftyPrice);
					angelOneService.createStrategy_modified(smartconnect, "NIFTY", 0, "BUY",null);

				}

			}
			
		}
		else
		{
			logger.info("strangle_920_modified is disabled");
		}
		
	}

	public String readPriceFromTable(String name, BigDecimal currentPriceValue) {
        String result=null;
        int max =0;
        int min =0;
        if(currentPriceValue!=null)
        {
        	
        	int currentPrice = currentPriceValue.intValue();
     		List<Stoploss> priceList = priceRepo.findTop3ByNameOrderByIdDesc(name);
     		if(priceList.size()>=3 &&  currentPrice!=0)
     		{
     			max =(int) priceList.stream().filter(price -> currentPrice >= price.getMax().intValue()).count();
     			min =(int) priceList.stream().filter(price -> currentPrice <= price.getMin().intValue()).count();
     		
     			if(max==3)
     			{
     				result = "BUY";
     			}
     			if(min==3)
     			{
     				result = "SELL";
     			}
     		}
        }
       
		return result;
	}
	
	
	
	
	/*
	public void checkTime() throws ParseException, InterruptedException {
		Date currentTime = new Date(); // Instantiate a Date object
		Date updatedTIme = new Date(); // Instantiate a Date object
		updatedTIme.setHours(9);
		updatedTIme.setMinutes(25);
		if (currentTime.compareTo(updatedTIme) == 1) {
			resetTimeFrame(3);

			timeCheck = true;
		} else {
			timeCheck = false;
		}

	}
	
	public void resetTimeFrame(int candleSize) throws InterruptedException
	{
		angelOneService.updateCandleStickById(candleSize,2);
		angelOneService.updateJobs("N", 2);
		Thread.sleep(1000);
		IntraServiceImpl.timeReset = false;
		System.out.println("Update the Job 2");
		angelOneService.updateJobs("Y", 2);
	}*/
	public boolean analysePrice(BigDecimal nifty_ClosePrice,BigDecimal nifty_OpenPrice)
	{
		if(nifty_OpenPrice.compareTo(nifty_ClosePrice)<0)
		{
			//Gap Down
			int diff = nifty_ClosePrice.intValue() - nifty_OpenPrice.intValue();
			if(diff<=70)
			{
				return true; // No big move 
			}
		}
		else if (nifty_OpenPrice.compareTo(nifty_ClosePrice)>0)
		{
			//Gap Up
			int diff = nifty_OpenPrice.intValue() - nifty_ClosePrice.intValue();
			if(diff<=70)
			{
				return true; // No big move 
			}
			
		}
		return false; // Big Up or Down
	}
	
	@Transactional
	public void updateStrategy()
	{
		logger.info("Both call has been taken");
		//Jobs jobs=jobsRepository.getById((long)3);
		//jobs.setActive("N");
		//jobsRepository.save(jobs);
	}
	
//	public void getPreviousCandleData()
//	{
//		Date currentTime = new Date(); // Instantiate a Date object
//	
//		SmartConnect smartConnect = angelOne.signIn();
//		BigDecimal nifty_CurrentPrice = intraService.getcurrentPrice(smartConnect,
//				strategy.getExchange(), strategy.getTradingsymbol(),
//				strategy.getToken(),"ltp");
//		String format = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
//		List<Integer> MAX_List= new ArrayList<>();
//		List<Integer> MIN_List= new ArrayList<>();
//		JSONObject  requestObejct = new JSONObject();
//		JSONArray jsonArray= new JSONArray();
//		JSONArray jsonArrayInner= new JSONArray();
//		requestObejct.put("exchange", strategy.getExchange());
//		requestObejct.put("symboltoken", strategy.getToken());
//	}
	//30   , 35(complete 4 5mins candle)
	public int getNiftyPrice(String startTime, String endTime, Strategy strategy,int triggerValue) {
		BigDecimal nifty_CurrentPrice = new BigDecimal(0);
		try
		{
			Date currentTime = new Date(); // Instantiate a Date object
			Date updatedTIme = new Date(); // Instantiate a Date object
			SmartConnect smartConnect = AngelOne.signIn();
			nifty_CurrentPrice = angelOneService.getcurrentPrice(smartConnect,
					strategy.getExchange(), strategy.getTradingsymbol(),
					strategy.getToken(),"ltp");
			String format = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
			List<Integer> MAX_List= new ArrayList<>();
			List<Integer> MIN_List= new ArrayList<>();
			JSONObject  requestObejct = new JSONObject();
			//JSONArray jsonArray= new JSONArray();
			JSONArray jsonArrayInner= new JSONArray();
			requestObejct.put("exchange", strategy.getExchange());
			requestObejct.put("symboltoken", strategy.getToken());
			
				updatedTIme.setHours(9);
				updatedTIme.setMinutes(triggerValue);
				requestObejct.put("interval", "FIVE_MINUTE");
				requestObejct.put("fromdate", format+" 09:"+startTime); 
				requestObejct.put("todate", format+" 09:"+endTime);
				JSONObject json = new JSONObject(smartConnect.candleData(requestObejct)); 
				JSONArray jsonArray = smartConnect.candleData(requestObejct);
				if(currentTime.compareTo(updatedTIme)==1)
				{
					if(!jsonArray.isEmpty() && MAX==0) 
					{
						
						//jsonArray = (JSONArray) json.get("data");
						for(int i=0;i<jsonArray.length();i++)
						{
							jsonArrayInner= (JSONArray) jsonArray.get(i);
							MAX_List.add(jsonArrayInner.getInt(2));
							jsonArrayInner= (JSONArray) jsonArray.get(i);
							MIN_List.add(jsonArrayInner.getInt(3));
						}
						 Collections.sort(MAX_List, Collections.reverseOrder()); 
						 MAX =MAX_List.get(0);
						 Collections.sort(MIN_List);
						 MIN=MIN_List.get(0);
		               
					}
				}
				
			
		}
		catch(Exception ex)
		{
			logger.error("ERROR WHILE GET NIFTY FUTURE PRICE " + ex.getMessage());
			//sendEmail.sendmail("ERROR WHILE GET NIFTY FUTURE PRICE", ex.getMessage());
		}
		
		return nifty_CurrentPrice.intValue();
	}

	public String callNifty(String strategyName, String type) {
		HttpHeaders headers = new HttpHeaders();
		headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
		HttpEntity<String> entity = new HttpEntity<String>(headers);
		ResponseEntity<String> response = restTemplate.exchange(
				"http://localhost:8080/nifty/" + strategyName + "/" + type, HttpMethod.GET, entity, String.class);
		//logger.info("Nifty :" + response.getBody());
		return response.getBody();
	}
	
	public CPR calculate_CPR(SmartConnect smartConnect, Strategy strategy) throws IOException, SmartAPIException
	{
		CPR cpr =new CPR();
		String[] timing = strategy.getDayCandle().split(",");
		Instant now = Instant.now(); //current date
		Instant twoDateMinus= now.minus(Duration.ofDays(Integer.parseInt(timing[0])));
		Instant oneDateMinus= now.minus(Duration.ofDays(Integer.parseInt(timing[1])));
		Date from = Date.from(twoDateMinus);
		Date to = Date.from(oneDateMinus);
		String fromDate = new SimpleDateFormat("yyyy-MM-dd").format(from);
		String toDate = new SimpleDateFormat("yyyy-MM-dd").format(to);
		
		JSONArray responseArray = new JSONArray();
		JSONObject requestObejct = new JSONObject();
		requestObejct.put("exchange", strategy.getExchange());
		requestObejct.put("symboltoken", strategy.getToken());
		requestObejct.put("interval", "ONE_DAY");
		requestObejct.put("fromdate", fromDate.concat(" 09:15"));
		requestObejct.put("todate", toDate.concat(" 09:15"));
		
		responseArray = smartConnect.candleData(requestObejct);
        if(!responseArray.isEmpty())
        {
			
			JSONArray ohlcArray = (JSONArray) responseArray.get(0);
			BigDecimal open = new BigDecimal(String.valueOf(ohlcArray.getDouble(1)));
			BigDecimal high = new BigDecimal(String.valueOf(ohlcArray.getDouble(2)));
			BigDecimal low = new BigDecimal(String.valueOf(ohlcArray.getDouble(3)));
			BigDecimal close = new BigDecimal(String.valueOf(ohlcArray.getDouble(4)));
			BigDecimal pivot =(high.add(low).add(close)).divide(new BigDecimal(3),2, BigDecimal.ROUND_HALF_UP);
			BigDecimal bottom_pivot =(high.add(low)).divide(new BigDecimal(2),2,BigDecimal.ROUND_HALF_UP);
			BigDecimal top_pivot =(pivot.subtract(bottom_pivot)).add(pivot);
			cpr.setPivot(pivot);
			cpr.setBottom_pivot(bottom_pivot);
			cpr.setTop_pivot(top_pivot);
			
        }
		return cpr;
      
	}
}
