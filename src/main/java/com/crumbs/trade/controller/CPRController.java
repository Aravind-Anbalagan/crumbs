package com.crumbs.trade.controller;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.service.StrategyService;

@RestController
@RequestMapping("/api/Straddle")
public class CPRController {

	@Autowired
	StrategyService strategyService;
	private static final Logger logger = LoggerFactory.getLogger(CPRController.class);

	@PostMapping("/execute")
	public void updateSecondMidPoint() throws IOException, SmartAPIException {

		logger.info("📊 CPR — fetching CPR details");
		strategyService.getCPRDetails();
	}

}
