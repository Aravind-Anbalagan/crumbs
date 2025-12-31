package com.crumbs.trade.dto;

import java.util.List;

import com.crumbs.trade.utility.PressureZone;

import lombok.Data;

@Data
public class PressureInsightDTO {

    private int pressure;
    private PressureZone zone;

    private List<String> reasons;

    private double premiumDelta;
    private double oiRatio;
    private double spotDeviationPct;
}
