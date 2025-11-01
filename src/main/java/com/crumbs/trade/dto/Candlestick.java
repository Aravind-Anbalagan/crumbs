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
    private String timestamp;
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

    // Constructor with volume
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

    // ✅ Copy constructor — fixes Candlestick(Candlestick) undefined issue
    public Candlestick(Candlestick other) {
        if (other == null) return;
        this.id = other.getId();
        this.open = other.getOpen();
        this.high = other.getHigh();
        this.low = other.getLow();
        this.close = other.getClose();
        this.volume = other.getVolume();
        this.signal = other.getSignal();
        this.psarPrice = other.getPsarPrice();
        this.candleType = other.getCandleType();
        this.timestamp = other.getTimestamp();
        this.vwap = other.getVwap();
        this.smoothMA = other.getSmoothMA();
        this.masignal = other.getMasignal();
        this.superTrend = other.getSuperTrend();
        this.superTrendSignal = other.getSuperTrendSignal();
    }
}
