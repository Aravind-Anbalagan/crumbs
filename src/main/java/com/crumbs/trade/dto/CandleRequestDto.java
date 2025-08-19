package com.crumbs.trade.dto;

import java.time.LocalDate;

import lombok.Data;

@Data
public class CandleRequestDto {
	private String fromDate;
	private String toDate;
	private String timeFrame;
	private String name;
	private String type;
}
