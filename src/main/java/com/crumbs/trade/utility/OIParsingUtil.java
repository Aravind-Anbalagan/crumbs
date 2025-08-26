package com.crumbs.trade.utility;



import com.crumbs.trade.dto.TimeValue;
import java.util.ArrayList;
import java.util.List;

public class OIParsingUtil {

    public static List<TimeValue> parseTimeValueString(String rawData) {
        List<TimeValue> list = new ArrayList<>();
        if (rawData == null || rawData.isEmpty()) return list;

        // Remove brackets [] if present
        rawData = rawData.replaceAll("[\\[\\]]", "");

        // Split by comma and space
        String[] items = rawData.split(", ");
        for (String item : items) {
            String[] parts = item.split("=");
            if (parts.length == 2) {
                String timestamp = parts[0].trim();
                String value = parts[1].trim();
                list.add(new TimeValue(timestamp, value));
            }
        }

        return list;
    }
}

