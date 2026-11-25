package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.iflytek.astron.console.hub.entity.ashare.PaperOrder;
import com.iflytek.astron.console.hub.entity.ashare.TradingLesson;
import com.iflytek.astron.console.hub.entity.ashare.TradingStrategyVersion;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Bar;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Quote;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Series;
import com.iflytek.astron.console.hub.service.chat.springai.ChatToolContext;
import com.iflytek.astron.console.hub.service.chat.springai.ParallelToolCalls;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Tools of the {@code a_share} built-in group: market data, the paper account, trading lessons and
 * strategy evolution. Everything is keyed by the chatting user, so the lead and its experts share
 * one account, one lesson book and one strategy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AShareToolFactory {

    public static final String GROUP = "a_share";
    private static final int MAX_RESULT_CHARS = 12_000;
    private static final String DISCLAIMER = "（模拟交易，仅供学习研究，不构成投资建议）";
    /** Major indices by Eastmoney security id. */
    private static final Map<String, String> INDICES = new LinkedHashMap<>();

    static {
        INDICES.put("1.000001", "上证指数");
        INDICES.put("0.399001", "深证成指");
        INDICES.put("0.399006", "创业板指");
        INDICES.put("1.000300", "沪深300");
        INDICES.put("1.000688", "科创50");
    }

    private final EastmoneyClient market;
    private final PaperTradingService paperTradingService;
    private final LessonService lessonService;
    private final StrategyService strategyService;
    private final AShareContextService contextService;
    private final MarketPackService packService;
    private final MarketExtras extras;
    private final DecisionSignalService signalService;

    public List<ToolCallback> create(ChatToolContext context) {
        return List.of(
                new ParallelAShareTool("a_share_market", "A股行情", MARKET_DESC, MARKET_SCHEMA, context, this::market, this::afterCall),
                new AShareTool("paper_trade", "模拟交易", TRADE_DESC, TRADE_SCHEMA, context, this::trade, this::afterCall),
                new AShareTool("trading_lessons", "交易经验", LESSON_DESC, LESSON_SCHEMA, context, this::lessons, this::afterCall),
                new AShareTool("strategy_evolve", "策略进化", STRATEGY_DESC, STRATEGY_SCHEMA, context,
                        this::strategy, this::afterCall),
                // The recording agent is part of the evidence, so the signal tool closes over the context.
                new AShareTool("decision_signal", "建议留痕", SIGNAL_DESC, SIGNAL_SCHEMA, context,
                        (uid, args) -> signal(uid, args, context.getBotId()), this::afterCall));
    }

    /** The first agent a user trades with becomes the default daily review agent. */
    private void afterCall(ChatToolContext context) {
        try {
            paperTradingService.rememberReviewBot(context.getUserId(), context.getBotId());
        } catch (RuntimeException e) {
            log.warn("Remember review agent failed, uid={}, msg={}", context.getUserId(), e.getMessage());
        }
    }

    public String contextPrompt(String uid) {
        return contextService.contextPrompt(uid);
    }

    // ------------------------------------------------------------------ a_share_market

    private static final String MARKET_DESC = "查询沪深 A 股行情与市场数据（东方财富、新浪公开接口）。quote：实时报价与估值（codes，最多 10 个）；"
            + "kline：日 K 线与技术指标（code、days，前复权）；search：按名称/拼音/代码找股票（keyword）；index：主要指数；"
            + "pack：一次拿到某只股票的报价、指标、资金流向、所属行业/概念、最近公告、龙虎榜和数据质量标注（code，推荐分析个股时先调这个）；"
            + "market：大盘状态——指数、涨跌家数、涨停跌停数、领涨领跌行业与热门概念（大盘复盘和盘前计划先调这个）；"
            + "flow：个股逐日资金流向（code、days，主力=大单+超大单净额）；boards：个股所属行业与概念；announcements：个股最近公告（code）；"
            + "lhb：个股龙虎榜（code）。返回里带 dataQuality 和 marketPhase 时，必须按其中的 limitations 和 rule 约束结论。";
    private static final String MARKET_SCHEMA = """
            {"type":"object","properties":{
            "action":{"type":"string","enum":["quote","kline","search","index","pack","market","flow","boards","announcements","lhb"]},
            "codes":{"type":"array","items":{"type":"string"},"description":"quote：6 位股票代码"},
            "code":{"type":"string","description":"kline：6 位股票代码"},
            "days":{"type":"integer","description":"kline：返回最近多少个交易日的 K 线，默认 20，最多 120（指标总用更长的历史计算）"},
            "keyword":{"type":"string","description":"search：股票名称、拼音首字母或代码"}
            },"required":["action"]}""";

    private Object market(String uid, JSONObject args) {
        String action = action(args);
        switch (action) {
            case "quote" -> {
                List<String> codes = new ArrayList<>();
                JSONArray arr = args.getJSONArray("codes");
                if (arr != null) {
                    arr.forEach(c -> codes.add(String.valueOf(c)));
                }
                if (codes.isEmpty() && StringUtils.isNotBlank(args.getString("code"))) {
                    codes.add(args.getString("code"));
                }
                if (codes.isEmpty() || codes.size() > 10) {
                    throw new IllegalArgumentException("codes 需为 1–10 个股票代码");
                }
                JSONArray out = new JSONArray();
                for (String code : codes) {
                    out.add(quoteJson(market.quote(code)));
                }
                return out;
            }
            case "kline" -> {
                String code = AShareRules.normalizeCode(args.getString("code"));
                int days = Math.max(5, Math.min(120, args.getIntValue("days", 20)));
                Series s = market.dailyBars(code, true, 250);
                List<Bar> bars = s.bars();
                JSONArray rows = new JSONArray();
                for (Bar b : bars.subList(Math.max(0, bars.size() - days), bars.size())) {
                    rows.add(String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f,%.2f,%.2f%%,%.0f,%.2f%%", b.date(), b.open(),
                            b.close(), b.high(), b.low(), b.pctChg(), b.volume(), b.turnoverPct()));
                }
                return new JSONObject()
                        .fluentPut("code", code)
                        .fluentPut("name", s.name())
                        .fluentPut("board", AShareRules.board(code).label())
                        .fluentPut("indicators", Indicators.latestSummary(bars))
                        .fluentPut("columns", "date,open,close,high,low,pctChg,volumeLots,turnover")
                        .fluentPut("bars", rows)
                        .fluentPut("note", "前复权价格；最后一根在交易时段内为未收盘数据。");
            }
            case "search" -> {
                return market.search(args.getString("keyword"));
            }
            case "index" -> {
                JSONArray out = new JSONArray();
                for (var e : INDICES.entrySet()) {
                    Quote q = market.quoteBySecid(e.getKey());
                    out.add(new JSONObject()
                            .fluentPut("name", e.getValue())
                            .fluentPut("price", q.price())
                            .fluentPut("pctChg", q.pctChg())
                            .fluentPut("amount", q.amount())
                            .fluentPut("time", q.time() == null ? null : q.time().toString()));
                }
                return out;
            }
            case "pack" -> {
                return packService.stock(args.getString("code"), true).toJson();
            }
            case "market" -> {
                return packService.overview().toJson();
            }
            case "flow" -> {
                String code = AShareRules.normalizeCode(args.getString("code"));
                int days = Math.max(3, Math.min(60, args.getIntValue("days", 10)));
                List<MarketExtras.FlowDay> flow = extras.flow(code, days);
                JSONArray rows = new JSONArray();
                flow.forEach(f -> rows.add(f.toJson()));
                Double main5 = MarketExtras.mainNetSum(flow, 5);
                return new JSONObject()
                        .fluentPut("code", code)
                        .fluentPut("unit", "亿元，正为净流入")
                        .fluentPut("main5dYi", main5 == null ? null : Math.round(main5 / 1e6) / 100.0)
                        .fluentPut("days", rows)
                        .fluentPut("note", flow.isEmpty() ? "资金流向接口暂时取不到，不要据此下结论"
                                : "main 为大单+超大单净额（主力），retail 为中小单净额。");
            }
            case "boards" -> {
                return extras.boards(args.getString("code"))
                        .toJson()
                        .fluentPut("code", AShareRules.normalizeCode(args.getString("code")));
            }
            case "announcements" -> {
                JSONArray rows = new JSONArray();
                extras.announcements(args.getString("code"), 8).forEach(a -> rows.add(a.toJson()));
                return new JSONObject().fluentPut("code", AShareRules.normalizeCode(args.getString("code")))
                        .fluentPut("announcements", rows)
                        .fluentPut("note", rows.isEmpty() ? "公告接口没有返回内容（可能近期无公告或接口不可用）" : null);
            }
            case "lhb" -> {
                JSONArray rows = new JSONArray();
                extras.dragonTiger(args.getString("code"), 5).forEach(l -> rows.add(l.toJson()));
                return new JSONObject().fluentPut("code", AShareRules.normalizeCode(args.getString("code")))
                        .fluentPut("dragonTiger", rows)
                        .fluentPut("note", rows.isEmpty() ? "近期没有上龙虎榜，或接口不可用" : "net 为龙虎榜净买入额（亿元）。");
            }
            default -> throw new IllegalArgumentException(
                    "action 只能是 quote / kline / search / index / pack / market / flow / boards / announcements / lhb");
        }
    }

    private static JSONObject quoteJson(Quote q) {
        return new JSONObject()
                .fluentPut("code", q.code())
                .fluentPut("name", q.name())
                .fluentPut("board", AShareRules.board(q.code()).label())
                .fluentPut("time", q.time() == null ? null : q.time().toString())
                .fluentPut("suspended", q.suspended())
                .fluentPut("price", q.price())
                .fluentPut("pctChg", q.pctChg())
                .fluentPut("open", q.open())
                .fluentPut("high", q.high())
                .fluentPut("low", q.low())
                .fluentPut("prevClose", q.prevClose())
                .fluentPut("amount", q.amount())
                .fluentPut("turnoverPct", q.turnoverPct())
                .fluentPut("peDynamic", q.pe())
                .fluentPut("pb", q.pb())
                .fluentPut("totalCap", q.totalCap())
                .fluentPut("floatCap", q.floatCap())
                .fluentPut("limitPct", AShareRules.limitPct(q.code(), q.name()));
    }

    // ------------------------------------------------------------------ paper_trade

    private static final String TRADE_DESC = "用户唯一的 A 股模拟账户（本金和持仓以 account 返回为准，可能同步自用户的实盘持仓），按 A 股规则成交：交易时段（9:30–11:30、13:00–15:00）"
            + "市价单立即按最新价成交（含 0.1% 滑点），限价单价格到达即成交；非交易时段的订单参与下一交易日开盘集合竞价；订单当日有效；"
            + "T+1（当日买入的次日才能卖）；涨停封板买不进、跌停封板卖不出。account：资产、持仓与可卖数量；place：下单（必须写 reason，"
            + "买入还要写 expectation；可选 limitPrice 为限价单）；cancel：撤销未成交订单；orders：订单列表；performance：收益、回撤、胜率、"
            + "对比上证（可选 from/to 统计某一周或某一月）；review_queue：待复盘的成交；mark_reviewed：写复盘结论；"
            + "watchlist：用户的自选股（行情、今日策略信号、是否在规则股票池），watch：加入自选（codes），unwatch：移出自选（code）。"
            + "自选股每个交易日 14:50 用当前策略扫描，开启自选自动买入时信号出现即买入（与股票池共用持仓上限），否则只通知用户。";
    private static final String TRADE_SCHEMA = """
            {"type":"object","properties":{
            "action":{"type":"string","enum":["account","place","cancel","orders","performance","review_queue","mark_reviewed","watchlist","watch","unwatch"]},
            "code":{"type":"string","description":"place / unwatch：6 位股票代码"},
            "codes":{"type":"array","items":{"type":"string"},"description":"watch：要加入自选的股票代码（最多 20 只）"},
            "side":{"type":"string","enum":["BUY","SELL"]},
            "qty":{"type":"integer","description":"股数：100 的整数倍（科创板至少 200 股）；卖出可一次卖完零股"},
            "limitPrice":{"type":"number","description":"place 可选：限价（元，精确到 0.01，须在涨跌停价之间）；不填为市价单"},
            "from":{"type":"string","description":"performance 可选：统计开始日期 yyyy-MM-dd"},
            "to":{"type":"string","description":"performance 可选：统计结束日期 yyyy-MM-dd，默认今天"},
            "reason":{"type":"string","description":"place：下单依据（技术面、基本面、资讯、策略信号、引用的经验 #id）"},
            "expectation":{"type":"string","description":"place 买入必填：预期涨跌幅、持有周期、止损位，复盘时对照"},
            "confidence":{"type":"integer","description":"1–5"},
            "orderId":{"type":"integer"},
            "status":{"type":"string","enum":["PENDING","FILLED","REJECTED","CANCELLED"],"description":"orders：按状态过滤"},
            "outcome":{"type":"string","enum":["CORRECT","WRONG","MIXED"],"description":"mark_reviewed：判断对了 / 错了 / 部分对"},
            "note":{"type":"string","description":"mark_reviewed：对照 reason 和 expectation 的复盘结论"}
            },"required":["action"]}""";

    private Object trade(String uid, JSONObject args) {
        String action = action(args);
        return switch (action) {
            case "account" -> paperTradingService.portfolio(uid);
            case "place" -> paperTradingService.place(uid, new PaperTradingService.PlaceRequest(args.getString("code"),
                    args.getString("side"), args.getInteger("qty"), args.getBigDecimal("limitPrice"),
                    args.getString("reason"), args.getString("expectation"), args.getInteger("confidence"), "AGENT"))
                    .fluentPut("disclaimer", DISCLAIMER);
            case "cancel" -> paperTradingService.cancel(uid, args.getLong("orderId"));
            case "orders" -> {
                JSONArray out = new JSONArray();
                for (PaperOrder o : paperTradingService.listOrders(uid, args.getString("status"), 20)) {
                    out.add(PaperTradingService.orderJson(o));
                }
                yield out;
            }
            case "performance" -> StringUtils.isBlank(args.getString("from")) ? paperTradingService.performance(uid)
                    : paperTradingService.periodStats(uid, parseDate(args.getString("from")),
                            StringUtils.isBlank(args.getString("to")) ? TradingClock.now().toLocalDate()
                                    : parseDate(args.getString("to")));
            case "review_queue" -> new JSONObject()
                    .fluentPut("orders", paperTradingService.reviewQueue(uid))
                    .fluentPut("how", "逐笔对照 reason / expectation 与实际走势，mark_reviewed 写结论；"
                            + "可复用的规律用 trading_lessons add（附订单 id）或 feedback 已有经验。");
            case "mark_reviewed" -> PaperTradingService.orderJson(paperTradingService.markReviewed(uid,
                    args.getLong("orderId"), args.getString("outcome"), args.getString("note")));
            case "watchlist" -> paperTradingService.watchlistView(uid);
            case "watch" -> {
                JSONArray codes = args.getJSONArray("codes");
                List<String> list = codes != null ? codes.toJavaList(String.class)
                        : StringUtils.isBlank(args.getString("code")) ? List.of() : List.of(args.getString("code"));
                paperTradingService.watch(uid, list);
                yield paperTradingService.watchlistView(uid);
            }
            case "unwatch" -> {
                paperTradingService.unwatch(uid, args.getString("code"));
                yield paperTradingService.watchlistView(uid);
            }
            default -> throw new IllegalArgumentException(
                    "action 只能是 account / place / cancel / orders / performance / review_queue / mark_reviewed / watchlist / watch / unwatch");
        };
    }

    // ------------------------------------------------------------------ trading_lessons

    private static final String LESSON_DESC = "交易经验库（自进化记忆）。list：查看；add：新增 LESSON（必须附已成交订单 id 作证据）或 PREFERENCE（用户明确说出的偏好）；"
            + "feedback：用一笔新成交支持或反驳某条经验（支持≥2 且置信度≥0.6 才生效并注入提示词，反驳多了自动停用）；retire：停用。";
    private static final String LESSON_SCHEMA = """
            {"type":"object","properties":{
            "action":{"type":"string","enum":["list","add","feedback","retire"]},
            "kind":{"type":"string","enum":["LESSON","PREFERENCE","META"],"description":"add 只能用 LESSON / PREFERENCE；list 可按类型过滤"},
            "status":{"type":"string","enum":["CANDIDATE","ACTIVE","RETIRED"],"description":"list：按状态过滤，默认不含 RETIRED"},
            "content":{"type":"string","description":"add：一条可执行、可检验的经验，例如「放量突破 20 日高点后次日低开，3 天内多回落，不追」"},
            "tags":{"type":"string"},
            "evidence":{"type":"array","items":{"type":"integer"},"description":"add LESSON：支持这条经验的已成交订单 id"},
            "lessonId":{"type":"integer"},
            "supports":{"type":"boolean","description":"feedback：true 支持 / false 反驳"},
            "orderId":{"type":"integer","description":"feedback：作为证据的已成交订单 id"}
            },"required":["action"]}""";

    private Object lessons(String uid, JSONObject args) {
        String action = action(args);
        return switch (action) {
            case "list" -> {
                JSONArray out = new JSONArray();
                for (TradingLesson l : lessonService.list(uid, args.getString("kind"), args.getString("status"), 30)) {
                    out.add(lessonJson(l));
                }
                yield out;
            }
            case "add" -> {
                List<Long> evidence = new ArrayList<>();
                JSONArray arr = args.getJSONArray("evidence");
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        evidence.add(arr.getLong(i));
                    }
                }
                yield lessonJson(lessonService.add(uid, args.getString("kind"), args.getString("content"),
                        args.getString("tags"), evidence));
            }
            case "feedback" -> {
                Boolean supports = args.getBoolean("supports");
                if (supports == null) {
                    throw new IllegalArgumentException("supports 必填");
                }
                yield lessonJson(lessonService.feedback(uid, args.getLong("lessonId"), supports, args.getLong("orderId")));
            }
            case "retire" -> lessonJson(lessonService.retire(uid, args.getLong("lessonId")));
            default -> throw new IllegalArgumentException("action 只能是 list / add / feedback / retire");
        };
    }

    // ------------------------------------------------------------------ decision_signal

    private static final String SIGNAL_DESC = "给出可被检验的买卖建议并留痕。你每次对某只股票给出明确判断（买入/加仓/持有/观望/减仓/卖出/回避/风险预警），"
            + "都要用 record 记下来：系统会以「记录时最后一个已收盘交易日的前复权收盘价」为锚定价，在 1/3/5/10 个交易日后自动对照实际走势判定命中或未命中，"
            + "并按动作、周期、市场时点、数据质量分组统计命中率。这让你的分析能力和量化策略一样有证据、能进化。"
            + "record：记录一条建议（code、action、horizon、reason 必填；建议同时写 entryLow/entryHigh、stopLoss、targetPrice、watch，计划越完整越好）；"
            + "stats：你的历史命中率与分组统计（提建议前先看，知道自己哪类判断准）；list：建议与评估结果（可按 status/code 过滤）。"
            + "判定口径：中性带 ±2%，看多需涨 ≥2%，看空/回避需涨幅 ≤2%，持有需跌幅 <2%；WATCH / ALERT 只留痕不计命中率。样本少于 30 条不能当作结论。";
    private static final String SIGNAL_SCHEMA = """
            {"type":"object","properties":{
            "action":{"type":"string","enum":["record","stats","list"]},
            "code":{"type":"string","description":"record 必填；list 可选：6 位股票代码"},
            "advice":{"type":"string","enum":["BUY","ADD","HOLD","WATCH","REDUCE","SELL","AVOID","ALERT"],"description":"record 必填：建议动作"},
            "horizon":{"type":"string","enum":["1d","3d","5d","10d"],"description":"record：评估周期（交易日），默认 5d"},
            "confidence":{"type":"number","description":"record 可选：0–1 的置信度"},
            "entryLow":{"type":"number","description":"record 可选：理想买入价下限"},
            "entryHigh":{"type":"number","description":"record 可选：理想买入价上限"},
            "stopLoss":{"type":"number","description":"record 可选：止损价"},
            "targetPrice":{"type":"number","description":"record 可选：目标价"},
            "reason":{"type":"string","description":"record 必填：至少 10 字，写清技术面/资金面/消息面依据或引用的经验 #id"},
            "risk":{"type":"string","description":"record 可选：主要风险"},
            "watch":{"type":"string","description":"record 可选：需要观察什么、什么情况下判断失效"},
            "status":{"type":"string","enum":["PENDING","HIT","MISS","UNABLE","SUPERSEDED"],"description":"list 可选：按状态过滤"}
            },"required":["action"]}""";

    private Object signal(String uid, JSONObject args, Integer botId) {
        String action = action(args);
        return switch (action) {
            case "record" -> signalService.record(uid, new DecisionSignalService.SignalRequest(
                    args.getString("code"), args.getString("advice"), args.getString("horizon"),
                    args.getDouble("confidence"), args.getBigDecimal("entryLow"), args.getBigDecimal("entryHigh"),
                    args.getBigDecimal("stopLoss"), args.getBigDecimal("targetPrice"), args.getString("reason"),
                    args.getString("risk"), args.getString("watch"), null, botId, null))
                    .fluentPut("disclaimer", DISCLAIMER);
            case "stats" -> signalService.stats(uid);
            case "list" -> signalService.list(uid, args.getString("status"), args.getString("code"), 20);
            default -> throw new IllegalArgumentException("action 只能是 record / stats / list");
        };
    }

    private static JSONObject lessonJson(TradingLesson l) {
        return new JSONObject()
                .fluentPut("lessonId", l.getId())
                .fluentPut("kind", l.getKind())
                .fluentPut("status", l.getStatus())
                .fluentPut("confidence", l.getConfidence())
                .fluentPut("support", l.getSupport())
                .fluentPut("contradict", l.getContradict())
                .fluentPut("content", l.getContent())
                .fluentPut("tags", l.getTags())
                .fluentPut("evidence", l.getEvidence());
    }

    // ------------------------------------------------------------------ strategy_evolve

    private static final String STRATEGY_DESC = "可回测的量化策略与递归自进化：入场信号、仓位方式、止损/移动止损/分批止盈，以及你自己写的自定义规则（customEntry/customExit）都能进化。"
            + "current：当前版本、参数和前进测试中的候选；scan：股票池里今天触发入场信号的股票；universe：股票池的筛选规则、本月名单（含成交额/市值）和每月进出变化（股票池由规则每月筛选，不能手工指定）；backtest：回测当前版本或试算改动（params，不保存，返回闸门对比 gateCheck）；"
            + "propose：提交改动（params + rationale），先过回测闸门（4 年历史、按当月时点的规则股票池、样本外夏普显著提升、6 个窗口、上涨/下跌/震荡阶段、前 100 只扩展验证池），"
            + "通过后进入 10 个交易日前进测试，不差于当前版本才正式采纳；search：自动进化搜索（约 24 个候选）；rollback：回滚到采纳过的版本；history：版本记录。"
            + "处理短线噪音：固定百分比止损对高波动股可能只相当于 1 倍 ATR（一天的正常波动），容易被盘中插针扫出；可提案 stopMode=ATR 让止损按个股波动率自动放宽（仓位会随之变小，风险不增加），"
            + "或 exitCheck=CLOSE / HALF_THEN_CLOSE 用收盘价确认离场。是否采纳由闸门用数据决定。"
            + "自定义规则语法：序列 open high low close volume index；函数 ma ema rsi highest lowest ref std roc(x,n)、atr(n)、volratio(n)、macd_dif() macd_dea()、"
            + "cross_up(a,b) cross_down(a,b)、abs min max；运算 + - * / > < >= <= and or not。highest/lowest 取当前之前 n 根。"
            + "例：均值回归入场 rsi(close,6) < 25 and close > ma(close,120)；ATR 突破 close > highest(high,20) and close - ref(close,1) > atr(14)。";
    private static final String STRATEGY_SCHEMA =
            """
                    {"type":"object","properties":{
                    "action":{"type":"string","enum":["current","scan","universe","backtest","propose","search","rollback","history"]},
                    "params":{"type":"object","description":"只写要改的字段。入场：entryType(MA_CROSS 均线金叉 / BREAKOUT N日新高突破 / MACD 金叉 / ANY 任一)、maFast(3–20)、maSlow(10–120)、crossWithinDays(1–20)、breakoutDays(10–60)、rsiMin(10–60)、rsiMax(50–90)、volumeRatioMin(0.5–3)、requireAboveMa60、marketFilter(上证在 MA20 上方才买)、entryType=CUSTOM 时用 customEntry(规则表达式，替代内置信号和 RSI/量比/MA60 过滤)；仓位：sizing(EQUAL 等权 / RISK 按风险)、riskPerTradePct(0.3–3，止损时最多亏总资产的百分比)、maxPositions(1–10)、maxWeightPct(5–50)；离场：stopMode(PCT 固定百分比 / ATR 按个股波动率)、stopAtrMult(1–5，ATR 模式下止损为成本减 N 倍 ATR14)、stopLossPct(3–20，同时作为最大亏损上限)、exitCheck(INTRADAY 盘中触及即卖 / CLOSE 只在 14:50 按接近收盘价确认 / HALF_THEN_CLOSE 盘中触及先卖一半、收盘确认再清)、trailingStopPct(0 关闭或 3–25，从最高价回撤)、partialTakeProfitPct(0 关闭或 3–40，先卖一半)、takeProfitPct(5–60)、maxHoldDays(3–120)、exitOnMaCrossDown、customExit(收盘检查的额外离场规则表达式，空字符串关闭)；股票池：universeSize(10–50，规则筛选结果取前几只)"},
                    "rationale":{"type":"string","description":"propose 必填：依据哪些复盘结论、经验 #id 或回测现象"},
                    "version":{"type":"integer","description":"rollback：目标版本"},
                    "reason":{"type":"string","description":"rollback：原因"}
                    },"required":["action"]}""";

    private Object strategy(String uid, JSONObject args) {
        String action = action(args);
        return switch (action) {
            case "current" -> strategyService.current(uid);
            case "scan" -> strategyService.scan(uid);
            case "universe" -> strategyService.universe(uid);
            case "search" -> strategyService.autoSearch(uid, null, "智能体");
            case "backtest" -> strategyService.backtest(uid, args.getJSONObject("params"));
            case "propose" -> strategyService.propose(uid, args.getJSONObject("params"), args.getString("rationale"));
            case "rollback" -> strategyService.rollback(uid, args.getInteger("version"), args.getString("reason"));
            case "history" -> {
                JSONArray out = new JSONArray();
                for (TradingStrategyVersion v : strategyService.history(uid, 15)) {
                    JSONObject eval = StringUtils.isBlank(v.getEvaluation()) ? null : JSON.parseObject(v.getEvaluation());
                    out.add(new JSONObject()
                            .fluentPut("version", v.getVersion())
                            .fluentPut("parent", v.getParentVersion())
                            .fluentPut("status", v.getStatus())
                            .fluentPut("createdAt", String.valueOf(v.getCreateTime()))
                            .fluentPut("description", StrategyParams.parse(v.getParams()).describe())
                            .fluentPut("rationale", StringUtils.abbreviate(v.getRationale(), 200))
                            .fluentPut("reasons", eval == null ? null : eval.get("reasons"))
                            .fluentPut("candidateOos", eval == null ? null
                                    : eval.getJSONObject("candidate").getJSONObject("outOfSample")));
                }
                yield out;
            }
            default -> throw new IllegalArgumentException("action 只能是 current / scan / backtest / propose / search / rollback / history");
        };
    }

    // ------------------------------------------------------------------ plumbing

    private static java.time.LocalDate parseDate(String s) {
        try {
            return java.time.LocalDate.parse(s.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd：" + s);
        }
    }

    private static String action(JSONObject args) {
        return StringUtils.lowerCase(StringUtils.trimToEmpty(args.getString("action")), Locale.ROOT);
    }

    /** One tool of the group; failures come back as text the model can act on. */
    private static class AShareTool implements ToolCallback {
        private final String name;
        private final String label;
        private final String description;
        private final String schema;
        private final ChatToolContext context;
        private final BiFunction<String, JSONObject, Object> handler;

        private final Consumer<ChatToolContext> afterCall;
        private volatile boolean remembered;

        AShareTool(String name, String label, String description, String schema, ChatToolContext context,
                BiFunction<String, JSONObject, Object> handler, Consumer<ChatToolContext> afterCall) {
            this.name = name;
            this.label = label;
            this.description = description;
            this.schema = schema;
            this.context = context;
            this.handler = handler;
            this.afterCall = afterCall;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(name).description(description).inputSchema(schema).build();
        }

        @Override
        public String call(String toolInput) {
            long start = System.currentTimeMillis();
            JSONObject args;
            try {
                args = StringUtils.isBlank(toolInput) ? new JSONObject() : JSON.parseObject(toolInput);
            } catch (Exception e) {
                return "调用失败：参数不是合法的 JSON。";
            }
            if (args == null) {
                args = new JSONObject();
            }
            String uid = context.getUserId();
            String action = action(args);
            String result;
            boolean ok = false;
            try {
                Object value = handler.apply(uid, args);
                result = JSON.toJSONString(value, JSONWriter.Feature.WriteBigDecimalAsPlain);
                ok = true;
                if (!remembered) {
                    remembered = true;
                    afterCall.accept(context);
                }
            } catch (IllegalArgumentException e) {
                result = "操作未执行：" + e.getMessage();
            } catch (AShareDataException e) {
                result = "行情数据暂时取不到（东方财富公开接口）：" + e.getMessage() + "。本轮不要重复调用，告诉用户稍后再试。";
            } catch (RuntimeException e) {
                log.warn("A-share tool failed, tool={}, action={}, uid={}, errorType={}, msg={}", name, action, uid,
                        e.getClass().getSimpleName(), e.getMessage(), e);
                result = "操作失败：" + e.getClass().getSimpleName();
            }
            if (result.length() > MAX_RESULT_CHARS) {
                result = result.substring(0, MAX_RESULT_CHARS) + "…（已截断）";
            }
            log.info("A-share tool called, tool={}, action={}, uid={}, ok={}, resultChars={}, costMs={}", name, action,
                    uid, ok, result.length(), System.currentTimeMillis() - start);
            context.addTrace(new JSONObject()
                    .fluentPut("type", "link")
                    .fluentPut("deskToolName", label + "：" + action)
                    .fluentPut("toolName", name)
                    .fluentPut("arguments", args)
                    .fluentPut("content", StringUtils.abbreviate(result, 2000)));
            return result;
        }
    }

    /** Market queries are read-only, so the tool calling manager may run them in parallel. */
    private static final class ParallelAShareTool extends AShareTool implements ParallelToolCalls.ParallelSafe {
        ParallelAShareTool(String name, String label, String description, String schema, ChatToolContext context,
                BiFunction<String, JSONObject, Object> handler, Consumer<ChatToolContext> afterCall) {
            super(name, label, description, schema, context, handler, afterCall);
        }
    }
}
