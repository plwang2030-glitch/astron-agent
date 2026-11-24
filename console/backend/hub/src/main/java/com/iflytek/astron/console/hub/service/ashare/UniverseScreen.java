package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSONObject;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Rule-based stock universe, computed from data as it was at a date (point in time), so that
 * backtests of past months use the stocks that met the rules then, not today's winners:
 * <ul>
 * <li>listed for at least a year (first bar at least {@link Rules#minListDays} days before);</li>
 * <li>not ST (by today's name; past ST status is not available);</li>
 * <li>average daily turnover over the last {@link Rules#amountDays} trading days ≥
 * {@link Rules#minAvgAmount};</li>
 * <li>market cap ≥ {@link Rules#minCap}, estimated for past dates as today's cap scaled by the
 * forward-adjusted price change;</li>
 * <li>one lot (100 shares, STAR 200) costs at most {@link Rules#maxLotCost};</li>
 * </ul>
 * ranked by average turnover; the top {@link Rules#tradeSize} are the trading universe and the top
 * {@link Rules#poolSize} the validation pool. A month's universe uses data up to the last trading
 * day before the month (no look-ahead).
 */
public final class UniverseScreen {

    private UniverseScreen() {}

    public record Rules(int minListDays, int amountDays, double minAvgAmount, double minCap, double maxLotCost,
            int tradeSize, int poolSize, boolean excludeSt) {

        /** 5亿 average turnover, 300亿 market cap, one lot within 25% of a 50,000 account. */
        public static Rules defaults() {
            return new Rules(365, 60, 5e8, 3e10, 12_500, 30, 100, true);
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .fluentPut("listedDays", minListDays)
                    .fluentPut("amountDays", amountDays)
                    .fluentPut("minAvgAmount", minAvgAmount)
                    .fluentPut("minCap", minCap)
                    .fluentPut("maxLotCost", maxLotCost)
                    .fluentPut("tradeSize", tradeSize)
                    .fluentPut("poolSize", poolSize)
                    .fluentPut("excludeSt", excludeSt);
        }

        public String describe() {
            return String.format(java.util.Locale.ROOT,
                    "上市满 %d 天%s，近 %d 个交易日日均成交额 ≥ %.0f 亿，总市值 ≥ %.0f 亿，1 手金额 ≤ %.0f 元；按日均成交额排序，前 %d 只为交易股票池，前 %d 只为扩展验证池",
                    minListDays, excludeSt ? "、非 ST" : "", amountDays, minAvgAmount / 1e8, minCap / 1e8, maxLotCost,
                    tradeSize, poolSize);
        }
    }

    /** A stock that may enter the universe: today's name and market cap. */
    public record Candidate(String code, String name, double capNow) {}

    /**
     * What the screen needs of a stock's history: dates, forward-adjusted closes, turnover.
     * {@code listedBefore} is true when the stored history is cut off by the download window (the stock
     * was listed before its first stored bar), so the listing-age rule is met once it has bars.
     */
    public record History(String code, LocalDate[] dates, double[] close, double[] amount, boolean listedBefore) {}

    /** A stock that passed the rules at a date. */
    public record Metric(String code, String name, double avgAmount, double cap, double price) {

        public JSONObject toJson() {
            return new JSONObject()
                    .fluentPut("code", code)
                    .fluentPut("name", name)
                    .fluentPut("avgAmount", Math.round(avgAmount / 1e6) / 100.0)
                    .fluentPut("cap", Math.round(cap / 1e6) / 100.0)
                    .fluentPut("price", Math.round(price * 100) / 100.0);
        }
    }

    /** The screen of one month. */
    public record Ranked(YearMonth month, LocalDate asOf, List<Metric> pool) {

        public List<String> trade(int n) {
            return pool.subList(0, Math.min(n, pool.size())).stream().map(Metric::code).toList();
        }

        public List<String> poolCodes() {
            return pool.stream().map(Metric::code).toList();
        }
    }

    /** The stock's metrics at {@code asOf}, or {@code null} when it does not meet the rules then. */
    static Metric metricAt(Candidate c, History h, LocalDate asOf, Rules r) {
        if (h == null || h.dates().length == 0) {
            return null;
        }
        if (r.excludeSt() && AShareRules.isSt(c.name())) {
            return null;
        }
        int i = lastIndexOnOrBefore(h.dates(), asOf);
        if (i < 0 || i + 1 < Math.min(r.amountDays(), 40)) {
            return null;
        }
        if (!h.listedBefore() && h.dates()[0].isAfter(asOf.minusDays(r.minListDays()))) {
            return null;
        }
        // Suspended or not traded recently: the last bar must be within about two weeks.
        if (h.dates()[i].isBefore(asOf.minusDays(14))) {
            return null;
        }
        int from = Math.max(0, i - r.amountDays() + 1);
        double sum = 0;
        for (int j = from; j <= i; j++) {
            sum += h.amount()[j];
        }
        double avg = sum / (i - from + 1);
        double last = h.close()[h.close().length - 1];
        double price = h.close()[i];
        double cap = last > 0 ? c.capNow() * price / last : 0;
        int lot = AShareRules.board(c.code()) == AShareRules.Board.STAR ? 200 : 100;
        if (avg < r.minAvgAmount() || cap < r.minCap() || price * lot > r.maxLotCost()) {
            return null;
        }
        return new Metric(c.code(), c.name(), avg, cap, price);
    }

    /** The screen at {@code asOf} for {@code month}. */
    public static Ranked rank(List<Candidate> candidates, Map<String, History> histories, YearMonth month,
            LocalDate asOf, Rules r) {
        List<Metric> passed = new ArrayList<>();
        for (Candidate c : candidates) {
            Metric m = metricAt(c, histories.get(c.code()), asOf, r);
            if (m != null) {
                passed.add(m);
            }
        }
        passed.sort(Comparator.comparingDouble(Metric::avgAmount).reversed());
        return new Ranked(month, asOf, List.copyOf(passed.subList(0, Math.min(r.poolSize(), passed.size()))));
    }

    /**
     * Point-in-time screens for every month of the trading calendar after its first (partial) month:
     * month M uses data up to the last trading day before M's first trading day. Sorted by month.
     */
    public static Map<YearMonth, Ranked> history(List<Candidate> candidates, Map<String, History> histories,
            List<LocalDate> calendar, Rules r) {
        Map<YearMonth, Ranked> out = new TreeMap<>();
        for (int i = 1; i < calendar.size(); i++) {
            YearMonth m = YearMonth.from(calendar.get(i));
            if (m.equals(YearMonth.from(calendar.get(i - 1)))) {
                continue;
            }
            out.put(m, rank(candidates, histories, m, calendar.get(i - 1), r));
        }
        return out;
    }

    static int lastIndexOnOrBefore(LocalDate[] dates, LocalDate d) {
        int lo = 0;
        int hi = dates.length - 1;
        int ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (!dates[mid].isAfter(d)) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }
}
