package com.crumbs.trade.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class ChartDataDTO {
    private List<CandleDTO> candles;

    private List<BigDecimal> priceActionSupport;
    private List<BigDecimal> priceActionResistance;

    private List<FibonacciLevel> fiboSupport;
    private List<FibonacciLevel> fiboResistance;

    private List<SignalDTO> signals;
}


