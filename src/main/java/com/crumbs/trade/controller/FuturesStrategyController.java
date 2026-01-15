package com.crumbs.trade.controller;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crumbs.trade.dto.FuturesConfigDto;
import com.crumbs.trade.entity.FuturesConfig;
import com.crumbs.trade.entity.FuturesFilter;
import com.crumbs.trade.repo.FuturesFilterRepo;
import com.crumbs.trade.service.FuturesStrategyService;

@RestController
@RequestMapping("/api/futures")
public class FuturesStrategyController {

    private static final Logger logger = LogManager.getLogger(FuturesStrategyController.class);
    
    private static final LocalTime MARKET_START = LocalTime.of(9, 15);
    private static final LocalTime MARKET_END   = LocalTime.of(15, 30);

    @Autowired
    private FuturesStrategyService futuresStrategyService;
    
    @Autowired
    private FuturesFilterRepo futuresFilterRepo;

    /**
     * ⏰ Scheduled execution every hour from 9:15 AM to 3:15 PM
     */
    @Scheduled(cron = "0 15 9-15 * * MON-FRI")
    public void scheduler915to315() {
        executeIfMarketOpen();
    }

    /**
     * ⏰ Scheduled execution at 3:30 PM
     */
    @Scheduled(cron = "0 30 15 * * MON-FRI")
    public void scheduler330() {
        executeIfMarketOpen();
    }
    
    /**
     * 🔄 Execute strategy for all active configs during market hours
     */
    private void executeIfMarketOpen() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        
        if (now.isBefore(MARKET_START) || now.isAfter(MARKET_END)) {
            logger.info("Market Closed - skipping execution");
            return;
        }
        
        try {
            logger.info("Executing futures strategy for all active configs");
            futuresStrategyService.executeAll();
        } catch (Exception e) {
            logger.error("Error executing futures strategy", e);
        }
    }
    
    /**
     * 🔧 Manual execution endpoint (bypasses market hours check)
     */
    @GetMapping("/execute")
    public ResponseEntity<String> manualExecute() {
        try {
            logger.info("Manual execution triggered");
            futuresStrategyService.executeAll();
            return ResponseEntity.ok("Execution completed for all active configs");
        } catch (Exception e) {
            logger.error("Error in manual execution", e);
            return ResponseEntity.internalServerError().body("Execution failed: " + e.getMessage());
        }
    }

    /**
     * 📊 Get ALL filtered data (both Nifty 50 and Nifty 500)
     */
    @GetMapping("/filtered")
    public List<FuturesFilter> getAllFilteredData() {
        return futuresFilterRepo.findAll();
    }
    
    /**
     * 📊 Get filtered data for specific index type
     */
    @GetMapping("/filtered/{indexType}")
    public List<FuturesFilter> getFilteredDataByIndexType(@PathVariable String indexType) {
        return futuresFilterRepo.findByIndexType(indexType);
    }
    
    /**
     * ⚙️ Get all configs
     */
    @GetMapping("/config")
    public List<FuturesConfig> fetchAllConfigs() {
        return futuresStrategyService.fetchAll();
    }
    
    /**
     * ⚙️ Get all active configs
     */
    @GetMapping("/config/active")
    public List<FuturesConfig> fetchAllActiveConfigs() {
        return futuresStrategyService.fetchAllActive();
    }
    
    /**
     * ⚙️ Get config for specific index type
     */
    @GetMapping("/config/{indexType}")
    public FuturesConfig fetchConfigByIndexType(@PathVariable String indexType) {
        return futuresStrategyService.fetch(indexType);
    }
    
    /**
     * ✏️ Update config for specific index type
     */
    @PatchMapping("/config/{indexType}")
    public FuturesConfig updateConfig(
            @PathVariable String indexType,
            @RequestBody FuturesConfigDto dto) {
        return futuresStrategyService.partialUpdate(indexType, dto);
    }
    
    // ============== BACKWARD COMPATIBLE ENDPOINTS ==============
    
    @GetMapping("/getDetails")
    public ResponseEntity<String> executeOldEndpoint() {
        return manualExecute();
    }
}