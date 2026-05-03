package com.crumbs.trade.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DepthItem(
    int number,
    String quantity,
    String price
) {}