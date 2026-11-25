package com.iflytek.astron.console.hub.service.ashare;

import com.iflytek.astron.console.hub.entity.ashare.PaperAccount;
import com.iflytek.astron.console.hub.entity.ashare.TradingReviewReport;
import com.iflytek.astron.console.hub.service.ashare.AShareReviewService.ScheduledReview;
import com.iflytek.astron.console.toolkit.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Paper-trading jobs on A-share trading hours (Asia/Shanghai; weekends and holidays are skipped: a
 * holiday has no index trade):
 * <ul>
 * <li>09:26 盘前计划 after the opening call auction.</li>
 * <li>Every minute of continuous trading (09:30–11:30, 13:00–15:00): opening-auction and intraday
 * fills of pending orders, and intraday stop loss / take profit. Nothing runs outside trading
 * hours.</li>
 * <li>14:50 the active strategy's daily rules (exits, entries) when auto-trading is on.</li>
 * <li>15:10 day orders expire and the day's account value is saved.</li>
 * <li>15:12 the rule-based universe's bars are topped up (in the background, about ten minutes);
 * the first run of a month saves the month's universe.</li>
 * <li>15:25 the agents' recorded opinions whose window has passed are scored against what actually
 * happened, and the result is written into the lesson book.</li>
 * <li>15:30 收盘复盘; 16:00 on the last trading day of the week: automatic strategy search, then 周复盘;
 * of the month: automatic rollback check, then 月复盘.</li>
 * </ul>
 * Reviews run on the review service's executor, not on the shared scheduler threads. A Redis lock
 * keeps several hub instances from running the same job. Times can be overridden with
 * {@code astron.ashare.job.*-cron}; {@code astron.ashare.job.enabled=false} turns the jobs off.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "astron.ashare.job.enabled", havingValue = "true", matchIfMissing = true)
public class AShareDailyJob {

    private final PaperTradingService paperTradingService;
    private final AShareReviewService reviewService;
    private final AutoEvolutionService autoEvolutionService;
    private final RuleEvolutionService ruleEvolutionService;
    private final UniverseService universeService;
    private final DecisionSignalService signalService;
    private final RedisUtil redisUtil;

    @Scheduled(cron = "${astron.ashare.job.pre-open-cron:0 26 9 * * MON-FRI}", zone = "Asia/Shanghai")
    public void preOpenReview() {
        locked("pre-open", 600, () -> {
            if (!paperTradingService.isTradingDayToday()) {
                log.info("A-share pre-open review skipped: not a trading day");
                return;
            }
            String today = TradingClock.now().toLocalDate().toString();
            forEachAccount("pre-open", (uid, account) -> {
                boolean active = paperTradingService.hasOpenExposure(uid) || !Objects.equals(account.getAutoTrade(), 0);
                if (!Objects.equals(account.getPreOpenReview(), 0) && active) {
                    reviewService.submitScheduled(uid, List.of(new ScheduledReview(TradingReviewReport.PRE_OPEN, today)));
                }
            });
        });
    }

    /** Every minute; only does work during continuous trading of a trading day. */
    @Scheduled(cron = "${astron.ashare.job.monitor-cron:5 * 9-11,13-14 * * MON-FRI}", zone = "Asia/Shanghai")
    public void monitor() {
        if (!paperTradingService.isTradingNow()) {
            return;
        }
        locked("monitor", 55, () -> {
            long start = System.currentTimeMillis();
            int[] counts = new int[3];
            forEachAccount("monitor", (uid, account) -> {
                Set<String> codes = paperTradingService.watchedCodes(uid);
                if (codes.isEmpty()) {
                    return;
                }
                counts[0]++;
                counts[1] += paperTradingService.settle(uid);
                Map<String, EastmoneyClient.LiveQuote> quotes = paperTradingService.liveQuotes(codes);
                counts[2] += paperTradingService.intradayRiskExits(uid, quotes);
            });
            if (counts[1] + counts[2] > 0) {
                log.info("A-share monitor tick, accounts={}, ordersProcessed={}, riskExits={}, costMs={}", counts[0],
                        counts[1], counts[2], System.currentTimeMillis() - start);
            }
        });
    }

    @Scheduled(cron = "${astron.ashare.job.end-of-day-cron:0 50 14 * * MON-FRI}", zone = "Asia/Shanghai")
    public void endOfDayRules() {
        if (!paperTradingService.isTradingNow()) {
            return;
        }
        locked("end-of-day", 600, () -> {
            int[] counts = new int[2];
            forEachAccount("end-of-day", (uid, account) -> {
                var r = paperTradingService.endOfDayRules(uid);
                counts[0] += r.getIntValue("exits");
                counts[1] += r.getIntValue("entries");
            });
            log.info("A-share end-of-day rules done, exits={}, entries={}", counts[0], counts[1]);
        });
    }

    @Scheduled(cron = "${astron.ashare.job.close-cron:0 10 15 * * MON-FRI}", zone = "Asia/Shanghai")
    public void afterClose() {
        locked("close", 600, () -> {
            if (!paperTradingService.afterCloseOnTradingDay()) {
                return;
            }
            int[] expired = new int[1];
            forEachAccount("close", (uid, account) -> {
                expired[0] += paperTradingService.settle(uid);
                paperTradingService.snapshot(uid);
            });
            log.info("A-share close job done, ordersProcessed={}", expired[0]);
        });
    }

