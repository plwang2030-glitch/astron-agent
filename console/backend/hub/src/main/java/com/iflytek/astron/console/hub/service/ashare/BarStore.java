package com.iflytek.astron.console.hub.service.ashare;

import com.iflytek.astron.console.hub.mapper.ashare.DailyBarMapper;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Bar;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Series;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Long daily histories for backtests, kept in MySQL and topped up with the latest bars. Years of
 * history are downloaded once per stock; afterwards each update fetches about {@link #TAIL_BARS}
 * bars. Forward-adjusted prices change after a dividend or split: when the fetched bars disagree
 * with the stored ones on overlapping days, the stock's whole history is downloaded again. When the
 * market data endpoints are unavailable, the stored history is used as it is.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BarStore {

    static final int TAIL_BARS = 40;
    /** Relative price difference on an overlapping day that means the adjustment changed. */
    static final double ADJUSTMENT_TOLERANCE = 0.0005;
    /** How long a fresh top-up is trusted before asking the endpoint again. */
    private static final long REFRESH_MILLIS = 30 * 60_000L;
    private static final LocalTime SETTLED = LocalTime.of(15, 5);

    private final DailyBarMapper barMapper;
    private final EastmoneyClient market;
    private final Map<String, Long> refreshedAt = new ConcurrentHashMap<>();

    /**
     * Up to {@code limit} settled daily bars of a stock (today's bar only after the close), oldest
     * first.
     */
    public Series dailyBars(String code, boolean adjusted, int limit) {
        return bars(AShareRules.secid(AShareRules.normalizeCode(code)), adjusted, limit);
    }

    /** Up to {@code limit} settled daily bars of the Shanghai Composite (actual prices). */
    public Series indexBars(int limit) {
        return bars(EastmoneyClient.CALENDAR_SECID, false, limit);
    }

    Series bars(String secid, boolean adjusted, int limit) {
        int adj = adjusted ? 1 : 0;
        try {
            if (needsRefresh(secid, adjusted, limit)) {
                refresh(secid, adjusted, limit);
            }
        } catch (AShareDataException e) {
            log.warn("Bar store refresh failed, using stored bars, secid={}, adjusted={}, msg={}", secid, adjusted,
                    e.getMessage());
        }
        Series stored = read(secid, adj, limit);
        if (stored.bars().isEmpty()) {
            throw new AShareDataException("没有 " + secid + " 的历史行情（接口暂时取不到，本地也没有存档）");
        }
        return stored;
    }

    /**
     * The index is refreshed at most every {@link #REFRESH_MILLIS}; a stock only when its stored bars
     * end before the index's latest settled day, and then once per such day (a suspended stock has no
     * new bar to fetch).
     */
    private boolean needsRefresh(String secid, boolean adjusted, int limit) {
        int adj = adjusted ? 1 : 0;
        String key = secid + ":" + adj;
        long now = System.currentTimeMillis();
        if (EastmoneyClient.CALENDAR_SECID.equals(secid) && !adjusted) {
            Long last = refreshedAt.get(key);
            if (last != null && now - last < REFRESH_MILLIS) {
                return false;
            }
            refreshedAt.put(key, now);
            return true;
        }
        LocalDate target = latestSettledDay(limit);
        LocalDate stored = barMapper.lastDate(secid, adj);
        if (stored != null && target != null && !stored.isBefore(target)) {
            return false;
        }
        String checked = target == null ? "none" : target.toString();
        if (checked.equals(checkedFor.get(key))) {
            return false;
        }
        checkedFor.put(key, checked);
        return true;
    }

    /** The index's latest stored trading day after a refresh (the market's latest settled day). */
    public LocalDate latestSettledDay(int limit) {
        try {
            if (needsRefresh(EastmoneyClient.CALENDAR_SECID, false, limit)) {
                refresh(EastmoneyClient.CALENDAR_SECID, false, limit);
            }
        } catch (AShareDataException e) {
            log.warn("Index refresh failed: {}", e.getMessage());
        }
        return barMapper.lastDate(EastmoneyClient.CALENDAR_SECID, 0);
    }

    /** Whether the stock has stored bars up to the latest settled day (no network). */
    public boolean upToDate(String code, boolean adjusted, LocalDate latest) {
        LocalDate stored = barMapper.lastDate(AShareRules.secid(AShareRules.normalizeCode(code)), adjusted ? 1 : 0);
        return stored != null && latest != null && !stored.isBefore(latest);
    }

    /** Stored bars only (no network), oldest first; empty when none. */
    public Series stored(String code, boolean adjusted, int limit) {
        return read(AShareRules.secid(AShareRules.normalizeCode(code)), adjusted ? 1 : 0, limit);
    }

    /** Stored Shanghai Composite bars only (no network), oldest first; empty when none. */
    public Series storedIndex(int limit) {
        return read(EastmoneyClient.CALENDAR_SECID, 0, limit);
    }

    private final Map<String, String> checkedFor = new ConcurrentHashMap<>();

    private void refresh(String secid, boolean adjusted, int limit) {
        int adj = adjusted ? 1 : 0;
        LocalDate lastDate = barMapper.lastDate(secid, adj);
        List<Map<String, Object>> storedTail = barMapper.latest(secid, adj, 5);
        int have = storedTail.isEmpty() ? 0 : barMapper.latest(secid, adj, limit).size();
        boolean full = lastDate == null || have < Math.min(limit, 250);
        if (!full) {
            Series tail = market.barsBySecid(secid, adjusted, TAIL_BARS);
            List<Bar> settled = settled(tail.bars());
            if (!consistent(settled, storedTail)) {
                log.info("Bar store adjustment changed, reloading history, secid={}, adjusted={}", secid, adjusted);
                full = true;
            } else {
                List<Bar> fresh = settled.stream().filter(b -> b.date().isAfter(lastDate)).toList();
                if (!fresh.isEmpty()) {
                    barMapper.upsert(secid, adj, tail.name(), fresh);
                }
                return;
            }
        }
        Series all = market.barsBySecid(secid, adjusted, limit);
        List<Bar> settled = settled(all.bars());
        if (settled.isEmpty()) {
            return;
        }
        barMapper.deleteAll(secid, adj);
        for (int i = 0; i < settled.size(); i += 500) {
            barMapper.upsert(secid, adj, all.name(), settled.subList(i, Math.min(settled.size(), i + 500)));
        }
        log.info("Bar store loaded, secid={}, adjusted={}, bars={}", secid, adjusted, settled.size());
    }

    /** Drops today's bar until the market has closed (it is still moving). */
    static List<Bar> settled(List<Bar> bars) {
        LocalDateTime now = TradingClock.now();
        if (!bars.isEmpty() && bars.get(bars.size() - 1).date().equals(now.toLocalDate())
                && now.toLocalTime().isBefore(SETTLED)) {
            return bars.subList(0, bars.size() - 1);
        }
        return bars;
    }

    /** The stored bars agree with the fetched ones on the days both have. */
    static boolean consistent(List<Bar> fetched, List<Map<String, Object>> stored) {
        int compared = 0;
        for (Map<String, Object> row : stored) {
            LocalDate d = toDate(row.get("tradeDate"));
            for (Bar b : fetched) {
                if (b.date().equals(d)) {
                    double c = ((Number) row.get("close")).doubleValue();
                    if (Math.abs(b.close() - c) > Math.max(0.001, Math.abs(c) * ADJUSTMENT_TOLERANCE)) {
                        return false;
                    }
                    compared++;
                }
            }
        }
        // No overlap at all (a gap longer than the tail): reload to be safe.
        return compared > 0;
    }

    private Series read(String secid, int adj, int limit) {
        List<Map<String, Object>> rows = barMapper.latest(secid, adj, limit);
        List<Bar> bars = new ArrayList<>(rows.size());
        String name = null;
        for (Map<String, Object> r : rows) {
            name = name == null ? (String) r.get("name") : name;
            bars.add(new Bar(toDate(r.get("tradeDate")), num(r, "open"), num(r, "close"), num(r, "high"), num(r, "low"),
                    num(r, "volume"), num(r, "amount"), num(r, "pctChg"), num(r, "turnoverPct")));
        }
        Collections.reverse(bars);
        return new Series(secid.substring(secid.indexOf('.') + 1), name, List.copyOf(bars));
    }

    private static double num(Map<String, Object> r, String k) {
        Object v = r.get(k);
        return v == null ? 0 : ((Number) v).doubleValue();
    }

    private static LocalDate toDate(Object v) {
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof Date d) {
            return d.toLocalDate();
        }
        if (v instanceof java.util.Date d) {
            return new Date(d.getTime()).toLocalDate();
        }
        return LocalDate.parse(String.valueOf(v));
    }
}
