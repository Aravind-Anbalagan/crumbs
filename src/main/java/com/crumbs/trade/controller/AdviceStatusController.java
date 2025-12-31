package com.crumbs.trade.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.crumbs.trade.dto.AdviceStatusDTO;
import com.crumbs.trade.service.AdviceStatusService;

@RestController
@RequestMapping("/api/advice")
public class AdviceStatusController {

    @Autowired
    private AdviceStatusService statusService;

    @GetMapping("/status")
    public AdviceStatusDTO status(@RequestParam String symbol) {
        return statusService.getCurrentStatus(symbol);
    }
}
