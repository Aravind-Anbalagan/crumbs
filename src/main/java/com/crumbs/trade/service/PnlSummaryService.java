package com.crumbs.trade.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import com.crumbs.trade.entity.Pnl;
import com.crumbs.trade.repo.PnlRepo;

@Service
public class PnlSummaryService {

    private final PnlRepo pnlRepo;

    public PnlSummaryService(PnlRepo pnlRepo) {
        this.pnlRepo = pnlRepo;
    }

    public List<Pnl> getFilteredPnL(String name, LocalDate fromDate, LocalDate toDate) {
        // 🗓️ Default to current month if no dates are provided
        if (fromDate == null && toDate == null) {
            LocalDate now = LocalDate.now();
            fromDate = now.withDayOfMonth(1);  // first day of the month
            toDate = now;                      // today
        } else if (fromDate == null) {
            fromDate = LocalDate.now().withDayOfMonth(1);
        } else if (toDate == null) {
            toDate = LocalDate.now();
        }

        // 🎯 Apply filter conditions
        if (name != null && !name.isBlank()) {
            return pnlRepo.findByNameAndTradeDateBetween(name.toUpperCase(), fromDate, toDate);
        } else {
            return pnlRepo.findByTradeDateBetween(fromDate, toDate);
        }
    }
}
