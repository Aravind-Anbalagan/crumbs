package com.crumbs.trade.service;

import com.crumbs.trade.dto.UnifiedOrderDto;
import com.crumbs.trade.entity.TradeExecution;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.ResultVix;
import com.crumbs.trade.repo.TradeExecutionRepo;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.ResultVixRepo;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class TradeExecutionService {

    private final TradeExecutionRepo tradeExecutionRepo;
    private final OrderRepository orderRepository;
    private final ResultVixRepo resultVixRepository;

    // adjust formats to your actual strings
    private static final DateTimeFormatter RV_TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public TradeExecutionService(TradeExecutionRepo tradeExecutionRepo,
                                 OrderRepository orderRepository,
                                 ResultVixRepo resultVixRepository) {
        this.tradeExecutionRepo = tradeExecutionRepo;
        this.orderRepository = orderRepository;
        this.resultVixRepository = resultVixRepository;
    }

    /**
     * name = "SR"          -> TradeExecution
     * name = "CPR"         -> Orders
     * name = "HEIKIN_PSAR" -> ResultVix
     * name = null/other    -> all three
     */
    public List<UnifiedOrderDto> getAllOrders(String name) {
        List<UnifiedOrderDto> result = new ArrayList<>();

        if (name == null || "SR".equalsIgnoreCase(name)) {
            result.addAll(
                    tradeExecutionRepo.findAll()
                            .stream()
                            .map(this::fromTradeExecution)
                            .collect(Collectors.toList())
            );
        }

        if (name == null || "CPR".equalsIgnoreCase(name)) {
            result.addAll(
                    orderRepository.findAll()
                            .stream()
                            .map(this::fromOrders)
                            .collect(Collectors.toList())
            );
        }

        if (name == null || "HEIKIN_PSAR".equalsIgnoreCase(name)) {
            result.addAll(
                    resultVixRepository.findAll()
                            .stream()
                            .map(this::fromResultVix)
                            .collect(Collectors.toList())
            );
        }

        return result;
    }

    private UnifiedOrderDto fromTradeExecution(TradeExecution e) {
        UnifiedOrderDto dto = new UnifiedOrderDto();
        dto.setId(e.getId());
        dto.setStrategy("SR");

        dto.setSymbol(e.getSymbol());
        dto.setTimeframe(e.getTimeframe());
        dto.setTradeType(e.getTradeType());
        dto.setStatus(e.getStatus());

        dto.setEntryPrice(e.getEntryPrice());
        dto.setEntryTime(e.getEntryTime());
        dto.setExitPrice(e.getExitPrice());
        dto.setExitTime(e.getExitTime());

        dto.setTargetPrice(e.getTargetPrice());
        dto.setSlPrice(e.getSlPrice());
        dto.setPnl(e.getPnl());

        dto.setLevelValue(e.getLevelValue());
        dto.setMethod(e.getMethod());
        dto.setStrength(e.getStrength());
        dto.setExplanation(e.getExplanation());
      

        return dto;
    }

    private UnifiedOrderDto fromOrders(Orders o) {
        UnifiedOrderDto dto = new UnifiedOrderDto();
        dto.setId(o.getId());
        dto.setStrategy("CPR");

        dto.setSymbol(o.getSymbol());
        dto.setTimeframe(null);

        dto.setTradeType(o.getType());
        dto.setStatus(o.getActive() == 1 ? "OPEN" : "CLOSED");

        dto.setEntryPrice(o.getAskPrice());

        dto.setExitPrice(
            o.getExitPrice() == null || o.getExitPrice().compareTo(BigDecimal.ZERO) == 0
                ? null
                : o.getExitPrice()
        );

        dto.setTargetPrice(o.getPl());
        dto.setSlPrice(o.getSl());

        dto.setMethod("CPR");

        dto.setExchange(o.getExchange());
        dto.setToken(o.getToken());
        dto.setSignal(o.getSignal());
        dto.setOrderId(o.getOrderid());
        dto.setQuantity(o.getQuantity());

        return dto;
    }

    private UnifiedOrderDto fromResultVix(ResultVix r) {
        UnifiedOrderDto dto = new UnifiedOrderDto();
        dto.setId(r.getId());
        dto.setStrategy("HEIKIN_PSAR");

        dto.setSymbol(r.getSymbol());
        dto.setTimeframe(null);

        dto.setTradeType(r.getType());
        dto.setStatus(r.isActive() ? "OPEN" : "CLOSED");

        dto.setEntryPrice(r.getEntryPrice());
        dto.setExitPrice(r.getExitPrice());

        dto.setPoints(r.getPoints());
        dto.setLotSize(r.getLotSize());
        dto.setResult(r.getResult());

        dto.setMethod("HEIKIN_PSAR");
        dto.setExplanation(r.getComment());

        dto.setExchange(r.getExchange());
        dto.setToken(r.getToken());
        DateTimeFormatter formatter =
                DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    
        dto.setEntryTime(LocalDateTime.parse(r.getEntryTime(), formatter));
        dto.setExitTime(LocalDateTime.parse(r.getExitTime(), formatter));
        
     

        return dto;
    }
}
