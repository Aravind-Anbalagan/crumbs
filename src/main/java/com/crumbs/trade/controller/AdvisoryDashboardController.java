package com.crumbs.trade.controller;

import com.crumbs.trade.advisory.AdvisoryEngineService;
import com.crumbs.trade.advisory.OptionRecommendation;
import com.crumbs.trade.entity.Nifty;
import com.crumbs.trade.repo.NiftyRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/advisory")
@RequiredArgsConstructor
public class AdvisoryDashboardController {

    private final AdvisoryEngineService engineService;
    private final NiftyRepo niftyRepo;

    @GetMapping("/recommendation/{symbol}")
    public ResponseEntity<OptionRecommendation> getRecommendation(@PathVariable String symbol) {
        OptionRecommendation rec = engineService.processAdvisory(symbol, symbol);
        if (rec == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(rec);
    }

    @PostMapping("/scan-active")
    public ResponseEntity<List<OptionRecommendation>> scanActiveStocks() {
        List<Nifty> activeStocks = niftyRepo.findByIsActiveTrueAndTokenIsNotNull();
        List<OptionRecommendation> recommendations = new ArrayList<>();
        
        for (Nifty stock : activeStocks) {
            try {
                OptionRecommendation rec = engineService.processAdvisory(stock.getName(),stock.getToken());
                if (rec != null) recommendations.add(rec);
            } catch (Exception e) {
                log.error("Error evaluating {}: {}", stock.getName(), e.getMessage());
            }
        }
        
        return ResponseEntity.ok(recommendations);
    }
}