package com.crumbs.trade.service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.crumbs.trade.dto.TrendChartResponse;

@Component
public class StraddleTrendCache {

    private final Map<String, TrendChartResponse> store = new ConcurrentHashMap<>();
    private volatile LocalDate cachedDate;
    private volatile boolean allInstrumentsLoaded = false;

    public void put(TrendChartResponse response) {
        if (response == null) return;
        if (cachedDate != null && !cachedDate.equals(response.tradeDate())) {
            store.clear();
            allInstrumentsLoaded = false;
        }
        cachedDate = response.tradeDate();
        store.put(key(response.name(), response.expiry()), response);
    }

    public Optional<TrendChartResponse> get(String name, String expiry) {
        return Optional.ofNullable(store.get(key(name, expiry)));
    }

    public Collection<TrendChartResponse> getAll() {
        return store.values();
    }

    public LocalDate getCachedDate() {
        return cachedDate;
    }

    public boolean isEmpty() {
        return store.isEmpty();
    }

    public boolean isAllInstrumentsLoaded() {
        return allInstrumentsLoaded && !store.isEmpty();
    }

    public void markAllInstrumentsLoaded(boolean loaded) {
        this.allInstrumentsLoaded = loaded;
    }

    public void clearForNewDay(LocalDate newDate) {
        store.clear();
        cachedDate = newDate;
        allInstrumentsLoaded = false;
    }

    private String key(String name, String expiry) {
        return name.toUpperCase() + "|" + expiry.toUpperCase();
    }
}