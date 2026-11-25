package com.iflytek.astron.console.hub.controller.ashare;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.iflytek.astron.console.commons.response.ApiResult;
import com.iflytek.astron.console.commons.util.RequestContextUtil;
import com.iflytek.astron.console.commons.util.space.SpaceInfoUtil;
import com.iflytek.astron.console.hub.entity.ashare.PaperAccount;
import com.iflytek.astron.console.hub.entity.ashare.PaperOrder;
import com.iflytek.astron.console.hub.entity.ashare.TradingLesson;
import com.iflytek.astron.console.hub.entity.ashare.TradingReviewReport;
import com.iflytek.astron.console.hub.entity.ashare.TradingStrategyVersion;
import com.iflytek.astron.console.hub.service.ashare.AShareDataException;
import com.iflytek.astron.console.hub.service.ashare.AShareReviewService;
import com.iflytek.astron.console.hub.service.ashare.LessonService;
import com.iflytek.astron.console.hub.service.ashare.PaperTradingService;
import com.iflytek.astron.console.hub.service.ashare.StrategyParams;
import com.iflytek.astron.console.hub.service.ashare.DecisionSignalService;
import com.iflytek.astron.console.hub.service.ashare.MarketPackService;
import com.iflytek.astron.console.hub.service.ashare.StrategyService;
import com.iflytek.astron.console.hub.service.ashare.UniverseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 模拟炒股 page: the user's A-share paper account, its trading memory (lessons, strategy versions,
 * review reports) and automation settings. Everything is scoped to the signed-in user.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "A-share paper trading")
@RequestMapping("/a-share")
public class AShareController {

    private final PaperTradingService paperTradingService;
    private final LessonService lessonService;
    private final StrategyService strategyService;
    private final AShareReviewService reviewService;
    private final UniverseService universeService;
    private final DecisionSignalService signalService;
    private final MarketPackService packService;

    @GetMapping("/overview")
    @Operation(summary = "Account value, positions, pending orders and performance")
    public ApiResult<JSONObject> overview() {
        String uid = RequestContextUtil.getUID();
        return guarded(() -> {
            JSONObject performance = paperTradingService.performance(uid);
            JSONObject portfolio = paperTradingService.portfolio(uid);
            return performance
                    .fluentPut("initialCash", portfolio.get("initialCash"))
                    .fluentPut("cash", portfolio.get("cash"))
                    .fluentPut("frozenCash", portfolio.get("frozenCash"))
                    .fluentPut("marketValue", portfolio.get("marketValue"))
                    .fluentPut("pendingOrders", portfolio.get("pendingOrders"));
        });
    }

    @GetMapping("/orders")
    @Operation(summary = "Recent orders")
    public ApiResult<JSONArray> orders(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        String uid = RequestContextUtil.getUID();
        return guarded(() -> {
            JSONArray out = new JSONArray();
            for (PaperOrder o : paperTradingService.listOrders(uid, status, limit)) {
                out.add(PaperTradingService.orderJson(o));
            }
            return out;
        });
    }

    @GetMapping("/lessons")
    @Operation(summary = "Trading lessons")
    public ApiResult<JSONArray> lessons(@RequestParam(required = false) String kind,
            @RequestParam(required = false) String status) {
        String uid = RequestContextUtil.getUID();
        JSONArray out = new JSONArray();
        for (TradingLesson l : lessonService.list(uid, kind, status, 50)) {
            out.add(JSONObject.from(l).fluentPut("uid", null));
        }
        return ApiResult.success(out);
    }

    @PostMapping("/lessons/retire")
    @Operation(summary = "Retire a lesson")
    public ApiResult<JSONObject> retireLesson(@RequestBody IdForm form) {
        String uid = RequestContextUtil.getUID();
        return guarded(() -> JSONObject.from(lessonService.retire(uid, form.getId())).fluentPut("uid", null));
    }

    @PostMapping("/lessons/note")
    @Operation(summary = "Write a finding into the lesson book as an evolution record")
    public ApiResult<JSONObject> addNote(@RequestBody NoteForm form) {
        String uid = RequestContextUtil.getUID();
        return guarded(() -> {
            String content = StringUtils.trimToEmpty(form.getContent());
            if (content.length() < 10) {
                throw new IllegalArgumentException("content 至少 10 字");
            }
            String tags = StringUtils.isBlank(form.getTags()) ? "MANUAL_NOTE"
                    : "MANUAL_NOTE," + form.getTags().trim();
            lessonService.recordMeta(uid, content, null, tags);
            log.info("Lesson note recorded, uid={}, tags={}, chars={}", uid, tags, content.length());
            return new JSONObject().fluentPut("recorded", true).fluentPut("tags", tags);
        });
    }

