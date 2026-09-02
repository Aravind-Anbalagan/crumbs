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

    private static final List<String> TARGET_SYMBOLS = List.of("NIFTY", "BANKNIFTY");

    @Scheduled(cron = "0 0/15 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runAutomatedOptionScan() {
        ZoneId istZone = ZoneId.of("Asia/Kolkata");
        LocalTime now = LocalTime.now(istZone);
        LocalDate today = LocalDate.now(istZone);

        // ==========================================
        // 1. GUARD CLAUSES
        // ==========================================
        if (now.isBefore(LocalTime.of(9, 15)) || now.isAfter(LocalTime.of(15, 30))) {
            return;
        }
        if (!NSEWorkingDays.isNSEWorkingDay(today)) {
            return;
        }

        logger.info("🚀 Starting automated 15-minute Option Scanner routine...");

        OptionScannerConfig config = OptionScannerConfig.builder()
                .monthsToScan(1)
                .scanWeekly(true)
                .scanMonthly(true)
                .strikeDistance(10)
                .allowedMoneyness(Set.of(OptionScannerConfig.Moneyness.ATM, OptionScannerConfig.Moneyness.ITM))
                .build();

        // ==========================================
        // 2. EXECUTE WORKFLOW FOR EACH SYMBOL
        // ==========================================
        for (String symbol : TARGET_SYMBOLS) {
            try {
                logger.info("📊 Scanning {} options...", symbol);

                List<ScannedContractDto> contracts = scannerService.scanEligibleContractsList(symbol, config);
                contracts = indicatorService.evaluateIndicatorsForContracts(contracts);

                // This call now handles both Database persistence AND Telegram alerts!
                optionPriceService.saveExtremeContracts(contracts);

            } catch (Exception e) {
                logger.error("🛑 Scheduled scan failed for symbol {}: {}", symbol, e.getMessage());
            }
        }
        logger.info("✅ Automated scanner routine completed successfully.");
    }
}