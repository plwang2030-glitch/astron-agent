package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Bar;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Series;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The custom rule language that lets agents invent new entry / exit rules. */
class RuleExpressionTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 6);

    private static List<Bar> bars(double... closes) {
        List<Bar> out = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            double c = closes[i];
            out.add(new Bar(START.plusDays(i), c, c, c + 0.5, c - 0.5, 100 + i, 0, 0, 0));
        }
        return out;
    }

    private static double[] eval(String rule, List<Bar> bars) {
        return RuleExpression.parse(rule).evaluate(bars, Map.of());
    }

    @Test
    void arithmeticComparisonAndLogic() {
        List<Bar> b = bars(10, 11, 12, 13);
        assertEquals(1, eval("close > 11 and close < 13", b)[2]);
        assertEquals(0, eval("close > 11 and close < 13", b)[3]);
        assertEquals(1, eval("not close > 12 or close >= 13", b)[3]);
        assertEquals(24, eval("(close + 12) * 2 / 2", b)[2], 1e-9);
        assertEquals(-1, eval("-1", b)[0], 1e-9);
    }

    @Test
    void lookBackFunctionsExcludeTheCurrentBar() {
        List<Bar> b = bars(10, 12, 11, 15);
        // Highest close of the 3 bars before bar 3 is 12.
        assertEquals(12, eval("highest(close,3)", b)[3], 1e-9);
        assertEquals(10, eval("lowest(close,3)", b)[3], 1e-9);
        assertTrue(Double.isNaN(eval("highest(close,3)", b)[2]));
        assertEquals(11, eval("ref(close,1)", b)[3], 1e-9);
        assertEquals(50, eval("roc(close,3)", b)[3], 1e-9);
        assertEquals(13, eval("ma(close,2)", b)[3], 1e-9);
    }

    @Test
    void crossesHappenOnOneBar() {
        List<Bar> b = bars(10, 10, 10, 12, 13, 14);
        double[] up = eval("cross_up(close, ma(close,3))", b);
        assertEquals(1, up[3]);
        assertEquals(0, up[4]);
        assertTrue(Double.isNaN(up[1]), "MA not ready yet");
    }

    @Test
    void indexSeriesIsAlignedByDate() {
        List<Bar> b = bars(10, 11);
        double[] v = RuleExpression.parse("index > 3000").evaluate(b, Map.of(START, 2990.0, START.plusDays(1), 3010.0));
        assertEquals(0, v[0]);
        assertEquals(1, v[1]);
    }

    @Test
    void rejectsInvalidOrUnsafeText() {
        assertNotNull(RuleExpression.check(""));
        assertNotNull(RuleExpression.check("close >"));
        assertNotNull(RuleExpression.check("system(1)"));
        assertNotNull(RuleExpression.check("ma(close)"));
        assertNotNull(RuleExpression.check("ma(close,0)"));
        assertNotNull(RuleExpression.check("ma(close,2.5)"));
        assertNotNull(RuleExpression.check("ma(close,999)"));
        assertNotNull(RuleExpression.check("close > 1; drop table"));
        assertNotNull(RuleExpression.check("close".repeat(100)));
        assertNull(RuleExpression.check("cross_up(ema(close,12), ema(close,26)) and rsi(close,14) < 65"));
        assertNull(RuleExpression.check("close - ref(close,1) > atr(14) and volratio(5) > 1.5"));
    }

    @Test
    void lookbackCoversNestedPeriods() {
        assertEquals(26, RuleExpression.parse("ema(close,26) > 0").lookback());
        assertEquals(40, RuleExpression.parse("ma(ref(close,20),20)").lookback());
    }

    @Test
    void searchScalesOneNumberAtATime() {
        String rule = "rsi(close,6) < 25 and close > ma(close,120)";
        assertEquals(3, RuleExpression.countNumbers(rule));
        assertEquals("rsi(close,6) < 30 and close > ma(close,120)", RuleExpression.scaleNumber(rule, 1, 1.2));
        assertEquals("rsi(close,5) < 25 and close > ma(close,120)", RuleExpression.scaleNumber(rule, 0, 0.8));
        assertEquals("volratio(5) > 1.8", RuleExpression.scaleNumber("volratio(5) > 1.5", 1, 1.2));
    }

    @Test
    void customEntryDrivesTheBacktest() {
        // Mean reversion: buy a 3-day dip below the 3-day low; exit by take profit.
        List<Bar> list = new ArrayList<>();
        double prev = 10;
        for (int i = 0; i < 200; i++) {
            double c = 10 + Math.sin(i / 3.0) * 1.5;
            list.add(new Bar(START.plusDays(i), prev, c, Math.max(prev, c) + 0.05, Math.min(prev, c) - 0.05, 1000, 0, 0,
                    0));
            prev = c;
        }
        Series s = new Series("600036", "测试", list);
        StrategyParams p = StrategyParams.defaults()
                .merge(new JSONObject()
                        .fluentPut("universe", List.of("600036", "000333", "601318"))
                        .fluentPut("entryType", "CUSTOM")
                        .fluentPut("customEntry", "close < lowest(close,3) and rsi(close,6) < 30")
                        .fluentPut("takeProfitPct", 10)
                        .fluentPut("exitOnMaCrossDown", false)
                        .fluentPut("customExit", "close > ma(close,10)"));
        assertTrue(p.validate().isEmpty(), p.validate().toString());
        List<LocalDate> cal = list.stream().map(Bar::date).toList();
        Backtester.Result r = Backtester.run(p, List.of(s), cal, 30, cal.size() - 1, 50_000);
        assertTrue(r.trades() > 3, "trades=" + r.trades());
        assertTrue(r.lastTrades().stream().anyMatch(t -> "自定义离场".equals(t.exitReason())), r.lastTrades().toString());
        assertFalse(p.describe().isEmpty());
        assertTrue(p.describe().contains("自定义规则"));
    }

    @Test
    void invalidCustomRuleFailsValidation() {
        StrategyParams p = StrategyParams.defaults()
                .merge(new JSONObject().fluentPut("entryType", "CUSTOM").fluentPut("customEntry", "close >> 1"));
        assertTrue(p.validate().stream().anyMatch(e -> e.startsWith("customEntry")));
    }
}
