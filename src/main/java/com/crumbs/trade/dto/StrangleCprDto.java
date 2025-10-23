package com.crumbs.trade.dto;

import java.math.BigDecimal;

import lombok.Data;

@Data
public class StrangleCprDto {

	private BigDecimal pivot;
	private BigDecimal bottom_pivot;
	private BigDecimal top_pivot;
	private BigDecimal open;
	private BigDecimal high;
	private BigDecimal low;
	private BigDecimal close;
	private BigDecimal firstFiveMinHigh;
	private BigDecimal firstFiveMinLow;
}