    @GetMapping("/strategy")
    @Operation(summary = "Active strategy and version history")
    public ApiResult<JSONObject> strategy() {
        String uid = RequestContextUtil.getUID();
        return guarded(() -> {
            JSONArray history = new JSONArray();
            for (TradingStrategyVersion v : strategyService.history(uid, 30)) {
                history.add(new JSONObject()
                        .fluentPut("version", v.getVersion())
                        .fluentPut("parentVersion", v.getParentVersion())
                        .fluentPut("status", v.getStatus())
                        .fluentPut("createTime", String.valueOf(v.getCreateTime()))
                        .fluentPut("description", StrategyParams.parse(v.getParams()).describe())
                        .fluentPut("rationale", v.getRationale())
                        .fluentPut("evaluation", StringUtils.isBlank(v.getEvaluation()) ? null
                                : JSON.parseObject(v.getEvaluation())));
            }
            return new JSONObject().fluentPut("current", strategyService.current(uid)).fluentPut("history", history);
        });
    }

    @PostMapping("/strategy/backtest")
    @Operation(summary = "Backtest the active version, or the active version with a patch applied")
    public ApiResult<JSONObject> backtest(@RequestBody(required = false) JSONObject patch) {
        String uid = RequestContextUtil.getUID();
        return guarded(() -> strategyService.backtest(uid, patch));
    }

    @PostMapping("/strategy/search")
    @Operation(summary = "Run the automatic strategy search now")
    public ApiResult<JSONObject> searchStrategy() {
        String uid = RequestContextUtil.getUID();
        return guarded(() -> strategyService.autoSearch(uid, null, "页面手动"));
    }

    @GetMapping("/universe")
    @Operation(summary = "Rule-based stock universe: rules, monthly snapshots and point-in-time history")
    public ApiResult<JSONObject> universe() {
        String uid = RequestContextUtil.getUID();
        return guarded(() -> strategyService.universe(uid));
    }

    @PostMapping("/strategy/auto-universe")
    @Operation(summary = "Switch the active version to the rule-based universe (method change, no gate)")
    public ApiResult<JSONObject> switchToAutoUniverse() {
        String uid = RequestContextUtil.getUID();
        return guarded(() -> strategyService.switchToAutoUniverse(uid, StrategyParams.DEFAULT_UNIVERSE_SIZE,
                "页面操作：股票池改为规则筛选"));
    }

    @PostMapping("/universe/refresh")
    @Operation(summary = "Re-screen the universe now (background; replaces this month's snapshot)")
    public ApiResult<JSONObject> refreshUniverse() {
        String uid = RequestContextUtil.getUID();
        return guarded(() -> {
            String refused = universeService.manualRefresh();
            log.info("Universe refresh requested, uid={}, started={}", uid, refused == null);
            return new JSONObject().fluentPut("started", refused == null)
                    .fluentPut("message", refused == null ? "已开始后台更新，约 10 分钟，进度见本页" : refused);
        });
    }

    @PostMapping("/positions/import")
    @Operation(summary = "Sync a position held in the user's real brokerage account")
    public ApiResult<JSONObject> importPosition(@RequestBody ImportForm form) {
        String uid = RequestContextUtil.getUID();
        return guarded(() -> paperTradingService.importPosition(uid, form.getCode(), form.getQty(), form.getAvgCost(),
                StringUtils.isBlank(form.getBuyDate()) ? null : java.time.LocalDate.parse(form.getBuyDate()),
                form.getNote()));
    }

    @GetMapping("/signals")
    @Operation(summary = "The agents' recorded opinions and how they turned out")
    public ApiResult<JSONObject> signals(@RequestParam(required = false) String status,
            @RequestParam(required = false) String code) {
        String uid = RequestContextUtil.getUID();
        return guarded(() -> new JSONObject()
                .fluentPut("stats", signalService.stats(uid))
                .fluentPut("signals", signalService.list(uid, status, code, 50)));
    }

    @PostMapping("/signals/evaluate")
    @Operation(summary = "Score the opinions whose window has passed now")
    public ApiResult<JSONObject> evaluateSignals() {
        String uid = RequestContextUtil.getUID();
        return guarded(() -> {
            JSONObject r = signalService.evaluateDue(uid);
            if (r.getIntValue("hit") + r.getIntValue("miss") + r.getIntValue("unable") > 0) {
                signalService.recordStatsLesson(uid);
            }
            return r;
        });
    }

    @GetMapping("/market")
    @Operation(summary = "Market state: indices, breadth, leading sectors and data quality")
    public ApiResult<JSONObject> marketOverview() {
        return guarded(() -> packService.overview().toJson());
    }

    @GetMapping("/watchlist")
    @Operation(summary = "Watchlist with quotes and today's strategy signal")
    public ApiResult<JSONObject> watchlist() {
        String uid = RequestContextUtil.getUID();
        return guarded(() -> paperTradingService.watchlistView(uid));
    }

    @PostMapping("/watchlist/add")
    @Operation(summary = "Add stocks to the watchlist")
    public ApiResult<JSONObject> watch(@RequestBody WatchForm form) {
        String uid = RequestContextUtil.getUID();
        return guarded(() -> {
            java.util.List<String> codes = form.getCodes() != null ? form.getCodes()
                    : form.getCode() == null ? java.util.List.of() : java.util.List.of(form.getCode());
            paperTradingService.watch(uid, codes);
            return paperTradingService.watchlistView(uid);
        });
    }

