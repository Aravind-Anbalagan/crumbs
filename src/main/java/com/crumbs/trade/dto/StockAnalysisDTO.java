package com.crumbs.trade.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class StockAnalysisDTO {
    private String tradingSymbol;
    private String exchange;
    private BigDecimal currentPrice;
    private BigDecimal rsi;
    private List<BigDecimal> bollingerBand;
    private List<BigDecimal> cpr;
    private String dailySignal;
    private String weeklySignal;
    private String hourlySignal;
    private String psarDay;
    private String psarWeekly;
    private String heikinAshiDay;
    private String heikinAshiWeekly;
    private Map<String, BigDecimal> pivot;
    private String timeFrame;
    private String dailyVolume;
    private String weeklyVolume;
    private String support;
    private String resistance;
    private String dailyPriceActionSupport;
    private String dailyPriceActionResistance;
    private String dailyFiboSupport;
    private String dailyFiboResistance;

    private String weeklyPriceActionSupport;
    private String weeklyPriceActionResistance;
    private String weeklyFiboSupport;
    private String weeklyFiboResistance;
}
