package com.crumbs.trade.controller;

import java.io.IOException;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.MessagingException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.entity.Vix;
import com.crumbs.trade.repo.PricesNiftyRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.repo.VixRepo;
import com.crumbs.trade.service.ChartService;
import com.crumbs.trade.service.TaskService;

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

	// For 9:20:05 AM to 9:55:05 AM AM:
	@Scheduled(cron = "5 20-59/5 9 * * MON-FRI", zone = "IST")
	public void scheduledTask1() throws SmartAPIException, AddressException, MessagingException, IOException {
		//logger.info("First");
		// commonExecution_1();
		commonExecution_2();
	}

	// For 10:00:05 AM to 2:55:05 PM
	@Scheduled(cron = "5 0/5 10-14 * * MON-FRI", zone = "IST")
	public void scheduledTask2() throws SmartAPIException, AddressException, MessagingException, IOException {
		// Your task logic here
		//logger.info("Second");
		// commonExecution_1();
		commonExecution_2();
	}

	// For 3:00:05 PM to 3:20:05 PM
	@Scheduled(cron = "5 0-20/5 15 * * MON-FRI", zone = "IST")
	public void scheduledTask3() throws SmartAPIException, AddressException, MessagingException, IOException {
		// Your task logic here
		//logger.info("Third");
		// commonExecution_1();
		commonExecution_2();
	}

	// For 4:00 PM to 11:30 PM:
	// @Scheduled(fixedRate = 10000)
	@Scheduled(cron = "5 0/5 16-23 * * MON-FRI", zone = "IST")
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

	}

	// Strategy 3 - CRUDEOIL
	public void commonExecution_3() throws SmartAPIException, AddressException, MessagingException, IOException {

		String fromDate = chartService.getDate("FROM", "MCX");
		String toDate = chartService.getDate("TO", "MCX");
		vixRepo.deleteAll();

		if (strategyRepo.findByName("CRUDEOIL").getActive().equals("Y")) {
			chartService.readChartData("FIVE_MINUTE", "MCX", false, "CRUDEOIL", fromDate, toDate);
			chartService.monitorSignal("CRUDEOIL", "MCX", false, 0);
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
}
