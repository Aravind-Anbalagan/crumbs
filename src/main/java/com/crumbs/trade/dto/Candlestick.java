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
    
    // Legacy Moving Average / SuperTrend fields
    public BigDecimal smoothMA;
    public BigDecimal superTrend;
    public String superTrendSignal;
    
    // Dual EMA Crossover Fields
    public BigDecimal fastEma;
    public BigDecimal slowEma;
    public String masignal; 

    // 🚀 NEW: Tracks the exact point of intersection (e.g., "BUY_CROSS", "SELL_CROSS", "NONE")
    public String crossoverEvent;
    public String combinedSignal;
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

    // ✅ Copy constructor — securely transfers all state, including the new trigger events
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
        this.superTrend = other.getSuperTrend();
        this.superTrendSignal = other.getSuperTrendSignal();
        
        // Ensure EMA crossover states are perfectly copied
        this.fastEma = other.getFastEma();
        this.slowEma = other.getSlowEma();
        this.masignal = other.getMasignal();
        this.crossoverEvent = other.getCrossoverEvent();
    }
}