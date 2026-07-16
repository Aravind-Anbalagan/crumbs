package com.crumbs.trade.service;

import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import com.crumbs.trade.dto.UnifiedOrderDto;
import com.crumbs.trade.entity.*;
import com.crumbs.trade.repo.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TradeExecutionService {
    private static final Logger log = LoggerFactory.getLogger(TradeExecutionService.class);

    private final TradeExecutionRepo srRepo;
    private final OrderRepository orderRepository;
    private final ResultVixRepo haRepo;
    private final MonitorOrderService riskService;
    
    @Autowired 
    private AngelWebSocketService angelWebSocketService;

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
            List<Orders> allDbOrders = orderRepository.findAll();
            for (Orders o : allDbOrders) {
                allUnifiedOrders.add(mapGenericOrder(o));
            }
        } else {
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

        boolean isSeller = "SELL".equalsIgnoreCase(d.getDirection()) || 
                           "Option Seller".equalsIgnoreCase(d.getStrategyType());

        if (entry != null && exit != null && exit.compareTo(BigDecimal.ZERO) > 0) {
            pts = isSeller ? entry.subtract(exit) : exit.subtract(entry);
        }
        
        d.setPoints(pts);
        
        if ("OPEN".equalsIgnoreCase(d.getStatus())) {
            BigDecimal livePnl = riskService.getLivePnLForUI().get(o.getId());
            
            if (livePnl == null) {
                ExchangeType et = mapExchangeToType(o.getExchange());
                
                if (et != null && o.getToken() != null && !o.getToken().isEmpty()) {
                    // 1. Ask for the data
                    angelWebSocketService.subscribe(et, o.getToken());

                    // 2. WAIT FOR DELIVERY (Up to 1 second / 5 retries)
                    BigDecimal currentLtp = BigDecimal.ZERO;
                    int retries = 0;

                    while ((currentLtp == null || currentLtp.compareTo(BigDecimal.ZERO) == 0) && retries < 5) {
                        try {
                            Thread.sleep(200); // Wait 200 milliseconds
                            currentLtp = angelWebSocketService.getLatestLTP(et, o.getToken());
                            retries++;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    // 3. Calculate manual PnL using the fetched price
                    if (currentLtp != null && currentLtp.compareTo(BigDecimal.ZERO) > 0 && entry != null) {
                        BigDecimal manualPts = isSeller ? entry.subtract(currentLtp) : currentLtp.subtract(entry);
                        d.setPnl(manualPts.multiply(BigDecimal.valueOf(d.getQuantity())));
                        log.info("Missing PnL Fixed! Fetched LTP: {} for {}", currentLtp, o.getSymbol());
                    } else {
                        d.setPnl(BigDecimal.ZERO);
                        log.warn("Failed to get LTP for token {} after waiting.", o.getToken());
                    }
                } else {
                    d.setPnl(BigDecimal.ZERO);
                }
            } else {
                d.setPnl(livePnl); // Data was already available in RiskService cache
            }
        } else {
            // Static historical closing figure
            d.setPnl(o.getPl() != null ? o.getPl() : pts.multiply(BigDecimal.valueOf(d.getQuantity())));
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
        d.setTradeMode("PAPER"); 
        return d;
    }

    private UnifiedOrderDto mapHA(ResultVix r) {
        UnifiedOrderDto d = new UnifiedOrderDto();
        d.setId(r.getId());
        d.setStrategyName("HEIKIN_PSAR");
        d.setSymbol(r.getSymbol());
        d.setDirection("BUY"); 
        d.setStatus(r.isActive() ? "OPEN" : "CLOSED");
        d.setTradeMode("PAPER"); 
        return d;
    }

    private ExchangeType mapExchangeToType(String exchange) {
        if (exchange == null) return null;
        switch (exchange.toUpperCase().trim()) {
            case "NFO": return ExchangeType.NSE_FO;
            case "MCX": return ExchangeType.MCX_FO;
            case "NSE": return ExchangeType.NSE_CM;
            case "BSE": return ExchangeType.BSE_CM;
            case "BFO": return ExchangeType.BSE_FO;
            default: return null;
        }
    }
}