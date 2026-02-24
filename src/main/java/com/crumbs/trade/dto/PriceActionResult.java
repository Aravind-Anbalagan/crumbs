package com.crumbs.trade.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PriceActionResult {
    private BigDecimal       currentPrice;
    private List<SRLevelDTO> supportLevels;
    private List<SRLevelDTO> resistanceLevels;
}