package com.crumbs.trade.controller;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.crumbs.trade.dto.StraddleChartResponse;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.StraddleIntradayService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/straddle")
@RequiredArgsConstructor
public class StraddleChartController {

	@Autowired StraddleIntradayService straddleIntradayService;
	@Autowired StrategyRepo strategyRepo;
	

	@Scheduled(cron = "0 * * * * MON-FRI", zone = "Asia/Kolkata")
	public void straddleIntraday() {

		if ("Y".equalsIgnoreCase(strategyRepo.findByName("STRADDLE_PREMIUM").getActive())) {
			straddleIntradayService.getCombineStraddlePremium("NIFTY");
		}
	}
	
	@GetMapping("/straddle/combined-chart")
	public ResponseEntity<?> getCombinedChart(
	        @RequestParam String name,
	        @RequestParam String expiry,
	        @RequestParam BigDecimal ceStrike,
	        @RequestParam BigDecimal peStrike) {

	    if (ceStrike == null || peStrike == null)
	        return ResponseEntity.badRequest().body("ceStrike and peStrike are required");

	    return ResponseEntity.ok(
	    		straddleIntradayService.getStraddleCombinedChart(name, expiry, ceStrike, peStrike)
	    );
	}


	
}
