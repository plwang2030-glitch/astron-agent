package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.hub.entity.ashare.TradingLesson;
import com.iflytek.astron.console.hub.entity.ashare.TradingStrategyVersion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Builds the "trading memory" section added to the system prompt of agents with the {@code a_share}
 * tool group: the active strategy version, lessons that passed the evidence threshold, the user's
 * preferences, the latest evolution attempts and the account state. This is how what the agent
 * learned changes its next decisions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AShareContextService {

    private final StrategyService strategyService;
    private final LessonService lessonService;
    private final PaperTradingService paperTradingService;
    private final MarketPackService packService;
    private final DecisionSignalService signalService;

    public String contextPrompt(String uid) {
        try {
            StringBuilder sb = new StringBuilder("\n\n## A股模拟交易记忆（系统每轮自动更新）\n");
            TradingStrategyVersion v = strategyService.active(uid);
            StrategyParams p = StrategyParams.parse(v.getParams());
            sb.append("- 当前策略 v").append(v.getVersion()).append("：").append(p.describe()).append('\n');
            sb.append(p.autoUniverse() ? "  股票池（规则筛选，每月更新，可用 a_share_universe 查看规则和名单）：" : "  股票池：")
                    .append(String.join("、", strategyService.liveUniverse(p)))
                    .append('\n');
            List<String> watch = paperTradingService.watchlist(uid);
            if (!watch.isEmpty()) {
                sb.append("  用户自选（14:50 同样按策略信号")
                        .append(paperTradingService.watchlistAutoBuy(uid) ? "自动买入" : "只提示、不自动买入")
                        .append("；用 paper_trade 的 watchlist 查看信号）：")
                        .append(String.join("、", watch))
                        .append('\n');
            }
            JSONObject brief = paperTradingService.brief(uid);
            sb.append("- 账户：可用现金 ")
                    .append(brief.get("cash"))
                    .append("，冻结 ")
                    .append(brief.getOrDefault("frozenCash", 0))
                    .append("，持仓 ")
                    .append(brief.get("positions"))
                    .append("，待成交 ")
                    .append(brief.get("pending"))
                    .append(" 笔\n");
            boolean autoTrade = !Boolean.FALSE.equals(brief.getBoolean("autoTrade"));
            sb.append("- 自动化：策略自动交易")
                    .append(autoTrade
                            ? "开（交易时段每分钟盯盘：达到止损/止盈立即卖出；14:50 按策略买入信号股并执行到期、死叉离场）"
                            : "关（只自动止损，其他买卖由你和用户决定）")
                    .append("；盘前计划 9:26、收盘复盘 15:30、周/月复盘 16:00 自动运行；每周最后一个交易日系统自动搜索策略改进；通过回测闸门的候选先做 10 个交易日前进测试，不差于当前版本才采纳，")
                    .append("每月检查新版本实盘回撤，明显差于回测时自动回滚。用户可在「模拟炒股」页面修改。\n");
            // The market phase decides what may be stated as fact; per-analysis limits come from pack.
            TradingClock.Phase phase = packService.phase();
            sb.append("- 当前时点：").append(phase.label()).append("。").append(phase.rule()).append('\n');
            JSONObject signals = signalService.stats(uid);
            int evaluated = signals.getIntValue("evaluated");
            sb.append("- 你的建议后验：已评估 ")
                    .append(evaluated)
                    .append(" 条，待评估 ")
                    .append(signals.get("pending"))
                    .append(" 条");
            if (evaluated > 0) {
                sb.append("，命中率 ").append(signals.getJSONObject("overall").get("hitRatePct")).append("%");
                if (evaluated < DecisionSignalService.MIN_SAMPLE) {
                    sb.append("（样本不足 ").append(DecisionSignalService.MIN_SAMPLE).append(" 条，仅供观察）");
                }
            }
            sb.append("。每次给出明确买卖判断时，用 decision_signal 的 record 留痕（含依据、周期、止损和目标价），"
                    + "系统会在周期结束后自动判定命中与否；提建议前先用 stats 看自己哪类判断准。\n");
            sb.append("- 分析个股先用 a_share_market 的 pack（含资金流向、所属板块、公告和数据质量标注），"
                    + "看大盘先用 market（含涨跌家数、涨停跌停、领涨板块）；返回里的 dataQuality.limitations 必须遵守。\n");
            List<TradingLesson> lessons = lessonService.forContext(uid);
            appendKind(sb, lessons, TradingLesson.PREFERENCE, "- 用户偏好（必须遵守）：\n");
            appendKind(sb, lessons, TradingLesson.LESSON, "- 已被多次交易验证的经验（括号内为置信度）：\n");
            appendKind(sb, lessons, TradingLesson.META, "- 最近的策略进化记录（提新提案前先看，避免重复失败的方向）：\n");
            long reviewable = paperTradingService.countReviewable(uid);
            long candidates = lessonService.countCandidates(uid);
            if (reviewable > 0 || candidates > 0) {
                sb.append("- 待办：")
                        .append(reviewable)
                        .append(" 笔成交待复盘，")
                        .append(candidates)
                        .append(" 条候选经验待验证\n");
            }
            sb.append("- 规则：只做模拟交易，按 A 股规则成交：交易时段市价单按最新价立即成交，非交易时段的订单在下一交易日开盘成交；"
                    + "订单当日有效；T+1；涨停封板买不进、跌停封板卖不出。涉及买卖观点时注明「仅供学习研究，不构成投资建议」。\n");
            return sb.toString();
        } catch (RuntimeException e) {
            log.warn("A-share context unavailable, uid={}, errorType={}, msg={}", uid, e.getClass().getSimpleName(),
                    e.getMessage());
            return "";
        }
    }

    private static void appendKind(StringBuilder sb, List<TradingLesson> lessons, String kind, String title) {
        List<TradingLesson> rows = lessons.stream().filter(l -> kind.equals(l.getKind())).toList();
        if (rows.isEmpty()) {
            return;
        }
        sb.append(title);
        for (TradingLesson l : rows) {
            sb.append("  - ");
            if (TradingLesson.LESSON.equals(kind)) {
                sb.append(String.format(Locale.ROOT, "[#%d %.2f] ", l.getId(), l.getConfidence().doubleValue()));
            }
            sb.append(l.getContent()).append('\n');
        }
    }
}
