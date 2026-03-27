package com.crumbs.trade.controller;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.crumbs.trade.entity.OIResult;
import com.crumbs.trade.repo.OIResultRepo;
import com.crumbs.trade.service.OIAnalysisService;

@RestController
@RequestMapping("/api/oi")
public class OIController {

    @Autowired
    private OIResultRepo repo;

    @Autowired
    private OIAnalysisService oiAnalysisService; // ✅ added

    // 🔥 1. Latest full option chain (bar chart)
    @GetMapping("/latest/{name}")
    public List<OIResult> getLatest(@PathVariable String name) {
        return repo.findLatestByName(name);
    }

    // 🔥 2. Full time-series for a strike (core API)
    @GetMapping("/strike/{name}/{strike}")
    public List<OIResult> getStrikeData(
            @PathVariable String name,
            @PathVariable BigDecimal strike
    ) {
        return repo.findStrikeData(name, strike);
    }

    // 🔥 3. NEW: Get only strike list
    @GetMapping("/strikes/{name}")
    public List<BigDecimal> getStrikeList(@PathVariable String name) {
        return oiAnalysisService.getStrikeList(name);
    }
}