package com.iflytek.astron.console.hub.service.ashare;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iflytek.astron.console.hub.entity.ashare.PaperAccount;
import com.iflytek.astron.console.hub.entity.ashare.PaperOrder;
import com.iflytek.astron.console.hub.entity.ashare.PaperPosition;
import com.iflytek.astron.console.hub.entity.ashare.PaperSnapshot;
import com.iflytek.astron.console.hub.mapper.ashare.PaperAccountMapper;
import com.iflytek.astron.console.hub.mapper.ashare.PaperOrderMapper;
import com.iflytek.astron.console.hub.mapper.ashare.PaperPositionMapper;
import com.iflytek.astron.console.hub.mapper.ashare.PaperSnapshotMapper;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Bar;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Quote;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Series;
import com.iflytek.astron.console.hub.dto.notification.SendNotificationRequest;
import com.iflytek.astron.console.hub.enums.NotificationType;
import com.iflytek.astron.console.hub.service.notification.NotificationService;
import com.iflytek.astron.console.toolkit.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * One simulated A-share account per user (50,000 virtual cash). Orders are day orders that fill at
 * the open of the first trading session after they are placed, so the agent can never trade on a
 * price it has already seen. A buy is not filled at a limit-up open, a sell not at a limit-down
 * open, and neither while suspended. Buys reserve cash for the worst-case open; T+1 is enforced at
 * fill.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperTradingService {

    public static final BigDecimal INITIAL_CASH = new BigDecimal("50000.00");
    static final int MAX_ORDERS_PER_DAY = 20;
    private static final LocalTime OPEN = LocalTime.of(9, 30);
    private static final LocalTime CLOSE = LocalTime.of(15, 0);
    private static final int CALENDAR_DAYS = 60;
    /** Grace period after the open for the stock's own bar to appear before it counts as suspended. */
    private static final LocalTime FILL_DATA_GRACE = LocalTime.of(9, 40);

    private final PaperAccountMapper accountMapper;
    private final PaperPositionMapper positionMapper;
    private final PaperOrderMapper orderMapper;
    private final PaperSnapshotMapper snapshotMapper;
    private final EastmoneyClient market;
    private final StrategyService strategyService;
    private final RedisUtil redisUtil;
    private final TransactionTemplate transactionTemplate;
    private final NotificationService notificationService;

    /** An order request from the agent or the strategy's automatic exits. */
    public record PlaceRequest(String code, String side, Integer qty, BigDecimal limitPrice, String reason,
            String expectation, Integer confidence, String source) {}

    public PaperAccount account(String uid) {
        if (StringUtils.isBlank(uid)) {
            throw new IllegalArgumentException("缺少用户身份");
        }
        PaperAccount a = findAccount(uid);
        if (a != null) {
            return a;
        }
        PaperAccount created = new PaperAccount();
        created.setUid(uid);
        created.setInitialCash(INITIAL_CASH);
        created.setCash(INITIAL_CASH);
        created.setFrozenCash(BigDecimal.ZERO);
        try {
            accountMapper.insert(created);
            log.info("Paper account created, uid={}, accountId={}, cash={}", uid, created.getId(), INITIAL_CASH);
            return findAccount(uid);
        } catch (DuplicateKeyException e) {
            return findAccount(uid);
        }
    }

    private PaperAccount findAccount(String uid) {
        return accountMapper.selectOne(Wrappers.lambdaQuery(PaperAccount.class).eq(PaperAccount::getUid, uid));
    }

    public List<String> allUids() {
        return accountMapper.selectList(Wrappers.lambdaQuery(PaperAccount.class).select(PaperAccount::getUid))
                .stream()
                .map(PaperAccount::getUid)
                .toList();
    }

    // ------------------------------------------------------------------ orders

    /**
     * Places an order. During continuous trading (09:30–11:30, 13:00–15:00 on a trading day) a market
     * order fills at once at the latest price plus slippage, and a limit order fills as soon as the
     * price reaches it; otherwise the order joins the next opening auction and fills at that day's
     * open. Orders are valid for one trading day. T+1: shares bought today can be sold from the next
     * trading day. No buy while the stock is sealed at the limit up, no sell at the limit down.
     */
    public JSONObject place(String uid, PlaceRequest req) {
        String code = AShareRules.normalizeCode(req.code());
        String side = StringUtils.upperCase(StringUtils.trimToEmpty(req.side()), Locale.ROOT);
        boolean buy = PaperOrder.BUY.equals(side);
        if (!buy && !PaperOrder.SELL.equals(side)) {
            throw new IllegalArgumentException("side 只能是 BUY 或 SELL");
        }
        int qty = req.qty() == null ? 0 : req.qty();
        boolean strategy = "STRATEGY".equals(req.source());
        if (!strategy && StringUtils.length(StringUtils.trimToEmpty(req.reason())) < 10) {
            throw new IllegalArgumentException("reason 必填（至少 10 字）：写清下单依据，复盘时要对照");
        }
        if (!strategy && buy && StringUtils.isBlank(req.expectation())) {
            throw new IllegalArgumentException("买入必须写 expectation：预期涨跌幅、持有周期和止损位，复盘时检验");
        }
        Integer confidence = req.confidence() == null ? null : Math.max(1, Math.min(5, req.confidence()));
        settle(uid);
        Quote quote = market.quote(code);
        BigDecimal latest = quote.lastOrPrevClose();
        if (latest == null || latest.signum() <= 0) {
            throw new IllegalArgumentException("取不到 " + code + " 的价格，暂不能下单");
        }
        LocalDateTime now = TradingClock.now();
        LocalDate today = now.toLocalDate();
        boolean tradingDay = isTradingDayToday();
        LocalTime t = now.toLocalTime();
        // Valid today: placed between the open and the close of a trading day (lunch break included).
        boolean intraday = tradingDay && !t.isBefore(TradingClock.OPEN) && t.isBefore(TradingClock.CLOSE);
        boolean continuous = tradingDay && TradingClock.inContinuousSession(t);
        // Price limits of the day the order is valid for: today's while trading, else the next session's
        // (based on the latest close).
        BigDecimal base = intraday || quote.prevClose() == null ? quote.prevClose() : latest;
        if (base == null) {
            base = latest;
        }
        BigDecimal limitUp = AShareRules.limitUp(base, code, quote.name());
        BigDecimal limitDown = AShareRules.limitDown(base, code, quote.name());
        BigDecimal limitPrice = req.limitPrice();
        if (limitPrice != null) {
            String bad = AShareRules.checkLimitPrice(limitPrice, base, code, quote.name());
            if (bad != null) {
                throw new IllegalArgumentException(bad);
            }
            limitPrice = limitPrice.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal finalLimitPrice = limitPrice;
        StrategyParams params = strategyService.activeParams(uid);
        Integer strategyVersion = strategyService.active(uid).getVersion();
        Map<String, BigDecimal> prices = positionPrices(uid);
        return withAccountLock(uid, () -> {
            PaperAccount account = account(uid);
            long todayOrders = orderMapper.selectCount(Wrappers.lambdaQuery(PaperOrder.class)
                    .eq(PaperOrder::getAccountId, account.getId())
                    .ge(PaperOrder::getPlacedAt, today.atStartOfDay()));
            if (todayOrders >= MAX_ORDERS_PER_DAY) {
                throw new IllegalArgumentException("今天已下 " + todayOrders + " 单，达到每日上限 " + MAX_ORDERS_PER_DAY);
            }
            List<PaperPosition> positions = positions(account.getId());
            List<PaperOrder> pending = orders(account.getId(), PaperOrder.PENDING, 100);
            PaperPosition pos = positions.stream().filter(p -> p.getCode().equals(code)).findFirst().orElse(null);
            PaperOrder order = new PaperOrder();
            order.setAccountId(account.getId());
            order.setCode(code);
            order.setName(quote.name());
            order.setSide(side);
            order.setOrderType(finalLimitPrice == null ? PaperOrder.MARKET : PaperOrder.LIMIT);
            order.setLimitPrice(finalLimitPrice);
            order.setQty(qty);
            order.setStatus(PaperOrder.PENDING);
            order.setSource(strategy ? "STRATEGY" : "AGENT");
            order.setPlacedAt(now);
            order.setReason(StringUtils.abbreviate(StringUtils.trimToNull(req.reason()), 4000));
            order.setExpectation(StringUtils.abbreviate(StringUtils.trimToNull(req.expectation()), 1000));
            order.setConfidence(confidence);
            order.setStrategyVersion(strategyVersion);
            order.setReviewStatus("NONE");
            if (buy) {
                String bad = AShareRules.checkQty(code, true, qty, 0);
                if (bad != null) {
                    throw new IllegalArgumentException(bad);
                }
                BigDecimal refPrice = finalLimitPrice != null ? finalLimitPrice : latest;
                checkStrategyLimits(params, account, positions, pending, prices, code, qty, refPrice);
                // Cash reserved until the fill: the limit price, the current fill price, or the worst case
                // (the next session's limit up) for an order that waits for the opening auction.
                BigDecimal reservePrice = finalLimitPrice != null ? finalLimitPrice
                        : continuous ? AShareRules.buyFillPrice(latest, limitUp) : limitUp;
                BigDecimal amount = reservePrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
                BigDecimal freeze = amount.add(AShareRules.fees(true, amount).total());
                if (account.getCash().compareTo(freeze) < 0) {
                    int maxQty = AShareRules.maxBuyQty(code, reservePrice.doubleValue(),
                            account.getCash().doubleValue() * 0.998 - 5);
                    throw new IllegalArgumentException("可用资金 " + account.getCash() + " 不足：需冻结 " + freeze
                            + "（按 " + reservePrice + " 元计），当前最多可买约 " + maxQty + " 股");
                }
                order.setFrozenAmount(freeze);
                account.setCash(account.getCash().subtract(freeze));
                account.setFrozenCash(account.getFrozenCash().add(freeze));
                transactionTemplate.executeWithoutResult(s -> {
                    orderMapper.insert(order);
                    updateAccount(account);
                });
            } else {
                if (pos == null) {
                    throw new IllegalArgumentException("没有 " + code + " 的持仓");
                }
                int pendingSell = pending.stream()
                        .filter(o -> o.getCode().equals(code) && PaperOrder.SELL.equals(o.getSide()))
                        .mapToInt(PaperOrder::getQty)
                        .sum();
                // T+1: an order valid today cannot sell what was bought today.
                int boughtToday = intraday && today.equals(pos.getLastBuyDate()) ? pos.getLastBuyQty() : 0;
                int sellable = pos.getQty() - pendingSell - boughtToday;
                if (sellable <= 0) {
                    throw new IllegalArgumentException(boughtToday > 0 && pendingSell < pos.getQty() - boughtToday + 1
                            ? code + " 今日买入的 " + boughtToday + " 股要下一个交易日才能卖（T+1）"
                            : code + " 没有可卖的股份（已挂卖单或 T+1 限制）");
                }
                if (qty > sellable) {
                    throw new IllegalArgumentException("可卖数量只有 " + sellable + " 股"
                            + (boughtToday > 0 ? "（今日买入的 " + boughtToday + " 股受 T+1 限制）" : ""));
                }
                String bad = AShareRules.checkQty(code, false, qty, sellable);
                if (bad != null) {
                    throw new IllegalArgumentException(bad + "（或一次卖出全部 " + sellable + " 股）");
                }
                order.setFrozenAmount(BigDecimal.ZERO);
                orderMapper.insert(order);
            }
            log.info("Paper order placed, uid={}, orderId={}, side={}, type={}, limit={}, code={}, qty={}, frozen={}, source={}, intraday={}",
                    uid, order.getId(), side, order.getOrderType(), finalLimitPrice, code, qty, order.getFrozenAmount(),
                    order.getSource(), intraday);
            String fill;
            if (continuous) {
                FillDecision d = decideLive(side, order.getOrderType(), finalLimitPrice, code, quote.name(), quote.price(),
                        quote.prevClose(), quote.time() != null && quote.time().toLocalDate().equals(today));
                applyLive(uid, order, d, today, now);
                fill = switch (d.kind()) {
                    case FILL -> "已按 " + d.price() + " 元成交（最新价 " + latest + "，含 0.1% 滑点）";
                    case REJECT -> "未成交：" + d.reason();
                    case WAIT -> "限价 " + finalLimitPrice + " 元挂单中，价格到达即成交；今日收盘未成交自动失效";
                };
            } else if (intraday) {
                fill = "午间休市，13:00 开盘后按最新价" + (finalLimitPrice == null ? "成交" : "达到限价时成交") + "；今日收盘未成交自动失效";
            } else {
                fill = "非交易时段：参与下一交易日开盘集合竞价，按开盘价" + (finalLimitPrice == null ? "" : "（不高于/不低于限价）")
                        + "成交；开盘" + (buy ? "涨停" : "跌停") + "或停牌则不成交，当日未成交自动失效";
            }
            PaperOrder saved = orderMapper.selectById(order.getId());
            return new JSONObject()
                    .fluentPut("orderId", order.getId())
                    .fluentPut("status", saved.getStatus())
                    .fluentPut("side", side)
                    .fluentPut("orderType", order.getOrderType())
                    .fluentPut("limitPrice", finalLimitPrice)
                    .fluentPut("code", code)
                    .fluentPut("name", quote.name())
                    .fluentPut("qty", qty)
                    .fluentPut("latestPrice", latest)
                    .fluentPut("fillPrice", saved.getFillPrice())
                    .fluentPut("frozenCash", PaperOrder.PENDING.equals(saved.getStatus()) ? order.getFrozenAmount() : 0)
                    .fluentPut("fill", fill);
        });
    }

    /** Rejects buys that would break the active strategy's position count or weight limits. */
    private void checkStrategyLimits(StrategyParams params, PaperAccount account, List<PaperPosition> positions,
            List<PaperOrder> pending, Map<String, BigDecimal> prices, String code, int qty, BigDecimal latest) {
        Set<String> names = new HashSet<>();
        positions.forEach(p -> names.add(p.getCode()));
        pending.stream().filter(o -> PaperOrder.BUY.equals(o.getSide())).forEach(o -> names.add(o.getCode()));
        if (!names.contains(code) && names.size() >= params.getMaxPositions()) {
            throw new IllegalArgumentException("持仓和待买已有 " + names.size() + " 只，达到策略上限 " + params.getMaxPositions()
                    + " 只（可通过 strategy_evolve 调整）");
        }
        BigDecimal marketValue = BigDecimal.ZERO;
        BigDecimal exposure = BigDecimal.ZERO;
        for (PaperPosition p : positions) {
            BigDecimal v = prices.getOrDefault(p.getCode(), p.getCostAmount()
                    .divide(BigDecimal.valueOf(p.getQty()), 3,
                            RoundingMode.HALF_UP))
                    .multiply(BigDecimal.valueOf(p.getQty()));
            marketValue = marketValue.add(v);
            if (p.getCode().equals(code)) {
                exposure = exposure.add(v);
            }
        }
        for (PaperOrder o : pending) {
            if (PaperOrder.BUY.equals(o.getSide()) && o.getCode().equals(code)) {
                exposure = exposure.add(latest.multiply(BigDecimal.valueOf(o.getQty())));
            }
        }
        BigDecimal equity = account.getCash().add(account.getFrozenCash()).add(marketValue);
        BigDecimal after = exposure.add(latest.multiply(BigDecimal.valueOf(qty)));
        double weight = after.doubleValue() / equity.doubleValue() * 100;
        if (weight > params.getMaxWeightPct() + 0.5) {
            double room = equity.doubleValue() * params.getMaxWeightPct() / 100 - exposure.doubleValue();
            int maxQty = AShareRules.maxBuyQty(code, latest.doubleValue(), room);
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "买入后 %s 仓位约 %.1f%%，超过策略单只上限 %.0f%%，最多还能买约 %d 股", code, weight,
                    params.getMaxWeightPct(), maxQty));
        }
    }

    public JSONObject cancel(String uid, Long orderId) {
        return withAccountLock(uid, () -> {
            PaperAccount account = account(uid);
            PaperOrder order = orderId == null ? null : orderMapper.selectById(orderId);
            if (order == null || !Objects.equals(order.getAccountId(), account.getId())) {
                throw new IllegalArgumentException("没有这个订单：" + orderId);
            }
            if (!PaperOrder.PENDING.equals(order.getStatus())) {
                throw new IllegalArgumentException("订单状态为 " + order.getStatus() + "，不能撤单");
            }
            List<LocalDate> cal = calendarWithToday();
            LocalDate session = TradingClock.orderSession(order.getPlacedAt(), cal);
            LocalDateTime now = TradingClock.now();
            // The opening auction does not accept cancellations from 09:20 until it ends.
            if (session != null && session.equals(now.toLocalDate()) && !TradingClock.placedIntraday(order.getPlacedAt(), cal)
                    && !now.toLocalTime().isBefore(TradingClock.AUCTION_NO_CANCEL)
                    && now.toLocalTime().isBefore(TradingClock.OPEN)) {
                throw new IllegalArgumentException("09:20–09:30 集合竞价期间不能撤单");
            }
            transactionTemplate.executeWithoutResult(s -> {
                order.setStatus(PaperOrder.CANCELLED);
                updateOrder(order);
                release(account, order.getFrozenAmount());
                updateAccount(account);
            });
            log.info("Paper order cancelled, uid={}, orderId={}", uid, orderId);
            return new JSONObject().fluentPut("orderId", orderId).fluentPut("status", PaperOrder.CANCELLED);
        });
    }

    // ------------------------------------------------------------------ settlement

    /**
     * First trading session whose 09:30 open is after {@code placedAt}; {@code null} if not opened yet.
     */
    static LocalDate fillSession(LocalDateTime placedAt, List<LocalDate> calendar) {
        for (LocalDate d : calendar) {
            if (d.atTime(OPEN).isAfter(placedAt)) {
                return d;
            }
        }
        return null;
    }

    /** What happens to a pending order: fill at {@code price}, reject, or keep waiting. */
    record FillDecision(Kind kind, BigDecimal price, String reason) {
        enum Kind {
            FILL, REJECT, WAIT
        }

        static FillDecision fill(BigDecimal price) {
            return new FillDecision(Kind.FILL, price, null);
        }

        static FillDecision reject(String reason) {
            return new FillDecision(Kind.REJECT, null, reason);
        }

        static FillDecision waiting() {
            return new FillDecision(Kind.WAIT, null, null);
        }
    }

    /**
     * Opening auction of the order's session, from the stock's actual (unadjusted) daily bars: fills at
     * the open, unless the stock is suspended (no bar), a buy opens at the limit up or a sell at the
     * limit down. A limit order fills at the open only when the open is at or better than its limit;
     * otherwise it stays for intraday trading. Shortly after the open the bar may not be published yet,
     * so the order waits instead of counting as suspended.
     */
    static FillDecision decideFill(String side, String orderType, BigDecimal limitPrice, String code, String name,
            List<Bar> bars, LocalDate session, LocalDateTime now) {
        boolean limit = PaperOrder.LIMIT.equals(orderType) && limitPrice != null;
        int i = indexOf(bars, session);
        if (i < 0) {
            if (session.equals(now.toLocalDate()) && now.toLocalTime().isBefore(FILL_DATA_GRACE)) {
                return FillDecision.waiting();
            }
            return FillDecision.reject(session + " 停牌，未成交");
        }
        if (i == 0) {
            return FillDecision.reject("缺少前收盘价，未成交");
        }
        BigDecimal open = BigDecimal.valueOf(bars.get(i).open()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal prevClose = BigDecimal.valueOf(bars.get(i - 1).close());
        if (PaperOrder.BUY.equals(side)) {
            if (open.compareTo(AShareRules.limitUp(prevClose, code, name)) >= 0) {
                return limit ? FillDecision.waiting()
                        : FillDecision.reject(session + " 开盘涨停（" + open + "），买不进");
            }
            if (limit && open.compareTo(limitPrice) > 0) {
                return FillDecision.waiting();
            }
        } else {
            if (open.compareTo(AShareRules.limitDown(prevClose, code, name)) <= 0) {
                return limit ? FillDecision.waiting()
                        : FillDecision.reject(session + " 开盘跌停（" + open + "），卖不出");
            }
            if (limit && open.compareTo(limitPrice) < 0) {
                return FillDecision.waiting();
            }
        }
        return FillDecision.fill(open);
    }

    /**
     * Continuous trading at the latest price: a market buy pays latest × (1 + 0.1%) rounded up to the
     * tick, a market sell gets latest × (1 − 0.1%) rounded down, both capped by the price limits. A buy
     * cannot fill while the stock is sealed at the limit up, a sell at the limit down (market orders
     * are then rejected, limit orders keep waiting). A limit buy fills when the price is at or below
     * its limit, a limit sell at or above it, never at a worse price than the limit.
     *
     * @param tradedToday the quote is from today's trading (not a suspended stock's old quote)
     */
    static FillDecision decideLive(String side, String orderType, BigDecimal limitPrice, String code, String name,
            BigDecimal latest, BigDecimal prevClose, boolean tradedToday) {
        boolean limit = PaperOrder.LIMIT.equals(orderType) && limitPrice != null;
        if (!tradedToday || latest == null || latest.signum() <= 0 || prevClose == null || prevClose.signum() <= 0) {
            return limit ? FillDecision.waiting() : FillDecision.reject("停牌或今日暂无成交，市价单未成交");
        }
        BigDecimal up = AShareRules.limitUp(prevClose, code, name);
        BigDecimal down = AShareRules.limitDown(prevClose, code, name);
        if (PaperOrder.BUY.equals(side)) {
            if (latest.compareTo(up) >= 0) {
                return limit ? FillDecision.waiting() : FillDecision.reject("涨停封板（" + up + "），买不进");
            }
            if (limit && latest.compareTo(limitPrice) > 0) {
                return FillDecision.waiting();
            }
            BigDecimal price = AShareRules.buyFillPrice(latest, up);
            return FillDecision.fill(limit ? price.min(limitPrice) : price);
        }
        if (latest.compareTo(down) <= 0) {
            return limit ? FillDecision.waiting() : FillDecision.reject("跌停封板（" + down + "），卖不出");
        }
        if (limit && latest.compareTo(limitPrice) < 0) {
            return FillDecision.waiting();
        }
        BigDecimal price = AShareRules.sellFillPrice(latest, down);
        return FillDecision.fill(limit ? price.max(limitPrice) : price);
    }

    private void applyLive(String uid, PaperOrder o, FillDecision d, LocalDate day, LocalDateTime time) {
        switch (d.kind()) {
            case FILL -> {
                if (PaperOrder.BUY.equals(o.getSide())) {
                    fillBuy(uid, o, day, d.price(), time);
                } else {
                    fillSell(uid, o, day, d.price(), time);
                }
            }
            case REJECT -> reject(uid, o, d.reason());
            case WAIT -> {
                // Limit order: stays pending for the rest of the session
            }
        }
    }

    private List<LocalDate> calendar() {
        return market.calendarBars(CALENDAR_DAYS).bars().stream().map(Bar::date).toList();
    }

    /**
     * The calendar, with today added once it is known to be a trading day (its bar may still be
     * missing).
     */
    private List<LocalDate> calendarWithToday() {
        List<LocalDate> cal = new ArrayList<>(calendar());
        LocalDate today = TradingClock.now().toLocalDate();
        if (!cal.contains(today) && isTradingDayToday()) {
            cal.add(today);
        }
        return cal;
    }

    private volatile LocalDate knownTradingDay;

    /**
     * Today is a trading day: the index has traded today (known from the 09:15 call auction on). Before
     * that, and on weekends, {@code false}.
     */
    public boolean isTradingDayToday() {
        LocalDateTime now = TradingClock.now();
        LocalDate today = now.toLocalDate();
        if (today.equals(knownTradingDay)) {
            return true;
        }
        if (today.getDayOfWeek() == java.time.DayOfWeek.SATURDAY || today.getDayOfWeek() == java.time.DayOfWeek.SUNDAY
                || now.toLocalTime().isBefore(TradingClock.AUCTION_START)) {
            return false;
        }
        boolean yes;
        try {
            Quote index = market.quoteBySecid(EastmoneyClient.CALENDAR_SECID);
            yes = index.time() != null && index.time().toLocalDate().equals(today);
        } catch (AShareDataException e) {
            yes = calendar().contains(today);
        }
        if (yes) {
            knownTradingDay = today;
        }
        return yes;
    }

    /** Past trading days (about three months), including today once trading has started. */
    public List<LocalDate> tradingCalendar() {
        return calendarWithToday();
    }

    /** Latest quotes for the intraday monitor. */
    public Map<String, EastmoneyClient.LiveQuote> liveQuotes(java.util.Collection<String> codes) {
        return market.liveQuotes(codes);
    }

    /** Within continuous trading of a trading day. */
    public boolean isTradingNow() {
        return TradingClock.inContinuousSession(TradingClock.now().toLocalTime()) && isTradingDayToday();
    }

    /**
     * Processes the user's pending orders: opening-auction fills, intraday fills of orders that can
     * trade now, and expiry of orders whose trading day has ended. Returns orders filled, rejected or
     * expired.
     */
    public int settle(String uid) {
        PaperAccount account = findAccount(uid);
        if (account == null) {
            return 0;
        }
        List<PaperOrder> pending = orders(account.getId(), PaperOrder.PENDING, 100);
        if (pending.isEmpty()) {
            return 0;
        }
        List<LocalDate> cal = calendarWithToday();
        LocalDateTime now = TradingClock.now();
        LocalDate today = now.toLocalDate();
        boolean continuous = TradingClock.inContinuousSession(now.toLocalTime()) && cal.contains(today);
        List<PaperOrder> due = new ArrayList<>();
        Map<String, Series> bars = new HashMap<>();
        java.util.Set<String> liveCodes = new java.util.LinkedHashSet<>();
        for (PaperOrder o : pending.stream().sorted((a, b) -> a.getPlacedAt().compareTo(b.getPlacedAt())).toList()) {
            LocalDate session = TradingClock.orderSession(o.getPlacedAt(), cal);
            if (session == null || session.isAfter(today) || now.isBefore(session.atTime(OPEN))) {
                continue;
            }
            due.add(o);
            if (!TradingClock.placedIntraday(o.getPlacedAt(), cal)) {
                bars.computeIfAbsent(o.getCode(), c -> market.dailyBars(c, false, CALENDAR_DAYS));
            }
            if (session.equals(today) && continuous) {
                liveCodes.add(o.getCode());
            }
        }
        if (due.isEmpty()) {
            return 0;
        }
        Map<String, EastmoneyClient.LiveQuote> live = liveCodes.isEmpty() ? Map.of() : market.liveQuotes(liveCodes);
        return withAccountLock(uid, () -> {
            int processed = 0;
            for (PaperOrder stale : due) {
                PaperOrder o = orderMapper.selectById(stale.getId());
                if (o == null || !PaperOrder.PENDING.equals(o.getStatus())) {
                    continue;
                }
                LocalDate session = TradingClock.orderSession(o.getPlacedAt(), cal);
                String name = o.getName();
                // 1. Opening auction of the session (orders placed outside trading hours).
                if (!TradingClock.placedIntraday(o.getPlacedAt(), cal)) {
                    Series s = bars.get(o.getCode());
                    FillDecision d = decideFill(o.getSide(), o.getOrderType(), o.getLimitPrice(), o.getCode(),
                            StringUtils.defaultIfBlank(name, s.name()), s.bars(), session, now);
                    if (d.kind() != FillDecision.Kind.WAIT) {
                        applyLive(uid, o, d, session, session.atTime(OPEN));
                        processed++;
                        continue;
                    }
                    if (!PaperOrder.LIMIT.equals(o.getOrderType())) {
                        // A market order waits only for the opening bar to be published.
                        continue;
                    }
                }
                // 2. Continuous trading today.
                if (session.equals(today) && continuous) {
                    EastmoneyClient.LiveQuote q = live.get(o.getCode());
                    if (q != null) {
                        FillDecision d = decideLive(o.getSide(), o.getOrderType(), o.getLimitPrice(), o.getCode(),
                                StringUtils.defaultIfBlank(name, q.name()), q.price(), q.prevClose(), q.tradedOn(today));
                        if (d.kind() != FillDecision.Kind.WAIT) {
                            applyLive(uid, o, d, today, now);
                            processed++;
                            continue;
                        }
                    }
                }
                // 3. Day orders expire when their trading day ends.
                if (session.isBefore(today) || !now.toLocalTime().isBefore(TradingClock.CLOSE)) {
                    reject(uid, o, session + " 收盘未成交，订单已失效"
                            + (o.getLimitPrice() == null ? "" : "（限价 " + o.getLimitPrice() + "）"));
                    processed++;
                }
            }
            return processed;
        });
    }

    private void fillBuy(String uid, PaperOrder o, LocalDate day, BigDecimal open, LocalDateTime time) {
        PaperAccount account = account(uid);
        BigDecimal amount = open.multiply(BigDecimal.valueOf(o.getQty())).setScale(2, RoundingMode.HALF_UP);
        BigDecimal fees = AShareRules.fees(true, amount).total();
        BigDecimal total = amount.add(fees);
        release(account, o.getFrozenAmount());
        if (account.getCash().compareTo(total) < 0) {
            o.setStatus(PaperOrder.REJECTED);
            o.setRejectReason("成交价高于冻结时的预估，资金不足");
            transactionTemplate.executeWithoutResult(s -> {
                updateOrder(o);
                updateAccount(account);
            });
            log.info("Paper buy rejected, uid={}, orderId={}, reason=insufficient cash", uid, o.getId());
            return;
        }
        account.setCash(account.getCash().subtract(total));
        PaperPosition pos = positionMapper.selectOne(Wrappers.lambdaQuery(PaperPosition.class)
                .eq(PaperPosition::getAccountId, account.getId())
                .eq(PaperPosition::getCode, o.getCode()));
        o.setStatus(PaperOrder.FILLED);
        o.setFillDate(day);
        o.setFillTime(time);
        o.setFillPrice(open);
        o.setFillAmount(amount);
        o.setFees(fees);
        transactionTemplate.executeWithoutResult(s -> {
            if (pos == null) {
                PaperPosition created = new PaperPosition();
                created.setAccountId(account.getId());
                created.setCode(o.getCode());
                created.setName(o.getName());
                created.setQty(o.getQty());
                created.setCostAmount(total);
                created.setPeakPrice(open);
                created.setPartialTaken(0);
                created.setFirstBuyDate(day);
                created.setLastBuyDate(day);
                created.setLastBuyQty(o.getQty());
                positionMapper.insert(created);
            } else {
                pos.setLastBuyQty(day.equals(pos.getLastBuyDate()) ? pos.getLastBuyQty() + o.getQty() : o.getQty());
                pos.setQty(pos.getQty() + o.getQty());
                pos.setCostAmount(pos.getCostAmount().add(total));
                pos.setPeakPrice(pos.getPeakPrice() == null ? open : pos.getPeakPrice().max(open));
                pos.setLastBuyDate(day);
                pos.setUpdateTime(null);
                positionMapper.updateById(pos);
            }
            updateOrder(o);
            updateAccount(account);
        });
        log.info("Paper buy filled, uid={}, orderId={}, code={}, qty={}, price={}, fees={}", uid, o.getId(),
                o.getCode(), o.getQty(), open, fees);
    }

    /** Source of positions synced from the user's real brokerage account. */
    public static final String SOURCE_IMPORT = "IMPORT";

    /**
     * Records a position the user holds in a real brokerage account: a FILLED buy at the broker's
     * average cost (which already includes fees, so no fees are added) on {@code buyDate}, paid from
     * the account's cash. T+1, stop loss / take profit, exits and reviews then treat it like any other
     * position. Adds to an existing position of the same stock.
     */
    public JSONObject importPosition(String uid, String rawCode, Integer qty, BigDecimal avgCost, LocalDate buyDate,
            String note) {
        String code = AShareRules.normalizeCode(rawCode);
        LocalDate today = TradingClock.now().toLocalDate();
        LocalDate day = buyDate == null ? today : buyDate;
        if (qty == null || AShareRules.checkQty(code, true, qty, 0) != null) {
            throw new IllegalArgumentException("持仓数量不合法：" + AShareRules.checkQty(code, true, qty == null ? 0 : qty, 0));
        }
        if (avgCost == null || avgCost.signum() <= 0) {
            throw new IllegalArgumentException("成本价必须大于 0");
        }
        if (day.isAfter(today)) {
            throw new IllegalArgumentException("买入日期不能晚于今天");
        }
        Quote q = market.quote(code);
        return withAccountLock(uid, () -> {
            PaperAccount account = account(uid);
            BigDecimal price = avgCost.setScale(3, RoundingMode.HALF_UP);
            BigDecimal total = avgCost.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
            if (account.getCash().compareTo(total) < 0) {
                throw new IllegalArgumentException("可用现金 " + account.getCash() + " 不够记入这笔持仓（需要 " + total + "）");
            }
            LocalDateTime now = TradingClock.now();
            PaperOrder o = new PaperOrder();
            o.setAccountId(account.getId());
            o.setCode(code);
            o.setName(q.name());
            o.setSide(PaperOrder.BUY);
            o.setOrderType(PaperOrder.MARKET);
            o.setQty(qty);
            o.setStatus(PaperOrder.FILLED);
            o.setSource(SOURCE_IMPORT);
            o.setPlacedAt(now);
            o.setFrozenAmount(BigDecimal.ZERO);
            o.setFillDate(day);
            o.setFillTime(day.equals(today) ? now : day.atTime(TradingClock.CLOSE));
            o.setFillPrice(price);
            o.setFillAmount(total);
            o.setFees(BigDecimal.ZERO);
            o.setReason("同步实盘持仓：" + day + " 买入 " + qty + " 股，券商成本价 " + price.stripTrailingZeros().toPlainString()
                    + "（已含手续费）" + (StringUtils.isBlank(note) ? "" : "。" + StringUtils.abbreviate(note.trim(), 300)));
            PaperPosition pos = positionMapper.selectOne(Wrappers.lambdaQuery(PaperPosition.class)
                    .eq(PaperPosition::getAccountId, account.getId())
                    .eq(PaperPosition::getCode, code));
            account.setCash(account.getCash().subtract(total));
            transactionTemplate.executeWithoutResult(st -> {
                orderMapper.insert(o);
                if (pos == null) {
                    PaperPosition created = new PaperPosition();
                    created.setAccountId(account.getId());
                    created.setCode(code);
                    created.setName(q.name());
                    created.setQty(qty);
                    created.setCostAmount(total);
                    created.setPeakPrice(price.max(q.price() == null ? price : q.price()));
                    created.setPartialTaken(0);
                    created.setFirstBuyDate(day);
                    created.setLastBuyDate(day);
                    created.setLastBuyQty(qty);
                    positionMapper.insert(created);
                } else {
                    pos.setLastBuyQty(day.equals(pos.getLastBuyDate()) ? pos.getLastBuyQty() + qty : qty);
                    pos.setQty(pos.getQty() + qty);
                    pos.setCostAmount(pos.getCostAmount().add(total));
                    pos.setLastBuyDate(day.isAfter(pos.getLastBuyDate()) ? day : pos.getLastBuyDate());
                    pos.setUpdateTime(null);
                    positionMapper.updateById(pos);
                }
                updateAccount(account);
            });
            log.info("Position imported, uid={}, orderId={}, code={}, qty={}, avgCost={}, buyDate={}, cashLeft={}", uid,
                    o.getId(), code, qty, price, day, account.getCash());
            return orderJson(o).fluentPut("cashLeft", account.getCash());
        });
    }

    private void fillSell(String uid, PaperOrder o, LocalDate day, BigDecimal open, LocalDateTime time) {
        PaperAccount account = account(uid);
        PaperPosition pos = positionMapper.selectOne(Wrappers.lambdaQuery(PaperPosition.class)
                .eq(PaperPosition::getAccountId, account.getId())
                .eq(PaperPosition::getCode, o.getCode()));
        int boughtToday = pos != null && day.equals(pos.getLastBuyDate()) ? pos.getLastBuyQty() : 0;
        int sellable = pos == null ? 0 : pos.getQty() - boughtToday;
        if (o.getQty() > sellable) {
            reject(uid, o, "可卖 " + sellable + " 股不足（T+1：当日买入的不能卖）");
            return;
        }
        BigDecimal amount = open.multiply(BigDecimal.valueOf(o.getQty())).setScale(2, RoundingMode.HALF_UP);
        BigDecimal fees = AShareRules.fees(false, amount).total();
        BigDecimal proceeds = amount.subtract(fees);
        BigDecimal costPart = pos.getCostAmount()
                .multiply(BigDecimal.valueOf(o.getQty()))
                .divide(BigDecimal.valueOf(pos.getQty()), 2, RoundingMode.HALF_UP);
        o.setStatus(PaperOrder.FILLED);
        o.setFillDate(day);
        o.setFillTime(time);
        o.setFillPrice(open);
        o.setFillAmount(amount);
        o.setFees(fees);
        o.setRealizedPnl(proceeds.subtract(costPart));
        account.setCash(account.getCash().add(proceeds));
        transactionTemplate.executeWithoutResult(s -> {
            if (pos.getQty().equals(o.getQty())) {
                positionMapper.deleteById(pos.getId());
            } else {
                pos.setQty(pos.getQty() - o.getQty());
                pos.setCostAmount(pos.getCostAmount().subtract(costPart));
                pos.setUpdateTime(null);
                positionMapper.updateById(pos);
            }
            updateOrder(o);
            updateAccount(account);
        });
        log.info("Paper sell filled, uid={}, orderId={}, code={}, qty={}, price={}, pnl={}", uid, o.getId(),
                o.getCode(), o.getQty(), open, o.getRealizedPnl());
    }

    private void reject(String uid, PaperOrder o, String reason) {
        PaperAccount account = account(uid);
        o.setStatus(PaperOrder.REJECTED);
        o.setRejectReason(StringUtils.abbreviate(reason, 255));
        transactionTemplate.executeWithoutResult(s -> {
            updateOrder(o);
            if (o.getFrozenAmount() != null && o.getFrozenAmount().signum() > 0) {
                release(account, o.getFrozenAmount());
                updateAccount(account);
            }
        });
        log.info("Paper order rejected, uid={}, orderId={}, reason={}", uid, o.getId(), reason);
    }

    private static void release(PaperAccount account, BigDecimal frozen) {
        if (frozen == null || frozen.signum() <= 0) {
            return;
        }
        account.setFrozenCash(account.getFrozenCash().subtract(frozen).max(BigDecimal.ZERO));
        account.setCash(account.getCash().add(frozen));
    }

    private static int indexOf(List<Bar> bars, LocalDate day) {
        for (int i = bars.size() - 1; i >= 0; i--) {
            if (bars.get(i).date().equals(day)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ strategy exits & snapshots

    /** Shares of a position that can be sold today (T+1) and are not already on a pending sell. */
    private static int sellableNow(PaperPosition pos, List<PaperOrder> pending, LocalDate today) {
        int pendingSell = pending.stream()
                .filter(o -> o.getCode().equals(pos.getCode()) && PaperOrder.SELL.equals(o.getSide()))
                .mapToInt(PaperOrder::getQty)
                .sum();
        int boughtToday = today.equals(pos.getLastBuyDate()) ? pos.getLastBuyQty() : 0;
        return Math.max(0, pos.getQty() - pendingSell - boughtToday);
    }

    /**
     * Intraday risk check, run every minute of continuous trading, on the latest price: the stop loss
     * (always); with auto-trading also the trailing stop from the highest price since the buy, selling
     * half at the partial take profit, and the take profit. Only shares bought before today can be sold
     * (T+1); a stock sealed at the limit down waits until it opens. Keeps each position's highest
     * price.
     */
    public int intradayRiskExits(String uid, Map<String, EastmoneyClient.LiveQuote> quotes) {
        PaperAccount account = findAccount(uid);
        if (account == null) {
            return 0;
        }
        LocalDate today = TradingClock.now().toLocalDate();
        StrategyParams params = strategyService.activeParams(uid);
        int version = strategyService.active(uid).getVersion();
        boolean autoTrade = !Objects.equals(account.getAutoTrade(), 0);
        List<PaperOrder> pending = orders(account.getId(), PaperOrder.PENDING, 100);
        int placed = 0;
        for (PaperPosition pos : positions(account.getId())) {
            EastmoneyClient.LiveQuote q = quotes.get(pos.getCode());
            if (q == null || !q.tradedOn(today)) {
                continue;
            }
            double cost = pos.getCostAmount().doubleValue() / pos.getQty();
            BigDecimal peak = pos.getPeakPrice() == null ? BigDecimal.valueOf(cost) : pos.getPeakPrice();
            if (q.price().compareTo(peak) > 0) {
                peak = q.price();
                PaperPosition patch = new PaperPosition();
                patch.setId(pos.getId());
                patch.setPeakPrice(peak);
                positionMapper.updateById(patch);
            }
            int sellable = sellableNow(pos, pending, today);
            if (sellable <= 0) {
                continue;
            }
            boolean partialTaken = Objects.equals(pos.getPartialTaken(), 1);
            String reason = riskExit(params, cost, q.price().doubleValue(), peak.doubleValue(), partialTaken, autoTrade);
            if (reason == null) {
                continue;
            }
            if (q.prevClose() != null && q.price()
                    .compareTo(AShareRules.limitDown(q.prevClose(), pos.getCode(),
                            pos.getName())) <= 0) {
                log.info("Risk exit waits: sealed at limit down, uid={}, code={}, price={}", uid, pos.getCode(),
                        q.price());
                continue;
            }
            boolean partial = Backtester.PARTIAL_TAKE_PROFIT.equals(reason);
            int qty = partial ? AShareRules.halfQty(pos.getCode(), pos.getQty()) : sellable;
            if (partial && (qty <= 0 || qty > sellable)) {
                if (qty <= 0) {
                    // Too small to split: the rest of the rules (trailing stop, take profit) apply to all of it.
                    markPartialTaken(pos);
                }
                continue;
            }
            try {
                JSONObject r = place(uid, new PlaceRequest(pos.getCode(), PaperOrder.SELL, qty, null,
                        String.format(Locale.ROOT, "策略 v%d 盘中%s：现价 %s，成本 %.3f，最高 %s", version, reason, q.price(),
                                cost, peak),
                        null, null, "STRATEGY"));
                if (partial && PaperOrder.FILLED.equals(r.getString("status"))) {
                    markPartialTaken(pos);
                }
                placed++;
            } catch (RuntimeException e) {
                log.warn("Risk exit failed, uid={}, code={}, errorType={}, msg={}", uid, pos.getCode(),
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }
        return placed;
    }

    private void markPartialTaken(PaperPosition pos) {
        PaperPosition patch = new PaperPosition();
        patch.setId(pos.getId());
        patch.setPartialTaken(1);
        positionMapper.updateById(patch);
    }

    /**
     * Price-level exit at the latest price, else {@code null}: the stop loss always; with auto-trading
     * the strategy's trailing stop, partial take profit and take profit as well (same rule as the
     * backtest).
     */
    static String riskExit(StrategyParams p, double costPerShare, double price, double peak, boolean partialTaken,
            boolean autoTrade) {
        if (costPerShare <= 0) {
            return null;
        }
        if (!autoTrade) {
            return price <= costPerShare * (1 - p.getStopLossPct() / 100) ? Backtester.STOP_LOSS : null;
        }
        Backtester.PriceExit hit = Backtester.priceExit(p, costPerShare, peak, partialTaken, price, price, price);
        return hit == null ? null : hit.reason();
    }

    /**
     * From 14:50, with auto-trading on: applies the active strategy's daily rules on today's nearly
     * complete bar. Sells positions that hit max holding days or the MA cross down, then buys entry
     * signals (strongest volume first) within the position count and weight limits. Fills at once at
     * the latest price, like the backtest's close fills.
     */
    public JSONObject endOfDayRules(String uid) {
        JSONObject result = new JSONObject().fluentPut("exits", 0).fluentPut("entries", 0);
        PaperAccount account = findAccount(uid);
        LocalDateTime now = TradingClock.now();
        if (account == null || !isTradingNow() || now.toLocalTime().isBefore(TradingClock.END_OF_DAY_RULES)) {
            return result;
        }
        boolean autoTrade = !Objects.equals(account.getAutoTrade(), 0);
        if (!autoTrade && watchlist(account).isEmpty()) {
            return result;
        }
        LocalDate today = now.toLocalDate();
        StrategyParams params = strategyService.activeParams(uid);
        int version = strategyService.active(uid).getVersion();
        List<LocalDate> cal = calendarWithToday();
        int exits = 0;
        Series index = strategyService.indexNow();
        List<PaperOrder> pending = orders(account.getId(), PaperOrder.PENDING, 100);
        // Without auto-trading only the watchlist is scanned (notify only); exits stay manual.
        for (PaperPosition pos : autoTrade ? positions(account.getId()) : List.<PaperPosition>of()) {
            int qty = sellableNow(pos, pending, today);
            if (qty <= 0) {
                continue;
            }
            try {
                Series s = market.dailyBars(pos.getCode(), true, 300);
                int holdDays = (int) cal.stream().filter(d -> d.isAfter(pos.getFirstBuyDate())).count();
                String reason = Backtester.latestExit(params, s, pos.getCostAmount().doubleValue() / pos.getQty(),
                        holdDays, index);
                if (reason == null) {
                    continue;
                }
                place(uid, new PlaceRequest(pos.getCode(), PaperOrder.SELL, qty, null,
                        "策略 v" + version + " 尾盘离场：" + reason, null, null, "STRATEGY"));
                exits++;
            } catch (RuntimeException e) {
                log.warn("Strategy exit skipped, uid={}, code={}, errorType={}, msg={}", uid, pos.getCode(),
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }
        result.put("exits", exits);
        result.put("entries", endOfDayEntries(uid, params, version, index, autoTrade));
        return result;
    }

    /**
     * The strategy's entries at 14:50: signals in the trading universe (when auto-trading is on) and in
     * the user's watchlist, strongest volume ratio first, within the position count and weight limits.
     * Watchlist signals are bought only when auto-trading and watchlist auto-buy are both on; the user
     * is notified of every watchlist signal and what happened to it.
     */
    private int endOfDayEntries(String uid, StrategyParams params, int version, Series index, boolean autoTrade) {
        PaperAccount account = account(uid);
        List<String> watch = watchlist(account);
        boolean watchBuy = autoTrade && !Objects.equals(account.getWatchlistAutoTrade(), 0);
        Set<String> taken = new HashSet<>();
        positions(account.getId()).forEach(p -> taken.add(p.getCode()));
        orders(account.getId(), PaperOrder.PENDING, 100).stream()
                .filter(o -> PaperOrder.BUY.equals(o.getSide()))
                .forEach(o -> taken.add(o.getCode()));
        int slots = params.getMaxPositions() - taken.size();
        boolean marketOk = !Boolean.TRUE.equals(params.getMarketFilter()) || index == null
                || Backtester.marketOkNow(index);
        List<String> universe = autoTrade ? strategyService.liveUniverse(params) : List.of();
        Set<String> inUniverse = new HashSet<>(universe);
        Set<String> codes = new java.util.LinkedHashSet<>();
        if (slots > 0 && marketOk) {
            codes.addAll(universe);
        }
        codes.addAll(watch);
        log.info("Strategy entry scan, uid={}, version={}, universeMode={}, universe={}, watchlist={}, slots={}, marketOk={}, watchBuy={}",
                uid, version, params.getUniverseMode(), universe.size(), watch.size(), slots, marketOk, watchBuy);
        List<JSONObject> signals = new ArrayList<>();
        for (String code : codes) {
            if (taken.contains(code)) {
                continue;
            }
            try {
                JSONObject s = Backtester.latestSignal(params, market.dailyBars(code, true, 300), index);
                if (Boolean.TRUE.equals(s.getBoolean("signal"))) {
                    signals.add(s);
                }
            } catch (RuntimeException e) {
                log.warn("Strategy entry scan skipped, uid={}, code={}, msg={}", uid, code, e.getMessage());
            }
        }
        signals.sort((a, b) -> Double.compare(b.getDoubleValue("volumeRatio"), a.getDoubleValue("volumeRatio")));
        double equity = portfolio(uid).getBigDecimal("totalAssets").doubleValue();
        double budget = params.positionBudget(equity);
        int placed = 0;
        List<String> watchNotes = new ArrayList<>();
        for (JSONObject s : signals) {
            String code = s.getString("code");
            boolean watched = watch.contains(code);
            boolean fromWatch = watched && !inUniverse.contains(code);
            String label = StringUtils.defaultIfBlank(s.getString("name"), code) + " " + code;
            String skip = entrySkipReason(fromWatch, watchBuy, autoTrade, marketOk, placed, slots,
                    params.getMaxPositions());
            if (skip != null) {
                if (watched) {
                    watchNotes.add(label + " 未买入：" + skip);
                }
                continue;
            }
            try {
                Quote q = market.quote(code);
                if (q.suspended() || q.price() == null) {
                    if (watched) {
                        watchNotes.add(label + " 未买入：停牌或无报价");
                    }
                    continue;
                }
                BigDecimal reserve = AShareRules.buyFillPrice(q.price(), null);
                int qty = entryQty(code, q.price().doubleValue(), reserve.doubleValue(), budget,
                        account(uid).getCash().doubleValue());
                if (qty <= 0) {
                    if (watched) {
                        watchNotes.add(String.format(Locale.ROOT, "%s 未买入：仓位预算 %.0f 元或现金不够买 1 手（现价 %s）", label,
                                budget, q.price()));
                    }
                    continue;
                }
                String reason = String.format(Locale.ROOT, "%s策略 v%d 尾盘入场（%s）：RSI14 %s，量比 %s，现价 %s，仓位预算 %.0f 元",
                        fromWatch ? "自选关注 · " : "", version, params.getEntryType(), s.get("rsi14"),
                        s.get("volumeRatio"), q.price(), budget);
                String expectation = "按策略 v" + version + " 管理：" + params.describe().replaceFirst("^.*?离场：", "");
                JSONObject r = place(uid, new PlaceRequest(code, PaperOrder.BUY, qty, null, reason, expectation, 3,
                        "STRATEGY"));
                boolean filled = PaperOrder.FILLED.equals(r.getString("status"));
                if (filled) {
                    placed++;
                }
                if (watched) {
                    watchNotes.add(filled
                            ? label + " 已买入 " + qty + " 股 @" + Objects.requireNonNullElse(r.get("fillPrice"), q.price())
                            : label + " 未成交：" + StringUtils.defaultIfBlank(r.getString("rejectReason"),
                                    r.getString("status")));
                }
            } catch (RuntimeException e) {
                log.warn("Strategy entry skipped, uid={}, code={}, errorType={}, msg={}", uid, code,
                        e.getClass().getSimpleName(), e.getMessage());
                if (watched) {
                    watchNotes.add(label + " 未买入：" + e.getMessage());
                }
            }
        }
        if (!signals.isEmpty()) {
            log.info("Strategy entries, uid={}, version={}, signals={}, filled={}, watchNotes={}", uid, version,
                    signals.size(), placed, watchNotes);
        }
        if (!watchNotes.isEmpty()) {
            notifyWatchlist(uid, watchNotes);
        }
        return placed;
    }

    /**
     * Why a 14:50 signal is not bought, or {@code null} to buy it: a watchlist-only signal needs
     * auto-trading and watchlist auto-buy; every signal needs the market filter and a free slot.
     */
    static String entrySkipReason(boolean fromWatch, boolean watchBuy, boolean autoTrade, boolean marketOk,
            int placed, int slots, int maxPositions) {
        if (fromWatch && !watchBuy) {
            return autoTrade ? "自选只提示（自动买入已关闭）" : "策略自动交易已关闭，只提示";
        }
        if (!autoTrade) {
            return "策略自动交易已关闭，只提示";
        }
        if (!marketOk) {
            return "大盘过滤未通过（上证在 MA20 下方）";
        }
        if (placed >= slots) {
            return "持仓已达上限 " + maxPositions + " 只";
        }
        return null;
    }

    private void notifyWatchlist(String uid, List<String> notes) {
        try {
            SendNotificationRequest request = new SendNotificationRequest();
            request.setType(NotificationType.PERSONAL);
            request.setTitle("A股模拟盘：自选股出现买入信号");
            request.setBody(TradingClock.now().toLocalDate() + " 尾盘：" + StringUtils.abbreviate(String.join("；", notes), 480));
            request.setPayload(new JSONObject().fluentPut("outlink", AShareReviewService.PAGE_PATH).toJSONString());
            request.setReceiverUids(List.of(uid));
            notificationService.sendNotification(request);
        } catch (Exception e) {
            log.warn("Watchlist notification failed, uid={}, errorType={}, msg={}", uid, e.getClass().getSimpleName(),
                    e.getMessage());
        }
    }

    // ------------------------------------------------------------------ watchlist

    public static final int MAX_WATCHLIST = 20;

    /** Watchlist signals are bought automatically (auto-trading and watchlist auto-buy both on). */
    public boolean watchlistAutoBuy(String uid) {
        PaperAccount a = findAccount(uid);
        return a != null && !Objects.equals(a.getAutoTrade(), 0) && !Objects.equals(a.getWatchlistAutoTrade(), 0);
    }

    /** The user's watched codes, in the order added. */
    public List<String> watchlist(String uid) {
        return watchlist(findAccount(uid));
    }

    static List<String> watchlist(PaperAccount account) {
        if (account == null || StringUtils.isBlank(account.getWatchlist())) {
            return List.of();
        }
        try {
            return List.copyOf(JSON.parseArray(account.getWatchlist(), String.class));
        } catch (RuntimeException e) {
            log.warn("Watchlist unreadable, accountId={}, msg={}", account.getId(), e.getMessage());
            return List.of();
        }
    }

    /** Adds stocks (checked against live quotes); returns the new watchlist. */
    public List<String> watch(String uid, List<String> rawCodes) {
        if (rawCodes == null || rawCodes.isEmpty()) {
            throw new IllegalArgumentException("codes 不能为空");
        }
        PaperAccount account = account(uid);
        List<String> list = new ArrayList<>(watchlist(account));
        List<String> added = new ArrayList<>();
        for (String raw : rawCodes) {
            String code = AShareRules.normalizeCode(raw);
            if (list.contains(code)) {
                continue;
            }
            if (list.size() >= MAX_WATCHLIST) {
                throw new IllegalArgumentException("自选最多 " + MAX_WATCHLIST + " 只");
            }
            Quote q = market.quote(code);
            if (q == null || StringUtils.isBlank(q.name())) {
                throw new IllegalArgumentException("查不到股票 " + code);
            }
            list.add(code);
            added.add(code + " " + q.name());
        }
        saveWatchlist(account, list);
        log.info("Watchlist updated, uid={}, added={}, size={}", uid, added, list.size());
        return list;
    }

    /** Removes a stock; returns the new watchlist. Open positions are not touched. */
    public List<String> unwatch(String uid, String rawCode) {
        String code = AShareRules.normalizeCode(rawCode);
        PaperAccount account = account(uid);
        List<String> list = new ArrayList<>(watchlist(account));
        if (!list.remove(code)) {
            throw new IllegalArgumentException(code + " 不在自选里");
        }
        saveWatchlist(account, list);
        log.info("Watchlist updated, uid={}, removed={}, size={}", uid, code, list.size());
        return list;
    }

    private void saveWatchlist(PaperAccount account, List<String> list) {
        PaperAccount patch = new PaperAccount();
        patch.setId(account.getId());
        patch.setWatchlist(JSON.toJSONString(list));
        accountMapper.updateById(patch);
    }

    /**
     * Watched stocks with the latest quote, today's strategy signal (on the latest, possibly unfinished
     * bar), whether the rule-based trading universe has them and whether they are held.
     */
    public JSONObject watchlistView(String uid) {
        PaperAccount account = account(uid);
        List<String> watch = watchlist(account);
        StrategyParams params = strategyService.activeParams(uid);
        Set<String> universe = new HashSet<>(strategyService.liveUniverse(params));
        Set<String> held = new HashSet<>();
        positions(account.getId()).forEach(p -> held.add(p.getCode()));
        Series index = watch.isEmpty() ? null : strategyService.indexNow();
        JSONArray rows = new JSONArray();
        for (String code : watch) {
            JSONObject row = new JSONObject().fluentPut("code", code)
                    .fluentPut("inUniverse", universe.contains(code))
                    .fluentPut("held", held.contains(code));
            try {
                Quote q = market.quote(code);
                row.fluentPut("name", q.name()).fluentPut("price", q.price()).fluentPut("pctChg", q.pctChg());
            } catch (RuntimeException e) {
                row.put("error", e.getMessage());
            }
            try {
                JSONObject sig = Backtester.latestSignal(params, market.dailyBars(code, true, 300), index);
                row.fluentPut("signal", sig.getBoolean("signal"))
                        .fluentPut("rsi14", sig.get("rsi14"))
                        .fluentPut("volumeRatio", sig.get("volumeRatio"))
                        .fluentPut("barDate", sig.get("date"))
                        .fluentPut("close", sig.get("close"))
                        .fluentPut("maFast", sig.get("maFast"))
                        .fluentPut("maSlow", sig.get("maSlow"))
                        .fluentPut("ma60", sig.get("ma60"))
                        // Only meaningful when the strategy uses the market filter.
                        .fluentPut("marketOk", Boolean.TRUE.equals(params.getMarketFilter()) ? sig.get("marketOk") : null);
                if (row.get("name") == null) {
                    row.put("name", sig.get("name"));
                }
            } catch (RuntimeException e) {
                row.putIfAbsent("error", e.getMessage());
            }
            rows.add(row);
        }
        boolean autoTrade = !Objects.equals(account.getAutoTrade(), 0);
        boolean watchBuy = !Objects.equals(account.getWatchlistAutoTrade(), 0);
        return new JSONObject()
                .fluentPut("time", TradingClock.now().withNano(0).toString().replace('T', ' '))
                .fluentPut("tradingNow", isTradingNow())
                .fluentPut("stocks", rows)
                .fluentPut("max", MAX_WATCHLIST)
                .fluentPut("autoBuy", watchBuy)
                .fluentPut("effective", autoTrade && watchBuy ? "信号出现时 14:50 自动买入（与策略股票池共用持仓上限和仓位预算）"
                        : autoTrade ? "只提示，不自动买入" : "策略自动交易已关闭：只提示")
                .fluentPut("note", "自选股用当前策略 v" + strategyService.active(uid).getVersion()
                        + " 的入场信号判断；不在规则股票池里的自选股不参与回测和策略进化，其盈亏计入实盘账户。");
    }

    /**
     * Shares for an automatic entry: the position at the latest price stays within the strategy's
     * position budget ({@link StrategyParams#positionBudget}), and the cash for the fill price
     * ({@code reservePrice}) plus fees fits.
     */
    static int entryQty(String code, double latest, double reservePrice, double budget, double cash) {
        int byWeight = AShareRules.maxBuyQty(code, latest, budget);
        int byCash = AShareRules.maxBuyQty(code, reservePrice, cash * 0.998 - 5);
        return Math.min(byWeight, byCash);
    }

    /** Today is a trading day and the market has closed. */
    public boolean afterCloseOnTradingDay() {
        LocalDateTime now = TradingClock.now();
        List<LocalDate> cal = calendar();
        return !now.toLocalTime().isBefore(CLOSE) && (isTradingDayToday()
                || !cal.isEmpty() && cal.get(cal.size() - 1).equals(now.toLocalDate()));
    }

    /** Anything worth reviewing: open positions, unreviewed fills or orders filled today. */
    public boolean hasReviewableActivity(String uid) {
        PaperAccount account = findAccount(uid);
        if (account == null) {
            return false;
        }
        LocalDate today = TradingClock.now().toLocalDate();
        return !positions(account.getId()).isEmpty() || countReviewable(uid) > 0
                || orderMapper.selectCount(Wrappers.lambdaQuery(PaperOrder.class)
                        .eq(PaperOrder::getAccountId, account.getId())
                        .eq(PaperOrder::getFillDate, today)) > 0;
    }

    /** Uses the first a_share agent the user talks to as the review agent until the user picks one. */
    public void rememberReviewBot(String uid, Integer botId) {
        if (botId == null || StringUtils.isBlank(uid)) {
            return;
        }
        PaperAccount account = findAccount(uid);
        if (account != null && account.getReviewBotId() == null) {
            PaperAccount patch = new PaperAccount();
            patch.setId(account.getId());
            patch.setReviewBotId(botId);
            accountMapper.updateById(patch);
            log.info("Review agent remembered, uid={}, botId={}", uid, botId);
        }
    }

    /** Automation settings; {@code null} keeps a value. */
    public record Settings(Boolean autoTrade, Boolean preOpenReview, Boolean closeReview, Boolean weeklyReview,
            Boolean monthlyReview, Boolean autoEvolve, Integer reviewBotId, Boolean watchlistAutoTrade) {}

    public PaperAccount updateSettings(String uid, Settings settings) {
        PaperAccount account = account(uid);
        PaperAccount patch = new PaperAccount();
        patch.setId(account.getId());
        patch.setAutoTrade(flag(settings.autoTrade()));
        patch.setPreOpenReview(flag(settings.preOpenReview()));
        patch.setAutoReview(flag(settings.closeReview()));
        patch.setWeeklyReview(flag(settings.weeklyReview()));
        patch.setMonthlyReview(flag(settings.monthlyReview()));
        patch.setAutoEvolve(flag(settings.autoEvolve()));
        patch.setWatchlistAutoTrade(flag(settings.watchlistAutoTrade()));
        patch.setReviewBotId(settings.reviewBotId());
        accountMapper.updateById(patch);
        log.info("Paper account settings updated, uid={}, settings={}", uid, settings);
        return account(uid);
    }

    private static Integer flag(Boolean v) {
        return v == null ? null : (v ? 1 : 0);
    }

    /**
     * Account result over {@code [from, to]} for weekly / monthly reviews: start and end value (daily
     * snapshots), return, drawdown, fills, realized P&L, win rate, fees and the Shanghai Composite.
     */
    public JSONObject periodStats(String uid, LocalDate from, LocalDate to) {
        PaperAccount account = account(uid);
        List<PaperSnapshot> snaps = snapshotMapper.selectList(Wrappers.lambdaQuery(PaperSnapshot.class)
                .eq(PaperSnapshot::getAccountId, account.getId())
                .le(PaperSnapshot::getTradeDate, to)
                .orderByAsc(PaperSnapshot::getTradeDate));
        Double lastBefore = null;
        List<Double> inPeriod = new ArrayList<>();
        for (PaperSnapshot sn : snaps) {
            if (sn.getTradeDate().isBefore(from)) {
                lastBefore = sn.getTotal().doubleValue();
            } else {
                inPeriod.add(sn.getTotal().doubleValue());
            }
        }
        // Start value: the last close before the period; an account opened during the period starts with
        // its initial cash; otherwise (no earlier snapshot) the first close in the period.
        boolean openedInPeriod = account.getCreateTime() == null || !account.getCreateTime().toLocalDate().isBefore(from);
        double start = lastBefore != null ? lastBefore
                : openedInPeriod || inPeriod.isEmpty() ? account.getInitialCash().doubleValue() : inPeriod.get(0);
        String startBasis = lastBefore != null ? "上一交易日收盘快照" : openedInPeriod ? "初始资金（本期开户）" : "本期首个收盘快照（之前没有快照）";
        LocalDate today = TradingClock.now().toLocalDate();
        if (!to.isBefore(today)) {
            inPeriod.add(portfolio(uid).getBigDecimal("totalAssets").doubleValue());
        }
        double end = inPeriod.isEmpty() ? start : inPeriod.get(inPeriod.size() - 1);
        double peak = start;
        double maxDd = 0;
        for (double v : inPeriod) {
            peak = Math.max(peak, v);
            maxDd = Math.max(maxDd, peak > 0 ? (peak - v) / peak * 100 : 0);
        }
        List<PaperOrder> fills = orderMapper.selectList(Wrappers.lambdaQuery(PaperOrder.class)
                .eq(PaperOrder::getAccountId, account.getId())
                .eq(PaperOrder::getStatus, PaperOrder.FILLED)
                .between(PaperOrder::getFillDate, from, to));
        List<PaperOrder> sells = fills.stream().filter(o -> PaperOrder.SELL.equals(o.getSide())).toList();
        BigDecimal realized = sells.stream()
                .map(PaperOrder::getRealizedPnl)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long wins = sells.stream().filter(o -> o.getRealizedPnl() != null && o.getRealizedPnl().signum() > 0).count();
        BigDecimal fees = fills.stream()
                .map(PaperOrder::getFees)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long strategyFills = fills.stream().filter(o -> "STRATEGY".equals(o.getSource())).count();
        JSONObject benchmark = null;
        try {
            List<Bar> idx = market.calendarBars(300).bars();
            Bar before = null;
            Bar last = null;
            for (Bar b : idx) {
                if (b.date().isBefore(from)) {
                    before = b;
                } else if (!b.date().isAfter(to)) {
                    last = b;
                }
            }
            if (before != null && last != null) {
                benchmark = new JSONObject()
                        .fluentPut("name", "上证指数")
                        .fluentPut("returnPct", Indicators.round((last.close() / before.close() - 1) * 100, 2));
            }
        } catch (AShareDataException e) {
            log.debug("Benchmark unavailable: {}", e.getMessage());
        }
        return new JSONObject()
                .fluentPut("from", from.toString())
                .fluentPut("to", to.toString())
                .fluentPut("startValue", Indicators.round(start, 2))
                .fluentPut("startBasis", startBasis)
                .fluentPut("days", inPeriod.size())
                .fluentPut("endValue", Indicators.round(end, 2))
                .fluentPut("returnPct", Indicators.round(start > 0 ? (end / start - 1) * 100 : 0, 2))
                .fluentPut("maxDrawdownPct", Indicators.round(maxDd, 2))
                .fluentPut("buys", fills.size() - sells.size())
                .fluentPut("sells", sells.size())
                .fluentPut("strategyFills", strategyFills)
                .fluentPut("realizedPnl", realized)
                .fluentPut("winRatePct", sells.isEmpty() ? null : Indicators.round(wins * 100.0 / sells.size(), 1))
                .fluentPut("fees", fees)
                .fluentPut("benchmark", benchmark);
    }

    /** Positions held, or orders placed, during {@code [from, to]}. */
    public boolean hasActivityBetween(String uid, LocalDate from, LocalDate to) {
        PaperAccount account = findAccount(uid);
        if (account == null) {
            return false;
        }
        return !positions(account.getId()).isEmpty() || orderMapper.selectCount(Wrappers.lambdaQuery(PaperOrder.class)
                .eq(PaperOrder::getAccountId, account.getId())
                .ge(PaperOrder::getPlacedAt, from.atStartOfDay())
                .lt(PaperOrder::getPlacedAt, to.plusDays(1).atStartOfDay())) > 0;
    }

    /** Open positions or pending orders. */
    public boolean hasOpenExposure(String uid) {
        PaperAccount account = findAccount(uid);
        return account != null && (!positions(account.getId()).isEmpty()
                || !orders(account.getId(), PaperOrder.PENDING, 1).isEmpty());
    }

    /** Codes to quote in the intraday monitor: positions and pending orders. */
    public Set<String> watchedCodes(String uid) {
        Set<String> codes = new java.util.LinkedHashSet<>();
        PaperAccount account = findAccount(uid);
        if (account != null) {
            positions(account.getId()).forEach(p -> codes.add(p.getCode()));
            orders(account.getId(), PaperOrder.PENDING, 100).forEach(o -> codes.add(o.getCode()));
        }
        return codes;
    }

    /** Saves today's account value (after the close). */
    public void snapshot(String uid) {
        PaperAccount account = findAccount(uid);
        if (account == null) {
            return;
        }
        List<LocalDate> cal = calendar();
        if (cal.isEmpty()) {
            return;
        }
        LocalDate day = cal.get(cal.size() - 1);
        BigDecimal mv = marketValue(account.getId(), positionPrices(uid));
        BigDecimal cash = account.getCash().add(account.getFrozenCash());
        PaperSnapshot existing = snapshotMapper.selectOne(Wrappers.lambdaQuery(PaperSnapshot.class)
                .eq(PaperSnapshot::getAccountId, account.getId())
                .eq(PaperSnapshot::getTradeDate, day));
        PaperSnapshot snap = existing == null ? new PaperSnapshot() : existing;
        snap.setAccountId(account.getId());
        snap.setTradeDate(day);
        snap.setCash(cash);
        snap.setMarketValue(mv);
        snap.setTotal(cash.add(mv));
        if (existing == null) {
            snapshotMapper.insert(snap);
        } else {
            snapshotMapper.updateById(snap);
        }
    }

    // ------------------------------------------------------------------ queries

    public JSONObject portfolio(String uid) {
        settle(uid);
        PaperAccount account = account(uid);
        Map<String, BigDecimal> prices = positionPrices(uid);
        List<LocalDate> cal = calendar();
        JSONArray rows = new JSONArray();
        BigDecimal mv = BigDecimal.ZERO;
        for (PaperPosition p : positions(account.getId())) {
            BigDecimal price = prices.get(p.getCode());
            BigDecimal value = price == null ? p.getCostAmount() : price.multiply(BigDecimal.valueOf(p.getQty()));
            mv = mv.add(value);
            BigDecimal pnl = value.subtract(p.getCostAmount());
            rows.add(new JSONObject()
                    .fluentPut("code", p.getCode())
                    .fluentPut("name", p.getName())
                    .fluentPut("qty", p.getQty())
                    .fluentPut("sellableQty", p.getQty() - (TradingClock.now().toLocalDate().equals(p.getLastBuyDate())
                            ? p.getLastBuyQty()
                            : 0))
                    .fluentPut("costPerShare", p.getCostAmount().divide(BigDecimal.valueOf(p.getQty()), 3, RoundingMode.HALF_UP))
                    .fluentPut("price", price)
                    .fluentPut("marketValue", value.setScale(2, RoundingMode.HALF_UP))
                    .fluentPut("pnl", pnl.setScale(2, RoundingMode.HALF_UP))
                    .fluentPut("pnlPct", pct(pnl, p.getCostAmount()))
                    .fluentPut("firstBuyDate", String.valueOf(p.getFirstBuyDate()))
                    .fluentPut("holdTradingDays", cal.stream().filter(d -> d.isAfter(p.getFirstBuyDate())).count()));
        }
        BigDecimal total = account.getCash().add(account.getFrozenCash()).add(mv);
        JSONArray pending = new JSONArray();
        for (PaperOrder o : orders(account.getId(), PaperOrder.PENDING, 20)) {
            pending.add(orderJson(o));
        }
        return new JSONObject()
                .fluentPut("initialCash", account.getInitialCash())
                .fluentPut("cash", account.getCash())
                .fluentPut("frozenCash", account.getFrozenCash())
                .fluentPut("marketValue", mv.setScale(2, RoundingMode.HALF_UP))
                .fluentPut("totalAssets", total.setScale(2, RoundingMode.HALF_UP))
                .fluentPut("returnPct", pct(total.subtract(account.getInitialCash()), account.getInitialCash()))
                .fluentPut("positions", rows)
                .fluentPut("pendingOrders", pending);
    }

    public List<PaperOrder> listOrders(String uid, String status, int limit) {
        settle(uid);
        return orders(account(uid).getId(), StringUtils.upperCase(StringUtils.trimToNull(status), Locale.ROOT), limit);
    }

    public JSONObject performance(String uid) {
        JSONObject p = portfolio(uid);
        PaperAccount account = account(uid);
        List<PaperOrder> sells = orderMapper.selectList(Wrappers.lambdaQuery(PaperOrder.class)
                .eq(PaperOrder::getAccountId, account.getId())
                .eq(PaperOrder::getStatus, PaperOrder.FILLED)
                .eq(PaperOrder::getSide, PaperOrder.SELL));
        BigDecimal realized = sells.stream()
                .map(PaperOrder::getRealizedPnl)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long wins = sells.stream().filter(o -> o.getRealizedPnl() != null && o.getRealizedPnl().signum() > 0).count();
        BigDecimal fees = orderMapper.selectList(Wrappers.lambdaQuery(PaperOrder.class)
                .eq(PaperOrder::getAccountId, account.getId())
                .eq(PaperOrder::getStatus, PaperOrder.FILLED))
                .stream()
                .map(PaperOrder::getFees)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<PaperSnapshot> snaps = snapshotMapper.selectList(Wrappers.lambdaQuery(PaperSnapshot.class)
                .eq(PaperSnapshot::getAccountId, account.getId())
                .orderByAsc(PaperSnapshot::getTradeDate));
        double peak = account.getInitialCash().doubleValue();
        double maxDd = 0;
        List<Double> values = new ArrayList<>(snaps.stream().map(s -> s.getTotal().doubleValue()).toList());
        values.add(p.getBigDecimal("totalAssets").doubleValue());
        for (double v : values) {
            peak = Math.max(peak, v);
            maxDd = Math.max(maxDd, (peak - v) / peak * 100);
        }
        JSONArray curve = new JSONArray();
        for (PaperSnapshot s : snaps.subList(Math.max(0, snaps.size() - 30), snaps.size())) {
            curve.add(s.getTradeDate() + " " + s.getTotal());
        }
        JSONObject benchmark = null;
        try {
            List<Bar> idx = market.calendarBars(CALENDAR_DAYS * 4).bars();
            LocalDate start = account.getCreateTime() == null ? null : account.getCreateTime().toLocalDate();
            Bar first = idx.stream().filter(b -> start == null || !b.date().isBefore(start)).findFirst().orElse(null);
            if (first != null && !idx.isEmpty()) {
                Bar last = idx.get(idx.size() - 1);
                benchmark = new JSONObject()
                        .fluentPut("name", "上证指数")
                        .fluentPut("from", first.date().toString())
                        .fluentPut("returnPct", Indicators.round((last.close() / first.open() - 1) * 100, 2));
            }
        } catch (AShareDataException e) {
            log.debug("Benchmark unavailable: {}", e.getMessage());
        }
        return new JSONObject()
                .fluentPut("totalAssets", p.get("totalAssets"))
                .fluentPut("returnPct", p.get("returnPct"))
                .fluentPut("maxDrawdownPct", Indicators.round(maxDd, 2))
                .fluentPut("realizedPnl", realized)
                .fluentPut("closedTrades", sells.size())
                .fluentPut("winRatePct", sells.isEmpty() ? null : Indicators.round(wins * 100.0 / sells.size(), 1))
                .fluentPut("totalFees", fees)
                .fluentPut("benchmark", benchmark)
                .fluentPut("equityCurve", curve)
                .fluentPut("positions", p.get("positions"));
    }

    /** Filled orders waiting for review: sells, and buys at least 3 trading days old. */
    public JSONArray reviewQueue(String uid) {
        settle(uid);
        PaperAccount account = account(uid);
        List<LocalDate> cal = calendar();
        List<PaperOrder> orders = orderMapper.selectList(Wrappers.lambdaQuery(PaperOrder.class)
                .eq(PaperOrder::getAccountId, account.getId())
                .eq(PaperOrder::getStatus, PaperOrder.FILLED)
                .eq(PaperOrder::getReviewStatus, "NONE")
                .orderByAsc(PaperOrder::getId)
                .last("LIMIT 30"));
        JSONArray out = new JSONArray();
        for (PaperOrder o : orders) {
            long days = cal.stream().filter(d -> d.isAfter(o.getFillDate())).count();
            if (PaperOrder.BUY.equals(o.getSide()) && days < 3) {
                continue;
            }
            JSONObject row = orderJson(o).fluentPut("tradingDaysSinceFill", days);
            try {
                BigDecimal now = market.quote(o.getCode()).lastOrPrevClose();
                row.put("priceNow", now);
                row.put("changeSinceFillPct", pct(now.subtract(o.getFillPrice()), o.getFillPrice()));
            } catch (RuntimeException e) {
                row.put("priceNow", null);
            }
            out.add(row);
            if (out.size() >= 10) {
                break;
            }
        }
        return out;
    }

    public PaperOrder markReviewed(String uid, Long orderId, String outcome, String note) {
        String result = StringUtils.upperCase(StringUtils.trimToEmpty(outcome), Locale.ROOT);
        if (!Set.of("CORRECT", "WRONG", "MIXED").contains(result)) {
            throw new IllegalArgumentException("outcome 只能是 CORRECT / WRONG / MIXED");
        }
        if (StringUtils.length(StringUtils.trimToEmpty(note)) < 10) {
            throw new IllegalArgumentException("note 必填（至少 10 字）：对照当初的 reason 和 expectation 写结论");
        }
        PaperAccount account = account(uid);
        PaperOrder o = orderId == null ? null : orderMapper.selectById(orderId);
        if (o == null || !Objects.equals(o.getAccountId(), account.getId()) || !PaperOrder.FILLED.equals(o.getStatus())) {
            throw new IllegalArgumentException("没有这个已成交订单：" + orderId);
        }
        o.setReviewStatus("REVIEWED");
        o.setReviewOutcome(result);
        o.setReviewNote(StringUtils.abbreviate(note.trim(), 4000));
        updateOrder(o);
        log.info("Paper order reviewed, uid={}, orderId={}, outcome={}", uid, orderId, result);
        return o;
    }

    public long countReviewable(String uid) {
        PaperAccount account = findAccount(uid);
        if (account == null) {
            return 0;
        }
        return orderMapper.selectCount(Wrappers.lambdaQuery(PaperOrder.class)
                .eq(PaperOrder::getAccountId, account.getId())
                .eq(PaperOrder::getStatus, PaperOrder.FILLED)
                .eq(PaperOrder::getReviewStatus, "NONE"));
    }

    /** Account summary without network calls, for the prompt context. */
    public JSONObject brief(String uid) {
        PaperAccount account = findAccount(uid);
        if (account == null) {
            return new JSONObject().fluentPut("cash", INITIAL_CASH).fluentPut("positions", 0).fluentPut("pending", 0);
        }
        List<PaperPosition> positions = positions(account.getId());
        long pending = orderMapper.selectCount(Wrappers.lambdaQuery(PaperOrder.class)
                .eq(PaperOrder::getAccountId, account.getId())
                .eq(PaperOrder::getStatus, PaperOrder.PENDING));
        BigDecimal cost = positions.stream().map(PaperPosition::getCostAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new JSONObject()
                .fluentPut("cash", account.getCash())
                .fluentPut("frozenCash", account.getFrozenCash())
                .fluentPut("positionCost", cost)
                .fluentPut("positions", positions.stream().map(p -> p.getName() + "(" + p.getCode() + ")×" + p.getQty()).toList())
                .fluentPut("pending", pending)
                .fluentPut("autoReview", !Objects.equals(account.getAutoReview(), 0))
                .fluentPut("autoTrade", Objects.equals(account.getAutoTrade(), 1))
                .fluentPut("autoReview", !Objects.equals(account.getAutoReview(), 0))
                .fluentPut("autoTrade", Objects.equals(account.getAutoTrade(), 1));
    }

    public static JSONObject orderJson(PaperOrder o) {
        return new JSONObject()
                .fluentPut("orderId", o.getId())
                .fluentPut("side", o.getSide())
                .fluentPut("orderType", StringUtils.defaultIfBlank(o.getOrderType(), PaperOrder.MARKET))
                .fluentPut("limitPrice", o.getLimitPrice())
                .fluentPut("code", o.getCode())
                .fluentPut("name", o.getName())
                .fluentPut("qty", o.getQty())
                .fluentPut("status", o.getStatus())
                .fluentPut("source", o.getSource())
                .fluentPut("placedAt", String.valueOf(o.getPlacedAt()))
                .fluentPut("fillDate", o.getFillDate() == null ? null : o.getFillDate().toString())
                .fluentPut("fillTime", o.getFillTime() == null ? null : o.getFillTime().toString())
                .fluentPut("fillPrice", o.getFillPrice())
                .fluentPut("fees", o.getFees())
                .fluentPut("realizedPnl", o.getRealizedPnl())
                .fluentPut("rejectReason", o.getRejectReason())
                .fluentPut("reason", o.getReason())
                .fluentPut("expectation", o.getExpectation())
                .fluentPut("confidence", o.getConfidence())
                .fluentPut("strategyVersion", o.getStrategyVersion())
                .fluentPut("review", o.getReviewOutcome() == null ? null : o.getReviewOutcome() + "：" + o.getReviewNote());
    }

    // ------------------------------------------------------------------ helpers

    private List<PaperPosition> positions(Long accountId) {
        return positionMapper.selectList(Wrappers.lambdaQuery(PaperPosition.class)
                .eq(PaperPosition::getAccountId, accountId)
                .orderByAsc(PaperPosition::getId));
    }

    private List<PaperOrder> orders(Long accountId, String status, int limit) {
        var q = Wrappers.lambdaQuery(PaperOrder.class).eq(PaperOrder::getAccountId, accountId);
        if (status != null) {
            q.eq(PaperOrder::getStatus, status);
        }
        return orderMapper.selectList(q.orderByDesc(PaperOrder::getId).last("LIMIT " + Math.max(1, Math.min(limit, 100))));
    }

    /** Latest price of each held stock; missing when the quote fails. */
    private Map<String, BigDecimal> positionPrices(String uid) {
        PaperAccount account = findAccount(uid);
        Map<String, BigDecimal> prices = new HashMap<>();
        if (account == null) {
            return prices;
        }
        for (PaperPosition p : positions(account.getId())) {
            try {
                BigDecimal price = market.quote(p.getCode()).lastOrPrevClose();
                if (price != null) {
                    prices.put(p.getCode(), price);
                }
            } catch (RuntimeException e) {
                log.warn("Position quote failed, code={}, msg={}", p.getCode(), e.getMessage());
            }
        }
        return prices;
    }

    private BigDecimal marketValue(Long accountId, Map<String, BigDecimal> prices) {
        BigDecimal mv = BigDecimal.ZERO;
        for (PaperPosition p : positions(accountId)) {
            BigDecimal price = prices.get(p.getCode());
            mv = mv.add(price == null ? p.getCostAmount() : price.multiply(BigDecimal.valueOf(p.getQty())));
        }
        return mv.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal base) {
        if (base == null || base.signum() == 0) {
            return null;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(base, 2, RoundingMode.HALF_UP);
    }

    private void updateAccount(PaperAccount account) {
        account.setUpdateTime(null);
        accountMapper.updateById(account);
    }

    private void updateOrder(PaperOrder order) {
        order.setUpdateTime(null);
        orderMapper.updateById(order);
    }

    private <T> T withAccountLock(String uid, Supplier<T> action) {
        String key = "ashare:account:" + uid;
        String token = UUID.randomUUID().toString();
        for (int attempt = 0; attempt < 50; attempt++) {
            if (redisUtil.tryLock(key, 60, token)) {
                try {
                    return action.get();
                } finally {
                    redisUtil.unlock(key, token);
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalArgumentException("账户正在处理其他操作，请稍后再试");
    }
}
