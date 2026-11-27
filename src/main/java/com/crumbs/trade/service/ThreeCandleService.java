package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.crumbs.trade.dto.CandleDTO;
import com.crumbs.trade.dto.ThreeCandleDTO;

@Service
public class ThreeCandleService {

    private record C(long t, double o, double h, double l, double c) {}

    public ThreeCandleDTO analyze(List<CandleDTO> raw, String timeFrame) {
        ThreeCandleDTO dto = new ThreeCandleDTO();
        List<C> cs = clean(raw);
        int n = cs.size();
        if (n < 4) return dto;

        String state = "FLAT";
        double entryPrice = 0;
        long entryTime = 0;
        double sl = 0;

        List<ThreeCandleDTO.Event> events = new ArrayList<>();
        List<ThreeCandleDTO.TrailPoint> trailLine = new ArrayList<>();

        // Continuous across all candles and timeframes, always evaluating against the last 3 completed bars
        for (int i = 3; i < n; i++) {
            C c1 = cs.get(i - 3);
            C c2 = cs.get(i - 2);
            C c3 = cs.get(i - 1);
            C c4 = cs.get(i);

            double boxHigh   = Math.max(c1.h, Math.max(c2.h, c3.h));
            double boxLow    = Math.min(c1.l, Math.min(c2.l, c3.l));
            double minClose3 = Math.min(c1.c, Math.min(c2.c, c3.c));
            double maxClose3 = Math.max(c1.c, Math.max(c2.c, c3.c));

            if ("FLAT".equals(state)) {
                if (c4.c > boxHigh) {
                    state = "BUY";
                    entryPrice = c4.c;
                    entryTime = c4.t;
                    sl = minClose3;
                    events.add(new ThreeCandleDTO.Event(c4.t, "BUY", bd(c4.c), "BUY"));
                    trailLine.add(new ThreeCandleDTO.TrailPoint(c4.t, bd(sl), "BUY"));
                } else if (c4.c < boxLow) {
                    state = "SELL";
                    entryPrice = c4.c;
                    entryTime = c4.t;
                    sl = maxClose3;
                    events.add(new ThreeCandleDTO.Event(c4.t, "SELL", bd(c4.c), "SELL"));
                    trailLine.add(new ThreeCandleDTO.TrailPoint(c4.t, bd(sl), "SELL"));
                }
            } else if ("BUY".equals(state)) {
                // One-way ratchet up from completed C1..C3
                sl = Math.max(sl, minClose3);

                if (c4.o < sl) {
                    // Gap-down open past SL
                    events.add(new ThreeCandleDTO.Event(c4.t, "SL", bd(c4.o), "SL"));
                    state = "FLAT";
                    entryPrice = 0;
                    entryTime = 0;
                    sl = 0;
                } else if (c4.c < boxLow) {
                    // Opposite 3-candle breakdown → flip directly to SELL
                    state = "SELL";
                    entryPrice = c4.c;
                    entryTime = c4.t;
                    sl = maxClose3;
                    events.add(new ThreeCandleDTO.Event(c4.t, "SELL", bd(c4.c), "SELL"));
                    trailLine.add(new ThreeCandleDTO.TrailPoint(c4.t, bd(sl), "SELL"));
                } else if (c4.c < sl) {
                    // Trailing SL hit on candle close
                    events.add(new ThreeCandleDTO.Event(c4.t, "SL", bd(c4.c), "SL"));
                    state = "FLAT";
                    entryPrice = 0;
                    entryTime = 0;
                    sl = 0;
                } else {
                    trailLine.add(new ThreeCandleDTO.TrailPoint(c4.t, bd(sl), "BUY"));
                }
            } else if ("SELL".equals(state)) {
                // One-way ratchet down from completed C1..C3
                sl = Math.min(sl, maxClose3);

                if (c4.o > sl) {
                    // Gap-up open past SL
                    events.add(new ThreeCandleDTO.Event(c4.t, "SL", bd(c4.o), "SL"));
                    state = "FLAT";
                    entryPrice = 0;
                    entryTime = 0;
                    sl = 0;
                } else if (c4.c > boxHigh) {
                    // Opposite 3-candle breakout → flip directly to BUY
                    state = "BUY";
                    entryPrice = c4.c;
                    entryTime = c4.t;
                    sl = minClose3;
                    events.add(new ThreeCandleDTO.Event(c4.t, "BUY", bd(c4.c), "BUY"));
                    trailLine.add(new ThreeCandleDTO.TrailPoint(c4.t, bd(sl), "BUY"));
                } else if (c4.c > sl) {
                    // Trailing SL hit on candle close
                    events.add(new ThreeCandleDTO.Event(c4.t, "SL", bd(c4.c), "SL"));
                    state = "FLAT";
                    entryPrice = 0;
                    entryTime = 0;
                    sl = 0;
                } else {
                    trailLine.add(new ThreeCandleDTO.TrailPoint(c4.t, bd(sl), "SELL"));
                }
            }
        }

        // Active 3-candle box & live trailing SL from the last 3 completed candles
        C l1 = cs.get(n - 3);
        C l2 = cs.get(n - 2);
        C l3 = cs.get(n - 1);
        dto.setBoxHigh(bd(Math.max(l1.h, Math.max(l2.h, l3.h))));
        dto.setBoxLow(bd(Math.min(l1.l, Math.min(l2.l, l3.l))));

        if ("BUY".equals(state)) {
            sl = Math.max(sl, Math.min(l1.c, Math.min(l2.c, l3.c)));
        } else if ("SELL".equals(state)) {
            sl = Math.min(sl, Math.max(l1.c, Math.max(l2.c, l3.c)));
        }

        dto.setStatus(state);
        if (!"FLAT".equals(state)) {
            dto.setEntryPrice(bd(entryPrice));
            dto.setEntryTime(entryTime);
            dto.setTrailingSl(bd(sl));
        }
        dto.setEvents(events);
        dto.setTrailLine(trailLine);
        return dto;
    }

    private List<C> clean(List<CandleDTO> raw) {
        Map<Long, C> map = new TreeMap<>();
        if (raw == null) return new ArrayList<>();
        for (CandleDTO d : raw) {
            if (d == null || d.getOpen() == null || d.getHigh() == null
                    || d.getLow() == null || d.getClose() == null) continue;
            long t = d.getTime() > 10_000_000_000L ? d.getTime() / 1000L : d.getTime();
            map.put(t, new C(t,
                    d.getOpen().doubleValue(),
                    d.getHigh().doubleValue(),
                    d.getLow().doubleValue(),
                    d.getClose().doubleValue()));
        }
        return new ArrayList<>(map.values());
    }

    private BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}