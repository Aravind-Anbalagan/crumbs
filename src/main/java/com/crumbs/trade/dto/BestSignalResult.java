package com.crumbs.trade.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@AllArgsConstructor   // generates constructor with all fields
@NoArgsConstructor    // generates no-args constructor
public class BestSignalResult {
    private String signal;
    private double confidence;
    private List<String> reasons;
    private String type;
}