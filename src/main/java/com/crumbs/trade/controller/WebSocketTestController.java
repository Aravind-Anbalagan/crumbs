package com.crumbs.trade.controller;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import com.crumbs.trade.service.AngelWebSocketService;
import com.crumbs.trade.service.WebSocketService;

@RestController
@RequestMapping("/api/websocket")
public class WebSocketTestController {

    private static final Logger log = LoggerFactory.getLogger(WebSocketTestController.class);

    // ─── Change tokens here when contract expires ─────────────────────────────
    private static final ExchangeType NIFTY_EXCHANGE    = ExchangeType.NSE_FO;
    private static final String       NIFTY_TOKEN       = "59182";

    private static final ExchangeType CRUDEOIL_EXCHANGE = ExchangeType.MCX_FO;
    private static final String       CRUDEOIL_TOKEN    = "467013";
    // ─────────────────────────────────────────────────────────────────────────

    @Autowired
    private AngelWebSocketService angelWebSocketService;
    @Autowired
    private WebSocketService webSocketService;

    @GetMapping("/subscribe/nifty")
    public ResponseEntity<String> subscribeNifty() {
        angelWebSocketService.subscribe(NIFTY_EXCHANGE, NIFTY_TOKEN);
        log.info("Subscribed NIFTY | {} | {}", NIFTY_EXCHANGE, NIFTY_TOKEN);
        return ResponseEntity.ok("Subscribed NIFTY");
    }

    @GetMapping("/subscribe/crude")
    public ResponseEntity<String> subscribeCrude() {
        angelWebSocketService.subscribe(CRUDEOIL_EXCHANGE, CRUDEOIL_TOKEN);
        log.info("Subscribed CRUDEOIL | {} | {}", CRUDEOIL_EXCHANGE, CRUDEOIL_TOKEN);
        return ResponseEntity.ok("Subscribed CRUDEOIL");
    }

    @GetMapping("/subscribe/all")
    public ResponseEntity<String> subscribeAll() {
        angelWebSocketService.subscribe(NIFTY_EXCHANGE, NIFTY_TOKEN);
        angelWebSocketService.subscribe(CRUDEOIL_EXCHANGE, CRUDEOIL_TOKEN);
        log.info("Subscribed NIFTY and CRUDEOIL");
        return ResponseEntity.ok("Subscribed NIFTY and CRUDEOIL");
    }
    
	@GetMapping("/unsubscribe/all")
	public ResponseEntity<String> sunubscribeAll() {
		angelWebSocketService.unsubscribeAll();

		log.info("Unsubscribed");
		return ResponseEntity.ok("Unsubscribed");
	}

    @GetMapping("/ltp/nifty")
    public ResponseEntity<?> getNiftyLTP() {
        BigDecimal ltp = angelWebSocketService.getLatestLTP(NIFTY_EXCHANGE, NIFTY_TOKEN);
        if (ltp == null || ltp.compareTo(BigDecimal.ZERO) == 0)
            return ResponseEntity.status(204).body("NIFTY LTP not available yet");
        return ResponseEntity.ok(Map.of("name", "NIFTY", "ltp", ltp));
    }

    @GetMapping("/ltp/crude")
    public ResponseEntity<?> getCrudeLTP() {
        BigDecimal ltp = angelWebSocketService.getLatestLTP(CRUDEOIL_EXCHANGE, CRUDEOIL_TOKEN);
        if (ltp == null || ltp.compareTo(BigDecimal.ZERO) == 0)
            return ResponseEntity.status(204).body("CRUDEOIL LTP not available yet");
        return ResponseEntity.ok(Map.of("name", "CRUDEOIL", "ltp", ltp));
    }

    @GetMapping("/ltp/all")
    public ResponseEntity<?> getAllLTP() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("NIFTY",    angelWebSocketService.getLatestLTP(NIFTY_EXCHANGE, NIFTY_TOKEN));
        result.put("CRUDEOIL", angelWebSocketService.getLatestLTP(CRUDEOIL_EXCHANGE, CRUDEOIL_TOKEN));
        return ResponseEntity.ok(result);
    }
    
    /**
     * Returns the WebSocket key for a given instrument name.
     * HTML calls this to avoid hardcoding tokens.
     * e.g. GET /test/instrument/key?name=CRUDEOIL → {"key":"MCX_FO_467013"}
     */
    @GetMapping("/instrument/key")
    public ResponseEntity<?> getInstrumentKey(@RequestParam String name) {
        try {
            return ResponseEntity.ok(webSocketService.getInstrumentKey(name));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}