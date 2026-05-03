package com.crumbs.trade.utility;

public enum TradingInstrument {
    
    NIFTY(
        "NFO",        // Options Exchange
        "NSE",        // Spot Exchange (For fetching LTP)
        "NIFTY 50",   // Spot Symbol for Samco
        50.0,         // Distance between strikes
        500,          // Tracking range (+/-)
        "65"          // Lot Size
    ),
    CRUDEOIL(
        "MCX",        // Options Exchange
        "MCX",        // Spot Exchange
        "CRUDEOIL",   // Spot Symbol for Samco
        100.0,        // Distance between strikes (Adjust to 50 if your specific contract uses 50)
        1000,         // Tracking range (+/-) for high volatility
        "100"         // Lot Size (100 Barrels)
    );

    public final String optionsExchange;
    public final String spotExchange;
    public final String spotSymbol;
    public final double strikeGap;
    public final int trackingRange;
    public final String lotSize;

    TradingInstrument(String optionsExchange, String spotExchange, String spotSymbol, 
                      double strikeGap, int trackingRange, String lotSize) {
        this.optionsExchange = optionsExchange;
        this.spotExchange = spotExchange;
        this.spotSymbol = spotSymbol;
        this.strikeGap = strikeGap;
        this.trackingRange = trackingRange;
        this.lotSize = lotSize;
    }
}