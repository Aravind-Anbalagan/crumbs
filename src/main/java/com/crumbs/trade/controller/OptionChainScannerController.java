package com.crumbs.trade.controller;

import com.crumbs.trade.builder.OptionScannerConfig;
import com.crumbs.trade.dto.DominanceSummaryDto;
import com.crumbs.trade.dto.ScannedContractDto;
import com.crumbs.trade.entity.OptionPrice;
import com.crumbs.trade.repo.NiftyRepo;
import com.crumbs.trade.service.OptionChainScannerService;
import com.crumbs.trade.service.OptionIndicatorService;
import com.crumbs.trade.service.OptionPriceService;
import com.crumbs.trade.service.StrategyConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j; // 👈 Added for logging
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j // 👈 Enables log.error, log.warn, etc.
@RestController
@RequestMapping("/api/options/scanner")
@RequiredArgsConstructor
@Tag(name = "Option Chain Scanner", description = "APIs for scanning NSE/MCX options, calculating RSI & MA indicators, and tracking lifecycle extremes.")
public class OptionChainScannerController {

    private final OptionChainScannerService scannerService;
    private final OptionIndicatorService indicatorService;
    private final OptionPriceService optionPriceService;
    private final StrategyConfigService configService;
    private final NiftyRepo niftyRepo;

