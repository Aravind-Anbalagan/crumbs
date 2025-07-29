package com.crumbs.trade.controller;

import java.time.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ChartController {

    @GetMapping("/stock-data")
    public ResponseEntity<?> getStockData() {
        try {
            List<Map<String, Object>> candles = generateCandles();

            Map<String, Object> response = new HashMap<>();
            response.put("name", "RELIANCE");
            response.put("candles", candles);

            // Sample markers (BUY, SELL, SL)
            response.put("markers", List.of(
                    Map.of("time", toEpoch("2025-07-25T09:20:00"), "position", "belowBar", "color", "green", "shape", "arrowUp", "text", "BUY", "price", 101),
                    Map.of("time", toEpoch("2025-07-25T09:30:00"), "position", "belowBar", "color", "orange", "shape", "circle", "text", "SL", "price", 101.5),
                    Map.of("time", toEpoch("2025-07-25T09:40:00"), "position", "aboveBar", "color", "red", "shape", "arrowDown", "text", "SELL", "price", 102.3)
            ));

            // Support & Resistance levels
            response.put("supportLevels", List.of(101));
            response.put("resistanceLevels", List.of(102.8));

            // Trend channel
            response.put("trendChannel", Map.of(
                    "upper", List.of(
                            Map.of("time", toEpoch("2025-07-25T09:15:00"), "value", 103),
                            Map.of("time", toEpoch("2025-07-25T10:00:00"), "value", 105)
                    ),
                    "lower", List.of(
                            Map.of("time", toEpoch("2025-07-25T09:15:00"), "value", 100),
                            Map.of("time", toEpoch("2025-07-25T10:00:00"), "value", 102)
                    )
            ));

            // Zigzag
            response.put("zigzag", List.of(
                    Map.of("time", toEpoch("2025-07-25T09:15:00"), "value", 100),
                    Map.of("time", toEpoch("2025-07-25T09:20:00"), "value", 101.2),
                    Map.of("time", toEpoch("2025-07-25T09:25:00"), "value", 100.4)
            ));

            // Feature flags
            response.put("flags", Map.of(
                    "showBuySell", true,
                    "showSupportResistance", true,
                    "showTrendLine", true,
                    "showZigzag", true
            ));

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private List<Map<String, Object>> generateCandles() {
        return List.of(
                candle("2025-07-25T09:15:00", 100, 101, 99.5, 100.5, 1000),
                candle("2025-07-25T09:20:00", 100.5, 101.2, 100.2, 101, 1100),
                candle("2025-07-25T09:25:00", 101, 101.5, 100.8, 101.3, 900),
                candle("2025-07-25T09:30:00", 101.3, 101.8, 101, 101.6, 1050),
                candle("2025-07-25T09:35:00", 101.6, 102, 101.5, 101.8, 980),
                candle("2025-07-25T09:40:00", 101.8, 102.1, 101.4, 101.7, 1200),
                candle("2025-07-25T09:45:00", 101.7, 102.2, 101.6, 102, 950),
                candle("2025-07-25T09:50:00", 102, 102.5, 101.8, 102.3, 1100),
                candle("2025-07-25T09:55:00", 102.3, 102.6, 102, 102.5, 1030),
                candle("2025-07-25T10:00:00", 102.5, 103, 102.4, 102.8, 990)
        );
    }

    private Map<String, Object> candle(String time, double open, double high, double low, double close, int volume) {
        Map<String, Object> candle = new HashMap<>();
        candle.put("time", toEpoch(time));
        candle.put("open", open);
        candle.put("high", high);
        candle.put("low", low);
        candle.put("close", close);
        candle.put("volume", volume);
        return candle;
    }

    private long toEpoch(String isoDateTime) {
        LocalDateTime localDateTime = LocalDateTime.parse(isoDateTime);
        ZoneId zoneId = ZoneId.of("Asia/Kolkata");
        return localDateTime.atZone(zoneId).toEpochSecond();
    }
}
