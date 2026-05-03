package com.crumbs.trade.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SamcoOptionChainResponse(
    String serverTime,
    String status,
    String statusMessage,
    List<OptionChainDetail> optionChainDetails
) {}