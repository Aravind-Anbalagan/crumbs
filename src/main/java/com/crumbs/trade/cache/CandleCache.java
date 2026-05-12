package com.crumbs.trade.cache;

import com.crumbs.trade.entity.PricesIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CandleCache {

    private static final Logger logger = LoggerFactory.getLogger(CandleCache.class);

    private final Map<String, List<PricesIndex>> cache     = new ConcurrentHashMap<>();
    private final Map<String, LocalDate>         cacheDate = new ConcurrentHashMap<>();

    public boolean isLoadedToday(String name) {
        if (name == null || name.isEmpty()) {
            //logger.warn("⚠️ [CACHE] isLoadedToday called with null/empty name");
            return false;
        }
        LocalDate loaded = cacheDate.get(name);
        if (loaded == null) {
            //logger.info("📭 [CACHE] No cache found for {} — full load required", name);
            return false;
        }
        boolean result = loaded.equals(LocalDate.now());
        //logger.info("🔍 [CACHE] isLoadedToday({}) = {} | cached={} | today={}", name, result, loaded, LocalDate.now());
        return result;
    }

    public List<PricesIndex> get(String name) {
        if (name == null || name.isEmpty()) return new ArrayList<>();
        List<PricesIndex> candles = cache.getOrDefault(name, new ArrayList<>());
        //logger.debug("📤 [CACHE] get({}) → {} candles", name, candles.size());
        return candles;
    }

    public void loadAll(String name, List<PricesIndex> candles) {
        if (name == null || candles == null) return;
        cache.put(name, new ArrayList<>(candles));
        cacheDate.put(name, LocalDate.now());
        //logger.info("✅ [CACHE] loadAll({}) → {} candles cached for today", name, candles.size());
    }

    public void addOrUpdateLatest(String name, PricesIndex latest) {
        if (name == null || latest == null) {
            //logger.warn("⚠️ [CACHE] addOrUpdateLatest called with null values");
            return;
        }
        List<PricesIndex> candles = cache.getOrDefault(name, new ArrayList<>());

        if (!candles.isEmpty()) {
            PricesIndex last = candles.get(candles.size() - 1);
            if (last.getTimestamp() != null && last.getTimestamp().equals(latest.getTimestamp())) {
                candles.set(candles.size() - 1, latest);
                //logger.debug("🔄 [CACHE] Updated existing candle for {} @ {}", name, latest.getTimestamp());
            } else {
                candles.add(latest);
                //logger.debug("➕ [CACHE] Added new candle for {} @ {}", name, latest.getTimestamp());
            }
        } else {
            candles.add(latest);
            //logger.debug("➕ [CACHE] First candle added for {} @ {}", name, latest.getTimestamp());
        }
        cache.put(name, candles);
    }

    public void clear(String name) {
        cache.remove(name);
        cacheDate.remove(name);
        //logger.info("🧹 [CACHE] Cleared cache for {}", name);
    }

    public void clearAll() {
        cache.clear();
        cacheDate.clear();
        //logger.info("🧹 [CACHE] All caches cleared");
    }

    public int size(String name) {
        return cache.getOrDefault(name, new ArrayList<>()).size();
    }

    public boolean isEmpty(String name) {
        return cache.getOrDefault(name, new ArrayList<>()).isEmpty();
    }
}