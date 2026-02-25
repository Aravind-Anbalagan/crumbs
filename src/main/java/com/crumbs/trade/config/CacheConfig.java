package com.crumbs.trade.config;

import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(
                "indexByToken",
                "indexBySymbol",
                "indexByNameSymbol",
                "indexByNameSymbolExchange",
                "indexByNameExchange"
        );

        manager.setCaffeine(
            Caffeine.newBuilder()
                .maximumSize(5_000)
                .expireAfterWrite(8, TimeUnit.HOURS)
                .recordStats()
        );

        return manager;
    }
}