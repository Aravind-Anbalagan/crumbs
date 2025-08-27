package com.crumbs.trade.service;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class OISignalGenerator {

    private static final List<Duration> DEFAULT_INTERVALS = Arrays.asList(
            Duration.ofMinutes(15),
            Duration.ofMinutes(30),
            Duration.ofHours(1),
            Duration.ofHours(4)
    );

    private final List<DataPoint> history = new ArrayList<>();

    public static class DataPoint {
        private String time;
        private BigDecimal oi;     // ✅ Open Interest
        private BigDecimal ltp;    // ✅ Last Traded Price
        private BigDecimal volume; // ✅ Volume

        private Map<String, IntervalChange> intervalChanges = new LinkedHashMap<>();

        @JsonIgnore
        private LocalDateTime parsedTime;

        public DataPoint(String time, BigDecimal oi, BigDecimal ltp, BigDecimal volume) {
            this.time = time;
            this.oi = oi;
            this.ltp = ltp;
            this.volume = volume;
            this.parsedTime = LocalDateTime.parse(time, DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH));
        }

        public String getTime() { return time; }
        public BigDecimal getOi() { return oi; }
        public BigDecimal getLtp() { return ltp; }
        public BigDecimal getVolume() { return volume; }
        public Map<String, IntervalChange> getIntervalChanges() { return intervalChanges; }

        @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY) // ✅ ensures all fields are serialized
        public static class IntervalChange {
            public BigDecimal oiChange;
            public BigDecimal ltpChange;
            public BigDecimal ltpPercentChange;
            public BigDecimal volumeChange;
            public String startTime;
            public String endTime;

            // ✅ Tick values (now included in JSON)
            public BigDecimal baseOi;
            public BigDecimal baseLtp;
            public BigDecimal baseVolume;

            public BigDecimal latestOi;
            public BigDecimal latestLtp;
            public BigDecimal latestVolume;

            public IntervalChange(BigDecimal oiChange, BigDecimal ltpChange, BigDecimal ltpPercentChange,
                                  BigDecimal volumeChange, String startTime, String endTime,
                                  BigDecimal baseOi, BigDecimal baseLtp, BigDecimal baseVolume,
                                  BigDecimal latestOi, BigDecimal latestLtp, BigDecimal latestVolume) {
                this.oiChange = oiChange;
                this.ltpChange = ltpChange;
                this.ltpPercentChange = ltpPercentChange;
                this.volumeChange = volumeChange;
                this.startTime = startTime;
                this.endTime = endTime;

                this.baseOi = baseOi;
                this.baseLtp = baseLtp;
                this.baseVolume = baseVolume;

                this.latestOi = latestOi;
                this.latestLtp = latestLtp;
                this.latestVolume = latestVolume;
            }
        }
    }

    private static Map<String, BigDecimal> parseStringToMap(String input) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        if (input == null || input.trim().isEmpty()) {
            return map;
        }

        // Split by comma (multiple entries)
        String[] entries = input.split(",");
        for (String entry : entries) {
            entry = entry.trim();

            // Remove any brackets
            entry = entry.replaceAll("[\\[\\]]", "").trim();

            if (entry.contains("=")) {
                String[] parts = entry.split("=");
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String valueStr = parts[1].trim();

                    try {
                        BigDecimal value = new BigDecimal(valueStr);
                        map.put(key, value);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid number format: " + valueStr);
                    }
                }
            }
        }
        return map;
    }





    public DataPoint addTicksFromTimestampedStrings(String ltpString, String oiString, String volumeString) {
        Map<String, BigDecimal> ltpMap = parseStringToMap(ltpString);
        Map<String, BigDecimal> oiMap = parseStringToMap(oiString);
        Map<String, BigDecimal> volumeMap = parseStringToMap(volumeString);

        BigDecimal lastOI = BigDecimal.ZERO;
        BigDecimal lastVolume = BigDecimal.ZERO;
        DataPoint latest = null;

        for (String ts : ltpMap.keySet()) {
            BigDecimal ltp = ltpMap.get(ts);
            BigDecimal oi = oiMap.getOrDefault(ts, lastOI);
            BigDecimal volume = volumeMap.getOrDefault(ts, lastVolume);

            DataPoint dp = new DataPoint(ts, oi, ltp, volume);
            history.add(dp);
            computeIntervalChanges(dp);
            latest = dp;

            lastOI = oi;
            lastVolume = volume;
        }

        history.sort(Comparator.comparing(dp -> dp.parsedTime));
        return latest;
    }

    public String addTicksFromTimestampedStringsAsJson(String ltpString, String oiString, String volumeString) {
        DataPoint latest = addTicksFromTimestampedStrings(ltpString, oiString, volumeString);
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writeValueAsString(latest);
        } catch (Exception e) {
            e.printStackTrace();
            return "{}";
        }
    }

    public String addNewTick(BigDecimal oi, BigDecimal ltp, BigDecimal volume) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH));
        return addNewTick(time, oi, ltp, volume);
    }

    public String addNewTick(String time, BigDecimal oi, BigDecimal ltp, BigDecimal volume) {
        DataPoint dp = new DataPoint(time, oi, ltp, volume);
        history.add(dp);
        computeIntervalChanges(dp);

        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writeValueAsString(dp);
        } catch (Exception e) {
            e.printStackTrace();
            return "{}";
        }
    }

    private void computeIntervalChanges(DataPoint latest) {
        // only sort if needed
        if (!history.isEmpty() && latest.parsedTime.isBefore(history.get(history.size() - 1).parsedTime)) {
            history.sort(Comparator.comparing(dp -> dp.parsedTime));
        }

        LocalDateTime now = latest.parsedTime;

        DataPoint overallBase = history.get(0);
        latest.intervalChanges.put("Overall", buildIntervalChange(overallBase, latest));

        for (Duration interval : DEFAULT_INTERVALS) {
            DataPoint base = findBaseBefore(interval, now);
            latest.intervalChanges.put(formatHumanReadable(interval), buildIntervalChange(base, latest));
        }
    }

    private DataPoint findBaseBefore(Duration interval, LocalDateTime now) {
        LocalDateTime target = now.minus(interval);
        DataPoint closest = null;
        for (DataPoint dp : history) {
            if (!dp.parsedTime.isAfter(target)) {
                closest = dp;
            } else {
                break;
            }
        }
        return closest != null ? closest : history.get(0);
    }

    private DataPoint.IntervalChange buildIntervalChange(DataPoint base, DataPoint latest) {
        BigDecimal oiChange = safeSubtract(latest.oi, base.oi);
        BigDecimal ltpChange = safeSubtract(latest.ltp, base.ltp);

        BigDecimal ltpPercentChange = BigDecimal.ZERO;
        if (base.ltp != null && base.ltp.abs().compareTo(BigDecimal.ZERO) > 0) {
            ltpPercentChange = ltpChange
                    .divide(base.ltp, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }

        BigDecimal volumeChange = safeSubtract(latest.volume, base.volume);

        return new DataPoint.IntervalChange(
                oiChange, ltpChange, ltpPercentChange, volumeChange,
                base.time, latest.time,
                base.oi, base.ltp, base.volume,
                latest.oi, latest.ltp, latest.volume
        );
    }

    private static BigDecimal safeSubtract(BigDecimal a, BigDecimal b) {
        return (a == null ? BigDecimal.ZERO : a)
                .subtract(b == null ? BigDecimal.ZERO : b);
    }

    private static String formatHumanReadable(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutes() % 60;
        if (hours > 0 && minutes > 0) return "Last " + hours + "h " + minutes + "m";
        if (hours > 0) return "Last " + hours + "h";
        if (minutes > 0) return "Last " + minutes + "m";
        return "Last 0m";
    }

    public void resetHistory() {
        history.clear();
    }
}
