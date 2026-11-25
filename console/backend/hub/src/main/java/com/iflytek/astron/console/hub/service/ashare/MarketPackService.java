package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Bar;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Quote;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Series;
import com.iflytek.astron.console.hub.service.ashare.MarketExtras.Announcement;
import com.iflytek.astron.console.hub.service.ashare.MarketExtras.Boards;
import com.iflytek.astron.console.hub.service.ashare.MarketExtras.Breadth;
import com.iflytek.astron.console.hub.service.ashare.MarketExtras.FlowDay;
import com.iflytek.astron.console.hub.service.ashare.MarketExtras.Lhb;
import com.iflytek.astron.console.hub.service.ashare.MarketExtras.Sector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything an agent should know before judging a stock, gathered in one call and labelled with
 * how trustworthy it is: quote, indicators, money flow, the sectors it belongs to, announcements,
 * 龙虎榜 appearances, and the market's own state (indices, breadth, leading sectors). Each block is
 * optional; what could not be fetched becomes a stated limitation in {@link DataQuality} instead of
 * an exception, because a partial analysis with honest caveats beats no analysis.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketPackService {

    private static final int BARS = 250;
    private static final int FLOW_DAYS = 20;

    private final EastmoneyClient market;
    private final MarketExtras extras;
    private final BarStore barStore;

    /** One stock's full picture plus the quality of the data behind it. */
    public record StockPack(String code, String name, Quote quote, Series series, List<FlowDay> flow, Boards boards,
            List<Announcement> announcements, List<Lhb> dragonTiger, DataQuality quality, TradingClock.Phase phase) {

        public JSONObject toJson() {
            JSONObject out = new JSONObject().fluentPut("code", code).fluentPut("name", name);
            if (quote != null) {
                out.put("quote", new JSONObject()
                        .fluentPut("price", quote.price())
                        .fluentPut("pctChg", quote.pctChg())
                        .fluentPut("prevClose", quote.prevClose())
                        .fluentPut("turnoverPct", quote.turnoverPct())
                        .fluentPut("amountYi", quote.amount() == null ? null
                                : Math.round(quote.amount().doubleValue() / 1e6) / 100.0)
                        .fluentPut("peDynamic", quote.pe())
                        .fluentPut("pb", quote.pb())
                        .fluentPut("totalCapYi", quote.totalCap() == null ? null
                                : Math.round(quote.totalCap().doubleValue() / 1e6) / 100.0)
                        .fluentPut("suspended", quote.suspended())
                        .fluentPut("time", quote.time() == null ? null : quote.time().toString()));
            }
            if (series != null && !series.bars().isEmpty()) {
                out.put("indicators", Indicators.latestSummary(series.bars()));
                out.put("lastBarDate", series.bars().get(series.bars().size() - 1).date().toString());
            }
            if (flow != null && !flow.isEmpty()) {
                JSONArray rows = new JSONArray();
                flow.subList(Math.max(0, flow.size() - 5), flow.size()).forEach(f -> rows.add(f.toJson()));
                Double main5 = MarketExtras.mainNetSum(flow, 5);
                out.put("fundFlow", new JSONObject()
                        .fluentPut("unit", "亿元，正为净流入")
                        .fluentPut("recent", rows)
                        .fluentPut("main5dYi", main5 == null ? null : Math.round(main5 / 1e6) / 100.0));
            }
            if (boards != null && !boards.isEmpty()) {
                out.put("boards", boards.toJson());
            }
            if (announcements != null && !announcements.isEmpty()) {
                JSONArray rows = new JSONArray();
                announcements.forEach(a -> rows.add(a.toJson()));
                out.put("announcements", rows);
            }
            if (dragonTiger != null && !dragonTiger.isEmpty()) {
                JSONArray rows = new JSONArray();
                dragonTiger.forEach(l -> rows.add(l.toJson()));
                out.put("dragonTiger", rows);
            }
            out.put("marketPhase", phase.name());
            out.put("marketPhaseLabel", phase.label());
            out.put("dataQuality", quality.toJson());
            out.put("rule", phase.rule());
            return out;
        }

        /** Short text for a prompt: the money-flow and sector facts an agent should cite. */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(name).append(' ').append(code);
            if (quote != null && quote.price() != null) {
                sb.append(String.format(Locale.ROOT, " 现价 %s（%+.2f%%）", quote.price(),
                        quote.pctChg() == null ? 0 : quote.pctChg().doubleValue()));
            }
            Double main5 = MarketExtras.mainNetSum(flow, 5);
            if (main5 != null) {
                sb.append(String.format(Locale.ROOT, "；近 5 日主力净%s %.2f 亿", main5 >= 0 ? "流入" : "流出",
                        Math.abs(main5) / 1e8));
            }
            if (boards != null && !boards.industries().isEmpty()) {
                sb.append("；所属 ").append(String.join("/", boards.industries()));
            }
            if (announcements != null && !announcements.isEmpty()) {
                sb.append("；最近公告 ").append(announcements.get(0).date()).append(' ')
                        .append(announcements.get(0).title());
            }
            return sb.toString();
        }
    }

    /** The market's own state: indices, breadth and the leading / lagging sectors. */
    public record MarketPack(JSONArray indices, Breadth breadth, List<Sector> industries, List<Sector> concepts,
            DataQuality quality, TradingClock.Phase phase) {

        public JSONObject toJson() {
            JSONObject out = new JSONObject()
                    .fluentPut("indices", indices)
                    .fluentPut("marketPhase", phase.name())
                    .fluentPut("marketPhaseLabel", phase.label())
                    .fluentPut("rule", phase.rule())
                    .fluentPut("dataQuality", quality.toJson());
            if (breadth != null) {
                out.put("breadth", breadth.toJson());
            }
            if (industries != null && !industries.isEmpty()) {
                JSONArray lead = new JSONArray();
                industries.subList(0, Math.min(5, industries.size())).forEach(s -> lead.add(s.toJson()));
                JSONArray lag = new JSONArray();
                List<Sector> tail = industries.subList(Math.max(0, industries.size() - 5), industries.size());
                for (int i = tail.size() - 1; i >= 0; i--) {
                    lag.add(tail.get(i).toJson());
                }
                out.put("industryLeaders", lead);
                out.put("industryLaggards", lag);
            }
            if (concepts != null && !concepts.isEmpty()) {
                JSONArray hot = new JSONArray();
                concepts.subList(0, Math.min(5, concepts.size())).forEach(s -> hot.add(s.toJson()));
                out.put("hotConcepts", hot);
            }
            return out;
        }

        /** Lines for a prompt; empty parts are left out rather than guessed. */
        public String describe() {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < indices.size(); i++) {
                JSONObject idx = indices.getJSONObject(i);
                if (idx.get("price") != null) {
                    parts.add(String.format(Locale.ROOT, "%s %s（%+.2f%%）", idx.getString("name"), idx.get("price"),
                            idx.getDoubleValue("pctChg")));
                }
            }
            StringBuilder sb = new StringBuilder();
            if (!parts.isEmpty()) {
                sb.append("指数：").append(String.join("，", parts)).append('\n');
            }
            String b = breadth == null ? null : breadth.describe();
            if (b != null) {
                sb.append("市场宽度：").append(b).append('\n');
            }
            String ind = MarketExtras.describeSectors(industries, 3);
            if (ind != null) {
                sb.append("行业板块：").append(ind).append('\n');
            }
            String con = MarketExtras.describeSectors(concepts, 3);
            if (con != null) {
                sb.append("概念板块：").append(con).append('\n');
            }
            return sb.toString();
        }
    }

    /** The market phase right now, using the stored index calendar to tell trading days apart. */
    public TradingClock.Phase phase() {
        LocalDate today = TradingClock.now().toLocalDate();
        boolean tradingDay;
        try {
            Series index = barStore.storedIndex(30);
            List<LocalDate> days = index.bars().stream().map(Bar::date).toList();
            LocalDate last = days.isEmpty() ? null : days.get(days.size() - 1);
            // Today is a trading day when the index already has today's bar, or when it is a weekday
            // and the latest stored bar is older than today (the bar appears only after the close).
            tradingDay = today.equals(last) || (today.getDayOfWeek().getValue() <= 5 && last != null
                    && last.isBefore(today));
        } catch (RuntimeException e) {
            tradingDay = today.getDayOfWeek().getValue() <= 5;
        }
        return TradingClock.phase(TradingClock.now(), tradingDay);
    }

    /**
     * One stock's pack. {@code deep} also fetches announcements and 龙虎榜 (two more requests), which the
     * per-minute paths skip.
     */
    public StockPack stock(String rawCode, boolean deep) {
        String code = AShareRules.normalizeCode(rawCode);
        TradingClock.Phase phase = phase();
        DataQuality quality = new DataQuality();
        LocalDate latestSettled = null;
        try {
            latestSettled = barStore.latestSettledDay(BARS);
        } catch (RuntimeException e) {
            log.info("Latest settled day unknown: {}", e.getMessage());
        }
        Quote quote = null;
        try {
            quote = market.quote(code);
            quality.put(DataQuality.Block.QUOTE, quote.suspended()
                    ? DataQuality.Status.STALE
                    : DataQuality.Status.FRESH, quote.suspended() ? "停牌，用前收盘价" : null);
            quality.put(DataQuality.Block.VALUATION,
                    quote.pe() == null && quote.pb() == null ? DataQuality.Status.MISSING : DataQuality.Status.FRESH);
        } catch (RuntimeException e) {
            quality.put(DataQuality.Block.QUOTE, DataQuality.Status.FETCH_FAILED, e.getMessage());
            quality.put(DataQuality.Block.VALUATION, DataQuality.Status.FETCH_FAILED);
        }
        Series series = null;
        try {
            series = market.dailyBars(code, true, BARS);
            List<LocalDate> dates = series.bars().stream().map(Bar::date).toList();
            DataQuality.Status bars = DataQuality.barStatus(dates, latestSettled, 60);
            String note = dates.isEmpty() ? null : "最后一根 " + dates.get(dates.size() - 1);
            quality.put(DataQuality.Block.DAILY_BARS, bars, bars == DataQuality.Status.FRESH ? null : note);
            quality.put(DataQuality.Block.TECHNICAL,
                    series.bars().size() >= 60 ? bars : DataQuality.Status.STALE,
                    series.bars().size() >= 60 ? null : "K 线不足 60 根，均线和 RSI 可能不可靠");
        } catch (RuntimeException e) {
            quality.put(DataQuality.Block.DAILY_BARS, DataQuality.Status.FETCH_FAILED, e.getMessage());
            quality.put(DataQuality.Block.TECHNICAL, DataQuality.Status.FETCH_FAILED);
        }
        List<FlowDay> flow = extras.flow(code, FLOW_DAYS);
        quality.put(DataQuality.Block.FUND_FLOW,
                flow.isEmpty() ? DataQuality.Status.FETCH_FAILED : DataQuality.Status.FRESH);
        Boards boards = extras.boards(code);
        List<Announcement> announcements = deep ? extras.announcements(code, 5) : List.of();
        List<Lhb> lhb = deep ? extras.dragonTiger(code, 3) : List.of();
        if (deep) {
            quality.put(DataQuality.Block.NEWS,
                    announcements.isEmpty() ? DataQuality.Status.MISSING : DataQuality.Status.FRESH,
                    announcements.isEmpty() ? "公告接口没有返回内容" : null);
        } else {
            quality.put(DataQuality.Block.NEWS, DataQuality.Status.NOT_SUPPORTED);
        }
        String name = quote != null ? quote.name() : series != null ? series.name() : code;
        return new StockPack(code, name, quote, series, flow, boards, announcements, lhb, quality, phase);
    }

    /** The market's state. Index quotes are required; breadth and sectors are best-effort. */
    public MarketPack overview() {
        TradingClock.Phase phase = phase();
        DataQuality quality = new DataQuality();
        JSONArray indices = new JSONArray();
        int ok = 0;
        for (var e : INDICES.entrySet()) {
            try {
                Quote q = market.quoteBySecid(e.getKey());
                indices.add(new JSONObject()
                        .fluentPut("name", e.getValue())
                        .fluentPut("price", q.price())
                        .fluentPut("pctChg", q.pctChg())
                        .fluentPut("amountYi", q.amount() == null ? null
                                : Math.round(q.amount().doubleValue() / 1e6) / 100.0)
                        .fluentPut("time", q.time() == null ? null : q.time().toString()));
                ok++;
            } catch (RuntimeException ex) {
                log.info("Index quote unavailable, secid={}, msg={}", e.getKey(), ex.getMessage());
            }
        }
        quality.put(DataQuality.Block.QUOTE, ok == 0 ? DataQuality.Status.FETCH_FAILED
                : ok < INDICES.size() ? DataQuality.Status.STALE : DataQuality.Status.FRESH);
        quality.put(DataQuality.Block.DAILY_BARS, DataQuality.Status.NOT_SUPPORTED);
        quality.put(DataQuality.Block.TECHNICAL, DataQuality.Status.NOT_SUPPORTED);
        quality.put(DataQuality.Block.VALUATION, DataQuality.Status.NOT_SUPPORTED);
        Breadth breadth = null;
        try {
            breadth = extras.breadth();
        } catch (RuntimeException e) {
            log.info("Breadth unavailable: {}", e.getMessage());
        }
        boolean hasBreadth = breadth != null && breadth.describe() != null;
        quality.put(DataQuality.Block.FUND_FLOW, hasBreadth ? DataQuality.Status.FRESH : DataQuality.Status.MISSING,
                hasBreadth ? null : phase == TradingClock.Phase.PRE_OPEN ? "盘前没有涨跌家数" : "涨跌家数接口取不到");
        List<Sector> industries = extras.sectors(false);
        List<Sector> concepts = extras.sectors(true);
        boolean hasSectors = MarketExtras.describeSectors(industries, 1) != null;
        quality.put(DataQuality.Block.NEWS, hasSectors ? DataQuality.Status.FRESH : DataQuality.Status.MISSING,
                hasSectors ? null : phase == TradingClock.Phase.PRE_OPEN ? "盘前板块涨跌幅为 0" : "板块排行取不到");
        return new MarketPack(indices, breadth, industries, concepts, quality, phase);
    }

    /** Major indices by Eastmoney security id. */
    private static final java.util.Map<String, String> INDICES = new java.util.LinkedHashMap<>();

    static {
        INDICES.put("1.000001", "上证指数");
        INDICES.put("0.399001", "深证成指");
        INDICES.put("0.399006", "创业板指");
        INDICES.put("1.000300", "沪深300");
    }
}
