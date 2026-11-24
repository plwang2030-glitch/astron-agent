package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.hub.entity.ashare.TradingStrategyVersion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Evolution that does not wait for an agent: the weekly automatic strategy search and the monthly
 * check that rolls a newly adopted version back when it does clearly worse live than in its
 * backtest.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoEvolutionService {

    /** A version must have run this many trading days live before it can be judged. */
    static final int MIN_LIVE_DAYS = 20;

    private final StrategyService strategyService;
    private final PaperTradingService paperTradingService;

    public JSONObject weeklySearch(String uid, String period) {
        try {
            JSONObject r = strategyService.autoSearch(uid, period, "每周定时");
            log.info("Weekly auto search, uid={}, period={}, adopted={}", uid, period, r.get("adopted") != null);
            return r;
        } catch (RuntimeException e) {
            log.warn("Weekly auto search failed, uid={}, period={}, errorType={}, msg={}", uid, period,
                    e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /** Forward test of the user's trial versions after each trading day. */
    public JSONObject dailyTrials(String uid) {
        try {
            return strategyService.evaluateTrials(uid);
        } catch (RuntimeException e) {
            log.warn("Trial evaluation failed, uid={}, errorType={}, msg={}", uid, e.getClass().getSimpleName(),
                    e.getMessage());
            return null;
        }
    }

    /**
     * Rolls the active version back to its parent when, after at least {@link #MIN_LIVE_DAYS} trading
     * days live, its live drawdown exceeds {@link #rollbackLimit} of the out-of-sample drawdown it was
     * adopted with. Returns the rollback result, or {@code null} when nothing was done.
     */
    public JSONObject monthlyRollbackCheck(String uid) {
        TradingStrategyVersion active = strategyService.active(uid);
        if (active.getParentVersion() == null || active.getCreateTime() == null
                || StringUtils.isBlank(active.getEvaluation())) {
            return null;
        }
        JSONObject eval = JSON.parseObject(active.getEvaluation());
        JSONObject oos = eval.getJSONObject("candidate") == null ? null
                : eval.getJSONObject("candidate").getJSONObject("outOfSample");
        if (oos == null || oos.get("maxDrawdownPct") == null) {
            return null;
        }
        LocalDate since = active.getCreateTime().toLocalDate();
        JSONObject live = paperTradingService.periodStats(uid, since, TradingClock.now().toLocalDate());
        int days = live.getIntValue("days");
        double liveDd = live.getDoubleValue("maxDrawdownPct");
        double backtestDd = oos.getDoubleValue("maxDrawdownPct");
        double limit = rollbackLimit(backtestDd);
        log.info("Rollback check, uid={}, version={}, liveDays={}, liveDd={}, backtestDd={}, limit={}", uid,
                active.getVersion(), days, liveDd, backtestDd, limit);
        if (days < MIN_LIVE_DAYS || liveDd <= limit) {
            return null;
        }
        TradingStrategyVersion parent = strategyService.version(uid, active.getParentVersion());
        if (parent == null || !TradingStrategyVersion.RETIRED.equals(parent.getStatus())) {
            return null;
        }
        String reason = String.format(Locale.ROOT,
                "自动回滚：v%d 实盘 %d 个交易日最大回撤 %.2f%%，超过其样本外回测回撤 %.2f%% 的容忍上限 %.2f%%（实盘收益 %s%%）", active.getVersion(),
                days, liveDd, backtestDd, limit, live.get("returnPct"));
        return strategyService.rollback(uid, parent.getVersion(), reason);
    }

    /** Live drawdown tolerated for a version: 1.5 × its backtest drawdown, at least 5 points more. */
    static double rollbackLimit(double backtestDrawdownPct) {
        return Math.max(backtestDrawdownPct * 1.5, backtestDrawdownPct + 5);
    }
}
