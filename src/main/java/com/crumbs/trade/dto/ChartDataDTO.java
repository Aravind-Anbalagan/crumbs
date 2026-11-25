package com.crumbs.trade.dto;

import lombok.Data;
import java.util.List;

@Data
public class ChartDataDTO {
    private List<CandleDTO>   candles;
    private List<SRLevelDTO>  supportLevels;
    private List<SRLevelDTO>  resistanceLevels;
    private CandleDTO         previousDayCandle;
    private List<SignalDTO>   signals;
    private DecisionDTO decision;
    private TrendDTO trend;
    public TrendDTO getTrend() { return trend; }
    public void setTrend(TrendDTO trend) { this.trend = trend; }
    private ThreeCandleDTO threeCandle;
    public ThreeCandleDTO getThreeCandle() { return threeCandle; }
    public void setThreeCandle(ThreeCandleDTO threeCandle) { this.threeCandle = threeCandle; }
}