package com.crumbs.trade.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CombinedChartPoint {

    private String timestamp;

    private BigDecimal ce;       // CE LTP
    private BigDecimal pe;       // PE LTP
    private BigDecimal combinedPremium;
    private BigDecimal spot;

    private BigDecimal ceOpen;
    private BigDecimal peOpen;
    private BigDecimal combinedOpen;


    private BigDecimal peExtrinsic;   // <-- NEW
    private BigDecimal ceExtrinsic;   // <-- NEW
    private BigDecimal avgPrice;
    
    private BigDecimal ceVwap;
    private BigDecimal peVwap;
    private BigDecimal combinedVwap;
    
 // ✅ ADD THESE
    private BigDecimal ceIV;
    private BigDecimal peIV;
    private BigDecimal combinedIV;
    
 // 🆕 ADD THESE NEW PARAMETERS
    BigDecimal cePrevClose;
    BigDecimal pePrevClose;
    BigDecimal combinedPrevClose;
}
