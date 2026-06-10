package com.crumbs.trade.scheduler;

import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.DominanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class DominanceScheduler {

    private final DominanceService dominanceService;
    private final StrategyRepo strategyRepo;

    private static final String STRATEGY_NAME = "DOMINANCE";
    private static final String ACTIVE_STATUS = "Y";

    /**
     * Executes the dominance check every minute during weekdays (MON-FRI).
     * Timezone set to Asia/Kolkata.
     */
    //@Scheduled(cron = "0 * * * * MON-FRI", zone = "Asia/Kolkata")
    public void runDominanceCheck() {
        if (!isStrategyActive(STRATEGY_NAME)) {
            log.debug("Dominance check skipped: Strategy '{}' is inactive.", STRATEGY_NAME);
            return;
        }

        try {
            log.info("Starting Dominance process check...");
            dominanceService.process();
            log.info("Dominance process completed successfully.");
        } catch (Exception e) {
            log.error("Error occurred during dominance process execution: {}", e.getMessage(), e);
        }
    }

    private boolean isStrategyActive(String strategy) {
        return Optional.ofNullable(strategyRepo.findByName(strategy))
                .map(s -> ACTIVE_STATUS.equalsIgnoreCase(s.getActive()))
                .orElse(false);
    }
}