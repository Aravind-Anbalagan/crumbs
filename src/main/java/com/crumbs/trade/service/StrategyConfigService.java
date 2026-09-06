package com.crumbs.trade.service;

import com.crumbs.trade.entity.StrategyConfig;
import com.crumbs.trade.repo.StrategyConfigRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StrategyConfigService {

    private final StrategyConfigRepo configRepo;

    private StrategyConfig cachedConfig;
    private long lastFetchTime = 0;
    private static final long CACHE_TTL_MS = 60000; // 1 minute cache

    public StrategyConfig getActiveConfig() {
        long now = System.currentTimeMillis();
        if (cachedConfig == null || (now - lastFetchTime) > CACHE_TTL_MS) {
            cachedConfig = configRepo.findById(1L).orElseGet(this::getFallbackConfig);
            lastFetchTime = now;
        }
        return cachedConfig;
    }

    // Fallback in case the DB table is empty
    private StrategyConfig getFallbackConfig() {
        return StrategyConfig.builder()
                .id(1L)
                .defaultInterval("FIFTEEN_MINUTE")
                .maPeriod(20)
                .rsiPeriod(14)
                .maProximity(50.0)
                .build();
    }

    // Optional: Call this from a Controller to force an immediate refresh when you update the DB
    public void invalidateCache() {
        this.cachedConfig = null;
    }
}