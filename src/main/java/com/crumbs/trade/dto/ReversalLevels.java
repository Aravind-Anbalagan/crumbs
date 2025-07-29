package com.crumbs.trade.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ReversalLevels {
    private BigDecimal currentPrice;

    // === Support/Resistance-Based ===
    private String sr_signal;                      // BUY / SELL / HOLD
    private String sr_trend;                       // UPTREND / DOWNTREND / SIDEWAYS / UNKNOWN
    private String sr_reason;                      // Reason for SR signal
    private String sr_confidence;                  // LOW / MEDIUM / HIGH
    private boolean sr_priceActionTriggered;

    private BigDecimal sr_stopLoss;
    private BigDecimal sr_projectedTarget;

    private List<BigDecimal> sr_nearestSupports;
    private List<BigDecimal> sr_nearestResistances;
    private String sr_nearestSupportsJson;
    private String sr_nearestResistancesJson;

    private boolean volumeConfirmed;

    // === Fibonacci-Based ===
    private boolean fibo_triggered;

    private List<FibonacciLevel> fibo_supports;
    private List<FibonacciLevel> fibo_resistances;
    private String fibo_supportsJson;
    private String fibo_resistancesJson;

    private BigDecimal fibo_nearestLevel;
    private String fibo_label;
    private String fibo_type;                      // Support / Resistance
    private String fibo_bias;                      // Bullish bounce expected / Bearish rejection likely

    // ✅ NEW: Future Fibonacci Signal Fields (logic to be implemented later)
    private String fibo_signal;                    // BUY / SELL / HOLD
    private String fibo_reason;                    // Reason for fibo signal
    private String fibo_confidence;                // LOW / MEDIUM / HIGH
    private String fibo_trend;                     // Optional trend from fibo swing

    // === External SL Logic (Optional) ===
    private BigDecimal buyStopLoss;
    private BigDecimal sellStopLoss;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public void serializeListsToJson() {
        this.sr_nearestSupportsJson = toJson(this.sr_nearestSupports);
        this.sr_nearestResistancesJson = toJson(this.sr_nearestResistances);
        this.fibo_supportsJson = toJson(this.fibo_supports);
        this.fibo_resistancesJson = toJson(this.fibo_resistances);
    }

    public void deserializeJsonToLists() {
        this.sr_nearestSupports = fromJson(sr_nearestSupportsJson, new TypeReference<>() {});
        this.sr_nearestResistances = fromJson(sr_nearestResistancesJson, new TypeReference<>() {});
        this.fibo_supports = fromJson(fibo_supportsJson, new TypeReference<>() {});
        this.fibo_resistances = fromJson(fibo_resistancesJson, new TypeReference<>() {});
    }

    public static <T> String toJson(List<T> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    public static <T> List<T> fromJson(String json, TypeReference<List<T>> typeRef) {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
    }
}
