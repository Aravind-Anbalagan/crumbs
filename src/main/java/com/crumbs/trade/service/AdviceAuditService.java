package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;

import com.crumbs.trade.entity.StraddleIntraday;
import com.crumbs.trade.entity.TradingAdvice;
import com.crumbs.trade.entity.TradingAdviceAudit;

@Service
public class AdviceAuditService {

    public TradingAdviceAudit evaluate(
            TradingAdvice advice,
            List<StraddleIntraday> data,
            MarketPressureService pressureService) {

        TradingAdviceAudit audit = new TradingAdviceAudit();
        audit.setAdviceId(advice.getId());
        audit.setSymbol(advice.getSymbol());
        audit.setTradeDate(advice.getTradeDate());
        audit.setAdvisedMode(advice.getRecommendedMode().name());
        audit.setAdviceTime(advice.getAdviceTime());
        audit.setExitTime(advice.getExitTime());
        audit.setEntryPressure(advice.getEntryPressure());

        BigDecimal entrySpot = data.stream()
                .filter(d -> !d.getTimestamp().isBefore(advice.getAdviceTime()))
                .findFirst()
                .map(StraddleIntraday::getSpot)
                .orElse(BigDecimal.ZERO);

        int maxPressure = advice.getEntryPressure();
        BigDecimal maxAgainst = BigDecimal.ZERO;

        for (StraddleIntraday row : data) {

            if (row.getTimestamp().isBefore(advice.getAdviceTime())) continue;
            if (advice.getExitTime() != null &&
                row.getTimestamp().isAfter(advice.getExitTime())) break;

            int p = pressureService.calculate(row).getPressure();
            maxPressure = Math.max(maxPressure, p);

            BigDecimal move = row.getSpot().subtract(entrySpot);
            maxAgainst = maxAgainst.min(move);
        }

        audit.setMaxPressureAfterEntry(maxPressure);
        audit.setMaxSpotMoveAgainst(maxAgainst.abs());

        audit.setAdviceSurvived(advice.getExitTime() == null);
        audit.setExitWasTimely(
                advice.getExitTime() != null &&
                maxAgainst.abs().compareTo(new BigDecimal("50")) < 0
        );

        audit.setAuditConclusion(
                audit.isAdviceSurvived()
                    ? "Belief held entire session"
                    : audit.isExitWasTimely()
                        ? "Exit protected trader"
                        : "Exit was late"
        );

        return audit;
    }
}
