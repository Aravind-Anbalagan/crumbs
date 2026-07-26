package com.crumbs.trade.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OptionChainDetail(
    String tradingSymbol,
    String instrumentToken, // <--- ADDED THIS FIELD FOR THE API TOKEN
    String exchange,
    String underLyingSymbol,
    String strikePrice,
    String expiryDate,
    String optionType,
    String spotPrice,
    String lastTradedPrice, // Changed to String to match your Service's parseDoubleSafely logic
    String openInterest,
    String impliedVolatility,
    String delta,
    String gamma,
    String theta,
    String vega,
    List<DepthItem> bestBids,
    List<DepthItem> bestAsks
) {}