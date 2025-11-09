package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.crumbs.trade.entity.ResultVix;
import com.crumbs.trade.entity.Pnl;

import com.crumbs.trade.repo.ResultVixRepo;
import com.crumbs.trade.repo.PnlRepo;

@Component
public class PnlScheduler {

    private final ResultVixRepo resultVixRepo;
    private final PnlRepo pnlSummaryRepo;

    private static final Map<String, Integer> LOT_SIZE = Map.of(
        "NIFTY", 75,
        "SILVERM", 10
    );

    public PnlScheduler(ResultVixRepo resultVixRepo, PnlRepo pnlSummaryRepo) {
        this.resultVixRepo = resultVixRepo;
        this.pnlSummaryRepo = pnlSummaryRepo;
    }

    @Transactional
    @Scheduled(cron = "0 55 23 * * ?") // runs at 11:55 PM daily
    public void generatePnLSummary() {
        List<ResultVix> allOrders = resultVixRepo.findAll();

        if (allOrders.isEmpty()) {
            System.out.println("No trades found for today — skipping PnL summary.");
            return;
        }

        // Group trades by instrument (e.g., NIFTY, SILVERM)
        Map<String, List<ResultVix>> grouped = allOrders.stream()
                .collect(Collectors.groupingBy(ResultVix::getName));

        for (Map.Entry<String, List<ResultVix>> entry : grouped.entrySet()) {
            String name = entry.getKey();
            List<ResultVix> trades = entry.getValue();

            BigDecimal totalPoints = BigDecimal.ZERO;
            int profitCount = 0;
            int lossCount = 0;

            for (ResultVix trade : trades) {
                BigDecimal points = BigDecimal.valueOf(trade.getPoints());
                totalPoints = totalPoints.add(points);

                if (points.compareTo(BigDecimal.ZERO) > 0) profitCount++;
                else if (points.compareTo(BigDecimal.ZERO) < 0) lossCount++;
            }

            int lotSize = LOT_SIZE.getOrDefault(name.toUpperCase(), 1);
            BigDecimal netPnl = totalPoints.multiply(BigDecimal.valueOf(lotSize));

            Pnl pnl = new Pnl();
            pnl.setName(name);
            pnl.setTotalPoints(totalPoints);
            pnl.setLotSize(lotSize);
            pnl.setNetPnl(netPnl);
            pnl.setProfitTrades(profitCount);
            pnl.setLossTrades(lossCount);
            pnl.setTotalTrades(trades.size());
            pnl.setCreatedAt(LocalDateTime.now());

            pnlSummaryRepo.save(pnl);
        }

        // ✅ Once summary is stored, clear the resultVix table
        resultVixRepo.deleteAll();
    }
}
