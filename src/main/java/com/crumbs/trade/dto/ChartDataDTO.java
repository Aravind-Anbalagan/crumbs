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
}