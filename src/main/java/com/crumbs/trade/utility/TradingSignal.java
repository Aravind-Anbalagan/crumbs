package com.crumbs.trade.utility;

public enum TradingSignal {
    STRADDLE_BUY_SETUP("✅ Straddle Buy", "Good entry for buying straddle"),
    STRADDLE_SELL_SETUP("💰 Straddle Sell", "Good entry for selling straddle"),
    PREMIUM_DECAY("⏳ Premium Decay", "Time decay in progress"),
    PREMIUM_SURGE("🔥 Premium Surge", "Volatility spike detected"),
    BULLISH_MOVE("🚀 Bullish Move", "Strong upward movement"),
    BEARISH_MOVE("⬇️ Bearish Move", "Strong downward movement"),
    RANGE_BOUND("↔️ Range Bound", "Sideways movement"),
    NEUTRAL("➖ Neutral", "No clear signal");
    
    private final String displayName;
    private final String description;
    
    TradingSignal(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
    
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}