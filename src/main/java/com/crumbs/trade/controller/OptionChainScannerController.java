package com.crumbs.trade.controller;




import com.crumbs.trade.builder.OptionScannerConfig;
import com.crumbs.trade.builder.*;

import com.crumbs.trade.dto.ScannedContractDto;
import com.crumbs.trade.service.OptionChainScannerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/options/scanner")
@RequiredArgsConstructor
public class OptionChainScannerController {

    private final OptionChainScannerService scannerService;

    /**
     * Example:
     * GET /api/options/scanner/scan?symbol=NIFTY&spot=24235&months=1&weekly=true&monthly=true&distance=10
     * GET /api/options/scanner/scan?symbol=RELIANCE&spot=2980&months=2&weekly=false&monthly=true&distance=5
     */
    @GetMapping("/scan")
    public ResponseEntity<List<ScannedContractDto>> testScan(
            @RequestParam(defaultValue = "NIFTY") String symbol,
            @RequestParam(defaultValue = "1") int months,
            @RequestParam(defaultValue = "true") boolean weekly,
            @RequestParam(defaultValue = "true") boolean monthly,
            @RequestParam(defaultValue = "10") int distance) {

        OptionScannerConfig config = OptionScannerConfig.builder()
                .monthsToScan(months)
                .scanWeekly(weekly)
                .scanMonthly(monthly)
                .strikeDistance(distance)
                .allowedMoneyness(Set.of(OptionScannerConfig.Moneyness.ATM, OptionScannerConfig.Moneyness.ITM))
                .build();

        // Works for NIFTY, BANKNIFTY, RELIANCE, TCS, etc.
        List<ScannedContractDto> contracts = scannerService.scanEligibleContractsList(symbol, config);
        return ResponseEntity.ok(contracts);
    }
}