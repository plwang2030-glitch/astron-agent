package com.iflytek.astron.console.hub.service.ashare;

import com.iflytek.astron.console.hub.entity.ashare.PaperOrder;
import com.iflytek.astron.console.hub.entity.ashare.TradingLesson;
import com.iflytek.astron.console.hub.entity.ashare.TradingReviewReport;
import com.iflytek.astron.console.hub.service.ashare.EastmoneyClient.Bar;
import com.iflytek.astron.console.hub.service.ashare.PaperTradingService.FillDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paper fills: next-session open, limit-up / limit-down / suspension, and the lesson thresholds.
 */
class PaperFillRulesTest {

    private static final LocalDate FRI = LocalDate.of(2026, 11, 20);
    private static final LocalDate MON = LocalDate.of(2026, 11, 23);
    private static final LocalDateTime LATER = MON.atTime(15, 0);

    private static List<Bar> bars(double prevClose, double open) {
        return List.of(new Bar(FRI, prevClose, prevClose, prevClose, prevClose, 1, 1, 0, 0),
                new Bar(MON, open, open, open, open, 1, 1, 0, 0));
    }

    @Test
    void fillSessionIsTheFirstOpenAfterPlacing() {
        List<LocalDate> cal = List.of(FRI, MON);
        assertEquals(MON, PaperTradingService.fillSession(FRI.atTime(20, 0), cal));
        assertEquals(MON, PaperTradingService.fillSession(MON.atTime(9, 0), cal));
        // Placed during Friday's session: Friday's open has passed, so it waits for Monday.
        assertEquals(MON, PaperTradingService.fillSession(FRI.atTime(10, 0), cal));
        assertNull(PaperTradingService.fillSession(MON.atTime(9, 31), cal));
    }

    @Test
    void buyFillsAtOpenBelowLimitUp() {
        FillDecision d = PaperTradingService.decideFill(PaperOrder.BUY, PaperOrder.MARKET, null, "600036", "招商银行", bars(10, 10.5), MON, LATER);
        assertEquals(FillDecision.Kind.FILL, d.kind());
        assertEquals(new BigDecimal("10.50"), d.price());
    }

    @Test
    void buyRejectedAtLimitUpOpen() {
        FillDecision d = PaperTradingService.decideFill(PaperOrder.BUY, PaperOrder.MARKET, null, "600036", "招商银行", bars(10, 11), MON, LATER);
        assertEquals(FillDecision.Kind.REJECT, d.kind());
        assertTrue(d.reason().contains("涨停"));
    }

    @Test
    void limitDependsOnBoardAndSt() {
        // ChiNext allows 20%: an 11.00 open after a 10.00 close still fills.
        assertEquals(FillDecision.Kind.FILL,
                PaperTradingService.decideFill(PaperOrder.BUY, PaperOrder.MARKET, null, "300750", "宁德时代", bars(10, 11), MON, LATER).kind());
        // ST on the main board is limited at 5%.
        assertEquals(FillDecision.Kind.REJECT,
                PaperTradingService.decideFill(PaperOrder.BUY, PaperOrder.MARKET, null, "600123", "*ST 某某", bars(10, 10.5), MON, LATER).kind());
    }

    @Test
    void sellRejectedAtLimitDownOpen() {
        FillDecision d = PaperTradingService.decideFill(PaperOrder.SELL, PaperOrder.MARKET, null, "600036", "招商银行", bars(10, 9), MON, LATER);
        assertEquals(FillDecision.Kind.REJECT, d.kind());
        assertTrue(d.reason().contains("跌停"));
        assertEquals(FillDecision.Kind.FILL,
                PaperTradingService.decideFill(PaperOrder.SELL, PaperOrder.MARKET, null, "600036", "招商银行", bars(10, 9.5), MON, LATER).kind());
    }

    @Test
    void suspendedStockIsRejectedAfterTheGracePeriod() {
        List<Bar> onlyFriday = bars(10, 10).subList(0, 1);
        FillDecision d = PaperTradingService.decideFill(PaperOrder.BUY, PaperOrder.MARKET, null, "600036", "招商银行", onlyFriday, MON, LATER);
        assertEquals(FillDecision.Kind.REJECT, d.kind());
        assertTrue(d.reason().contains("停牌"));
        // Right after the open the day's bar may not be published yet.
        assertEquals(FillDecision.Kind.WAIT, PaperTradingService
                .decideFill(PaperOrder.BUY, PaperOrder.MARKET, null, "600036", "招商银行", onlyFriday, MON, MON.atTime(9, 36))
                .kind());
    }

