package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Bar;

import java.util.Arrays;
import java.util.List;

/** Technical indicators over daily bars; values before an indicator's warm-up are {@code NaN}. */
public final class Indicators {

    private Indicators() {}

    public static double[] closes(List<Bar> bars) {
        return bars.stream().mapToDouble(Bar::close).toArray();
    }

    public static double[] volumes(List<Bar> bars) {
        return bars.stream().mapToDouble(Bar::volume).toArray();
    }

    public static double[] sma(double[] v, int n) {
        double[] out = nanArray(v.length);
        double sum = 0;
        for (int i = 0; i < v.length; i++) {
            sum += v[i];
            if (i >= n) {
                sum -= v[i - n];
            }
            if (i >= n - 1) {
                out[i] = sum / n;
            }
        }
        return out;
    }

    public static double[] ema(double[] v, int n) {
        double[] out = nanArray(v.length);
        if (v.length == 0) {
            return out;
        }
        double k = 2.0 / (n + 1);
        out[0] = v[0];
        for (int i = 1; i < v.length; i++) {
            out[i] = v[i] * k + out[i - 1] * (1 - k);
        }
        return out;
    }

    /** Wilder RSI. */
    public static double[] rsi(double[] close, int n) {
        double[] out = nanArray(close.length);
        if (close.length <= n) {
            return out;
        }
        double gain = 0;
        double loss = 0;
        for (int i = 1; i <= n; i++) {
            double d = close[i] - close[i - 1];
            gain += Math.max(d, 0);
            loss += Math.max(-d, 0);
        }
        gain /= n;
        loss /= n;
        out[n] = rsiValue(gain, loss);
        for (int i = n + 1; i < close.length; i++) {
            double d = close[i] - close[i - 1];
            gain = (gain * (n - 1) + Math.max(d, 0)) / n;
            loss = (loss * (n - 1) + Math.max(-d, 0)) / n;
            out[i] = rsiValue(gain, loss);
        }
        return out;
    }

    private static double rsiValue(double gain, double loss) {
        if (loss == 0) {
            return gain == 0 ? 50 : 100;
        }
        return 100 - 100 / (1 + gain / loss);
    }

    /** Today's volume over the average of the previous {@code n} days. */
    public static double[] volumeRatio(double[] vol, int n) {
        double[] out = nanArray(vol.length);
        double sum = 0;
        for (int i = 0; i < vol.length; i++) {
            if (i >= n) {
                double avg = sum / n;
                out[i] = avg > 0 ? vol[i] / avg : Double.NaN;
                sum -= vol[i - n];
            }
            sum += vol[i];
        }
        return out;
    }

    /** MACD(12, 26, 9): DIF, DEA and the histogram {@code 2 * (DIF - DEA)}. */
    public record Macd(double[] dif, double[] dea, double[] hist) {}

    public static Macd macd(double[] close) {
        double[] fast = ema(close, 12);
        double[] slow = ema(close, 26);
        double[] dif = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            dif[i] = fast[i] - slow[i];
        }
        double[] dea = ema(dif, 9);
        double[] hist = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            hist[i] = 2 * (dif[i] - dea[i]);
        }
        return new Macd(dif, dea, hist);
    }

    /** Summary of the latest bar for the analysis tool. */
    public static JSONObject latestSummary(List<Bar> bars) {
        JSONObject out = new JSONObject();
        if (bars.isEmpty()) {
            return out;
        }
        double[] c = closes(bars);
        int i = c.length - 1;
        Bar last = bars.get(i);
        out.put("date", last.date().toString());
        out.put("close", round(last.close(), 3));
        out.put("ma5", round(sma(c, 5)[i], 3));
        out.put("ma10", round(sma(c, 10)[i], 3));
        out.put("ma20", round(sma(c, 20)[i], 3));
        out.put("ma60", round(sma(c, 60)[i], 3));
        Macd m = macd(c);
        out.put("macdDif", round(m.dif()[i], 3));
        out.put("macdDea", round(m.dea()[i], 3));
        out.put("macdHist", round(m.hist()[i], 3));
        out.put("rsi14", round(rsi(c, 14)[i], 1));
        out.put("volumeRatio5", round(volumeRatio(volumes(bars), 5)[i], 2));
        int from20 = Math.max(0, i - 19);
        out.put("high20", round(Arrays.stream(bars.subList(from20, i + 1).stream().mapToDouble(Bar::high).toArray())
                .max()
                .orElse(Double.NaN), 3));
        out.put("low20", round(Arrays.stream(bars.subList(from20, i + 1).stream().mapToDouble(Bar::low).toArray())
                .min()
                .orElse(Double.NaN), 3));
        out.put("return5dPct", pctChange(c, i, 5));
        out.put("return20dPct", pctChange(c, i, 20));
        out.put("return60dPct", pctChange(c, i, 60));
        return out;
    }

    private static Double pctChange(double[] c, int i, int n) {
        if (i - n < 0 || c[i - n] == 0) {
            return null;
        }
        return round((c[i] / c[i - n] - 1) * 100, 2);
    }

    /** Rounded value, or {@code null} for NaN so JSON stays clean. */
    public static Double round(double v, int scale) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return null;
        }
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }

    private static double[] nanArray(int n) {
        double[] out = new double[n];
        Arrays.fill(out, Double.NaN);
        return out;
    }
}
