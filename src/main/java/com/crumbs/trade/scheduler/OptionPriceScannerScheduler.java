package com.crumbs.trade.scheduler;

import com.crumbs.trade.builder.OptionScannerConfig;
import com.crumbs.trade.dto.ScannedContractDto;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.entity.StrategyConfig;
import com.crumbs.trade.repo.NiftyRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.OptionChainScannerService;
import com.crumbs.trade.service.OptionIndicatorService;
import com.crumbs.trade.service.OptionPriceService;
import com.crumbs.trade.service.StrategyConfigService;
import com.crumbs.trade.utility.NSEWorkingDays;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class OptionPriceScannerScheduler {

    private static final Logger logger = LogManager.getLogger(OptionPriceScannerScheduler.class);

    private final OptionChainScannerService scannerService;
    private final OptionIndicatorService indicatorService;
    private final OptionPriceService optionPriceService;
    private final StrategyConfigService configService;
    private final NiftyRepo niftyRepo;
    private final StrategyRepo strategyRepo;

    // ==========================================
    // ⚙️ SCANNER CONFIGURATION TOGGLES
    // ==========================================
    // Set to true if you want to include dynamic F&O stocks that hit the % threshold
    private static final boolean SCAN_DYNAMIC_FNO_STOCKS = false;

    private static final List<String> MCX_SYMBOLS = List.of("CRUDEOILM", "GOLDM","SILVERM");
    private static final List<String> NSE_SYMBOLS = List.of("NIFTY", "BANKNIFTY");

    // Persistent Daily State
    private final Map<String, Double> dailyLockedStrikes = new ConcurrentHashMap<>();
    private LocalDate trackingDate = null;

    private boolean isScannerStrategyActive() {
        try {
            Strategy strat = strategyRepo.findByName("OPTION_PRICE_SCANNER");
            return strat != null && "Y".equalsIgnoreCase(strat.getActive());
        } catch (Exception e) {
            logger.error("⚠️ Could not verify OPTION_PRICE_SCANNER strategy status: {}", e.getMessage());
            return false;
        }
    }

    private List<String> getDailySymbolsToScan(LocalDate today, BigDecimal threshold) {
        if (trackingDate == null || !trackingDate.equals(today)) {
            dailyLockedStrikes.clear();

            // Always add predefined index symbols
            NSE_SYMBOLS.forEach(sym -> dailyLockedStrikes.put(sym, 0.0));

            trackingDate = today;
            logger.info("🔄 Resetting daily scanning symbols list for new trading day: {}. Dynamic F&O Scanning enabled: {}", today, SCAN_DYNAMIC_FNO_STOCKS);
        }

        // Only scan database for F&O stocks if the flag is TRUE
        if (SCAN_DYNAMIC_FNO_STOCKS) {
            List<String> allNames = niftyRepo.getAllNames();
            if (allNames != null) {
                for (String symbol : allNames) {
                    try {
                        if (!dailyLockedStrikes.containsKey(symbol)) {
                            Optional<com.crumbs.trade.entity.Nifty> stockOpt = niftyRepo.findByName(symbol);
                            if (stockOpt.isPresent() && stockOpt.get().getPercentageChange() != null) {
                                BigDecimal currentChange = stockOpt.get().getPercentageChange();
                                if (currentChange.abs().compareTo(threshold) >= 0) {
                                    logger.info("🎯 Symbol {} touched threshold {}%. Adding to persistent daily scan list.", symbol, threshold);
                                    dailyLockedStrikes.put(symbol, 0.0);
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.error("⚠️ Error evaluating percentage threshold for symbol '{}': {}", symbol, e.getMessage());
                    }
                }
            }
        }

        return new ArrayList<>(dailyLockedStrikes.keySet());
    }

    @Scheduled(cron = "0 30 9-13 * * MON-FRI", zone = "Asia/Kolkata")
    public void runNseHourlyScan() {
        if (!isScannerStrategyActive()) return;

        ZoneId istZone = ZoneId.of("Asia/Kolkata");
        LocalTime now = LocalTime.now(istZone);
        LocalDate today = LocalDate.now(istZone);

        if (now.isBefore(LocalTime.of(9, 15)) || now.isAfter(LocalTime.of(15, 30))) return;
        if (!NSEWorkingDays.isNSEWorkingDay(today)) return;

        StrategyConfig activeConfig = configService.getActiveConfig();
        BigDecimal threshold = activeConfig.getMinPercentageChange() != null
                ? activeConfig.getMinPercentageChange() : BigDecimal.valueOf(5.0);

        List<String> symbolsToScan = getDailySymbolsToScan(today, threshold);

        String interval = activeConfig.getDefaultInterval() != null
                ? activeConfig.getDefaultInterval() : "ONE_HOUR";

        executeScanWorkflow(symbolsToScan, interval);
    }

    @Scheduled(cron = "0 0 16-22 * * MON-FRI", zone = "Asia/Kolkata")
    public void runMcxHourlyScan() {
        if (!isScannerStrategyActive()) return;

        ZoneId istZone = ZoneId.of("Asia/Kolkata");
        LocalTime now = LocalTime.now(istZone);

        if (now.isBefore(LocalTime.of(16, 0)) || now.isAfter(LocalTime.of(23, 30))) return;
        executeScanWorkflow(MCX_SYMBOLS, "ONE_HOUR");
    }

    private void executeScanWorkflow(List<String> symbols, String interval) {
        for (String symbol : symbols) {
            try {
                Double lockedStrike = dailyLockedStrikes.getOrDefault(symbol, 0.0);

                OptionScannerConfig config = OptionScannerConfig.builder()
                        .monthsToScan(1)
                        .scanWeekly(true)
                        .scanMonthly(true)
                        .strikeDistance(10)
                        .interval(interval)
                        .allowedMoneyness(Set.of(OptionScannerConfig.Moneyness.ATM, OptionScannerConfig.Moneyness.ITM))
                        .targetStrike(lockedStrike > 0 ? lockedStrike : null)
                        .build();

                List<ScannedContractDto> contracts = scannerService.scanEligibleContractsList(symbol, config);

                if (contracts != null && !contracts.isEmpty()) {
                    if (lockedStrike == 0.0) {
                        ScannedContractDto atmContract = contracts.stream()
                                .filter(c -> c.getMoneyness() == OptionScannerConfig.Moneyness.ATM)
                                .findFirst()
                                .orElse(contracts.get(0));
                        dailyLockedStrikes.put(symbol, atmContract.getStrike());
                    }

                    contracts = indicatorService.evaluateIndicatorsForContracts(contracts, config.getInterval());
                    optionPriceService.saveExtremeContracts(contracts);
                }
            } catch (Exception e) {
                logger.error("🛑 Scheduled scan failed for symbol {} on {}: {}", symbol, interval, e.getMessage());
            }
        }
    }
}