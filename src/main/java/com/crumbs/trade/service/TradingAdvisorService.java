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
                        "Pressure LOW. Market balanced.",
                        "Premium decay expected, range likely",
                        p.getPressure(),
                        p.getZone()
                );

            case MEDIUM:
                return new AdvisorDecisionDTO(
                        TradingMode.NO_TRADE,
                        "Pressure building. Waiting for confirmation.",
                        "Early imbalance detected",
                        p.getPressure(),
                        p.getZone()
                );

            case HIGH:
                return new AdvisorDecisionDTO(
                        TradingMode.OPTION_BUY_DIRECTIONAL,
                        "Pressure HIGH. Directional setup forming.",
                        "Sellers under stress, momentum building",
                        p.getPressure(),
                        p.getZone()
                );

            case CRITICAL:
                return new AdvisorDecisionDTO(
                        TradingMode.OPTION_BUY_DIRECTIONAL,
                        "Pressure CRITICAL. Trend acceleration.",
                        "Strong imbalance, breakout conditions",
                        p.getPressure(),
                        p.getZone()
                );

            default:
                return new AdvisorDecisionDTO(
                        TradingMode.NO_TRADE,
                        "Invalid pressure state.",
                        "Unknown zone received",
                        p.getPressure(),
                        p.getZone()
                );
        }
    }
}
