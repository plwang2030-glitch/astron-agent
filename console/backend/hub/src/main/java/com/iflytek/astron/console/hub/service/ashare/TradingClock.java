package com.iflytek.astron.console.hub.service.ashare;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * A-share trading hours (Asia/Shanghai): opening call auction 09:15–09:25, continuous trading
 * 09:30–11:30 and 13:00–15:00. Trading days come from the index's daily bars (a past weekday
 * without a bar was a holiday).
 */
public final class TradingClock {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final LocalTime AUCTION_START = LocalTime.of(9, 15);
    /** From 09:20 the opening auction no longer accepts cancellations. */
    public static final LocalTime AUCTION_NO_CANCEL = LocalTime.of(9, 20);
    public static final LocalTime OPEN = LocalTime.of(9, 30);
    public static final LocalTime MORNING_CLOSE = LocalTime.of(11, 30);
    public static final LocalTime AFTERNOON_OPEN = LocalTime.of(13, 0);
    public static final LocalTime CLOSE = LocalTime.of(15, 0);
    /** End-of-day strategy rules run from here, on nearly complete daily bars. */
    public static final LocalTime END_OF_DAY_RULES = LocalTime.of(14, 50);

    private TradingClock() {}

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }

    /** Within 09:30–11:30 or 13:00–15:00 (the day must also be a trading day). */
    public static boolean inContinuousSession(LocalTime t) {
        return (!t.isBefore(OPEN) && t.isBefore(MORNING_CLOSE)) || (!t.isBefore(AFTERNOON_OPEN) && t.isBefore(CLOSE));
    }

    /** Placed during the trading hours of a trading day (including the lunch break): valid that day. */
    public static boolean placedIntraday(LocalDateTime placedAt, Collection<LocalDate> calendar) {
        LocalTime t = placedAt.toLocalTime();
        return calendar.contains(placedAt.toLocalDate()) && !t.isBefore(OPEN) && t.isBefore(CLOSE);
    }

    /**
     * The trading day an order is valid for: the day it was placed when placed during trading hours,
     * otherwise the first trading day whose 09:30 open comes after it (it then joins that day's opening
     * auction). {@code null} while that day has not started.
     */
    public static LocalDate orderSession(LocalDateTime placedAt, List<LocalDate> calendar) {
        if (placedIntraday(placedAt, calendar)) {
            return placedAt.toLocalDate();
        }
        for (LocalDate d : calendar) {
            if (d.atTime(OPEN).isAfter(placedAt)) {
                return d;
            }
        }
        return null;
    }

    public static String weekKey(LocalDate d) {
        return String.format(Locale.ROOT, "%d-W%02d", d.get(IsoFields.WEEK_BASED_YEAR),
                d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    public static String monthKey(LocalDate d) {
        return String.format(Locale.ROOT, "%d-%02d", d.getYear(), d.getMonthValue());
    }

    /** Last Monday–Friday of the week or month containing {@code d}. */
    static LocalDate lastWeekday(LocalDate d, boolean weekly) {
        LocalDate last = weekly ? d.with(DayOfWeek.FRIDAY) : d.with(TemporalAdjusters.lastDayOfMonth());
        while (last.getDayOfWeek() == DayOfWeek.SATURDAY || last.getDayOfWeek() == DayOfWeek.SUNDAY) {
            last = last.minusDays(1);
        }
        return last;
    }

    /** First day of the week (Monday) or month containing {@code d}. */
    public static LocalDate periodStart(LocalDate d, boolean weekly) {
        return weekly ? d.with(DayOfWeek.MONDAY) : d.withDayOfMonth(1);
    }

    public static String periodKey(LocalDate d, boolean weekly) {
        return weekly ? weekKey(d) : monthKey(d);
    }

    /**
     * The week or month to review after today's close, or {@code null}. Normally the current period on
     * its last weekday. When the previous period's last weekday was a holiday (no bar in the calendar),
     * that period is reviewed on the next trading day instead.
     *
     * @param calendar past trading days including today (today must be a trading day)
     * @param done periods already reviewed
     */
    public static String duePeriod(LocalDate today, List<LocalDate> calendar, Collection<String> done,
            boolean weekly) {
        if (!calendar.contains(today)) {
            return null;
        }
        String current = periodKey(today, weekly);
        if (today.equals(lastWeekday(today, weekly))) {
            return done.contains(current) ? null : current;
        }
        LocalDate prevDay = periodStart(today, weekly).minusDays(1);
        LocalDate prevLast = lastWeekday(prevDay, weekly);
        String prev = periodKey(prevDay, weekly);
        boolean prevLastWasHoliday = !calendar.isEmpty() && !prevLast.isBefore(calendar.get(0))
                && !calendar.contains(prevLast);
        // Only the first trading day after such a period catches up.
        boolean firstTradingDayAfter = calendar.stream()
                .filter(d -> d.isAfter(prevLast) && d.isBefore(today))
                .findAny()
                .isEmpty();
        return prevLastWasHoliday && firstTradingDayAfter && !done.contains(prev) ? prev : null;
    }
}
