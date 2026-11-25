package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.HostPool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Market data beyond price and volume, from the same free public endpoints: where the money goes,
 * which sectors a stock belongs to, what it announced, and how broad the market's move is. Each
 * item has a fallback provider, and every method answers with what it could get instead of failing
 * the whole analysis, so a missing block becomes a stated limitation (see {@link DataQuality}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketExtras {

    private static final String UT = "fa5fd1943c7b386f172d6893dbfba10b";
    /** Eastmoney's own 涨停板行情 pages use this token. */
    private static final String POOL_UT = "7eea3edcaed734bea9cbfc24409ed989";
    private static final Charset GBK = Charset.forName("GBK");
    private static final long FLOW_TTL = 10 * 60_000L;
    private static final long BOARDS_TTL = 6 * 60 * 60_000L;
    private static final long ANNOUNCE_TTL = 30 * 60_000L;
    private static final long BREADTH_TTL = 60_000L;
    private static final long SECTOR_TTL = 5 * 60_000L;
    private static final long LHB_TTL = 30 * 60_000L;

    private final EastmoneyClient market;

    private final HostPool flowHosts = new HostPool("push2his.eastmoney.com", "7.push2his.eastmoney.com",
            "33.push2his.eastmoney.com", "91.push2his.eastmoney.com");

    // ------------------------------------------------------------------ fund flow

    /**
     * One day of money flow in yuan: {@code net} is the whole day's net inflow, {@code main} the net
     * of large and extra-large orders (institutions and big players), {@code retail} the net of small
     * and medium orders.
     */
    public record FlowDay(LocalDate date, double net, double main, double retail, Double pctChg) {

        public JSONObject toJson() {
            return new JSONObject()
                    .fluentPut("date", date.toString())
                    .fluentPut("netYi", round(net))
                    .fluentPut("mainYi", round(main))
                    .fluentPut("retailYi", round(retail))
                    .fluentPut("pctChg", pctChg);
        }

        private static double round(double yuan) {
            return Math.round(yuan / 1e6) / 100.0;
        }
    }

    /**
     * Daily money flow of a stock, newest last. Sina is the primary source because Eastmoney's flow
     * mirrors drop connections from here; Eastmoney is the fallback. Empty when neither answers.
     */
    public List<FlowDay> flow(String rawCode, int days) {
        String code = AShareRules.normalizeCode(rawCode);
        int limit = Math.max(1, Math.min(60, days));
        return market.cached("flow:" + code + ":" + limit, FLOW_TTL, () -> {
            try {
                return sinaFlow(code, limit);
            } catch (RuntimeException e) {
                log.info("Sina flow unavailable, falling back to Eastmoney, code={}, msg={}", code, e.getMessage());
            }
            try {
                return eastmoneyFlow(code, limit);
            } catch (RuntimeException e) {
                log.warn("Fund flow unavailable, code={}, msg={}", code, e.getMessage());
                return List.of();
            }
        });
    }

    /** Sina 资金流向: {@code r0}/{@code r1} are extra-large/large orders, {@code r2}/{@code r3} small. */
    private List<FlowDay> sinaFlow(String code, int limit) {
        HttpUrl url = HttpUrl.parse("https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/MoneyFlow.ssl_qsfx_lscjfb")
                .newBuilder()
                .addQueryParameter("page", "1")
                .addQueryParameter("num", String.valueOf(limit))
                .addQueryParameter("sort", "opendate")
                .addQueryParameter("asc", "0")
                .addQueryParameter("daima", AShareRules.sinaSymbol(code))
                .build();
        JSONArray rows = market.getArray(url, "https://finance.sina.com.cn");
        if (rows == null || rows.isEmpty()) {
            throw new AShareDataException("资金流向返回为空");
        }
        List<FlowDay> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            JSONObject r = rows.getJSONObject(i);
            double main = dbl(r, "r0_net") + dbl(r, "r1_net");
            double retail = dbl(r, "r2_net") + dbl(r, "r3_net");
            Double pct = r.get("changeratio") == null ? null
                    : Math.round(r.getDoubleValue("changeratio") * 10000) / 100.0;
            out.add(new FlowDay(LocalDate.parse(r.getString("opendate")), dbl(r, "netamount"), main, retail, pct));
        }
        out.sort(Comparator.comparing(FlowDay::date));
        return List.copyOf(out);
    }

    /**
     * Eastmoney 资金流向 day line: {@code f51} date, {@code f52} whole-day net, {@code f55} large net,
     * {@code f56} extra-large net, {@code f53}/{@code f54} small and medium net.
     */
    private List<FlowDay> eastmoneyFlow(String code, int limit) {
        HttpUrl url = HttpUrl.parse("https://push2his.eastmoney.com/api/qt/stock/fflow/daykline/get")
                .newBuilder()
                .addQueryParameter("lmt", String.valueOf(limit))
                .addQueryParameter("klt", "101")
                .addQueryParameter("secid", AShareRules.secid(code))
                .addQueryParameter("fields1", "f1,f2,f3,f7")
                .addQueryParameter("fields2", "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61,f62,f63,f64,f65")
                .addQueryParameter("ut", UT)
                .build();
        JSONObject data = market.get(url, flowHosts).getJSONObject("data");
        JSONArray klines = data == null ? null : data.getJSONArray("klines");
        if (klines == null || klines.isEmpty()) {
            throw new AShareDataException("资金流向返回为空");
        }
        List<FlowDay> out = new ArrayList<>();
        for (int i = 0; i < klines.size(); i++) {
            String[] p = klines.getString(i).split(",");
            if (p.length < 7) {
                continue;
            }
            double net = num(p[1]);
            double main = num(p[4]) + num(p[5]);
            double retail = num(p[2]) + num(p[3]);
            out.add(new FlowDay(LocalDate.parse(p[0]), net, main, retail, p.length > 11 ? num(p[11]) : null));
        }
        return List.copyOf(out);
    }

    /**
     * Sum of the last {@code days} days of main-player net flow, in yuan; {@code null} with no data.
     */
    public static Double mainNetSum(List<FlowDay> flow, int days) {
        if (flow == null || flow.isEmpty()) {
            return null;
        }
        double sum = 0;
        for (FlowDay d : flow.subList(Math.max(0, flow.size() - days), flow.size())) {
            sum += d.main();
        }
        return sum;
    }

    // ------------------------------------------------------------------ sectors of a stock

    /** The sectors and concepts a stock belongs to, strongest association first. */
    public record Boards(List<String> industries, List<String> concepts, List<String> coreBusiness) {

        public boolean isEmpty() {
            return industries.isEmpty() && concepts.isEmpty();
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .fluentPut("industries", industries)
                    .fluentPut("concepts", concepts)
                    .fluentPut("coreBusiness", coreBusiness);
        }
    }

    /**
     * Sectors of a stock from Eastmoney's F10 core-concept page (ranked by how closely it belongs),
     * falling back to the quote page's board list. The first three ranked entries are treated as the
     * industry, the rest as concepts. Empty when both fail.
     */
    public Boards boards(String rawCode) {
        String code = AShareRules.normalizeCode(rawCode);
        return market.cached("boards:" + code, BOARDS_TTL, () -> {
            try {
                return f10Boards(code);
            } catch (RuntimeException e) {
                log.info("F10 boards unavailable, falling back, code={}, msg={}", code, e.getMessage());
            }
            try {
                return slistBoards(code);
            } catch (RuntimeException e) {
                log.warn("Boards unavailable, code={}, msg={}", code, e.getMessage());
                return new Boards(List.of(), List.of(), List.of());
            }
        });
    }

    private Boards f10Boards(String code) {
        String market10 = AShareRules.board(code) == AShareRules.Board.SH_MAIN
                || AShareRules.board(code) == AShareRules.Board.STAR ? "SH" : "SZ";
        HttpUrl url = HttpUrl.parse("https://emweb.securities.eastmoney.com/PC_HSF10/CoreConception/PageAjax")
                .newBuilder()
                .addQueryParameter("code", market10 + code)
                .build();
        String text = market.getText(url, "https://emweb.securities.eastmoney.com/", null);
        JSONObject json = JSON.parseObject(text);
        JSONArray ssbk = json == null ? null : json.getJSONArray("ssbk");
        if (ssbk == null || ssbk.isEmpty()) {
            throw new AShareDataException("板块信息返回为空");
        }
        List<String> ranked = new ArrayList<>();
        for (int i = 0; i < ssbk.size(); i++) {
            String name = ssbk.getJSONObject(i).getString("BOARD_NAME");
            if (StringUtils.isNotBlank(name)) {
                ranked.add(name);
            }
        }
        List<String> core = new ArrayList<>();
        JSONArray hxtc = json.getJSONArray("hxtc");
        for (int i = 0; hxtc != null && i < hxtc.size() && core.size() < 3; i++) {
            JSONObject row = hxtc.getJSONObject(i);
            if ("主营业务".equals(row.getString("KEY_CLASSIF")) || "行业背景".equals(row.getString("KEY_CLASSIF"))) {
                core.add(StringUtils.abbreviate(row.getString("KEYWORD"), 60));
            }
        }
        return new Boards(List.copyOf(ranked.subList(0, Math.min(3, ranked.size()))),
                List.copyOf(ranked.subList(Math.min(3, ranked.size()), Math.min(15, ranked.size()))), List.copyOf(core));
    }

    private Boards slistBoards(String code) {
        HttpUrl url = HttpUrl.parse("https://push2.eastmoney.com/api/qt/slist/get")
                .newBuilder()
                .addQueryParameter("spt", "3")
                .addQueryParameter("secid", AShareRules.secid(code))
                .addQueryParameter("fields", "f12,f14,f3")
                .addQueryParameter("ut", UT)
                .addQueryParameter("pi", "0")
                .addQueryParameter("pz", "30")
                .addQueryParameter("po", "1")
                .addQueryParameter("fid", "f3")
                .build();
        JSONObject data = market.get(url, null).getJSONObject("data");
        JSONObject diff = data == null ? null : data.getJSONObject("diff");
        if (diff == null || diff.isEmpty()) {
            throw new AShareDataException("板块信息返回为空");
        }
        List<String> names = new ArrayList<>();
        for (String k : diff.keySet()) {
            String name = diff.getJSONObject(k).getString("f14");
            if (StringUtils.isNotBlank(name)) {
                names.add(name);
            }
        }
        return new Boards(List.of(), List.copyOf(names.subList(0, Math.min(15, names.size()))), List.of());
    }

    // ------------------------------------------------------------------ sector rankings

    /** A sector's move today. */
    public record Sector(String name, int members, double pctChg) {

        public JSONObject toJson() {
            return new JSONObject().fluentPut("name", name).fluentPut("members", members)
                    .fluentPut("pctChg", Math.round(pctChg * 100) / 100.0);
        }
    }

    /**
     * Industry and concept sectors sorted by today's move, leaders first. From Sina's sector pages
     * (Eastmoney's sector list endpoint is unreachable from some networks). Empty when unavailable;
     * all-zero moves before the open are reported as they are.
     */
    public List<Sector> sectors(boolean concepts) {
        String key = concepts ? "sectors:concept" : "sectors:industry";
        return market.cached(key, SECTOR_TTL, () -> {
            try {
                HttpUrl url = concepts
                        ? HttpUrl.parse("https://money.finance.sina.com.cn/q/view/newFLJK.php?param=class")
                        : HttpUrl.parse("https://vip.stock.finance.sina.com.cn/q/view/newSinaHy.php");
                String text = market.getText(url, "https://finance.sina.com.cn", GBK);
                return parseSinaSectors(text);
            } catch (RuntimeException e) {
                log.warn("Sector rankings unavailable, concepts={}, msg={}", concepts, e.getMessage());
                return List.of();
            }
        });
    }

    /**
     * Sina answers with {@code var X = {"key":"code,name,members,...,pctChg,...", ...}}. The row is a
     * comma-separated list whose 4th field is the average price and 5th the move in percent.
     */
    static List<Sector> parseSinaSectors(String text) {
        int from = text.indexOf('{');
        int to = text.lastIndexOf('}');
        if (from < 0 || to <= from) {
            throw new AShareDataException("板块排行返回格式变化");
        }
        JSONObject json = JSON.parseObject(text.substring(from, to + 1));
        List<Sector> out = new ArrayList<>();
        for (String k : json.keySet()) {
            String[] p = StringUtils.defaultString(json.getString(k)).split(",");
            if (p.length < 6 || StringUtils.isBlank(p[1])) {
                continue;
            }
            try {
                out.add(new Sector(p[1], (int) num(p[2]), num(p[5])));
            } catch (RuntimeException e) {
                // A row with unexpected fields is skipped, the rest of the ranking is still usable.
            }
        }
        out.sort(Comparator.comparingDouble(Sector::pctChg).reversed());
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ market breadth

    /**
     * How broad the market's move is: how many stocks rose and fell, and how many hit the limit up or
     * down. {@code advances} is {@code null} when only the limit counts could be read.
     */
    public record Breadth(Integer advances, Integer declines, Integer unchanged, Integer limitUp, Integer limitDown,
            Integer brokenLimitUp, LocalDate date) {

        public JSONObject toJson() {
            return new JSONObject()
                    .fluentPut("date", date == null ? null : date.toString())
                    .fluentPut("advances", advances)
                    .fluentPut("declines", declines)
                    .fluentPut("unchanged", unchanged)
                    .fluentPut("limitUp", limitUp)
                    .fluentPut("limitDown", limitDown)
                    .fluentPut("brokenLimitUp", brokenLimitUp);
        }

        /** One line for a prompt, or {@code null} when nothing could be read. */
        public String describe() {
            if (advances == null && limitUp == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            if (advances != null) {
                sb.append("上涨 ").append(advances).append(" 家，下跌 ").append(declines).append(" 家");
                if (unchanged != null) {
                    sb.append("，平盘 ").append(unchanged).append(" 家");
                }
            }
            if (limitUp != null) {
                sb.append(sb.length() > 0 ? "；" : "").append("涨停 ").append(limitUp).append(" 家");
                if (limitDown != null) {
                    sb.append("，跌停 ").append(limitDown).append(" 家");
                }
                if (brokenLimitUp != null && brokenLimitUp > 0) {
                    sb.append("，炸板 ").append(brokenLimitUp).append(" 家");
                }
            }
            return sb.toString();
        }
    }

    /**
     * Market breadth: advance/decline counts from the index quote fields and the limit-up / limit-down
     * pools. Each part is optional; whatever is missing stays {@code null}.
     */
    public Breadth breadth() {
        return market.cached("breadth", BREADTH_TTL, () -> {
            Integer[] ad = advanceDecline();
            Integer[] limits = limitCounts();
            return new Breadth(ad[0], ad[1], ad[2], limits[0], limits[1], limits[2],
                    TradingClock.now().toLocalDate());
        });
    }

    /** {@code f104} rising, {@code f105} falling, {@code f106} unchanged members of the index. */
    private Integer[] advanceDecline() {
        try {
            HttpUrl url = HttpUrl.parse("https://push2.eastmoney.com/api/qt/ulist.np/get")
                    .newBuilder()
                    .addQueryParameter("fltt", "2")
                    .addQueryParameter("secids", "1.000001,0.399001")
                    .addQueryParameter("fields", "f12,f104,f105,f106")
                    .addQueryParameter("ut", UT)
                    .build();
            JSONObject data = market.get(url, null).getJSONObject("data");
            JSONArray diff = data == null ? null : data.getJSONArray("diff");
            if (diff == null || diff.isEmpty()) {
                return new Integer[] {null, null, null};
            }
            int up = 0;
            int down = 0;
            int flat = 0;
            for (int i = 0; i < diff.size(); i++) {
                JSONObject r = diff.getJSONObject(i);
                up += r.getIntValue("f104");
                down += r.getIntValue("f105");
                flat += r.getIntValue("f106");
            }
            // Before the open every count is zero: that is no data, not a flat market.
            if (up + down + flat == 0) {
                return new Integer[] {null, null, null};
            }
            return new Integer[] {up, down, flat};
        } catch (RuntimeException e) {
            log.info("Advance/decline unavailable: {}", e.getMessage());
            return new Integer[] {null, null, null};
        }
    }

    /** Limit-up, limit-down and broken-limit counts from Eastmoney's 涨停板行情 pools. */
    private Integer[] limitCounts() {
        Integer up = null;
        Integer broken = null;
        Integer down = null;
        try {
            JSONObject pool = pool("getTopicZTPool", "fbt:asc");
            up = pool == null ? null : pool.getInteger("tc");
            JSONArray rows = pool == null ? null : pool.getJSONArray("pool");
            if (rows != null) {
                int zb = 0;
                for (int i = 0; i < rows.size(); i++) {
                    zb += rows.getJSONObject(i).getIntValue("zbc") > 0 ? 1 : 0;
                }
                broken = zb;
            }
        } catch (RuntimeException e) {
            log.info("Limit-up pool unavailable: {}", e.getMessage());
        }
        try {
            JSONObject pool = pool("getTopicDTPool", "fund:asc");
            down = pool == null ? null : pool.getInteger("tc");
        } catch (RuntimeException e) {
            log.info("Limit-down pool unavailable: {}", e.getMessage());
        }
        return new Integer[] {up, down, broken};
    }

    private JSONObject pool(String path, String sort) {
        HttpUrl url = HttpUrl.parse("https://push2ex.eastmoney.com/" + path)
                .newBuilder()
                .addQueryParameter("ut", POOL_UT)
                .addQueryParameter("dpt", "wz.ztzt")
                .addQueryParameter("Pageindex", "0")
                .addQueryParameter("pagesize", "170")
                .addQueryParameter("sort", sort)
                .addQueryParameter("date", TradingClock.now().toLocalDate().toString().replace("-", ""))
                .build();
        return market.get(url, null).getJSONObject("data");
    }

    // ------------------------------------------------------------------ announcements

    /** One announcement of a company. */
    public record Announcement(LocalDate date, String title, List<String> types) {

        public JSONObject toJson() {
            return new JSONObject().fluentPut("date", date == null ? null : date.toString())
                    .fluentPut("title", title)
                    .fluentPut("types", types);
        }
    }

    /** Recent announcements of a stock, newest first; empty when the endpoint is unavailable. */
    public List<Announcement> announcements(String rawCode, int limit) {
        String code = AShareRules.normalizeCode(rawCode);
        int n = Math.max(1, Math.min(20, limit));
        return market.cached("ann:" + code + ":" + n, ANNOUNCE_TTL, () -> {
            try {
                HttpUrl url = HttpUrl.parse("https://np-anotice-stock.eastmoney.com/api/security/ann")
                        .newBuilder()
                        .addQueryParameter("sr", "-1")
                        .addQueryParameter("page_size", String.valueOf(n))
                        .addQueryParameter("page_index", "1")
                        .addQueryParameter("ann_type", "A")
                        .addQueryParameter("client_source", "web")
                        .addQueryParameter("stock_list", code)
                        .addQueryParameter("f_node", "0")
                        .addQueryParameter("s_node", "0")
                        .build();
                JSONObject data = market.get(url, null).getJSONObject("data");
                JSONArray list = data == null ? null : data.getJSONArray("list");
                if (list == null) {
                    return List.<Announcement>of();
                }
                List<Announcement> out = new ArrayList<>();
                for (int i = 0; i < list.size(); i++) {
                    JSONObject a = list.getJSONObject(i);
                    List<String> types = new ArrayList<>();
                    JSONArray columns = a.getJSONArray("columns");
                    for (int j = 0; columns != null && j < columns.size(); j++) {
                        String name = columns.getJSONObject(j).getString("column_name");
                        if (StringUtils.isNotBlank(name)) {
                            types.add(name);
                        }
                    }
                    out.add(new Announcement(parseDay(a.getString("notice_date")),
                            StringUtils.abbreviate(a.getString("title"), 120), List.copyOf(types)));
                }
                return List.copyOf(out);
            } catch (RuntimeException e) {
                log.warn("Announcements unavailable, code={}, msg={}", code, e.getMessage());
                return List.<Announcement>of();
            }
        });
    }

    // ------------------------------------------------------------------ dragon-tiger list

    /** One appearance of a stock on the 龙虎榜 (the exchange's daily disclosure of big trades). */
    public record Lhb(LocalDate date, String reason, double netAmount, double buyAmount, double sellAmount,
            String explain, double pctChg) {

        public JSONObject toJson() {
            return new JSONObject()
                    .fluentPut("date", date == null ? null : date.toString())
                    .fluentPut("reason", reason)
                    .fluentPut("netYi", Math.round(netAmount / 1e6) / 100.0)
                    .fluentPut("buyYi", Math.round(buyAmount / 1e6) / 100.0)
                    .fluentPut("sellYi", Math.round(sellAmount / 1e6) / 100.0)
                    .fluentPut("seats", explain)
                    .fluentPut("pctChg", pctChg);
        }
    }

    /** The stock's recent 龙虎榜 appearances, newest first; empty when unavailable. */
    public List<Lhb> dragonTiger(String rawCode, int limit) {
        String code = AShareRules.normalizeCode(rawCode);
        int n = Math.max(1, Math.min(10, limit));
        return market.cached("lhb:" + code + ":" + n, LHB_TTL, () -> {
            try {
                HttpUrl url = HttpUrl.parse("https://datacenter-web.eastmoney.com/api/data/v1/get")
                        .newBuilder()
                        .addQueryParameter("sortColumns", "TRADE_DATE")
                        .addQueryParameter("sortTypes", "-1")
                        .addQueryParameter("pageSize", String.valueOf(n))
                        .addQueryParameter("pageNumber", "1")
                        .addQueryParameter("reportName", "RPT_DAILYBILLBOARD_DETAILSNEW")
                        .addQueryParameter("columns", "ALL")
                        .addQueryParameter("filter", "(SECURITY_CODE=\"" + code + "\")")
                        .build();
                JSONObject result = market.get(url, null).getJSONObject("result");
                JSONArray data = result == null ? null : result.getJSONArray("data");
                if (data == null) {
                    return List.<Lhb>of();
                }
                List<Lhb> out = new ArrayList<>();
                for (int i = 0; i < data.size(); i++) {
                    JSONObject r = data.getJSONObject(i);
                    out.add(new Lhb(parseDay(r.getString("TRADE_DATE")),
                            StringUtils.abbreviate(r.getString("EXPLANATION"), 80),
                            r.getDoubleValue("BILLBOARD_NET_AMT"), r.getDoubleValue("BILLBOARD_BUY_AMT"),
                            r.getDoubleValue("BILLBOARD_SELL_AMT"),
                            StringUtils.abbreviate(r.getString("EXPLAIN"), 80),
                            Math.round(r.getDoubleValue("CHANGE_RATE") * 100) / 100.0));
                }
                return List.copyOf(out);
            } catch (RuntimeException e) {
                log.warn("Dragon-tiger list unavailable, code={}, msg={}", code, e.getMessage());
                return List.<Lhb>of();
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    /** The leading and lagging sectors in one line, or {@code null} when the ranking is unavailable. */
    public static String describeSectors(List<Sector> sectors, int n) {
        if (sectors == null || sectors.isEmpty()) {
            return null;
        }
        boolean allFlat = sectors.stream().allMatch(s -> s.pctChg() == 0);
        if (allFlat) {
            return null;
        }
        Map<String, List<String>> parts = new LinkedHashMap<>();
        parts.put("领涨", sectors.subList(0, Math.min(n, sectors.size()))
                .stream()
                .map(s -> String.format(Locale.ROOT, "%s %+.2f%%", s.name(), s.pctChg()))
                .toList());
        List<Sector> tail = sectors.subList(Math.max(0, sectors.size() - n), sectors.size());
        List<String> lag = new ArrayList<>(tail.stream()
                .map(s -> String.format(Locale.ROOT, "%s %+.2f%%", s.name(), s.pctChg()))
                .toList());
        java.util.Collections.reverse(lag);
        parts.put("领跌", lag);
        return "领涨：" + String.join("、", parts.get("领涨")) + "；领跌：" + String.join("、", parts.get("领跌"));
    }

    private static LocalDate parseDay(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static double dbl(JSONObject row, String key) {
        Object v = row.get(key);
        if (v == null) {
            return 0;
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(StringUtils.trimToEmpty(s));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
