package com.crumbs.trade.controller;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.crumbs.trade.dto.CandleRequestDto;
import com.crumbs.trade.dto.PriceActionResult;
import com.crumbs.trade.entity.PricesIndex;
import com.crumbs.trade.repo.PricesIndexRepo;
import com.crumbs.trade.service.AngelOneService;
import com.crumbs.trade.service.PriceActionService;
import com.crumbs.trade.service.SRService;

@RestController
@RequestMapping("/sr")
@CrossOrigin(origins = "*")
public class SRController {

	@Autowired
	PriceActionService priceActionService;

	@Autowired
	SRService srService;

	@Autowired
	PricesIndexRepo pricesIndexRepo;

	@Autowired
	AngelOneService angelOneService;

	// @Scheduled(fixedRate = 10000)
	@GetMapping("/zones")
	public PriceActionResult detectZones(
			@RequestParam(name = "timeFrame", defaultValue = "FIVE_MINUTE") String timeFrame) {
		// Mock OHLCV candles
		pricesIndexRepo.deleteAll();
		
		CandleRequestDto candle = srService.getCandleTiming(timeFrame);
		
		List<PricesIndex> candles = srService.getCandleData(candle);

		if (candles != null && !candles.isEmpty()) {
			BigDecimal currentPrice = srService.getCurrentPriceForIndex();

			PriceActionResult pa = priceActionService.analyze(currentPrice, candles,timeFrame);
			return pa;
		}

		return null;
	}
	
	@GetMapping("/getCandleList")
	public List<PricesIndex> getCandleData() {
		return pricesIndexRepo.findAll();	
	}

}
