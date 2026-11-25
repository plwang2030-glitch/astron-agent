package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Bar;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Series;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Portfolio backtest of {@link StrategyParams} on forward-adjusted daily bars, with the same rules
 * as the paper account's strategy trading: entries and daily exits at the close (live: 14:50); from
 * the day after the buy (T+1) intraday stop loss, trailing stop, partial and full take profit; 0.1%
 * slippage, no buy sealed at the limit up, no sell sealed at the limit down, lot sizes and fees.
 */
public final class Backtester {

    /** Bars before the window needed for MA60, the slowest MA and MACD. */
    public static final int WARMUP_BARS = 130;
    public static final String STOP_LOSS = "止损";
    public static final String TRAILING_STOP = "移动止损";
    public static final String PARTIAL_TAKE_PROFIT = "分批止盈";
    public static final String TAKE_PROFIT = "止盈";
    /** Market filter: the index must close above this moving average. */
    static final int MARKET_MA_DAYS = 20;

    private Backtester() {}

    public record Trade(String code, LocalDate entryDate, double entryPrice, LocalDate exitDate, double exitPrice,
            int qty, double pnl, String exitReason) {}

    /**
     * Result of a run. {@code score} is the annualized Sharpe ratio of the daily returns: unlike return
     * minus drawdown it does not reward simply investing less (scaling all positions down leaves it
     * about the same).
     */
    public record Result(LocalDate from, LocalDate to, double initialCash, double finalEquity, double returnPct,
            double maxDrawdownPct, int trades, double winRatePct, double avgHoldDays, double score,
            List<Trade> lastTrades, List<LocalDate> days, double[] equity, double[] invested, List<Trade> allTrades) {

        public Result(LocalDate from, LocalDate to, double initialCash, double finalEquity, double returnPct,
                double maxDrawdownPct, int trades, double winRatePct, double avgHoldDays, double score,
                List<Trade> lastTrades) {
            this(from, to, initialCash, finalEquity, returnPct, maxDrawdownPct, trades, winRatePct, avgHoldDays, score,
                    lastTrades, List.of(), new double[0], new double[0], lastTrades);
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .fluentPut("from", String.valueOf(from))
                    .fluentPut("to", String.valueOf(to))
                    .fluentPut("returnPct", Indicators.round(returnPct, 2))
                    .fluentPut("maxDrawdownPct", Indicators.round(maxDrawdownPct, 2))
                    .fluentPut("trades", trades)
                    .fluentPut("winRatePct", Indicators.round(winRatePct, 1))
                    .fluentPut("avgHoldDays", Indicators.round(avgHoldDays, 1))
                    .fluentPut("sharpe", Indicators.round(score, 2))
                    .fluentPut("score", Indicators.round(score, 2));
        }
    }

    /** Annualized Sharpe ratio of daily returns (no risk-free rate); 0 without variation. */
    public static double sharpe(double[] dailyReturns) {
        int n = dailyReturns.length;
        if (n < 2) {
            return 0;
        }
        double mean = 0;
        for (double r : dailyReturns) {
            mean += r;
        }
        mean /= n;
        double var = 0;
        for (double r : dailyReturns) {
            var += (r - mean) * (r - mean);
        }
        double sd = Math.sqrt(var / (n - 1));
        return sd < 1e-12 ? 0 : mean / sd * Math.sqrt(250);
    }

