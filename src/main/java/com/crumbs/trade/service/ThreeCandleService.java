package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.crumbs.trade.dto.CandleDTO;
import com.crumbs.trade.dto.ThreeCandleDTO;

@Service
public class ThreeCandleService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private record C(long t, double o, double h, double l, double c) {}

    public ThreeCandleDTO analyze(List<CandleDTO> raw, String timeFrame) {
        ThreeCandleDTO dto = new ThreeCandleDTO();
        List<C> cs = clean(raw);
        if (cs.size() < 4) return dto;

        boolean intraday = !"ONE_DAY".equalsIgnoreCase(timeFrame);

        String state = "FLAT";
        double entryPrice = 0;
        long entryTime = 0;
        double sl = 0;

        LocalDate currentDay = null;
        int dayBars = 0;

        List<ThreeCandleDTO.Event> events = new ArrayList<>();
        List<ThreeCandleDTO.TrailPoint> trailLine = new ArrayList<>();

        for (int i = 0; i < cs.size(); i++) {
            C c4 = cs.get(i);

            // Intraday: reset cleanly on each new trading day so overnight gaps don't carry over
            if (intraday) {
                LocalDate d = Instant.ofEpochSecond(c4.t).atZone(IST).toLocalDate();
                if (!d.equals(currentDay)) {
                    currentDay = d;
                    dayBars = 0;
                    state = "FLAT";
                    entryPrice = 0;
                    entryTime = 0;
                    sl = 0;
                }
            }
            dayBars++;

            // Need 3 prior completed candles in the current session (or series for ONE_DAY)
            if (dayBars <= 3) continue;

            C c1 = cs.get(i - 3);
            C c2 = cs.get(i - 2);
            C c3 = cs.get(i - 1);

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
                sl = Math.max(sl, minClose3);

                if (c4.c < boxLow) {
                    state = "SELL";
                    entryPrice = c4.c;
                    entryTime = c4.t;
                    sl = maxClose3;
                    events.add(new ThreeCandleDTO.Event(c4.t, "SELL", bd(c4.c), "SELL"));
                    trailLine.add(new ThreeCandleDTO.TrailPoint(c4.t, bd(sl), "SELL"));
                } else if (c4.c < sl) {
                    trailLine.add(new ThreeCandleDTO.TrailPoint(c4.t, bd(sl), "BUY"));
                    events.add(new ThreeCandleDTO.Event(c4.t, "SL", bd(c4.c), "SL"));
                    state = "FLAT";
                    entryPrice = 0;
                    entryTime = 0;
                    sl = 0;
                } else {
                    trailLine.add(new ThreeCandleDTO.TrailPoint(c4.t, bd(sl), "BUY"));
                }
            } else if ("SELL".equals(state)) {
                sl = Math.min(sl, maxClose3);

                if (c4.c > boxHigh) {
                    state = "BUY";
                    entryPrice = c4.c;
                    entryTime = c4.t;
                    sl = minClose3;
                    events.add(new ThreeCandleDTO.Event(c4.t, "BUY", bd(c4.c), "BUY"));
                    trailLine.add(new ThreeCandleDTO.TrailPoint(c4.t, bd(sl), "BUY"));
                } else if (c4.c > sl) {
                    trailLine.add(new ThreeCandleDTO.TrailPoint(c4.t, bd(sl), "SELL"));
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

        // Active 3-candle box & live trailing SL for the next candle
        int n = cs.size();
        if (dayBars >= 3) {
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
            if (d.getOpen() == null || d.getHigh() == null
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