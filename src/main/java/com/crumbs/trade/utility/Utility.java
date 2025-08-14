package com.crumbs.trade.utility;

import org.springframework.stereotype.Component;

@Component
public class Utility {

	public static String normalizeToken(String token) {
        // Remove everything except letters/numbers and uppercase it
        token = token.replaceAll("[^A-Za-z0-9]", "").toUpperCase();

        // Pattern for options: SYMBOL + DATE + STRIKE + OPTYPE (C/P/CE/PE)
        String optionRegex = "([A-Z]+)(\\d{2}[A-Z]{3}\\d{2})(\\d+)([CP]E?)";

        // Pattern for futures: SYMBOL + DATE + F
        String futureRegex = "([A-Z]+)(\\d{2}[A-Z]{3}\\d{2})F";

        if (token.matches(optionRegex)) {
            String symbol = token.replaceAll(optionRegex, "$1");
            String date = token.replaceAll(optionRegex, "$2");
            String strike = token.replaceAll(optionRegex, "$3");
            String optType = token.replaceAll(optionRegex, "$4").substring(0, 1); // Keep only C or P
            return symbol + date + optType + strike;
        } else if (token.matches(futureRegex)) {
            String symbol = token.replaceAll(futureRegex, "$1");
            String date = token.replaceAll(futureRegex, "$2");
            return symbol + date + "F";
        }

        // If doesn't match known patterns, return as-is
        return token;
    }
}
