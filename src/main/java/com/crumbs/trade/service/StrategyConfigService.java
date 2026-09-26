package com.crumbs.trade.service;

import com.crumbs.trade.entity.StrategyConfig;
import com.crumbs.trade.repo.StrategyConfigRepo;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StrategyConfigService {

    private static final Logger logger = LogManager.getLogger(StrategyConfigService.class);

    private final StrategyConfigRepo configRepo;

    private StrategyConfig cachedConfig;
    private long lastFetchTime = 0;
    private static final long CACHE_TTL_MS = 60000; // 1 minute cache

    public StrategyConfig getActiveConfig() {
        long now = System.currentTimeMillis();
        if (cachedConfig == null || (now - lastFetchTime) > CACHE_TTL_MS) {
            cachedConfig = configRepo.findById(1L).orElseGet(this::getFallbackConfig);
            lastFetchTime = now;

            // Log the newly fetched/refreshed config values
            logger.info("⚙️ Strategy Config Loaded: Interval={}, MA_Period={}, MA_Proximity={}, RSI_Period={}, RSI_Oversold={}, RSI_Overbought={}",
                    cachedConfig.getDefaultInterval(),
                    cachedConfig.getMaPeriod(),
                    cachedConfig.getMaProximity(),
                    cachedConfig.getRsiPeriod(),
                    cachedConfig.getRsiOversold(),
                    cachedConfig.getRsiOverbought());
        }
        return cachedConfig;
    }

    // Fallback in case the DB table is empty
    private StrategyConfig getFallbackConfig() {
        return StrategyConfig.builder()
                .id(1L)
                .defaultInterval("ONE_HOUR")
                .maPeriod(20)
                .rsiPeriod(14)
                .maProximity(50.0)
                .rsiOverbought(80.0)
                .rsiOversold(20.0)
                .rsiAlert("Y") // Default to Y
                .maAlert("Y")  // Default to Y
                .build();
    }

    // Optional: Call this from a Controller to force an immediate refresh when you update the DB
    public void invalidateCache() {
        this.cachedConfig = null;
        logger.info("🔄 Strategy Config cache invalidated manually.");
    }
}