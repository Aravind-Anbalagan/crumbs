package com.crumbs.trade.dto;

import java.math.BigDecimal;



import lombok.Data;
@Data
public class CandlesDetails {
    String startTime;
    String endTime;
	BigDecimal min;
	BigDecimal max;
}
