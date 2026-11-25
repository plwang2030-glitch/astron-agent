package com.iflytek.astron.console.hub.service.ashare;

import com.iflytek.astron.console.hub.service.ashare.DataQuality.Block;
import com.iflytek.astron.console.hub.service.ashare.DataQuality.Status;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataQualityTest {

    private static DataQuality allFresh() {
        DataQuality q = new DataQuality();
        for (Block b : Block.values()) {
            q.put(b, Status.FRESH);
        }
        return q;
    }

    @Test
    void everythingFreshScoresFullAndAddsNoLimitation() {
        DataQuality q = allFresh();
        assertEquals(100, q.score());
        assertEquals(DataQuality.Level.GOOD, q.level());
        assertFalse(q.coreDegraded());
        assertTrue(q.limitations().isEmpty());
    }

    @Test
    void missingCoreBlockForbidsHighConfidence() {
        DataQuality q = allFresh().put(Block.DAILY_BARS, Status.FETCH_FAILED, "接口连不上");
        assertTrue(q.coreDegraded());
        assertEquals(75, q.score());
        // A degraded core block caps the level at FAIR even when the score is high.
        assertEquals(DataQuality.Level.FAIR, q.level());
        assertTrue(q.limitations().stream().anyMatch(l -> l.contains("日线历史") && l.contains("接口连不上")));
        assertTrue(q.limitations().stream().anyMatch(l -> l.contains("不要给出高置信度的买卖结论")));
    }

    @Test
    void sideBlockMissingDoesNotBlockAConclusion() {
        DataQuality q = allFresh().put(Block.FUND_FLOW, Status.FETCH_FAILED);
        assertFalse(q.coreDegraded());
        assertEquals(90, q.score());
        assertEquals(DataQuality.Level.GOOD, q.level());
        assertEquals(1, q.limitations().size());
    }

    @Test
    void blocksThatDoNotApplyAreLeftOutOfTheScore() {
        DataQuality q = allFresh().put(Block.FUND_FLOW, Status.NOT_SUPPORTED);
        // Not punished for data the instrument never has.
        assertEquals(100, q.score());
        assertTrue(q.limitations().isEmpty());
    }

    @Test
    void staleBarsCountAsPartlyUsable() {
        DataQuality q = allFresh().put(Block.DAILY_BARS, Status.STALE, "最后一根 2026-11-20");
        assertTrue(q.score() < 100);
        assertTrue(q.coreDegraded());
        assertTrue(q.limitations().get(0).contains("数据偏旧"));
    }

    @Test
    void barStatusComparesWithTheLatestSettledDay() {
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            dates.add(LocalDate.of(2026, 1, 5).plusDays(i));
        }
        LocalDate last = dates.get(dates.size() - 1);
        assertEquals(Status.FRESH, DataQuality.barStatus(dates, last, 60));
        assertEquals(Status.STALE, DataQuality.barStatus(dates, last.plusDays(1), 60));
        assertEquals(Status.STALE, DataQuality.barStatus(dates.subList(0, 30), last, 60));
        assertEquals(Status.MISSING, DataQuality.barStatus(List.of(), last, 60));
        assertEquals(Status.MISSING, DataQuality.barStatus(null, last, 60));
    }

    @Test
    void promptSectionStatesThePhaseRuleAndTheLimits() {
        String text = allFresh().put(Block.NEWS, Status.MISSING)
                .promptSection(TradingClock.Phase.PRE_OPEN);
        assertTrue(text.contains("盘前"));
        assertTrue(text.contains("不能把尚未发生的今日走势写成事实"));
        assertTrue(text.contains("公告与资讯：缺失"));
        assertTrue(text.contains("只说明「不知道」"));
    }

    @Test
    void phaseFollowsTheTradingDay() {
        LocalDate day = LocalDate.of(2026, 11, 25);
        assertEquals(TradingClock.Phase.PRE_OPEN, TradingClock.phase(day.atTime(9, 20), true));
        assertEquals(TradingClock.Phase.INTRADAY, TradingClock.phase(day.atTime(10, 0), true));
        assertEquals(TradingClock.Phase.LUNCH_BREAK, TradingClock.phase(day.atTime(12, 0), true));
        assertEquals(TradingClock.Phase.INTRADAY, TradingClock.phase(day.atTime(14, 59), true));
        assertEquals(TradingClock.Phase.AFTER_CLOSE, TradingClock.phase(day.atTime(15, 0), true));
        assertEquals(TradingClock.Phase.NON_TRADING, TradingClock.phase(day.atTime(10, 0), false));
        // Only after the close is today's bar final.
        assertTrue(TradingClock.phase(day.atTime(15, 30), true).dayComplete());
        assertFalse(TradingClock.phase(day.atTime(14, 0), true).dayComplete());
        assertFalse(TradingClock.phase(LocalDateTime.of(day, java.time.LocalTime.of(9, 0)), true).dayComplete());
    }
}
