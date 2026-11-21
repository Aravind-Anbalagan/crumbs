package com.crumbs.trade.dto;

import lombok.Data;

@Data
public class VwapStraddleUpdateRequest {
    private Integer entryHitTarget;
    private Integer exitHitTarget;
    private String alertFlag;       // "Y" or "N"
    private Boolean isActive;
}