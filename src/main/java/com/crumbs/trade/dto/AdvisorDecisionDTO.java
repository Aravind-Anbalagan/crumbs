package com.crumbs.trade.dto;

import com.crumbs.trade.utility.PressureZone;
import com.crumbs.trade.utility.TradingMode;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AdvisorDecisionDTO {

    private TradingMode recommendedMode;
    private String message;
    private String reasoning;

    private int pressure;
    private PressureZone zone;
}
