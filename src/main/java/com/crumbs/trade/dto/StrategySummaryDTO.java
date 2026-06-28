package com.crumbs.trade.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StrategySummaryDTO {
    private Long id;
    private String name;
    private String active;
    private String exchange;
    private String expiry;
    private String live;
    private String symbol;
    private String token;
    private String tradingsymbol;
    private int quantity;
}