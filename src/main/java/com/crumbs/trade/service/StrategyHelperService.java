package com.crumbs.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.crumbs.trade.dto.StrategyDTO;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Thin lookup service for Strategy configuration and chart metadata.
 * No business logic — pure data retrieval and DTO conversion.
 */
@Service
public class StrategyHelperService {

    Logger logger = LoggerFactory.getLogger(StrategyHelperService.class);

    @Autowired
    StrategyRepo strategyRepo;

    @Autowired
    IndexesRepo indexesRepo;

    @Autowired
    private ObjectMapper objectMapper;

    // =========================================================
    // Strategy lookup
    // =========================================================

    public StrategyDTO getStrategyDetails(String name, String exchange) {
        if ("NIFTY_OI".equalsIgnoreCase(name) && "NSE".equalsIgnoreCase(exchange)) {
            return convertStrategyToDto(strategyRepo.findByName("NIFTY"));
        } else if ("NFO".equalsIgnoreCase(exchange)) {
            return convertStrategyToDto(strategyRepo.findByName("NIFTY"));
        } else if ("MCX".equalsIgnoreCase(exchange)) {
            return convertStrategyToDto(strategyRepo.findByName(name));
        } else if ("CPR_STRATEGY".equalsIgnoreCase(name) && "NSE".equalsIgnoreCase(exchange)) {
            StrategyDTO strategyDTO = convertStrategyToDto(strategyRepo.findByName("CPR_STRATEGY"));
            strategyDTO.setName("NIFTY");
            return strategyDTO;
        } else if ("NSE".equalsIgnoreCase(exchange)) {
            return convertStrategyToDto(strategyRepo.findByName("VIX"));
        }
        return null;
    }

    public StrategyDTO convertStrategyToDto(Strategy strategy) {
        return objectMapper.convertValue(strategy, StrategyDTO.class);
    }

    // =========================================================
    // Chart / index metadata
    // =========================================================

    public Strategy getChart(String indexName, String symbol, String live) {
        Strategy strategy = new Strategy();
        Indexes indexes = indexesRepo.findByNameAndSymbol(indexName, symbol);
        if (indexes != null) {
            strategy.setExchange(indexes.getExchange());
            strategy.setName(indexes.getName());
            strategy.setToken(indexes.getToken());
            strategy.setTradingsymbol(indexes.getSymbol());
            strategy.setLive(live);
        }
        return strategy;
    }

    public Indexes getIndexChart(String indexName, String symbol) {
        Indexes indexes = indexesRepo.findByNameAndSymbol(indexName, symbol);
        if (indexes != null) {
            indexes.setExchange(indexes.getExchange());
            indexes.setName(indexes.getName());
            indexes.setToken(indexes.getToken());
        }
        return indexes;
    }
}