package com.crumbs.trade.scheduler;

import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.MarketTrendChartService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MarketTrendScheduler {

    private static final Logger log = LoggerFactory.getLogger(MarketTrendScheduler.class);

    private static final String NIFTY_PLACEHOLDER  = "MARKET_TREND_NIFTY";
    private static final String CRUDE_PLACEHOLDER  = "MARKET_TREND_CRUDEOIL";
    private static final String SYMBOL_NIFTY       = "SAMCO_NIFTY";
    private static final String SYMBOL_CRUDE       = "SAMCO_CRUDEOIL";
    private static final String TIME_ZONE          = "Asia/Kolkata";

    private final MarketTrendChartService chartService;
    private final StrategyRepo strategyRepo;

    // =========================================================
    // 📅 NIFTY SCHEDULE (9:15 AM - 3:30 PM) - Every Minute
    // =========================================================
    @Schedules({
        @Scheduled(cron = "0 15-59 9 * * MON-FRI", zone = TIME_ZONE),  // 09:15 to 09:59
        @Scheduled(cron = "0 * 10-14 * * MON-FRI", zone = TIME_ZONE),   // 10:00 to 14:59
        @Scheduled(cron = "0 0-30 15 * * MON-FRI", zone = TIME_ZONE)    // 15:00 to 15:30
    })
    public void scheduleNifty() {
        if (isSymbolActive(NIFTY_PLACEHOLDER)) {
            log.info("⏰ [NIFTY] Running minute analysis...");
            chartService.evaluateStrategy(SYMBOL_NIFTY, ExchangeType.NSE_FO);
        }
    }

    // =========================================================
    // 📅 CRUDE OIL SCHEDULE (4:00 PM - 11:30 PM) - Every Minute
    // =========================================================
    @Schedules({
        @Scheduled(cron = "0 * 16-22 * * MON-FRI", zone = TIME_ZONE),  // 16:00 to 22:59
        @Scheduled(cron = "0 0-30 23 * * MON-FRI", zone = TIME_ZONE)   // 23:00 to 23:30
    })
    public void scheduleCrude() {
        if (isSymbolActive(CRUDE_PLACEHOLDER)) {
            log.info("⏰ [CRUDEOIL] Running minute analysis...");
            chartService.evaluateStrategy(SYMBOL_CRUDE, ExchangeType.MCX_FO);
        }
    }

    // =========================================================
    // 🛡️ GATEKEEPER LOGIC
    // =========================================================
    private boolean isSymbolActive(String placeholderName) {
        try {
            Strategy strategy = strategyRepo.findByName(placeholderName);
            return strategy != null && "Y".equalsIgnoreCase(strategy.getActive());
        } catch (Exception e) {
            log.error("❌ Error fetching strategy status for {}: {}", placeholderName, e.getMessage());
            return false;
        }
    }
}