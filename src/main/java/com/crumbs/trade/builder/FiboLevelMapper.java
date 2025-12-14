package com.crumbs.trade.builder;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.crumbs.trade.dto.FiboLevel;
import com.crumbs.trade.dto.FibonacciLevel;

@Component
public class FiboLevelMapper {

    private static final Pattern TOUCH_PATTERN =
            Pattern.compile("(\\d+)\\s+touches");

    public FiboLevel fromFibonacciLevel(FibonacciLevel src) {

        FiboLevel f = new FiboLevel();
        f.setLevel(src.getLevel());
        f.setLabel(src.getLabel());

        if (src.getLabel() != null) {

            if (src.getLabel().contains("CRITICAL")) {
                f.setStrength("CRITICAL");
            } else if (src.getLabel().contains("MODERATE")) {
                f.setStrength("MODERATE");
            }

            Matcher m = TOUCH_PATTERN.matcher(src.getLabel());
            if (m.find()) {
                f.setTouches(Integer.parseInt(m.group(1)));
            }
        }
        return f;
    }
}
