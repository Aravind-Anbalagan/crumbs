package com.crumbs.trade.utility;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;

public class ExpiryUtil {
    public static String[] getNormalizedExpiries(String rawExpiry) {
        if (rawExpiry == null || rawExpiry.trim().isEmpty() || "null".equalsIgnoreCase(rawExpiry)) {
            return new String[]{"", ""};
        }
        String clean = rawExpiry.trim().toUpperCase();
        
        // If 7 chars like "07JUL26" -> convert to ["07JUL26", "07JUL2026"]
        if (clean.length() == 7) {
            String dayMonth = clean.substring(0, 5); // "07JUL"
            String year = clean.substring(5);        // "26"
            return new String[]{ clean, dayMonth + "20" + year };
        }
        
        // If 9 chars like "07JUL2026" -> convert to ["07JUL26", "07JUL2026"]
        if (clean.length() == 9) {
            String dayMonth = clean.substring(0, 5); // "07JUL"
            String shortYear = clean.substring(7);   // "26"
            return new String[]{ dayMonth + shortYear, clean };
        }
        
        return new String[]{clean, clean};
    }
}