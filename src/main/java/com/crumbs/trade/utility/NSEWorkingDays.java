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
		    LocalDate.of(2026, 1, 26),   // Republic Day
		    LocalDate.of(2026, 3, 3),    // Holi ← was Mar 06
		    LocalDate.of(2026, 3, 26),   // Shri Ram Navami ← was Apr 06
		    LocalDate.of(2026, 3, 31),   // Shri Mahavir Jayanti ← was Mar 30
		    LocalDate.of(2026, 4, 3),    // Good Friday
		    LocalDate.of(2026, 4, 14),   // Dr. Baba Saheb Ambedkar Jayanti
		    LocalDate.of(2026, 5, 1),    // Maharashtra Day
		    LocalDate.of(2026, 5, 28),   // Bakri Id ← was May 27
		    LocalDate.of(2026, 6, 26),   // Muharram ← was Sep 26
		    LocalDate.of(2026, 9, 14),   // Ganesh Chaturthi ← was Aug 16
		    LocalDate.of(2026, 10, 2),   // Mahatma Gandhi Jayanti
		    LocalDate.of(2026, 10, 20),  // Dussehra
		    LocalDate.of(2026, 11, 10),  // Diwali-Balipratipada ← was Nov 09
		    LocalDate.of(2026, 11, 24),  // Prakash Gurpurb Sri Guru Nanak Dev
		    LocalDate.of(2026, 12, 25)   // Christmas
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