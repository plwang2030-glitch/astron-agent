package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Market data from Eastmoney's public web endpoints (quote, daily K-line, code search), with
 * Tencent's public K-line endpoint as a fallback for daily bars. Both are free but unofficial: the
 * format can change without notice and use is subject to their terms, so failures surface as
 * {@link AShareDataException} and results are cached briefly.
 */
@Slf4j
@Component
public class EastmoneyClient {

    /** Shanghai Composite, used as the trading calendar. */
    public static final String CALENDAR_SECID = "1.000001";
    private static final String UT = "fa5fd1943c7b386f172d6893dbfba10b";
    private static final String SEARCH_TOKEN = "D43BF722C8E33BDC906FB84D85E326E8";
    private static final String QUOTE_FIELDS = "f43,f44,f45,f46,f47,f48,f57,f58,f60,f86,f116,f117,f162,f167,f168,f169,f170";
    private static final int MAX_CACHE_ENTRIES = 2000;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(8))
            .build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    /** Eastmoney K-line requests are skipped until then (Tencent is used instead). */
    private volatile long eastmoneyKlineDownUntil;
    private static final long KLINE_FALLBACK_MILLIS = 5 * 60_000L;

    private record Cached(Object value, long expiresAt) {}

    /**
     * Interchangeable hosts of one endpoint. The bare host sometimes closes connections without a reply
     * while the numbered mirrors (used by Eastmoney's own pages) keep working, so a failed request
     * moves on to the next host and the one that answered is tried first next time.
     */
    static final class HostPool {
        private final List<String> hosts;
        private volatile int preferred;

        HostPool(String... hosts) {
            this.hosts = List.of(hosts);
        }

        List<String> order() {
            List<String> out = new ArrayList<>(hosts.size());
            int start = preferred;
            for (int i = 0; i < hosts.size(); i++) {
                out.add(hosts.get((start + i) % hosts.size()));
            }
            return out;
        }

        void succeeded(String host) {
            int i = hosts.indexOf(host);
            if (i >= 0) {
                preferred = i;
            }
        }
    }

    private final HostPool quoteHosts = new HostPool("push2.eastmoney.com", "82.push2.eastmoney.com",
            "1.push2.eastmoney.com", "60.push2.eastmoney.com");
    private final HostPool klineHosts = new HostPool("push2his.eastmoney.com", "7.push2his.eastmoney.com",
            "33.push2his.eastmoney.com", "91.push2his.eastmoney.com");

    /** Real-time quote; prices are {@code null} while suspended. */
    public record Quote(String code, String name, BigDecimal price, BigDecimal open, BigDecimal high, BigDecimal low,
            BigDecimal prevClose, BigDecimal change, BigDecimal pctChg, BigDecimal volumeLots, BigDecimal amount,
            BigDecimal totalCap, BigDecimal floatCap, BigDecimal pe, BigDecimal pb, BigDecimal turnoverPct,
            LocalDateTime time) {

        public boolean suspended() {
            return price == null || price.signum() <= 0;
        }

        /** Latest price, or the previous close while suspended. */
        public BigDecimal lastOrPrevClose() {
            return suspended() ? prevClose : price;
        }
    }

    /** One daily bar; volume in lots (100 shares). */
    public record Bar(LocalDate date, double open, double close, double high, double low, double volume,
            double amount, double pctChg, double turnoverPct) {}

    /** Daily bars of one security. */
    public record Series(String code, String name, List<Bar> bars) {}

    public record SearchHit(String code, String name, String market) {}

    public Quote quote(String code) {
        String c = AShareRules.normalizeCode(code);
        return quoteBySecid(AShareRules.secid(c));
    }

    /** Quote by Eastmoney security id, e.g. {@code 1.000001} for an index. */
    public Quote quoteBySecid(String secid) {
        return cached("q:" + secid, 3_000, () -> {
            HttpUrl url = HttpUrl.parse("https://push2.eastmoney.com/api/qt/stock/get")
                    .newBuilder()
                    .addQueryParameter("secid", secid)
                    .addQueryParameter("ut", UT)
                    .addQueryParameter("fltt", "2")
                    .addQueryParameter("invt", "2")
                    .addQueryParameter("fields", QUOTE_FIELDS)
                    .build();
            JSONObject data = get(url, quoteHosts).getJSONObject("data");
            if (data == null || StringUtils.isBlank(data.getString("f57"))) {
                throw new AShareDataException("没有查到行情：" + secid);
            }
            return new Quote(data.getString("f57"), data.getString("f58"), num(data, "f43"), num(data, "f46"),
                    num(data, "f44"), num(data, "f45"), num(data, "f60"), num(data, "f169"), num(data, "f170"),
                    num(data, "f47"), num(data, "f48"), num(data, "f116"), num(data, "f117"), num(data, "f162"),
                    num(data, "f167"), num(data, "f168"), quoteTime(data));
        });
    }

    /**
     * Daily bars, oldest first.
     *
     * @param adjusted {@code true} forward-adjusted (analysis, backtests); {@code false} actual traded
     *        prices (paper fills)
     */
    public Series dailyBars(String code, boolean adjusted, int limit) {
        String c = AShareRules.normalizeCode(code);
        return barsBySecid(AShareRules.secid(c), adjusted, limit);
    }

    /** Shanghai Composite daily bars: the trading calendar. */
    public Series calendarBars(int limit) {
        return barsBySecid(CALENDAR_SECID, false, limit);
    }

    public Series barsBySecid(String secid, boolean adjusted, int limit) {
        int lmt = Math.max(1, Math.min(limit, 1500));
        // During trading the last bar moves with the price; after the close adjusted history changes at
        // most once a day. Actual (unadjusted) prices settle fills.
        boolean trading = TradingClock.inContinuousSession(TradingClock.now().toLocalTime());
        // Long histories feed backtests, which drop today's unfinished bar: keep them for hours so a
        // search over ~65 stocks does not download four years each time (and get rate-limited).
        long ttl = adjusted && !trading ? 10 * 60_000L : 20_000L;
        if (lmt >= 500) {
            // Long histories are kept by BarStore in MySQL; caching them here would only hold memory.
            return loadBars(secid, adjusted, lmt);
        }
        return cached("k:" + secid + ":" + adjusted + ":" + lmt, ttl, () -> loadBars(secid, adjusted, lmt));
    }

    private Series loadBars(String secid, boolean adjusted, int lmt) {
        {
            if (System.currentTimeMillis() < eastmoneyKlineDownUntil) {
                return tencentBars(secid, adjusted, lmt);
            }
            try {
                return eastmoneyBars(secid, adjusted, lmt);
            } catch (AShareDataException e) {
                // Eastmoney's K-line servers sometimes refuse every connection for a while; use Tencent's
                // public K-line endpoint meanwhile and retry Eastmoney later.
                eastmoneyKlineDownUntil = System.currentTimeMillis() + KLINE_FALLBACK_MILLIS;
                log.warn("Eastmoney K-line unavailable, falling back to Tencent for {} min, secid={}, msg={}",
                        KLINE_FALLBACK_MILLIS / 60_000, secid, e.getMessage());
                try {
                    return tencentBars(secid, adjusted, lmt);
                } catch (AShareDataException fallback) {
                    throw e;
                }
            }
        }
    }

    private Series eastmoneyBars(String secid, boolean adjusted, int lmt) {
        {
            HttpUrl url = HttpUrl.parse("https://push2his.eastmoney.com/api/qt/stock/kline/get")
                    .newBuilder()
                    .addQueryParameter("secid", secid)
                    .addQueryParameter("ut", UT)
                    .addQueryParameter("fields1", "f1,f2,f3")
                    .addQueryParameter("fields2", "f51,f52,f53,f54,f55,f56,f57,f59,f61")
                    .addQueryParameter("klt", "101")
                    .addQueryParameter("fqt", adjusted ? "1" : "0")
                    .addQueryParameter("end", "20500101")
                    .addQueryParameter("lmt", String.valueOf(lmt))
                    .build();
            JSONObject data = get(url, klineHosts).getJSONObject("data");
            if (data == null) {
                throw new AShareDataException("没有查到 K 线：" + secid);
            }
            JSONArray lines = data.getJSONArray("klines");
            List<Bar> bars = new ArrayList<>();
            if (lines != null) {
                for (int i = 0; i < lines.size(); i++) {
                    Bar bar = parseBar(lines.getString(i));
                    if (bar != null) {
                        bars.add(bar);
                    }
                }
            }
            return new Series(data.getString("code"), data.getString("name"), List.copyOf(bars));
        }
    }

    /**
     * Daily bars from Tencent's public K-line endpoint ({@code web.ifzq.gtimg.cn}, also unofficial):
     * rows are {@code date, open, close, high, low, volume}; percent change is derived from the
     * previous close, amount and turnover are not provided (0).
     */
    Series tencentBars(String secid, boolean adjusted, int lmt) {
        String symbol = (secid.startsWith("1.") ? "sh" : "sz") + secid.substring(2);
        // Forward-adjusted history comes in pages of at most 640 bars: page backwards by end date.
        java.util.TreeMap<LocalDate, Bar> merged = new java.util.TreeMap<>();
        String name = null;
        String end = "";
        for (int page = 0; page < 4 && merged.size() < lmt; page++) {
            int want = adjusted ? Math.min(TENCENT_PAGE, lmt - merged.size()) : lmt;
            JSONObject item = tencentKline(symbol, end, want, adjusted);
            JSONArray rows = item.getJSONArray(adjusted ? "qfqday" : "day");
            if (rows == null) {
                rows = item.getJSONArray(adjusted ? "day" : "qfqday");
            }
            JSONObject qt = item.getJSONObject("qt");
            JSONArray q = qt == null ? null : qt.getJSONArray(symbol);
            if (name == null && q != null && q.size() > 1) {
                name = q.getString(1);
            }
            List<Bar> bars = parseTencentRows(rows);
            int before = merged.size();
            bars.forEach(b -> merged.putIfAbsent(b.date(), b));
            if (!adjusted || bars.size() < want || merged.size() == before) {
                break;
            }
            end = merged.firstKey().minusDays(1).toString();
        }
        List<Bar> all = new ArrayList<>(merged.values());
        List<Bar> last = all.subList(Math.max(0, all.size() - lmt), all.size());
        return new Series(secid.substring(2), name, recomputePctChg(last));
    }

    private static final int TENCENT_PAGE = 640;
    /** Newer endpoint first; the older host has been seen returning 501 for every request. */
    private static final List<String> TENCENT_KLINE_URLS = List.of(
            "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/newfqkline/get",
            "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get");

    private JSONObject tencentKline(String symbol, String end, int count, boolean adjusted) {
        AShareDataException last = null;
        for (String base : TENCENT_KLINE_URLS) {
            HttpUrl url = HttpUrl.parse(base)
                    .newBuilder()
                    .addQueryParameter("param", symbol + ",day,," + end + "," + count + "," + (adjusted ? "qfq" : ""))
                    .build();
            try {
                JSONObject data = get(url, null).getJSONObject("data");
                JSONObject item = data == null ? null : data.getJSONObject(symbol);
                if (item != null) {
                    return item;
                }
                last = new AShareDataException("没有查到 K 线：" + symbol);
            } catch (AShareDataException e) {
                last = e;
            }
        }
        throw last;
    }

    /** Percent change from the previous close (the first bar keeps its own value). */
    private static List<Bar> recomputePctChg(List<Bar> bars) {
        List<Bar> out = new ArrayList<>(bars.size());
        for (int i = 0; i < bars.size(); i++) {
            Bar b = bars.get(i);
            double pct = i == 0 || bars.get(i - 1).close() == 0 ? b.pctChg()
                    : Math.round((b.close() / bars.get(i - 1).close() - 1) * 10000) / 100.0;
            out.add(new Bar(b.date(), b.open(), b.close(), b.high(), b.low(), b.volume(), b.amount(), pct,
                    b.turnoverPct()));
        }
        return List.copyOf(out);
    }

    static List<Bar> parseTencentRows(JSONArray rows) {
        List<Bar> bars = new ArrayList<>();
        if (rows == null) {
            return bars;
        }
        double prevClose = Double.NaN;
        for (int i = 0; i < rows.size(); i++) {
            JSONArray r = rows.getJSONArray(i);
            if (r == null || r.size() < 6) {
                continue;
            }
            try {
                double open = r.getDoubleValue(1);
                double close = r.getDoubleValue(2);
                double pct = Double.isNaN(prevClose) || prevClose == 0 ? 0 : (close / prevClose - 1) * 100;
                // Newer rows: [date, open, close, high, low, volume, {}, turnover %, amount (10k yuan), ...]
                double turnover = r.size() > 7 ? parseOrZero(r.getString(7)) : 0;
                double amount = r.size() > 8 ? parseOrZero(r.getString(8)) * 10_000 : 0;
                bars.add(new Bar(LocalDate.parse(r.getString(0)), open, close, r.getDoubleValue(3),
                        r.getDoubleValue(4), r.getDoubleValue(5), amount, Math.round(pct * 100) / 100.0, turnover));
                prevClose = close;
            } catch (RuntimeException e) {
                // Skip malformed rows
            }
        }
        return List.copyOf(bars);
    }

    /** Latest trade of one security for intraday fills; {@code time} is the exchange time of the quote. */
    public record LiveQuote(String code, String name, BigDecimal price, BigDecimal open, BigDecimal high,
            BigDecimal low, BigDecimal prevClose, LocalDateTime time) {

        /** Traded today (not suspended, not yesterday's quote). */
        public boolean tradedOn(LocalDate day) {
            return price != null && price.signum() > 0 && time != null && time.toLocalDate().equals(day);
        }
    }

    /**
     * Latest quotes of several stocks in one request (Eastmoney, then Tencent as fallback); not cached,
     * for the intraday monitor. Codes that cannot be quoted are missing from the result.
     */
    public Map<String, LiveQuote> liveQuotes(java.util.Collection<String> codes) {
        List<String> list = codes.stream().map(AShareRules::normalizeCode).distinct().toList();
        if (list.isEmpty()) {
            return Map.of();
        }
        try {
            return eastmoneyLive(list);
        } catch (AShareDataException e) {
            log.warn("Eastmoney batch quote unavailable, falling back to Tencent, count={}, msg={}", list.size(),
                    e.getMessage());
            return tencentLive(list);
        }
    }

    private Map<String, LiveQuote> eastmoneyLive(List<String> codes) {
        HttpUrl url = HttpUrl.parse("https://push2.eastmoney.com/api/qt/ulist.np/get")
                .newBuilder()
                .addQueryParameter("fltt", "2")
                .addQueryParameter("invt", "2")
                .addQueryParameter("ut", UT)
                .addQueryParameter("secids", String.join(",", codes.stream().map(AShareRules::secid).toList()))
                .addQueryParameter("fields", "f2,f12,f14,f15,f16,f17,f18,f124")
                .build();
        JSONObject data = get(url, quoteHosts).getJSONObject("data");
        JSONArray diff = data == null ? null : data.getJSONArray("diff");
        if (diff == null) {
            throw new AShareDataException("批量行情返回为空");
        }
        Map<String, LiveQuote> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < diff.size(); i++) {
            JSONObject d = diff.getJSONObject(i);
            Long epoch = d.getLong("f124");
            LocalDateTime time = epoch == null || epoch <= 0 ? null
                    : LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), TradingClock.ZONE);
            out.put(d.getString("f12"), new LiveQuote(d.getString("f12"), d.getString("f14"), num(d, "f2"),
                    num(d, "f17"), num(d, "f15"), num(d, "f16"), num(d, "f18"), time));
        }
        return out;
    }

    /** Tencent {@code qt.gtimg.cn}: GBK text, fields separated by {@code ~}. */
    private Map<String, LiveQuote> tencentLive(List<String> codes) {
        String symbols = String.join(",",
                codes.stream().map(c -> (AShareRules.secid(c).startsWith("1.") ? "sh" : "sz") + c).toList());
        Request request = new Request.Builder().url("https://qt.gtimg.cn/q=" + symbols).get().build();
        try (Response response = http.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new AShareDataException("腾讯行情返回 " + response.code());
            }
            return parseTencentQuotes(new String(body.bytes(), java.nio.charset.Charset.forName("GBK")));
        } catch (IOException e) {
            throw new AShareDataException("腾讯行情连接失败：" + e.getClass().getSimpleName());
        }
    }

    static Map<String, LiveQuote> parseTencentQuotes(String text) {
        Map<String, LiveQuote> out = new java.util.LinkedHashMap<>();
        for (String line : StringUtils.defaultString(text).split(";")) {
            int q = line.indexOf('"');
            int end = line.lastIndexOf('"');
            if (q < 0 || end <= q) {
                continue;
            }
            String[] f = line.substring(q + 1, end).split("~", -1);
            if (f.length < 35) {
                continue;
            }
            LocalDateTime time = null;
            try {
                time = LocalDateTime.parse(f[30], java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            } catch (RuntimeException e) {
                // Missing time: treated as not traded today
            }
            out.put(f[2], new LiveQuote(f[2], f[1], decimal(f[3]), decimal(f[5]), decimal(f[33]), decimal(f[34]),
                    decimal(f[4]), time));
        }
        return out;
    }

    private static BigDecimal decimal(String s) {
        try {
            BigDecimal v = new BigDecimal(s.trim());
            return v.signum() > 0 ? v : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A listed Shanghai / Shenzhen A share from the market-wide list. */
    public record ListedStock(String code, String name, double marketCap, double price, double amountToday) {}

    /**
     * Shanghai / Shenzhen A shares with a total market cap of at least {@code minCap} yuan, largest
     * first, from Sina's market center list (public, unofficial; Eastmoney's list endpoint has been
     * unreachable). Beijing exchange stocks are skipped.
     */
    public List<ListedStock> largeCaps(double minCap) {
        List<ListedStock> out = new ArrayList<>();
        for (int page = 1; page <= 40; page++) {
            HttpUrl url = HttpUrl.parse("https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHQNodeData")
                    .newBuilder()
                    .addQueryParameter("page", String.valueOf(page))
                    .addQueryParameter("num", "100")
                    .addQueryParameter("sort", "mktcap")
                    .addQueryParameter("asc", "0")
                    .addQueryParameter("node", "hs_a")
                    .build();
            JSONArray rows = getArray(url, "https://finance.sina.com.cn");
            if (rows == null || rows.isEmpty()) {
                break;
            }
            boolean below = false;
            for (int i = 0; i < rows.size(); i++) {
                JSONObject r = rows.getJSONObject(i);
                double cap = r.getDoubleValue("mktcap") * 10_000;
                if (cap < minCap) {
                    below = true;
                    break;
                }
                String code = r.getString("code");
                try {
                    AShareRules.board(code);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                out.add(new ListedStock(code, r.getString("name"), cap, r.getDoubleValue("trade"), r.getDoubleValue("amount")));
            }
            if (below) {
                break;
            }
        }
        if (out.isEmpty()) {
            throw new AShareDataException("股票列表取不到");
        }
        return out;
    }

    /** GET of a JSON array (Sina returns a bare array). */
    private JSONArray getArray(HttpUrl url, String referer) {
        pace(provider(url.host()));
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .header("Referer", referer)
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new AShareDataException("股票列表接口返回 " + response.code());
            }
            String text = body.string().trim();
            return text.startsWith("[") ? JSON.parseArray(text) : null;
        } catch (IOException e) {
            throw new AShareDataException("股票列表接口连接失败：" + e.getClass().getSimpleName());
        } catch (com.alibaba.fastjson2.JSONException e) {
            throw new AShareDataException("股票列表接口返回格式变化");
        }
    }

    /** Finds A-share stocks by code, name or pinyin initials. */
    public List<SearchHit> search(String keyword) {
        String kw = StringUtils.trimToEmpty(keyword);
        if (kw.isEmpty()) {
            return List.of();
        }
        return cached("s:" + kw, 3_600_000L, () -> {
            HttpUrl url = HttpUrl.parse("https://searchapi.eastmoney.com/api/suggest/get")
                    .newBuilder()
                    .addQueryParameter("input", kw)
                    .addQueryParameter("type", "14")
                    .addQueryParameter("token", SEARCH_TOKEN)
                    .addQueryParameter("count", "10")
                    .build();
            JSONObject table = get(url, null).getJSONObject("QuotationCodeTable");
            JSONArray rows = table == null ? null : table.getJSONArray("Data");
            List<SearchHit> hits = new ArrayList<>();
            if (rows != null) {
                for (int i = 0; i < rows.size(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    String code = row.getString("Code");
                    try {
                        AShareRules.board(code);
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    String mkt = row.getString("MktNum");
                    // Skip indices and funds that share a 6-digit code with a stock.
                    if (!("1".equals(mkt) || "0".equals(mkt)) || !AShareRules.secid(code).startsWith(mkt + ".")) {
                        continue;
                    }
                    String type = StringUtils.defaultString(row.getString("SecurityTypeName"));
                    if (type.contains("指") || type.contains("基") || type.contains("债")) {
                        continue;
                    }
                    hits.add(new SearchHit(code, row.getString("Name"), "1".equals(mkt) ? "SH" : "SZ"));
                }
            }
            return List.copyOf(hits);
        });
    }

    static Bar parseBar(String line) {
        String[] p = StringUtils.defaultString(line).split(",");
        if (p.length < 9) {
            return null;
        }
        try {
            return new Bar(LocalDate.parse(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                    Double.parseDouble(p[3]), Double.parseDouble(p[4]), Double.parseDouble(p[5]),
                    Double.parseDouble(p[6]), parseOrZero(p[7]), parseOrZero(p[8]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static double parseOrZero(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Time of the latest trade (f86, epoch seconds) in Asia/Shanghai. */
    private static LocalDateTime quoteTime(JSONObject data) {
        Long epoch = data.getLong("f86");
        if (epoch == null || epoch <= 0) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneId.of("Asia/Shanghai"));
    }

    /** Numeric field; Eastmoney sends {@code "-"} for missing values. */
    private static BigDecimal num(JSONObject data, String key) {
        Object v = data.get(key);
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Minimum gap between two requests to the same provider, to stay under its rate limits. */
    static final long MIN_GAP_MS = 200;
    private static final long RETRY_BACKOFF_MS = 800;
    private final Map<String, Long> lastRequestAt = new ConcurrentHashMap<>();

    /** Waits until {@link #MIN_GAP_MS} has passed since the provider's previous request. */
    private void pace(String provider) {
        long wait;
        synchronized (lastRequestAt) {
            long now = System.currentTimeMillis();
            long next = lastRequestAt.getOrDefault(provider, 0L) + MIN_GAP_MS;
            wait = Math.max(0, next - now);
            lastRequestAt.put(provider, now + wait);
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AShareDataException("请求被中断");
            }
        }
    }

    private static String provider(String host) {
        return host.endsWith("gtimg.cn") || host.endsWith("qq.com") ? "tencent"
                : host.contains("sina") ? "sina" : host.contains("push2his") ? "eastmoney-kline" : "eastmoney";
    }

    /**
     * GET with the first host of {@code pool} that answers ({@code null} pool: the URL's host); when
     * every host fails, one more round after a short backoff.
     */
    private JSONObject get(HttpUrl url, HostPool pool) {
        List<String> hosts = pool == null ? List.of(url.host()) : pool.order();
        AShareDataException last = null;
        for (int round = 0; round < 2; round++) {
            if (round > 0) {
                try {
                    Thread.sleep(RETRY_BACKOFF_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            for (String host : hosts) {
                try {
                    pace(provider(host));
                    JSONObject json = getOnce(url.newBuilder().host(host).build());
                    if (pool != null) {
                        pool.succeeded(host);
                    }
                    return json;
                } catch (AShareDataException e) {
                    last = e;
                }
            }
        }
        throw last == null ? new AShareDataException("行情接口不可用") : last;
    }

    private JSONObject getOnce(HttpUrl url) {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .header("Referer", "https://quote.eastmoney.com/")
                .get()
                .build();
        long start = System.currentTimeMillis();
        try (Response response = http.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                log.warn("Eastmoney request failed, host={}, path={}, status={}, costMs={}", url.host(),
                        url.encodedPath(), response.code(), System.currentTimeMillis() - start);
                throw new AShareDataException("行情接口返回 " + response.code());
            }
            JSONObject json = JSON.parseObject(body.string());
            if (json == null) {
                throw new AShareDataException("行情接口返回为空");
            }
            return json;
        } catch (IOException e) {
            log.warn("Eastmoney request error, host={}, path={}, errorType={}, costMs={}", url.host(),
                    url.encodedPath(), e.getClass().getSimpleName(), System.currentTimeMillis() - start);
            throw new AShareDataException("行情接口连接失败：" + e.getClass().getSimpleName());
        } catch (com.alibaba.fastjson2.JSONException e) {
            log.warn("Eastmoney response not JSON, host={}, path={}", url.host(), url.encodedPath());
            throw new AShareDataException("行情接口返回格式变化");
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, long ttlMillis, Supplier<T> loader) {
        long now = System.currentTimeMillis();
        Cached hit = cache.get(key);
        if (hit != null && hit.expiresAt() > now) {
            return (T) hit.value();
        }
        T value = loader.get();
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                cache.clear();
            }
        }
        cache.put(key, new Cached(value, now + ttlMillis));
        return value;
    }
}
