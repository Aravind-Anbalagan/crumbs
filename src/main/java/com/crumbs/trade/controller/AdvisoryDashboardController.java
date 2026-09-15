package com.crumbs.trade.controller;

import com.crumbs.trade.advisory.AdvisoryLedger;
import com.crumbs.trade.advisory.AdvisoryLedgerRepository;
import com.crumbs.trade.advisory.AdvisoryEngineScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/advisory")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AdvisoryDashboardController {

    private final AdvisoryLedgerRepository ledgerRepository;
    private final AdvisoryEngineScheduler advisoryEngineScheduler;

    // =========================================================================
    // 🖥️ UI CONSUMED APIs
    // =========================================================================

    /**
     * 1. GET /timeline - Consumed by React UI to render the Lifecycle Matrix
     */
    @GetMapping("/timeline")
    public ResponseEntity<List<AdvisoryLedger>> getTimelineFlat(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate) {

        LocalDateTime start = (startDate != null) ? startDate.atStartOfDay() : LocalDateTime.now().minusDays(30);
        List<AdvisoryLedger> records = ledgerRepository.findAllAfterTimestampOrderedBySymbolAndDate(start);

        if (records.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(records);
    }

    /**
     * 2. POST /scan-active - Consumed by React UI "⚡ Scan Market" button
     * Maps to the Morning Scan logic.
     */
    @PostMapping("/scan-active")
    public ResponseEntity<?> scanActivePositions() {
        log.info("🔄 UI Trigger: Manual Morning Scan...");
        try {
            advisoryEngineScheduler.runMorningScan();
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Morning Scan completed."));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    // =========================================================================
    // 🧪 TESTING & LIFECYCLE APIs (Test via Postman / Browser)
    // =========================================================================

    /**
     * 3. POST /scan/preclose - Test Intraday SL / Fresh afternoon entries
     */
    @PostMapping("/scan/preclose")
    public ResponseEntity<?> testPreCloseScan() {
        log.info("🔄 Test Trigger: 3:15 PM Pre-Close Scan...");
        try {
            advisoryEngineScheduler.runPreCloseScan();
            return ResponseEntity.ok(Map.of("status", "SUCCESS"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    /**
     * 4. POST /scan/eod - Test EOD MTM & Realized PnL Calculation
     */
    @PostMapping("/scan/eod")
    public ResponseEntity<?> testEodSettlement() {
        log.info("🔄 Test Trigger: 3:35 PM EOD Settlement...");
        try {
            advisoryEngineScheduler.runEodPnlSettlement();
            return ResponseEntity.ok(Map.of("status", "SUCCESS"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    /**
     * 5. DELETE /reset-ledger - 🚨 CRITICAL FOR TESTING 🚨
     * Wipes the table so you can test the new Lot Size (₹) PnL cleanly.
     */
    @DeleteMapping("/reset-ledger")
    public ResponseEntity<?> resetLedgerForTesting() {
        log.warn("🚨 Wipe requested: Deleting all Advisory Ledger records...");
        ledgerRepository.deleteAll();
        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "message", "Ledger wiped cleanly. Ready for fresh ₹ PnL testing."
        ));
    }
}