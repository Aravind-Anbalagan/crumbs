package com.crumbs.trade.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;

@Data
public class StraddlePremiumDto {
	public LocalDateTime timestamp;   // X-axis (09:15, 09:16, 09:17...)
	public BigDecimal cePrice;      // Line 1
	public BigDecimal pePrice;      // Line 2
	public Token peToken;
	public Token ceToken;
    public BigDecimal strikePrice;
    public BigDecimal combinedPremium;  // CE + PE
    public BigDecimal ceIv;
    public BigDecimal peIv;
    public BigDecimal combinedIv;
    public BigDecimal ceVwap;
    public BigDecimal peVwap;
    public BigDecimal extrinsicValue;
    public BigDecimal intrinsicValue;
}
