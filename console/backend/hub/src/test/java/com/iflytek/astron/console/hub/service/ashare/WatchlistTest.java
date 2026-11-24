package com.iflytek.astron.console.hub.service.ashare;

import com.iflytek.astron.console.hub.entity.ashare.PaperAccount;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchlistTest {

    @Test
    void watchlistOnlySignalNeedsBothSwitches() {
        // Auto-trading and watchlist auto-buy on, a free slot: bought.
        assertNull(PaperTradingService.entrySkipReason(true, true, true, true, 0, 5, 5));
        // Watchlist auto-buy off: notify only.
        assertTrue(PaperTradingService.entrySkipReason(true, false, true, true, 0, 5, 5).contains("只提示"));
        // Auto-trading off: notify only (watchBuy is false then).
        assertTrue(PaperTradingService.entrySkipReason(true, false, false, true, 0, 5, 5).contains("自动交易已关闭"));
    }

    @Test
    void sameLimitsAsTheStrategyPool() {
        // A universe signal ignores the watchlist switch.
        assertNull(PaperTradingService.entrySkipReason(false, false, true, true, 0, 5, 5));
        // Market filter and the position limit apply to every signal.
        assertTrue(PaperTradingService.entrySkipReason(true, true, true, false, 0, 5, 5).contains("大盘过滤"));
        assertTrue(PaperTradingService.entrySkipReason(true, true, true, true, 2, 2, 5).contains("上限 5 只"));
        assertTrue(PaperTradingService.entrySkipReason(false, true, true, true, 2, 2, 5).contains("上限"));
    }

    @Test
    void parsesTheStoredList() {
        PaperAccount a = new PaperAccount();
        assertEquals(List.of(), PaperTradingService.watchlist(a));
        a.setWatchlist("[\"002837\",\"002281\",\"603083\"]");
        assertEquals(List.of("002837", "002281", "603083"), PaperTradingService.watchlist(a));
        a.setWatchlist("not json");
        assertEquals(List.of(), PaperTradingService.watchlist(a));
        assertEquals(List.of(), PaperTradingService.watchlist((PaperAccount) null));
    }
}
