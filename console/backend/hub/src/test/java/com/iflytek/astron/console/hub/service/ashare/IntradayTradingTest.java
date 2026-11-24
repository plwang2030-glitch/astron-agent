package com.iflytek.astron.console.hub.service.ashare;

import com.iflytek.astron.console.hub.entity.ashare.PaperOrder;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Bar;
import com.iflytek.astron.console.hub.service.ashare.PaperTradingService.FillDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Intraday A-share rules: trading hours, intraday fills, limit orders, periods and the backtest
 * model.
 */
class IntradayTradingTest {

    private static final LocalDate THU = LocalDate.of(2026, 11, 19);
    private static final LocalDate FRI = LocalDate.of(2026, 11, 20);
    private static final LocalDate MON = LocalDate.of(2026, 11, 23);
    private static final BigDecimal TEN = new BigDecimal("10.00");

    private static FillDecision live(String side, String type, String limit, String latest) {
        return PaperTradingService.decideLive(side, type, limit == null ? null : new BigDecimal(limit), "600036",
                "招商银行", new BigDecimal(latest), TEN, true);
    }

    @Test
    void tradingHours() {
        assertFalse(TradingClock.inContinuousSession(LocalTime.of(9, 25)));
        assertTrue(TradingClock.inContinuousSession(LocalTime.of(9, 30)));
        assertTrue(TradingClock.inContinuousSession(LocalTime.of(11, 29, 59)));
        assertFalse(TradingClock.inContinuousSession(LocalTime.of(11, 30)));
        assertFalse(TradingClock.inContinuousSession(LocalTime.of(12, 30)));
        assertTrue(TradingClock.inContinuousSession(LocalTime.of(13, 0)));
        assertFalse(TradingClock.inContinuousSession(LocalTime.of(15, 0)));
    }

    @Test
    void orderSessionDependsOnWhenItWasPlaced() {
        List<LocalDate> cal = List.of(THU, FRI, MON);
        // During trading hours (lunch break included): valid that day.
        assertEquals(FRI, TradingClock.orderSession(FRI.atTime(10, 0), cal));
        assertEquals(FRI, TradingClock.orderSession(FRI.atTime(12, 0), cal));
        // After the close, over the weekend or before the open: the next opening auction.
        assertEquals(MON, TradingClock.orderSession(FRI.atTime(15, 30), cal));
        assertEquals(MON, TradingClock.orderSession(LocalDate.of(2026, 11, 22).atTime(20, 0), cal));
        assertEquals(MON, TradingClock.orderSession(MON.atTime(9, 20), cal));
        assertNull(TradingClock.orderSession(MON.atTime(15, 30), cal));
    }

    @Test
    void marketBuyFillsAtLatestPlusSlippageRoundedUp() {
        FillDecision d = live(PaperOrder.BUY, PaperOrder.MARKET, null, "10.31");
        assertEquals(FillDecision.Kind.FILL, d.kind());
        // 10.31 × 1.001 = 10.32031 -> 10.33
        assertEquals(new BigDecimal("10.33"), d.price());
        // Never above the limit up (11.00).
        assertEquals(new BigDecimal("11.00"), live(PaperOrder.BUY, PaperOrder.MARKET, null, "10.995").price());
    }

    @Test
    void marketSellFillsAtLatestMinusSlippageRoundedDown() {
        FillDecision d = live(PaperOrder.SELL, PaperOrder.MARKET, null, "10.31");
        // 10.31 × 0.999 = 10.29969 -> 10.29
        assertEquals(new BigDecimal("10.29"), d.price());
    }

    @Test
    void sealedLimitBlocksMarketOrdersAndHoldsLimitOrders() {
        FillDecision buy = live(PaperOrder.BUY, PaperOrder.MARKET, null, "11.00");
        assertEquals(FillDecision.Kind.REJECT, buy.kind());
        assertTrue(buy.reason().contains("涨停"));
        assertEquals(FillDecision.Kind.WAIT, live(PaperOrder.BUY, PaperOrder.LIMIT, "11.00", "11.00").kind());
        FillDecision sell = live(PaperOrder.SELL, PaperOrder.MARKET, null, "9.00");
        assertEquals(FillDecision.Kind.REJECT, sell.kind());
        assertTrue(sell.reason().contains("跌停"));
        assertEquals(FillDecision.Kind.WAIT, live(PaperOrder.SELL, PaperOrder.LIMIT, "9.00", "9.00").kind());
    }

