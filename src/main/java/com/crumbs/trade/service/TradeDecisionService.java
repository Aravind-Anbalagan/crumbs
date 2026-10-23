package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.crumbs.trade.dto.CandleDTO;
import com.crumbs.trade.dto.DecisionDTO;
import com.crumbs.trade.dto.SRLevelDTO;

@Service
public class TradeDecisionService {

    private record C(long t, double o, double h, double l, double c, double v) {}

    // all distances are in ATR; make configurable per instrument/timeframe later
    private static final double ZONE = 0.3, NEAR = 0.6, FAR = 1.5, BREAK = 0.5, MOVE = 1.0,
            IMPULSE = 2.0, ER_MIN = 0.6, STOP = 0.3, MIN_RR = 2.0, WILSON_MIN = 0.55, Z = 1.28;
    private static final int MIN_CANDLES = 50, MIN_SAMPLE = 5, LOOK = 8;

    public DecisionDTO decide(List<CandleDTO> raw, List<SRLevelDTO> sup, List<SRLevelDTO> res,
                              BigDecimal live, String tf) {
        List<C> cs = clean(raw);
        if (cs.size() < MIN_CANDLES) return out("WAIT", "Not enough clean candle data.", null, null, null);
        double atr = atr(cs);
        if (atr <= 0) return out("WAIT", "Cannot measure volatility.", null, null, null);
        double px = (live != null && live.signum() > 0) ? live.doubleValue() : cs.get(cs.size() - 1).c;

        List<Double> S = prices(sup), R = prices(res);
        Double s = nearest(S, px, atr, true);
        Double r = nearest(R, px, atr, false);
        if (s == null && r == null) return out("WAIT", "No strong level nearby.", null, null, null);

        boolean useSupport = r == null || (s != null && Math.abs(px - s) <= Math.abs(r - px));
        return useSupport
                ? evaluate(cs, s, px, atr, tf, true, R)
                : evaluate(mirror(cs), -r, -px, atr, tf, false, neg(S));
    }

    // Runs in "support space". For resistance the inputs are mirrored; sg maps back to real prices.
    private DecisionDTO evaluate(List<C> m, double lvl, double px, double atr, String tf,
                                 boolean sup, List<Double> opp) {
        int n = m.size();
        C k = m.get(n - 1), p = m.get(n - 2);
        double sg = sup ? 1 : -1;
        String act = sup ? "BUY" : "SELL", zone = sup ? "support" : "resistance", who = sup ? "buyers" : "sellers";
        String move = sup ? "drop" : "rally";
        String conf = sup ? "a close above the reversal candle's high" : "a close below the reversal candle's low";
        String pre = "At " + zone + " " + f(sg * lvl) + ": ";
        BigDecimal lv = BigDecimal.valueOf(sg * lvl);
        String waitTrig = (sup ? "Buy only after a close above " : "Sell only after a close below ") + f(sg * lvl);

        // 1) history: held vs broke, counted from candles
        int[] hb = history(m, lvl, atr);
        int held = hb[0], cnt = hb[0] + hb[1];
        double wl = wilson(held, cnt);
        String ev = held + " of " + cnt;

        // 2) approach
        int N = lookback(tf);
        double net = (k.c - m.get(n - 1 - N).c) / atr;          // negative = moving into the level
        double path = 0;
        for (int i = n - N; i < n; i++) path += Math.abs(m.get(i).c - m.get(i - 1).c);
        double er = path == 0 ? 0 : Math.abs(k.c - m.get(n - 1 - N).c) / path;
        boolean impulse = net <= -IMPULSE && er >= ER_MIN;
        double rvol = rvol(m);

        // 3) reaction
        boolean confirmed = reacts(p, lvl, atr) && k.c > p.h;
        boolean watch = reacts(k, lvl, atr);
        boolean accepted = px < lvl - ZONE * atr || k.c < lvl - ZONE * atr;

        Map<String, Object> dbg = new LinkedHashMap<>();
        dbg.put("atr", atr); dbg.put("held", held); dbg.put("sample", cnt); dbg.put("wilsonLow", wl);
        dbg.put("netMoveAtr", net); dbg.put("efficiency", er); dbg.put("impulse", impulse);
        dbg.put("rvol", rvol); dbg.put("confirmed", confirmed); dbg.put("watch", watch);

        if (accepted) {
            return out("WAIT", pre + "price closed beyond the level, so it is breaking, not holding. "
                    + "Wait for a close back inside, or a failed retest.", lv, waitTrig, dbg);
        }
        if (cnt >= MIN_SAMPLE && wl < WILSON_MIN) {
            return out("WAIT", pre + "similar past touches held only " + ev + ". Weak level.", lv, waitTrig, dbg);
        }
        if (impulse && !confirmed) {
            return out("WAIT", pre + "price arrived with a strong one-way " + move + " ("
                            + String.format("%.1f", Math.abs(net)) + " ATR). Wait for a reversal candle, then " + conf + ".",
                    lv, waitTrig, dbg);
        }
        if (!confirmed && !(watch && !impulse)) {
            return out("WAIT", pre + "no reaction from " + who + " yet. Wait for a rejection candle.", lv, waitTrig, dbg);
        }

        double stop = Math.min(Math.min(k.l, p.l), lvl - ZONE * atr) - STOP * atr;
        double target = opp.stream().filter(o -> o > px + atr).min(Double::compare).orElse(px + 3 * atr);
        double risk = px - stop, reward = target - px;
        if (risk <= 0 || reward < MIN_RR * risk) {
            return out("WAIT", pre + who + " reacted, but the next level is too close for the risk (reward/risk "
                    + String.format("%.1f", risk > 0 ? reward / risk : 0) + ").", lv, waitTrig, dbg);
        }
        String sample = cnt < MIN_SAMPLE ? " (low sample: " + ev + ")" : " (held " + ev + ")";
        return out(act, pre + who + " are defending it" + sample
                        + (impulse ? ", but the move in was fast, so use smaller size." : "."),
                lv, act + " near " + f(sg * px) + ", stop " + f(sg * stop) + ", target " + f(sg * target)
                        + " (reward/risk " + String.format("%.1f", reward / risk) + ")", dbg);
    }

