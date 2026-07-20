package com.crumbs.trade.controller;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.dto.FuturesConfigDto;
import com.crumbs.trade.entity.FuturesBreakEvent;
import com.crumbs.trade.entity.FuturesConfig;
import com.crumbs.trade.entity.FuturesFilter;
import com.crumbs.trade.repo.FuturesFilterRepo;
import com.crumbs.trade.service.FuturesStrategyService;

@RestController
@RequestMapping("/api/futures")
public class FuturesStrategyController {

    private static final Logger logger = LogManager.getLogger(FuturesStrategyController.class);

    @Autowired
    private FuturesStrategyService futuresStrategyService;

    @Autowired
    private FuturesFilterRepo futuresFilterRepo;

    // ──────────────────────────────────────────────────────────
    //  EXECUTION ENDPOINTS
    // ──────────────────────────────────────────────────────────

    /**
     * 🌅 Manual Morning Setup (Pre-caches the Expiry High/Low)
     */
    @GetMapping("/execute-setup")
    public ResponseEntity<String> manualExecuteSetup() {
        try {
            logger.info("Manual morning setup execution triggered");
            futuresStrategyService.initializeDailyExpiryStructure();
            return ResponseEntity.ok("Morning expiry structure pre-cache completed successfully.");
        } catch (Exception | SmartAPIException e) {
            logger.error("Error in manual morning setup", e);
            return ResponseEntity.internalServerError().body("Setup failed: " + e.getMessage());
        }
    }

    /**
     * 🔧 Manual intraday scanner execution (bypasses market hours)
     */
    @GetMapping("/execute")
    public ResponseEntity<String> manualExecute() {
        try {
            logger.info("Manual intraday execution triggered");
            futuresStrategyService.executeAll();
            return ResponseEntity.ok("Execution completed for all active configs");
        } catch (Exception | SmartAPIException e) {
            logger.error("Error in manual execution", e);
            return ResponseEntity.internalServerError().body("Execution failed: " + e.getMessage());
        }
    }

    // ✅ Backward compatibility
    @GetMapping("/getDetails")
    public ResponseEntity<String> executeOldEndpoint() {
        return manualExecute();
    }

    // ──────────────────────────────────────────────────────────
    //  FILTER DATA ENDPOINTS
    // ──────────────────────────────────────────────────────────

    @GetMapping("/filtered")
    public List<FuturesFilter> getAllFilteredData() {
        return futuresFilterRepo.findAll();
    }

    @GetMapping("/filtered/{indexType}")
    public List<FuturesFilter> getFilteredDataByIndexType(@PathVariable String indexType) {
        return futuresFilterRepo.findByIndexType(indexType);
    }

    // ──────────────────────────────────────────────────────────
    //  CONFIG ENDPOINTS
    // ──────────────────────────────────────────────────────────

    @GetMapping("/config")
    public List<FuturesConfig> fetchAllConfigs() {
        return futuresStrategyService.fetchAll();
    }

    @GetMapping("/config/active")
    public List<FuturesConfig> fetchAllActiveConfigs() {
        return futuresStrategyService.fetchAllActive();
    }

    @GetMapping("/config/{indexType}")
    public FuturesConfig fetchConfigByIndexType(@PathVariable String indexType) {
        return futuresStrategyService.fetch(indexType);
    }

    @PatchMapping("/config/{indexType}")
    public FuturesConfig updateConfig(@PathVariable String indexType, @RequestBody FuturesConfigDto dto) {
        return futuresStrategyService.partialUpdate(indexType, dto);
    }

    // ──────────────────────────────────────────────────────────
    //  BREAK EVENT ENDPOINTS
    // ──────────────────────────────────────────────────────────

    @GetMapping("/break-events")
    public List<FuturesBreakEvent> getAllBreakEvents() {
        return futuresStrategyService.getAllBreakEvents();
    }

    @GetMapping("/break-events/date/{date}")
    public List<FuturesBreakEvent> getBreakEventsByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return futuresStrategyService.getBreakEventsByDate(date);
    }

    @GetMapping("/break-events/today")
    public List<FuturesBreakEvent> getTodayBreakEvents() {
        return futuresStrategyService.getBreakEventsByDate(LocalDate.now(ZoneId.of("Asia/Kolkata")));
    }
}