    @PostMapping("/watchlist/remove")
    @Operation(summary = "Remove a stock from the watchlist")
    public ApiResult<JSONObject> unwatch(@RequestBody WatchForm form) {
        String uid = RequestContextUtil.getUID();
        return guarded(() -> {
            paperTradingService.unwatch(uid, form.getCode());
            return paperTradingService.watchlistView(uid);
        });
    }

    @GetMapping("/reports")
    @Operation(summary = "Review reports")
    public ApiResult<JSONArray> reports(@RequestParam(required = false) String type,
            @RequestParam(defaultValue = "20") int limit) {
        String uid = RequestContextUtil.getUID();
        JSONArray out = new JSONArray();
        for (TradingReviewReport r : reviewService.list(uid, type, limit)) {
            out.add(JSONObject.from(r).fluentPut("uid", null));
        }
        return ApiResult.success(out);
    }

    @PostMapping("/review/run")
    @Operation(summary = "Start a review now")
    public ApiResult<JSONObject> runReview(@RequestBody(required = false) ReviewForm form) {
        String uid = RequestContextUtil.getUID();
        String type = form == null ? null : form.getType();
        return guarded(() -> JSONObject.from(reviewService.startManual(uid, type)).fluentPut("uid", null));
    }

    @GetMapping("/settings")
    @Operation(summary = "Automation settings and selectable review agents")
    public ApiResult<JSONObject> settings() {
        String uid = RequestContextUtil.getUID();
        return guarded(() -> settingsJson(uid, paperTradingService.account(uid)));
    }

    @PostMapping("/settings")
    @Operation(summary = "Update automation settings")
    public ApiResult<JSONObject> updateSettings(@RequestBody SettingsForm form) {
        String uid = RequestContextUtil.getUID();
        return guarded(() -> {
            if (form.getReviewBotId() != null) {
                reviewService.requireCandidate(uid, SpaceInfoUtil.getSpaceId(), form.getReviewBotId());
            }
            PaperAccount account = paperTradingService.updateSettings(uid, new PaperTradingService.Settings(
                    form.getAutoTrade(), form.getPreOpenReview(), form.getAutoReview(), form.getWeeklyReview(),
                    form.getMonthlyReview(), form.getAutoEvolve(), form.getReviewBotId(),
                    form.getWatchlistAutoTrade()));
            return settingsJson(uid, account);
        });
    }

    private JSONObject settingsJson(String uid, PaperAccount account) {
        return new JSONObject()
                .fluentPut("autoReview", !Objects.equals(account.getAutoReview(), 0))
                .fluentPut("preOpenReview", !Objects.equals(account.getPreOpenReview(), 0))
                .fluentPut("weeklyReview", !Objects.equals(account.getWeeklyReview(), 0))
                .fluentPut("monthlyReview", !Objects.equals(account.getMonthlyReview(), 0))
                .fluentPut("autoEvolve", !Objects.equals(account.getAutoEvolve(), 0))
                .fluentPut("autoTrade", !Objects.equals(account.getAutoTrade(), 0))
                .fluentPut("watchlistAutoTrade", !Objects.equals(account.getWatchlistAutoTrade(), 0))
                .fluentPut("tradingNow", paperTradingService.isTradingNow())
                .fluentPut("reviewBotId", account.getReviewBotId())
                .fluentPut("reviewBots", reviewService.candidates(uid, SpaceInfoUtil.getSpaceId()));
    }

    /** Rule violations and market data outages are shown to the user as a message, not a 500. */
    private static <T> ApiResult<T> guarded(Supplier<T> action) {
        try {
            return ApiResult.success(action.get());
        } catch (IllegalArgumentException e) {
            return ApiResult.error(-1, e.getMessage());
        } catch (AShareDataException e) {
            return ApiResult.error(-1, "行情数据暂时取不到（东方财富公开接口）：" + e.getMessage());
        }
    }

    @Data
    public static class IdForm {
        private Long id;
    }

    @Data
    public static class ReviewForm {
        /** PRE_OPEN / CLOSE / WEEKLY / MONTHLY; default CLOSE. */
        private String type;
    }

    @Data
    public static class SettingsForm {
        private Boolean autoReview;
        private Boolean preOpenReview;
        private Boolean weeklyReview;
        private Boolean monthlyReview;
        private Boolean autoEvolve;
        private Boolean autoTrade;
        private Integer reviewBotId;
        private Boolean watchlistAutoTrade;
    }

    @Data
    public static class NoteForm {
        private String content;
        /** Extra comma-separated markers; MANUAL_NOTE is always added. */
        private String tags;
    }

    @Data
    public static class ImportForm {
        private String code;
        private Integer qty;
        /** Broker's average cost per share, fees included. */
        private java.math.BigDecimal avgCost;
        /** yyyy-MM-dd, default today. */
        private String buyDate;
        private String note;
    }

    @Data
    public static class WatchForm {
        /** add: one or more codes. */
        private java.util.List<String> codes;
        /** remove: one code. */
        private String code;
    }
}