    @Operation(
            summary = "Scan Option Chain & Evaluate Indicators for High Movers",
            description = "Scans option chains only for symbols that have moved by at least the specified percentage threshold, calculates indicators, saves extremes, and fires alerts.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Successfully scanned and processed contracts",
                            content = @Content(schema = @Schema(implementation = ScannedContractDto.class)))
            }
    )
    @GetMapping("/scan")
    public ResponseEntity<List<ScannedContractDto>> testScan(
            @Parameter(description = "List of indices or stocks to scan (e.g. NIFTY, BANKNIFTY or ALL)", example = "ALL")
            @RequestParam(defaultValue = "NIFTY") List<String> symbols,

            @Parameter(description = "Minimum absolute percentage change required to trigger a scan", example = "5.0")
            @RequestParam(defaultValue = "5.0") BigDecimal minPercentageChange,

            @Parameter(description = "Number of monthly expiries to scan", example = "1")
            @RequestParam(defaultValue = "1") int months,

            @Parameter(description = "Whether to include weekly expiries", example = "true")
            @RequestParam(defaultValue = "true") boolean weekly,

            @Parameter(description = "Whether to include monthly expiries", example = "true")
            @RequestParam(defaultValue = "true") boolean monthly,

            @Parameter(description = "Number of strikes up and down from the ATM strike to scan", example = "10")
            @RequestParam(defaultValue = "10") int distance,

            @Parameter(
                    description = "The historical candle timeframe interval. If left blank, defaults to DB StrategyConfig.",
                    example = "ONE_HOUR"
            )
            @RequestParam(required = false) String interval) {

        // Use DB configuration if no interval is explicitly passed
        String actualInterval = (interval != null && !interval.isBlank())
                ? interval
                : configService.getActiveConfig().getDefaultInterval();

        OptionScannerConfig config = OptionScannerConfig.builder()
                .monthsToScan(months)
                .scanWeekly(weekly)
                .scanMonthly(monthly)
                .strikeDistance(distance)
                .allowedMoneyness(Set.of(OptionScannerConfig.Moneyness.ATM, OptionScannerConfig.Moneyness.ITM))
                .interval(actualInterval)
                .build();

        // 1. Resolve symbols (Handles "ALL" or individual names) with error safety
        List<String> rawResolvedSymbols = new ArrayList<>();
        for (String symbol : symbols) {
            try {
                if ("ALL".equalsIgnoreCase(symbol.trim())) {
                    List<String> allNames = niftyRepo.getAllNames();
                    if (allNames != null && !allNames.isEmpty()) {
                        rawResolvedSymbols.addAll(allNames);
                    }
                } else {
                    rawResolvedSymbols.add(symbol);
                }
            } catch (Exception e) {
                log.error("⚠️ Error resolving symbol input '{}': {}. Skipping.", symbol, e.getMessage());
            }
        }

        // 2. Filter symbols based on the percentage change threshold safely
        List<String> finalSymbolsToScan = new ArrayList<>();
        for (String symbol : rawResolvedSymbols) {
            String cleanSymbol = symbol.trim();
            if (cleanSymbol.isEmpty()) {
                continue;
            }

            try {
                Optional<com.crumbs.trade.entity.Nifty> stockOpt = niftyRepo.findByName(cleanSymbol);

                if (stockOpt != null && stockOpt.isPresent()) {
                    BigDecimal currentChange = stockOpt.get().getPercentageChange();

                    // Check if the absolute percentage change meets or exceeds the threshold
                    if (currentChange != null && currentChange.abs().compareTo(minPercentageChange) >= 0) {
                        finalSymbolsToScan.add(cleanSymbol);
                    }
                } else {
                    // Fallback: If it's a broad index/symbol not tied to the stock table, allow it through
                    finalSymbolsToScan.add(cleanSymbol);
                }
            } catch (Exception e) {
                log.error("⚠️ Error evaluating percentage threshold for symbol '{}': {}. Skipping filter check.", cleanSymbol, e.getMessage());
            }
        }

        List<ScannedContractDto> masterContractList = new ArrayList<>();

        // 3. Scan only the filtered list of high movers with individual try-catch error insulation
        for (String symbol : finalSymbolsToScan) {
            try {
                List<ScannedContractDto> contracts = scannerService.scanEligibleContractsList(symbol, config);
                if (contracts != null && !contracts.isEmpty()) {
                    contracts = indicatorService.evaluateIndicatorsForContracts(contracts, config.getInterval());
                    masterContractList.addAll(contracts);
                }
            } catch (Exception e) {
                log.error("❌ Failed to scan or evaluate indicators for symbol '{}': {}. Moving to next symbol.", symbol, e.getMessage(), e);
            }
        }

        try {
            optionPriceService.saveExtremeContracts(masterContractList);
        } catch (Exception e) {
            log.error("❌ Failed to save extreme contracts batch to database: {}", e.getMessage(), e);
        }

        return ResponseEntity.ok(masterContractList);
    }

    // ==========================================
    // SEPARATED LIVE DASHBOARD ENDPOINTS
    // ==========================================

    @Operation(
            summary = "Retrieve Intraday CE/PE Dominance",
            description = "Calculates active CE vs PE strike breadth to power the live Tug-of-War UI bar."
    )
    @GetMapping("/tracked/live/dominance")
    public ResponseEntity<DominanceSummaryDto> getLiveDominance(
            @Parameter(description = "Underlying symbol (e.g. NIFTY)", example = "NIFTY")
            @RequestParam(defaultValue = "NIFTY") String symbol) {

        DominanceSummaryDto summary = optionPriceService.getDominanceSummary(symbol.trim().toUpperCase());
        return ResponseEntity.ok(summary);
    }

    @Operation(
            summary = "Retrieve Live RSI Signals",
            description = "Fetches the most recent live contracts currently in an RSI Extreme zone."
    )
    @GetMapping("/tracked/live/rsi")
    public ResponseEntity<List<OptionPrice>> getLiveRsiSignals(
            @Parameter(description = "Filter by timeframe. Use 'ALL' for no filter.", example = "ONE_HOUR")
            @RequestParam(required = false, defaultValue = "ALL") String timeFrame) {

        List<OptionPrice> rsiData = optionPriceService.getLiveRsiSignals(timeFrame);
        return ResponseEntity.ok(rsiData);
    }

    @Operation(
            summary = "Retrieve Live MA Signals (Breakouts & Breakdowns)",
            description = "Fetches the most recent live contracts that have just broken out above or broken down below the MA."
    )
    @GetMapping("/tracked/live/ma")
    public ResponseEntity<List<OptionPrice>> getLiveMaBreakouts(
            @Parameter(description = "Filter by timeframe. Use 'ALL' for no filter.", example = "ONE_HOUR")
            @RequestParam(required = false, defaultValue = "ALL") String timeFrame) {

        List<OptionPrice> maData = optionPriceService.getLiveMaBreakouts(timeFrame);
        return ResponseEntity.ok(maData);
    }

    @Operation(
            summary = "Retrieve Lifecycle Audit History",
            description = "Fetches the step-by-step chronological history of a specific contract symbol for the day."
    )
    @GetMapping("/tracked/audit")
    public ResponseEntity<List<OptionPrice>> getLifecycleAudit(
            @Parameter(description = "The exact symbol token to audit", example = "CRUDEOILM17SEP268250CE")
            @RequestParam String symbol,
            @Parameter(description = "Filter by timeframe", example = "ONE_HOUR")
            @RequestParam(required = false, defaultValue = "ALL") String timeFrame) {

        List<OptionPrice> auditHistory = optionPriceService.getSymbolLifecycleHistory(symbol, timeFrame);
        return ResponseEntity.ok(auditHistory);
    }

    @Operation(
            summary = "Retrieve Unique Time Frames",
            description = "Fetches a list of all distinct time frames currently available in the database."
    )
    @GetMapping("/tracked/live/timeframes")
    public ResponseEntity<List<String>> getAvailableTimeFrames() {
        List<String> timeFrames = optionPriceService.getUniqueTimeFrames();
        return ResponseEntity.ok(timeFrames);
    }
}