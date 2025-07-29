package com.crumbs.trade.dto;

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
}
