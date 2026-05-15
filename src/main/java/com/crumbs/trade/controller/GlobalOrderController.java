package com.crumbs.trade.controller;

import com.crumbs.trade.service.GlobalOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/global")
public class GlobalOrderController {

    @Autowired private GlobalOrderService globalOrderService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @PostMapping("/entry")
    public ResponseEntity<String> entry(
            @RequestParam String name, 
            @RequestParam String type, 
            @RequestParam(defaultValue = "BUY") String txnType) {
        try {
            return ResponseEntity.ok(globalOrderService.processGlobalEntry(name.toUpperCase(), type.toUpperCase(), txnType.toUpperCase()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Entry Error: " + e.getMessage());
        }
    }

    @PostMapping("/exit")
    public ResponseEntity<String> exit(@RequestParam String name, @RequestParam String type) {
        try {
            return ResponseEntity.ok(globalOrderService.processGlobalExit(name.toUpperCase(), type.toUpperCase()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Exit Error: " + e.getMessage());
        }
    }

    @GetMapping(value = "/pnl/stream", produces = "text/event-stream")
    public SseEmitter streamPnl(@RequestParam String name, @RequestParam String type) {
        // 10-minute timeout for the stream
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L); 

        executor.execute(() -> {
            try {
                // Send updates for 600 seconds (10 mins)
                for (int i = 0; i < 600; i++) {
                    String pnlValue = globalOrderService.getLivePnl(name.toUpperCase(), type.toUpperCase());
                    emitter.send(SseEmitter.event().name("pnl-update").data(pnlValue));
                    Thread.sleep(1000); 
                }
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}