package com.crumbs.trade.controller;


import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import com.crumbs.trade.service.SpreadService;

@RestController
@RequestMapping("/api/spread")
@RequiredArgsConstructor
public class SpreadController {

	private final SpreadService spreadService;

	@PostMapping("/execute")
	public void executeSpread() {
		spreadService.getStockList();
	}
}
