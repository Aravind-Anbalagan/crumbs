package com.crumbs.trade.service;

import org.springframework.stereotype.Service;

import com.crumbs.trade.dto.AdvisorDecisionDTO;
import com.crumbs.trade.dto.PressureInsightDTO;
import com.crumbs.trade.utility.TradingMode;

@Service
public class TradingAdvisorService {

    public AdvisorDecisionDTO advise(PressureInsightDTO p) {

        switch (p.getZone()) {

            case LOW:
                return new AdvisorDecisionDTO(
                        TradingMode.OPTION_SELL_RANGE,
                        "Pressure LOW. Range option selling is safe.",
                        "Premium decaying & OI balanced",
                        p.getPressure(),
                        p.getZone()
                );

            case MEDIUM:
                return new AdvisorDecisionDTO(
                        TradingMode.OPTION_SELL_DIRECTIONAL,
                        "Pressure rising. Avoid straddle.",
                        "Range may be tested",
                        p.getPressure(),
                        p.getZone()
                );

            case HIGH:
                return new AdvisorDecisionDTO(
                        TradingMode.OPTION_BUY_DIRECTIONAL,
                        "Pressure HIGH. Sellers at risk.",
                        "Directional momentum building",
                        p.getPressure(),
                        p.getZone()
                );

            default:
                return new AdvisorDecisionDTO(
                        TradingMode.NO_TRADE,
                        "Pressure CRITICAL. No new trades.",
                        "Market breaking structure",
                        p.getPressure(),
                        p.getZone()
                );
        }
    }
}
