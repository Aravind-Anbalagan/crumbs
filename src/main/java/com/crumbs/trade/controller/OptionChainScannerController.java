package com.crumbs.trade.controller;

import com.crumbs.trade.builder.OptionScannerConfig;
import com.crumbs.trade.dto.DominanceSummaryDto;
import com.crumbs.trade.dto.ScannedContractDto;
import com.crumbs.trade.entity.OptionPrice;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/options/scanner")
@RequiredArgsConstructor
@Tag(name = "Option Chain Scanner", description = "APIs for scanning NSE/MCX options, calculating RSI & MA indicators, and tracking lifecycle extremes.")
public class OptionChainScannerController {

    private final OptionChainScannerService scannerService;
    private final OptionIndicatorService indicatorService;
    private final OptionPriceService optionPriceService;
    private final StrategyConfigService configService;
    @Operation(
            summary = "Scan Option Chain & Evaluate Indicators",
            description = "Scans the option chain for the requested symbols, calculates the RSI and MA based on historical data, saves extremes/hooks to the database, and fires Telegram alerts.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Successfully scanned and processed contracts",
                            content = @Content(schema = @Schema(implementation = ScannedContractDto.class)))
            }
    )
    @GetMapping("/scan")
    public ResponseEntity<List<ScannedContractDto>> testScan(
            @Parameter(description = "List of indices or stocks to scan (e.g. NIFTY, BANKNIFTY)", example = "NIFTY,BANKNIFTY")
            @RequestParam(defaultValue = "NIFTY") List<String> symbols,

            @Parameter(description = "Number of monthly expiries to scan", example = "1")
            @RequestParam(defaultValue = "1") int months,

            @Parameter(description = "Whether to include weekly expiries", example = "true")
            @RequestParam(defaultValue = "true") boolean weekly,

            @Parameter(description = "Whether to include monthly expiries", example = "true")
            @RequestParam(defaultValue = "true") boolean monthly,

            @Parameter(description = "Number of strikes up and down from the ATM strike to scan", example = "10")
            @RequestParam(defaultValue = "10") int distance,

            @Parameter(
                    description = "The historical candle timeframe interval for indicator calculation. Allowed values: ONE_MINUTE, THREE_MINUTE, FIVE_MINUTE, TEN_MINUTE, FIFTEEN_MINUTE, THIRTY_MINUTE, ONE_HOUR, ONE_DAY",
                    example = "FIFTEEN_MINUTE"
            )
            @RequestParam(defaultValue = "ONE_HOUR") String interval) {

        // Use DB configuration if no interval is explicitly passed
        String actualInterval = (interval != null && !interval.isEmpty())
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

        List<ScannedContractDto> masterContractList = new ArrayList<>();

        for (String symbol : symbols) {
            String cleanSymbol = symbol.trim();
            List<ScannedContractDto> contracts = scannerService.scanEligibleContractsList(cleanSymbol, config);
            contracts = indicatorService.evaluateIndicatorsForContracts(contracts, config.getInterval());
            masterContractList.addAll(contracts);
        }

        optionPriceService.saveExtremeContracts(masterContractList);

        return ResponseEntity.ok(masterContractList);
    }

    // ==========================================
    // SEPARATED LIVE DASHBOARD ENDPOINTS
    // ==========================================

    @Operation(
            summary = "Retrieve Intraday CE/PE Dominance",
            description = "Calculates active CE vs PE strike breadth to power the live Tug-of-War UI bar.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Dominance summary calculated successfully",
                            content = @Content(schema = @Schema(implementation = DominanceSummaryDto.class)))
            }
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
            description = "Fetches the most recent live contracts that are currently in an RSI Extreme zone (>=80 or <=20) or actively triggering a hook.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "List of active RSI extreme/hook contracts",
                            content = @Content(schema = @Schema(implementation = OptionPrice.class)))
            }
    )
    @GetMapping("/tracked/live/rsi")
    public ResponseEntity<List<OptionPrice>> getLiveRsiSignals(
            @Parameter(description = "Filter by timeframe. Use 'ALL' for no filter.", example = "FIFTEEN_MINUTE")
            @RequestParam(required = false, defaultValue = "ALL") String timeFrame) {

        List<OptionPrice> rsiData = optionPriceService.getLiveRsiSignals(timeFrame);
        return ResponseEntity.ok(rsiData);
    }

    @Operation(
            summary = "Retrieve Live MA Breakouts",
            description = "Fetches the most recent live contracts that have just broken out above the Moving Average (within the proximity threshold).",
            responses = {
                    @ApiResponse(responseCode = "200", description = "List of active MA breakout contracts",
                            content = @Content(schema = @Schema(implementation = OptionPrice.class)))
            }
    )
    @GetMapping("/tracked/live/ma")
    public ResponseEntity<List<OptionPrice>> getLiveMaBreakouts(
            @Parameter(description = "Filter by timeframe. Use 'ALL' for no filter.", example = "FIFTEEN_MINUTE")
            @RequestParam(required = false, defaultValue = "ALL") String timeFrame) {

        List<OptionPrice> maData = optionPriceService.getLiveMaBreakouts(timeFrame);
        return ResponseEntity.ok(maData);
    }

    // ==========================================
    // LIFECYCLE AUDIT ENDPOINT
    // ==========================================

    @Operation(
            summary = "Retrieve Lifecycle Audit History",
            description = "Fetches the step-by-step chronological history of a specific contract symbol for the day, allowing the UI to draw an audit trail.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Chronological list of option state changes",
                            content = @Content(schema = @Schema(implementation = OptionPrice.class)))
            }
    )
    @GetMapping("/tracked/audit")
    public ResponseEntity<List<OptionPrice>> getLifecycleAudit(
            @Parameter(description = "The exact symbol token to audit", example = "CRUDEOILM17SEP268250CE")
            @RequestParam String symbol,
            @Parameter(description = "Filter by timeframe", example = "FIFTEEN_MINUTE")
            @RequestParam(required = false, defaultValue = "ALL") String timeFrame) {

        List<OptionPrice> auditHistory = optionPriceService.getSymbolLifecycleHistory(symbol, timeFrame);
        return ResponseEntity.ok(auditHistory);
    }
}