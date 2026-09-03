package com.crumbs.trade.scheduler;

import com.crumbs.trade.builder.OptionScannerConfig;
import com.crumbs.trade.dto.ScannedContractDto;
import com.crumbs.trade.service.OptionChainScannerService;
import com.crumbs.trade.service.OptionIndicatorService;
import com.crumbs.trade.service.OptionPriceService;
import com.crumbs.trade.utility.NSEWorkingDays;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class OptionPriceScannerScheduler {

    private static final Logger logger = LogManager.getLogger(OptionPriceScannerScheduler.class);

    private final OptionChainScannerService scannerService;
    private final OptionIndicatorService indicatorService;
    private final OptionPriceService optionPriceService;

    // Define target lists separately
    private static final List<String> NSE_SYMBOLS = List.of("NIFTY", "BANKNIFTY");
    private static final List<String> MCX_SYMBOLS = List.of("CRUDEOILM", "GOLDM");

    // ==========================================
    // 1. NSE SCHEDULER (9:00 AM to 3:59 PM)
    // ==========================================
    @Scheduled(cron = "0 0/15 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runNseAutomatedScan() {
        ZoneId istZone = ZoneId.of("Asia/Kolkata");
        LocalTime now = LocalTime.now(istZone);
        LocalDate today = LocalDate.now(istZone);

        // NSE Guard Clauses
        if (now.isBefore(LocalTime.of(9, 15)) || now.isAfter(LocalTime.of(15, 30))) {
            return;
        }
        if (!NSEWorkingDays.isNSEWorkingDay(today)) {
            return;
        }

        logger.info("🚀 [NSE] Starting 15-min Option Scanner...");
        executeScanWorkflow(NSE_SYMBOLS, "FIFTEEN_MINUTE");
    }

    // ==========================================
    // 2. MCX SCHEDULER (9:00 AM to 11:59 PM)
    // ==========================================
    @Scheduled(cron = "0 0/15 9-23 * * MON-FRI", zone = "Asia/Kolkata")
    public void runMcxAutomatedScan() {
        ZoneId istZone = ZoneId.of("Asia/Kolkata");
        LocalTime now = LocalTime.now(istZone);

        // MCX Guard Clauses (Usually 9:00 AM to 11:30 PM or 11:55 PM)
        if (now.isBefore(LocalTime.of(9, 0)) || now.isAfter(LocalTime.of(23, 30))) {
            return;
        }

        // Note: We deliberately SKIP the NSE holiday check here because
        // MCX is often open for the evening session on NSE holidays!

        logger.info("🛢️ [MCX] Starting 15-min Option Scanner...");
        executeScanWorkflow(MCX_SYMBOLS, "FIFTEEN_MINUTE");
    }

    // ==========================================
    // SHARED WORKFLOW ENGINE
    // ==========================================
    private void executeScanWorkflow(List<String> symbols, String interval) {
        OptionScannerConfig config = OptionScannerConfig.builder()
                .monthsToScan(1)
                .scanWeekly(true)
                .scanMonthly(true)
                .strikeDistance(10)
                .interval(interval)
                .allowedMoneyness(Set.of(OptionScannerConfig.Moneyness.ATM, OptionScannerConfig.Moneyness.ITM))
                .build();

        for (String symbol : symbols) {
            try {
                logger.debug("📊 Scanning {} options...", symbol);

                // 1. Scan Chain
                List<ScannedContractDto> contracts = scannerService.scanEligibleContractsList(symbol, config);

                // 2. Evaluate Indicators
                contracts = indicatorService.evaluateIndicatorsForContracts(contracts, config.getInterval());

                // 3. Save to DB & Notify Telegram
                optionPriceService.saveExtremeContracts(contracts);

            } catch (Exception e) {
                logger.error("🛑 Scheduled scan failed for symbol {}: {}", symbol, e.getMessage());
            }
        }
    }
}