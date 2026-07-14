package com.crumbs.trade.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crumbs.trade.service.MonitorOrderService;
import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/risk")
@CrossOrigin(origins = "*") // Allows your UI to fetch data without CORS block errors
public class RiskController {

    @Autowired
    private MonitorOrderService riskService;

    @GetMapping("/live-pnl")
    public ResponseEntity<Map<Long, BigDecimal>> getLiveDashboardPnL() {
        // Reads instantly from RAM. Zero database egress cost.
        Map<Long, BigDecimal> livePnLMap = riskService.getLivePnLForUI();
        
        return ResponseEntity.ok(livePnLMap); 
    }
}