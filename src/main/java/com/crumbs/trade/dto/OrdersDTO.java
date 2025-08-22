package com.crumbs.trade.dto;

import java.math.BigDecimal;
import java.util.Date;

import lombok.Data;

@Data
public class OrdersDTO {
	   private Long id;
	    private String name;
	    private String active;
	    private Date createdDate;
	    private Date modifiedDate;
	    private String comment;
	    private String type;
	    private String pr;
	    private String result;
	    private String timestamp;
	    private String entryTime;
	    private String exitTime;
	    private BigDecimal entryPrice;
	    private BigDecimal exitPrice;
	    private int points;
	    private int lotSize;
	    private int maxHigh;
	    private int maxLow;
	    private String exchange;
	    private String token;
	    private String symbol;
}