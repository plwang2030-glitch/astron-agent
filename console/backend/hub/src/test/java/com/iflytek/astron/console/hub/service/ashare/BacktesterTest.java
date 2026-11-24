package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Bar;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Series;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktesterTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 5);

    /**
     * 100 flat days at 10, then a zigzag rise (+2% / -1%); gapless: each open is the previous close.
     */
    private static Series trending(String code, int risingDays) {
        List<Bar> bars = new ArrayList<>();
        double prev = 10;
        for (int i = 0; i < 100 + risingDays; i++) {
            double close = i < 100 ? 10 : prev * ((i - 100) % 2 == 0 ? 1.02 : 0.99);
            bars.add(new Bar(START.plusDays(i), prev, close, Math.max(prev, close), Math.min(prev, close), 1000,
                    1000 * close, 0, 1));
            prev = close;
        }
        return new Series(code, "测试" + code, bars);
    }

    private static StrategyParams params(List<String> universe) {
        return StrategyParams.defaults().merge(new JSONObject().fluentPut("universe", universe));
    }

    @Test
    void sharpeIgnoresHowMuchIsInvested() {
        double[] r = {0.01, -0.005, 0.002, 0.008, -0.003, 0.004};
        double[] half = new double[r.length];
        for (int i = 0; i < r.length; i++) {
            half[i] = r[i] / 2;
        }
        // Investing half as much halves every daily return: the Sharpe ratio stays the same.
        assertEquals(Backtester.sharpe(r), Backtester.sharpe(half), 1e-9);
        assertEquals(0, Backtester.sharpe(new double[] {0, 0, 0}), 1e-9);
    }

    @Test
    void buysTheTrendAtTheNextOpenAndTakesProfit() {
        Series s = trending("600036", 80);
        List<LocalDate> calendar = s.bars().stream().map(Bar::date).toList();
        // The zigzag gains about 15% in 30 days, so use a 10% take profit.
        StrategyParams p = params(List.of("600036", "000333", "601318")).merge(new JSONObject().fluentPut("takeProfitPct", 10));
        Backtester.Result r = Backtester.run(p, List.of(s), calendar, 60, calendar.size() - 1, 50_000);
        assertTrue(r.trades() >= 1, "trades=" + r.trades());
        Backtester.Trade first = r.lastTrades().get(0);
        assertEquals("止盈", first.exitReason());
        assertTrue(first.pnl() > 0);
        // Bought at the signal day's close plus slippage; the cost per share also includes fees.
        Bar entryBar = s.bars().stream().filter(b -> b.date().equals(first.entryDate())).findFirst().orElseThrow();
        assertTrue(first.entryPrice() >= entryBar.close() * 1.001, "close + slippage + fees");
        // Sold on a later day (T+1).
        assertTrue(first.exitDate().isAfter(first.entryDate()));
        assertTrue(first.entryDate().isAfter(START.plusDays(100)));
        assertTrue(r.returnPct() > 0);
        // One position at most 25% of equity.
        assertTrue(first.qty() * first.entryPrice() <= 50_000 * 0.25 + 1);
    }

    @Test
    void monthUniverseLimitsNewEntries() {
        Series a = trending("600036", 80);
        Series b = trending("000333", 80);
        List<LocalDate> calendar = a.bars().stream().map(Bar::date).toList();
        StrategyParams p = params(List.of("600036", "000333", "601318")).merge(new JSONObject().fluentPut("takeProfitPct", 10));
        Map<YearMonth, Set<String>> onlyB = new LinkedHashMap<>();
        for (LocalDate d : calendar) {
            onlyB.put(YearMonth.from(d), Set.of("000333"));
        }
        Backtester.Result r = Backtester.run(p, List.of(a, b), null, calendar, 60, calendar.size() - 1, 50_000, onlyB);
        assertTrue(r.trades() >= 1);
        assertTrue(r.allTrades().stream().allMatch(t -> t.code().equals("000333")));
        // A stock outside the strategy's list still trades when the month's universe has it.
        Series c = trending("601899", 80);
        Map<YearMonth, Set<String>> onlyC = new LinkedHashMap<>();
        onlyB.keySet().forEach(m -> onlyC.put(m, Set.of("601899")));
        Backtester.Result rc = Backtester.run(p, List.of(a, b, c), null, calendar, 60, calendar.size() - 1, 50_000, onlyC);
        assertTrue(rc.trades() >= 1);
        assertTrue(rc.allTrades().stream().allMatch(t -> t.code().equals("601899")));
        // Months without an entry: no new positions.
        Backtester.Result none = Backtester.run(p, List.of(a, b), null, calendar, 60, calendar.size() - 1, 50_000,
                Map.of());
        assertEquals(0, none.trades());
    }

    @Test
    void positionsExitAfterTheStockLeavesTheUniverse() {
        Series a = trending("600036", 80);
        List<LocalDate> calendar = a.bars().stream().map(Bar::date).toList();
        StrategyParams p = params(List.of("600036", "000333", "601318")).merge(new JSONObject().fluentPut("takeProfitPct", 10));
        Backtester.Result all = Backtester.run(p, List.of(a), null, calendar, 60, calendar.size() - 1, 50_000);
        Backtester.Trade first = all.allTrades().get(0);
        // In the universe only in the month of the first entry.
        Map<YearMonth, Set<String>> u = Map.of(YearMonth.from(first.entryDate()), Set.of("600036"));
        Backtester.Result r = Backtester.run(p, List.of(a), null, calendar, 60, calendar.size() - 1, 50_000, u);
        Backtester.Trade t = r.allTrades().get(0);
        assertEquals(first.entryDate(), t.entryDate());
        assertEquals(first.exitDate(), t.exitDate());
        assertTrue(r.allTrades().stream().allMatch(x -> YearMonth.from(x.entryDate()).equals(YearMonth.from(first.entryDate()))));
    }

    @Test
    void flatMarketMakesNoTrades() {
        Series s = trending("600036", 0);
        List<LocalDate> calendar = s.bars().stream().map(Bar::date).toList();
        Backtester.Result r = Backtester.run(params(List.of("600036", "000333", "601318")), List.of(s), calendar, 60,
                calendar.size() - 1, 50_000);
        assertEquals(0, r.trades());
        assertEquals(0, r.returnPct(), 1e-9);
    }

    @Test
    void exitRulesInOrder() {
        StrategyParams p = StrategyParams.defaults();
        assertEquals(Backtester.STOP_LOSS, Backtester.exitReason(p, 9.1, 9, 10, 10, 1));
        assertEquals("止盈", Backtester.exitReason(p, 12.1, 12, 11, 10, 1));
        assertEquals("到期", Backtester.exitReason(p, 10.5, 11, 10, 10, 30));
        assertEquals("均线死叉", Backtester.exitReason(p, 10.5, 9.9, 10, 10, 5));
        assertNull(Backtester.exitReason(p, 10.5, 10.6, 10, 10, 5));
    }

    @Test
    void latestSignalReportsIndicators() {
        Series s = trending("600036", 3);
        JSONObject sig = Backtester.latestSignal(params(List.of("600036", "000333", "601318")), s);
        assertEquals("600036", sig.getString("code"));
        assertTrue(sig.containsKey("rsi14"));
        assertFalse(sig.getBooleanValue("signal") && sig.getDoubleValue("maFast") <= sig.getDoubleValue("maSlow"));
    }
}
