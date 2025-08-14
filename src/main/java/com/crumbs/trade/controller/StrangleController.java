package com.crumbs.trade.controller;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.dto.APIResponse;
import com.crumbs.trade.dto.JData;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.FlatTradeService;
import com.crumbs.trade.service.StrategyService;
import com.crumbs.trade.service.TaskService;
import com.crumbs.trade.utility.Utility;

@RestController
@RequestMapping("/strangle")
public class StrangleController {

	private static final Logger logger = LogManager.getLogger(StrangleController.class);

	@Autowired
	StrategyService strategyService;

	@Autowired
	StrategyRepo strategyRepo;

	@Autowired
	TaskService taskService;
	
	@Autowired
	FlatTradeService flatTradeService;
	
	@Autowired
	Utility utility;

	/*
	 * NIFTY MODIFIED - READ 30 MINS CANDLE Read Candle based on time interval and
	 * save in DB
	 */

	// @Scheduled(cron = "0 45 9 * * ?")
	// @Scheduled(cron = "0 15 10-15 * * ?")
	// @Scheduled(cron = "0 45 10-14 * * ?")
	// @Scheduled(cron = "0 15 15 * * ?")
	public void runTask() {
		// Code to run every 30 minutes from 9:45 AM to 3:30 PM
		//logger.info("30 MINUTES Candle Reading..");

		DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
		LocalDateTime now = LocalDateTime.now();
		Strategy strategy = new Strategy();
		strategy = strategyRepo.findByName("NIFTY");
		taskService.saveChartPrice(strategy, "THIRTY_MINUTE");
	}

	// Run every 30 sec from 9.15 AM to 3:30 PM
	@Scheduled(cron = "0/30 * 9-15 * * ?", zone = "IST")
	public void modifiedShortStrangle() throws SmartAPIException, Exception {
		LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
		LocalTime start = LocalTime.of(9, 15);
		LocalTime end = LocalTime.of(15, 30);

		if (!now.isBefore(start) && !now.isAfter(end)) {
			if (strategyRepo.findByName("STRANGLE").getActive().equals("Y")) {
				strategyService.shortStrangleModified();
			}
		}
	}

	//@Scheduled(fixedRate = 10000)
	public void optionChain() throws SmartAPIException, Exception {

		String key = flatTradeService.getTokenForFlatTrade();	
		Token token= new Token();
		token.setExch_seg("MCX");
		token.setSymbol(Utility.normalizeToken("CRUDEOIL14AUG255500PE"));
		token.setTransactionType("S");
		token.setQuantity(100);
		String url = "https://piconnect.flattrade.in/PiConnectTP/PlaceOrder";
		APIResponse apiResponse=flatTradeService.callFlatTrade(setJDataForOrder(token), key, url);
		
		//flatTradeService.placeOrder();
	}
	public JData setJDataForSearch(String name,String exch)
	{
		JData jdata = new JData();
		jdata.setUid("MALIT158");
		jdata.setStext(name);
		jdata.setExch(exch);
		return jdata;
	}
	
	public JData setJDataForOrder(Token token)
	{
		JData jdata = new JData();
		jdata.setUid("MALIT158");
		jdata.setActid("MALIT158");
		jdata.setExch(token.getExch_seg());
		jdata.setTsym(token.getSymbol());
		jdata.setQty(String.valueOf(token.getQuantity()));
		//jdata.setMkt_protection("5");
		//jdata.setPrc("0");
		//jdata.setDscqty("0");
		jdata.setPrd("I"); //Intraday
		jdata.setTrantype(token.getTransactionType());
		jdata.setPrctyp("MKT");
		jdata.setRet("DAY");
		jdata.setOrdersource("API");
		return jdata;
		
	}
}
