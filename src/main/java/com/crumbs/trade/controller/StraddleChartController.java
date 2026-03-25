package com.crumbs.trade.controller;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.crumbs.trade.dto.NameExpiryStrikeGroupedDto;
import com.crumbs.trade.dto.PreMarketAnalysisResponseDto;
import com.crumbs.trade.dto.PreMarketAnalysisResponseDto.CrossPlotTargetsDto;
import com.crumbs.trade.dto.PreMarketAnalysisResponseDto.OptionDataDto;
import com.crumbs.trade.dto.PreMarketAnalysisResponseDto.PreMarketDataDto;
import com.crumbs.trade.dto.SecondMidPointRequest;
import com.crumbs.trade.entity.PreMarketAnalysis;
import com.crumbs.trade.entity.StraddleIntraday;
import com.crumbs.trade.service.PreMarketAnalysisService;
import com.crumbs.trade.service.StraddleExecutionService;
import com.crumbs.trade.service.StraddleGroupingService;
import com.crumbs.trade.service.StraddleIntradayService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/straddle")
@RequiredArgsConstructor
public class StraddleChartController {
    
    private final StraddleIntradayService straddleIntradayService;
    private final StraddleGroupingService straddleGroupingService;
    private final PreMarketAnalysisService preMarketAnalysisService;
    private final StraddleExecutionService straddleExecutionService;
    
    @GetMapping("/combined-chart")
    public ResponseEntity<?> getCombinedChart(
            @RequestParam String name,
            @RequestParam String expiry,
            @RequestParam BigDecimal ceStrike,
            @RequestParam BigDecimal peStrike) {
        
        if (ceStrike == null || peStrike == null) {
            return ResponseEntity.badRequest()
                    .body("ceStrike and peStrike are required");
        }
        
        return ResponseEntity.ok(
                straddleIntradayService
                        .getStraddleCombinedChart(name, expiry, ceStrike, peStrike)
        );
    }
    
    @GetMapping("/grouped")
    public List<NameExpiryStrikeGroupedDto> getGrouped() {
        return straddleGroupingService.getGrouped();
    }
    
    /**
     * Get complete pre-market analysis with time-series data
     * Returns one-time calculated values + series of current CE/PE prices
     * 
     * Endpoint: GET /api/straddle/pre-market/chart/{name}
     * Example: GET /api/straddle/pre-market/chart/NIFTY
     */
    @GetMapping("/pre-market/chart/{name}")
    public ResponseEntity<?> getPreMarketChartData(@PathVariable String name) {
        
        try {
            // 1. Get pre-market analysis (one-time calculated values)
            Optional<PreMarketAnalysis> preMarketOpt = preMarketAnalysisService.getTodayAnalysis(name);
            
            if (preMarketOpt.isEmpty()) {
                preMarketOpt = preMarketAnalysisService.getLatestAnalysis(name);
            }
            
            if (preMarketOpt.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                    "status", "error",
                    "message", "No pre-market analysis found"
                ));
            }
            
            PreMarketAnalysis preMarket = preMarketOpt.get();
            
            // 2. Get time-series data from STRADDLE_INTRADAY table
            List<StraddleIntraday> timeSeries = straddleIntradayService.getTimeSeriesByStrike(
                name,
                preMarket.getExpiry(),
                preMarket.getAtmStrike()
            );
            
            // 3. Build time-series arrays
            List<String> timestamps = new ArrayList<>();
            List<BigDecimal> cePricesSeries = new ArrayList<>();
            List<BigDecimal> pePricesSeries = new ArrayList<>();
            
            DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");
            DateTimeFormatter analysisTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
            
            if (timeSeries != null && !timeSeries.isEmpty()) {
                for (StraddleIntraday record : timeSeries) {
                    timestamps.add(record.getTimestamp().format(timeFormatter));
                    cePricesSeries.add(record.getCePrice());
                    pePricesSeries.add(record.getPePrice());
                }
            }
            
            // 4. Build complete response
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("timestamp", preMarket.getTimestamp().toString());
            
            Map<String, Object> dataMap = new HashMap<>();
            
            // Basic info
            dataMap.put("strategy", preMarket.getName());
            dataMap.put("expiry", preMarket.getExpiry());
            dataMap.put("tradingDate", preMarket.getTradingDate().toString());
            dataMap.put("analysisTime", preMarket.getTimestamp().format(analysisTimeFormatter));
            
            // Selected ATM strike info
            Map<String, Object> selectedAtm = new HashMap<>();
            selectedAtm.put("strike", preMarket.getAtmStrike());
            selectedAtm.put("ltpDiff", preMarket.getLtpDiff());
            selectedAtm.put("midPoint", preMarket.getMidPoint());
            selectedAtm.put("secondMidPoint", preMarket.getSecondMidPoint());
            dataMap.put("selectedAtm", selectedAtm);
            
            // CE data (pre-market + time-series)
            Map<String, Object> ceMap = new HashMap<>();
            ceMap.put("ltp", preMarket.getCeLtp());
            ceMap.put("prevHigh", preMarket.getCePrevHigh());
            ceMap.put("prevLow", preMarket.getCePrevLow());
            ceMap.put("pricesSeries", cePricesSeries);  // Time-series array
            dataMap.put("ce", ceMap);
            
            // PE data (pre-market + time-series)
            Map<String, Object> peMap = new HashMap<>();
            peMap.put("ltp", preMarket.getPeLtp());
            peMap.put("prevHigh", preMarket.getPePrevHigh());
            peMap.put("prevLow", preMarket.getPePrevLow());
            peMap.put("pricesSeries", pePricesSeries);  // Time-series array
            dataMap.put("pe", peMap);
            
            // Combined data
            Map<String, Object> combinedMap = new HashMap<>();
            combinedMap.put("ltp", preMarket.getCombinedLtp());
            combinedMap.put("midPoint", preMarket.getMidPoint());
            dataMap.put("combined", combinedMap);
            
            // Timestamps for time-series
            dataMap.put("timestamps", timestamps);
            
            // Cross-plot targets
            Map<String, Object> targets = new HashMap<>();
            targets.put("ceTarget", preMarket.getPePrevLow());  // CE target = PE prev low
            targets.put("peTarget", preMarket.getCePrevLow());  // PE target = CE prev low
            dataMap.put("crossPlotTargets", targets);
            
            response.put("data", dataMap);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                "status", "error",
                "message", e.getMessage()
            ));
        }
    }
    
    @PostMapping("/second-mid-point")
    public ResponseEntity<PreMarketAnalysis> updateSecondMidPoint(
            @RequestBody SecondMidPointRequest request) {
        PreMarketAnalysis updated = preMarketAnalysisService.updateSecondMidPoint(request);
        return ResponseEntity.ok(updated);
    }
    
    @GetMapping("/preMarketAnalysis")
    public void getPreMartketLevels() {
    	straddleIntradayService.prevDayDataDate = null;
        straddleExecutionService.executePreMarket("NIFTY");
    }
}