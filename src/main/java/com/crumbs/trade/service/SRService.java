package com.crumbs.trade.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.CandleRequestDto;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.PricesIndex;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.PricesIndexRepo;

import java.math.BigDecimal;
import java.util.*;
@Service
public class SRService {

	@Autowired
	ChartService chartService;
	
	@Autowired
	PricesIndexRepo pricesIndexRepo;
	
	@Autowired
	IndexesRepo indexesRepo;
	
	@Autowired
	AngelOneService angelOneService;
	
	@Autowired
	AngelOne angelOne;
	
	public List<PricesIndex> getCandleData(CandleRequestDto candleRequestDto)
	{
		Strategy strategy = chartService.getTokenDetails("NIFTY", "NFO");
		if (strategy.getName() != null) {
			chartService.readCandle(strategy, candleRequestDto.getType() , false, candleRequestDto.getTimeFrame(), candleRequestDto.getName(),
					candleRequestDto.getFromDate(), candleRequestDto.getToDate(),"OTHER");
			return pricesIndexRepo.findAll();
		}
		return null;
	}
	
	public BigDecimal getCurrentPriceForIndex()
	{
		SmartConnect smartConnect = angelOne.signIn();
		Indexes indexes = indexesRepo.findByNameAndSymbol("NIFTY", "NIFTY28AUG25FUT");
		BigDecimal currentPrice = angelOneService.getcurrentPrice(smartConnect, indexes.getExchange(),
				indexes.getSymbol(), indexes.getToken());
		return currentPrice;
	}
}
