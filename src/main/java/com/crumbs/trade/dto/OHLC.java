package com.crumbs.trade.dto;

import java.math.BigDecimal;



import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class OHLC {

	String timestamp;

	BigDecimal open;

	BigDecimal high;

	BigDecimal low;

	BigDecimal close;

	BigDecimal volume;

	String timeframe;

	String name;

	String type;

	BigDecimal range;

	BigDecimal percentage;

	String signal;

	String result;

	BigDecimal currentprice;
}
