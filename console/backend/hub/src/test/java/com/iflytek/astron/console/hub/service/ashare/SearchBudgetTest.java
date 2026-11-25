package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where the search spends its budget, and when its choice cannot be trusted at all. */
class SearchBudgetTest {

    private static StrategyParams base() {
        return StrategyParams.defaults()
                .merge(new JSONObject().fluentPut("universe", List.of("600036", "000333", "601318")));
    }

    private static StrategyService.Evaluation evalWithWindows(double... windowScores) {
        List<Backtester.Metrics> windows = new ArrayList<>();
        for (double s : windowScores) {
            // Metrics(from, to, returnPct, maxDrawdownPct, score, trades, winRatePct, exposurePct, daily)
            windows.add(new Backtester.Metrics(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 3, 5), s * 5, 8, s,
                    30, 60, 20, new double[] {0.001, -0.001}));
        }
        Backtester.Metrics whole = windows.get(0);
        return new StrategyService.Evaluation(whole, whole, windows);
    }

    @Test
    void aDeadEndStopsEatingTheBudgetButIsNeverStarved() {
        // takeProfitPct has been tried a lot and always scored badly; stopLossPct is untried.
        Map<String, double[]> stats = Map.of(
                "takeProfitPct", new double[] {40, -1.5},
                "rsiMax", new double[] {30, 0.4});
        double dead = StrategyService.preference(new JSONObject().fluentPut("takeProfitPct", 15), stats, 100);
        double decent = StrategyService.preference(new JSONObject().fluentPut("rsiMax", 65), stats, 100);
        double untried = StrategyService.preference(new JSONObject().fluentPut("stopLossPct", 12), stats, 100);
        assertTrue(decent > dead, decent + " > " + dead);
        // Something never tried is always worth a look.
        assertEquals(Double.POSITIVE_INFINITY, untried);
        // A pair is judged by its most promising field, so a good idea is not blocked by a bad partner.
        double pair = StrategyService.preference(
                new JSONObject().fluentPut("takeProfitPct", 15).fluentPut("rsiMax", 65), stats, 100);
        assertEquals(decent, pair, 1e-9);
    }

    @Test
    void historyChangesTheOrderButNotTheContents() {
        StrategyParams base = base();
        Set<String> tried = Set.of();
        // A budget large enough to hold every single-field mutation, so the ordering is what is tested
        // rather than which ones happened to fit.
        List<StrategyParams> blind = StrategyService.candidates(base, 7, 100, tried);
        Map<String, double[]> stats = Map.of("rsiMax", new double[] {20, 1.2},
                "takeProfitPct", new double[] {20, -1.2});
        List<StrategyParams> aimed = StrategyService.candidates(base, 7, 100, tried, stats, 200);
        assertEquals(blind.size(), aimed.size());
        // Every candidate still passes validation and differs from the parent, aimed or not.
        assertTrue(aimed.stream().allMatch(p -> p.validate().isEmpty()));
        assertTrue(aimed.stream().noneMatch(p -> p.sameAs(base)));
        // Untried directions come first (optimism under uncertainty), but between two directions that
        // have been tried, the one that worked is reached before the one that did not.
        int rsi = indexOfFirst(aimed, p -> !p.getRsiMax().equals(base.getRsiMax()));
        int take = indexOfFirst(aimed, p -> !p.getTakeProfitPct().equals(base.getTakeProfitPct()));
        assertTrue(rsi >= 0 && take >= 0, "both directions should still be present");
        assertTrue(rsi < take, "rsiMax(" + rsi + ") should be reached before takeProfitPct(" + take + ")");
    }

    private static int indexOfFirst(List<StrategyParams> list, java.util.function.Predicate<StrategyParams> test) {
        for (int i = 0; i < list.size(); i++) {
            if (test.test(list.get(i))) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void anUnreliableSelectionIsDetected() {
        // Two candidates that each win half the windows and lose the other half: choosing between them
        // on one half says nothing about the other.
        List<StrategyService.Evaluation> flipping = List.of(
                evalWithWindows(3, 3, 3, -3, -3, -3),
                evalWithWindows(-3, -3, -3, 3, 3, 3));
        assertEquals(1.0, StrategyService.overfitProbability(flipping), 1e-9);
        // One candidate genuinely better in every window: the choice generalizes.
        List<StrategyService.Evaluation> consistent = List.of(
                evalWithWindows(2, 2, 2, 2, 2, 2),
                evalWithWindows(1, 1, 1, 1, 1, 1),
                evalWithWindows(0, 0, 0, 0, 0, 0));
        assertEquals(0.0, StrategyService.overfitProbability(consistent), 1e-9);
    }

    @Test
    void overfitProbabilityNeedsComparableCandidates() {
        assertTrue(Double.isNaN(StrategyService.overfitProbability(List.of())));
        assertTrue(Double.isNaN(StrategyService.overfitProbability(List.of(evalWithWindows(1, 1, 1, 1, 1, 1)))));
        // Too few windows to split into halves.
        assertTrue(Double.isNaN(StrategyService.overfitProbability(
                List.of(evalWithWindows(1, 1), evalWithWindows(2, 2)))));
        // Candidates with different window counts are refused rather than lined up wrongly.
        assertTrue(Double.isNaN(StrategyService.overfitProbability(
                List.of(evalWithWindows(1, 1, 1, 1, 1, 1), evalWithWindows(1, 1, 1, 1)))));
    }

    @Test
    void theGuardTripsOnlyWhenSelectionIsWorseThanACoinToss() {
        assertEquals(0.5, StrategyService.MAX_OVERFIT_PROBABILITY);
        assertTrue(StrategyService.EXPLORE_SHARE > 0 && StrategyService.EXPLORE_SHARE < 1,
                "some budget must always stay random");
        assertFalse(0.5 > StrategyService.MAX_OVERFIT_PROBABILITY, "exactly a coin toss does not trip it");
    }
}
