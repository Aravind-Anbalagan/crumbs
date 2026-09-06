package com.crumbs.trade.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class DominanceSummaryDto {
    private String symbol;
    private int ceCount;
    private int peCount;
    private int totalCount;
    private double cePercentage;
    private double pePercentage;
    private String dominance;
    private LocalDateTime evaluatedAt;
}