    @Test
    void limitOrdersFillOnlyAtTheLimitOrBetter() {
        assertEquals(FillDecision.Kind.WAIT, live(PaperOrder.BUY, PaperOrder.LIMIT, "10.20", "10.21").kind());
        FillDecision buy = live(PaperOrder.BUY, PaperOrder.LIMIT, "10.20", "10.19");
        assertEquals(FillDecision.Kind.FILL, buy.kind());
        // 10.19 + slippage = 10.21, capped at the limit.
        assertEquals(new BigDecimal("10.20"), buy.price());
        assertEquals(FillDecision.Kind.WAIT, live(PaperOrder.SELL, PaperOrder.LIMIT, "10.50", "10.49").kind());
        assertEquals(new BigDecimal("10.50"), live(PaperOrder.SELL, PaperOrder.LIMIT, "10.50", "10.51").price());
    }

    @Test
    void suspendedStock() {
        FillDecision market = PaperTradingService.decideLive(PaperOrder.BUY, PaperOrder.MARKET, null, "600036",
                "招商银行", TEN, TEN, false);
        assertEquals(FillDecision.Kind.REJECT, market.kind());
        assertEquals(FillDecision.Kind.WAIT, PaperTradingService.decideLive(PaperOrder.BUY, PaperOrder.LIMIT,
                TEN, "600036", "招商银行", TEN, TEN, false).kind());
    }

    @Test
    void limitOrderAtTheOpeningAuction() {
        List<Bar> bars = List.of(new Bar(FRI, 10, 10, 10, 10, 1, 1, 0, 0), new Bar(MON, 10.3, 10.3, 10.3, 10.3, 1, 1, 0, 0));
        LocalDateTime later = MON.atTime(10, 0);
        // Open above the buy limit: keeps waiting for intraday trading.
        assertEquals(FillDecision.Kind.WAIT, PaperTradingService.decideFill(PaperOrder.BUY, PaperOrder.LIMIT,
                new BigDecimal("10.20"), "600036", "招商银行", bars, MON, later).kind());
        FillDecision d = PaperTradingService.decideFill(PaperOrder.BUY, PaperOrder.LIMIT, new BigDecimal("10.50"),
                "600036", "招商银行", bars, MON, later);
        assertEquals(FillDecision.Kind.FILL, d.kind());
        assertEquals(new BigDecimal("10.30"), d.price());
    }

    @Test
    void limitPriceValidation() {
        assertNull(AShareRules.checkLimitPrice(new BigDecimal("10.50"), TEN, "600036", "招商银行"));
        assertNotNull(AShareRules.checkLimitPrice(new BigDecimal("10.505"), TEN, "600036", "招商银行"));
        assertNotNull(AShareRules.checkLimitPrice(new BigDecimal("11.01"), TEN, "600036", "招商银行"));
        assertNotNull(AShareRules.checkLimitPrice(new BigDecimal("8.99"), TEN, "600036", "招商银行"));
        assertNotNull(AShareRules.checkLimitPrice(BigDecimal.ZERO, TEN, "600036", "招商银行"));
    }

    @Test
    void riskExitAtStopLossAlwaysAndTakeProfitWithAutoTrading() {
        StrategyParams p = StrategyParams.defaults();
        assertEquals(Backtester.STOP_LOSS, PaperTradingService.riskExit(p, 10, 9.2, 10, false, false));
        assertNull(PaperTradingService.riskExit(p, 10, 12.0, 12, false, false));
        assertEquals(Backtester.TAKE_PROFIT, PaperTradingService.riskExit(p, 10, 12.0, 12, false, true));
        assertNull(PaperTradingService.riskExit(p, 10, 10.5, 10.5, false, true));
    }

    @Test
    void trailingStopAndPartialTakeProfitApplyWithAutoTradingOnly() {
        StrategyParams p = StrategyParams.defaults()
                .merge(new com.alibaba.fastjson2.JSONObject().fluentPut("trailingStopPct", 10).fluentPut("partialTakeProfitPct", 8));
        // Peak 12, trailing stop at 10.80 (above the fixed stop 9.20).
        assertEquals(Backtester.TRAILING_STOP, PaperTradingService.riskExit(p, 10, 10.79, 12, true, true));
        assertNull(PaperTradingService.riskExit(p, 10, 10.79, 12, true, false));
        assertEquals(Backtester.PARTIAL_TAKE_PROFIT, PaperTradingService.riskExit(p, 10, 10.8, 10.8, false, true));
        // Once half is sold, the rest waits for the trailing stop or the take profit.
        assertNull(PaperTradingService.riskExit(p, 10, 10.9, 10.9, true, true));
    }

