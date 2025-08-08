package com.crumbs.trade.controller;

import com.crumbs.trade.dto.OrdersDTO;
import com.crumbs.trade.entity.ResultVix;
import com.crumbs.trade.repo.ResultVixRepo;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins = "*")
public class OrdersController {

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
        dto.setActive(order.getActive());
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
        dto.setMaxHigh(order.getMaxHigh());
        dto.setMaxLow(order.getMaxLow());
        dto.setExchange(order.getExchange());
        dto.setToken(order.getToken());
        dto.setSymbol(order.getSymbol());
        return dto;
    }
}
