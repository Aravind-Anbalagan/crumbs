package com.crumbs.trade.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreMarketAnalysisResponseDto {
    
    private String status;
    private String timestamp;
    private PreMarketDataDto data;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PreMarketDataDto {
        private Long id;
        private String name;
        private String expiry;
        
        @JsonProperty("tradingDate")
        private String tradingDate;
        
        private String timestamp;
        
        @JsonProperty("atmStrike")
        private BigDecimal atmStrike;
        
        @JsonProperty("ltpDiff")
        private BigDecimal ltpDiff;
        
        @JsonProperty("midPoint")
        private BigDecimal midPoint;
        
        @JsonProperty("combinedLtp")
        private BigDecimal combinedLtp;
        
        private OptionDataDto ce;
        private OptionDataDto pe;
        
        @JsonProperty("crossPlotTargets")
        private CrossPlotTargetsDto crossPlotTargets;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionDataDto {
        private BigDecimal ltp;
        
        @JsonProperty("prevHigh")
        private BigDecimal prevHigh;
        
        @JsonProperty("prevLow")
        private BigDecimal prevLow;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CrossPlotTargetsDto {
        @JsonProperty("ceTarget")
        private BigDecimal ceTarget;
        
        @JsonProperty("peTarget")
        private BigDecimal peTarget;
    }
}