    @Test
    void backtestPriceExitLevels() {
        StrategyParams p = StrategyParams.defaults();
        // Gap below the stop (9.20): fills at the open.
        Backtester.PriceExit gap = Backtester.priceExit(p, 10, 10, false, 9.0, 9.3, 8.9);
        assertEquals(9.0, gap.price(), 1e-9);
        assertEquals(Backtester.STOP_LOSS, gap.reason());
        // Range touches the stop: fills at the stop.
        assertEquals(9.2, Backtester.priceExit(p, 10, 10, false, 9.6, 9.7, 9.1).price(), 1e-9);
        // Reaches the take profit (12.00).
        Backtester.PriceExit tp = Backtester.priceExit(p, 10, 10, false, 11.5, 12.2, 11.4);
        assertEquals(12.0, tp.price(), 1e-9);
        assertEquals(Backtester.TAKE_PROFIT, tp.reason());
        assertNull(Backtester.priceExit(p, 10, 10, false, 10, 10.3, 9.9));
        // Trailing stop from the peak.
        StrategyParams trail = p.merge(new com.alibaba.fastjson2.JSONObject().fluentPut("trailingStopPct", 10));
        Backtester.PriceExit t = Backtester.priceExit(trail, 10, 13, false, 12, 12.1, 11.6);
        assertEquals(Backtester.TRAILING_STOP, t.reason());
        assertEquals(11.7, t.price(), 1e-9);
    }

    @Test
    void halfQtyUsesValidLots() {
        assertEquals(200, AShareRules.halfQty("600036", 500));
        assertEquals(0, AShareRules.halfQty("600036", 100));
        assertEquals(250, AShareRules.halfQty("688981", 500));
        assertEquals(0, AShareRules.halfQty("688981", 300));
    }

    @Test
    void weeklyAndMonthlyReviewsAreDueOnTheLastTradingDay() {
        LocalDate wed = LocalDate.of(2026, 11, 25);
        LocalDate thu = LocalDate.of(2026, 11, 26);
        LocalDate fri = LocalDate.of(2026, 11, 27);
        List<LocalDate> cal = List.of(MON, LocalDate.of(2026, 11, 24), wed, thu, fri);
        assertEquals("2026-W48", TradingClock.duePeriod(fri, cal, Set.of(), true));
        assertNull(TradingClock.duePeriod(thu, cal, Set.of(), true));
        assertNull(TradingClock.duePeriod(fri, cal, Set.of("2026-W48"), true));
        // November 2026 ends on a Monday (30th).
        LocalDate nov30 = LocalDate.of(2026, 11, 30);
        List<LocalDate> calMonth = List.of(fri, nov30);
        assertEquals("2026-11", TradingClock.duePeriod(nov30, calMonth, Set.of(), false));
        assertNull(TradingClock.duePeriod(fri, cal, Set.of(), false));
    }

    @Test
    void reviewCatchesUpWhenTheLastWeekdayWasAHoliday() {
        // Friday 2026-10-02 is a holiday; the week is reviewed on the next trading day.
        LocalDate sep30 = LocalDate.of(2026, 9, 30);
        LocalDate fridayHoliday = LocalDate.of(2026, 10, 2);
        LocalDate nextMon = LocalDate.of(2026, 10, 5);
        List<LocalDate> cal = List.of(sep30, nextMon);
        assertFalse(cal.contains(fridayHoliday));
        assertEquals(TradingClock.weekKey(fridayHoliday), TradingClock.duePeriod(nextMon, cal, Set.of(), true));
        assertNull(TradingClock.duePeriod(nextMon, cal, Set.of(TradingClock.weekKey(fridayHoliday)), true));
        // Only the first trading day after the period catches up.
        LocalDate tue = nextMon.plusDays(1);
        assertNull(TradingClock.duePeriod(tue, List.of(sep30, nextMon, tue), Set.of(), true));
    }

    @Test
    void periodKeys() {
        assertEquals("2026-W48", TradingClock.weekKey(MON));
        assertEquals("2026-11", TradingClock.monthKey(MON));
        assertEquals(MON, TradingClock.periodStart(LocalDate.of(2026, 11, 27), true));
        assertEquals(LocalDate.of(2026, 11, 1), TradingClock.periodStart(MON, false));
    }
}
