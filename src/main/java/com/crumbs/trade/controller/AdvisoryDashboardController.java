package com.crumbs.trade.controller;

import com.crumbs.trade.advisory.AdvisoryEngineService;
import com.crumbs.trade.advisory.AdvisoryLedger;
import com.crumbs.trade.advisory.AdvisoryLedgerRepository;
import com.crumbs.trade.advisory.OptionRecommendation;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.Nifty;
import com.crumbs.trade.repo.IndexesRepo;
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
@CrossOrigin(origins = "*") // Allow UI to connect
public class AdvisoryDashboardController {

    private final AdvisoryEngineService engineService;
    private final NiftyRepo niftyRepo;
    private final IndexesRepo indexesRepo;
    private final AdvisoryLedgerRepository ledgerRepository;

    // =========================================================
    // 📊 VIEW 1: GLOBAL DASHBOARD (Sub-10ms Load)
    // =========================================================
    
    @GetMapping("/dashboard")
    public ResponseEntity<List<AdvisoryLedger>> getDashboardSummary() {
        // Fetches only the currently ACTIVE rows for all symbols directly from the DB.
        // Extremely fast for the UI to load on refresh.
        List<AdvisoryLedger> activeRecords = ledgerRepository.findByStatus("ACTIVE");
        return ResponseEntity.ok(activeRecords);
    }

    // =========================================================
    // 🔎 VIEW 2: SYMBOL DRILL-DOWN (The Timeline)
    // =========================================================
    
    @GetMapping("/history/{symbol}")
    public ResponseEntity<List<AdvisoryLedger>> getSymbolHistory(@PathVariable String symbol) {
        // Returns the historical audit trail for the Timeline component
        List<AdvisoryLedger> history = ledgerRepository.findBySymbolOrderByTimestampDesc(symbol);
        if (history.isEmpty()) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(history);
    }

    // =========================================================
    // ⚙️ ADMIN COMMANDS (Manual Engine Triggers)
    // =========================================================

    @PostMapping("/trigger/{symbol}")
    public ResponseEntity<OptionRecommendation> triggerSingleEngine(@PathVariable String symbol) {
        // 🚀 Fix: Look up the token first before passing it to the engine
        Indexes index = indexesRepo.findByNameAndExchange(symbol, "NSE"); 
        
        if (index == null) {
            log.warn("Cannot run engine: No index found for symbol {}", symbol);
            return ResponseEntity.badRequest().build();
        }
        
        OptionRecommendation rec = engineService.processAdvisory(symbol, index.getToken());
        if (rec == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(rec);
    }

    @PostMapping("/scan-active")
    public ResponseEntity<List<OptionRecommendation>> triggerFullScan() {
        // 🚀 This is your manual override if you don't want to wait for the 9:00 AM Cron
        List<Nifty> activeStocks = niftyRepo.findByIsActiveTrueAndTokenIsNotNull();
        List<OptionRecommendation> recommendations = new ArrayList<>();
        
        for (Nifty stock : activeStocks) {
            try {
                OptionRecommendation rec = engineService.processAdvisory(stock.getName(), stock.getToken());
                if (rec != null) recommendations.add(rec);
            } catch (Exception e) {
                log.error("Error evaluating {}: {}", stock.getName(), e.getMessage());
            }
        }
        
        return ResponseEntity.ok(recommendations);
    }
}