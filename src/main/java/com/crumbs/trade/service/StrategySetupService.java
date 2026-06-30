package com.crumbs.trade.service;

import com.crumbs.trade.dto.StrategySummaryDTO;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.StrategyRepo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class StrategySetupService {

    @Autowired
    private StrategyRepo repository;

    // 1. Updated to return the DTO
    public List<StrategySummaryDTO> getAllStrategies(String active, String name) {
        List<Strategy> strategies;
        
        if (active != null && name != null) {
            strategies = repository.findByActiveAndNameContainingIgnoreCase(active, name);
        } else if (active != null) {
            strategies = repository.findByActive(active);
        } else if (name != null) {
            strategies = repository.findByNameContainingIgnoreCase(name);
        } else {
            strategies = repository.findAll();
        }

        // Convert the Entities to DTOs
        return strategies.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

 
   // Helper method for mapping
    private StrategySummaryDTO convertToDTO(Strategy strategy) {
        return new StrategySummaryDTO(
            strategy.getId(),
            strategy.getName(),
            strategy.getActive(),
            strategy.getExchange(),
            strategy.getExpiry(),
            strategy.getLive(),
            strategy.getSymbol(),
            strategy.getToken(),
            strategy.getTradingsymbol(),
            strategy.getQuantity()
        );
    }

    // 2. Added: Fetch a single full strategy for the Edit form
    public Strategy getStrategyById(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new RuntimeException("Strategy not found with id: " + id));
    }

    public Strategy updateStrategy(Long id, Strategy strategyDetails) {
        Strategy strategy = repository.findById(id)
            .orElseThrow(() -> new RuntimeException("Strategy not found with id: " + id));
        
        strategy.setName(strategyDetails.getName());
        strategy.setActive(strategyDetails.getActive());
        strategy.setQuantity(strategyDetails.getQuantity());
        strategy.setSlPoints(strategyDetails.getSlPoints());
        strategy.setTargetPoints(strategyDetails.getTargetPoints());
        strategy.setExchange(strategyDetails.getExchange());
        strategy.setSymbol(strategyDetails.getSymbol());
        strategy.setToken(strategyDetails.getToken());
        strategy.setTradingsymbol(strategyDetails.getTradingsymbol());
        strategy.setLive(strategyDetails.getLive());
        // ... update remaining fields ...
        
        return repository.save(strategy);
    }
}