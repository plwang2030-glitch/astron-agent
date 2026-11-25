package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iflytek.astron.console.hub.entity.ashare.SearchTrial;
import com.iflytek.astron.console.hub.entity.ashare.TradingStrategyVersion;
import com.iflytek.astron.console.hub.mapper.ashare.SearchTrialMapper;
import com.iflytek.astron.console.hub.mapper.ashare.TradingStrategyVersionMapper;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Series;
import com.iflytek.astron.console.toolkit.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Versioned strategy with an evidence-based adoption process, the core of the self-improvement
 * loop.
 * <p>
 * <b>Backtest gate</b> (same data for candidate and active version): about {@link #HISTORY_DAYS}
 * trading days of forward-adjusted bars; the latest {@link #OOS_DAYS} days are out-of-sample in
 * {@link #WINDOW_DAYS}-day windows. The score is the annualized Sharpe ratio (not fooled by simply
 * investing less). A candidate passes when it:
 * <ol>
 * <li>improves the out-of-sample Sharpe by a margin, and the improvement is statistically
 * significant (Jobson–Korkie / Memmel z);</li>
 * <li>wins at least {@link #MIN_WINDOWS_WON} windows including the latest;</li>
 * <li>is not clearly worse in any market regime (rising / falling / sideways 60-day blocks of the
 * whole history, by the Shanghai Composite);</li>
 * <li>is not worse on a broader validation pool of about 60 stocks;</li>
 * <li>does not worsen the drawdown by more than {@link #MAX_DD_WORSENING} points and trades at
 * least {@link #MIN_TRADES} times.</li>
 * </ol>
 * <b>Forward test</b>: a passing candidate becomes a TRIAL and is adopted only after it has done no
 * worse than the active version on {@link #FORWARD_DAYS}+ new trading days no backtest had seen.
 * <p>
 * Candidates come from agents' hypotheses ({@link #propose}, including custom
 * {@link RuleExpression} rules) and from the automatic search ({@link #autoSearch}). Every attempt
 * is recorded as a META lesson that later proposals see.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyService {

    public static final ZoneId ZONE = TradingClock.ZONE;
    static final int HISTORY_DAYS = 1000;
    static final int OOS_DAYS = 360;
    static final int WINDOW_DAYS = 60;
    static final int BLOCK_DAYS = 60;
    static final double REGIME_MOVE_PCT = 5;
    /** Out-of-sample Sharpe margins and significance (z) for proposals / the search (many tries). */
    static final double MIN_IMPROVEMENT = 0.2;
    static final double MIN_IMPROVEMENT_SEARCH = 0.3;
    static final double MIN_Z = 1.0;
    static final double MIN_Z_SEARCH = 1.65;
    static final int MIN_WINDOWS_WON = 4;
    static final double REGIME_TOLERANCE = 0.5;
    static final double POOL_TOLERANCE = 0.1;
    static final double MAX_DD_WORSENING = 3;
    /**
     * Minimum deflated Sharpe ratio: the probability that the true Sharpe is above zero once the number
     * of candidates already measured against this data is taken into account. 0.95 is the usual "strong
     * evidence" bar; a proposal, of which there are far fewer, is held to 0.90.
     */
    static final double MIN_DEFLATED = 0.90;
    static final double MIN_DEFLATED_SEARCH = 0.95;
    static final int MIN_TRADES = 20;
    static final int FORWARD_DAYS = 10;
    static final int FORWARD_MAX_DAYS = 30;
    static final double FORWARD_RETURN_TOLERANCE = 1.0;
    static final double FORWARD_DD_TOLERANCE = 2.0;
    static final int MAX_TRIALS = 3;
    static final int MAX_PROPOSALS_PER_DAY = 10;
    static final int MAX_SEARCHES_PER_DAY = 3;
    static final int SEARCH_CANDIDATES = 24;
    static final double BACKTEST_CASH = 50_000;
    public static final String SEARCH_TAG = "AUTO_SEARCH";
    /**
     * Broader validation pool: liquid large caps across sectors (banks, insurance, brokers, consumer,
     * pharma, autos, materials, energy, utilities, telecom, tech, transport, property). Used only to
     * check that a change is not specific to the trading universe; codes that cannot be loaded are
     * skipped.
     */
    static final List<String> VALIDATION_POOL = List.of("600036", "601318", "600900", "000333", "601012", "000725",
            "002415", "600030", "601899", "300059", "600276", "002475", "601166", "600000", "601398", "601288",
            "601988", "600016", "000001", "601328", "002142", "600919", "601601", "601628", "600309", "600031",
            "000651", "600690", "600887", "000895", "601888", "600585", "601668", "601390", "601186", "601766",
            "600028", "601857", "600019", "601088", "601225", "600050", "601728", "000002", "600048", "002714",
            "000063", "002230", "600588", "002352", "601919", "600104", "000625", "601633", "600438", "000100",
            "002027", "600406", "601985", "003816", "600196", "000538", "600111", "600346");

    private final TradingStrategyVersionMapper versionMapper;
    private final SearchTrialMapper trialMapper;
    private final EastmoneyClient market;
    private final BarStore barStore;
    private final UniverseService universeService;
    private final LessonService lessonService;
    private final RedisUtil redisUtil;
    private final TransactionTemplate transactionTemplate;

    /** Average Sharpe of the history's 60-day blocks in one market regime. */
    public record RegimeStat(int blocks, double sharpe) {}

    /**
     * Evaluation of one version: the full run on the trading universe, its in-sample part, the
     * out-of-sample span and windows, per-regime results and the validation pool's out-of-sample span.
     */
    public record Evaluation(Backtester.Result full, Backtester.Metrics inSample, Backtester.Metrics outOfSample,
            List<Backtester.Metrics> windows, Map<String, RegimeStat> regimes, Backtester.Metrics pool) {

        /** For tests and simple comparisons: no full run, regimes or pool. */
        public Evaluation(Backtester.Metrics inSample, Backtester.Metrics outOfSample, List<Backtester.Metrics> windows) {
            this(null, inSample, outOfSample, windows, Map.of(), null);
        }

        public JSONObject toJson() {
            JSONArray w = new JSONArray();
            windows.forEach(r -> w.add(r.toJson()));
            JSONObject reg = new JSONObject();
            regimes.forEach((k, v) -> reg.put(k, new JSONObject().fluentPut("blocks", v.blocks())
                    .fluentPut("sharpe", Indicators.round(v.sharpe(), 2))));
            return new JSONObject()
                    .fluentPut("inSample", inSample.toJson())
                    .fluentPut("outOfSample", outOfSample.toJson())
                    .fluentPut("windows", w)
                    .fluentPut("regimes", reg)
                    .fluentPut("pool", pool == null ? null : pool.toJson());
        }
    }

    /** The active version; version 1 (defaults) is created on first use. */
    public TradingStrategyVersion active(String uid) {
        TradingStrategyVersion v = findActive(uid);
        if (v != null) {
            return v;
        }
        TradingStrategyVersion first = new TradingStrategyVersion();
        first.setUid(uid);
        first.setVersion(1);
        first.setParams(StrategyParams.defaults().toJson());
        first.setRationale("初始版本：均线趋势 + RSI + 量比过滤，等权仓位，固定止损止盈；股票池按规则每月筛选");
        first.setStatus(TradingStrategyVersion.ACTIVE);
        try {
            versionMapper.insert(first);
            log.info("Trading strategy v1 created, uid={}", uid);
            return first;
        } catch (DuplicateKeyException e) {
            return Objects.requireNonNull(findActive(uid));
        }
    }

    public StrategyParams activeParams(String uid) {
        return StrategyParams.parse(active(uid).getParams());
    }

    private TradingStrategyVersion findActive(String uid) {
        return versionMapper.selectOne(Wrappers.lambdaQuery(TradingStrategyVersion.class)
                .eq(TradingStrategyVersion::getUid, uid)
                .eq(TradingStrategyVersion::getStatus, TradingStrategyVersion.ACTIVE)
                .orderByDesc(TradingStrategyVersion::getVersion)
                .last("LIMIT 1"));
    }

    public TradingStrategyVersion version(String uid, Integer version) {
        return version == null ? null
                : versionMapper.selectOne(Wrappers.lambdaQuery(TradingStrategyVersion.class)
                        .eq(TradingStrategyVersion::getUid, uid)
                        .eq(TradingStrategyVersion::getVersion, version));
    }

    public List<TradingStrategyVersion> trials(String uid) {
        return versionMapper.selectList(Wrappers.lambdaQuery(TradingStrategyVersion.class)
                .eq(TradingStrategyVersion::getUid, uid)
                .eq(TradingStrategyVersion::getStatus, TradingStrategyVersion.TRIAL)
                .orderByAsc(TradingStrategyVersion::getVersion));
    }

    public JSONObject current(String uid) {
        TradingStrategyVersion v = active(uid);
        StrategyParams p = StrategyParams.parse(v.getParams());
        JSONArray trialRows = new JSONArray();
        for (TradingStrategyVersion t : trials(uid)) {
            JSONObject eval = StringUtils.isBlank(t.getEvaluation()) ? new JSONObject() : JSON.parseObject(t.getEvaluation());
            trialRows.add(new JSONObject()
                    .fluentPut("version", t.getVersion())
                    .fluentPut("description", StrategyParams.parse(t.getParams()).describe())
                    .fluentPut("forward", eval.get("forward")));
        }
        return new JSONObject()
                .fluentPut("version", v.getVersion())
                .fluentPut("since", v.getCreateTime() == null ? null : v.getCreateTime().toLocalDate().toString())
                .fluentPut("description", p.describe())
                .fluentPut("params", JSONObject.from(p))
                .fluentPut("universe", liveUniverse(p))
                .fluentPut("rationale", v.getRationale())
                .fluentPut("trials", trialRows)
                .fluentPut("gate", gateText());
    }

    /** The active version's live universe with the screen's rules and monthly changes. */
    public JSONObject universe(String uid) {
        StrategyParams p = activeParams(uid);
        int size = p.autoUniverse() ? p.getUniverseSize() : UniverseScreen.Rules.defaults().tradeSize();
        JSONObject out = universeService.overview(size, 6);
        out.put("mode", p.getUniverseMode());
        out.put("live", liveUniverse(p));
        if (!p.autoUniverse()) {
            out.put("note", "当前版本使用手选股票池（旧版本），规则筛选结果仅供参考");
        }
        return out;
    }

    public List<TradingStrategyVersion> history(String uid, int limit) {
        active(uid);
        return versionMapper.selectList(Wrappers.lambdaQuery(TradingStrategyVersion.class)
                .eq(TradingStrategyVersion::getUid, uid)
                .orderByDesc(TradingStrategyVersion::getVersion)
                .last("LIMIT " + Math.max(1, Math.min(limit, 30))));
    }

    /**
     * Backtests the active version, or the active version with {@code patch} applied (not saved, with
     * the gate check against the active version).
     */
    public JSONObject backtest(String uid, JSONObject patch) {
        TradingStrategyVersion v = active(uid);
        StrategyParams base = StrategyParams.parse(v.getParams());
        boolean trial = patch != null && !patch.isEmpty();
        StrategyParams p = trial ? base.merge(patch) : base;
        List<String> errors = p.validate();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("参数不合法：" + String.join("；", errors));
        }
        Data data = loadData(List.of(base, p), trial);
        Evaluation e = evaluate(p, data);
        JSONObject out = new JSONObject()
                .fluentPut("basedOnVersion", v.getVersion())
                .fluentPut("changes", p.diff(base))
                .fluentPut("description", p.describe())
                .fluentPut("result", e.toJson())
                .fluentPut("lastTrades", tradesJson(e.full()))
                .fluentPut("note", "约 " + data.calendar().size() + " 个交易日前复权日线回测，规则与实盘相同；得分为夏普比率；历史表现不代表未来。");
        if (trial) {
            Evaluation b = evaluate(base, data);
            int trials = trialCount(uid) + 1;
            double dsr = Statistics.deflatedSharpe(e.outOfSample().dailyReturns(), trials,
                    historicalSharpeSpread(uid));
            List<String> reasons = gate(b, e, MIN_IMPROVEMENT, MIN_Z, dsr, MIN_DEFLATED, trials);
            out.put("baseline", b.toJson());
            out.put("significanceZ", Indicators.round(z(b, e), 2));
            out.put("deflatedSharpe", Indicators.round(dsr, 3));
            out.put("trials", trials);
            out.put("gateCheck", reasons);
            // Measuring a candidate against the out-of-sample data is a trial whoever asked for it,
            // including a dry run. Not counting it would understate how hard the system has searched
            // and quietly lower the bar for everything measured afterwards.
            recordTrial(uid, p, base, v.getVersion(), b, e, dsr, trials, reasons.isEmpty(), SearchTrial.BACKTEST);
        }
        return out;
    }

    static double z(Evaluation base, Evaluation cand) {
        return Backtester.sharpeDiffZ(cand.outOfSample().dailyReturns(), base.outOfSample().dailyReturns());
    }

    /**
     * Gate reasons against the baseline; empty means the candidate passes the backtest stage.
     *
     * @param minImprovement out-of-sample Sharpe margin over the baseline
     * @param minZ required significance of the Sharpe improvement
     */
    static List<String> gate(Evaluation base, Evaluation cand, double minImprovement, double minZ) {
        return gate(base, cand, minImprovement, minZ, Double.NaN, 0, 0);
    }

    /**
     * @param deflated deflated Sharpe ratio of the candidate, or {@code NaN} to skip the check
     * @param minDeflated the bar it must clear
     * @param trials how many candidates have been measured against this data
     */
    static List<String> gate(Evaluation base, Evaluation cand, double minImprovement, double minZ, double deflated,
            double minDeflated, int trials) {
        List<String> reasons = new ArrayList<>();
        if (!Double.isNaN(deflated) && deflated < minDeflated) {
            reasons.add(String.format(Locale.ROOT,
                    "按已检验过的 %d 个候选折减后，真实夏普大于 0 的概率只有 %.0f%%（需 ≥ %.0f%%）：这个结果还不能和「搜索得多自然会碰到」区分开",
                    trials, deflated * 100, minDeflated * 100));
        }
        Backtester.Metrics b = base.outOfSample();
        Backtester.Metrics c = cand.outOfSample();
        if (c.score() <= b.score() + minImprovement) {
            reasons.add(String.format(Locale.ROOT, "样本外夏普 %.2f 未超过基线 %.2f + %.1f", c.score(), b.score(),
                    minImprovement));
        }
        if (c.dailyReturns().length > 0 && b.dailyReturns().length > 0) {
            double z = Backtester.sharpeDiffZ(c.dailyReturns(), b.dailyReturns());
            if (z < minZ) {
                reasons.add(String.format(Locale.ROOT, "提升不显著（z=%.2f，需 ≥ %.2f）", z, minZ));
            }
        }
        int won = windowsWon(base, cand);
        int n = cand.windows().size();
        boolean latestWon = n > 0 && cand.windows().get(n - 1).score() > base.windows().get(n - 1).score();
        int need = Math.min(MIN_WINDOWS_WON, n);
        if (won < need || !latestWon) {
            reasons.add("滚动窗口只赢了 " + won + "/" + n + (latestWon ? "" : "，且最近一个窗口没赢") + "（需至少赢 " + need
                    + " 个且包括最近一个）");
        }
        for (var e : base.regimes().entrySet()) {
            RegimeStat cs = cand.regimes().get(e.getKey());
            if (cs != null && cs.sharpe() < e.getValue().sharpe() - REGIME_TOLERANCE) {
                reasons.add(String.format(Locale.ROOT, "在%s阶段明显变差（夏普 %.2f vs 基线 %.2f，%d 段）", regimeLabel(e.getKey()),
                        cs.sharpe(), e.getValue().sharpe(), cs.blocks()));
            }
        }
        if (base.pool() != null && cand.pool() != null && cand.pool().score() < base.pool().score() - POOL_TOLERANCE) {
            reasons.add(String.format(Locale.ROOT, "在扩展验证股票池上变差（样本外夏普 %.2f vs 基线 %.2f）", cand.pool().score(),
                    base.pool().score()));
        }
        if (c.maxDrawdownPct() > b.maxDrawdownPct() + MAX_DD_WORSENING) {
            reasons.add(String.format(Locale.ROOT, "样本外最大回撤 %.2f%% 比基线 %.2f%% 恶化超过 %.0f 个点",
                    c.maxDrawdownPct(), b.maxDrawdownPct(), MAX_DD_WORSENING));
        }
        if (c.trades() < MIN_TRADES) {
            reasons.add("样本外只成交 " + c.trades() + " 笔，少于 " + MIN_TRADES + " 笔，结论不可靠");
        }
        return reasons;
    }

    static String regimeLabel(String regime) {
        return switch (regime) {
            case "UP" -> "上涨";
            case "DOWN" -> "下跌";
            default -> "震荡";
        };
    }

    static int windowsWon(Evaluation base, Evaluation cand) {
        int won = 0;
        for (int i = 0; i < cand.windows().size() && i < base.windows().size(); i++) {
            if (cand.windows().get(i).score() > base.windows().get(i).score()) {
                won++;
            }
        }
        return won;
    }

    /**
     * Backtests a proposal against the active version. Passing the gate makes it a TRIAL (forward
     * test); otherwise it is REJECTED. Either way the attempt is saved and recorded as a META lesson.
     */
    public JSONObject propose(String uid, JSONObject patch, String rationale) {
        if (patch == null || patch.isEmpty()) {
            throw new IllegalArgumentException("params 不能为空：只写要改的字段，例如 {\"rsiMax\":65}");
        }
        if (StringUtils.isBlank(rationale)) {
            throw new IllegalArgumentException("rationale 必填：说明依据哪些复盘结论或经验提出这次修改");
        }
        return withLock(uid, () -> {
            LocalDateTime dayStart = TradingClock.now().toLocalDate().atStartOfDay();
            long today = versionMapper.selectCount(Wrappers.lambdaQuery(TradingStrategyVersion.class)
                    .eq(TradingStrategyVersion::getUid, uid)
                    .gt(TradingStrategyVersion::getVersion, 1)
                    .ge(TradingStrategyVersion::getCreateTime, dayStart));
            if (today >= MAX_PROPOSALS_PER_DAY) {
                // Each attempt peeks at the out-of-sample data; too many attempts overfit it.
                throw new IllegalArgumentException("今天已提交 " + today + " 次策略提案，达到上限 " + MAX_PROPOSALS_PER_DAY
                        + "（反复试错会过拟合样本外数据），明天再试");
            }
            TradingStrategyVersion active = active(uid);
            StrategyParams base = StrategyParams.parse(active.getParams());
            StrategyParams cand = base.merge(patch);
            List<String> errors = cand.validate();
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException("参数不合法：" + String.join("；", errors));
            }
            if (cand.sameAs(base)) {
                throw new IllegalArgumentException("提案与当前版本 v" + active.getVersion() + " 相同");
            }
            Data data = loadData(List.of(base, cand), true);
            Evaluation baseEval = evaluate(base, data);
            Evaluation candEval = evaluate(cand, data);
            int trials = trialCount(uid) + 1;
            double dsr = Statistics.deflatedSharpe(candEval.outOfSample().dailyReturns(), trials,
                    historicalSharpeSpread(uid));
            List<String> reasons = gate(baseEval, candEval, MIN_IMPROVEMENT, MIN_Z, dsr, MIN_DEFLATED, trials);
            recordTrial(uid, cand, base, active.getVersion(), baseEval, candEval, dsr, trials, reasons.isEmpty(),
                    SearchTrial.PROPOSAL);
            return saveVersion(uid, active, base, cand, baseEval, candEval, reasons, rationale.trim(), "提案",
                    String.format(Locale.ROOT, "（折减后真实夏普>0 的概率 %.0f%%，已检验 %d 个候选）", dsr * 100, trials), null);
        });
    }

    /**
     * Saves a candidate as a new version: TRIAL when {@code reasons} is empty (forward test pending),
     * else REJECTED, with a META lesson describing the attempt. Keeps at most {@link #MAX_TRIALS}
     * trials (the weakest by out-of-sample Sharpe is dropped).
     */
    private JSONObject saveVersion(String uid, TradingStrategyVersion active, StrategyParams base,
            StrategyParams cand, Evaluation baseEval, Evaluation candEval, List<String> reasons, String rationale,
            String kind, String extraMeta, String tags) {
        boolean passed = reasons.isEmpty();
        int version = nextVersion(uid);
        String changes = cand.diff(base);
        Backtester.Metrics b = baseEval.outOfSample();
        Backtester.Metrics c = candEval.outOfSample();
        double z = z(baseEval, candEval);
        JSONObject evaluation = new JSONObject()
                .fluentPut("baseline", baseEval.toJson().fluentPut("version", active.getVersion()))
                .fluentPut("candidate", candEval.toJson().fluentPut("version", version))
                .fluentPut("windowsWon", windowsWon(baseEval, candEval))
                .fluentPut("significanceZ", Indicators.round(z, 2))
                .fluentPut("passedBacktest", passed)
                .fluentPut("adopted", false)
                .fluentPut("reasons", reasons);
        TradingStrategyVersion row = new TradingStrategyVersion();
        row.setUid(uid);
        row.setVersion(version);
        row.setParentVersion(active.getVersion());
        row.setParams(cand.toJson());
        row.setRationale(StringUtils.abbreviate(rationale, 2000));
        row.setStatus(passed ? TradingStrategyVersion.TRIAL : TradingStrategyVersion.REJECTED);
        row.setEvaluation(evaluation.toJSONString());
        String meta = String.format(Locale.ROOT,
                "v%d %s%s（基于 v%d：%s）：样本外夏普 %.2f vs 基线 %.2f（z=%.2f），收益 %.2f%% vs %.2f%%，回撤 %.2f%% vs %.2f%%，仓位占用 %.0f%% vs %.0f%%，窗口赢 %d/%d，交易 %d 笔。%s%s理由：%s",
                version, kind, passed ? "通过回测闸门，进入 " + FORWARD_DAYS + " 个交易日前进测试" : "被拒绝", active.getVersion(),
                changes, c.score(), b.score(), z, c.returnPct(), b.returnPct(), c.maxDrawdownPct(), b.maxDrawdownPct(),
                c.exposurePct(), b.exposurePct(), windowsWon(baseEval, candEval), candEval.windows().size(), c.trades(),
                passed ? "" : "原因：" + String.join("；", reasons) + "。", StringUtils.defaultString(extraMeta),
                StringUtils.abbreviate(rationale, 160));
        transactionTemplate.executeWithoutResult(s -> {
            versionMapper.insert(row);
            lessonService.recordMeta(uid, meta, JSON.toJSONString(List.of("v" + version)), tags);
            if (passed) {
                trimTrials(uid);
            }
        });
        log.info("Strategy {} evaluated, uid={}, version={}, parent={}, passed={}, candOos={}, baseOos={}, z={}, windowsWon={}, trades={}",
                kind, uid, version, active.getVersion(), passed, Indicators.round(c.score(), 2),
                Indicators.round(b.score(), 2), Indicators.round(z, 2), windowsWon(baseEval, candEval), c.trades());
        return new JSONObject()
                .fluentPut("version", version)
                .fluentPut("status", row.getStatus())
                .fluentPut("passedBacktest", passed)
                .fluentPut("changes", changes)
                .fluentPut("reasons", reasons)
                .fluentPut("evaluation", evaluation)
                .fluentPut("activeVersion", active.getVersion())
                .fluentPut("next", passed ? "进入前进测试：在之后 " + FORWARD_DAYS + " 个交易日的新行情上与当前版本对比，不差于当前版本才正式采纳" : null)
                .fluentPut("recordedLesson", meta);
    }

    private void trimTrials(String uid) {
        List<TradingStrategyVersion> trials = trials(uid);
        if (trials.size() <= MAX_TRIALS) {
            return;
        }
        trials.sort(Comparator.comparingDouble(StrategyService::trialScore));
        for (TradingStrategyVersion t : trials.subList(0, trials.size() - MAX_TRIALS)) {
            finishTrial(uid, t, false, "观察名额已满（最多 " + MAX_TRIALS + " 个），样本外夏普最低的被移出", null);
        }
    }

    private static double trialScore(TradingStrategyVersion t) {
        try {
            return JSON.parseObject(t.getEvaluation())
                    .getJSONObject("candidate")
                    .getJSONObject("outOfSample")
                    .getDoubleValue("score");
        } catch (RuntimeException e) {
            return Double.NEGATIVE_INFINITY;
        }
    }

    /**
     * Forward test of the user's TRIAL versions, run after each trading day: each is compared with the
     * active version on the trading days after it was created (data its backtest never saw). After
     * {@link #FORWARD_DAYS} days with trades (or {@link #FORWARD_MAX_DAYS} days in any case) it is
     * adopted when it did no worse ({@link #forwardVerdict}); otherwise rejected. A trial whose
     * baseline changed is dropped. Returns a summary.
     */
    public JSONObject evaluateTrials(String uid) {
        List<TradingStrategyVersion> trials = trials(uid);
        JSONObject out = new JSONObject().fluentPut("trials", trials.size());
        if (trials.isEmpty()) {
            return out;
        }
        return withLock(uid, () -> {
            TradingStrategyVersion active = active(uid);
            StrategyParams base = StrategyParams.parse(active.getParams());
            List<StrategyParams> versions = new ArrayList<>(List.of(base));
            for (TradingStrategyVersion t : trials) {
                versions.add(StrategyParams.parse(t.getParams()));
            }
            Data data = loadData(versions, false);
            JSONArray results = new JSONArray();
            // Best backtest first, so that it wins when several finish on the same day.
            trials.sort(Comparator.comparingDouble((TradingStrategyVersion t) -> -trialScore(t)));
            boolean adopted = false;
            for (TradingStrategyVersion t : trials) {
                if (adopted || !Objects.equals(t.getParentVersion(), active.getVersion())) {
                    finishTrial(uid, t, false, adopted ? "同期另一个候选已被采纳，基线已更新" : "当前版本已变化（不再是 v" + t.getParentVersion() + "）",
                            null);
                    results.add(new JSONObject().fluentPut("version", t.getVersion()).fluentPut("result", "dropped"));
                    continue;
                }
                LocalDate since = t.getCreateTime() == null ? TradingClock.now().toLocalDate()
                        : t.getCreateTime().toLocalDate();
                List<LocalDate> cal = data.calendar();
                int from = 0;
                while (from < cal.size() && !cal.get(from).isAfter(since)) {
                    from++;
                }
                int days = cal.size() - from;
                JSONObject forward = new JSONObject().fluentPut("since", since.toString())
                        .fluentPut("days", days)
                        .fluentPut("needDays", FORWARD_DAYS);
                if (days > 0) {
                    StrategyParams cp = StrategyParams.parse(t.getParams());
                    Backtester.Result cr = Backtester.run(cp, data.series(), data.index(), cal, from, cal.size() - 1,
                            BACKTEST_CASH, data.tradeUniverse(cp));
                    Backtester.Result br = Backtester.run(base, data.series(), data.index(), cal, from, cal.size() - 1,
                            BACKTEST_CASH, data.tradeUniverse(base));
                    forward.put("candidate", Backtester.segment(cr, 0, cr.days().size() - 1).toJson());
                    forward.put("baseline", Backtester.segment(br, 0, br.days().size() - 1).toJson());
                    String verdict = forwardVerdict(days, cr, br);
                    forward.put("verdict", verdict);
                    if ("ADOPT".equals(verdict) || "REJECT".equals(verdict)) {
                        boolean ok = "ADOPT".equals(verdict);
                        String why = String.format(Locale.ROOT, "前进测试 %d 个交易日：收益 %.2f%% vs 当前版本 %.2f%%，回撤 %.2f%% vs %.2f%%，交易 %d vs %d 笔",
                                days, cr.returnPct(), br.returnPct(), cr.maxDrawdownPct(), br.maxDrawdownPct(), cr.trades(),
                                br.trades());
                        finishTrial(uid, t, ok, why, forward);
                        if (ok) {
                            active.setStatus(TradingStrategyVersion.RETIRED);
                            versionMapper.updateById(active);
                            adopted = true;
                        }
                        results.add(new JSONObject().fluentPut("version", t.getVersion()).fluentPut("result", verdict));
                        continue;
                    }
                }
                saveForward(t, forward);
                results.add(new JSONObject().fluentPut("version", t.getVersion())
                        .fluentPut("result", "WAIT")
                        .fluentPut("days", days));
            }
            log.info("Strategy trials evaluated, uid={}, results={}", uid, results);
            return out.fluentPut("results", results);
        });
    }

    /**
     * ADOPT, REJECT or WAIT. Adopted when the candidate's forward return is no more than
     * {@link #FORWARD_RETURN_TOLERANCE} points below the active version's and its drawdown no more than
     * {@link #FORWARD_DD_TOLERANCE} points worse. Waits until {@link #FORWARD_DAYS} days with at least
     * one trade by either version, or {@link #FORWARD_MAX_DAYS} days.
     */
    static String forwardVerdict(int days, Backtester.Result cand, Backtester.Result base) {
        boolean traded = cand.trades() + base.trades() > 0 || cand.returnPct() != base.returnPct();
        if (days < FORWARD_DAYS || (!traded && days < FORWARD_MAX_DAYS)) {
            return "WAIT";
        }
        boolean ok = cand.returnPct() >= base.returnPct() - FORWARD_RETURN_TOLERANCE
                && cand.maxDrawdownPct() <= base.maxDrawdownPct() + FORWARD_DD_TOLERANCE;
        return ok ? "ADOPT" : "REJECT";
    }

    private void saveForward(TradingStrategyVersion t, JSONObject forward) {
        JSONObject eval = StringUtils.isBlank(t.getEvaluation()) ? new JSONObject() : JSON.parseObject(t.getEvaluation());
        eval.put("forward", forward);
        TradingStrategyVersion patch = new TradingStrategyVersion();
        patch.setId(t.getId());
        patch.setEvaluation(eval.toJSONString());
        versionMapper.updateById(patch);
    }

    private void finishTrial(String uid, TradingStrategyVersion t, boolean adopt, String why, JSONObject forward) {
        JSONObject eval = StringUtils.isBlank(t.getEvaluation()) ? new JSONObject() : JSON.parseObject(t.getEvaluation());
        if (forward != null) {
            eval.put("forward", forward);
        }
        eval.put("adopted", adopt);
        if (!adopt) {
            JSONArray reasons = eval.getJSONArray("reasons") == null ? new JSONArray() : eval.getJSONArray("reasons");
            reasons.add(why);
            eval.put("reasons", reasons);
        }
        TradingStrategyVersion patch = new TradingStrategyVersion();
        patch.setId(t.getId());
        patch.setStatus(adopt ? TradingStrategyVersion.ACTIVE : TradingStrategyVersion.REJECTED);
        patch.setEvaluation(eval.toJSONString());
        versionMapper.updateById(patch);
        lessonService.recordMeta(uid, "v" + t.getVersion() + (adopt ? " 通过前进测试，正式采纳。" : " 未被采纳。") + why
                + "。改动：" + StrategyParams.parse(t.getParams()).describe(), JSON.toJSONString(List.of("v" + t.getVersion())),
                null);
        log.info("Strategy trial finished, uid={}, version={}, adopted={}, why={}", uid, t.getVersion(), adopt, why);
    }

    /** One backtested candidate of a search. */
    record Scored(StrategyParams params, Evaluation eval, List<String> reasons, double deflatedSharpe) {}

    /**
     * Automatic evolution: backtests {@link #SEARCH_CANDIDATES} mutations of the active version (one or
     * two parameters, options or rule thresholds each, never repeating an earlier version) on the same
     * data, and sends the best one that passes the gate with the stricter search margins to the forward
     * test. The universe is not changed by the search. The outcome is recorded as a META lesson.
     *
     * @param period tag of the run (e.g. 2026-W48) to avoid running twice for one period; {@code null}
     *        for an on-demand run
     */
    public JSONObject autoSearch(String uid, String period, String trigger) {
        return withLock(uid, () -> {
            if (period != null && lessonService.hasMetaTag(uid, SEARCH_TAG + ":" + period)) {
                return new JSONObject().fluentPut("skipped", "本期已经搜索过");
            }
            if (lessonService.countMetaTagSince(uid, SEARCH_TAG, TradingClock.now().toLocalDate()) >= MAX_SEARCHES_PER_DAY) {
                throw new IllegalArgumentException("今天已自动搜索 " + MAX_SEARCHES_PER_DAY + " 次，明天再试");
            }
            long start = System.currentTimeMillis();
            TradingStrategyVersion active = active(uid);
            StrategyParams base = StrategyParams.parse(active.getParams());
            Set<String> tried = new HashSet<>();
            for (TradingStrategyVersion v : history(uid, 30)) {
                tried.add(StrategyParams.parse(v.getParams()).toJson());
            }
            long seed = Objects.hash(uid, period == null ? TradingClock.now().toString() : period, active.getVersion());
            List<StrategyParams> candidates = candidates(base, seed, SEARCH_CANDIDATES, tried);
            Data data = loadData(List.of(base), true);
            Evaluation baseEval = evaluate(base, data);
            // Every candidate is evaluated first, then judged: the spread of their scores and the
            // running trial count are both needed before any of them can be deflated.
            int trialsBefore = trialCount(uid);
            List<Evaluation> evals = new ArrayList<>();
            List<Double> sharpes = new ArrayList<>();
            for (StrategyParams c : candidates) {
                Evaluation e = evaluate(c, data);
                evals.add(e);
                sharpes.add(e.outOfSample().score());
            }
            double spread = Statistics.sharpeSpread(sharpes);
            int trials = trialsBefore + candidates.size();
            List<Scored> scored = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                StrategyParams c = candidates.get(i);
                Evaluation e = evals.get(i);
                double dsr = Statistics.deflatedSharpe(e.outOfSample().dailyReturns(), trials, spread);
                List<String> reasons = gate(baseEval, e, MIN_IMPROVEMENT_SEARCH, MIN_Z_SEARCH, dsr,
                        MIN_DEFLATED_SEARCH, trials);
                scored.add(new Scored(c, e, reasons, dsr));
                recordTrial(uid, c, base, active.getVersion(), baseEval, e, dsr, trials, reasons.isEmpty(),
                        SearchTrial.SEARCH);
            }
            scored.sort(Comparator.comparingDouble((Scored s) -> -s.eval().outOfSample().score()));
            Scored best = scored.stream().filter(s -> s.reasons().isEmpty()).findFirst().orElse(null);
            long passed = scored.stream().filter(s -> s.reasons().isEmpty()).count();
            String label = (period == null ? "手动" : period) + " 自动进化搜索";
            JSONArray top = new JSONArray();
            for (Scored s : scored.subList(0, Math.min(5, scored.size()))) {
                Backtester.Metrics o = s.eval().outOfSample();
                top.add(new JSONObject()
                        .fluentPut("changes", s.params().diff(base))
                        .fluentPut("oosScore", Indicators.round(o.score(), 2))
                        .fluentPut("oosReturnPct", Indicators.round(o.returnPct(), 2))
                        .fluentPut("oosMaxDrawdownPct", Indicators.round(o.maxDrawdownPct(), 2))
                        .fluentPut("exposurePct", Indicators.round(o.exposurePct(), 1))
                        .fluentPut("z", Indicators.round(z(baseEval, s.eval()), 2))
                        .fluentPut("deflatedSharpe", Indicators.round(s.deflatedSharpe(), 3))
                        .fluentPut("windowsWon", windowsWon(baseEval, s.eval()))
                        .fluentPut("windows", s.eval().windows().size())
                        .fluentPut("trades", o.trades())
                        .fluentPut("passed", s.reasons().isEmpty())
                        .fluentPut("reasons", s.reasons()));
            }
            JSONObject out = new JSONObject()
                    .fluentPut("tried", scored.size())
                    .fluentPut("passed", passed)
                    .fluentPut("baselineVersion", active.getVersion())
                    .fluentPut("baselineOosScore", Indicators.round(baseEval.outOfSample().score(), 2))
                    .fluentPut("trials", trials)
                    .fluentPut("minDeflated", MIN_DEFLATED_SEARCH)
                    .fluentPut("historyDays", data.calendar().size())
                    .fluentPut("poolSize", data.pool() == null ? 0 : data.pool().size())
                    .fluentPut("top", top);
            String tags = SEARCH_TAG + (period == null ? "" : "," + SEARCH_TAG + ":" + period);
            if (best != null) {
                JSONObject saved = saveVersion(uid, active, base, best.params(), baseEval, best.eval(), List.of(),
                        label + "：从 " + scored.size() + " 个候选中样本外夏普最高且通过闸门（" + passed + " 个通过）", "自动进化",
                        "（" + label + "：" + scored.size() + " 个候选，" + passed + " 个通过，触发：" + trigger + "）", tags);
                out.put("trial", saved);
            } else {
                Scored first = scored.isEmpty() ? null : scored.get(0);
                String meta = label + "：试了 " + scored.size() + " 个候选，没有通过闸门，当前仍为 v" + active.getVersion()
                        + (first == null ? "。"
                                : String.format(Locale.ROOT, "。最好的候选：%s，样本外夏普 %.2f vs 基线 %.2f，未通过：%s。",
                                        first.params().diff(base), first.eval().outOfSample().score(),
                                        baseEval.outOfSample().score(), String.join("；", first.reasons())));
                lessonService.recordMeta(uid, meta, null, tags);
                out.put("trial", null);
                out.put("recordedLesson", meta);
            }
            log.info("Strategy auto search done, uid={}, period={}, trigger={}, tried={}, passed={}, trial={}, days={}, pool={}, costMs={}",
                    uid, period, trigger, scored.size(), passed, best != null, data.calendar().size(),
                    data.pool() == null ? 0 : data.pool().size(), System.currentTimeMillis() - start);
            return out;
        });
    }

    /**
     * Mutations of {@code base} for the search: every valid single change of one number, option or rule
     * threshold, plus pairs of changes to different fields, shuffled with {@code seed}; never
     * {@code base} itself or a version in {@code tried}. The universe is left alone.
     */
    static List<StrategyParams> candidates(StrategyParams base, long seed, int n, Set<String> tried) {
        List<JSONObject> singles = mutations(base);
        Random random = new Random(seed);
        Collections.shuffle(singles, random);
        Map<String, StrategyParams> out = new LinkedHashMap<>();
        Set<String> skip = new HashSet<>(tried);
        skip.add(base.toJson());
        int singleQuota = Math.max(1, n * 6 / 10);
        for (JSONObject m : singles) {
            if (out.size() >= singleQuota) {
                break;
            }
            add(base, m, out, skip);
        }
        for (int attempt = 0; attempt < n * 20 && out.size() < n && singles.size() > 1; attempt++) {
            JSONObject a = singles.get(random.nextInt(singles.size()));
            JSONObject b = singles.get(random.nextInt(singles.size()));
            if (a.keySet().equals(b.keySet())) {
                continue;
            }
            JSONObject both = new JSONObject();
            both.putAll(a);
            both.putAll(b);
            add(base, both, out, skip);
        }
        return new ArrayList<>(out.values());
    }

    private static void add(StrategyParams base, JSONObject patch, Map<String, StrategyParams> out, Set<String> skip) {
        StrategyParams c = base.merge(patch);
        String key = c.toJson();
        if (c.validate().isEmpty() && !skip.contains(key) && !c.sameAs(base)) {
            out.putIfAbsent(key, c);
        }
    }

    /** Every single-field change the search may try. */
    static List<JSONObject> mutations(StrategyParams p) {
        List<JSONObject> out = new ArrayList<>();
        step(out, "maFast", p.getMaFast(), 2);
        step(out, "maSlow", p.getMaSlow(), 10);
        step(out, "crossWithinDays", p.getCrossWithinDays(), 2);
        step(out, "breakoutDays", p.getBreakoutDays(), 10);
        step(out, "rsiMin", p.getRsiMin(), 5);
        step(out, "rsiMax", p.getRsiMax(), 5);
        step(out, "volumeRatioMin", p.getVolumeRatioMin(), 0.2);
        step(out, "stopLossPct", p.getStopLossPct(), 2);
        step(out, "takeProfitPct", p.getTakeProfitPct(), 5);
        step(out, "maxHoldDays", p.getMaxHoldDays(), 10);
        step(out, "maxPositions", p.getMaxPositions(), 1);
        step(out, "maxWeightPct", p.getMaxWeightPct(), 5);
        step(out, "riskPerTradePct", p.getRiskPerTradePct(), 0.5);
        // Noise handling: how wide the stop is relative to the stock's own volatility, and whether a
        // price level is acted on the moment it is touched or only once the close confirms it.
        step(out, "stopAtrMult", p.getStopAtrMult(), 0.5);
        for (String mode : List.of(StrategyParams.PCT, StrategyParams.ATR)) {
            if (!mode.equals(p.getStopMode())) {
                out.add(new JSONObject().fluentPut("stopMode", mode));
            }
        }
        for (String check : List.of(StrategyParams.INTRADAY, StrategyParams.CLOSE, StrategyParams.HALF_THEN_CLOSE)) {
            if (!check.equals(p.getExitCheck())) {
                out.add(new JSONObject().fluentPut("exitCheck", check));
            }
        }
        options(out, "trailingStopPct", p.getTrailingStopPct(), 0.0, 6.0, 10.0, 15.0);
        options(out, "partialTakeProfitPct", p.getPartialTakeProfitPct(), 0.0, 8.0, 12.0);
        List<String> types = new ArrayList<>(List.of(StrategyParams.MA_CROSS, StrategyParams.BREAKOUT,
                StrategyParams.MACD, StrategyParams.ANY));
        if (StringUtils.isNotBlank(p.getCustomEntry())) {
            types.add(StrategyParams.CUSTOM);
        }
        for (String t : types) {
            if (!t.equals(p.getEntryType())) {
                out.add(new JSONObject().fluentPut("entryType", t));
            }
        }
        // Nearby thresholds of custom rules (agents invent the rules, the search tunes their numbers).
        ruleSteps(out, "customEntry", p.getCustomEntry());
        ruleSteps(out, "customExit", p.getCustomExit());
        out.add(new JSONObject().fluentPut("sizing",
                StrategyParams.RISK.equals(p.getSizing()) ? StrategyParams.EQUAL : StrategyParams.RISK));
        out.add(new JSONObject().fluentPut("marketFilter", !Boolean.TRUE.equals(p.getMarketFilter())));
        out.add(new JSONObject().fluentPut("requireAboveMa60", !Boolean.TRUE.equals(p.getRequireAboveMa60())));
        out.add(new JSONObject().fluentPut("exitOnMaCrossDown", !Boolean.TRUE.equals(p.getExitOnMaCrossDown())));
        return out;
    }

    private static void ruleSteps(List<JSONObject> out, String field, String rule) {
        if (StringUtils.isBlank(rule)) {
            return;
        }
        int count = Math.min(6, RuleExpression.countNumbers(rule));
        for (int i = 0; i < count; i++) {
            for (double f : new double[] {0.8, 1.2}) {
                String next = RuleExpression.scaleNumber(rule, i, f);
                if (!next.equals(rule) && RuleExpression.check(next) == null) {
                    out.add(new JSONObject().fluentPut(field, next));
                }
            }
        }
    }

    private static void step(List<JSONObject> out, String field, Number value, double step) {
        boolean integer = value instanceof Integer;
        for (int dir : new int[] {-1, 1}) {
            double v = value.doubleValue() + dir * step;
            out.add(new JSONObject().fluentPut(field, integer ? (Object) (int) Math.round(v)
                    : (Object) (Math.round(v * 100) / 100.0)));
        }
    }

    private static void options(List<JSONObject> out, String field, Double value, double... choices) {
        for (double c : choices) {
            if (value == null || Double.compare(value, c) != 0) {
                out.add(new JSONObject().fluentPut(field, c));
            }
        }
    }

    /**
     * Method change, not a proposal: the active version with a hand-picked universe becomes a new
     * active version that trades the rule-based monthly screen ({@link UniverseScreen}). It does not go
     * through the gate, since the old universe's backtest is biased (stocks picked with hindsight) and
     * is no fair baseline; both backtests are saved for reference and the change is recorded as a META
     * lesson. Needs the screens to exist.
     */
    public JSONObject switchToAutoUniverse(String uid, int size, String reason) {
        return withLock(uid, () -> {
            TradingStrategyVersion active = active(uid);
            StrategyParams base = StrategyParams.parse(active.getParams());
            if (base.autoUniverse()) {
                throw new IllegalArgumentException("v" + active.getVersion() + " 已经使用规则筛选股票池");
            }
            StrategyParams cand = base.copy();
            cand.setUniverseMode(StrategyParams.AUTO);
            cand.setUniverseSize(size);
            List<String> errors = cand.validate();
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException("参数不合法：" + String.join("；", errors));
            }
            Data data = loadData(List.of(base, cand), true);
            if (data.ranked() == null) {
                throw new IllegalArgumentException("规则股票池数据还没准备好，请先在「股票池」页更新");
            }
            Evaluation baseEval = evaluate(base, data);
            Evaluation candEval = evaluate(cand, data);
            int version = nextVersion(uid);
            Backtester.Metrics b = baseEval.outOfSample();
            Backtester.Metrics c = candEval.outOfSample();
            Backtester.Result bf = baseEval.full();
            Backtester.Result cf = candEval.full();
            JSONObject evaluation = new JSONObject()
                    .fluentPut("methodChange", true)
                    .fluentPut("baseline", baseEval.toJson().fluentPut("version", active.getVersion()))
                    .fluentPut("candidate", candEval.toJson().fluentPut("version", version))
                    .fluentPut("passedBacktest", null)
                    .fluentPut("adopted", true)
                    .fluentPut("note", "方法变更不经闸门：旧版本的手选股票池含事后挑选偏差，其回测不是公平基线");
            String meta = String.format(Locale.ROOT,
                    "方法变更：股票池改为规则筛选（不经闸门）。v%d 由 v%d 改为每月按规则筛选的前 %d 只，其余参数不变。按当月时点股票池回测：全期收益 %.2f%%、夏普 %.2f、回撤 %.2f%%，样本外夏普 %.2f；"
                            + "旧手选股票池同期 %.2f%%、%.2f、%.2f%%，样本外 %.2f（含事后挑选偏差，偏乐观）。原因：%s",
                    version, active.getVersion(), size, cf.returnPct(), cf.score(), cf.maxDrawdownPct(), c.score(),
                    bf.returnPct(), bf.score(), bf.maxDrawdownPct(), b.score(),
                    StringUtils.abbreviate(StringUtils.defaultIfBlank(reason, "消除手选股票池的幸存者和事后挑选偏差"), 200));
            TradingStrategyVersion row = new TradingStrategyVersion();
            row.setUid(uid);
            row.setVersion(version);
            row.setParentVersion(active.getVersion());
            row.setParams(cand.toJson());
            row.setRationale(StringUtils.abbreviate(meta, 2000));
            row.setStatus(TradingStrategyVersion.ACTIVE);
            row.setEvaluation(evaluation.toJSONString());
            transactionTemplate.executeWithoutResult(st -> {
                active.setStatus(TradingStrategyVersion.RETIRED);
                versionMapper.updateById(active);
                versionMapper.insert(row);
                lessonService.recordMeta(uid, meta, JSON.toJSONString(List.of("v" + active.getVersion(), "v" + version)),
                        "METHOD_CHANGE");
            });
            log.info("Strategy universe switched to AUTO, uid={}, from={}, to={}, size={}, autoReturn={}, autoSharpe={}, manualReturn={}, manualSharpe={}",
                    uid, active.getVersion(), version, size, Indicators.round(cf.returnPct(), 2),
                    Indicators.round(cf.score(), 2), Indicators.round(bf.returnPct(), 2), Indicators.round(bf.score(), 2));
            return new JSONObject()
                    .fluentPut("version", version)
                    .fluentPut("from", active.getVersion())
                    .fluentPut("evaluation", evaluation)
                    .fluentPut("recordedLesson", meta);
        });
    }

    /** Re-activates an earlier adopted version (RETIRED), e.g. when the live account does worse. */
    public JSONObject rollback(String uid, Integer version, String reason) {
        if (version == null) {
            throw new IllegalArgumentException("version 必填");
        }
        return withLock(uid, () -> {
            TradingStrategyVersion active = active(uid);
            if (Objects.equals(active.getVersion(), version)) {
                throw new IllegalArgumentException("v" + version + " 已经是当前版本");
            }
            TradingStrategyVersion target = version(uid, version);
            if (target == null || !TradingStrategyVersion.RETIRED.equals(target.getStatus())) {
                throw new IllegalArgumentException("只能回滚到曾经采纳过的版本（RETIRED），v" + version + " 不符合");
            }
            String meta = "从 v" + active.getVersion() + " 回滚到 v" + version + "。原因："
                    + StringUtils.abbreviate(StringUtils.defaultIfBlank(reason, "未说明"), 300);
            transactionTemplate.executeWithoutResult(s -> {
                active.setStatus(TradingStrategyVersion.RETIRED);
                versionMapper.updateById(active);
                target.setStatus(TradingStrategyVersion.ACTIVE);
                versionMapper.updateById(target);
                lessonService.recordMeta(uid, meta, JSON.toJSONString(List.of("v" + active.getVersion(), "v" + version)),
                        null);
            });
            log.info("Strategy rolled back, uid={}, from={}, to={}", uid, active.getVersion(), version);
            return new JSONObject().fluentPut("activeVersion", version).fluentPut("recordedLesson", meta);
        });
    }

    /**
     * Recent Shanghai Composite bars including today's during trading (market filter, custom rules).
     */
    public Series indexNow() {
        try {
            return market.calendarBars(300);
        } catch (AShareDataException e) {
            log.warn("Index data unavailable, market filter treated as passed: {}", e.getMessage());
            return null;
        }
    }

    /** Entry signals of the active strategy on the latest bars of its universe. */
    public JSONObject scan(String uid) {
        TradingStrategyVersion v = active(uid);
        StrategyParams p = StrategyParams.parse(v.getParams());
        Series index = indexNow();
        JSONArray hits = new JSONArray();
        JSONArray others = new JSONArray();
        List<String> failed = new ArrayList<>();
        for (String code : liveUniverse(p)) {
            try {
                JSONObject s = Backtester.latestSignal(p, market.dailyBars(code, true, 300), index);
                (Boolean.TRUE.equals(s.getBoolean("signal")) ? hits : others).add(s);
            } catch (AShareDataException e) {
                failed.add(code);
            }
        }
        return new JSONObject()
                .fluentPut("version", v.getVersion())
                .fluentPut("description", p.describe())
                .fluentPut("marketOk", index == null || Backtester.marketOkNow(index))
                .fluentPut("signals", hits)
                .fluentPut("noSignal", others)
                .fluentPut("failed", failed)
                .fluentPut("note", "信号按最新一根日线计算（盘中为未收盘数据）；下单前仍需结合基本面、资讯和账户风控。");
    }

    /**
     * The stocks a version trades now: in AUTO mode the top {@code universeSize} of the latest monthly
     * screen (its list until the first screen exists), else its list.
     */
    public List<String> liveUniverse(StrategyParams p) {
        if (p.autoUniverse()) {
            List<String> codes = universeService.liveTrade(p.getUniverseSize());
            if (!codes.isEmpty()) {
                return codes;
            }
            log.warn("Rule-based universe not ready, using the fallback list");
        }
        return p.getUniverse();
    }

    /**
     * Calendar, index, bars and (optionally) the validation pool's bars. With rule-based screens
     * ({@code ranked}: each month's passing stocks by turnover, point in time), AUTO versions trade the
     * top {@code universeSize} of each month and the pool is every passing stock of each month.
     */
    record Data(List<LocalDate> calendar, Series index, List<Series> series, List<Series> pool,
            Map<YearMonth, List<String>> ranked) {

        Data(List<LocalDate> calendar, Series index, List<Series> series, List<Series> pool) {
            this(calendar, index, series, pool, null);
        }

        /** The month-by-month trading universe of {@code p}, {@code null} for its list. */
        Map<YearMonth, Set<String>> tradeUniverse(StrategyParams p) {
            return ranked == null || !p.autoUniverse() ? null : top(ranked, p.getUniverseSize());
        }

        Map<YearMonth, Set<String>> poolUniverse() {
            return ranked == null ? null : top(ranked, Integer.MAX_VALUE);
        }

        static Map<YearMonth, Set<String>> top(Map<YearMonth, List<String>> ranked, int n) {
            Map<YearMonth, Set<String>> out = new LinkedHashMap<>();
            ranked.forEach((m, codes) -> out.put(m, new LinkedHashSet<>(codes.subList(0, Math.min(n, codes.size())))));
            return out;
        }
    }

    Data loadData(List<StrategyParams> versions, boolean withPool) {
        int limit = HISTORY_DAYS + Backtester.WARMUP_BARS + 20;
        Series index = barStore.indexBars(limit);
        List<LocalDate> calendar = new ArrayList<>(index.bars().stream().map(EastmoneyClient.Bar::date).toList());
        // Today's bar is incomplete until the close.
        LocalDateTime now = TradingClock.now();
        if (!calendar.isEmpty() && calendar.get(calendar.size() - 1).equals(now.toLocalDate())
                && now.toLocalTime().isBefore(LocalTime.of(15, 5))) {
            calendar.remove(calendar.size() - 1);
        }
        while (calendar.size() > HISTORY_DAYS) {
            calendar.remove(0);
        }
        if (calendar.size() < 200) {
            throw new AShareDataException("交易日历数据不足");
        }
        Map<YearMonth, List<String>> ranked = null;
        if (versions.stream().anyMatch(StrategyParams::autoUniverse)) {
            ranked = rankedMonths(calendar);
            if (ranked == null) {
                log.warn("Rule-based universe not ready, AUTO versions are backtested on their fallback list");
            }
        }
        Set<String> codes = new LinkedHashSet<>();
        for (StrategyParams v : versions) {
            if (!v.autoUniverse() || ranked == null) {
                codes.addAll(v.getUniverse());
            }
        }
        if (ranked != null) {
            ranked.values().forEach(codes::addAll);
        }
        long start = System.currentTimeMillis();
        List<Series> series = new ArrayList<>();
        Map<String, Series> loaded = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String code : codes) {
            try {
                Series s = barStore.dailyBars(code, true, limit);
                series.add(s);
                loaded.put(code, s);
            } catch (AShareDataException e) {
                // Both versions are compared on the same data, so a missing stock keeps it fair.
                missing.add(code);
            }
        }
        if (series.size() < StrategyParams.MIN_UNIVERSE) {
            throw new AShareDataException("股票池行情取不到（" + missing.size() + " 只失败）");
        }
        if (!missing.isEmpty()) {
            log.warn("Backtest data incomplete, missing={}", missing.size() > 20 ? missing.size() + " codes" : missing);
        }
        List<Series> pool = null;
        if (withPool && ranked != null) {
            pool = series;
        } else if (withPool) {
            pool = new ArrayList<>();
            for (String code : VALIDATION_POOL) {
                Series s = loaded.get(code);
                if (s == null) {
                    try {
                        s = barStore.dailyBars(code, true, limit);
                    } catch (RuntimeException e) {
                        log.warn("Validation pool stock skipped, code={}, msg={}", code, e.getMessage());
                        continue;
                    }
                }
                if (s.bars().size() >= 250) {
                    pool.add(s);
                }
            }
        }
        log.info("Backtest data loaded, days={}, series={}, missing={}, pointInTime={}, costMs={}", calendar.size(),
                series.size(), missing.size(), ranked != null, System.currentTimeMillis() - start);
        return new Data(List.copyOf(calendar), index, series, pool, ranked);
    }

    /**
     * Each backtest month's screen (point in time), or {@code null} when the screens are not available
     * or miss months of the calendar.
     */
    private Map<YearMonth, List<String>> rankedMonths(List<LocalDate> calendar) {
        Map<YearMonth, UniverseScreen.Ranked> screens = universeService.monthlyScreens();
        if (screens.isEmpty()) {
            return null;
        }
        Map<YearMonth, List<String>> out = new LinkedHashMap<>();
        int empty = 0;
        for (LocalDate d : calendar) {
            YearMonth m = YearMonth.from(d);
            if (out.containsKey(m)) {
                continue;
            }
            UniverseScreen.Ranked r = screens.get(m);
            List<String> codes = r == null ? List.of() : r.poolCodes();
            empty += codes.isEmpty() ? 1 : 0;
            out.put(m, codes);
        }
        if (empty > 2) {
            log.warn("Rule-based universe has {} empty months of {}", empty, out.size());
            return null;
        }
        return out;
    }

    /**
     * Full run, in-sample part, out-of-sample span and windows, regimes and (with a pool) the pool's
     * span.
     */
    static Evaluation evaluate(StrategyParams p, Data data) {
        List<LocalDate> cal = data.calendar();
        int n = cal.size();
        int oosDays = Math.min(OOS_DAYS, n * 36 / 100 / WINDOW_DAYS * WINDOW_DAYS);
        int windowsCount = Math.max(1, oosDays / WINDOW_DAYS);
        int split = n - windowsCount * WINDOW_DAYS;
        Backtester.Result full = Backtester.run(p, data.series(), data.index(), cal, 0, n - 1, BACKTEST_CASH,
                data.tradeUniverse(p));
        Backtester.Metrics is = Backtester.segment(full, 0, split - 1);
        Backtester.Metrics oos = Backtester.segment(full, split, n - 1);
        List<Backtester.Metrics> windows = new ArrayList<>();
        for (int w = 0; w < windowsCount; w++) {
            int from = split + w * WINDOW_DAYS;
            windows.add(Backtester.segment(full, from, w == windowsCount - 1 ? n - 1 : from + WINDOW_DAYS - 1));
        }
        Backtester.Metrics pool = null;
        if (data.pool() != null && !data.pool().isEmpty()) {
            StrategyParams pp = p.copy();
            pp.setUniverse(data.pool().stream().map(Series::code).toList());
            Backtester.Result pr = Backtester.run(pp, data.pool(), data.index(), cal, 0, n - 1, BACKTEST_CASH,
                    data.poolUniverse());
            pool = Backtester.segment(pr, split, n - 1);
        }
        return new Evaluation(full, is, oos, windows, regimes(full, data.index(), cal), pool);
    }

    /**
     * Average Sharpe of the run in each market regime: the history is cut into {@link #BLOCK_DAYS}-day
     * blocks labeled UP / DOWN / FLAT by the Shanghai Composite's move over the block
     * (±{@link #REGIME_MOVE_PCT}%).
     */
    static Map<String, RegimeStat> regimes(Backtester.Result full, Series index, List<LocalDate> cal) {
        Map<String, RegimeStat> out = new LinkedHashMap<>();
        if (index == null || full.days().isEmpty()) {
            return out;
        }
        Map<LocalDate, Double> close = Backtester.indexCloses(index);
        Map<String, double[]> acc = new LinkedHashMap<>();
        for (int from = 0; from + BLOCK_DAYS <= cal.size(); from += BLOCK_DAYS) {
            int to = from + BLOCK_DAYS - 1;
            Double a = from == 0 ? close.get(cal.get(0)) : close.get(cal.get(from - 1));
            Double b = close.get(cal.get(to));
            if (a == null || b == null || a == 0) {
                continue;
            }
            double move = (b / a - 1) * 100;
            String label = move > REGIME_MOVE_PCT ? "UP" : move < -REGIME_MOVE_PCT ? "DOWN" : "FLAT";
            double s = Backtester.segment(full, from, to).score();
            double[] v = acc.computeIfAbsent(label, k -> new double[2]);
            v[0] += s;
            v[1]++;
        }
        acc.forEach((k, v) -> out.put(k, new RegimeStat((int) v[1], v[0] / v[1])));
        return out;
    }

    private static JSONArray tradesJson(Backtester.Result r) {
        JSONArray out = new JSONArray();
        for (Backtester.Trade t : r.lastTrades()) {
            out.add(new JSONObject()
                    .fluentPut("code", t.code())
                    .fluentPut("entry", t.entryDate() + " @" + Indicators.round(t.entryPrice(), 3))
                    .fluentPut("exit", t.exitDate() + " @" + Indicators.round(t.exitPrice(), 3))
                    .fluentPut("qty", t.qty())
                    .fluentPut("pnl", Indicators.round(t.pnl(), 2))
                    .fluentPut("exitReason", t.exitReason()));
        }
        return out;
    }

    static String gateText() {
        return String.format(Locale.ROOT,
                "采纳流程：① 回测闸门——约 %d 个交易日（4 年）回测，最近 %d 天为样本外（%d 个 %d 日窗口）；样本外夏普需比当前版本高 %.1f（自动搜索 %.1f）且统计显著（z ≥ %.2f，搜索 %.2f），"
                        + "并按「历史上已检验过多少个候选」折减（折减后真实夏普 > 0 的概率需 ≥ 90%%，自动搜索 ≥ 95%%；搜得越多门槛越高，防止把运气当本事），"
                        + "窗口至少赢 %d 个且包括最近一个，上涨/下跌/震荡阶段都不明显变差，扩展验证池（规则筛选每月前 100 只，按当月时点；筛选数据未就绪时为约 60 只手选大盘股）上不变差，回撤恶化不超过 %.0f 个点，成交至少 %d 笔；"
                        + "② 前进测试——在之后至少 %d 个交易日的新行情上不差于当前版本，才正式采纳。每天最多 %d 次提案、%d 次自动搜索。",
                HISTORY_DAYS, OOS_DAYS, OOS_DAYS / WINDOW_DAYS, WINDOW_DAYS, MIN_IMPROVEMENT, MIN_IMPROVEMENT_SEARCH, MIN_Z,
                MIN_Z_SEARCH, MIN_WINDOWS_WON, MAX_DD_WORSENING, MIN_TRADES, FORWARD_DAYS, MAX_PROPOSALS_PER_DAY,
                MAX_SEARCHES_PER_DAY);
    }

    /** How many candidates have ever been measured against this user's out-of-sample data. */
    int trialCount(String uid) {
        Long n = trialMapper.selectCount(Wrappers.lambdaQuery(SearchTrial.class).eq(SearchTrial::getUid, uid));
        return n == null ? 0 : n.intValue();
    }

    /** Spread of the Sharpe ratios measured so far, for deflating a single proposal. */
    private double historicalSharpeSpread(String uid) {
        List<SearchTrial> rows = trialMapper.selectList(Wrappers.lambdaQuery(SearchTrial.class)
                .select(SearchTrial::getOosScore)
                .eq(SearchTrial::getUid, uid)
                .isNotNull(SearchTrial::getOosScore)
                .orderByDesc(SearchTrial::getId)
                .last("LIMIT 500"));
        return Statistics.sharpeSpread(rows.stream().map(SearchTrial::getOosScore).toList());
    }

    /**
     * Saves what a candidate scored. This is the record the deflation depends on, so it is written for
     * every candidate, not only the winners.
     */
    private void recordTrial(String uid, StrategyParams cand, StrategyParams base, Integer baseVersion,
            Evaluation baseEval, Evaluation eval, double deflated, int trials, boolean passed, String origin) {
        try {
            SearchTrial row = new SearchTrial();
            row.setUid(uid);
            row.setParamsHash(hash(cand.toJson()));
            row.setChanges(StringUtils.abbreviate(cand.diff(base), 500));
            row.setFields(StringUtils.abbreviate(changedFields(cand, base), 255));
            row.setBaseVersion(baseVersion);
            row.setOosScore(Indicators.round(eval.outOfSample().score(), 4));
            row.setOosReturnPct(Indicators.round(eval.outOfSample().returnPct(), 3));
            row.setSignificanceZ(Indicators.round(z(baseEval, eval), 3));
            row.setDeflatedSharpe(Double.isNaN(deflated) ? null : Indicators.round(deflated, 4));
            row.setTrialsAtTime(trials);
            row.setPassed(passed ? 1 : 0);
            row.setOrigin(origin);
            trialMapper.insert(row);
        } catch (RuntimeException e) {
            log.warn("Search trial not recorded, uid={}, errorType={}, msg={}", uid, e.getClass().getSimpleName(),
                    e.getMessage());
        }
    }

    /** Names of the fields a candidate changed, for per-field statistics later. */
    static String changedFields(StrategyParams cand, StrategyParams base) {
        JSONObject a = JSONObject.from(base);
        JSONObject b = JSONObject.from(cand);
        List<String> out = new ArrayList<>();
        for (String key : StrategyParams.FIELD_ORDER) {
            if (!String.valueOf(a.get(key)).equals(String.valueOf(b.get(key)))) {
                out.add(key);
            }
        }
        return String.join(",", out);
    }

    private static String hash(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private int nextVersion(String uid) {
        TradingStrategyVersion last = versionMapper.selectOne(Wrappers.lambdaQuery(TradingStrategyVersion.class)
                .eq(TradingStrategyVersion::getUid, uid)
                .orderByDesc(TradingStrategyVersion::getVersion)
                .last("LIMIT 1"));
        return last == null ? 1 : last.getVersion() + 1;
    }

    private <T> T withLock(String uid, Supplier<T> action) {
        String key = "ashare:strategy:" + uid;
        String token = UUID.randomUUID().toString();
        if (!redisUtil.tryLock(key, 600, token)) {
            throw new IllegalArgumentException("另一个策略提案或搜索正在评估，请稍后再试");
        }
        try {
            return action.get();
        } finally {
            redisUtil.unlock(key, token);
        }
    }
}
