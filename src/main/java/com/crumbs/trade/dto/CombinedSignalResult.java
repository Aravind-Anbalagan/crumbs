package com.crumbs.trade.dto;

import lombok.Data;

@Data
public class CombinedSignalResult {
    private String combineSignal;
    private String combineConfidence;
    private String combineReasonSummary;
    private String combineDetailedReason;
    private int combineBuyVotes;
    private int combineSellVotes;
    private int combineHoldVotes;
}