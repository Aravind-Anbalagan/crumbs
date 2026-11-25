package com.crumbs.trade.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class ThreeCandleDTO {

    private String status = "FLAT";       // BUY, SELL, FLAT
    private BigDecimal entryPrice;
    private Long entryTime;               // epoch seconds
    private BigDecimal trailingSl;
    private BigDecimal boxHigh;           // current 3-candle breakout high
    private BigDecimal boxLow;            // current 3-candle breakout low
    private List<Event> events = new ArrayList<>();
    private List<TrailPoint> trailLine = new ArrayList<>();

    public static class Event {
        private long time;
        private String type;              // BUY, SELL, SL
        private BigDecimal price;
        private String text;

        public Event(long time, String type, BigDecimal price, String text) {
            this.time = time;
            this.type = type;
            this.price = price;
            this.text = text;
        }

        public long getTime() { return time; }
        public String getType() { return type; }
        public BigDecimal getPrice() { return price; }
        public String getText() { return text; }
    }

    public static class TrailPoint {
        private long time;
        private BigDecimal value;
        private String side;              // BUY, SELL

        public TrailPoint(long time, BigDecimal value, String side) {
            this.time = time;
            this.value = value;
            this.side = side;
        }

        public long getTime() { return time; }
        public BigDecimal getValue() { return value; }
        public String getSide() { return side; }
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }
    public Long getEntryTime() { return entryTime; }
    public void setEntryTime(Long entryTime) { this.entryTime = entryTime; }
    public BigDecimal getTrailingSl() { return trailingSl; }
    public void setTrailingSl(BigDecimal trailingSl) { this.trailingSl = trailingSl; }
    public BigDecimal getBoxHigh() { return boxHigh; }
    public void setBoxHigh(BigDecimal boxHigh) { this.boxHigh = boxHigh; }
    public BigDecimal getBoxLow() { return boxLow; }
    public void setBoxLow(BigDecimal boxLow) { this.boxLow = boxLow; }
    public List<Event> getEvents() { return events; }
    public void setEvents(List<Event> events) { this.events = events; }
    public List<TrailPoint> getTrailLine() { return trailLine; }
    public void setTrailLine(List<TrailPoint> trailLine) { this.trailLine = trailLine; }
}