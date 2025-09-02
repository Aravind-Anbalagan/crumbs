package com.crumbs.trade.controller;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.MessagingException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.entity.PricesIndex;
import com.crumbs.trade.entity.Vix;
import com.crumbs.trade.repo.PricesNiftyRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.repo.VixRepo;
import com.crumbs.trade.service.ChartService;
import com.crumbs.trade.service.OIDataService;
import com.crumbs.trade.service.OIService;
import com.crumbs.trade.service.TaskService;
import com.crumbs.trade.service.ChartService.CandleRange;

import jakarta.mail.internet.AddressException;

@RestController
@RequestMapping(value = "/heikinpsar")
public class HeikinPsarController {

	private static final Logger logger = LogManager.getLogger(HeikinPsarController.class);
	@Autowired
	ChartService chartService;

	@Autowired
	VixRepo vixRepo;

	@Autowired
	PricesNiftyRepo pricesNiftyRepo;

	@Autowired
	TaskService taskService;

	@Autowired
	StrategyRepo strategyRepo;
	
	@Autowired
	OIService oiService;


	// For 9:10:05 AM to 9:55:05 AM AM:
	@Scheduled(cron = "5 10-55/5 9 * * MON-FRI", zone = "Asia/Kolkata")
	public void scheduledTask1() throws SmartAPIException, AddressException, MessagingException, IOException {
		//logger.info("First");
		// commonExecution_1();
		commonExecution_2();
	}

	// For 10:00:05 AM to 2:55:05 PM
	@Scheduled(cron = "5 0/5 10-14 * * MON-FRI", zone = "Asia/Kolkata")
	public void scheduledTask2() throws SmartAPIException, AddressException, MessagingException, IOException {
		// Your task logic here
		//logger.info("Second");
		// commonExecution_1();
		commonExecution_2();
	}

	// For 3:00:05 PM to 3:15:05 PM
	@Scheduled(cron = "5 0-15/5 15 * * MON-FRI", zone = "Asia/Kolkata")
	public void scheduledTask3() throws SmartAPIException, AddressException, MessagingException, IOException {
		// Your task logic here
		//logger.info("Third");
		// commonExecution_1();
		commonExecution_2();
	}

	// 16:00 → 22:55
	// 23:00, 23:05, 23:10, 23:15
	// @Scheduled(fixedRate = 10000)
	@Scheduled(cron = "5 0/5 16-22 * * MON-FRI", zone = "Asia/Kolkata")
	@Scheduled(cron = "5 0-15/5 23 * * MON-FRI", zone = "Asia/Kolkata")
	public void scheduledTask4() throws SmartAPIException, AddressException, MessagingException, IOException {
		//logger.info("Crude");
		commonExecution_3();

	}

	// Strategy 1
	public void commonExecution_1() throws SmartAPIException {
		if (strategyRepo.findByName("NIFTY").getActive().equals("Y")) {
			pricesNiftyRepo.deleteAll();
			taskService.getVolumeData("FIVE_MINUTE", "NFO", false);
		}
	}

	// Strategy 2
	public void commonExecution_2() throws SmartAPIException, AddressException, MessagingException, IOException {

		// It reads VIX and Nifty
		String fromDate = chartService.getDate("FROM", "NSE");
		String toDate = chartService.getDate("TO", "NSE");
		vixRepo.deleteAll();// Must delete
		// VIX
		if (strategyRepo.findByName("VIX").getActive().equals("Y")) {
			chartService.readChartData("FIVE_MINUTE", "NSE", false, "VIX", fromDate, toDate);
		}

		// NIFTY
		if (strategyRepo.findByName("NIFTY").getActive().equals("Y")) {
			chartService.readChartData("FIVE_MINUTE", "NFO", false, "NIFTY", fromDate, toDate);
			chartService.monitorSignal("NIFTY", "NFO", false, 0);
		}
		//NIFTY OI
		if (strategyRepo.findByName("NIFTY_OI").getActive().equals("Y")) {
			oiService.getOptionChain("NIFTY_OI");
		}

	}

	// Strategy 3 - CRUDEOIL
	public void commonExecution_3() throws SmartAPIException, AddressException, MessagingException, IOException {

		String fromDate = chartService.getDate("FROM", "MCX");
		String toDate = chartService.getDate("TO", "MCX");
		vixRepo.deleteAll();
		
        //FUT
		if (strategyRepo.findByName("CRUDEOIL").getActive().equals("Y")) {
			chartService.readChartData("FIVE_MINUTE", "MCX", false, "CRUDEOIL", fromDate, toDate);
			chartService.monitorSignal("CRUDEOIL", "MCX", false, 0);
		}
		//OI
		if (strategyRepo.findByName("CRUDEOIL").getActive().equals("Y")) {
			oiService.getOptionChain("CRUDEOIL");
		}

	}

	/*
	 * Its a common method to entry and exist order for Vix
	 */
	@Scheduled(cron = "*/10 * * * * MON-FRI")
	public void monitorExecutedOrders() {

		if (chartService.getName().equalsIgnoreCase("NIFTY")
				&& strategyRepo.findByName("NIFTY").getActive().equals("Y")) {
			List<Vix> vixList = vixRepo.findAllByNameContainingOrderByIdDesc("NIFTY");
			Vix vix = new Vix();
			if (vixList != null && !vixList.isEmpty()) {
				// Get Last candle
				vix = vixList.get(0);
				chartService.lookForExecutedOrder("NIFTY", "NFO", vix, false);
			}

		} else if(strategyRepo.findByName("CRUDEOIL").getActive().equals("Y")){
			List<Vix> vixList = vixRepo.findAllByNameContainingOrderByIdDesc("CRUDEOIL");
			Vix vix = new Vix();
			if (vixList != null && !vixList.isEmpty()) {
				// Get Last candle
				vix = vixList.get(0);
				chartService.lookForExecutedOrder("CRUDEOIL", "MCX", vix, false);
			}

		}

	}
	
	@GetMapping("/getCandleList")
	public List<Vix> getCandleData() {
		return vixRepo.findByName("CRUDEOIL");
	}
	
	//Exit for Nifty
	@Scheduled(cron = "0 20 15 ? * MON-FRI", zone = "Asia/Kolkata")
	public void nfoExit() throws AddressException, MessagingException, IOException {
		chartService.monitorSignal("NIFTY", "NFO", false, 0);
	}

	// Exit for Crude
	@Scheduled(cron = "0 20 23 ? * MON-FRI", zone = "Asia/Kolkata")
	public void mcxExit() throws AddressException, MessagingException, IOException {
		chartService.monitorSignal("CRUDEOIL", "MCX", false, 0);
	}
}
