package com.crumbs.trade.controller;

import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.OIDataService;
import com.crumbs.trade.service.OIService;

@RestController
@RequestMapping(value = "/optionChain")
public class OptionChainController {
	private static final Logger logger = LogManager.getLogger(OptionChainController.class);

	@Autowired
	OIService oiService;

	@Autowired
	OIDataService oiDataService;

	@Autowired
	StrategyRepo strategyRepo;

	// @Scheduled(fixedRate = 10000)
	@GetMapping("/dailyoi")
	@Scheduled(cron = "0 00 20 * * ?") // Works
	public void optionChain() throws SmartAPIException, Exception {
		if (strategyRepo.findByName("NIFTY_OI").getActive().equals("Y")) {
			oiDataService.getOptionChain("NIFTY_OI");
		}

	}

	// For 9:20:05 AM to 9:55:05 AM AM:
	//@Scheduled(fixedRate = 10000)
	//@Scheduled(cron = "5 20-59/5 9 * * *", zone = "IST")
	public void scheduledTask1() throws SmartAPIException, IOException {

		if (strategyRepo.findByName("NIFTY_OI").getActive().equals("Y")) {
			oiService.getOptionChain("NIFTY_OI");
		}
	}

	// For 10:00:05 AM to 2:55:05 PM
	//@Scheduled(cron = "5 0/5 10-14 * * *", zone = "IST")
	public void scheduledTask2() throws SmartAPIException, IOException {
		if (strategyRepo.findByName("NIFTY_OI").getActive().equals("Y")) {
			oiService.getOptionChain("NIFTY_OI");
		}
	}

	// For 3:00:05 PM to 3:30:05 PM
	//@Scheduled(cron = "5 0-30/5 15 * * *", zone = "IST")
	public void scheduledTask3() throws SmartAPIException, IOException {
		if (strategyRepo.findByName("NIFTY_OI").getActive().equals("Y")) {
			oiService.getOptionChain("NIFTY_OI");
		}
	}

	// For 3:00 PM to 3:30 PM:
	//@Scheduled(cron = "5 0/5 15-22 * * *", zone = "IST")
	public void scheduledTask4() throws SmartAPIException, IOException {
		if (strategyRepo.findByName("CRUDEOIL").getActive().equals("Y")) {
			//oiService.getOptionChain("CRUDEOIL");
		}

	}
}
