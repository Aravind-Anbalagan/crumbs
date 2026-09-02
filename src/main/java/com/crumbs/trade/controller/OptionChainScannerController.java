package com.crumbs.trade.controller;

import com.crumbs.trade.builder.OptionScannerConfig;
import com.crumbs.trade.dto.ScannedContractDto;
import com.crumbs.trade.entity.OptionPrice;
import com.crumbs.trade.service.OptionChainScannerService;
import com.crumbs.trade.service.OptionIndicatorService;
import com.crumbs.trade.service.OptionPriceService;
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
    private final OptionIndicatorService indicatorService;
    private final OptionPriceService optionPriceService;

    /**
     * Example API calls:
     * GET /api/options/scanner/scan?symbols=NIFTY&months=1
     * GET /api/options/scanner/scan?symbols=NIFTY,BANKNIFTY,RELIANCE&distance=10
     */
    @GetMapping("/scan")
    public ResponseEntity<List<ScannedContractDto>> testScan(
            @RequestParam(defaultValue = "NIFTY") List<String> symbols,
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

        List<ScannedContractDto> masterContractList = new ArrayList<>();

        // Loop through each requested symbol
        for (String symbol : symbols) {
            String cleanSymbol = symbol.trim();

            // 1. Scan the Chain
            List<ScannedContractDto> contracts = scannerService.scanEligibleContractsList(cleanSymbol, config);

            // 2. Evaluate Indicators
            contracts = indicatorService.evaluateIndicatorsForContracts(contracts);

            // Add to our master list
            masterContractList.addAll(contracts);
        }

        // 3. Save only the extremes to the database and fire Telegram alerts
        // (Your service handles the grouping by symbol internally, so passing the master list is safe!)
        optionPriceService.saveExtremeContracts(masterContractList);

        return ResponseEntity.ok(masterContractList);
    }

    /**
     * Retrieves the historical tracked RSI extreme contracts
     * GET /api/options/scanner/tracked
     */
    @GetMapping("/tracked")
    public ResponseEntity<List<OptionPrice>> getTrackedData() {
        List<OptionPrice> trackedHistory = optionPriceService.getTrackedHistory();
        return ResponseEntity.ok(trackedHistory);
    }
}