    @Test
    void missingPreviousCloseIsRejected() {
        List<Bar> onlyMonday = bars(10, 10).subList(1, 2);
        assertEquals(FillDecision.Kind.REJECT, PaperTradingService
                .decideFill(PaperOrder.BUY, PaperOrder.MARKET, null, "600036", "招商银行", onlyMonday, MON, LATER)
                .kind());
    }

    private static TradingLesson lesson(int support, int contradict) {
        TradingLesson l = new TradingLesson();
        l.setSupport(support);
        l.setContradict(contradict);
        LessonService.applyConfidence(l);
        return l;
    }

    @Test
    void lessonConfidenceThresholds() {
        TradingLesson one = lesson(1, 0);
        assertEquals(new BigDecimal("0.67"), one.getConfidence());
        assertEquals(TradingLesson.CANDIDATE, one.getStatus());
        assertEquals(TradingLesson.ACTIVE, lesson(2, 0).getStatus());
        assertEquals(TradingLesson.CANDIDATE, lesson(2, 2).getStatus());
        TradingLesson refuted = lesson(0, 2);
        assertEquals(new BigDecimal("0.25"), refuted.getConfidence());
        assertEquals(TradingLesson.RETIRED, refuted.getStatus());
        // An active lesson that keeps failing is retired.
        assertEquals(TradingLesson.RETIRED, lesson(2, 5).getStatus());
    }

    @Test
    void parsesKlineRows() {
        Bar bar = EastmoneyClient.parseBar("2026-11-20,43.15,43.08,43.30,42.90,512345,2.2E9,-0.55,0.25");
        assertNotNull(bar);
        assertEquals(FRI, bar.date());
        assertEquals(43.15, bar.open(), 1e-9);
        assertEquals(-0.55, bar.pctChg(), 1e-9);
        assertNull(EastmoneyClient.parseBar("2026-11-20,43.15"));
        assertNull(EastmoneyClient.parseBar("bad,date,1,2,3,4,5,6,7"));
    }

    @Test
    void entryQtyRespectsWeightAndCash() {
        // 25% of 50,000 at 43.5 -> 287 -> 200 shares (100-share lots).
        assertEquals(200, PaperTradingService.entryQty("600036", 43.5, 43.55, 12_500, 50_000));
        // Little cash left: 5,000 at 43.55 -> 114 -> 100 shares.
        assertEquals(100, PaperTradingService.entryQty("600036", 43.5, 43.55, 12_500, 5_000));
        assertEquals(0, PaperTradingService.entryQty("600036", 43.5, 43.55, 12_500, 3_000));
        // STAR board: at least 200 shares, then any number.
        assertEquals(0, PaperTradingService.entryQty("688981", 80, 80.08, 12_500, 50_000));
        assertEquals(312, PaperTradingService.entryQty("688981", 40, 40.04, 12_500, 50_000));
    }

    @Test
    void parsesTencentRows() {
        List<Bar> bars = EastmoneyClient.parseTencentRows(com.alibaba.fastjson2.JSON.parseArray(
                "[[\"2026-11-20\",\"43.15\",\"43.00\",\"43.30\",\"42.90\",\"512345\"],"
                        + "[\"2026-11-23\",\"43.10\",\"43.43\",\"43.89\",\"43.00\",\"300000\"],[\"bad\"]]"));
        assertEquals(2, bars.size());
        assertEquals(MON, bars.get(1).date());
        assertEquals(43.10, bars.get(1).open(), 1e-9);
        assertEquals(1.0, bars.get(1).pctChg(), 1e-9);
        assertEquals(0, bars.get(0).pctChg(), 1e-9);
    }

    @Test
    void hostPoolPrefersTheLastHostThatAnswered() {
        EastmoneyClient.HostPool pool = new EastmoneyClient.HostPool("a", "b", "c");
        assertEquals(List.of("a", "b", "c"), pool.order());
        pool.succeeded("c");
        assertEquals(List.of("c", "a", "b"), pool.order());
        pool.succeeded("unknown");
        assertEquals("c", pool.order().get(0));
    }

    @Test
    void reviewPromptForbidsTrading() {
        TradingReviewReport r = new TradingReviewReport();
        r.setTradeDate(FRI);
        r.setTriggerType(TradingReviewReport.AUTO);
        String prompt = AShareReviewService.prompt(r);
        assertTrue(prompt.contains("不要下单"));
        assertTrue(prompt.contains("用户不在线"));
        assertTrue(prompt.contains(FRI.toString()));
    }
}
