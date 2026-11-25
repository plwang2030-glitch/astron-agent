package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The gate must refuse a result that the search effort alone explains. */
class DeflatedGateTest {

    private static double[] returns(double annualSharpe, int days, long seed) {
        Random r = new Random(seed);
        double sd = 0.01;
        double mean = annualSharpe / Math.sqrt(Statistics.TRADING_DAYS) * sd;
        double[] out = new double[days];
        for (int i = 0; i < days; i++) {
            out[i] = mean + r.nextGaussian() * sd;
        }
        return out;
    }

    private static Backtester.Metrics metrics(double score, double returnPct, double dd, int trades,
            double[] daily) {
        return new Backtester.Metrics(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 11, 25), returnPct, dd, trades,
                60, score, 20, daily);
    }

    /** An evaluation good enough to clear every criterion except the deflation. */
    private static StrategyService.Evaluation eval(double score, double[] daily) {
        Backtester.Metrics m = metrics(score, score * 5, 8, 40, daily);
        return new StrategyService.Evaluation(m, m, List.of(m, m, m, m, m, m));
    }

    @Test
    void aStrongCandidateWithFewTrialsPasses() {
        double[] baseR = returns(0.0, 360, 1);
        double[] candR = returns(2.5, 360, 2);
        StrategyService.Evaluation base = eval(0.0, baseR);
        StrategyService.Evaluation cand = eval(2.5, candR);
        double dsr = Statistics.deflatedSharpe(candR, 5, 0.5);
        assertTrue(dsr > 0.95, "dsr=" + dsr);
        List<String> reasons = StrategyService.gate(base, cand, 0.2, 1.0, dsr, 0.90, 5);
        assertFalse(reasons.stream().anyMatch(r -> r.contains("折减")), String.valueOf(reasons));
    }

    @Test
    void theSameCandidateIsRefusedOnceTheSearchHasBeenBusy() {
        double[] baseR = returns(0.0, 360, 1);
        double[] candR = returns(1.1, 360, 3);
        StrategyService.Evaluation base = eval(0.0, baseR);
        StrategyService.Evaluation cand = eval(1.1, candR);
        double few = Statistics.deflatedSharpe(candR, 5, 0.5);
        double many = Statistics.deflatedSharpe(candR, 2000, 0.5);
        assertTrue(few > many, few + " > " + many);
        // With thousands of candidates behind it, this Sharpe no longer stands out.
        List<String> reasons = StrategyService.gate(base, cand, 0.2, 1.0, many, 0.95, 2000);
        assertTrue(reasons.stream().anyMatch(r -> r.contains("折减") && r.contains("2000")),
                String.valueOf(reasons));
    }

    @Test
    void theDeflationCheckIsSkippedWhenNotApplicable() {
        double[] r = returns(1.0, 360, 4);
        StrategyService.Evaluation base = eval(0.0, returns(0, 360, 5));
        StrategyService.Evaluation cand = eval(1.0, r);
        // NaN means "not measured", e.g. the plain backtest view: no deflation reason is added.
        assertFalse(StrategyService.gate(base, cand, 0.2, 1.0, Double.NaN, 0.95, 0)
                .stream()
                .anyMatch(x -> x.contains("折减")));
        // The old four-argument gate behaves exactly as before.
        assertEquals(StrategyService.gate(base, cand, 0.2, 1.0),
                StrategyService.gate(base, cand, 0.2, 1.0, Double.NaN, 0, 0));
    }

    @Test
    void changedFieldsAreListedForLaterStatistics() {
        StrategyParams base = StrategyParams.defaults();
        StrategyParams cand = base.merge(new JSONObject().fluentPut("rsiMax", 65).fluentPut("maxHoldDays", 20));
        String fields = StrategyService.changedFields(cand, base);
        assertTrue(fields.contains("rsiMax"), fields);
        assertTrue(fields.contains("maxHoldDays"), fields);
        assertFalse(fields.contains("stopLossPct"), fields);
        assertEquals("", StrategyService.changedFields(base, base));
    }
}
