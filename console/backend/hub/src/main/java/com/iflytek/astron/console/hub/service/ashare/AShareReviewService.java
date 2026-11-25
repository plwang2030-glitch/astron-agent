package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iflytek.astron.console.commons.entity.bot.ChatBotBase;
import com.iflytek.astron.console.commons.data.UserInfoDataService;
import com.iflytek.astron.console.commons.entity.bot.ChatBotMarket;
import com.iflytek.astron.console.commons.entity.user.UserInfo;
import com.iflytek.astron.console.commons.enums.ShelfStatusEnum;
import com.iflytek.astron.console.commons.mapper.bot.ChatBotBaseMapper;
import com.iflytek.astron.console.commons.service.bot.ChatBotDataService;
import com.iflytek.astron.console.hub.dto.notification.SendNotificationRequest;
import com.iflytek.astron.console.hub.entity.ashare.PaperAccount;
import com.iflytek.astron.console.hub.entity.ashare.TradingReviewReport;
import com.iflytek.astron.console.hub.enums.NotificationType;
import com.iflytek.astron.console.hub.mapper.ashare.TradingReviewReportMapper;
import com.iflytek.astron.console.hub.service.chat.springai.AgentToolCallbackResolver;
import com.iflytek.astron.console.hub.service.chat.springai.ChatToolContext;
import com.iflytek.astron.console.hub.service.chat.springai.PreparedSubAgent;
import com.iflytek.astron.console.hub.service.chat.springai.SubAgentPreparer;
import com.iflytek.astron.console.hub.service.chat.springai.SubAgentToolFactory;
import com.iflytek.astron.console.hub.service.notification.NotificationService;
import com.iflytek.astron.console.toolkit.util.RedisUtil;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the user's review agent without a chat: after each trading day (scheduled) or when the user
 * clicks 立即复盘. The agent reviews the fills, updates the lesson book and may propose one strategy
 * change through the backtest gate, i.e. one turn of the self-improvement loop. The written review
 * is saved as a report and the user is notified.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AShareReviewService {

    static final Duration RUN_BUDGET = Duration.ofMinutes(25);
    /** RUNNING rows older than this were cut off (e.g. by a restart). */
    private static final Duration STALE_AFTER = Duration.ofMinutes(40);
    public static final String PAGE_PATH = "/management/a-share";

    private final TradingReviewReportMapper reportMapper;
    private final PaperTradingService paperTradingService;
    private final SubAgentPreparer subAgentPreparer;
    private final SubAgentToolFactory subAgentToolFactory;
    private final ChatBotBaseMapper chatBotBaseMapper;
    private final ChatBotDataService chatBotDataService;
    private final NotificationService notificationService;
    private final UserInfoDataService userInfoDataService;
    private final MarketPackService packService;
    private final DecisionSignalService signalService;
    private final RedisUtil redisUtil;

    private final AtomicInteger threadIds = new AtomicInteger();
    private final ExecutorService executor = new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(20), r -> {
                Thread t = new Thread(r, "ashare-review-" + threadIds.incrementAndGet());
                t.setDaemon(true);
                return t;
            });

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** An agent the user may pick as the review agent. */
    public record ReviewBot(Integer botId, String name, boolean published) {}

    /** Agents with the A-share tools that the user can access in this space or that are published. */
    public List<ReviewBot> candidates(String uid, Long spaceId) {
        List<ChatBotBase> bots = chatBotBaseMapper.selectList(Wrappers.lambdaQuery(ChatBotBase.class)
                .like(ChatBotBase::getBuiltinTools, "\"" + AShareToolFactory.GROUP + "\"")
                .eq(ChatBotBase::getIsDelete, 0)
                .orderByDesc(ChatBotBase::getId)
                .last("LIMIT 50"));
        List<ReviewBot> out = new ArrayList<>();
        for (ChatBotBase b : bots) {
            boolean published = isPublished(b.getId());
            boolean own = chatBotBaseMapper.checkBotPermission(b.getId(), uid, spaceId) > 0;
            if (published || own) {
                out.add(new ReviewBot(b.getId(), b.getBotName(), published));
            }
        }
        return out;
    }

    /** Throws when {@code botId} cannot be the user's review agent. */
    public void requireCandidate(String uid, Long spaceId, Integer botId) {
        boolean ok = candidates(uid, spaceId).stream().anyMatch(c -> c.botId().equals(botId));
        if (!ok) {
            throw new IllegalArgumentException("只能选择已开启「A股投研工具」且你有权限使用的智能体");
        }
    }

    /** Starts a review in the background; the report is RUNNING until the agent finishes. */
    public TradingReviewReport startManual(String uid, String reviewType) {
        String type = normalizeType(reviewType);
        PaperAccount account = paperTradingService.account(uid);
        Integer botId = requireReviewBot(account);
        String lockToken = UUID.randomUUID().toString();
        if (!redisUtil.tryLock(lockKey(uid), RUN_BUDGET.plusMinutes(5).toSeconds(), lockToken)) {
            throw new IllegalArgumentException("复盘正在进行中，请稍后刷新查看");
        }
        TradingReviewReport report = newReport(uid, TradingReviewReport.MANUAL, botId, type,
                currentPeriod(type, TradingClock.now().toLocalDate()));
        try {
            executor.execute(() -> execute(report, lockToken));
        } catch (RejectedExecutionException e) {
            redisUtil.unlock(lockKey(uid), lockToken);
            fail(report, "排队的复盘任务过多，请稍后再试", null);
            throw new IllegalArgumentException("排队的复盘任务过多，请稍后再试");
        }
        return report;
    }

    /** One due review: its type and the period it covers. */
    public record ScheduledReview(String type, String period) {}

    /**
     * Queues the user's due reviews on the review executor (scheduler threads are shared with other
     * jobs and must not wait minutes for an agent). They run one after another.
     */
    public void submitScheduled(String uid, List<ScheduledReview> reviews) {
        if (reviews.isEmpty()) {
            return;
        }
        try {
            executor.execute(() -> {
                for (ScheduledReview r : reviews) {
                    try {
                        runScheduled(uid, r.type(), r.period());
                    } catch (RuntimeException e) {
                        log.warn("Scheduled review failed, uid={}, type={}, errorType={}, msg={}", uid, r.type(),
                                e.getClass().getSimpleName(), e.getMessage());
                    }
                }
            });
            log.info("Scheduled reviews queued, uid={}, reviews={}", uid, reviews);
        } catch (RejectedExecutionException e) {
            log.warn("Scheduled reviews dropped: queue full, uid={}, reviews={}", uid, reviews);
        }
    }

    /** Scheduled run on the job thread; skipped when the user's review is already running. */
    public void runScheduled(String uid, String reviewType, String period) {
        PaperAccount account = paperTradingService.account(uid);
        if (account.getReviewBotId() == null) {
            log.info("Daily review skipped: no review agent, uid={}", uid);
            return;
        }
        String lockToken = UUID.randomUUID().toString();
        if (!redisUtil.tryLock(lockKey(uid), RUN_BUDGET.plusMinutes(5).toSeconds(), lockToken)) {
            log.info("Daily review skipped: already running, uid={}", uid);
            return;
        }
        execute(newReport(uid, TradingReviewReport.AUTO, account.getReviewBotId(), normalizeType(reviewType), period),
                lockToken);
    }

    public List<TradingReviewReport> list(String uid, String reviewType, int limit) {
        LocalDateTime staleBefore = TradingClock.now().minus(STALE_AFTER);
        var q = Wrappers.lambdaQuery(TradingReviewReport.class).eq(TradingReviewReport::getUid, uid);
        if (StringUtils.isNotBlank(reviewType)) {
            q.eq(TradingReviewReport::getReviewType, normalizeType(reviewType));
        }
        List<TradingReviewReport> rows = reportMapper.selectList(q
                .orderByDesc(TradingReviewReport::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 50))));
        for (TradingReviewReport r : rows) {
            if (TradingReviewReport.RUNNING.equals(r.getStatus()) && r.getCreateTime() != null
                    && r.getCreateTime().isBefore(staleBefore)) {
                fail(r, "复盘被中断（服务重启或超时）", null);
            }
        }
        return rows;
    }

    private Integer requireReviewBot(PaperAccount account) {
        if (account.getReviewBotId() == null) {
            throw new IllegalArgumentException("还没有复盘智能体：先在「设置」里选择一个开启了 A股投研工具的智能体");
        }
        return account.getReviewBotId();
    }

    /** Periods of this type that already have a completed or running review. */
    public List<String> reviewedPeriods(String uid, String reviewType) {
        return reportMapper.selectList(Wrappers.lambdaQuery(TradingReviewReport.class)
                .select(TradingReviewReport::getPeriod)
                .eq(TradingReviewReport::getUid, uid)
                .eq(TradingReviewReport::getReviewType, reviewType)
                .ne(TradingReviewReport::getStatus, TradingReviewReport.FAILED)
                .orderByDesc(TradingReviewReport::getId)
                .last("LIMIT 60"))
                .stream()
                .map(TradingReviewReport::getPeriod)
                .filter(Objects::nonNull)
                .toList();
    }

    static String normalizeType(String reviewType) {
        String t = StringUtils.upperCase(StringUtils.defaultIfBlank(reviewType, TradingReviewReport.CLOSE),
                java.util.Locale.ROOT);
        if (!List.of(TradingReviewReport.PRE_OPEN, TradingReviewReport.CLOSE, TradingReviewReport.WEEKLY,
                TradingReviewReport.MONTHLY).contains(t)) {
            throw new IllegalArgumentException("复盘类型只能是 PRE_OPEN / CLOSE / WEEKLY / MONTHLY");
        }
        return t;
    }

    static String currentPeriod(String type, LocalDate day) {
        return switch (type) {
            case TradingReviewReport.WEEKLY -> TradingClock.weekKey(day);
            case TradingReviewReport.MONTHLY -> TradingClock.monthKey(day);
            default -> day.toString();
        };
    }

    private TradingReviewReport newReport(String uid, String trigger, Integer botId, String type, String period) {
        TradingReviewReport report = new TradingReviewReport();
        report.setUid(uid);
        report.setTradeDate(TradingClock.now().toLocalDate());
        report.setTriggerType(trigger);
        report.setReviewType(type);
        report.setPeriod(period);
        report.setBotId(botId);
        report.setStatus(TradingReviewReport.RUNNING);
        reportMapper.insert(report);
        return report;
    }

    private void execute(TradingReviewReport report, String lockToken) {
        String uid = report.getUid();
        long start = System.currentTimeMillis();
        ChatToolContext context = new ChatToolContext(uid);
        context.setBotId(report.getBotId());
        context.setLongRunning(true);
        context.setDeadlineMillis(start + RUN_BUDGET.toMillis());
        log.info("A-share review started, uid={}, reportId={}, botId={}, trigger={}", uid, report.getId(),
                report.getBotId(), report.getTriggerType());
        try {
            ChatBotBase base = chatBotDataService.findById(report.getBotId()).orElse(null);
            if (base == null || Objects.equals(base.getIsDelete(), 1)) {
                fail(report, "复盘智能体不存在或已删除，请在设置里重新选择", start);
                return;
            }
            if (!AgentToolCallbackResolver.parseBuiltinTools(base.getBuiltinTools()).contains(AShareToolFactory.GROUP)) {
                fail(report, "复盘智能体「" + base.getBotName() + "」没有开启 A股投研工具", start);
                return;
            }
            // Resolved like an attached agent of the user (published version, its own experts included).
            UserInfo user = userInfoDataService.findByUid(uid).orElse(null);
            if (user == null) {
                fail(report, "用户不存在", start);
                return;
            }
            List<PreparedSubAgent> prepared = subAgentPreparer.prepareForUser("[{\"botId\":" + base.getId() + "}]",
                    base.getUid(), base.getSpaceId(), user, base.getSpaceId(), !Objects.equals(base.getUid(), uid));
            List<ToolCallback> tools = prepared.isEmpty() ? List.of() : subAgentToolFactory.build(prepared, context);
            if (tools.isEmpty()) {
                fail(report, "复盘智能体「" + base.getBotName() + "」未发布或模型不可用", start);
                return;
            }
            String answer = tools.get(0)
                    .call(new JSONObject().fluentPut("query", prompt(report) + marketContext(report)).toJSONString());
            if (StringUtils.isBlank(answer) || answer.startsWith("调用智能体") || answer.startsWith("剩余时间不足")
                    || answer.startsWith("对话已结束")) {
                fail(report, StringUtils.abbreviate(StringUtils.defaultIfBlank(answer, "智能体没有返回内容"), 500), start);
                return;
            }
            report.setStatus(TradingReviewReport.DONE);
            report.setContent(answer);
            report.setCostMs((int) (System.currentTimeMillis() - start));
            report.setUpdateTime(null);
            reportMapper.updateById(report);
            log.info("A-share review done, uid={}, reportId={}, answerChars={}, costMs={}", uid, report.getId(),
                    answer.length(), report.getCostMs());
            notifyUser(report, true);
        } catch (RuntimeException e) {
            log.warn("A-share review failed, uid={}, reportId={}, errorType={}, msg={}", uid, report.getId(),
                    e.getClass().getSimpleName(), e.getMessage(), e);
            fail(report, "复盘失败：" + e.getClass().getSimpleName(), start);
        } finally {
            context.cancel();
            redisUtil.unlock(lockKey(uid), lockToken);
        }
    }

    /**
     * The market's state and the data limits, appended to the prompt. The agent could fetch this
     * itself, but putting the phase rule and the quality caveats in the prompt means a review cannot
     * silently skip them. Failures here only shorten the section.
     */
    private String marketContext(TradingReviewReport report) {
        StringBuilder sb = new StringBuilder();
        try {
            MarketPackService.MarketPack pack = packService.overview();
            String described = pack.describe();
            if (StringUtils.isNotBlank(described)) {
                sb.append("\n\n## 当前市场状态（系统已取好，可直接引用）\n").append(described);
            }
            sb.append(pack.quality().promptSection(pack.phase()));
        } catch (RuntimeException e) {
            log.info("Market context unavailable for review, reportId={}, msg={}", report.getId(), e.getMessage());
        }
        try {
            JSONObject stats = signalService.stats(report.getUid());
            int evaluated = stats.getIntValue("evaluated");
            sb.append("\n## 你过去建议的命中情况\n")
                    .append("- 已评估 ")
                    .append(evaluated)
                    .append(" 条，待评估 ")
                    .append(stats.get("pending"))
                    .append(" 条");
            if (evaluated > 0) {
                sb.append("，命中率 ").append(stats.getJSONObject("overall").get("hitRatePct")).append("%");
            }
            sb.append("。按动作分组见 decision_signal 的 stats。\n")
                    .append("- 样本少于 ")
                    .append(DecisionSignalService.MIN_SAMPLE)
                    .append(" 条时只作为观察，不要当成结论。\n");
        } catch (RuntimeException e) {
            log.info("Signal stats unavailable for review, reportId={}, msg={}", report.getId(), e.getMessage());
        }
        return sb.toString();
    }

    static String prompt(TradingReviewReport report) {
        boolean auto = TradingReviewReport.AUTO.equals(report.getTriggerType());
        String type = StringUtils.defaultIfBlank(report.getReviewType(), TradingReviewReport.CLOSE);
        String head = (auto ? "【系统定时任务，用户不在线，不要向用户提问】" : "【用户在「模拟炒股」页面手动发起，不要向用户提问】")
                + "今天是 " + report.getTradeDate() + "。";
        String noTrade = "不要下单或撤单：盘中买卖由策略按规则自动执行，主观买卖需要用户确认。";
        String tail = "日报直接给用户看，不要写给负责人的说明；涉及买卖观点时最后注明「仅供学习研究，不构成投资建议」。";
        return switch (type) {
            case TradingReviewReport.PRE_OPEN -> head + "请做【盘前计划】（不提交策略修改）：\n"
                    + "1. 用 a_share_market 的 market 看大盘（指数、涨跌家数、涨停跌停、领涨领跌板块），用 web_search 查隔夜和今早的重要财经新闻、政策和外围市场。\n"
                    + "2. 用 paper_trade 的 account 看持仓；对每只持仓用 a_share_market 的 pack 看资金流向、所属板块和最近公告，"
                    + "说明离策略止损价和止盈价还有多远、有没有隔夜利空。\n"
                    + "3. 用 strategy_evolve 的 scan 看股票池里接近入场条件的股票，列出今天要盯的 3–5 只和触发条件。\n"
                    + "4. 对今天明确看多或看空的股票，用 decision_signal 的 record 留痕（action、horizon、reason、止损与目标价），"
                    + "系统会在周期后自动判定命中与否。\n"
                    + "5. " + noTrade + "\n"
                    + "输出一份盘前计划（不超过 600 字，Markdown）：大盘与消息面、持仓风险、今日关注（含资金流向依据）、已留痕的建议、风险提示。" + tail;
            case TradingReviewReport.WEEKLY -> head + "请做【周复盘】（" + report.getPeriod() + "）：\n"
                    + "1. 用 paper_trade 的 performance（from/to 设为本周一到今天）看本周收益、回撤、胜率、手续费，与上证指数对比。\n"
                    + "1b. 用 decision_signal 的 stats 看自己建议的命中率（按动作、周期、数据质量分组），"
                    + "指出哪类判断准、哪类不准；样本不足 30 条时只作为观察。\n"
                    + "2. 用 paper_trade 的 review_queue 逐笔复盘本周还没复盘的成交，mark_reviewed。\n"
                    + "3. 整理经验库：用 trading_lessons 的 list 看全部经验，用本周成交 feedback；重复或过时的 retire；新规律 add（附订单 id）。\n"
                    + "4. 系统刚完成本周的自动进化搜索：用 strategy_evolve 的 history 和 trading_lessons 的 list（kind=META）看结果，"
                    + "解释采纳或没采纳的原因；再看本周实盘与回测是否一致。复盘中发现搜索覆盖不到的问题（例如股票池需要调整）时，最多 propose 1 次。\n"
                    + "5. " + noTrade + "\n"
                    + "输出周报（不超过 1000 字，Markdown）：本周账户、交易回顾、经验库变化、策略进化、下周计划。" + tail;
            case TradingReviewReport.MONTHLY -> head + "请做【月复盘】（" + report.getPeriod() + "）：\n"
                    + "1. 用 paper_trade 的 performance（from/to 设为本月 1 日到今天）看本月收益、回撤、胜率，与上证指数对比。\n"
                    + "2. 按策略版本统计本月成交（订单里的 strategyVersion），比较各版本的实盘表现与它被采纳时的样本外回测。\n"
                    + "3. 系统已自动检查当前版本的实盘回撤（明显差于回测时已自动回滚，见 META 记录）；没有自动回滚但收益、胜率明显差于回测时，"
                    + "可用 strategy_evolve 的 rollback 回到上一个采纳过的版本，并写明原因。\n"
                    + "3b. 用 decision_signal 的 stats 和 list 复盘本月建议：命中率按动作和数据质量的差异，"
                    + "把稳定的规律写进 trading_lessons。\n"
                    + "4. 整理经验库：置信度低、长期没有新证据的候选经验 retire；把本月最重要的 1–3 条规律写清楚。\n"
                    + "5. 证据足够时用 backtest 试算后最多 propose 1 次。" + noTrade + "\n"
                    + "输出月报（不超过 1200 字，Markdown）：本月账户、策略版本表现、经验库变化、策略进化或回滚、下月计划。" + tail;
            default -> head + "请做【收盘复盘】，按「复盘与递归自进化流程」完成一轮复盘与自进化：\n"
                    + "0. 用 decision_signal 的 list（status=HIT 和 status=MISS）看今天刚判定的建议，"
                    + "用 stats 看整体命中率；说明判断对了还是错了、为什么。\n"
                    + "1. 用 paper_trade 的 performance 和 review_queue 看全局和待复盘成交，逐笔 mark_reviewed；"
                    + "对亏损的持仓用 a_share_market 的 pack 看资金流向和公告，判断是技术面失效还是消息面变化。\n"
                    + "2. 用 trading_lessons 对新成交做 feedback（支持或反驳已有经验）或 add 新经验，必须附订单 id。\n"
                    + "3. 证据足够时（例如已平仓至少 3 笔，或回测暴露出明显问题），用 strategy_evolve 的 backtest 试算后最多 propose 1 次；"
                    + "证据不足就说明不提案的原因。\n"
                    + "4. " + noTrade + "\n"
                    + "最后输出一份复盘日报（不超过 800 字，Markdown）：今日账户、复盘表、经验变化、策略进化、明日关注。" + tail;
        };
    }

    private void fail(TradingReviewReport report, String error, Long start) {
        report.setStatus(TradingReviewReport.FAILED);
        report.setError(StringUtils.abbreviate(error, 500));
        if (start != null && start > 0) {
            report.setCostMs((int) (System.currentTimeMillis() - start));
        }
        report.setUpdateTime(null);
        reportMapper.updateById(report);
        log.info("A-share review not completed, uid={}, reportId={}, error={}", report.getUid(), report.getId(), error);
        if (start != null && start > 0) {
            notifyUser(report, false);
        }
    }

    private void notifyUser(TradingReviewReport report, boolean ok) {
        try {
            SendNotificationRequest request = new SendNotificationRequest();
            request.setType(NotificationType.PERSONAL);
            request.setTitle((ok ? "A股模拟盘" : "A股模拟盘复盘未完成：") + typeLabel(report.getReviewType())
                    + (ok ? "已生成" : ""));
            String body = ok ? StringUtils.abbreviate(StringUtils.normalizeSpace(report.getContent()), 120)
                    : report.getError();
            request.setBody(report.getTradeDate() + (ok ? " 复盘：" : " ") + StringUtils.defaultString(body));
            request.setPayload(new JSONObject()
                    .fluentPut("outlink", PAGE_PATH)
                    .fluentPut("reportId", report.getId())
                    .toJSONString());
            request.setReceiverUids(List.of(report.getUid()));
            notificationService.sendNotification(request);
        } catch (Exception e) {
            log.warn("A-share review notification failed, uid={}, reportId={}, errorType={}, msg={}",
                    report.getUid(), report.getId(), e.getClass().getSimpleName(), e.getMessage());
        }
    }

    static String typeLabel(String type) {
        return switch (StringUtils.defaultString(type)) {
            case TradingReviewReport.PRE_OPEN -> "盘前计划";
            case TradingReviewReport.WEEKLY -> "周复盘";
            case TradingReviewReport.MONTHLY -> "月复盘";
            default -> "收盘复盘";
        };
    }

    private boolean isPublished(Integer botId) {
        ChatBotMarket market = chatBotDataService.findMarketBotByBotId(botId);
        return market != null && ShelfStatusEnum.isOnShelf(market.getBotStatus());
    }

    private static String lockKey(String uid) {
        return "ashare:review:" + uid;
    }
}
