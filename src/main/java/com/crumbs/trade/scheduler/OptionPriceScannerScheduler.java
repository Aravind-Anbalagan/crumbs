package com.crumbs.trade.scheduler;

import com.crumbs.trade.builder.OptionScannerConfig;
import com.crumbs.trade.dto.ScannedContractDto;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.entity.StrategyConfig;
import com.crumbs.trade.repo.NiftyRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.OptionChainScannerService;
import com.crumbs.trade.service.OptionIndicatorService;
import com.crumbs.trade.service.OptionPriceOrderService;
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
    private final OptionPriceOrderService optionPriceOrderService;
    private final StrategyRepo strategyRepo;

    private static final List<String> MCX_SYMBOLS = List.of("CRUDEOILM", "GOLDM","SILVERM");
    private static final List<String> NSE_SYMBOLS = List.of("NIFTY", "BANKNIFTY");

    // ==========================================
    // PERSISTENT DAILY STATE
    // ==========================================
    // Map tracks symbol -> locked ATM strike. (0.0 means not locked yet)
    private final Map<String, Double> dailyLockedStrikes = new ConcurrentHashMap<>();
    private LocalDate trackingDate = null;

    // ==========================================
    // STRATEGY TOGGLE CHECK
    // ==========================================
    private boolean isScannerStrategyActive() {
        try {
            Strategy strat = strategyRepo.findByName("OPTION_PRICE_SCANNER");
            return strat != null && "Y".equalsIgnoreCase(strat.getActive());
        } catch (Exception e) {
            logger.error("⚠️ Could not verify OPTION_PRICE_SCANNER strategy status: {}", e.getMessage());
            return false;
        }
    }

    // ==========================================
    // DYNAMIC SYMBOL COLLECTOR (PERSISTENT)
    // ==========================================
    private List<String> getDailySymbolsToScan(LocalDate today, BigDecimal threshold) {
        // 1. Reset list if it is a new trading day
        if (trackingDate == null || !trackingDate.equals(today)) {
            dailyLockedStrikes.clear();
            // Initialize default indices with 0.0 (unlocked state)
            NSE_SYMBOLS.forEach(sym -> dailyLockedStrikes.put(sym, 0.0));
            trackingDate = today;
            logger.info("🔄 Resetting daily scanning symbols list for new trading day: {}", today);
        }

        // 2. Fetch all names to check against threshold
        List<String> allNames = niftyRepo.getAllNames();
        if (allNames != null) {
            for (String symbol : allNames) {
                try {
                    // Optimization: Skip DB check if the symbol is already in our daily map
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

        return new ArrayList<>(dailyLockedStrikes.keySet());
    }

    // ==========================================
    // 1. NSE 1-HOUR SCHEDULER
    // ==========================================
    @Scheduled(cron = "0 30 9-13 * * MON-FRI", zone = "Asia/Kolkata")
    public void runNseHourlyScan() {
        if (!isScannerStrategyActive()) return;

        ZoneId istZone = ZoneId.of("Asia/Kolkata");
        LocalTime now = LocalTime.now(istZone);
        LocalDate today = LocalDate.now(istZone);

        if (now.isBefore(LocalTime.of(9, 15)) || now.isAfter(LocalTime.of(15, 30))) return;
        if (!NSEWorkingDays.isNSEWorkingDay(today)) return;

        logger.info("🚀 [NSE] Starting 1-Hour Option Scanner...");

        StrategyConfig activeConfig = configService.getActiveConfig();
        BigDecimal threshold = activeConfig.getMinPercentageChange() != null
                ? activeConfig.getMinPercentageChange()
                : BigDecimal.valueOf(5.0);

        List<String> symbolsToScan = getDailySymbolsToScan(today, threshold);

        logger.info("✅ [NSE] Scanning {} symbols (Default indices + stocks that breached {}% threshold today).",
                symbolsToScan.size(), threshold);

        String interval = activeConfig.getDefaultInterval() != null
                ? activeConfig.getDefaultInterval()
                : "ONE_HOUR";

        executeScanWorkflow(symbolsToScan, interval);
    }

    // ==========================================
    // 2. MCX 1-HOUR SCHEDULER
    // ==========================================
    @Scheduled(cron = "0 0 16-22 * * MON-FRI", zone = "Asia/Kolkata")
    public void runMcxHourlyScan() {
        if (!isScannerStrategyActive()) return;

        ZoneId istZone = ZoneId.of("Asia/Kolkata");
        LocalTime now = LocalTime.now(istZone);

        if (now.isBefore(LocalTime.of(16, 0)) || now.isAfter(LocalTime.of(23, 30))) return;

        logger.info("🛢️ [MCX] Starting 1-Hour Option Scanner...");
        executeScanWorkflow(MCX_SYMBOLS, "ONE_HOUR");
    }

    // ==========================================
    // 3. AUTO SQUARE OFF AT 3:15 PM
    // ==========================================
    @Scheduled(cron = "0 15 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void squareOffAllTrades315PM() {
        if (!isScannerStrategyActive()) return;

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        if (!NSEWorkingDays.isNSEWorkingDay(today)) return;

        logger.info("⏰ [SQUARE-OFF] 3:15 PM Reached. Initiating forced market exit for all open AI_BIAS trades...");
        optionPriceOrderService.closeAllOpenTrades();
    }

    // ==========================================
    // 4. FAST-LOOP RISK MONITOR (NEW)
    // ==========================================
    @Scheduled(fixedDelay = 5000)
    public void runAiTradeRiskMonitor() {
        if (!isScannerStrategyActive()) return;
        optionPriceService.monitorActiveAiTrades(null);
    }

    // ==========================================
    // SHARED WORKFLOW ENGINE
    // ==========================================
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

                logger.debug("📊 Scanning {} options on {} timeframe...", symbol, interval);

                List<ScannedContractDto> contracts = scannerService.scanEligibleContractsList(symbol, config);

                if (contracts != null && !contracts.isEmpty()) {
                    // Lock the strike on the very first scan if it hasn't been locked yet
                    if (lockedStrike == 0.0) {
                        ScannedContractDto atmContract = contracts.stream()
                                .filter(c -> c.getMoneyness() == OptionScannerConfig.Moneyness.ATM)
                                .findFirst()
                                .orElse(contracts.get(0));
                        dailyLockedStrikes.put(symbol, atmContract.getStrike());
                        logger.info("🔒 Locked Anchor ATM Strike for {} at {}", symbol, atmContract.getStrike());
                    }

                    contracts = indicatorService.evaluateIndicatorsForContracts(contracts, config.getInterval());
                    optionPriceService.saveExtremeContracts(contracts);
                }

            } catch (Exception e) {
                logger.error("🛑 Scheduled scan failed for symbol {} on {}: {}", symbol, interval, e.getMessage());
            }
        }
    }

    // ==========================================
    // 5. 1-MINUTE VWAP SCHEDULER (ATM ONLY)
    // ==========================================
    @Scheduled(cron = "5 * 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runNseMinuteVwapScan() {
        if (!isScannerStrategyActive()) return;

        ZoneId istZone = ZoneId.of("Asia/Kolkata");
        LocalTime now = LocalTime.now(istZone);
        LocalDate today = LocalDate.now(istZone);

        if (now.isBefore(LocalTime.of(9, 15)) || now.isAfter(LocalTime.of(15, 30))) return;
        if (!NSEWorkingDays.isNSEWorkingDay(today)) return;

        logger.info("⏱️ [VWAP] Starting 1-Minute ATM Option Scanner...");

        StrategyConfig activeConfig = configService.getActiveConfig();
        BigDecimal threshold = activeConfig.getMinPercentageChange() != null
                ? activeConfig.getMinPercentageChange()
                : BigDecimal.valueOf(5.0);

        List<String> symbolsToScan = getDailySymbolsToScan(today, threshold);
        executeAtmScanWorkflow(symbolsToScan, "ONE_MINUTE");
    }

    // ==========================================
    // DEDICATED VWAP ENGINE (RESTRICTED TO ATM)
    // ==========================================
    private void executeAtmScanWorkflow(List<String> symbols, String interval) {
        for (String symbol : symbols) {
            try {
                Double lockedStrike = dailyLockedStrikes.getOrDefault(symbol, 0.0);

                OptionScannerConfig config = OptionScannerConfig.builder()
                        .monthsToScan(1)
                        .scanWeekly(true)
                        .scanMonthly(true)
                        .strikeDistance(0)
                        .interval(interval)
                        .allowedMoneyness(Set.of(OptionScannerConfig.Moneyness.ATM))
                        .targetStrike(lockedStrike > 0 ? lockedStrike : null)
                        .build();

                logger.debug("📊 Scanning 1-Min ATM VWAP for {}...", symbol);

                List<ScannedContractDto> contracts = scannerService.scanEligibleContractsList(symbol, config);

                if (contracts != null && !contracts.isEmpty()) {
                    // Lock the strike on the very first scan if it hasn't been locked yet
                    if (lockedStrike == 0.0) {
                        double foundStrike = contracts.get(0).getStrike();
                        dailyLockedStrikes.put(symbol, foundStrike);
                        logger.info("🔒 Locked Anchor ATM Strike for VWAP on {} at {}", symbol, foundStrike);
                    }

                    contracts = indicatorService.evaluateIndicatorsForContracts(contracts, config.getInterval());
                    optionPriceService.saveVwapPairData(contracts);
                }

            } catch (Exception e) {
                logger.error("🛑 Scheduled VWAP scan failed for symbol {} on {}: {}", symbol, interval, e.getMessage());
            }
        }
    }
}