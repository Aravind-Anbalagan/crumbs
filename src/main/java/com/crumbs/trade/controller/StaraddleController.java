package com.crumbs.trade.controller;

import com.crumbs.trade.scheduler.StraddleScheduler;
import com.crumbs.trade.service.ShortStraddleService;
import com.crumbs.trade.service.StraddleExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test/scheduler")
@RequiredArgsConstructor
public class StaraddleController {

    private final StraddleScheduler straddleScheduler;
    private final ShortStraddleService shortStraddleService;
    private final StraddleExecutionService straddleExecutionService;
    @PostMapping("/nifty")
    public ResponseEntity<String> triggerNiftyStraddle() {
        try {
            // Manually invoke the scheduled method
            //straddleScheduler.straddleNifty();
            shortStraddleService.evaluate("BANKNIFTY");
            return ResponseEntity.ok("NIFTY and SENSEX straddle execution completed successfully.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Error executing NIFTY straddle: " + e.getMessage());
        }
    }

    @PostMapping("/crude")
    public ResponseEntity<String> triggerCrudeStraddle() {
        try {
            // Manually invoke the scheduled method
            straddleScheduler.straddleCrude();
            return ResponseEntity.ok("CRUDEOIL and NATURALGAS straddle execution completed successfully.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Error executing CRUDE straddle: " + e.getMessage());
        }
    }
}