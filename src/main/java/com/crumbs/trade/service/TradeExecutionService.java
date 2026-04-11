package com.crumbs.trade.service;

import com.crumbs.trade.dto.UnifiedOrderDto;
import com.crumbs.trade.entity.*;
import com.crumbs.trade.repo.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TradeExecutionService {

    private final TradeExecutionRepo srRepo;
    private final OrderRepository orderRepository;
    private final ResultVixRepo haRepo;

    public TradeExecutionService(TradeExecutionRepo srRepo, OrderRepository orderRepository, ResultVixRepo haRepo) {
        this.srRepo = srRepo;
        this.orderRepository = orderRepository;
        this.haRepo = haRepo;
    }

    public List<UnifiedOrderDto> getAllOrders(String strategy) {
        if (strategy == null) return Collections.emptyList();
        String upperStrategy = strategy.toUpperCase();

        if (upperStrategy.contains("CPR")) {
            return orderRepository.findByNameContainingIgnoreCase("CPR").stream()
                    .map(this::mapCPR).collect(Collectors.toList());
        }

        if (upperStrategy.startsWith("SHORT_STRADDLE_")) {
            return orderRepository.findAllByName(upperStrategy).stream()
                    .map(this::mapShortStraddle).collect(Collectors.toList());
        }

        return switch (upperStrategy) {
            case "SR" -> srRepo.findAll().stream().map(this::mapSR).collect(Collectors.toList());
            case "HEIKIN_PSAR" -> haRepo.findAll().stream().map(this::mapHA).collect(Collectors.toList());
            default -> Collections.emptyList();
        };
    }

    private UnifiedOrderDto mapShortStraddle(Orders o) {
        UnifiedOrderDto d = createBaseDto(o); // Fills ID, Symbol, Name, Qty
        d.setStrategyName(o.getName());
        d.setStrategyType("Option Seller");
        
        // Use OptionType (CE/PE) from DB
        d.setInstrumentType(o.getOptionType());
        
        applyTradingMath(d, o, true); // True = Seller Math
        applyTimestamps(d, o);
        return d;
    }

    private UnifiedOrderDto mapCPR(Orders o) {
        UnifiedOrderDto d = createBaseDto(o); // Fills ID, Symbol, Name, Qty
        
        // Logic for CPR Instrument Type (Signal column)
        d.setInstrumentType(o.getSignal() != null ? o.getSignal() : 
                           (o.getSymbol() != null && o.getSymbol().endsWith("PE") ? "PE" : "CE"));

        d.setStrategyType("Option Seller");
        
        applyTradingMath(d, o, true); // True = Seller Math
        applyTimestamps(d, o);
        return d;
    }

    // --- SHARED HELPERS TO PREVENT BREAKS ---

    private UnifiedOrderDto createBaseDto(Orders o) {
        UnifiedOrderDto d = new UnifiedOrderDto();
        d.setId(o.getId()); // Ensure ID is mapped first
        d.setSymbol(o.getSymbol());
        d.setStrategyName(o.getName() != null ? o.getName() : "CPR_STRATEGY");
        d.setQuantity(Math.abs(o.getQuantity()));
        
        // Strike logic
        if (o.getStrike() != null) {
            d.setStrike(o.getStrike().toString());
        } else if (o.getSymbol() != null) {
            String numericOnly = o.getSymbol().replaceAll("[^0-9]", "");
            d.setStrike(numericOnly.length() >= 5 ? numericOnly.substring(numericOnly.length() - 5) : "-");
        } else {
            d.setStrike("-");
        }
        return d;
    }

    private void applyTradingMath(UnifiedOrderDto d, Orders o, boolean isSeller) {
        d.setEntryPrice(o.getAskPrice());
        d.setExitPrice(o.getExitPrice());
        
        BigDecimal entry = o.getAskPrice();
        BigDecimal exit = o.getExitPrice();
        BigDecimal pts = BigDecimal.ZERO;

        if (entry != null && exit != null && exit.compareTo(BigDecimal.ZERO) > 0) {
            // Seller: Entry - Exit | Buyer: Exit - Entry
            pts = isSeller ? entry.subtract(exit) : exit.subtract(entry);
        }
        
        d.setPoints(pts);
        
        // Use DB 'pl' if exists, else calculate: points * qty
        if (o.getPl() != null && o.getPl().compareTo(BigDecimal.ZERO) != 0) {
            d.setPnl(o.getPl());
        } else {
            d.setPnl(pts.multiply(BigDecimal.valueOf(d.getQuantity())));
        }
    }

    private void applyTimestamps(UnifiedOrderDto d, Orders o) {
        d.setEntryTime(o.getCreatedOn() != null ? 
                      o.getCreatedOn().toString().replace("T", " ").substring(0, 19) : "N/A");
        
        if (o.getActive() == 0 && o.getClosedOn() != null) {
            d.setExitTime(o.getClosedOn().toString().replace("T", " ").substring(0, 19));
            d.setStatus("CLOSED");
        } else {
            d.setExitTime("ACTIVE");
            d.setStatus("OPEN");
        }
    }

    // Standard mappings for other tables
    private UnifiedOrderDto mapSR(TradeExecution e) {
        UnifiedOrderDto d = new UnifiedOrderDto();
        d.setId(e.getId());
        d.setStrategyName("SR");
        d.setSymbol(e.getSymbol());
        d.setInstrumentType(e.getTradeType());
        d.setStrike(e.getLevelValue() != null ? e.getLevelValue().toString() : "-");
        d.setStrategyType(e.getTradeType() != null && e.getTradeType().contains("SELL") ? "SELLER" : "BUYER");
        d.setEntryPrice(e.getEntryPrice());
        d.setExitPrice(e.getExitPrice());
        d.setPnl(e.getPnl());
        d.setStatus(e.getStatus());
        return d;
    }

    private UnifiedOrderDto mapHA(ResultVix r) {
        UnifiedOrderDto d = new UnifiedOrderDto();
        d.setId(r.getId());
        d.setStrategyName("HEIKIN_PSAR");
        d.setSymbol(r.getSymbol());
        d.setStatus(r.isActive() ? "OPEN" : "CLOSED");
        return d;
    }
}