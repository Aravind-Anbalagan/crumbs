package com.crumbs.trade.controller;


import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.SpreadService;

@RestController
@RequestMapping("/api/spread")
@RequiredArgsConstructor
public class SpreadController {

	private final SpreadService spreadService;

	@Autowired
	StrategyRepo strategyRepo;
	
	@PostMapping("/execute")
	@Scheduled(cron = "0 20 09 * * MON-FRI", zone = "Asia/Kolkata")
	public void executeSpread() {
		if (strategyRepo.findByName("SPREAD_STRATEGY").getActive().equals("Y")) {
			spreadService.getStockList();
		}

	}
}
