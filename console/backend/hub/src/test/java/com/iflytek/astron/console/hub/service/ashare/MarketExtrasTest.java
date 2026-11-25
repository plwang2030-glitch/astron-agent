package com.iflytek.astron.console.hub.service.ashare;

import com.iflytek.astron.console.hub.service.ashare.MarketExtras.Breadth;
import com.iflytek.astron.console.hub.service.ashare.MarketExtras.FlowDay;
import com.iflytek.astron.console.hub.service.ashare.MarketExtras.Sector;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketExtrasTest {

    @Test
    void sinaSectorsAreParsedAndRankedByMove() {
        // Sina answers with JavaScript: var X = {"key":"code,name,members,avgPrice,change,pctChg,..."}.
        String text = "var S_Finance_bankuai_sinaindustry = {"
                + "\"new_a\":\"new_a,玻璃行业,19,12.5,0.3,2.41,0,0,0,0,0,0,--\","
                + "\"new_b\":\"new_b,船舶制造,8,30.1,-0.5,-1.62,0,0,0,0,0,0,--\","
                + "\"new_c\":\"new_c,电力行业,62,8.4,0.1,0.85,0,0,0,0,0,0,--\"};";
        List<Sector> out = MarketExtras.parseSinaSectors(text);
        assertEquals(3, out.size());
        assertEquals("玻璃行业", out.get(0).name());
        assertEquals(2.41, out.get(0).pctChg(), 1e-9);
        assertEquals(19, out.get(0).members());
        assertEquals("船舶制造", out.get(2).name());
        assertTrue(out.get(0).pctChg() > out.get(1).pctChg());
    }

    @Test
    void sectorsWithoutDataAreNotDescribedAsFlat() {
        // Before the open Sina reports every sector as 0: that is no data, not a flat market.
        List<Sector> flat = List.of(new Sector("玻璃行业", 19, 0), new Sector("船舶制造", 8, 0));
        assertNull(MarketExtras.describeSectors(flat, 3));
        assertNull(MarketExtras.describeSectors(List.of(), 3));
        assertNull(MarketExtras.describeSectors(null, 3));
        String text = MarketExtras.describeSectors(List.of(new Sector("玻璃行业", 19, 2.4),
                new Sector("船舶制造", 8, -1.6)), 1);
        assertNotNull(text);
        assertTrue(text.contains("领涨：玻璃行业 +2.40%"));
        assertTrue(text.contains("领跌：船舶制造 -1.60%"));
    }

    @Test
    void breadthDescribesOnlyWhatWasRead() {
        LocalDate day = LocalDate.of(2026, 11, 25);
        assertNull(new Breadth(null, null, null, null, null, null, day).describe());
        assertEquals("涨停 55 家，跌停 2 家，炸板 3 家",
                new Breadth(null, null, null, 55, 2, 3, day).describe());
        String full = new Breadth(3920, 1349, 120, 155, 3, 0, day).describe();
        assertEquals("上涨 3920 家，下跌 1349 家，平盘 120 家；涨停 155 家，跌停 3 家", full);
    }

    @Test
    void mainNetSumAddsTheRequestedDays() {
        List<FlowDay> flow = List.of(
                new FlowDay(LocalDate.of(2026, 11, 20), 1e8, 5e7, 5e7, 1.0),
                new FlowDay(LocalDate.of(2026, 11, 23), -2e8, -1.5e8, -5e7, -2.0),
                new FlowDay(LocalDate.of(2026, 11, 24), 3e8, 2e8, 1e8, 3.0));
        assertEquals(2e8, MarketExtras.mainNetSum(flow, 1), 1);
        assertEquals(5e7, MarketExtras.mainNetSum(flow, 2), 1);
        assertEquals(1e8, MarketExtras.mainNetSum(flow, 10), 1);
        assertNull(MarketExtras.mainNetSum(List.of(), 5));
        assertNull(MarketExtras.mainNetSum(null, 5));
    }

    @Test
    void flowIsReportedInYiWithTheSignKept() {
        FlowDay day = new FlowDay(LocalDate.of(2026, 11, 24), 9.06e7, -1.2e8, 2.1e8, -0.8);
        assertEquals(0.91, day.toJson().getDoubleValue("netYi"), 1e-9);
        assertEquals(-1.2, day.toJson().getDoubleValue("mainYi"), 1e-9);
        assertEquals(2.1, day.toJson().getDoubleValue("retailYi"), 1e-9);
    }
}
