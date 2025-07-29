package com.crumbs.trade.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public class DynamicIndicatorDTO {

    private final Map<String, Object[]> headers = new LinkedHashMap<>();

    public void addHeader(String name, Object value, boolean visible) {
        headers.put(name, new Object[]{value, visible});
    }

    public Map<String, Object[]> getHeaders() {
        return headers;
    }
}