    @Scheduled(cron = "${astron.ashare.job.universe-cron:0 12 15 * * MON-FRI}", zone = "Asia/Shanghai")
    public void universeMaintenance() {
        locked("universe", 300, () -> {
            if (!paperTradingService.afterCloseOnTradingDay()) {
                return;
            }
            boolean started = universeService.maintainAsync("daily");
            log.info("A-share universe maintenance {}", started ? "started" : "skipped: already running");
        });
    }

    /**
     * Scores the agents' opinions that have come due, before the close review runs, so the review sees
     * today's results. Needs the day's bars, which the universe job has just topped up.
     */
    @Scheduled(cron = "${astron.ashare.job.signal-cron:0 25 15 * * MON-FRI}", zone = "Asia/Shanghai")
    public void evaluateSignals() {
        locked("signals", 600, () -> {
            if (!paperTradingService.afterCloseOnTradingDay()) {
                return;
            }
            forEachAccount("signals", (uid, account) -> {
                var r = signalService.evaluateDue(uid);
                if (r.getIntValue("hit") + r.getIntValue("miss") + r.getIntValue("unable") > 0) {
                    signalService.recordStatsLesson(uid);
                }
            });
        });
    }

    @Scheduled(cron = "${astron.ashare.job.review-cron:0 30 15 * * MON-FRI}", zone = "Asia/Shanghai")
    public void closeReview() {
        locked("close-review", 600, () -> {
            if (!paperTradingService.afterCloseOnTradingDay()) {
                log.info("A-share close review skipped: not a trading day");
                return;
            }
            String today = TradingClock.now().toLocalDate().toString();
            forEachAccount("close-review", (uid, account) -> {
                if (!Objects.equals(account.getAutoReview(), 0) && paperTradingService.hasReviewableActivity(uid)) {
                    reviewService.submitScheduled(uid, List.of(new ScheduledReview(TradingReviewReport.CLOSE, today)));
                }
            });
        });
    }

    @Scheduled(cron = "${astron.ashare.job.period-review-cron:0 0 16 * * MON-FRI}", zone = "Asia/Shanghai")
    public void periodReviews() {
        locked("period-review", 600, () -> {
            if (!paperTradingService.afterCloseOnTradingDay()) {
                return;
            }
            LocalDate today = TradingClock.now().toLocalDate();
            List<LocalDate> calendar = paperTradingService.tradingCalendar();
            forEachAccount("period-review", (uid, account) -> {
                boolean evolve = !Objects.equals(account.getAutoEvolve(), 0);
                // Forward test of trial versions on the new day's data.
                if (evolve) {
                    autoEvolutionService.dailyTrials(uid);
                }
                // Automatic evolution first, so the reviews see its outcome.
                String week = TradingClock.duePeriod(today, calendar, List.of(), true);
                if (evolve && week != null) {
                    autoEvolutionService.weeklySearch(uid, week);
                }
                String month = TradingClock.duePeriod(today, calendar, List.of(), false);
                if (evolve && month != null) {
                    autoEvolutionService.monthlyRollbackCheck(uid);
                    // Inventing rules costs statistical power (every attempt raises the deflation bar),
                    // so it runs monthly while the parameter search runs weekly.
                    try {
                        ruleEvolutionService.evolve(uid, 2, "每月定时");
                    } catch (RuntimeException e) {
                        log.warn("Monthly rule evolution failed, uid={}, errorType={}, msg={}", uid,
                                e.getClass().getSimpleName(), e.getMessage());
                    }
                }
                List<ScheduledReview> due = new ArrayList<>();
                addDue(due, uid, account.getWeeklyReview(), TradingReviewReport.WEEKLY, true, today, calendar);
                addDue(due, uid, account.getMonthlyReview(), TradingReviewReport.MONTHLY, false, today, calendar);
                reviewService.submitScheduled(uid, due);
            });
        });
    }

    private void addDue(List<ScheduledReview> due, String uid, Integer enabled, String type, boolean weekly,
            LocalDate today, List<LocalDate> calendar) {
        if (Objects.equals(enabled, 0)) {
            return;
        }
        String period = TradingClock.duePeriod(today, calendar, reviewService.reviewedPeriods(uid, type), weekly);
        if (period == null) {
            return;
        }
        boolean current = period.equals(TradingClock.periodKey(today, weekly));
        LocalDate ref = current ? today : TradingClock.periodStart(today, weekly).minusDays(1);
        LocalDate from = TradingClock.periodStart(ref, weekly);
        if (paperTradingService.hasActivityBetween(uid, from, today)) {
            due.add(new ScheduledReview(type, period));
        }
    }

    private void forEachAccount(String job, java.util.function.BiConsumer<String, PaperAccount> action) {
        for (String uid : paperTradingService.allUids()) {
            try {
                action.accept(uid, paperTradingService.account(uid));
            } catch (RuntimeException e) {
                log.warn("A-share job failed for account, job={}, uid={}, errorType={}, msg={}", job, uid,
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    private void locked(String name, long ttlSeconds, Runnable action) {
        String key = "ashare:job:" + name;
        String token = UUID.randomUUID().toString();
        if (!redisUtil.tryLock(key, ttlSeconds, token)) {
            log.debug("A-share job skipped: running elsewhere, job={}", name);
            return;
        }
        try {
            action.run();
        } finally {
            redisUtil.unlock(key, token);
        }
    }
}
