package com.crumbs.trade.dto;

public class TrendDTO {
    private String direction;     // UP, DOWN, SIDEWAYS
    private String allowedSide;   // BUY, SELL, NONE
    private String tfTrend;       // current chart TF: UP, DOWN, SIDEWAYS
    private String htfTimeFrame;  // e.g. ONE_HOUR
    private String htfTrend;      // higher TF: UP, DOWN, SIDEWAYS
    private double adx;           // ADX(14) on current TF
    private double htfAdx;        // ADX(14) on higher TF
    private double ema20;         // current TF EMA 20
    private double ema50;         // current TF EMA 50
    private String reason;        // short 1-line summary for the UI

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getAllowedSide() { return allowedSide; }
    public void setAllowedSide(String allowedSide) { this.allowedSide = allowedSide; }
    public String getTfTrend() { return tfTrend; }
    public void setTfTrend(String tfTrend) { this.tfTrend = tfTrend; }
    public String getHtfTimeFrame() { return htfTimeFrame; }
    public void setHtfTimeFrame(String htfTimeFrame) { this.htfTimeFrame = htfTimeFrame; }
    public String getHtfTrend() { return htfTrend; }
    public void setHtfTrend(String htfTrend) { this.htfTrend = htfTrend; }
    public double getAdx() { return adx; }
    public void setAdx(double adx) { this.adx = adx; }
    public double getHtfAdx() { return htfAdx; }
    public void setHtfAdx(double htfAdx) { this.htfAdx = htfAdx; }
    public double getEma20() { return ema20; }
    public void setEma20(double ema20) { this.ema20 = ema20; }
    public double getEma50() { return ema50; }
    public void setEma50(double ema50) { this.ema50 = ema50; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}