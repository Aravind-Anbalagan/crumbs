package com.crumbs.trade.dto;

import java.math.BigDecimal;
import lombok.Data;

@Data
public class CPR {

	private BigDecimal pivot;
	private BigDecimal bottom_pivot;
	private BigDecimal top_pivot;
}
