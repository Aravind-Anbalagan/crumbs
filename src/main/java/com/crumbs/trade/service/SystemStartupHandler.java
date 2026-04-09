package com.crumbs.trade.service;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.StrategyRepo;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SystemStartupHandler {

    private final StraddleIntradayService straddleService;
    private final StrategyRepo strategyRepo;
    private static final Logger logger = LoggerFactory.getLogger(SystemStartupHandler.class);

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationEvent() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        int hour = now.getHour();
        
        // Basic check: Don't warm up if it's before 9:00 AM
        if (hour < 9) {
            logger.info("Market not open yet. Skipping VWAP warm-up.");
            return;
        }

        // Warm up NIFTY
        Strategy nifty = strategyRepo.findByName("NIFTY");
        if (nifty != null) {
            straddleService.warmUpVwap("NIFTY", nifty);
        } else {
            logger.warn("Startup Warm-up: NIFTY strategy configuration not found in DB.");
        }
        
        // Warm up CRUDE
        Strategy crude = strategyRepo.findByName("CRUDEOIL");
        if (crude != null) {
            straddleService.warmUpVwap("CRUDEOIL", crude);
        } else {
            logger.warn("Startup Warm-up: CRUDEOIL strategy configuration not found in DB.");
        }
    }
}