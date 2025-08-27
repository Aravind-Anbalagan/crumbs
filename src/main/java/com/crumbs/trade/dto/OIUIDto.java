package com.crumbs.trade.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class OIUIDto {
    private Long id;
    private String name;
    private BigDecimal strikePrice;
    private List<TimeValue> callOIChange;
    private List<TimeValue> callOI;
    private List<TimeValue> callLTP;
    private List<TimeValue> putOIChange;
    private List<TimeValue> putOI;
    private List<TimeValue> putLTP;
    private String spot;
    private String putSignal;
    private String callSignal;
    private String putTradingSignal;
    private String callTradingSignal;
    private String expiry;
    private List<TimeValue> callVolume;
    private List<TimeValue> putVolume;
}
