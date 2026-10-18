package com.crumbs.trade.dto;

import com.crumbs.trade.entity.StraddleVwapData;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class VwapChartResponseDto {
    private Double currentAtmStrike;
    private BigDecimal latestSpotPrice;
    private List<Double> availableStrikes; // ✅ NEW: Sorted list for the UI dropdown
    private Map<Double, List<StraddleVwapData>> strikeData;
}