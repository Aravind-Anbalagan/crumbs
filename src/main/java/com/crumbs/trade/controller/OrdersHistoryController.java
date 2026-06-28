package com.crumbs.trade.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crumbs.trade.entity.OrdersHistory;
import com.crumbs.trade.service.OrdersHistoryService;

@RestController
@RequestMapping("/api/v1/history")
public class OrdersHistoryController {

    @Autowired
    private OrdersHistoryService historyService;

    // GET: /api/v1/history/overall
    @GetMapping("/overall")
    public ResponseEntity<List<OrdersHistory>> getOverallHistory() {
        return ResponseEntity.ok(historyService.getOverallHistory());
    }

    // GET: /api/v1/history/month/2026-06
    @GetMapping("/month/{yearMonth}")
    public ResponseEntity<List<OrdersHistory>> getHistoryByMonth(@PathVariable String yearMonth) {
        return ResponseEntity.ok(historyService.getHistoryByMonth(yearMonth));
    }

    // GET: /api/v1/history/date/2026-06-28
    @GetMapping("/date/{date}")
    public ResponseEntity<List<OrdersHistory>> getHistoryByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(historyService.getHistoryByDate(date));
    }
}