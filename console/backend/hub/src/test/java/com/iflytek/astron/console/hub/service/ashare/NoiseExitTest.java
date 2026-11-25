package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Bar;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Series;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exits that are not fooled by intraday noise: a stop scaled to the stock's own volatility, and
 * price levels confirmed on the close instead of the moment they are touched.
 */
class NoiseExitTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 5);

    private static StrategyParams params(JSONObject patch) {
        return StrategyParams.defaults()
                .merge(new JSONObject().fluentPut("universe", List.of("600036", "000333", "601318"))
                        .fluentPut("stopLossPct", 10)
                        .fluentPut("takeProfitPct", 20));
    }

    private static StrategyParams with(JSONObject patch) {
        return params(null).merge(patch);
    }

    @Test
    void atrStopWidensForAVolatileStockAndStaysTightForACalmOne() {
        StrategyParams atr = with(new JSONObject().fluentPut("stopMode", "ATR").fluentPut("stopAtrMult", 2.5));
        // A stock whose ATR14 is 1% of a 100 price: 2.5 x 1 = 2.5 below the entry.
        assertEquals(97.5, Backtester.stopPrice(atr, 100, 1.0), 1e-9);
        // A volatile stock with ATR 10% of price gets real room: 2.5 x 10 = 25, at the sanity bound.
        assertEquals(75.0, Backtester.stopPrice(atr, 100, 10.0), 1e-9);
        assertEquals(25.0, Backtester.stopDistancePct(atr, 100, 10.0), 1e-9);
        // The bounds keep the distance sane whatever the ATR says.
        assertEquals(StrategyParams.MIN_STOP_PCT, Backtester.stopDistancePct(atr, 100, 0.1), 1e-9);
        assertEquals(StrategyParams.MAX_STOP_PCT, Backtester.stopDistancePct(atr, 100, 50), 1e-9);
        // The percentage mode ignores the ATR entirely.
        assertEquals(90.0, Backtester.stopPrice(params(null), 100, 1.0), 1e-9);
        // Without a usable ATR the protection falls back to the percentage rule, it never disappears.
        assertEquals(90.0, Backtester.stopPrice(atr, 100, Double.NaN), 1e-9);
        assertEquals(90.0, Backtester.stopPrice(atr, 100, 0), 1e-9);
    }

    @Test
    void aWiderStopBuysASmallerPosition() {
        StrategyParams risk = with(new JSONObject().fluentPut("sizing", "RISK")
                .fluentPut("riskPerTradePct", 1.0)
                .fluentPut("maxWeightPct", 50));
        // Risking 1% of 100,000 with a 5% stop allows 20,000; with a 20% stop only 5,000.
        assertEquals(20_000, risk.positionBudget(100_000, 5), 1e-6);
        assertEquals(5_000, risk.positionBudget(100_000, 20), 1e-6);
        // So the loss when the stop is hit is the same either way.
        assertEquals(20_000 * 0.05, 5_000 * 0.20, 1e-6);
        // The weight cap still applies.
        assertEquals(50_000, risk.positionBudget(100_000, 1), 1e-6);
    }

    @Test
    void closeConfirmIgnoresADipThatRecovers() {
        StrategyParams intraday = params(null);
        StrategyParams onClose = with(new JSONObject().fluentPut("exitCheck", "CLOSE"));
        // Bought at 100, stop at 90: the day dips to 89 and closes back at 97.
        assertEquals(Backtester.STOP_LOSS,
                Backtester.priceExit(intraday, 100, 100, false, 98, 99, 89).reason());
        // Judged on the close alone, 97 is above the stop: nothing is sold.
        assertNull(Backtester.priceExit(onClose, 100, 100, false, 97, 97, 97));
        // A close that is genuinely below the stop still sells.
        assertEquals(Backtester.STOP_LOSS,
                Backtester.priceExit(onClose, 100, 100, false, 88, 88, 88).reason());
    }

    @Test
    void theStopOnTheCloseUsesTheAtrPriceToo() {
        StrategyParams p = with(new JSONObject().fluentPut("stopMode", "ATR").fluentPut("stopAtrMult", 2.0));
        // ATR 1.0 puts the stop at 98: a close of 98.5 holds, 97.5 sells.
        assertNull(Backtester.exitReason(p, 98.5, 10, 9, 100, 1, false, 1.0));
        assertEquals(Backtester.STOP_LOSS, Backtester.exitReason(p, 97.5, 10, 9, 100, 1, false, 1.0));
        // The same closes under the percentage rule (stop 90) do not sell at all.
        assertNull(Backtester.exitReason(params(null), 97.5, 10, 9, 100, 1, false, 1.0));
    }

    @Test
    void defaultsKeepTheOldBehaviour() {
        StrategyParams d = StrategyParams.defaults();
        assertEquals(StrategyParams.PCT, d.getStopMode());
        assertEquals(StrategyParams.INTRADAY, d.getExitCheck());
        assertTrue(d.validate().isEmpty());
        // A version saved before these options existed behaves exactly as before.
        StrategyParams old = StrategyParams.parse("{\"stopLossPct\":10,\"takeProfitPct\":20}");
        assertEquals(StrategyParams.PCT, old.getStopMode());
        assertEquals(StrategyParams.INTRADAY, old.getExitCheck());
        assertEquals(90.0, Backtester.stopPrice(old, 100, 1.0), 1e-9);
        assertEquals(Backtester.STOP_LOSS, Backtester.priceExit(old, 100, 100, false, 98, 99, 89).reason());
    }

    @Test
    void validationBoundsAndDescription() {
        assertTrue(with(new JSONObject().fluentPut("stopMode", "WHATEVER")).validate()
                .stream()
                .anyMatch(e -> e.contains("stopMode")));
        assertTrue(with(new JSONObject().fluentPut("exitCheck", "SOMETIMES")).validate()
                .stream()
                .anyMatch(e -> e.contains("exitCheck")));
        assertTrue(with(new JSONObject().fluentPut("stopAtrMult", 9)).validate()
                .stream()
                .anyMatch(e -> e.contains("stopAtrMult")));
        String text = with(new JSONObject().fluentPut("stopMode", "ATR").fluentPut("exitCheck", "CLOSE")).describe();
        assertTrue(text.contains("倍 ATR14"));
        assertTrue(text.contains("不被盘中插针扫出"));
    }

    @Test
    void theSearchCanTryBothKnobs() {
        List<JSONObject> mutations = StrategyService.mutations(params(null));
        assertTrue(mutations.stream().anyMatch(m -> "ATR".equals(m.getString("stopMode"))));
        assertTrue(mutations.stream().anyMatch(m -> "CLOSE".equals(m.getString("exitCheck"))));
        assertTrue(mutations.stream().anyMatch(m -> "HALF_THEN_CLOSE".equals(m.getString("exitCheck"))));
        assertTrue(mutations.stream().anyMatch(m -> m.containsKey("stopAtrMult")));
        // It never proposes the value the version already has.
        assertTrue(mutations.stream().noneMatch(m -> "INTRADAY".equals(m.getString("exitCheck"))));
    }

    /**
     * 100 flat days, then a zigzag rise (+2% / -1%) that triggers the MA entry while keeping RSI inside
     * the strategy's band, then one day that dips 25% intraday but closes only 7% down, then flat days.
     * The dip breaks a 10% stop; the close does not.
     */
    private static Series spikeThenRecover() {
        List<Bar> bars = new ArrayList<>();
        double prev = 10;
        for (int i = 0; i < 100; i++) {
            bars.add(new Bar(START.plusDays(i), prev, 10, 10.1, 9.9, 1000, 10000, 0, 1));
        }
        for (int i = 0; i < 12; i++) {
            double close = prev * (i % 2 == 0 ? 1.02 : 0.99);
            bars.add(new Bar(START.plusDays(100 + i), prev, close, Math.max(prev, close), Math.min(prev, close), 3000,
                    3000 * close, 0, 2));
            prev = close;
        }
        double close = prev * 0.93;
        bars.add(new Bar(START.plusDays(112), prev, close, prev, prev * 0.75, 5000, 5000 * close, -7, 3));
        prev = close;
        for (int i = 0; i < 12; i++) {
            bars.add(new Bar(START.plusDays(113 + i), prev, prev, prev * 1.01, prev * 0.99, 2000, 2000 * prev, 0, 1));
        }
        return new Series("600036", "测试", bars);
    }

    @Test
    void intradaySpikeSellsOnlyWhenNotConfirmedOnTheClose() {
        Series s = spikeThenRecover();
        List<LocalDate> calendar = s.bars().stream().map(Bar::date).toList();
        Backtester.Result intraday = Backtester.run(params(null), List.of(s), calendar, 60, calendar.size() - 1, 50_000);
        Backtester.Result onClose = Backtester.run(with(new JSONObject().fluentPut("exitCheck", "CLOSE")), List.of(s),
                calendar, 60, calendar.size() - 1, 50_000);
        // The spike's low breaks the 10% stop, so the intraday version is sold at the worst price.
        assertTrue(intraday.allTrades().stream().anyMatch(t -> Backtester.STOP_LOSS.equals(t.exitReason())),
                "intraday exits: " + intraday.allTrades().stream().map(Backtester.Trade::exitReason).toList());
        // Confirming on the close, the 7% drop stays inside the stop, so the position is kept.
        assertTrue(onClose.allTrades().stream().noneMatch(t -> Backtester.STOP_LOSS.equals(t.exitReason())),
                "close exits: " + onClose.allTrades().stream().map(Backtester.Trade::exitReason).toList());
        assertTrue(onClose.returnPct() > intraday.returnPct(),
                "close " + onClose.returnPct() + " vs intraday " + intraday.returnPct());
    }

    @Test
    void halfOnTouchSellsHalfThenKeepsTheRestWhenTheCloseRecovers() {
        Series s = spikeThenRecover();
        List<LocalDate> calendar = s.bars().stream().map(Bar::date).toList();
        Backtester.Result half = Backtester.run(with(new JSONObject().fluentPut("exitCheck", "HALF_THEN_CLOSE")),
                List.of(s), calendar, 60, calendar.size() - 1, 50_000);
        List<Backtester.Trade> stops = half.allTrades()
                .stream()
                .filter(t -> Backtester.STOP_LOSS.equals(t.exitReason()))
                .toList();
        assertEquals(1, stops.size(), "one partial stop on the touch");
        Backtester.Result intraday = Backtester.run(params(null), List.of(s), calendar, 60, calendar.size() - 1, 50_000);
        Backtester.Trade full = intraday.allTrades()
                .stream()
                .filter(t -> Backtester.STOP_LOSS.equals(t.exitReason()))
                .findFirst()
                .orElseThrow();
        // Only part of the position left on the touch, so the loss taken there is smaller.
        assertTrue(stops.get(0).qty() < full.qty(), stops.get(0).qty() + " < " + full.qty());
        assertTrue(half.returnPct() > intraday.returnPct());
    }

    @Test
    void atrIsTheAverageTrueRangeIncludingGaps() {
        List<Bar> bars = List.of(
                new Bar(START, 10, 10, 10.5, 9.5, 100, 1000, 0, 1),
                // Gaps down to 8: the true range measures from the previous close of 10.
                new Bar(START.plusDays(1), 8, 8.2, 8.4, 7.9, 100, 1000, -18, 1));
        double[] atr = Indicators.atr(bars, 2);
        // Like every moving average here, the first value has too little history.
        assertTrue(Double.isNaN(atr[0]));
        // Day 1 range 10.5 - 9.5 = 1.0; day 2 true range 10 - 7.9 = 2.1 (the gap counts); average 1.55.
        assertEquals(1.55, atr[1], 1e-9);
        assertNotNull(Indicators.atr(List.of(), 14));
    }
}
