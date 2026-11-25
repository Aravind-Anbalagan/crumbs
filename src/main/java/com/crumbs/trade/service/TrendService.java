package com.crumbs.trade.service;

import java.util.List;
import org.springframework.stereotype.Service;
import com.crumbs.trade.dto.CandleDTO;
import com.crumbs.trade.dto.TrendDTO;

@Service
public class TrendService {

    private static final int    EMA_FAST      = 20;
    private static final int    EMA_SLOW      = 50;
    private static final int    ADX_PERIOD    = 14;
    private static final double ADX_THRESHOLD = 20.0;

    /** Maps each chart timeframe to its single higher ("boss") timeframe. */
    public String parentTimeFrame(String tf) {
        if (tf == null) return "ONE_HOUR";
        return switch (tf.toUpperCase()) {
            case "ONE_MINUTE"     -> "FIFTEEN_MINUTE";
            case "FIVE_MINUTE"    -> "ONE_HOUR";
            case "FIFTEEN_MINUTE" -> "ONE_HOUR";
            case "THIRTY_MINUTE"  -> "ONE_DAY";
            case "ONE_HOUR"       -> "ONE_DAY";
            default               -> "ONE_DAY";
        };
    }

    public TrendDTO analyze(List<CandleDTO> candles, String tf,
                            List<CandleDTO> htfCandles, String htfTf) {
        TrendDTO dto = new TrendDTO();
        dto.setHtfTimeFrame(htfTf);

        TfResult cur = evaluateTf(candles);
        TfResult htf = (htfCandles == null || htfCandles.isEmpty()) ? cur : evaluateTf(htfCandles);

        dto.setTfTrend(cur.dir);
        dto.setHtfTrend(htf.dir);
        dto.setAdx(round1(cur.adx));
        dto.setHtfAdx(round1(htf.adx));
        dto.setEma20(round2(cur.ema20));
        dto.setEma50(round2(cur.ema50));

        boolean strong = cur.adx >= ADX_THRESHOLD && htf.adx >= ADX_THRESHOLD;

        if ("UP".equals(htf.dir) && "UP".equals(cur.dir) && strong) {
            dto.setDirection("UP");
            dto.setAllowedSide("BUY");
            dto.setReason(shortTf(htfTf) + " & " + shortTf(tf)
                    + " both UP (ADX " + dto.getAdx() + ") — BUY only, ignore resistance shorts.");
        } else if ("DOWN".equals(htf.dir) && "DOWN".equals(cur.dir) && strong) {
            dto.setDirection("DOWN");
            dto.setAllowedSide("SELL");
            dto.setReason(shortTf(htfTf) + " & " + shortTf(tf)
                    + " both DOWN (ADX " + dto.getAdx() + ") — SELL only, ignore support longs.");
        } else {
            dto.setDirection("SIDEWAYS");
            dto.setAllowedSide("NONE");
            if (!strong) {
                dto.setReason("Weak trend (ADX " + dto.getAdx() + " / " + shortTf(htfTf)
                        + " " + dto.getHtfAdx() + " < " + (int) ADX_THRESHOLD + ") — stay out.");
            } else {
                dto.setReason("Timeframes not aligned (" + shortTf(htfTf) + ": " + htf.dir
                        + ", " + shortTf(tf) + ": " + cur.dir + ") — wait for alignment.");
            }
        }
        return dto;
    }

    private record TfResult(String dir, double ema20, double ema50, double adx) {}

    private TfResult evaluateTf(List<CandleDTO> cs) {
        if (cs == null || cs.size() < EMA_SLOW) {
            return new TfResult("SIDEWAYS", 0, 0, 0);
        }
        double close = cs.get(cs.size() - 1).getClose().doubleValue();
        double ema20 = ema(cs, EMA_FAST);
        double ema50 = ema(cs, EMA_SLOW);
        double adx   = adx(cs, ADX_PERIOD);

        String dir = "SIDEWAYS";
        if (close > ema20 && ema20 > ema50)      dir = "UP";
        else if (close < ema20 && ema20 < ema50) dir = "DOWN";

        return new TfResult(dir, ema20, ema50, adx);
    }

    private double ema(List<CandleDTO> cs, int period) {
        double sum = 0;
        for (int i = 0; i < period; i++) sum += cs.get(i).getClose().doubleValue();
        double val = sum / period;
        double k = 2.0 / (period + 1);
        for (int i = period; i < cs.size(); i++) {
            double c = cs.get(i).getClose().doubleValue();
            val = c * k + val * (1.0 - k);
        }
        return val;
    }

    private double adx(List<CandleDTO> cs, int period) {
        int n = cs.size();
        if (n < period * 2 + 1) return 0;

        double smTR = 0, smPlus = 0, smMinus = 0;
        for (int i = 1; i <= period; i++) {
            double[] d = dmTr(cs.get(i - 1), cs.get(i));
            smTR += d[0]; smPlus += d[1]; smMinus += d[2];
        }

        double dxSum = dx(smTR, smPlus, smMinus);
        double adx = 0;

        for (int i = period + 1; i < n; i++) {
            double[] d = dmTr(cs.get(i - 1), cs.get(i));
            smTR    = smTR    - (smTR    / period) + d[0];
            smPlus  = smPlus  - (smPlus  / period) + d[1];
            smMinus = smMinus - (smMinus / period) + d[2];

            double curDx = dx(smTR, smPlus, smMinus);
            if (i < period * 2) {
                dxSum += curDx;
                if (i == period * 2 - 1) adx = dxSum / period;
            } else {
                adx = ((adx * (period - 1)) + curDx) / period;
            }
        }
        return adx;
    }

    private double[] dmTr(CandleDTO prev, CandleDTO cur) {
        double h = cur.getHigh().doubleValue(),  l = cur.getLow().doubleValue();
        double ph = prev.getHigh().doubleValue(), pl = prev.getLow().doubleValue(), pc = prev.getClose().doubleValue();
        double tr      = Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));
        double upMove  = h - ph;
        double dnMove  = pl - l;
        double plusDM  = (upMove > dnMove && upMove > 0) ? upMove : 0;
        double minusDM = (dnMove > upMove && dnMove > 0) ? dnMove : 0;
        return new double[]{ tr, plusDM, minusDM };
    }

    private double dx(double tr, double plusDM, double minusDM) {
        if (tr <= 0) return 0;
        double pdi = 100.0 * plusDM / tr;
        double mdi = 100.0 * minusDM / tr;
        double sum = pdi + mdi;
        return sum == 0 ? 0 : 100.0 * Math.abs(pdi - mdi) / sum;
    }

    private String shortTf(String tf) {
        return switch (tf.toUpperCase()) {
            case "ONE_MINUTE"     -> "1M";
            case "FIVE_MINUTE"    -> "5M";
            case "FIFTEEN_MINUTE" -> "15M";
            case "THIRTY_MINUTE"  -> "30M";
            case "ONE_HOUR"       -> "1H";
            default               -> "1D";
        };
    }

    private double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}