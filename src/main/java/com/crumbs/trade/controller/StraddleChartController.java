package com.crumbs.trade.controller;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.crumbs.trade.dto.NameExpiryStrikeGroupedDto;
import com.crumbs.trade.service.StraddleGroupingService;
import com.crumbs.trade.service.StraddleIntradayService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/straddle")
@RequiredArgsConstructor
public class StraddleChartController {

    private final StraddleIntradayService straddleIntradayService;
    private final StraddleGroupingService straddleGroupingService;

    @GetMapping("/combined-chart")
    public ResponseEntity<?> getCombinedChart(
            @RequestParam String name,
            @RequestParam String expiry,
            @RequestParam BigDecimal ceStrike,
            @RequestParam BigDecimal peStrike) {

        if (ceStrike == null || peStrike == null) {
            return ResponseEntity.badRequest()
                    .body("ceStrike and peStrike are required");
        }

        return ResponseEntity.ok(
                straddleIntradayService
                        .getStraddleCombinedChart(name, expiry, ceStrike, peStrike)
        );
    }

    @GetMapping("/grouped")
    public List<NameExpiryStrikeGroupedDto> getGrouped() {
        return straddleGroupingService.getGrouped();
    }
}
