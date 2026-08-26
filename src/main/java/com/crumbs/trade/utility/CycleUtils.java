package com.crumbs.trade.utility;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.DayOfWeek;

public class CycleUtils {

    // NSE options expiry: Last TUESDAY of each month
    public static LocalDate getLastTuesday(YearMonth yearMonth) {
        LocalDate lastDay = yearMonth.atEndOfMonth();
        while (lastDay.getDayOfWeek() != DayOfWeek.TUESDAY) {
            lastDay = lastDay.minusDays(1);
        }
        return lastDay;
    }

    public static CycleBoundary getCurrentCycleBoundary(LocalDate today) {
        LocalDate currentExpiry = getLastTuesday(YearMonth.from(today));

        if (today.isAfter(currentExpiry)) {
            // NEW CYCLE: start from day after last expiry
            LocalDate cycleStart = currentExpiry.plusDays(1);
            LocalDate cycleEnd = getLastTuesday(YearMonth.from(today.plusMonths(1)));
            return new CycleBoundary(cycleStart, cycleEnd, true);
        } else {
            // CURRENT CYCLE: start from day after prev expiry
            LocalDate prevExpiry = getLastTuesday(YearMonth.from(today.minusMonths(1)));
            LocalDate cycleStart = prevExpiry.plusDays(1);
            LocalDate cycleEnd = currentExpiry;
            return new CycleBoundary(cycleStart, cycleEnd, false);
        }
    }

    public record CycleBoundary(LocalDate startDate, LocalDate endDate, boolean isNewCycle) {}
}