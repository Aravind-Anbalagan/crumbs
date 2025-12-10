package com.crumbs.trade.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class StraddleChartResponse {
    private List<ChartPoint> ce = new ArrayList<>();
    private List<ChartPoint> pe = new ArrayList<>();
    private List<ChartPoint> spot = new ArrayList<>();
}
