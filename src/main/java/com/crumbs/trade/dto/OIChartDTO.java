package com.crumbs.trade.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OIChartDTO(
	    LocalDateTime timestamp,
	    BigDecimal strike,

	    BigDecimal ceOi,
	    BigDecimal peOi,

	    BigDecimal ceOiChange,
	    BigDecimal peOiChange,

	    BigDecimal ceLtp,
	    BigDecimal peLtp,

	    BigDecimal ceLtpChange,
	    BigDecimal peLtpChange,

	    BigDecimal cePct,
	    BigDecimal pePct,
	    Boolean isATM   
	) {}