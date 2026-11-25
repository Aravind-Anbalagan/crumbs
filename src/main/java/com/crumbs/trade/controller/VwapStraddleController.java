package com.crumbs.trade.controller;

import com.crumbs.trade.dto.VwapStraddleAddRequest;
import com.crumbs.trade.dto.VwapStraddleTickDto;
import com.crumbs.trade.dto.VwapStraddleUpdateRequest;
import com.crumbs.trade.dto.VwapStraddleWatchlistDto;
import com.crumbs.trade.service.VwapStraddleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/vwap-straddle")
@RequiredArgsConstructor
public class VwapStraddleController {

    private final VwapStraddleService vwapStraddleService;

    @GetMapping("/symbols")
    public ResponseEntity<List<String>> getSymbols() {
        return ResponseEntity.ok(vwapStraddleService.getAvailableNfoSymbols());
    }

    @GetMapping("/strikes")
    public ResponseEntity<List<BigDecimal>> getStrikes(@RequestParam String symbol) {
        return ResponseEntity.ok(vwapStraddleService.getNearestExpiryStrikes(symbol));
    }

    @GetMapping("/watchlist")
    public ResponseEntity<List<VwapStraddleWatchlistDto>> getWatchlist() {
        return ResponseEntity.ok(vwapStraddleService.getTodayWatchlist());
    }

    @PostMapping("/watchlist")
    public ResponseEntity<VwapStraddleWatchlistDto> addWatchlist(@RequestBody VwapStraddleAddRequest req) {
        return ResponseEntity.ok(vwapStraddleService.addWatchlistItem(req));
    }

    @PatchMapping("/watchlist/{id}")
    public ResponseEntity<VwapStraddleWatchlistDto> updateWatchlist(
            @PathVariable Long id,
            @RequestBody VwapStraddleUpdateRequest req) {
        return ResponseEntity.ok(vwapStraddleService.updateWatchlistItem(id, req));
    }

    @DeleteMapping("/watchlist/{id}")
    public ResponseEntity<Void> deleteWatchlist(@PathVariable Long id) {
        vwapStraddleService.deleteWatchlistItem(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/watchlist/{id}/chart")
    public ResponseEntity<List<VwapStraddleTickDto>> getChart(@PathVariable Long id) {
        return ResponseEntity.ok(vwapStraddleService.getChartTicks(id));
    }
}