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
    
    // Inject RiskService to access the live RAM Cache
 
    private final MonitorOrderService riskService;
    public TradeExecutionService(TradeExecutionRepo srRepo, 
                                 OrderRepository orderRepository, 
                                 ResultVixRepo haRepo,
                                 MonitorOrderService riskService) {
        this.srRepo = srRepo;
        this.orderRepository = orderRepository;
        this.haRepo = haRepo;
        this.riskService = riskService;
    }

    /**
     * Updated unified method. If 'strategy' is null or empty, it collects EVERYTHING
     * from all tables so the UI can dynamically generate tabs for whatever is running.
     */
    public List<UnifiedOrderDto> getAllOrders(String strategy) {
        List<UnifiedOrderDto> allUnifiedOrders = new ArrayList<>();
        
        boolean fetchAll = (strategy == null || strategy.trim().isEmpty());
        String upperStrategy = fetchAll ? "" : strategy.toUpperCase();

        // 1. Process main ORDERS Table
        if (fetchAll) {
            // Fetch everything available if no specific filter is provided
            List<Orders> allDbOrders = orderRepository.findAll();
            for (Orders o : allDbOrders) {
                allUnifiedOrders.add(mapGenericOrder(o));
            }
        } else {
            // Maintain exact custom query routing if a specific string is requested
            if (upperStrategy.contains("CPR")) {
                allUnifiedOrders.addAll(orderRepository.findByNameContainingIgnoreCase("CPR").stream()
                        .map(this::mapGenericOrder).collect(Collectors.toList()));
            } else {
                allUnifiedOrders.addAll(orderRepository.findAllByName(upperStrategy).stream()
                        .map(this::mapGenericOrder).collect(Collectors.toList()));
            }
        }

        // 2. Process Support Resistance (SR) Table
        if (fetchAll || upperStrategy.equals("SR")) {
            allUnifiedOrders.addAll(srRepo.findAll().stream().map(this::mapSR).collect(Collectors.toList()));
        }

        // 3. Process Heikin Ashi (HEIKIN_PSAR) Table
        if (fetchAll || upperStrategy.equals("HEIKIN_PSAR")) {
            allUnifiedOrders.addAll(haRepo.findAll().stream().map(this::mapHA).collect(Collectors.toList()));
        }

        // Sort globally by ID descending so newest trades always hit the top of the UI grid
        allUnifiedOrders.sort(Comparator.comparing(UnifiedOrderDto::getId).reversed());

        return allUnifiedOrders;
    }

    /**
     * Dynamic mapper that looks at the existing row's 'name' property 
     * to safely apply parameters without causing NullPointerExceptions.
     */
    private UnifiedOrderDto mapGenericOrder(Orders o) {
        UnifiedOrderDto d = createBaseDto(o);

        String normName = d.getStrategyName().toUpperCase();

        // Dynamically assign strategy categories for UI presentation
        if (normName.contains("STRADDLE")) {
            d.setStrategyType("Option Seller");
            d.setInstrumentType(o.getOptionType());
        } else if (normName.contains("CPR")) {
            d.setStrategyType("Option Buyer");
            d.setInstrumentType(o.getOptionType() != null ? o.getOptionType() : 
                               (o.getSymbol() != null && o.getSymbol().endsWith("PE") ? "PE" : "CE"));
        } else if (normName.contains("DIRECTIONAL")) {
            d.setStrategyType("Directional Trend");
            d.setInstrumentType(o.getOptionType() != null ? o.getOptionType() : "FUT/OPT");
        } else {
            d.setStrategyType("Algorithmic Strategy");
            d.setInstrumentType(o.getOptionType());
        }

        applyTimestamps(d, o);
        applyTradingMath(d, o); 
        return d;
    }

    // --- SHARED HELPERS ---

    private UnifiedOrderDto createBaseDto(Orders o) {
        UnifiedOrderDto d = new UnifiedOrderDto();
        d.setId(o.getId());
        d.setSymbol(o.getSymbol());
        d.setStrategyName(o.getName() != null ? o.getName() : "UNNAMED_STRATEGY");
        d.setQuantity(Math.abs(o.getQuantity()));
        d.setDirection(o.getType()); 
        d.setExitReason(o.getExitReason());

        // 👉 IDENTIFY LIVE VS PAPER TRADING MODE
        // If the orderid exists, is not null, and is not explicitly "1", it's a LIVE market order.
        boolean isLive = o.getOrderid() != null && !o.getOrderid().trim().isEmpty() && !o.getOrderid().equals("1");
        d.setTradeMode(isLive ? "LIVE" : "PAPER");

        // Parse Strike Price
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

    private void applyTradingMath(UnifiedOrderDto d, Orders o) {
        d.setEntryPrice(o.getAskPrice());
        d.setExitPrice(o.getExitPrice());
        
        BigDecimal entry = o.getAskPrice();
        BigDecimal exit = o.getExitPrice();
        BigDecimal pts = BigDecimal.ZERO;

        boolean isSeller = "SELL".equalsIgnoreCase(d.getDirection()) || "Option Seller".equalsIgnoreCase(d.getStrategyType());

        if (entry != null && exit != null && exit.compareTo(BigDecimal.ZERO) > 0) {
            pts = isSeller ? entry.subtract(exit) : exit.subtract(entry);
        }
        
        d.setPoints(pts);
        
        if ("OPEN".equalsIgnoreCase(d.getStatus())) {
            // Live blinking figures from RAM Cache (Zero DB hits!)
            BigDecimal livePnl = riskService.getLivePnLForUI().get(o.getId());
            d.setPnl(livePnl != null ? livePnl : BigDecimal.ZERO);
        } else {
            // Static historical closing figure
            if (o.getPl() != null) {
                d.setPnl(o.getPl());
            } else {
                d.setPnl(pts.multiply(BigDecimal.valueOf(d.getQuantity())));
            }
        }
    }

    private void applyTimestamps(UnifiedOrderDto d, Orders o) {
        d.setEntryTime(o.getCreatedOn() != null ? 
                      o.getCreatedOn().toString().replace("T", " ").substring(0, 19) : "N/A");
        
        if (o.getActive() == 0 || "CLOSED".equalsIgnoreCase(o.getStatus())) {
            d.setExitTime(o.getClosedOn() != null ? o.getClosedOn().toString().replace("T", " ").substring(0, 19) : "N/A");
            d.setStatus("CLOSED");
        } else {
            d.setExitTime("ACTIVE");
            d.setStatus("OPEN");
        }
    }

    // --- LEGACY MAPPERS ---
    
    private UnifiedOrderDto mapSR(TradeExecution e) {
        UnifiedOrderDto d = new UnifiedOrderDto();
        d.setId(e.getId());
        d.setStrategyName("SR");
        d.setSymbol(e.getSymbol());
        d.setInstrumentType(e.getTradeType());
        d.setStrike(e.getLevelValue() != null ? e.getLevelValue().toString() : "-");
        d.setDirection(e.getTradeType() != null && e.getTradeType().toUpperCase().contains("SELL") ? "SELL" : "BUY");
        d.setStrategyType(d.getDirection().equals("SELL") ? "SELLER" : "BUYER");
        d.setEntryPrice(e.getEntryPrice());
        d.setExitPrice(e.getExitPrice());
        d.setPnl(e.getPnl());
        d.setStatus(e.getStatus());
        d.setTradeMode("PAPER"); // Default fallback for legacy tables
        return d;
    }

    private UnifiedOrderDto mapHA(ResultVix r) {
        UnifiedOrderDto d = new UnifiedOrderDto();
        d.setId(r.getId());
        d.setStrategyName("HEIKIN_PSAR");
        d.setSymbol(r.getSymbol());
        d.setDirection("BUY"); 
        d.setStatus(r.isActive() ? "OPEN" : "CLOSED");
        d.setTradeMode("PAPER"); // Default fallback for legacy tables
        return d;
    }
}