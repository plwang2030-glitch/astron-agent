package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Bar;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Quote;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Series;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Sina's public market data, a third provider behind Eastmoney and Tencent.
 * <p>
 * What it can and cannot do is deliberately narrow. Its quote carries price, open, high, low and
 * previous close but no valuation, so those fields stay {@code null} rather than zero. Its daily
 * bars are actual traded prices with no adjustment and no turnover, so they can serve paper fills
 * and the trading calendar but must never stand in for the forward-adjusted series that signals and
 * backtests use: mixing the two would silently change what every indicator means.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SinaMarket {

    private static final Charset GBK = Charset.forName("GBK");
    private static final String REFERER = "https://finance.sina.com.cn";

    private final EastmoneyClient http;

    /**
     * Real-time quote. Sina answers {@code var hq_str_sh600036="name,open,prevClose,price,high,low,
     * bid,ask,volume,amount,...,date,time"}; before the open the traded prices are 0, which is read as
     * "not trading" rather than as a price of zero.
     */
    public Quote quote(String secid) {
        String code = secid.substring(secid.indexOf('.') + 1);
        String text = http.getText(HttpUrl.parse("https://hq.sinajs.cn/list=" + AShareRules.symbolOfSecid(secid)),
                REFERER, GBK);
        int from = text.indexOf('"');
        int to = text.lastIndexOf('"');
        if (from < 0 || to <= from) {
            throw new AShareDataException("新浪行情返回格式变化：" + secid);
        }
        String[] f = text.substring(from + 1, to).split(",", -1);
        if (f.length < 32) {
            throw new AShareDataException("新浪行情字段不足：" + secid);
        }
        BigDecimal price = positive(f[3]);
        BigDecimal prevClose = positive(f[2]);
        if (price == null && prevClose == null) {
            throw new AShareDataException("新浪没有查到行情：" + secid);
        }
        BigDecimal change = price == null || prevClose == null ? null : price.subtract(prevClose);
        BigDecimal pct = change == null || prevClose.signum() == 0 ? null
                : change.multiply(BigDecimal.valueOf(100)).divide(prevClose, 2, RoundingMode.HALF_UP);
        // Volume is in shares and amount in yuan; lots are what the rest of the code expects.
        BigDecimal volumeLots = decimal(f[8]) == null ? null
                : decimal(f[8]).divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
        LocalDateTime time = parseTime(f[30], f[31]);
        return new Quote(code, StringUtils.trimToEmpty(f[0]), price, positive(f[1]), positive(f[4]), positive(f[5]),
                prevClose, change, pct, volumeLots, decimal(f[9]), null, null, null, null, null, time);
    }

    /**
     * Daily bars at actual traded prices, oldest first. Turnover and the change percentage are not
     * published here, so they are left at zero and recomputed from the closes where needed.
     *
     * @throws IllegalArgumentException if asked for adjusted prices, which Sina does not provide
     */
    public Series bars(String secid, boolean adjusted, int limit) {
        if (adjusted) {
            throw new IllegalArgumentException("新浪不提供前复权数据");
        }
        String code = secid.substring(secid.indexOf('.') + 1);
        HttpUrl url = HttpUrl
                .parse("https://quotes.sina.cn/cn/api/json_v2.php/CN_MarketDataService.getKLineData")
                .newBuilder()
                .addQueryParameter("symbol", AShareRules.symbolOfSecid(secid))
                .addQueryParameter("scale", "240")
                .addQueryParameter("ma", "no")
                .addQueryParameter("datalen", String.valueOf(Math.max(1, Math.min(1500, limit))))
                .build();
        String text = http.getText(url, REFERER, null);
        int from = text.indexOf('[');
        int to = text.lastIndexOf(']');
        if (from < 0 || to <= from) {
            throw new AShareDataException("新浪 K 线返回格式变化：" + secid);
        }
        JSONArray rows;
        try {
            rows = JSON.parseArray(text.substring(from, to + 1));
        } catch (RuntimeException e) {
            throw new AShareDataException("新浪 K 线返回不是合法 JSON：" + secid);
        }
        if (rows == null || rows.isEmpty()) {
            throw new AShareDataException("新浪没有 " + secid + " 的 K 线");
        }
        List<Bar> bars = new ArrayList<>(rows.size());
        double prevClose = 0;
        for (int i = 0; i < rows.size(); i++) {
            JSONObject r = rows.getJSONObject(i);
            double close = r.getDoubleValue("close");
            double open = r.getDoubleValue("open");
            if (close <= 0 || open <= 0) {
                continue;
            }
            double pct = prevClose > 0 ? (close / prevClose - 1) * 100 : 0;
            bars.add(new Bar(LocalDate.parse(r.getString("day").substring(0, 10)), open, close,
                    r.getDoubleValue("high"), r.getDoubleValue("low"), r.getDoubleValue("volume") / 100, 0,
                    Math.round(pct * 100) / 100.0, 0));
            prevClose = close;
        }
        if (bars.isEmpty()) {
            throw new AShareDataException("新浪 K 线为空：" + secid);
        }
        log.info("Bars served by Sina (unadjusted), secid={}, bars={}", secid, bars.size());
        return new Series(code, null, List.copyOf(bars));
    }

    private static LocalDateTime parseTime(String date, String time) {
        try {
            return LocalDateTime.parse(StringUtils.trim(date) + "T" + StringUtils.trim(time));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BigDecimal decimal(String s) {
        try {
            return new BigDecimal(StringUtils.trimToEmpty(s));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The value when it is a real price, {@code null} when Sina reports 0 (not trading yet). */
    private static BigDecimal positive(String s) {
        BigDecimal v = decimal(s);
        return v != null && v.signum() > 0 ? v : null;
    }
}
