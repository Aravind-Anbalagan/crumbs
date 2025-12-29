package com.crumbs.trade.controller;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crumbs.trade.dto.FuturesConfigDto;
import com.crumbs.trade.entity.FuturesConfig;
import com.crumbs.trade.entity.FuturesFilter;
import com.crumbs.trade.repo.FuturesFilterRepo;
import com.crumbs.trade.service.FuturesStrategyService;

import ch.qos.logback.classic.Logger;

@RestController
@RequestMapping("/api/futures")
public class FuturesStrategyController {
	private static final org.apache.logging.log4j.Logger logger = LogManager.getLogger(FuturesStrategyController.class);
	private static final LocalTime MARKET_START = LocalTime.of(9, 15);
	private static final LocalTime MARKET_END   = LocalTime.of(15, 30);

    @Autowired
    private FuturesStrategyService futuresStrategyService;

    @Autowired
    private FuturesFilterRepo futuresFilterRepo;

    @Scheduled(cron = "0 15 9-15 * * MON-FRI")
    public void scheduler915to315() {
        executeIfMarketOpen();
    }

    @Scheduled(cron = "0 30 15 * * MON-FRI")
    public void scheduler330() {
        executeIfMarketOpen();
    }
    

    private void executeIfMarketOpen() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (now.isBefore(MARKET_START) || now.isAfter(MARKET_END)) {
        	logger.info("Market Closed");
            return;
        }
        
        futuresStrategyService.execute();
    }
    
    @GetMapping("/getDetails")
    private void executeIfMarketClose() {
       
        futuresStrategyService.execute();
    }

    /**
     * 📊 UI API
     * Returns ALL filtered data (table already contains filtered rows only)
     */
    @GetMapping("/filtered")
    public List<FuturesFilter> getAllFilteredData() {
        return futuresFilterRepo.findAll();
    }
    
    // ✅ GET config
    @GetMapping
    public FuturesConfig fetchConfig() {
        return futuresStrategyService.fetch();
    }

    // ✅ Partial update
    @PatchMapping
    public FuturesConfig partialUpdate(
            @RequestBody FuturesConfigDto dto) {
        return futuresStrategyService.partialUpdate(dto);
    }
}
