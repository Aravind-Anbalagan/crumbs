package com.crumbs.trade.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class CombinedChartResponse {
    private List<CombinedChartPoint> data = new ArrayList<>();
}
