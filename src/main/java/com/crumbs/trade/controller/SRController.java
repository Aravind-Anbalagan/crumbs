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
	public PriceActionResult detectZones() {
		// Mock OHLCV candles
		pricesIndexRepo.deleteAll();
		CandleRequestDto candle = new CandleRequestDto();
		candle.setFromDate("2025-08-01 15:25");
		candle.setToDate("2025-08-18 15:25");
		candle.setTimeFrame("FIVE_MINUTE");
		candle.setType("NFO");
		List<PricesIndex> candles = srService.getCandleData(candle);

		if (candles != null && !candles.isEmpty()) {
			BigDecimal currentPrice = srService.getCurrentPriceForIndex();
			BigDecimal tolerance = BigDecimal.valueOf(0.5); // example tolerance
			BigDecimal avgVolume = BigDecimal.valueOf(100000); // example volume filter

			PriceActionResult pa = priceActionService.analyze(currentPrice, candles, PriceActionService.Mode.INTRADAY);
			return pa;
		}

		return null;
	}
	
	@GetMapping("/getCandleList")
	public List<PricesIndex> getCandleData() {
		return pricesIndexRepo.findAll();	
	}

}
