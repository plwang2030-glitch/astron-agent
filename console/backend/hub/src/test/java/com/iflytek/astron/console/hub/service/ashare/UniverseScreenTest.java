package com.iflytek.astron.console.hub.service.ashare;

import com.iflytek.astron.console.hub.service.ashare.UniverseScreen.Candidate;
import com.iflytek.astron.console.hub.service.ashare.UniverseScreen.History;
import com.iflytek.astron.console.hub.service.ashare.UniverseScreen.Ranked;
import com.iflytek.astron.console.hub.service.ashare.UniverseScreen.Rules;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniverseScreenTest {

    private static final Rules RULES = Rules.defaults();

    /** Weekdays from 2024-01-01, about two and a half years. */
    private static final List<LocalDate> CALENDAR = weekdays(LocalDate.of(2024, 1, 1), 650);

    private static List<LocalDate> weekdays(LocalDate from, int n) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = from; out.size() < n; d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                out.add(d);
            }
        }
        return out;
    }

    private static History history(String code, List<LocalDate> days, double price, IntToDoubleFunction amount,
            boolean listedBefore) {
        int n = days.size();
        double[] close = new double[n];
        double[] amt = new double[n];
        for (int i = 0; i < n; i++) {
            close[i] = price;
            amt[i] = amount.applyAsDouble(i);
        }
        return new History(code, days.toArray(LocalDate[]::new), close, amt, listedBefore);
    }

    private static History steady(String code, double price, double amount) {
        return history(code, CALENDAR, price, i -> amount, true);
    }

    private static final LocalDate AS_OF = CALENDAR.get(CALENDAR.size() - 1);

    @Test
    void appliesEveryRule() {
        Candidate ok = new Candidate("600036", "招商银行", 8e11);
        assertNotNull(UniverseScreen.metricAt(ok, steady("600036", 40, 2e9), AS_OF, RULES));
        // ST by name.
        assertNull(UniverseScreen.metricAt(new Candidate("600001", "*ST测试", 8e11), steady("600001", 40, 2e9), AS_OF,
                RULES));
        // Average turnover below 5亿.
        assertNull(UniverseScreen.metricAt(ok, steady("600036", 40, 4e8), AS_OF, RULES));
        // Market cap below 300亿.
        assertNull(UniverseScreen.metricAt(new Candidate("600036", "招商银行", 2.9e10), steady("600036", 40, 2e9), AS_OF,
                RULES));
        // One lot (100 shares) above 12,500 yuan.
        assertNull(UniverseScreen.metricAt(ok, steady("600036", 130, 2e9), AS_OF, RULES));
        // STAR market: one lot is 200 shares, so 70 yuan (14,000 per lot) is too expensive.
        assertNull(UniverseScreen.metricAt(new Candidate("688981", "中芯国际", 8e11), steady("688981", 70, 2e9), AS_OF,
                RULES));
        assertNotNull(UniverseScreen.metricAt(new Candidate("688981", "中芯国际", 8e11), steady("688981", 60, 2e9), AS_OF,
                RULES));
    }

    @Test
    void listingAgeUsesTheFirstBarUnlessCutOffByTheWindow() {
        Candidate c = new Candidate("601127", "新股", 1e11);
        List<LocalDate> recent = CALENDAR.subList(CALENDAR.size() - 200, CALENDAR.size());
        // Listed about 200 trading days (less than a year) before.
        assertNull(UniverseScreen.metricAt(c, history("601127", recent, 40, i -> 2e9, false), AS_OF, RULES));
        // The same bars cut off by the download window: listed earlier, so the rule is met.
        assertNotNull(UniverseScreen.metricAt(c, history("601127", recent, 40, i -> 2e9, true), AS_OF, RULES));
    }

    @Test
    void pastMarketCapScalesWithTheAdjustedPrice() {
        int n = CALENDAR.size();
        double[] close = new double[n];
        double[] amt = new double[n];
        for (int i = 0; i < n; i++) {
            // Price doubled over the period: from 20 to 40.
            close[i] = 20 + 20.0 * i / (n - 1);
            amt[i] = 2e9;
        }
        History h = new History("600519", CALENDAR.toArray(LocalDate[]::new), close, amt, true);
        // Today 500亿; at the start it was about 250亿, below the 300亿 rule.
        Candidate c = new Candidate("600519", "测试", 5e10);
        assertNull(UniverseScreen.metricAt(c, h, CALENDAR.get(100), RULES));
        UniverseScreen.Metric now = UniverseScreen.metricAt(c, h, AS_OF, RULES);
        assertNotNull(now);
        assertEquals(5e10, now.cap(), 1);
    }

    @Test
    void ranksByTurnoverAndCapsThePool() {
        List<Candidate> candidates = new ArrayList<>();
        Map<String, History> histories = new HashMap<>();
        for (int i = 0; i < 120; i++) {
            String code = String.format("600%03d", i);
            candidates.add(new Candidate(code, "股票" + i, 1e11));
            histories.put(code, steady(code, 30, 1e9 + i * 1e7));
        }
        Ranked r = UniverseScreen.rank(candidates, histories, YearMonth.from(AS_OF), AS_OF, RULES);
        assertEquals(RULES.poolSize(), r.pool().size());
        assertEquals("600119", r.pool().get(0).code());
        assertEquals(30, r.trade(30).size());
        for (int i = 1; i < r.pool().size(); i++) {
            assertTrue(r.pool().get(i - 1).avgAmount() >= r.pool().get(i).avgAmount());
        }
    }

    @Test
    void monthUsesOnlyDataBeforeItStarts() {
        // Turnover jumps from 1亿 to 50亿 on the first trading day of a month.
        int jump = -1;
        for (int i = 1; i < CALENDAR.size(); i++) {
            if (CALENDAR.get(i).getMonthValue() != CALENDAR.get(i - 1).getMonthValue() && i > 400) {
                jump = i;
                break;
            }
        }
        int j = jump;
        Candidate c = new Candidate("600036", "招商银行", 8e11);
        History h = history("600036", CALENDAR, 40, i -> i >= j ? 5e10 : 1e8, true);
        Map<YearMonth, Ranked> screens = UniverseScreen.history(List.of(c), Map.of("600036", h), CALENDAR, RULES);
        YearMonth month = YearMonth.from(CALENDAR.get(jump));
        // The month of the jump is screened with data up to the previous trading day: not in yet.
        assertEquals(CALENDAR.get(jump - 1), screens.get(month).asOf());
        assertTrue(screens.get(month).pool().isEmpty());
        // The next month sees the high turnover.
        assertFalse(screens.get(month.plusMonths(1)).pool().isEmpty());
        // The calendar's first (partial) month has no screen; months are in order.
        assertFalse(screens.containsKey(YearMonth.from(CALENDAR.get(0))));
        List<YearMonth> keys = new ArrayList<>(screens.keySet());
        for (int i = 1; i < keys.size(); i++) {
            assertTrue(keys.get(i - 1).isBefore(keys.get(i)));
        }
    }

    @Test
    void suspendedStockIsSkipped() {
        // No bar in the last month.
        List<LocalDate> days = CALENDAR.subList(0, CALENDAR.size() - 25);
        assertNull(UniverseScreen.metricAt(new Candidate("600036", "招商银行", 8e11),
                history("600036", days, 40, i -> 2e9, true), AS_OF, RULES));
    }
}
