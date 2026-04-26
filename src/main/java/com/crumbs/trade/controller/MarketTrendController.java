package com.crumbs.trade.controller;

import com.crumbs.trade.dto.MarketTrendChartDTO;
import com.crumbs.trade.service.MarketTrendChartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/market-trend")
@CrossOrigin(origins = "*") // Allows your frontend UI to fetch this data without CORS errors
@RequiredArgsConstructor
public class MarketTrendController {

    private final MarketTrendChartService marketTrendChartService;

    @GetMapping("/chart-data")
    public ResponseEntity<List<MarketTrendChartDTO>> getChartData(
            @RequestParam(defaultValue = "CRUDEOIL") String symbol,
            @RequestParam(defaultValue = "FIVE_MINUTE") String timeframe) {

        log.info("📡 MarketTrend UI requested chart data | Symbol: {} | Timeframe: {}", symbol, timeframe);
        
        List<MarketTrendChartDTO> data = marketTrendChartService.getMarketTrendData(symbol, timeframe);
        
        if (data.isEmpty()) {
            log.warn("⚠️ No MarketTrend data found for {}", symbol);
            return ResponseEntity.noContent().build();
        }
        
        log.info("✅ Successfully delivered {} MarketTrend candles", data.size());
        return ResponseEntity.ok(data);
    }
}