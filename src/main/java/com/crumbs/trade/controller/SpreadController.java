package com.crumbs.trade.controller;


import lombok.RequiredArgsConstructor;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import com.crumbs.trade.service.SpreadService;

@RestController
@RequestMapping("/api/spread")
@RequiredArgsConstructor
public class SpreadController {

	private final SpreadService spreadService;

	@PostMapping("/execute")
	@Scheduled(cron = "0 20 09 * * MON-FRI", zone = "Asia/Kolkata")
	public void executeSpread() {
		spreadService.getStockList();
	}
}
