package com.crumbs.trade.dto;

import java.math.BigDecimal;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class OIDto {
	BigDecimal strikePrice;
	String callLtp;
	String callOi;
	String callOiChange;
	String callOiTime;
	String putLtp;
	String putOi;
	String putOiChange;
	String putOiTime;
	String name;
	String spot;
	String expiry;
	
}
