package com.crumbs.trade.utility;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class NSEWorkingDays {

	
    // List of public holidays (in format yyyy-MM-dd) for 2025
    private static final List<LocalDate> publicHolidays = Arrays.asList(
            LocalDate.of(2025, 2, 26),   // Mahashivratri (26-Feb-2025)
            LocalDate.of(2025, 3, 14),   // Holi (14-Mar-2025)
            LocalDate.of(2025, 3, 31),   // Id-Ul-Fitr (31-Mar-2025)
            LocalDate.of(2025, 4, 10),   // Shri Mahavir Jayanti (10-Apr-2025)
            LocalDate.of(2025, 4, 14),   // Dr. Baba Saheb Ambedkar Jayanti (14-Apr-2025)
            LocalDate.of(2025, 4, 18),   // Good Friday (18-Apr-2025)
            LocalDate.of(2025, 5, 1),    // Maharashtra Day (1-May-2025)
            LocalDate.of(2025, 8, 15),   // Independence Day / Parsi New Year (15-Aug-2025)
            LocalDate.of(2025, 8, 27),   // Shri Ganesh Chaturthi (27-Aug-2025)
            LocalDate.of(2025, 10, 2),   // Mahatma Gandhi Jayanti/Dussehra (2-Oct-2025)
            LocalDate.of(2025, 10, 21),  // Diwali Laxmi Pujan (21-Oct-2025)
            LocalDate.of(2025, 10, 22),  // Balipratipada (22-Oct-2025)
            LocalDate.of(2025, 11, 5),   // Prakash Gurpurb Sri Guru Nanak Dev (5-Nov-2025)
            LocalDate.of(2025, 12, 25)   // Christmas (25-Dec-2025)
    );

    // Method to check if a date is a working day for NSE
    public static boolean isNSEWorkingDay(LocalDate date) {
        // Check if it's a weekend (Saturday or Sunday)
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false; // Not a working day
        }
        
        // Check if it's a public holiday
        if (publicHolidays.contains(date)) {
            return false; // Not a working day (public holiday)
        }
        
        return true; // It is a working day
    }

    // Method to get the last working day before a given date (excluding the given day)
    public static LocalDate getLastWorkingDay(LocalDate date) {
        // Subtract one day from the given date and check
        LocalDate previousDay = date.minusDays(1);
        
        // Recursively check if the previous day is a working day
        if (isNSEWorkingDay(previousDay)) {
            return previousDay;
        } else {
            return getLastWorkingDay(previousDay); // Recursively call if it's not a working day
        }
    }

    /*
    public static void main(String[] args) {
        // Get today's date
        LocalDate today = LocalDate.now();
        
        // Check if today is a working day or a holiday
        if (isNSEWorkingDay(today)) {
            System.out.println("Today (" + today + ") is a working day.");
        } else {
            System.out.println("Today (" + today + ") is a holiday.");
        }

        // Get the last working day (assuming today is the working day)
        LocalDate lastWorkingDay = getLastWorkingDay(today);
        System.out.println("The last working day before " + today + " is: " + lastWorkingDay);
    }
    */
}
