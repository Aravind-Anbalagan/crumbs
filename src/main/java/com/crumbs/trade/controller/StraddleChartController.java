package com.crumbs.trade.controller;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.crumbs.trade.dto.NameExpiryStrikeGroupedDto;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.StraddleGroupingService;
import com.crumbs.trade.service.StraddleIntradayService;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.scheduling.annotation.Scheduled;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/straddle")
@RequiredArgsConstructor
public class StraddleChartController {

	@Autowired StraddleIntradayService straddleIntradayService;
	@Autowired StrategyRepo strategyRepo;
	@Autowired StraddleGroupingService straddleGroupingService;

    @Schedules({
        @Scheduled(cron = "0 15-59 9 * * MON-FRI", zone = "Asia/Kolkata"),   // 9:15 - 9:59
        @Scheduled(cron = "0 * 10-14 * * MON-FRI", zone = "Asia/Kolkata"),   // 10:00 - 14:59
        @Scheduled(cron = "0 0-30 15 * * MON-FRI", zone = "Asia/Kolkata")    // 15:00 - 15:30
    })
	public void straddleIntraday() {

		if ("Y".equalsIgnoreCase(strategyRepo.findByName("STRADDLE_PREMIUM").getActive())) {
			straddleIntradayService.getCombineStraddlePremium("NIFTY");
		}
	}
	
	@GetMapping("/combined-chart")
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

	@GetMapping("/grouped")
    public List<NameExpiryStrikeGroupedDto> getGrouped() {
        return straddleGroupingService.getGrouped();
    }
	
}
