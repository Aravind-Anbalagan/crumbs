package com.crumbs.trade.dto;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class Candlestick {
    public Long id;
    public BigDecimal open;
    public BigDecimal high;
    public BigDecimal low;
    public BigDecimal close;
    public BigDecimal volume;
    public String signal;
    public BigDecimal psarPrice;
    public String candleType;

    public BigDecimal vwap;
    public BigDecimal smoothMA;
    public String masignal;
    public BigDecimal superTrend;
    public String superTrendSignal;

    public Candlestick() {}

    // Original constructor
    public Candlestick(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                       Long id, String signal, BigDecimal psarPrice, String candleType) {
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.id = id;
        this.signal = signal;
        this.psarPrice = psarPrice;
        this.candleType = candleType;
    }

    // ✅ Added constructor with volume
    public Candlestick(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                       Long id, BigDecimal volume, String signal, BigDecimal psarPrice, String candleType) {
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.id = id;
        this.volume = volume;
        this.signal = signal;
        this.psarPrice = psarPrice;
        this.candleType = candleType;
    }
}
