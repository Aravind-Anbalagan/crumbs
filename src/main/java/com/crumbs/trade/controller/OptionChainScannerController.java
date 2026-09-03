package com.crumbs.trade.controller;

import com.crumbs.trade.builder.OptionScannerConfig;
import com.crumbs.trade.dto.ScannedContractDto;
import com.crumbs.trade.entity.OptionPrice;
import com.crumbs.trade.service.OptionChainScannerService;
import com.crumbs.trade.service.OptionIndicatorService;
import com.crumbs.trade.service.OptionPriceService;
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
@Tag(name = "Option Chain Scanner", description = "APIs for scanning NSE options, calculating RSI hooks, and tracking extremes.")
public class OptionChainScannerController {

    private final OptionChainScannerService scannerService;
    private final OptionIndicatorService indicatorService;
    private final OptionPriceService optionPriceService;

    @Operation(
            summary = "Scan Option Chain & Evaluate Indicators",
            description = "Scans the option chain for the requested symbols, calculates the RSI based on historical data, saves extremes/hooks to the database, and fires Telegram alerts.",
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
                    description = "The historical candle timeframe interval for RSI calculation. Allowed values: ONE_MINUTE, THREE_MINUTE, FIVE_MINUTE, TEN_MINUTE, FIFTEEN_MINUTE, THIRTY_MINUTE, ONE_HOUR, ONE_DAY",
                    example = "FIFTEEN_MINUTE"
            )
            @RequestParam(defaultValue = "ONE_HOUR") String interval) {

        OptionScannerConfig config = OptionScannerConfig.builder()
                .monthsToScan(months)
                .scanWeekly(weekly)
                .scanMonthly(monthly)
                .strikeDistance(distance)
                .allowedMoneyness(Set.of(OptionScannerConfig.Moneyness.ATM, OptionScannerConfig.Moneyness.ITM))
                .interval(interval)
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

    @Operation(
            summary = "Retrieve Tracked Option Records",
            description = "Fetches the historical records of options that hit RSI extremes (>=70 or <=20) or triggered hooks. Can be filtered by timeframe.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "List of tracked extreme options",
                            content = @Content(schema = @Schema(implementation = OptionPrice.class)))
            }
    )
    @GetMapping("/tracked")
    public ResponseEntity<List<OptionPrice>> getTrackedData(
            @Parameter(
                    description = "Filter by timeframe (e.g., ONE_HOUR, FIFTEEN_MINUTE). Use 'ALL' for no filter.",
                    example = "FIFTEEN_MINUTE"
            )
            @RequestParam(required = false, defaultValue = "ALL") String timeFrame) {

        List<OptionPrice> trackedHistory = optionPriceService.getTrackedHistory(timeFrame);
        return ResponseEntity.ok(trackedHistory);
    }
}