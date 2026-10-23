package com.crumbs.trade.dto;

import java.math.BigDecimal;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DecisionDTO {
    private String signal;                 // BUY | SELL | WAIT
    private String message;
    private BigDecimal level;
    private String trigger;
    private Map<String, Object> debug;     // hidden unless ?debug=true
}