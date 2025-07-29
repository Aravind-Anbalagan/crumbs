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
    public String signal;
    public BigDecimal psarPrice;
    public String candleType;

    public Candlestick(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,Long id,String signal,BigDecimal psarPrice, String candleType) {
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.id = id;
        this.signal = signal;
        this.psarPrice = psarPrice;
        this.candleType = candleType;
    }

	public Candlestick() {
		
	}
    
}