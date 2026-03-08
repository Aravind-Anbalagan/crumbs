package com.crumbs.trade.controller;

import java.time.LocalDate;
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

    private static final Logger logger =
            LogManager.getLogger(FuturesStrategyController.class);

    @Autowired
    private FuturesStrategyService futuresStrategyService;

    @Autowired
    private FuturesFilterRepo futuresFilterRepo;

    /**
     * 🔧 Manual execution (bypasses market hours)
     * @throws SmartAPIException 
     */
    @GetMapping("/execute")
    public ResponseEntity<String> manualExecute() throws SmartAPIException {
        try {
            logger.info("Manual execution triggered");
            futuresStrategyService.executeAll();
            return ResponseEntity.ok("Execution completed for all active configs");
        } catch (Exception e) {
            logger.error("Error in manual execution", e);
            return ResponseEntity.internalServerError()
                    .body("Execution failed: " + e.getMessage());
        }
    }

    /**
     * 📊 Get ALL filtered data
     */
    @GetMapping("/filtered")
    public List<FuturesFilter> getAllFilteredData() {
        return futuresFilterRepo.findAll();
    }

    /**
     * 📊 Get filtered data by index type
     */
    @GetMapping("/filtered/{indexType}")
    public List<FuturesFilter> getFilteredDataByIndexType(
            @PathVariable String indexType) {
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
     * ⚙️ Get config by index type
     */
    @GetMapping("/config/{indexType}")
    public FuturesConfig fetchConfigByIndexType(
            @PathVariable String indexType) {
        return futuresStrategyService.fetch(indexType);
    }

    /**
     * ✏️ Update config
     */
    @PatchMapping("/config/{indexType}")
    public FuturesConfig updateConfig(
            @PathVariable String indexType,
            @RequestBody FuturesConfigDto dto) {
        return futuresStrategyService.partialUpdate(indexType, dto);
    }

    // ✅ Backward compatibility
    @GetMapping("/getDetails")
    public ResponseEntity<String> executeOldEndpoint() throws SmartAPIException {
        return manualExecute();
    }
    
 // GET /api/break-events
    @GetMapping
    public List<FuturesBreakEvent> getAllBreakEvents() {
        return futuresStrategyService.getAllBreakEvents();
    }

    // GET /api/break-events/date/2026-01-22
    @GetMapping("/date/{date}")
    public List<FuturesBreakEvent> getBreakEventsByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return futuresStrategyService.getBreakEventsByDate(date);
    }

    // GET /api/break-events/today
    @GetMapping("/today")
    public List<FuturesBreakEvent> getTodayBreakEvents() {
        return futuresStrategyService.getBreakEventsByDate(LocalDate.now());
    }
}
