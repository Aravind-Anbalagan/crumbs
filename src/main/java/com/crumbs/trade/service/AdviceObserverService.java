package com.crumbs.trade.service;

import org.springframework.stereotype.Service;

import com.crumbs.trade.dto.PressureInsightDTO;
import com.crumbs.trade.entity.TradingAdvice;
import com.crumbs.trade.utility.PressureZone;

@Service
public class AdviceObserverService {

    public boolean shouldExit(
            TradingAdvice advice,
            PressureInsightDTO p) {

        if ("EXITED".equals(advice.getStatus())) return false;

        switch (advice.getRecommendedMode()) {

            case OPTION_SELL_RANGE:
                return p.getZone() != PressureZone.LOW;

            case OPTION_SELL_DIRECTIONAL:
                return p.getZone() == PressureZone.HIGH
                    || p.getZone() == PressureZone.CRITICAL;

            case OPTION_BUY_DIRECTIONAL:
                return p.getZone() == PressureZone.LOW;

            default:
                return false;
        }
    }
}
