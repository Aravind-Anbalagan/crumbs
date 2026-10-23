package com.crumbs.trade.cache;

import com.crumbs.trade.entity.PricesIndex;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CandleCache {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final Map<String, List<PricesIndex>> cache     = new ConcurrentHashMap<>();
    private final Map<String, LocalDate>         cacheDate = new ConcurrentHashMap<>();

    /** One place to build the key (used in step 7). */
    public static String cacheKey(String name, String symbol, String timeFrame) {
        return name + "_" + symbol + "_" + timeFrame;
    }

    public boolean isLoadedToday(String name) {
        if (name == null || name.isEmpty()) return false;
        LocalDate loaded = cacheDate.get(name);
        return loaded != null && loaded.equals(LocalDate.now(IST));
    }

    public synchronized List<PricesIndex> get(String name) {
        if (name == null || name.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(cache.getOrDefault(name, new ArrayList<>()));   // copy, not the live list
    }

    public synchronized void loadAll(String name, List<PricesIndex> candles) {
        if (name == null || candles == null) return;
        cache.put(name, new ArrayList<>(candles));
        cacheDate.put(name, LocalDate.now(IST));
    }

    public synchronized void addOrUpdateLatest(String name, PricesIndex latest) {
        if (name == null || latest == null || latest.getTimestamp() == null) return;
        List<PricesIndex> list = cache.computeIfAbsent(name, k -> new ArrayList<>());
        Instant t = Instant.parse(latest.getTimestamp());

        int i = list.size() - 1;
        while (i >= 0) {
            Instant ti = Instant.parse(list.get(i).getTimestamp());
            if (ti.equals(t)) { list.set(i, latest); return; }   // same candle: replace
            if (ti.isBefore(t)) break;                           // found insert point
            i--;
        }
        list.add(i + 1, latest);                                 // sorted insert, never duplicates
    }

    public synchronized void clear(String name) {
        cache.remove(name);
        cacheDate.remove(name);
    }

    public synchronized void clearAll() {
        cache.clear();
        cacheDate.clear();
    }

    public synchronized int size(String name) {
        return cache.getOrDefault(name, new ArrayList<>()).size();
    }

    public synchronized boolean isEmpty(String name) {
        return cache.getOrDefault(name, new ArrayList<>()).isEmpty();
    }
}