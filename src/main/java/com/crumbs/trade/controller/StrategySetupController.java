package com.crumbs.trade.controller;

import com.crumbs.trade.dto.StrategySummaryDTO;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.service.StrategySetupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/strategies")
public class StrategySetupController {

    @Autowired
    private StrategySetupService strategySetupService;

    // GET: Returns the slim DTO for the main table
    @GetMapping
    public ResponseEntity<List<StrategySummaryDTO>> getAllStrategies(
            @RequestParam(required = false) String active,
            @RequestParam(required = false) String name) {
        
        List<StrategySummaryDTO> strategies = strategySetupService.getAllStrategies(active, name);
        return ResponseEntity.ok(strategies);
    }

    // NEW GET: Returns the FULL entity for editing
    @GetMapping("/{id}")
    public ResponseEntity<Strategy> getStrategyById(@PathVariable Long id) {
        try {
            Strategy strategy = strategySetupService.getStrategyById(id);
            return ResponseEntity.ok(strategy);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // PUT: Updates the full entity
    @PutMapping("/{id}")
    public ResponseEntity<Strategy> updateStrategy(
            @PathVariable Long id, 
            @RequestBody Strategy strategyDetails) {
        
        try {
            Strategy updatedStrategy = strategySetupService.updateStrategy(id, strategyDetails);
            return ResponseEntity.ok(updatedStrategy);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
}