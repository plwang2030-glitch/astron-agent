package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Bar;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Series;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Evolvable strategy structure, the automatic search and the adoption gate. */
class EvolutionTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 6);
    private static final List<String> UNIVERSE = List.of("600036", "000333", "601318");

    /** Flat at 10 for {@code flat} days, then a zigzag rise (+2% / −1%), gapless. */
    private static Series trending(String code, int flat, int rising) {
        List<Bar> bars = new ArrayList<>();
        double prev = 10;
        for (int i = 0; i < flat + rising; i++) {
            double close = i < flat ? 10 : prev * ((i - flat) % 2 == 0 ? 1.02 : 0.99);
            bars.add(new Bar(START.plusDays(i), prev, close, Math.max(prev, close) * 1.002, Math.min(prev, close) * 0.998,
                    1000, 1000 * close, 0, 1));
            prev = close;
        }
        return new Series(code, "测试" + code, bars);
    }

    private static StrategyParams params(JSONObject patch) {
        return StrategyParams.defaults().merge(new JSONObject().fluentPut("universe", UNIVERSE)).merge(patch);
    }

    private static Backtester.Result run(StrategyParams p, Series s, Series index) {
        List<LocalDate> cal = s.bars().stream().map(Bar::date).toList();
        return Backtester.run(p, List.of(s), index, cal, 60, cal.size() - 1, 50_000);
    }

    @Test
    void newOptionsValidateAndDescribe() {
        StrategyParams p = params(new JSONObject().fluentPut("entryType", "breakout")
                .fluentPut("sizing", "risk")
                .fluentPut("trailingStopPct", 10)
                .fluentPut("partialTakeProfitPct", 10)
                .fluentPut("marketFilter", true));
        assertTrue(p.validate().isEmpty(), p.validate().toString());
        assertEquals(StrategyParams.BREAKOUT, p.getEntryType());
        String d = p.describe();
        assertTrue(d.contains("突破") && d.contains("移动止损") && d.contains("先卖一半") && d.contains("按风险定仓位")
                && d.contains("上证指数"), d);
        assertFalse(params(new JSONObject().fluentPut("trailingStopPct", 2)).validate().isEmpty());
        assertFalse(params(new JSONObject().fluentPut("partialTakeProfitPct", 25)).validate().isEmpty(),
                "partial must stay below the 20% take profit");
        assertFalse(params(new JSONObject().fluentPut("entryType", "XYZ")).validate().isEmpty());
        // Older versions without the new fields keep the version-1 behavior.
        StrategyParams old = StrategyParams.parse("{\"rsiMax\":65}");
        assertEquals(StrategyParams.MA_CROSS, old.getEntryType());
        assertEquals(StrategyParams.EQUAL, old.getSizing());
        assertEquals(0.0, old.getTrailingStopPct());
    }

    @Test
    void riskSizingBudget() {
        StrategyParams equal = params(new JSONObject());
        assertEquals(12_500, equal.positionBudget(50_000), 1e-9);
        // 1% of 50,000 at risk with an 8% stop -> 6,250, under the 25% cap.
        StrategyParams risk = params(new JSONObject().fluentPut("sizing", "RISK"));
        assertEquals(6_250, risk.positionBudget(50_000), 1e-9);
        // A tight stop would allow more than the cap.
        StrategyParams tight = params(new JSONObject().fluentPut("sizing", "RISK").fluentPut("stopLossPct", 3));
        assertEquals(12_500, tight.positionBudget(50_000), 1e-9);
    }

    @Test
    void breakoutEntryAndPartialExitInBacktest() {
        Series s = trending("600036", 100, 60);
        StrategyParams p = params(new JSONObject().fluentPut("entryType", "BREAKOUT")
                .fluentPut("rsiMax", 90)
                .fluentPut("partialTakeProfitPct", 8)
                .fluentPut("takeProfitPct", 15));
        Backtester.Result r = run(p, s, null);
        assertTrue(r.trades() >= 2, "trades=" + r.trades());
        assertTrue(r.lastTrades().stream().anyMatch(t -> Backtester.PARTIAL_TAKE_PROFIT.equals(t.exitReason())),
                r.lastTrades().toString());
        assertTrue(r.returnPct() > 0);
    }

    @Test
    void riskSizingBuysSmallerPositions() {
        Series s = trending("600036", 100, 60);
        JSONObject loose = new JSONObject().fluentPut("rsiMax", 90).fluentPut("takeProfitPct", 10);
        Backtester.Result equal = run(params(loose), s, null);
        Backtester.Result risk = run(params(new JSONObject(loose).fluentPut("sizing", "RISK")), s, null);
        assertTrue(equal.lastTrades().get(0).qty() > risk.lastTrades().get(0).qty());
    }

    @Test
    void marketFilterBlocksEntriesInAWeakMarket() {
        Series s = trending("600036", 100, 60);
        // The index falls all the time: always below its MA20.
        List<Bar> idx = new ArrayList<>();
        for (int i = 0; i < 160; i++) {
            double c = 3000 - i * 5;
            idx.add(new Bar(START.plusDays(i), c, c, c, c, 1, 1, 0, 0));
        }
        Series index = new Series("000001", "上证指数", idx);
        JSONObject loose = new JSONObject().fluentPut("rsiMax", 90).fluentPut("takeProfitPct", 10);
        assertTrue(run(params(loose), s, index).trades() > 0, "without the filter the index is ignored");
        assertEquals(0, run(params(new JSONObject(loose).fluentPut("marketFilter", true)), s, index).trades());
    }

    @Test
    void searchCandidatesAreValidUniqueAndNew() {
        StrategyParams base = params(new JSONObject());
        Set<String> tried = new HashSet<>();
        List<StrategyParams> first = StrategyService.candidates(base, 42, 24, tried);
        assertEquals(24, first.size());
        Set<String> keys = new HashSet<>();
        for (StrategyParams c : first) {
            assertTrue(c.validate().isEmpty(), c.validate().toString());
            assertFalse(c.sameAs(base));
            assertEquals(base.getUniverse(), c.getUniverse(), "the search does not change the universe");
            assertEquals(base.getUniverseMode(), c.getUniverseMode());
            assertEquals(base.getUniverseSize(), c.getUniverseSize());
            assertTrue(keys.add(c.toJson()), "duplicate candidate");
        }
        // Structural options are among the mutations.
        List<JSONObject> m = StrategyService.mutations(base);
        assertTrue(m.stream().anyMatch(j -> j.containsKey("entryType")));
        assertTrue(m.stream().anyMatch(j -> j.containsKey("sizing")));
        assertTrue(m.stream().anyMatch(j -> j.containsKey("trailingStopPct")));
        assertTrue(m.stream().anyMatch(j -> j.containsKey("partialTakeProfitPct")));
        assertTrue(m.stream().anyMatch(j -> j.containsKey("marketFilter")));
        // Same seed, same candidates; versions tried before are skipped.
        assertEquals(first.get(0).toJson(), StrategyService.candidates(base, 42, 24, tried).get(0).toJson());
        tried.add(first.get(0).toJson());
        assertTrue(StrategyService.candidates(base, 42, 24, tried)
                .stream()
                .noneMatch(c -> c.toJson().equals(first.get(0).toJson())));
    }

    /** Daily returns with mean {@code mean} and a fixed zigzag noise. */
    private static double[] returns(double mean, int n, long seed) {
        java.util.Random r = new java.util.Random(seed);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = mean + r.nextGaussian() * 0.01;
        }
        return out;
    }

    private static Backtester.Metrics metrics(double sharpe, double dd, int trades, double[] rets) {
        return new Backtester.Metrics(START, START, 0, dd, sharpe, trades, 50, 50, rets);
    }

    private static StrategyService.Evaluation eval(double oosSharpe, double dd, int trades, double[] rets,
            double... windowSharpes) {
        List<Backtester.Metrics> w = new ArrayList<>();
        for (double s : windowSharpes) {
            w.add(metrics(s, 0, 1, new double[0]));
        }
        return new StrategyService.Evaluation(metrics(0, 0, 0, new double[0]), metrics(oosSharpe, dd, trades, rets), w);
    }

    @Test
    void gateNeedsMarginSignificanceWindowsDrawdownAndTrades() {
        double[] baseRets = returns(0.0002, 360, 1);
        double[] better = new double[360];
        for (int i = 0; i < 360; i++) {
            better[i] = baseRets[i] + 0.0015; // clearly better every day
        }
        // Different, unrelated daily returns with the same average: no real improvement.
        double[] same = returns(0.0002, 360, 2);
        StrategyService.Evaluation base = eval(0.3, 5, 40, baseRets, 0, 0, 0, 0, 0, 0);
        assertTrue(StrategyService.gate(base, eval(1.0, 5, 40, better, 1, 1, 1, 1, 0, 1), 0.2, 1.0).isEmpty());
        // Higher Sharpe on paper but no significant difference in the daily returns.
        assertFalse(StrategyService.gate(base, eval(1.0, 5, 40, same, 1, 1, 1, 1, 0, 1), 0.2, 1.0).isEmpty());
        // Not enough improvement.
        assertFalse(StrategyService.gate(base, eval(0.4, 5, 40, better, 1, 1, 1, 1, 1, 1), 0.2, 1.0).isEmpty());
        // Wins 5 of 6 windows but not the latest.
        assertFalse(StrategyService.gate(base, eval(1.0, 5, 40, better, 1, 1, 1, 1, 1, 0), 0.2, 1.0).isEmpty());
        // Wins only 3 of 6.
        assertFalse(StrategyService.gate(base, eval(1.0, 5, 40, better, 0, 0, 0, 1, 1, 1), 0.2, 1.0).isEmpty());
        // Drawdown more than 3 points worse.
        assertFalse(StrategyService.gate(base, eval(1.0, 9, 40, better, 1, 1, 1, 1, 1, 1), 0.2, 1.0).isEmpty());
        // Too few trades.
        assertFalse(StrategyService.gate(base, eval(1.0, 5, 10, better, 1, 1, 1, 1, 1, 1), 0.2, 1.0).isEmpty());
    }

    @Test
    void gateRejectsAChangeThatIsWorseInOneRegimeOrOnThePool() {
        double[] baseRets = returns(0.0002, 360, 1);
        double[] better = new double[360];
        for (int i = 0; i < 360; i++) {
            better[i] = baseRets[i] + 0.0015;
        }
        List<Backtester.Metrics> six = new ArrayList<>();
        List<Backtester.Metrics> sixBetter = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(metrics(0, 0, 1, new double[0]));
            sixBetter.add(metrics(1, 0, 1, new double[0]));
        }
        java.util.Map<String, StrategyService.RegimeStat> baseRegimes = java.util.Map.of("UP",
                new StrategyService.RegimeStat(4, 1.5), "DOWN", new StrategyService.RegimeStat(3, -0.5));
        StrategyService.Evaluation base = new StrategyService.Evaluation(null, metrics(0, 0, 0, new double[0]),
                metrics(0.3, 5, 40, baseRets), six, baseRegimes, metrics(0.4, 5, 40, baseRets));
        StrategyService.Evaluation goodEverywhere = new StrategyService.Evaluation(null, metrics(0, 0, 0, new double[0]),
                metrics(1.0, 5, 40, better), sixBetter, java.util.Map.of("UP", new StrategyService.RegimeStat(4, 1.4),
                        "DOWN", new StrategyService.RegimeStat(3, 0.2)),
                metrics(0.5, 5, 40, better));
        assertTrue(StrategyService.gate(base, goodEverywhere, 0.2, 1.0).isEmpty());
        // Better overall, but much worse in rising markets.
        StrategyService.Evaluation badInUp = new StrategyService.Evaluation(null, metrics(0, 0, 0, new double[0]),
                metrics(1.0, 5, 40, better), sixBetter, java.util.Map.of("UP", new StrategyService.RegimeStat(4, 0.5),
                        "DOWN", new StrategyService.RegimeStat(3, 0.8)),
                metrics(0.5, 5, 40, better));
        assertTrue(String.join("", StrategyService.gate(base, badInUp, 0.2, 1.0)).contains("上涨"));
        // Better on the trading universe only.
        StrategyService.Evaluation narrow = new StrategyService.Evaluation(null, metrics(0, 0, 0, new double[0]),
                metrics(1.0, 5, 40, better), sixBetter, baseRegimes, metrics(0.1, 5, 40, better));
        assertTrue(String.join("", StrategyService.gate(base, narrow, 0.2, 1.0)).contains("扩展验证股票池"));
    }

    @Test
    void sharpeDifferenceTest() {
        double[] a = returns(0.0002, 360, 7);
        double[] b = new double[360];
        for (int i = 0; i < 360; i++) {
            b[i] = a[i] + 0.002;
        }
        assertTrue(Backtester.sharpeDiffZ(b, a) > 1.65);
        assertTrue(Backtester.sharpeDiffZ(a, b) < -1.65);
        assertEquals(0, Backtester.sharpeDiffZ(a, a), 1e-6);
    }

    @Test
    void forwardTestVerdict() {
        assertEquals("WAIT", StrategyService.forwardVerdict(5, fwd(1, 2, 1), fwd(0, 2, 1)));
        assertEquals("ADOPT", StrategyService.forwardVerdict(10, fwd(-0.5, 2, 1), fwd(0, 2, 1)));
        assertEquals("REJECT", StrategyService.forwardVerdict(10, fwd(-2, 2, 1), fwd(0, 2, 1)));
        assertEquals("REJECT", StrategyService.forwardVerdict(10, fwd(1, 5, 1), fwd(0, 2, 1)));
        // No trades and identical curves: wait, but not forever.
        assertEquals("WAIT", StrategyService.forwardVerdict(12, fwd(0, 0, 0), fwd(0, 0, 0)));
        assertEquals("ADOPT", StrategyService.forwardVerdict(30, fwd(0, 0, 0), fwd(0, 0, 0)));
    }

    private static Backtester.Result fwd(double returnPct, double dd, int trades) {
        return new Backtester.Result(START, START, 50_000, 50_000, returnPct, dd, trades, 0, 0, 0, List.of());
    }

    @Test
    void rollbackTolerance() {
        assertEquals(9, AutoEvolutionService.rollbackLimit(4), 1e-9);
        assertEquals(15, AutoEvolutionService.rollbackLimit(10), 1e-9);
    }
}
