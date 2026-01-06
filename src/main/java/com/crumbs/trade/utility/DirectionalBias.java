package com.crumbs.trade.utility;

public enum DirectionalBias {
    BULLISH("Bulls active"),
    BEARISH("Bears active"),
    NEUTRAL("No clear direction");
    
    private final String description;
    
    DirectionalBias(String description) {
        this.description = description;
    }
    
    public String getDescription() { return description; }
}