    // Touch = one episode entering the zone from above after 3 candles outside it.
    // HELD: no close below level-0.5ATR within LOOK candles and price moved >= 1 ATR up.
    // BROKE: a close below level-0.5ATR within LOOK candles. Anything else is ignored.
    private int[] history(List<C> m, double lvl, double atr) {
        int held = 0, broke = 0, n = m.size();
        double z = ZONE * atr;
        for (int i = 4; i < n - LOOK - 1; i++) {
            C c = m.get(i);
            if (!(c.l <= lvl + z && c.h >= lvl - z)) continue;
            boolean fresh = true;
            for (int j = i - 3; j < i; j++) {
                C b = m.get(j);
                if (b.l <= lvl + z && b.h >= lvl - z) fresh = false;
            }
            if (!fresh || m.get(i - 1).c <= lvl) continue;
            boolean broken = false;
            double hi = c.h;
            for (int j = i; j <= i + LOOK; j++) {
                C f = m.get(j);
                if (f.c < lvl - BREAK * atr) { broken = true; break; }
                hi = Math.max(hi, f.h);
            }
            if (broken) broke++; else if (hi >= lvl + MOVE * atr) held++;
        }
        return new int[]{held, broke};
    }

    // rejection wick at the level, or a false break that closed back above it
    private boolean reacts(C c, double lvl, double atr) {
        double range = c.h - c.l;
        if (range <= 0 || c.l > lvl + ZONE * atr || c.c <= lvl) return false;
        double lowerWick = Math.min(c.o, c.c) - c.l;
        return lowerWick / range >= 0.5 || c.l < lvl - ZONE * atr;
    }

    private double wilson(int s, int n) {
        if (n == 0) return 0;
        double p = (double) s / n, z2 = Z * Z;
        return (p + z2 / (2 * n) - Z * Math.sqrt((p * (1 - p) + z2 / (4 * n)) / n)) / (1 + z2 / n);
    }

    private int lookback(String tf) {
        return switch (tf.toUpperCase()) {
            case "ONE_MINUTE" -> 20;
            case "FIVE_MINUTE", "FIFTEEN_MINUTE" -> 12;
            case "THIRTY_MINUTE", "ONE_HOUR" -> 8;
            default -> 5;
        };
    }

    private double rvol(List<C> m) {
        int n = m.size();
        double avg = 0;
        for (int i = n - 21; i < n - 1; i++) avg += m.get(i).v;
        avg /= 20;
        return avg > 0 ? m.get(n - 1).v / avg : 0;          // 0 = no volume data
    }

    private double atr(List<C> cs) {
        double sum = 0; int n = cs.size();
        for (int i = n - 14; i < n; i++) {
            C c = cs.get(i), p = cs.get(i - 1);
            sum += Math.max(c.h - c.l, Math.max(Math.abs(c.h - p.c), Math.abs(c.l - p.c)));
        }
        return sum / 14;
    }

    private Double nearest(List<Double> lv, double px, double atr, boolean support) {
        Double best = null; double bd = Double.MAX_VALUE;
        for (double l : lv) {
            double d = support ? px - l : l - px;          // positive = price on the normal side of the level
            if (d >= -FAR * atr && d <= NEAR * atr && Math.abs(d) < bd) { bd = Math.abs(d); best = l; }
        }
        return best;
    }

    // ---- helpers ----
    private List<C> clean(List<CandleDTO> raw) {
        Map<Long, C> m = new TreeMap<>();
        if (raw == null) return new ArrayList<>();
        for (CandleDTO d : raw) {
            if (d.getOpen() == null || d.getHigh() == null || d.getLow() == null || d.getClose() == null) continue;
            C c = new C(d.getTime(), d.getOpen().doubleValue(), d.getHigh().doubleValue(),
                    d.getLow().doubleValue(), d.getClose().doubleValue(),
                    d.getVolume() == null ? 0 : d.getVolume().doubleValue());
            C old = m.get(c.t);
            if (old == null || (old.v == 0 && c.v > 0)) m.put(c.t, c);
        }
        return new ArrayList<>(m.values());
    }

    private List<C> mirror(List<C> cs) {
        return cs.stream().map(c -> new C(c.t, -c.o, -c.l, -c.h, -c.c, c.v)).collect(Collectors.toList());
    }

    private List<Double> prices(List<SRLevelDTO> l) {
        return l == null ? new ArrayList<>()
                : l.stream().map(x -> x.getPrice().doubleValue()).collect(Collectors.toList());
    }

    private List<Double> neg(List<Double> l) { return l.stream().map(x -> -x).collect(Collectors.toList()); }
    private String f(double v) { return String.format("%.2f", v); }

    private DecisionDTO out(String sig, String msg, BigDecimal lvl, String trig, Map<String, Object> dbg) {
        DecisionDTO d = new DecisionDTO();
        d.setSignal(sig); d.setMessage(msg); d.setLevel(lvl); d.setTrigger(trig); d.setDebug(dbg);
        return d;
    }
}