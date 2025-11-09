package com.crumbs.trade.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import com.crumbs.trade.entity.Pnl;
import com.crumbs.trade.service.PnlSummaryService;

@RestController
@RequestMapping("/api/pnl")
@CrossOrigin(origins = "*") // optional, for frontend use
public class PnlSummaryController {

    private final PnlSummaryService pnlSummaryService;

    public PnlSummaryController(PnlSummaryService pnlSummaryService) {
        this.pnlSummaryService = pnlSummaryService;
    }

    @GetMapping
    public List<Pnl> getPnL(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {

        return pnlSummaryService.getFilteredPnL(name, fromDate, toDate);
    }
}