    /**
     * Test of the difference of two Sharpe ratios measured on the same days (Jobson & Korkie with
     * Memmel's correction): z &gt; 0 when {@code a} is better; about 1.28 / 1.65 is significant at the
     * 10% / 5% level (one-sided).
     */
    public static double sharpeDiffZ(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        if (n < 20) {
            return 0;
        }
        double ma = 0;
        double mb = 0;
        for (int i = 0; i < n; i++) {
            ma += a[i];
            mb += b[i];
        }
        ma /= n;
        mb /= n;
        double va = 0;
        double vb = 0;
        double cov = 0;
        for (int i = 0; i < n; i++) {
            va += (a[i] - ma) * (a[i] - ma);
            vb += (b[i] - mb) * (b[i] - mb);
            cov += (a[i] - ma) * (b[i] - mb);
        }
        double sa = Math.sqrt(va / (n - 1));
        double sb = Math.sqrt(vb / (n - 1));
        double srA = sa < 1e-12 ? 0 : ma / sa;
        double srB = sb < 1e-12 ? 0 : mb / sb;
        double rho = sa < 1e-12 || sb < 1e-12 ? 0 : cov / (n - 1) / (sa * sb);
        double v = (2 - 2 * rho + 0.5 * (srA * srA + srB * srB - 2 * srA * srB * rho * rho)) / n;
        return v <= 1e-15 ? 0 : (srA - srB) / Math.sqrt(v);
    }

    /** Metrics of one span of a run (from its daily equity curve). */
    public record Metrics(LocalDate from, LocalDate to, double returnPct, double maxDrawdownPct, double score,
            int trades, double winRatePct, double exposurePct, double[] dailyReturns) {

        public JSONObject toJson() {
            return new JSONObject()
                    .fluentPut("from", String.valueOf(from))
                    .fluentPut("to", String.valueOf(to))
                    .fluentPut("returnPct", Indicators.round(returnPct, 2))
                    .fluentPut("maxDrawdownPct", Indicators.round(maxDrawdownPct, 2))
                    .fluentPut("trades", trades)
                    .fluentPut("winRatePct", Indicators.round(winRatePct, 1))
                    .fluentPut("exposurePct", Indicators.round(exposurePct, 1))
                    .fluentPut("sharpe", Indicators.round(score, 2))
                    .fluentPut("score", Indicators.round(score, 2));
        }
    }

    /**
     * Metrics of {@code r} over days {@code [from, to]} of its curve (indexes into {@code r.days()}).
     */
    public static Metrics segment(Result r, int from, int to) {
        int n = r.days().size();
        from = Math.max(0, from);
        to = Math.min(n - 1, to);
        if (n == 0 || from > to) {
            return new Metrics(r.from(), r.to(), 0, 0, 0, 0, 0, 0, new double[0]);
        }
        double start = from == 0 ? r.initialCash() : r.equity()[from - 1];
        double[] rets = new double[to - from + 1];
        double prev = start;
        double peak = start;
        double maxDd = 0;
        double exposure = 0;
        for (int i = from; i <= to; i++) {
            double e = r.equity()[i];
            rets[i - from] = prev > 0 ? e / prev - 1 : 0;
            prev = e;
            peak = Math.max(peak, e);
            maxDd = Math.max(maxDd, peak > 0 ? (peak - e) / peak * 100 : 0);
            exposure += e > 0 ? r.invested()[i] / e : 0;
        }
        LocalDate d0 = r.days().get(from);
        LocalDate d1 = r.days().get(to);
        int trades = 0;
        int wins = 0;
        for (Trade t : r.allTrades()) {
            if (!t.exitDate().isBefore(d0) && !t.exitDate().isAfter(d1)) {
                trades++;
                if (t.pnl() > 0) {
                    wins++;
                }
            }
        }
        return new Metrics(d0, d1, start > 0 ? (prev / start - 1) * 100 : 0, maxDd, sharpe(rets), trades,
                trades == 0 ? 0 : wins * 100.0 / trades, exposure / rets.length * 100, rets);
    }

    /** Indicators of one stock for one parameter set. */
    static final class Prepared {
        final String code;
        final String name;
        final List<Bar> bars;
        final Map<LocalDate, Integer> index = new HashMap<>();
        final double[] close;
        final double[] fast;
        final double[] slow;
        final double[] ma60;
        final double[] rsi;
        final double[] volRatio;
        /** Highest high of the {@code breakoutDays} bars before each bar. */
        final double[] priorHigh;
        final double[] dif;
        final double[] dea;
        /** ATR14, for stops scaled to the stock's own volatility. */
        final double[] atr;
        /** Custom entry / exit rule values, or {@code null} when not used. */
        final double[] entryRule;
        final double[] exitRule;
        final int ruleWarmup;

