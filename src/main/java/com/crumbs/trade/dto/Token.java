package com.crumbs.trade.dto;

import java.math.BigDecimal;

import lombok.Data;

@Data
public class Token {
	String orderId;
	
	String token;
	String symbol;
	String finsymbol;
	String name;
	String type;
	String expiry;
	String strike;
	String lotsize;
	String instrumenttype;
	String exch_seg;
	String tick_size;
	String variety;
	String orderType;
	Double price;
	Double triggerPrice;
	String productType;
	String duration;
	String transactionType;
	int quantity;
	String signal;
	String bpprc;
	String blprc;
	String trailprc;
	String squareoff;
	String stoploss;
	String gttType;
	String stoplosstriggerprice;
	BigDecimal currentPrice;
	
	// ── CPR Order Meta ──────────────────────────────
    BigDecimal entryPrice;       // LTP at time of entry
    BigDecimal slPrice;          // computed stoploss level
    BigDecimal first5High;       // first 5-min high (buffered)
    BigDecimal first5Low;        // first 5-min low  (buffered)
    BigDecimal upperBand;        // CPR upper band
    BigDecimal lowerBand;        // CPR lower band
    BigDecimal pivot;            // CPR pivot
    String     marketType;       // NORMAL | GAP_UP | GAP_DOWN
    String     entryTime;        // ISO datetime of entry
}
