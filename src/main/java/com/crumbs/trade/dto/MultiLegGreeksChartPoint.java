package com.crumbs.trade.dto;

import java.math.BigDecimal;

public record MultiLegGreeksChartPoint(
	    long time, 
	    String istTime, 
	    // Prices
	    BigDecimal ceLtp, BigDecimal peLtp, BigDecimal combinedPremium,
	    // CE Greeks
	    double ceIv, double ceDelta, double ceGamma, double ceTheta, double ceVega,
	    // PE Greeks
	    double peIv, double peDelta, double peGamma, double peTheta, double peVega
	) {}