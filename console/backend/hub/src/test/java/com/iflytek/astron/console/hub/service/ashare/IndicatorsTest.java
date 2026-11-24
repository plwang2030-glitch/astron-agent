package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Bar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndicatorsTest {

    @Test
    void smaHasWarmup() {
        double[] out = Indicators.sma(new double[] {1, 2, 3, 4, 5}, 3);
        assertTrue(Double.isNaN(out[0]) && Double.isNaN(out[1]));
        assertEquals(2, out[2], 1e-9);
        assertEquals(4, out[4], 1e-9);
    }

    @Test
    void rsiExtremes() {
        double[] up = new double[30];
        double[] flat = new double[30];
        for (int i = 0; i < 30; i++) {
            up[i] = 10 + i;
            flat[i] = 10;
        }
        assertEquals(100, Indicators.rsi(up, 14)[29], 1e-9);
        assertEquals(50, Indicators.rsi(flat, 14)[29], 1e-9);
        assertTrue(Double.isNaN(Indicators.rsi(up, 14)[13]));
    }

    @Test
    void rsiOfZigzagRise() {
        // +2% / -1% alternating: average gain about twice the average loss, RSI near 67.
        double[] c = new double[60];
        c[0] = 10;
        for (int i = 1; i < 60; i++) {
            c[i] = c[i - 1] * (i % 2 == 1 ? 1.02 : 0.99);
        }
        double rsi = Indicators.rsi(c, 14)[59];
        assertTrue(rsi > 60 && rsi < 72, "rsi=" + rsi);
    }

    @Test
    void volumeRatioUsesPreviousDays() {
        double[] r = Indicators.volumeRatio(new double[] {100, 100, 100, 100, 100, 300}, 5);
        assertEquals(3, r[5], 1e-9);
        assertTrue(Double.isNaN(r[4]));
    }

    @Test
    void roundDropsNaN() {
        assertNull(Indicators.round(Double.NaN, 2));
        assertEquals(1.23, Indicators.round(1.234, 2));
    }

    @Test
    void latestSummaryOnShortHistory() {
        List<Bar> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 5);
        for (int i = 0; i < 30; i++) {
            double p = 10 + i * 0.1;
            bars.add(new Bar(d.plusDays(i), p, p, p + 0.1, p - 0.1, 1000, 1e6, 1, 1));
        }
        JSONObject s = Indicators.latestSummary(bars);
        assertNotNull(s.get("ma20"));
        // Not enough bars for MA60: reported as null instead of NaN.
        assertNull(s.get("ma60"));
        assertEquals(12.9, s.getDoubleValue("close"), 1e-9);
    }
}
