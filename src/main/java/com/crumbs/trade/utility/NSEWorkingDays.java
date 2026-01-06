package com.crumbs.trade.utility;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class NSEWorkingDays {
	
    // List of public holidays (in format yyyy-MM-dd) for 2026
    private static final List<LocalDate> publicHolidays = Arrays.asList(
            LocalDate.of(2026, 1, 26),   // Republic Day (26-Jan-2026)
            LocalDate.of(2026, 2, 16),   // Mahashivratri (16-Feb-2026)
            LocalDate.of(2026, 3, 6),    // Holi (6-Mar-2026)
            LocalDate.of(2026, 3, 20),   // Id-Ul-Fitr (Ramzan Id) (20-Mar-2026)
            LocalDate.of(2026, 3, 30),   // Shri Mahavir Jayanti (30-Mar-2026)
            LocalDate.of(2026, 4, 3),    // Good Friday (3-Apr-2026)
            LocalDate.of(2026, 4, 6),    // Shri Ram Navami (6-Apr-2026)
            LocalDate.of(2026, 4, 14),   // Dr. Baba Saheb Ambedkar Jayanti (14-Apr-2026)
            LocalDate.of(2026, 5, 1),    // Maharashtra Day (1-May-2026)
            LocalDate.of(2026, 5, 27),   // Id-Ul-Adha (Bakri Id) (27-May-2026)
            LocalDate.of(2026, 8, 15),   // Independence Day (15-Aug-2026)
            LocalDate.of(2026, 8, 16),   // Shri Ganesh Chaturthi (16-Aug-2026)
            LocalDate.of(2026, 9, 26),   // Muharram (26-Sep-2026)
            LocalDate.of(2026, 10, 2),   // Mahatma Gandhi Jayanti (2-Oct-2026)
            LocalDate.of(2026, 10, 20),  // Dussehra (20-Oct-2026)
            LocalDate.of(2026, 11, 8),   // Diwali Laxmi Pujan (8-Nov-2026)
            LocalDate.of(2026, 11, 9),   // Diwali Balipratipada (9-Nov-2026)
            LocalDate.of(2026, 11, 24),  // Prakash Gurpurb Sri Guru Nanak Dev (24-Nov-2026)
            LocalDate.of(2026, 12, 25)   // Christmas (25-Dec-2026)
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