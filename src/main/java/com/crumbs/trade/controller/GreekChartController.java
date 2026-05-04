package com.crumbs.trade.controller;

import com.crumbs.trade.dto.MultiLegGreeksChartPoint;
import com.crumbs.trade.service.GreekDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/v1/chart")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // Update with your specific frontend URL in production
public class GreekChartController {

    private final GreekDetailsService greekDetailsService;

    /**
     * HISTORICAL DATA: Use this to initial load the chart.
     * Lightweight Charts: lineSeries.setData(response);
     */
    @GetMapping("/historical")
    public ResponseEntity<List<MultiLegGreeksChartPoint>> getHistoricalDetails(
            @RequestParam String symbol,
            @RequestParam BigDecimal ceStrike,
            @RequestParam BigDecimal peStrike) {

        List<MultiLegGreeksChartPoint> data = greekDetailsService.getMultiLegChartData(symbol, ceStrike, peStrike);
        return ResponseEntity.ok(data);
    }

    /**
     * LIVE STREAM (SSE): Use this to update the chart tick-by-tick.
     * Lightweight Charts: lineSeries.update(event.data);
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<MultiLegGreeksChartPoint> streamLiveDetails(
            @RequestParam String symbol,
            @RequestParam BigDecimal ceStrike,
            @RequestParam BigDecimal peStrike) {

        return Flux.interval(Duration.ZERO, Duration.ofSeconds(10)) // Start at 0, poll every 10s for testing
                .flatMap(sequence -> {
                    List<MultiLegGreeksChartPoint> data = greekDetailsService.getMultiLegChartData(symbol, ceStrike, peStrike);
                    
                    if (data == null || data.isEmpty()) {
                        return Flux.empty(); // Skip this tick if no data found, don't crash
                    }
                    
                    return Flux.just(data.get(data.size() - 1));
                })
                .distinctUntilChanged(MultiLegGreeksChartPoint::time) // Don't push if the timestamp hasn't changed
                .log();
    }
}