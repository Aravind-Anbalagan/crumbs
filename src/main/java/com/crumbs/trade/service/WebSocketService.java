package com.crumbs.trade.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.StrategyRepo;

@Service
public class WebSocketService {
	private static final Logger log = LoggerFactory.getLogger(WebSocketService.class);
	
	@Autowired
	StrategyRepo strategyRepo;
	

    public Map<String, String> getInstrumentKey(String name) {

        Strategy strategy = strategyRepo
                .findByNameIgnoreCase(name)
                .orElseThrow(() ->
                        new IllegalArgumentException("Unknown instrument: " + name)
                );

        String key = strategy.getExchange() + "_" + strategy.getToken();

        return Map.of(
                "name", strategy.getName().toUpperCase(),
                "key", key
        );
    }
	
}
