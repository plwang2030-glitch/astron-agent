package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iflytek.astron.console.hub.entity.ashare.UniverseSnapshot;
import com.iflytek.astron.console.hub.mapper.ashare.UniverseSnapshotMapper;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Bar;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Series;
import com.iflytek.astron.console.hub.service.ashare.UniverseScreen.Candidate;
import com.iflytek.astron.console.hub.service.ashare.UniverseScreen.History;
import com.iflytek.astron.console.hub.service.ashare.UniverseScreen.Ranked;
import com.iflytek.astron.console.hub.service.ashare.UniverseScreen.Rules;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The rule-based stock universe ({@link UniverseScreen}): keeps the daily bars of every candidate
 * (today's A shares with a market cap of at least {@link #CANDIDATE_MIN_CAP}, so that stocks that
 * were large in the past and have since fallen by up to half are included), saves a snapshot of the
 * screen each month, and gives backtests the universe each past month had.
 * <p>
 * Survivorship bias is reduced, not removed: delisted stocks are not in today's list, and past ST
 * status and market cap are approximated.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UniverseService {

    /** Candidates: today's market cap ≥ 150亿 (half of the 300亿 rule). */
    static final double CANDIDATE_MIN_CAP = 1.5e10;
    static final int HISTORY_BARS = StrategyService.HISTORY_DAYS + Backtester.WARMUP_BARS + 20;

    private final EastmoneyClient market;
    private final BarStore barStore;
    private final UniverseSnapshotMapper snapshotMapper;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ashare-universe");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile String progress = "idle";
    private volatile List<Candidate> candidates = List.of();
    /** Point-in-time screens, recomputed when the latest stored trading day changes. */
    private volatile Map<YearMonth, Ranked> monthly = Map.of();
    private volatile LocalDate monthlyFor;

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    public Rules rules() {
        return Rules.defaults();
    }

    // ------------------------------------------------------------------ maintenance

    /**
     * Refreshes the candidate list, tops up every candidate's bars (the first run downloads about four
     * years per stock and takes a while), recomputes the point-in-time screens and saves this month's
     * snapshot if missing. Runs in the background; returns {@code false} when already running.
     */
    public boolean maintainAsync(String trigger) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        worker.execute(() -> {
            try {
                maintain(trigger);
            } catch (RuntimeException e) {
                log.warn("Universe maintenance failed, trigger={}, errorType={}, msg={}", trigger,
                        e.getClass().getSimpleName(), e.getMessage(), e);
                progress = "失败：" + e.getMessage();
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    /** Cool-down between manual refreshes (each one re-checks about a thousand stocks). */
    private static final long MANUAL_COOLDOWN_MILLIS = 30 * 60_000L;
    private volatile long lastManual;

    /** Starts a manual refresh; returns why it was refused, or {@code null} when started. */
    public String manualRefresh() {
        long now = System.currentTimeMillis();
        if (isRunning()) {
            return "正在更新中：" + progress;
        }
        if (now - lastManual < MANUAL_COOLDOWN_MILLIS) {
            return "30 分钟内只能手动更新一次";
        }
        if (!maintainAsync("manual")) {
            return "正在更新中：" + progress;
        }
        lastManual = now;
        return null;
    }

    public boolean isRunning() {
        return running.get();
    }

    public String progress() {
        return progress;
    }

    void maintain(String trigger) {
        long start = System.currentTimeMillis();
        progress = "获取股票列表";
        List<EastmoneyClient.ListedStock> listed = market.largeCaps(CANDIDATE_MIN_CAP);
        List<Candidate> list = listed.stream().map(s -> new Candidate(s.code(), s.name(), s.marketCap())).toList();
        candidates = list;
        LocalDate latest = barStore.latestSettledDay(HISTORY_BARS);
        int done = 0;
        int failed = 0;
        for (Candidate c : list) {
            done++;
            if (done % 50 == 0) {
                progress = "更新 K 线 " + done + "/" + list.size();
                log.info("Universe bars progress, done={}, total={}, failed={}", done, list.size(), failed);
            }
            if (barStore.upToDate(c.code(), true, latest)) {
                continue;
            }
            try {
                barStore.dailyBars(c.code(), true, HISTORY_BARS);
            } catch (RuntimeException e) {
                failed++;
            }
        }
        progress = "计算筛选结果";
        monthlyFor = null;
        Map<YearMonth, Ranked> screens = monthlyScreens();
        Ranked current = screens.isEmpty() ? null : new ArrayList<>(screens.values()).get(screens.size() - 1);
        saveSnapshot(latest, list.size(), "manual".equals(trigger));
        progress = "完成（" + list.size() + " 只候选，" + failed + " 只失败，耗时 " + (System.currentTimeMillis() - start) / 1000
                + " 秒）";
        log.info("Universe maintenance done, trigger={}, candidates={}, failed={}, months={}, currentPool={}, costMs={}",
                trigger, list.size(), failed, screens.size(), current == null ? 0 : current.pool().size(),
                System.currentTimeMillis() - start);
    }

    /**
     * This month's screen, using data up to the latest settled day. The live universe changes once a
     * month: an existing snapshot of this month is replaced only on a manual refresh.
     */
    private void saveSnapshot(LocalDate latest, int candidateCount, boolean replace) {
        if (latest == null) {
            log.warn("Universe snapshot skipped: no index calendar");
            return;
        }
        YearMonth month = YearMonth.from(TradingClock.now().toLocalDate());
        UniverseSnapshot existing = snapshotMapper.selectOne(Wrappers.lambdaQuery(UniverseSnapshot.class)
                .eq(UniverseSnapshot::getMonth, month.toString()));
        if (existing != null && !replace) {
            return;
        }
        Series index = barStore.storedIndex(HISTORY_BARS);
        LocalDate windowStart = index.bars().isEmpty() ? null : index.bars().get(0).date();
        List<Candidate> list = candidateList();
        Map<String, History> histories = loadHistories(list, windowStart);
        Ranked r = UniverseScreen.rank(list, histories, month, latest, rules());
        if (r.pool().isEmpty()) {
            log.warn("Universe snapshot skipped: no stock passed, latest={}", latest);
            return;
        }
        UniverseSnapshot snap = existing == null ? new UniverseSnapshot() : existing;
        snap.setMonth(month.toString());
        snap.setAsOf(latest);
        snap.setRules(rules().toJson().toJSONString());
        snap.setTradeCodes(JSON.toJSONString(r.trade(rules().tradeSize())));
        snap.setPoolCodes(JSON.toJSONString(r.poolCodes()));
        JSONArray details = new JSONArray();
        r.pool().forEach(m -> details.add(m.toJson()));
        snap.setDetails(details.toJSONString());
        snap.setCandidates(candidateCount);
        snap.setUpdateTime(null);
        if (existing == null) {
            snapshotMapper.insert(snap);
        } else {
            snapshotMapper.updateById(snap);
        }
        log.info("Universe snapshot saved, month={}, asOf={}, passed={}, trade={}", month, latest, r.pool().size(),
                r.trade(rules().tradeSize()));
    }

    private List<Candidate> candidateList() {
        List<Candidate> c = candidates;
        if (c.isEmpty()) {
            try {
                c = market.largeCaps(CANDIDATE_MIN_CAP)
                        .stream()
                        .map(s -> new Candidate(s.code(), s.name(), s.marketCap()))
                        .toList();
                candidates = c;
            } catch (AShareDataException e) {
                log.warn("Candidate list unavailable: {}", e.getMessage());
            }
        }
        return c;
    }

    /**
     * Stored adjusted bars of the candidates. A stock whose first stored bar is within a week of the
     * index's first stored bar ({@code windowStart}) was listed before the download window.
     */
    private Map<String, History> loadHistories(List<Candidate> list, LocalDate windowStart) {
        Map<String, History> out = new HashMap<>();
        for (Candidate c : list) {
            Series s = barStore.stored(c.code(), true, HISTORY_BARS);
            if (s.bars().isEmpty()) {
                continue;
            }
            int n = s.bars().size();
            LocalDate[] d = new LocalDate[n];
            double[] close = new double[n];
            double[] amount = new double[n];
            for (int i = 0; i < n; i++) {
                Bar b = s.bars().get(i);
                d[i] = b.date();
                close[i] = b.close();
                amount[i] = b.amount();
            }
            boolean listedBefore = windowStart != null && !d[0].isAfter(windowStart.plusDays(7));
            out.put(c.code(), new History(c.code(), d, close, amount, listedBefore));
        }
        return out;
    }

    // ------------------------------------------------------------------ queries

    /**
     * Point-in-time screens for every month of the stored index calendar (no network besides the
     * candidate list); cached until a new trading day is stored.
     */
    public Map<YearMonth, Ranked> monthlyScreens() {
        Series index = barStore.storedIndex(HISTORY_BARS);
        if (index.bars().isEmpty()) {
            return Map.of();
        }
        LocalDate latest = index.bars().get(index.bars().size() - 1).date();
        if (latest.equals(monthlyFor) && !monthly.isEmpty()) {
            return monthly;
        }
        synchronized (this) {
            if (latest.equals(monthlyFor) && !monthly.isEmpty()) {
                return monthly;
            }
            List<Candidate> list = candidateList();
            if (list.isEmpty()) {
                return monthly;
            }
            long start = System.currentTimeMillis();
            Map<String, History> histories = loadHistories(list, index.bars().get(0).date());
            if (histories.size() < list.size() / 2) {
                // Bars not downloaded yet (first maintenance still running): screens would be too thin.
                log.info("Universe screens skipped, candidates={}, withHistory={}", list.size(), histories.size());
                return Map.of();
            }
            List<LocalDate> calendar = index.bars().stream().map(Bar::date).toList();
            Map<YearMonth, Ranked> screens = UniverseScreen.history(list, histories, calendar, rules());
            monthly = screens;
            monthlyFor = latest;
            log.info("Universe screens computed, months={}, candidates={}, withHistory={}, costMs={}", screens.size(),
                    list.size(), histories.size(), System.currentTimeMillis() - start);
            return screens;
        }
    }

    /** Ready for backtests: enough candidates have stored history. */
    public boolean ready() {
        return snapshotMapper.selectCount(Wrappers.lambdaQuery(UniverseSnapshot.class)) > 0;
    }

    /** Per-month trading universe (top {@code size}) and validation pool for backtests. */
    public record MonthSets(Map<YearMonth, Set<String>> trade, Map<YearMonth, Set<String>> pool, Set<String> allCodes) {}

    public MonthSets monthSets(int tradeSize) {
        Map<YearMonth, Set<String>> trade = new LinkedHashMap<>();
        Map<YearMonth, Set<String>> pool = new LinkedHashMap<>();
        Set<String> all = new LinkedHashSet<>();
        for (var e : monthlyScreens().entrySet()) {
            Set<String> t = new LinkedHashSet<>(e.getValue().trade(tradeSize));
            Set<String> p = new LinkedHashSet<>(e.getValue().poolCodes());
            trade.put(e.getKey(), t);
            pool.put(e.getKey(), p);
            all.addAll(p);
            all.addAll(t);
        }
        return new MonthSets(trade, pool, all);
    }

    /** The latest snapshot (the live universe), or {@code null} before the first screen. */
    public UniverseSnapshot latest() {
        return snapshotMapper.selectOne(Wrappers.lambdaQuery(UniverseSnapshot.class)
                .orderByDesc(UniverseSnapshot::getMonth)
                .last("LIMIT 1"));
    }

    /** Live trading universe: the top {@code size} of the latest snapshot. */
    public List<String> liveTrade(int size) {
        UniverseSnapshot s = latest();
        if (s == null) {
            return List.of();
        }
        List<String> pool = JSON.parseArray(s.getPoolCodes(), String.class);
        return pool.subList(0, Math.min(size, pool.size()));
    }

    /** Snapshots, newest first, with what entered and left the trading universe each month. */
    public JSONObject overview(int tradeSize, int months) {
        List<UniverseSnapshot> snaps = snapshotMapper.selectList(Wrappers.lambdaQuery(UniverseSnapshot.class)
                .orderByDesc(UniverseSnapshot::getMonth)
                .last("LIMIT " + Math.max(1, Math.min(months, 24))));
        JSONArray rows = new JSONArray();
        for (int i = 0; i < snaps.size(); i++) {
            UniverseSnapshot s = snaps.get(i);
            List<String> pool = JSON.parseArray(s.getPoolCodes(), String.class);
            List<String> trade = pool.subList(0, Math.min(tradeSize, pool.size()));
            JSONObject row = new JSONObject()
                    .fluentPut("month", s.getMonth())
                    .fluentPut("asOf", String.valueOf(s.getAsOf()))
                    .fluentPut("candidates", s.getCandidates())
                    .fluentPut("tradeCodes", trade)
                    .fluentPut("poolSize", pool.size())
                    .fluentPut("summary", "从 " + s.getCandidates() + " 只候选（当前市值 ≥ 150 亿的沪深 A 股）中筛出 " + pool.size()
                            + " 只通过全部规则（扩展验证池），按日均成交额取前 " + trade.size() + " 只为交易股票池")
                    .fluentPut("details", JSON.parseArray(s.getDetails()));
            if (i + 1 < snaps.size()) {
                List<String> prevPool = JSON.parseArray(snaps.get(i + 1).getPoolCodes(), String.class);
                Set<String> prev = new LinkedHashSet<>(prevPool.subList(0, Math.min(tradeSize, prevPool.size())));
                row.put("added", trade.stream().filter(c -> !prev.contains(c)).toList());
                row.put("removed", prev.stream().filter(c -> !trade.contains(c)).toList());
            }
            rows.add(row);
        }
        // Point-in-time history of the trading universe size, from the backtest screens.
        JSONArray past = new JSONArray();
        for (var e : monthlyScreens().entrySet()) {
            past.add(new JSONObject().fluentPut("month", e.getKey().toString())
                    .fluentPut("asOf", e.getValue().asOf().toString())
                    .fluentPut("passed", e.getValue().pool().size())
                    .fluentPut("top", e.getValue().trade(5)));
        }
        return new JSONObject()
                .fluentPut("rules", rules().describe())
                .fluentPut("limitations", "幸存者偏差只是减少、没有消除：候选来自今天仍上市的股票，已退市的不在其中；历史市值按今天市值乘以复权价变化估算；"
                        + "ST 按今天的名称判断；1 手金额按前复权价估算。下载窗口最早的一两个月数据不足 " + rules().amountDays()
                        + " 个交易日，通过数很少是数据原因，不是当时市场无股可选，这些月份也不在 1000 个交易日的回测区间内。")
                .fluentPut("tradeSize", tradeSize)
                .fluentPut("running", isRunning())
                .fluentPut("progress", progress)
                .fluentPut("snapshots", rows)
                .fluentPut("pointInTime", past);
    }
}
