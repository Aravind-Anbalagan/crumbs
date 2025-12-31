package com.crumbs.trade.dto;



import java.util.List;

import com.crumbs.trade.utility.AdviceState;

import lombok.Data;

@Data
public class AdviceStatusDTO {

    private String symbol;
    private AdviceState state;
    private String summary;
    private List<String> details;
    private String nextAction;
}