        Prepared(Series s, StrategyParams p) {
            this(s, p, Map.of());
        }

        Prepared(Series s, StrategyParams p, Map<LocalDate, Double> indexClose) {
            code = s.code();
            name = s.name();
            bars = s.bars();
            for (int i = 0; i < bars.size(); i++) {
                index.put(bars.get(i).date(), i);
            }
            close = Indicators.closes(bars);
            fast = Indicators.sma(close, p.getMaFast());
            slow = Indicators.sma(close, p.getMaSlow());
            ma60 = Indicators.sma(close, 60);
            rsi = Indicators.rsi(close, 14);
            atr = Indicators.atr(bars, 14);
            volRatio = Indicators.volumeRatio(Indicators.volumes(bars), 5);
            priorHigh = new double[bars.size()];
            int n = p.getBreakoutDays();
            for (int i = 0; i < bars.size(); i++) {
                double max = Double.NaN;
                if (i >= n) {
                    max = Double.NEGATIVE_INFINITY;
                    for (int j = i - n; j < i; j++) {
                        max = Math.max(max, bars.get(j).high());
                    }
                }
                priorHigh[i] = max;
            }
            Indicators.Macd m = Indicators.macd(close);
            dif = m.dif();
            dea = m.dea();
            RuleExpression entry = p.usesCustomEntry() ? RuleExpression.parse(p.getCustomEntry()) : null;
            RuleExpression exit = p.hasCustomExit() ? RuleExpression.parse(p.getCustomExit()) : null;
            entryRule = entry == null ? null : entry.evaluate(bars, indexClose);
            exitRule = exit == null ? null : exit.evaluate(bars, indexClose);
            ruleWarmup = Math.max(entry == null ? 0 : entry.lookback(), exit == null ? 0 : exit.lookback()) + 1;
        }
    }

