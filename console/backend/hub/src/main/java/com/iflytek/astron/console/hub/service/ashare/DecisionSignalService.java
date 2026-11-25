package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iflytek.astron.console.hub.entity.ashare.DecisionSignal;
import com.iflytek.astron.console.hub.entity.ashare.SignalOutcome;
import com.iflytek.astron.console.hub.mapper.ashare.DecisionSignalMapper;
import com.iflytek.astron.console.hub.mapper.ashare.SignalOutcomeMapper;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Bar;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Series;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Makes the agents' judgement measurable, the way the quant strategy already is.
 * <p>
 * An agent records every actionable opinion as a signal: what to do, over how many trading days, at
 * what prices, and why. After the window has passed, {@link #evaluateDue} compares it with the
 * forward-adjusted bars and marks it HIT or MISS; an opinion that cannot be checked becomes UNABLE
 * instead of being quietly dropped. {@link #stats} then reports the hit rate per action, horizon,
 * market phase, source and data quality, and refuses to draw a conclusion from fewer than
 * {@link #MIN_SAMPLE} evaluated signals.
 * <p>
 * The win condition matches what the action actually claims: a buy must rise by more than
 * {@link #NEUTRAL_BAND}, a sell or avoid must not rise by it, and hold must not fall by it. Signals
 * that only ask for attention (WATCH, ALERT) are recorded but never scored, since no price move
 * would prove them right or wrong.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionSignalService {

    /** A move smaller than this counts as flat, so noise does not decide the outcome. */
    public static final double NEUTRAL_BAND = 2.0;
    /** Below this many evaluated signals a group's hit rate is shown but no conclusion is drawn. */
    public static final int MIN_SAMPLE = 30;
    static final Set<String> ACTIONS = Set.of(DecisionSignal.BUY, DecisionSignal.ADD, DecisionSignal.HOLD,
            DecisionSignal.WATCH, DecisionSignal.REDUCE, DecisionSignal.SELL, DecisionSignal.AVOID,
            DecisionSignal.ALERT);
    static final Map<String, Integer> HORIZONS = new LinkedHashMap<>();
    /** Actions that no price move can prove right or wrong. */
    static final Set<String> NOT_SCORED = Set.of(DecisionSignal.WATCH, DecisionSignal.ALERT);
    private static final int MAX_PER_DAY = 40;
    private static final int MAX_EVAL_PER_RUN = 200;

    static {
        HORIZONS.put("1d", 1);
        HORIZONS.put("3d", 3);
        HORIZONS.put("5d", 5);
        HORIZONS.put("10d", 10);
    }

    private final DecisionSignalMapper signalMapper;
    private final SignalOutcomeMapper outcomeMapper;
    private final BarStore barStore;
    private final MarketPackService packService;
    private final LessonService lessonService;
    private final TransactionTemplate transactionTemplate;

    // ------------------------------------------------------------------ recording

    /** What an agent states when it records an opinion. */
    public record SignalRequest(String code, String action, String horizon, Double confidence, BigDecimal entryLow,
            BigDecimal entryHigh, BigDecimal stopLoss, BigDecimal targetPrice, String reason, String risk,
            String watch, String source, Integer botId, Long reportId) {}

    /**
     * Saves one opinion. The anchor is the latest settled trading day, so the evaluation starts from a
     * price that was already final when the opinion was given; an opinion given during trading is
     * therefore judged from the previous close, never from a price the agent could not have seen.
     * Recording the same action for the same stock again on the same day supersedes the earlier one.
     */
    public JSONObject record(String uid, SignalRequest req) {
        String code = AShareRules.normalizeCode(req.code());
        String action = StringUtils.upperCase(StringUtils.trimToEmpty(req.action()), Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            throw new IllegalArgumentException("action 只能是 BUY / ADD / HOLD / WATCH / REDUCE / SELL / AVOID / ALERT");
        }
        String horizon = StringUtils.lowerCase(StringUtils.defaultIfBlank(req.horizon(), "5d"), Locale.ROOT);
        if (!HORIZONS.containsKey(horizon)) {
            throw new IllegalArgumentException("horizon 只能是 1d / 3d / 5d / 10d");
        }
        if (StringUtils.length(StringUtils.trimToEmpty(req.reason())) < 10) {
            throw new IllegalArgumentException("reason 必填：至少 10 字，写清依据（技术面、资金面、消息面或引用的经验 #id）");
        }
        LocalDate today = TradingClock.now().toLocalDate();
        long todayCount = signalMapper.selectCount(Wrappers.lambdaQuery(DecisionSignal.class)
                .eq(DecisionSignal::getUid, uid)
                .ge(DecisionSignal::getCreateTime, today.atStartOfDay()));
        if (todayCount >= MAX_PER_DAY) {
            throw new IllegalArgumentException("今天已记录 " + todayCount + " 条建议，达到上限 " + MAX_PER_DAY);
        }
        MarketPackService.StockPack pack = packService.stock(code, false);
        LocalDate anchorDate = null;
        BigDecimal anchorPrice = null;
        if (pack.series() != null && !pack.series().bars().isEmpty()) {
            List<Bar> bars = settled(pack.series().bars(), pack.phase());
            if (!bars.isEmpty()) {
                Bar last = bars.get(bars.size() - 1);
                anchorDate = last.date();
                anchorPrice = BigDecimal.valueOf(last.close()).setScale(3, RoundingMode.HALF_UP);
            }
        }
        if (anchorDate == null) {
            throw new AShareDataException("取不到 " + code + " 的日线，无法记录可评估的建议");
        }
        DecisionSignal row = new DecisionSignal();
        row.setUid(uid);
        row.setCode(code);
        row.setName(pack.name());
        row.setAction(action);
        row.setHorizon(horizon);
        row.setConfidence(req.confidence() == null ? null
                : BigDecimal.valueOf(Math.max(0, Math.min(1, req.confidence()))).setScale(2, RoundingMode.HALF_UP));
        row.setEntryLow(req.entryLow());
        row.setEntryHigh(req.entryHigh());
        row.setStopLoss(req.stopLoss());
        row.setTargetPrice(req.targetPrice());
        row.setReason(StringUtils.abbreviate(req.reason().trim(), 1000));
        row.setRisk(StringUtils.abbreviate(StringUtils.trimToNull(req.risk()), 500));
        row.setWatch(StringUtils.abbreviate(StringUtils.trimToNull(req.watch()), 500));
        row.setSource(DecisionSignal.REVIEW.equals(req.source()) ? DecisionSignal.REVIEW : DecisionSignal.AGENT);
        row.setBotId(req.botId());
        row.setReportId(req.reportId());
        row.setMarketPhase(pack.phase().name());
        row.setDataQuality(pack.quality().level().name());
        row.setPlanQuality(planQuality(row));
        row.setAnchorDate(anchorDate);
        row.setAnchorPrice(anchorPrice);
        row.setStatus(DecisionSignal.PENDING);
        // Earlier opinions on the same stock and day that this one replaces: the identical call (a
        // restatement) and any call in the opposite direction. Without the second rule a team could
        // record both a buy and a sell and look right whichever way the price went.
        List<DecisionSignal> replaced = signalMapper.selectList(Wrappers.lambdaQuery(DecisionSignal.class)
                .eq(DecisionSignal::getUid, uid)
                .eq(DecisionSignal::getCode, code)
                .eq(DecisionSignal::getAnchorDate, anchorDate)
                .eq(DecisionSignal::getStatus, DecisionSignal.PENDING))
                .stream()
                .filter(old -> supersedes(action, horizon, old))
                .toList();
        transactionTemplate.executeWithoutResult(s -> {
            for (DecisionSignal old : replaced) {
                DecisionSignal patch = new DecisionSignal();
                patch.setId(old.getId());
                patch.setStatus(DecisionSignal.SUPERSEDED);
                signalMapper.updateById(patch);
            }
            signalMapper.insert(row);
        });
        log.info("Decision signal recorded, uid={}, id={}, code={}, action={}, horizon={}, anchor={} @{}, quality={}, superseded={}",
                uid, row.getId(), code, action, horizon, anchorDate, anchorPrice, row.getDataQuality(), replaced.size());
        return json(row).fluentPut("note", "将在 " + horizon + "（交易日）后按前复权收盘价自动评估；"
                + (NOT_SCORED.contains(action) ? "WATCH / ALERT 只记录、不计入命中率。" : "评估口径见 stats 的 rule。"));
    }

    /**
     * Whether a new opinion replaces an earlier pending one on the same stock and day: the same call
     * restated, or a call in the opposite direction (the team changed its mind, so only the latest
     * stands). Opinions in the same direction over different windows are separate claims and both stay.
     */
    static boolean supersedes(String action, String horizon, DecisionSignal old) {
        if (action.equals(old.getAction()) && horizon.equals(old.getHorizon())) {
            return true;
        }
        int now = direction(action);
        int before = direction(old.getAction());
        return now != 0 && before != 0 && now != before;
    }

    /** +1 bullish, -1 bearish, 0 neither (hold, watch, alert). */
    static int direction(String action) {
        return switch (StringUtils.defaultString(action)) {
            case DecisionSignal.BUY, DecisionSignal.ADD -> 1;
            case DecisionSignal.SELL, DecisionSignal.REDUCE, DecisionSignal.AVOID -> -1;
            default -> 0;
        };
    }

    /**
     * How complete the plan is: an entry range, a stop loss, a target and what to watch are the four
     * parts of an actionable call.
     */
    static String planQuality(DecisionSignal s) {
        int parts = 0;
        parts += s.getEntryLow() != null || s.getEntryHigh() != null ? 1 : 0;
        parts += s.getStopLoss() != null ? 1 : 0;
        parts += s.getTargetPrice() != null ? 1 : 0;
        parts += StringUtils.isNotBlank(s.getWatch()) ? 1 : 0;
        return parts >= 4 ? "COMPLETE" : parts >= 2 ? "PARTIAL" : "MINIMAL";
    }

    /** Bars whose day is final: during trading today's bar is still moving, so it is dropped. */
    private static List<Bar> settled(List<Bar> bars, TradingClock.Phase phase) {
        if (bars.isEmpty() || phase.dayComplete()) {
            return bars;
        }
        LocalDate today = TradingClock.now().toLocalDate();
        return bars.get(bars.size() - 1).date().equals(today) ? bars.subList(0, bars.size() - 1) : bars;
    }

    // ------------------------------------------------------------------ evaluation

    /**
     * Evaluates every signal whose window has passed. Runs after the close; a signal whose stock has no
     * usable bars yet stays PENDING and is retried the next day.
     */
    public JSONObject evaluateDue(String uid) {
        List<DecisionSignal> pending = signalMapper.selectList(Wrappers.lambdaQuery(DecisionSignal.class)
                .eq(DecisionSignal::getUid, uid)
                .eq(DecisionSignal::getStatus, DecisionSignal.PENDING)
                .orderByAsc(DecisionSignal::getAnchorDate)
                .last("LIMIT " + MAX_EVAL_PER_RUN));
        int hit = 0;
        int miss = 0;
        int unable = 0;
        int waiting = 0;
        for (DecisionSignal s : pending) {
            try {
                String result = evaluate(s);
                switch (StringUtils.defaultString(result)) {
                    case DecisionSignal.HIT -> hit++;
                    case DecisionSignal.MISS -> miss++;
                    case DecisionSignal.UNABLE -> unable++;
                    default -> waiting++;
                }
            } catch (RuntimeException e) {
                waiting++;
                log.warn("Signal evaluation failed, uid={}, id={}, errorType={}, msg={}", uid, s.getId(),
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }
        if (hit + miss + unable > 0) {
            log.info("Decision signals evaluated, uid={}, hit={}, miss={}, unable={}, stillWaiting={}", uid, hit, miss,
                    unable, waiting);
        }
        return new JSONObject()
                .fluentPut("pending", pending.size())
                .fluentPut("hit", hit)
                .fluentPut("miss", miss)
                .fluentPut("unable", unable)
                .fluentPut("waiting", waiting);
    }

    /** Evaluates one signal; returns its new status, or {@code null} when the window is not over. */
    String evaluate(DecisionSignal s) {
        int days = HORIZONS.getOrDefault(s.getHorizon(), 5);
        if (NOT_SCORED.contains(s.getAction())) {
            return finish(s, DecisionSignal.UNABLE, null, null, "WATCH / ALERT 不按涨跌判定对错，只作为关注记录");
        }
        Series series = barStore.stored(s.getCode(), true, 400);
        List<Bar> bars = series.bars();
        if (bars.isEmpty()) {
            try {
                bars = barStore.dailyBars(s.getCode(), true, 400).bars();
            } catch (RuntimeException e) {
                return null;
            }
        }
        int anchor = -1;
        for (int i = 0; i < bars.size(); i++) {
            if (bars.get(i).date().equals(s.getAnchorDate())) {
                anchor = i;
                break;
            }
        }
        if (anchor < 0) {
            // The anchor day is outside the stored window (very old signal): it can never be evaluated.
            if (!bars.isEmpty() && bars.get(0).date().isAfter(s.getAnchorDate())) {
                return finish(s, DecisionSignal.UNABLE, null, null, "锚定日 " + s.getAnchorDate() + " 已超出本地行情区间");
            }
            return null;
        }
        if (anchor + days >= bars.size()) {
            return null;
        }
        List<Bar> window = bars.subList(anchor + 1, anchor + days + 1);
        double anchorPrice = bars.get(anchor).close();
        if (anchorPrice <= 0) {
            return finish(s, DecisionSignal.UNABLE, null, null, "锚定日收盘价无效");
        }
        Bar end = window.get(window.size() - 1);
        double minLow = window.stream().mapToDouble(Bar::low).min().orElse(end.low());
        double maxHigh = window.stream().mapToDouble(Bar::high).max().orElse(end.high());
        double ret = (end.close() / anchorPrice - 1) * 100;
        boolean bullish = DecisionSignal.BUY.equals(s.getAction()) || DecisionSignal.ADD.equals(s.getAction());
        boolean bearish = DecisionSignal.SELL.equals(s.getAction()) || DecisionSignal.REDUCE.equals(s.getAction())
                || DecisionSignal.AVOID.equals(s.getAction());
        boolean ok;
        String detail;
        if (bullish) {
            ok = ret >= NEUTRAL_BAND;
            detail = String.format(Locale.ROOT, "看多：%d 个交易日后 %+.2f%%，需 ≥ +%.0f%%", days, ret, NEUTRAL_BAND);
        } else if (bearish) {
            ok = ret <= NEUTRAL_BAND;
            detail = String.format(Locale.ROOT, "看空/回避：%d 个交易日后 %+.2f%%，需 ≤ +%.0f%%", days, ret, NEUTRAL_BAND);
        } else {
            ok = ret >= -NEUTRAL_BAND;
            detail = String.format(Locale.ROOT, "持有：%d 个交易日后 %+.2f%%，需 ≥ -%.0f%%", days, ret, NEUTRAL_BAND);
        }
        // Worst move against the opinion inside the window, so a right call that first hurt is visible.
        double adverse = bullish ? (anchorPrice - minLow) / anchorPrice * 100
                : bearish ? (maxHigh - anchorPrice) / anchorPrice * 100
                        : (anchorPrice - minLow) / anchorPrice * 100;
        SignalOutcome outcome = new SignalOutcome();
        outcome.setSignalId(s.getId());
        outcome.setUid(s.getUid());
        outcome.setCode(s.getCode());
        outcome.setHorizon(s.getHorizon());
        outcome.setAnchorDate(s.getAnchorDate());
        outcome.setAnchorPrice(BigDecimal.valueOf(anchorPrice).setScale(3, RoundingMode.HALF_UP));
        outcome.setEndDate(end.date());
        outcome.setEndPrice(BigDecimal.valueOf(end.close()).setScale(3, RoundingMode.HALF_UP));
        outcome.setMinLow(BigDecimal.valueOf(minLow).setScale(3, RoundingMode.HALF_UP));
        outcome.setMaxHigh(BigDecimal.valueOf(maxHigh).setScale(3, RoundingMode.HALF_UP));
        outcome.setReturnPct(BigDecimal.valueOf(ret).setScale(3, RoundingMode.HALF_UP));
        outcome.setMaxAdversePct(BigDecimal.valueOf(Math.max(0, adverse)).setScale(3, RoundingMode.HALF_UP));
        return finish(s, ok ? DecisionSignal.HIT : DecisionSignal.MISS, outcome, end.date(), detail);
    }

    private String finish(DecisionSignal s, String status, SignalOutcome outcome, LocalDate endDate, String detail) {
        SignalOutcome row = outcome == null ? new SignalOutcome() : outcome;
        if (outcome == null) {
            row.setSignalId(s.getId());
            row.setUid(s.getUid());
            row.setCode(s.getCode());
            row.setHorizon(s.getHorizon());
            row.setAnchorDate(s.getAnchorDate());
            row.setAnchorPrice(s.getAnchorPrice() == null ? BigDecimal.ZERO : s.getAnchorPrice());
            row.setEndDate(endDate);
        }
        row.setResult(status);
        row.setDetail(StringUtils.abbreviate(detail, 500));
        row.setAction(s.getAction());
        row.setMarketPhase(s.getMarketPhase());
        row.setSource(s.getSource());
        row.setDataQuality(s.getDataQuality());
        row.setPlanQuality(s.getPlanQuality());
        transactionTemplate.executeWithoutResult(t -> {
            DecisionSignal patch = new DecisionSignal();
            patch.setId(s.getId());
            patch.setStatus(status);
            signalMapper.updateById(patch);
            outcomeMapper.delete(Wrappers.lambdaQuery(SignalOutcome.class).eq(SignalOutcome::getSignalId, s.getId()));
            outcomeMapper.insert(row);
        });
        return status;
    }

    // ------------------------------------------------------------------ statistics

    /**
     * Hit rate overall and per group. A group with fewer than {@link #MIN_SAMPLE} evaluated signals
     * reports its counts but no conclusion, because a handful of calls says nothing.
     */
    public JSONObject stats(String uid) {
        List<SignalOutcome> all = outcomeMapper.selectList(Wrappers.lambdaQuery(SignalOutcome.class)
                .eq(SignalOutcome::getUid, uid)
                .orderByDesc(SignalOutcome::getId)
                .last("LIMIT 3000"));
        List<SignalOutcome> scored = all.stream().filter(o -> !DecisionSignal.UNABLE.equals(o.getResult())).toList();
        long pending = signalMapper.selectCount(Wrappers.lambdaQuery(DecisionSignal.class)
                .eq(DecisionSignal::getUid, uid)
                .eq(DecisionSignal::getStatus, DecisionSignal.PENDING));
        JSONObject out = new JSONObject()
                .fluentPut("evaluated", scored.size())
                .fluentPut("unable", all.size() - scored.size())
                .fluentPut("pending", pending)
                .fluentPut("minSample", MIN_SAMPLE)
                .fluentPut("rule", String.format(Locale.ROOT,
                        "按前复权收盘价，锚定日为建议记录时最后一个已收盘交易日；中性带 ±%.0f%%：看多需涨幅 ≥ %.0f%%，看空/回避需涨幅 ≤ %.0f%%，"
                                + "持有需跌幅 < %.0f%%；WATCH / ALERT 不计入。样本少于 %d 条只看不下结论。",
                        NEUTRAL_BAND, NEUTRAL_BAND, NEUTRAL_BAND, NEUTRAL_BAND, MIN_SAMPLE))
                .fluentPut("overall", group(scored));
        out.put("byAction", groupBy(scored, SignalOutcome::getAction));
        out.put("byHorizon", groupBy(scored, SignalOutcome::getHorizon));
        out.put("byMarketPhase", groupBy(scored, SignalOutcome::getMarketPhase));
        out.put("bySource", groupBy(scored, SignalOutcome::getSource));
        out.put("byDataQuality", groupBy(scored, SignalOutcome::getDataQuality));
        out.put("byPlanQuality", groupBy(scored, SignalOutcome::getPlanQuality));
        return out;
    }

    private static JSONArray groupBy(List<SignalOutcome> rows, java.util.function.Function<SignalOutcome, String> key) {
        Map<String, List<SignalOutcome>> groups = new LinkedHashMap<>();
        for (SignalOutcome o : rows) {
            groups.computeIfAbsent(StringUtils.defaultIfBlank(key.apply(o), "UNKNOWN"), k -> new ArrayList<>()).add(o);
        }
        List<Map.Entry<String, List<SignalOutcome>>> sorted = new ArrayList<>(groups.entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<String, List<SignalOutcome>> e) -> -e.getValue().size()));
        JSONArray out = new JSONArray();
        for (var e : sorted) {
            out.add(group(e.getValue()).fluentPut("key", e.getKey()));
        }
        return out;
    }

    private static JSONObject group(List<SignalOutcome> rows) {
        int n = rows.size();
        long hit = rows.stream().filter(o -> DecisionSignal.HIT.equals(o.getResult())).count();
        double avgRet = rows.stream()
                .filter(o -> o.getReturnPct() != null)
                .mapToDouble(o -> o.getReturnPct().doubleValue())
                .average()
                .orElse(0);
        double avgAdverse = rows.stream()
                .filter(o -> o.getMaxAdversePct() != null)
                .mapToDouble(o -> o.getMaxAdversePct().doubleValue())
                .average()
                .orElse(0);
        JSONObject out = new JSONObject()
                .fluentPut("samples", n)
                .fluentPut("hits", hit)
                .fluentPut("enough", n >= MIN_SAMPLE);
        if (n > 0) {
            out.fluentPut("hitRatePct", Math.round(hit * 1000.0 / n) / 10.0)
                    .fluentPut("avgReturnPct", Math.round(avgRet * 100) / 100.0)
                    .fluentPut("avgMaxAdversePct", Math.round(avgAdverse * 100) / 100.0);
        }
        if (n < MIN_SAMPLE) {
            out.put("note", "样本 " + n + " 条，少于 " + MIN_SAMPLE + " 条，仅供观察，不能当作结论");
        }
        return out;
    }

    /** Signals, newest first; {@code status} and {@code code} are optional filters. */
    public JSONArray list(String uid, String status, String code, int limit) {
        var q = Wrappers.lambdaQuery(DecisionSignal.class).eq(DecisionSignal::getUid, uid);
        if (StringUtils.isNotBlank(status)) {
            q.eq(DecisionSignal::getStatus, StringUtils.upperCase(status.trim(), Locale.ROOT));
        }
        if (StringUtils.isNotBlank(code)) {
            q.eq(DecisionSignal::getCode, AShareRules.normalizeCode(code));
        }
        List<DecisionSignal> rows = signalMapper.selectList(q
                .orderByDesc(DecisionSignal::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 100))));
        Map<Long, SignalOutcome> outcomes = new LinkedHashMap<>();
        if (!rows.isEmpty()) {
            outcomeMapper.selectList(Wrappers.lambdaQuery(SignalOutcome.class)
                    .in(SignalOutcome::getSignalId, rows.stream().map(DecisionSignal::getId).toList()))
                    .forEach(o -> outcomes.put(o.getSignalId(), o));
        }
        JSONArray out = new JSONArray();
        for (DecisionSignal s : rows) {
            JSONObject row = json(s);
            SignalOutcome o = outcomes.get(s.getId());
            if (o != null) {
                row.put("outcome", new JSONObject()
                        .fluentPut("result", o.getResult())
                        .fluentPut("endDate", o.getEndDate() == null ? null : o.getEndDate().toString())
                        .fluentPut("endPrice", o.getEndPrice())
                        .fluentPut("returnPct", o.getReturnPct())
                        .fluentPut("maxAdversePct", o.getMaxAdversePct())
                        .fluentPut("detail", o.getDetail()));
            }
            out.add(row);
        }
        return out;
    }

    static JSONObject json(DecisionSignal s) {
        return new JSONObject()
                .fluentPut("signalId", s.getId())
                .fluentPut("code", s.getCode())
                .fluentPut("name", s.getName())
                .fluentPut("action", s.getAction())
                .fluentPut("horizon", s.getHorizon())
                .fluentPut("confidence", s.getConfidence())
                .fluentPut("entryLow", s.getEntryLow())
                .fluentPut("entryHigh", s.getEntryHigh())
                .fluentPut("stopLoss", s.getStopLoss())
                .fluentPut("targetPrice", s.getTargetPrice())
                .fluentPut("reason", s.getReason())
                .fluentPut("risk", s.getRisk())
                .fluentPut("watch", s.getWatch())
                .fluentPut("source", s.getSource())
                .fluentPut("marketPhase", s.getMarketPhase())
                .fluentPut("dataQuality", s.getDataQuality())
                .fluentPut("planQuality", s.getPlanQuality())
                .fluentPut("anchorDate", s.getAnchorDate() == null ? null : s.getAnchorDate().toString())
                .fluentPut("anchorPrice", s.getAnchorPrice())
                .fluentPut("status", s.getStatus())
                .fluentPut("createTime", String.valueOf(s.getCreateTime()));
    }

    /**
     * Turns the evaluated signals into one META lesson after the close, so the review agent sees how
     * its own calls have done and the evolution loop has evidence about the analysis side too. Only
     * written when there is something new to say.
     */
    public void recordStatsLesson(String uid) {
        JSONObject stats = stats(uid);
        int evaluated = stats.getIntValue("evaluated");
        if (evaluated < 5) {
            return;
        }
        JSONObject overall = stats.getJSONObject("overall");
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "智能体建议后验：已评估 %d 条，命中率 %.1f%%，平均 %+.2f%%，平均最大逆向波动 %.2f%%%s。",
                evaluated, overall.getDoubleValue("hitRatePct"), overall.getDoubleValue("avgReturnPct"),
                overall.getDoubleValue("avgMaxAdversePct"),
                evaluated < MIN_SAMPLE ? "（样本不足 " + MIN_SAMPLE + " 条，仅供观察）" : ""));
        JSONArray byAction = stats.getJSONArray("byAction");
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < byAction.size() && i < 4; i++) {
            JSONObject g = byAction.getJSONObject(i);
            parts.add(String.format(Locale.ROOT, "%s %d 条命中 %.0f%%", g.getString("key"), g.getIntValue("samples"),
                    g.getDoubleValue("hitRatePct")));
        }
        if (!parts.isEmpty()) {
            sb.append("按动作：").append(String.join("，", parts)).append("。");
        }
        JSONArray byQuality = stats.getJSONArray("byDataQuality");
        for (int i = 0; i < byQuality.size(); i++) {
            JSONObject g = byQuality.getJSONObject(i);
            if ("POOR".equals(g.getString("key")) && g.getIntValue("samples") >= 5) {
                sb.append(String.format(Locale.ROOT, "数据质量差时的 %d 条命中率 %.0f%%，应更谨慎。", g.getIntValue("samples"),
                        g.getDoubleValue("hitRatePct")));
            }
        }
        String content = StringUtils.abbreviate(sb.toString(), 1000);
        lessonService.recordMeta(uid, content, null, "SIGNAL_STATS");
        log.info("Signal stats lesson recorded, uid={}, evaluated={}", uid, evaluated);
    }
}
