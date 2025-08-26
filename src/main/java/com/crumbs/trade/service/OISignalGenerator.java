package com.crumbs.trade.service;

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
            this.parsedTime = LocalDateTime.parse(time, DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss"));
        }

        public String getTime() { return time; }
        public BigDecimal getOi() { return oi; }
        public BigDecimal getLtp() { return ltp; }
        public BigDecimal getVolume() { return volume; }
        public Map<String, IntervalChange> getIntervalChanges() { return intervalChanges; }

        public static class IntervalChange {
            public BigDecimal oiChange;
            public BigDecimal ltpChange;
            public BigDecimal ltpPercentChange;
            public BigDecimal volumeChange;
            public String startTime;
            public String endTime;

            public IntervalChange(BigDecimal oiChange, BigDecimal ltpChange, BigDecimal ltpPercentChange,
                                  BigDecimal volumeChange, String startTime, String endTime) {
                this.oiChange = oiChange;
                this.ltpChange = ltpChange;
                this.ltpPercentChange = ltpPercentChange;
                this.volumeChange = volumeChange;
                this.startTime = startTime;
                this.endTime = endTime;
            }
        }
    }

    // Parse string like "[26-Aug-2025 11:16:31 = 151900, ...]" to Map<timestamp,value>
    private static Map<String, BigDecimal> parseStringToMap(String dataString) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        if (dataString == null || dataString.isEmpty()) return map;
        String[] entries = dataString.substring(1, dataString.length() - 1).split(", (?=\\d{2}-)");
        for (String entry : entries) {
            String[] parts = entry.split(" = ");
            if (parts.length == 2 && !parts[1].trim().isEmpty()) {
                map.put(parts[0].trim(), new BigDecimal(parts[1].trim()));
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
            BigDecimal ltp = ltpMap.get(ts);                  // ✅ LTP from ltpMap
            BigDecimal oi = oiMap.getOrDefault(ts, lastOI);   // ✅ OI from oiMap
            BigDecimal volume = volumeMap.getOrDefault(ts, lastVolume); // ✅ Volume

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
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss"));
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
        history.sort(Comparator.comparing(dp -> dp.parsedTime));
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
        BigDecimal oiChange = latest.oi.subtract(base.oi);
        BigDecimal ltpChange = latest.ltp.subtract(base.ltp);

        BigDecimal ltpPercentChange = BigDecimal.ZERO;
        if (base.ltp != null && base.ltp.abs().compareTo(BigDecimal.ZERO) > 0) {
            ltpPercentChange = ltpChange
                    .divide(base.ltp, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }

        BigDecimal volumeChange = latest.volume.subtract(base.volume);

        return new DataPoint.IntervalChange(
                oiChange,
                ltpChange,
                ltpPercentChange,
                volumeChange,
                base.time,
                latest.time
        );
    }

    private static String formatHumanReadable(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        StringBuilder label = new StringBuilder("Last ");
        if (hours > 0) {
            label.append(hours).append(" hour");
            if (hours > 1) label.append("s");
        }
        if (minutes > 0) {
            if (hours > 0) label.append(" ");
            label.append(minutes).append(" min");
        }
        if (hours == 0 && minutes == 0) label.append("0 min");
        return label.toString();
    }

    public void resetHistory() {
        history.clear();
    }
}
