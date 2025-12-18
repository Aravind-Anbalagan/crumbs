package com.crumbs.trade.controller;

import com.crumbs.trade.dto.OrdersDTO;
import com.crumbs.trade.dto.TradeExecutionDto;
import com.crumbs.trade.dto.UnifiedOrderDto;
import com.crumbs.trade.entity.ResultVix;
import com.crumbs.trade.entity.TradeExecution;
import com.crumbs.trade.repo.ResultVixRepo;
import com.crumbs.trade.service.TradeExecutionService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins = "*")
public class OrdersController {

	@Autowired TradeExecutionService tradeExecutionService;
    private final ResultVixRepo resultVixRepo;

    public OrdersController(ResultVixRepo ordersRepository) {
        this.resultVixRepo = ordersRepository;
    }

    @GetMapping
    public List<OrdersDTO> getAllOrders() {
        return resultVixRepo.findAll().stream()
            .map(this::convertToDto)
            .collect(Collectors.toList());
    }

    private OrdersDTO convertToDto(ResultVix order) {
        OrdersDTO dto = new OrdersDTO();
        dto.setId(order.getId());
        dto.setName(order.getName());
        dto.setActive(order.isActive()? "Y" : null);
        dto.setCreatedDate(order.getCreatedDate());
        dto.setModifiedDate(order.getModifiedDate());
        dto.setComment(order.getComment());
        dto.setType(order.getType());
        dto.setResult(order.getResult());
        dto.setTimestamp(order.getTimestamp());
        dto.setEntryTime(order.getEntryTime());
        dto.setExitTime(order.getExitTime());
        dto.setEntryPrice(order.getEntryPrice());
        dto.setExitPrice(order.getExitPrice());
        dto.setPoints(order.getPoints());
        dto.setLotSize(order.getLotSize());
       // dto.setMaxHigh(order.getMaxHigh());
        //dto.setMaxLow(order.getMaxLow());
        dto.setExchange(order.getExchange());
        dto.setToken(order.getToken());
        dto.setSymbol(order.getSymbol());
        dto.setPr(order.getPriceAction());
        dto.setFibo(order.getFibo());
        dto.setMa(order.getMa());
        dto.setSupertrend(order.getSuperTrend());
        return dto;
    }
    // /api/orders                -> SR + CPR + HEIKIN_PSAR
    // /api/orders?name=SR        -> SR only
    // /api/orders?name=CPR       -> CPR only
    // /api/orders?name=HEIKIN_PSAR -> HEIKIN_PSAR only
    @GetMapping("/orderList")
    public List<UnifiedOrderDto> getOrders(
            @RequestParam(value = "name", required = false) String name
    ) {
        return tradeExecutionService.getAllOrders(name);
    }
}