    private static boolean crossedUp(double[] a, double[] b, int i, int withinDays) {
        if (Double.isNaN(a[i]) || Double.isNaN(b[i]) || a[i] <= b[i]) {
            return false;
        }
        for (int j = i; j > i - withinDays && j >= 1; j--) {
            if (a[j] > b[j] && a[j - 1] <= b[j - 1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the entry rule holds at the close of bar {@code i}.
     *
     * @param marketOk the index is above its MA20 that day (ignored without the market filter)
     */
    static boolean entrySignal(StrategyParams p, Prepared d, int i, boolean marketOk) {
        if (Boolean.TRUE.equals(p.getMarketFilter()) && !marketOk) {
            return false;
        }
        if (d.entryRule != null) {
            // A custom rule is complete on its own: no built-in filters.
            return i >= d.ruleWarmup && RuleExpression.truthy(d.entryRule, i);
        }
        int warm = Math.max(Math.max(p.getMaSlow(), 60), Math.max(p.getBreakoutDays() + 1, 35));
        if (i < warm) {
            return false;
        }
        boolean signal = (p.usesMaCross() && crossedUp(d.fast, d.slow, i, p.getCrossWithinDays()))
                || (p.usesBreakout() && !Double.isNaN(d.priorHigh[i]) && d.close[i] > d.priorHigh[i])
                || (p.usesMacd() && crossedUp(d.dif, d.dea, i, p.getCrossWithinDays()));
        if (!signal || Double.isNaN(d.rsi[i]) || d.rsi[i] < p.getRsiMin() || d.rsi[i] > p.getRsiMax()) {
            return false;
        }
        if (Double.isNaN(d.volRatio[i]) || d.volRatio[i] < p.getVolumeRatioMin()) {
            return false;
        }
        if (Boolean.TRUE.equals(p.getMarketFilter()) && !marketOk) {
            return false;
        }
        return !Boolean.TRUE.equals(p.getRequireAboveMa60()) || (!Double.isNaN(d.ma60[i]) && d.close[i] > d.ma60[i]);
    }

    /** Days on which the index closed above its MA20; empty without index data. */
    static Map<LocalDate, Boolean> marketDays(Series index) {
        Map<LocalDate, Boolean> out = new HashMap<>();
        if (index == null) {
            return out;
        }
        double[] c = Indicators.closes(index.bars());
        double[] ma = Indicators.sma(c, MARKET_MA_DAYS);
        for (int i = 0; i < c.length; i++) {
            out.put(index.bars().get(i).date(), !Double.isNaN(ma[i]) && c[i] > ma[i]);
        }
        return out;
    }

    /** The index is above its MA20 on its latest bar (live, with today's bar during trading). */
    public static boolean marketOkNow(Series index) {
        if (index == null || index.bars().isEmpty()) {
            return true;
        }
        return marketDays(index).getOrDefault(index.bars().get(index.bars().size() - 1).date(), false);
    }

    /**
     * Daily exit reason at the close of bar {@code i}, or {@code null} to keep holding: stop loss and
     * take profit beyond the close (normally caught intraday first), max holding days, MA cross down.
     *
     * @param entryPrice average cost per share
     * @param holdDays trading days since the buy
     */
    static String exitReason(StrategyParams p, double close, double fast, double slow, double entryPrice,
            int holdDays) {
        return exitReason(p, close, fast, slow, entryPrice, holdDays, false, Double.NaN);
    }

    static String exitReason(StrategyParams p, double close, double fast, double slow, double entryPrice,
            int holdDays, boolean customExit) {
        return exitReason(p, close, fast, slow, entryPrice, holdDays, customExit, Double.NaN);
    }

    static String exitReason(StrategyParams p, double close, double fast, double slow, double entryPrice,
            int holdDays, boolean customExit, double atr) {
        if (entryPrice > 0 && close <= stopPrice(p, entryPrice, atr)) {
            return STOP_LOSS;
        }
        if (entryPrice > 0 && close >= entryPrice * (1 + p.getTakeProfitPct() / 100)) {
            return TAKE_PROFIT;
        }
        if (holdDays >= p.getMaxHoldDays()) {
            return "到期";
        }
        if (Boolean.TRUE.equals(p.getExitOnMaCrossDown()) && !Double.isNaN(fast) && !Double.isNaN(slow)
                && fast < slow) {
            return "均线死叉";
        }
        return customExit ? "自定义离场" : null;
    }

    /** Shanghai Composite close by date (for the {@code index} series of custom rules). */
    static Map<LocalDate, Double> indexCloses(Series index) {
        Map<LocalDate, Double> out = new HashMap<>();
        if (index != null) {
            for (Bar b : index.bars()) {
                out.put(b.date(), b.close());
            }
        }
        return out;
    }

    /** A price-level exit: where it fills and why. */
    public record PriceExit(double price, String reason) {

        public boolean partial() {
            return PARTIAL_TAKE_PROFIT.equals(reason);
        }
    }

    /**
     * Price-level exits of a position during one day, shared by the backtest (the day's open / high /
     * low) and the live monitor (the latest price as all three). In order: the stop (the higher of the
     * fixed stop loss and the trailing stop from {@code peak}), the partial take profit (not yet
     * taken), the take profit. A gap through a level fills at the open, otherwise at the level.
     */
    /**
     * How far below the entry the stop sits, in percent. In ATR mode the distance is
     * {@code stopAtrMult} × ATR14, so a volatile stock gets the room its own noise needs and a calm one
     * is kept tight, bounded by {@link StrategyParams#MIN_STOP_PCT} and
     * {@link StrategyParams#MAX_STOP_PCT}. Because position size is derived from this distance, a wider
     * stop buys a smaller position and the risk per trade stays the same. Falls back to
     * {@code stopLossPct} when the ATR is unavailable, so a missing indicator never removes the stop.
     */
    public static double stopDistancePct(StrategyParams p, double entryPrice, double atr) {
        if (!p.atrStop() || Double.isNaN(atr) || atr <= 0 || entryPrice <= 0) {
            return p.getStopLossPct();
        }
        double pct = p.getStopAtrMult() * atr / entryPrice * 100;
        return Math.min(StrategyParams.MAX_STOP_PCT, Math.max(StrategyParams.MIN_STOP_PCT, pct));
    }

    /** The stop price for a position, from {@link #stopDistancePct}. */
    public static double stopPrice(StrategyParams p, double entryPrice, double atr) {
        return entryPrice * (1 - stopDistancePct(p, entryPrice, atr) / 100);
    }

    public static PriceExit priceExit(StrategyParams p, double entryPrice, double peak, boolean partialTaken,
            double open, double high, double low) {
        return priceExit(p, entryPrice, peak, partialTaken, open, high, low, Double.NaN);
    }

    /**
     * Where a price-level exit fills and why, or {@code null} for none.
     *
     * @param atr ATR14 of the latest completed bar; {@code NaN} keeps the percentage stop
     */
    public static PriceExit priceExit(StrategyParams p, double entryPrice, double peak, boolean partialTaken,
            double open, double high, double low, double atr) {
        if (entryPrice <= 0) {
            return null;
        }
        double fixed = stopPrice(p, entryPrice, atr);
        double trailing = p.getTrailingStopPct() > 0 && peak > 0 ? peak * (1 - p.getTrailingStopPct() / 100) : 0;
        double stop = Math.max(fixed, trailing);
        String stopReason = trailing > fixed ? TRAILING_STOP : STOP_LOSS;
        if (open <= stop) {
            return new PriceExit(open, stopReason);
        }
        if (low <= stop) {
            return new PriceExit(stop, stopReason);
        }
        if (p.getPartialTakeProfitPct() > 0 && !partialTaken) {
            double level = entryPrice * (1 + p.getPartialTakeProfitPct() / 100);
            if (open >= level) {
                return new PriceExit(open, PARTIAL_TAKE_PROFIT);
            }
            if (high >= level) {
                return new PriceExit(level, PARTIAL_TAKE_PROFIT);
            }
        }
        double take = entryPrice * (1 + p.getTakeProfitPct() / 100);
        if (open >= take) {
            return new PriceExit(open, TAKE_PROFIT);
        }
        if (high >= take) {
            return new PriceExit(take, TAKE_PROFIT);
        }
        return null;
    }

    /** Entry signal on the latest bar of {@code s} (for scans), without the index. */
    public static JSONObject latestSignal(StrategyParams p, Series s) {
        return latestSignal(p, s, null);
    }

    /**
     * Entry signal on the latest bar of {@code s}; {@code index} (may be {@code null}) feeds the market
     * filter and the {@code index} series of custom rules.
     */
    public static JSONObject latestSignal(StrategyParams p, Series s, Series index) {
        boolean marketOk = index == null || marketOkNow(index);
        Prepared d = new Prepared(s, p, indexCloses(index));
        int i = d.bars.size() - 1;
        JSONObject out = new JSONObject()
                .fluentPut("code", d.code)
                .fluentPut("name", d.name);
        if (i < 0) {
            return out.fluentPut("signal", false);
        }
        return out.fluentPut("date", d.bars.get(i).date().toString())
                .fluentPut("close", Indicators.round(d.close[i], 3))
                .fluentPut("maFast", Indicators.round(d.fast[i], 3))
                .fluentPut("maSlow", Indicators.round(d.slow[i], 3))
                .fluentPut("ma60", Indicators.round(d.ma60[i], 3))
                .fluentPut("priorHigh", Indicators.round(d.priorHigh[i], 3))
                .fluentPut("macdDif", Indicators.round(d.dif[i], 3))
                .fluentPut("macdDea", Indicators.round(d.dea[i], 3))
                .fluentPut("rsi14", Indicators.round(d.rsi[i], 1))
                .fluentPut("volumeRatio", Indicators.round(d.volRatio[i], 2))
                .fluentPut("marketOk", marketOk)
                .fluentPut("customRule", d.entryRule == null ? null : RuleExpression.truthy(d.entryRule, i))
                .fluentPut("signal", entrySignal(p, d, i, marketOk));
    }

    /** Daily exit check on the latest bar of {@code s} for a position. */
    public static String latestExit(StrategyParams p, Series s, double entryPrice, int holdDays) {
        return latestExit(p, s, entryPrice, holdDays, null);
    }

    public static String latestExit(StrategyParams p, Series s, double entryPrice, int holdDays, Series index) {
        Prepared d = new Prepared(s, p, indexCloses(index));
        int i = d.bars.size() - 1;
        if (i < 0) {
            return null;
        }
        return exitReason(p, d.close[i], d.fast[i], d.slow[i], entryPrice, holdDays,
                d.exitRule != null && i >= d.ruleWarmup && RuleExpression.truthy(d.exitRule, i), d.atr[i]);
    }

    private static final class Position {
        int qty;
        double cost;
        double entryPrice;
        double peak;
        boolean partialTaken;
        LocalDate entryDate;
        int entryCal;
    }

    /** Backtest without the index (the market filter then always passes). */
    public static Result run(StrategyParams p, List<Series> data, List<LocalDate> calendar, int fromIdx, int toIdx,
            double initialCash) {
        return run(p, data, null, calendar, fromIdx, toIdx, initialCash);
    }

    /**
     * Runs over {@code calendar[fromIdx..toIdx]} starting with {@code initialCash}. Positions still
     * open at the end are valued at the last close.
     *
     * @param index Shanghai Composite bars for the market filter; {@code null} for none
     */
    public static Result run(StrategyParams p, List<Series> data, Series index, List<LocalDate> calendar, int fromIdx,
            int toIdx, double initialCash) {
        return run(p, data, index, calendar, fromIdx, toIdx, initialCash, null);
    }

    /**
     * Like {@link #run(StrategyParams, List, Series, List, int, int, double)}, with a universe that
     * changes each month (point in time): new positions are opened only in the stocks of the day's
     * month in {@code monthUniverse} (none in a month without an entry); positions in stocks that left
     * the universe are still managed and exit by the rules. {@code null} uses the strategy's list.
     */
    public static Result run(StrategyParams p, List<Series> data, Series index, List<LocalDate> calendar, int fromIdx,
            int toIdx, double initialCash, Map<YearMonth, Set<String>> monthUniverse) {
        Map<String, Prepared> stocks = new LinkedHashMap<>();
        Map<LocalDate, Double> indexClose = indexCloses(index);
        Set<String> wanted = new HashSet<>();
        if (monthUniverse == null) {
            wanted.addAll(p.getUniverse());
        } else {
            monthUniverse.values().forEach(wanted::addAll);
        }
        for (Series s : data) {
            if (s != null && !s.bars().isEmpty() && wanted.contains(s.code()) && !stocks.containsKey(s.code())) {
                stocks.put(s.code(), new Prepared(s, p, indexClose));
            }
        }
        Map<LocalDate, Boolean> market = marketDays(index);
        List<LocalDate> days = new ArrayList<>();
        double[] curve = new double[Math.max(0, toIdx - fromIdx + 1)];
        double[] investedCurve = new double[curve.length];
        double cash = initialCash;
        Map<String, Position> held = new LinkedHashMap<>();
        Map<String, Double> lastClose = new HashMap<>();
        List<Trade> trades = new ArrayList<>();
        double peakEquity = initialCash;
        double maxDd = 0;
        double equity = initialCash;
        for (int t = fromIdx; t <= toIdx; t++) {
            LocalDate day = calendar.get(t);
            // Intraday price levels of positions bought before today (T+1).
            for (var it = held.entrySet().iterator(); it.hasNext();) {
                var e = it.next();
                Position pos = e.getValue();
                Prepared d = stocks.get(e.getKey());
                Integer i = d.index.get(day);
                if (i == null || i == 0 || pos.entryCal >= t) {
                    continue;
                }
                Bar bar = d.bars.get(i);
                // With exits confirmed on the close, an intraday touch is ignored (CLOSE) or sells
                // only half (HALF_THEN_CLOSE); the rest is decided below on the near-final close.
                PriceExit exit = StrategyParams.CLOSE.equals(p.getExitCheck()) ? null
                        : priceExit(p, pos.entryPrice, pos.peak, pos.partialTaken, bar.open(), bar.high(), bar.low(),
                                d.atr[i]);
                double down = limitDownOf(d, i);
                if (exit != null && bar.high() > down) {
                    double px = Math.max(down, exit.price() * (1 - AShareRules.SLIPPAGE_VALUE));
                    boolean half = exit.partial() || (p.halfOnTouch() && !exit.partial());
                    if (half) {
                        int qty = AShareRules.halfQty(d.code, pos.qty);
                        if (exit.partial()) {
                            pos.partialTaken = true;
                        }
                        if (qty > 0) {
                            cash += sellPart(trades, d, pos, day, px, qty, exit.reason());
                        }
                        if (pos.qty <= 0) {
                            it.remove();
                            continue;
                        }
                    } else {
                        cash += sellPart(trades, d, pos, day, px, pos.qty, exit.reason());
                        it.remove();
                        continue;
                    }
                }
                // The trailing stop follows the high after the day is over (the order within the day is unknown).
                pos.peak = Math.max(pos.peak, bar.high());
            }
            // Close: daily exits, then entries.
            for (var it = held.entrySet().iterator(); it.hasNext();) {
                var e = it.next();
                Position pos = e.getValue();
                Prepared d = stocks.get(e.getKey());
                Integer i = d.index.get(day);
                if (i == null || i == 0 || pos.entryCal >= t) {
                    continue;
                }
                double close = d.close[i];
                double down = limitDownOf(d, i);
                if (close <= down) {
                    // Sealed at the limit down: nothing can be sold today.
                    continue;
                }
                double px = Math.max(down, close * (1 - AShareRules.SLIPPAGE_VALUE));
                // Versions that confirm on the close judge every price level here, on the day's own
                // close: the stop, the trailing stop and both take-profit levels.
                if (p.confirmOnClose()) {
                    PriceExit exit = priceExit(p, pos.entryPrice, pos.peak, pos.partialTaken, close, close, close,
                            d.atr[i]);
                    if (exit != null) {
                        if (exit.partial()) {
                            int qty = AShareRules.halfQty(d.code, pos.qty);
                            pos.partialTaken = true;
                            if (qty > 0) {
                                cash += sellPart(trades, d, pos, day, px, qty, exit.reason());
                            }
                            if (pos.qty <= 0) {
                                it.remove();
                            }
                        } else {
                            cash += sellPart(trades, d, pos, day, px, pos.qty, exit.reason());
                            it.remove();
                        }
                        continue;
                    }
                }
                String reason = exitReason(p, close, d.fast[i], d.slow[i], pos.entryPrice, t - pos.entryCal,
                        d.exitRule != null && i >= d.ruleWarmup && RuleExpression.truthy(d.exitRule, i), d.atr[i]);
                // Price levels were decided above (or intraday), so only the daily rules remain.
                if (reason == null || STOP_LOSS.equals(reason) || TAKE_PROFIT.equals(reason)) {
                    continue;
                }
                cash += sellPart(trades, d, pos, day, px, pos.qty, reason);
                it.remove();
            }
            double marketValue = 0;
            for (var e : held.entrySet()) {
                Prepared d = stocks.get(e.getKey());
                Integer i = d.index.get(day);
                if (i != null) {
                    lastClose.put(d.code, d.close[i]);
                }
                marketValue += e.getValue().qty * lastClose.getOrDefault(d.code, e.getValue().entryPrice);
            }
            double equityBefore = cash + marketValue;
            int slots = p.getMaxPositions() - held.size();
            boolean marketOk = market.isEmpty() || market.getOrDefault(day, false);
            Set<String> allowed = monthUniverse == null ? null : monthUniverse.getOrDefault(YearMonth.from(day), Set.of());
            if (slots > 0) {
                List<Prepared> candidates = new ArrayList<>();
                for (Prepared d : stocks.values()) {
                    if (held.containsKey(d.code) || (allowed != null && !allowed.contains(d.code))) {
                        continue;
                    }
                    Integer i = d.index.get(day);
                    if (i != null && i > 0 && entrySignal(p, d, i, marketOk)) {
                        candidates.add(d);
                    }
                }
                candidates.sort(Comparator.comparingDouble((Prepared d) -> -d.volRatio[d.index.get(day)]));
                for (Prepared d : candidates) {
                    if (slots <= 0) {
                        break;
                    }
                    int i = d.index.get(day);
                    // A wider stop (volatile stock) buys a smaller position, so risk per trade is equal.
                    double budget = p.positionBudget(equityBefore, stopDistancePct(p, d.close[i], d.atr[i]));
                    double up = AShareRules.limitUp(BigDecimal.valueOf(d.close[i - 1]), d.code, d.name).doubleValue();
                    if (d.close[i] >= up) {
                        continue; // sealed at the limit up
                    }
                    double px = Math.min(up, d.close[i] * (1 + AShareRules.SLIPPAGE_VALUE));
                    int qty = AShareRules.maxBuyQty(d.code, px, Math.min(budget, cash - 10));
                    if (qty <= 0) {
                        continue;
                    }
                    double amount = px * qty;
                    double total = amount + AShareRules.feesValue(true, amount);
                    if (total > cash) {
                        continue;
                    }
                    cash -= total;
                    Position pos = new Position();
                    pos.qty = qty;
                    pos.cost = total;
                    pos.entryPrice = total / qty;
                    pos.peak = d.close[i];
                    pos.entryDate = day;
                    pos.entryCal = t;
                    held.put(d.code, pos);
                    lastClose.put(d.code, d.close[i]);
                    slots--;
                }
            }
            marketValue = 0;
            for (var e : held.entrySet()) {
                marketValue += e.getValue().qty * lastClose.getOrDefault(e.getKey(), e.getValue().entryPrice);
            }
            equity = cash + marketValue;
            days.add(day);
            curve[t - fromIdx] = equity;
            investedCurve[t - fromIdx] = marketValue;
            peakEquity = Math.max(peakEquity, equity);
            maxDd = Math.max(maxDd, peakEquity > 0 ? (peakEquity - equity) / peakEquity * 100 : 0);
        }
        int wins = 0;
        double holdDays = 0;
        for (Trade tr : trades) {
            if (tr.pnl() > 0) {
                wins++;
            }
            int a = calendar.indexOf(tr.entryDate());
            int b = calendar.indexOf(tr.exitDate());
            holdDays += Math.max(0, b - a);
        }
        double returnPct = (equity / initialCash - 1) * 100;
        int n = trades.size();
        List<Trade> last = trades.subList(Math.max(0, n - 5), n);
        double[] rets = new double[curve.length];
        double prev = initialCash;
        for (int i = 0; i < curve.length; i++) {
            rets[i] = prev > 0 ? curve[i] / prev - 1 : 0;
            prev = curve[i];
        }
        return new Result(calendar.get(fromIdx), calendar.get(toIdx), initialCash, equity, returnPct, maxDd, n,
                n == 0 ? 0 : wins * 100.0 / n, n == 0 ? 0 : holdDays / n, sharpe(rets), List.copyOf(last),
                List.copyOf(days), curve, investedCurve, List.copyOf(trades));
    }

    private static double limitDownOf(Prepared d, int i) {
        return AShareRules.limitDown(BigDecimal.valueOf(d.close[i - 1]), d.code, d.name).doubleValue();
    }

    /**
     * Sells {@code qty} shares at {@code px}; returns the proceeds after fees and reduces the position.
     */
    private static double sellPart(List<Trade> trades, Prepared d, Position pos, LocalDate day, double px, int qty,
            String reason) {
        double amount = px * qty;
        double proceeds = amount - AShareRules.feesValue(false, amount);
        double costPart = pos.cost * qty / pos.qty;
        trades.add(new Trade(d.code, pos.entryDate, pos.entryPrice, day, px, qty, proceeds - costPart, reason));
        pos.cost -= costPart;
        pos.qty -= qty;
        return proceeds;
    }
}
