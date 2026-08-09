package com.crumbs.trade.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crumbs.trade.service.FnoScannerService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/test/fno")
@RequiredArgsConstructor
public class FnoScannerTestController {

    private static final Logger logger = LogManager.getLogger(FnoScannerTestController.class);

    private final FnoScannerService fnoScannerService;

    /**
     * Manual Trigger for Step 1: Pre-cache Yesterday's Closing Prices
     * GET /api/test/fno/precache
     */
    @GetMapping("/precache")
    public ResponseEntity<String> triggerPrecache() {
        logger.info("🛠️ Manual trigger received for F&O Pre-cache...");

        try {
            fnoScannerService.precacheFnoPreviousClose();
            return ResponseEntity.ok("✅ F&O Pre-cache execution completed. Check your Spring Boot logs for the batch processing details!");
        } catch (Exception e) {
            logger.error("❌ Error during manual pre-cache trigger: {}", e.getMessage());
            return ResponseEntity.internalServerError().body("Failed to execute pre-cache: " + e.getMessage());
        }
    }

    /**
     * Manual Trigger for Step 2: Calculate % Price Move from Live WebSocket LTP
     * GET /api/test/fno/percentage
     */
    @GetMapping("/percentage")
    public ResponseEntity<String> triggerPercentageCalculation() {
        logger.info("🛠️ Manual trigger received for F&O Percentage Change calculation...");

        try {
            fnoScannerService.calculateFnoPercentageChange();
            return ResponseEntity.ok("✅ F&O Percentage Change calculation completed. Check your Spring Boot logs for details!");
        } catch (Exception e) {
            logger.error("❌ Error during manual percentage calculation trigger: {}", e.getMessage());
            return ResponseEntity.internalServerError().body("Failed to execute percentage calculation: " + e.getMessage());
        }
    }
}