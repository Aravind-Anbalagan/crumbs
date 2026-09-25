package com.crumbs.trade.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.crumbs.trade.service.BrokerConfigService;
import com.crumbs.trade.service.FlatTradeService;
import com.crumbs.trade.dto.Token; // Ensure this import is added

@RestController
@RequestMapping("/api/flattrade")
public class FlatTradeLoginController {

    @Autowired
    private BrokerConfigService brokerConfigService;

    @Autowired
    private FlatTradeService flatTradeService;

    /**
     * STEP 1: Save the request_code to the database.
     */
    @PostMapping("/save-code")
    public ResponseEntity<String> saveRequestCode(@RequestParam("code") String code) {
        try {
            brokerConfigService.updateRequestCode(code);
            return ResponseEntity.ok("Step 1 Success: Request code saved to DB.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Step 1 Failed (DB Error): " + e.getMessage());
        }
    }

    /**
     * STEP 2: Generate the token using the code stored in the DB.
     */
    @PostMapping("/generate-token")
    public ResponseEntity<String> generateToken() {
        try {
            String jKey = flatTradeService.getTokenForFlatTrade();
            return ResponseEntity.ok("Step 2 Success: JKey generated and cached. Token starts with: " + jKey.substring(0, 6));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Step 2 Failed (API Error): " + e.getMessage());
        }
    }

    /**
     * STEP 3: Test Place Order
     * Execute a market order using the existing token.
     */
    @PostMapping("/test-order")
    public ResponseEntity<?> placeTestOrder(@RequestBody Token token) {
        try {
            Token processedToken = flatTradeService.PlaceOrderInFlatTrade(token);

            if (processedToken != null && processedToken.getOrderId() != null) {
                return ResponseEntity.ok(processedToken);
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Order failed: Received empty response or missing Order ID.");
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Order Exception: " + e.getMessage());
        }
    }

    /**
     * STEP 4: Smart Execution Engine
     * Instantly checks depth, fires a limit order, and chases the top bid/ask
     * asynchronously until the configured quantity is filled.
     */
    @PostMapping("/smart-order")
    public ResponseEntity<String> placeSmartOrder(
            @RequestBody Token token,
            @RequestParam("instrumentToken") String instrumentToken) {

        try {
            if (instrumentToken == null || instrumentToken.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("instrumentToken is required to fetch market depth.");
            }

            // Handoff to @Async background execution (non-blocking)
            flatTradeService.executeSmartOrder(token, instrumentToken);

            return ResponseEntity.ok("Smart execution initiated for " + token.getSymbol() + ". Monitoring in background.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Smart Order Exception: " + e.getMessage());
        }
    }
}