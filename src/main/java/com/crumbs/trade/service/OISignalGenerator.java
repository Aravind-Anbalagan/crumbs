package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;



@Service
public class OISignalGenerator {

    public static class DataPoint {
        private LocalDateTime time;
        private BigDecimal oi;
        private BigDecimal ltp;
        private String type; // "CE" or "PE"
        private String rawSignal;
        private String confirmedSignal;
        private String supportResistanceType; // SUPPORT or RESISTANCE

        public DataPoint(String timestamp, BigDecimal oi, BigDecimal ltp, String type) {
            this.time = LocalDateTime.parse(timestamp, DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss"));
            this.oi = oi;
            this.ltp = ltp;
            this.type = type;
        }

        public LocalDateTime getTime() {
            return time;
        }

        public BigDecimal getOi() {
            return oi;
        }

        public BigDecimal getLtp() {
            return ltp;
        }

        public String getType() {
            return type;
        }

        public void setRawSignal(String rawSignal) {
            this.rawSignal = rawSignal;
        }

        public String getRawSignal() {
            return rawSignal;
        }

        public void setConfirmedSignal(String confirmedSignal) {
            this.confirmedSignal = confirmedSignal;
        }

        public String getConfirmedSignal() {
            return confirmedSignal;
        }

        public String getSupportResistanceType() {
            return supportResistanceType;
        }

        public void setSupportResistanceType(String supportResistanceType) {
            this.supportResistanceType = supportResistanceType;
        }
    }

    public static Map<String, BigDecimal> parseStringToMap(String dataString) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        dataString = dataString.substring(1, dataString.length() - 1);
        String[] entries = dataString.split(", (?=\\d{2}-)");

        for (String entry : entries) {
            String[] parts = entry.split(" = ");
            if (parts.length == 2) {
                String time = parts[0].trim();
                BigDecimal value = new BigDecimal(parts[1].trim());
                map.put(time, value);
            }
        }

        return map;
    }

    public static List<DataPoint> parseDataPoints(String ltpString, String oiString, String type) {
        Map<String, BigDecimal> ltpMap = parseStringToMap(ltpString);
        Map<String, BigDecimal> oiMap = parseStringToMap(oiString);

        List<DataPoint> dataPoints = new ArrayList<>();

        for (String timestamp : ltpMap.keySet()) {
            if (oiMap.containsKey(timestamp)) {
                dataPoints.add(new DataPoint(timestamp, oiMap.get(timestamp), ltpMap.get(timestamp), type));
            }
        }

        dataPoints.sort(Comparator.comparing(DataPoint::getTime));
        return dataPoints;
    }

    public static void generateRawSignals(List<DataPoint> dataPoints) {
        for (int i = 0; i < dataPoints.size(); i++) {
            if (i == 0) {
                dataPoints.get(i).setRawSignal("BASE");
                continue;
            }
            DataPoint prev = dataPoints.get(i - 1);
            DataPoint curr = dataPoints.get(i);

            int oiCompare = curr.getOi().compareTo(prev.getOi());
            int ltpCompare = curr.getLtp().compareTo(prev.getLtp());

            String signal;

            if (curr.getType().equalsIgnoreCase("CE")) {
                if (oiCompare > 0 && ltpCompare > 0) signal = "SELL";
                else if (oiCompare < 0 && ltpCompare > 0) signal = "BUY";
                else if (oiCompare < 0 && ltpCompare < 0) signal = "HOLD";
                else signal = "SIDEWAYS";
            } else if (curr.getType().equalsIgnoreCase("PE")) {
                if (oiCompare > 0 && ltpCompare > 0) signal = "SELL";
                else if (oiCompare < 0 && ltpCompare > 0) signal = "BUY";
                else if (oiCompare < 0 && ltpCompare < 0) signal = "HOLD";
                else signal = "SIDEWAYS";
            } else {
                signal = "UNKNOWN";
            }

            curr.setRawSignal(signal);
        }
    }

    public static List<String> confirmSignals(List<DataPoint> dataPoints) {
        List<String> confirmedSignals = new ArrayList<>();
        for (int i = 0; i < dataPoints.size(); i++) {
            if (i < 3) {
                confirmedSignals.add(dataPoints.get(i).getRawSignal());
                dataPoints.get(i).setConfirmedSignal(dataPoints.get(i).getRawSignal());
            } else {
                String s1 = dataPoints.get(i - 2).getRawSignal();
                String s2 = dataPoints.get(i - 1).getRawSignal();
                String s3 = dataPoints.get(i).getRawSignal();

                Map<String, Integer> countMap = new HashMap<>();
                for (String s : Arrays.asList(s1, s2, s3)) {
                    countMap.put(s, countMap.getOrDefault(s, 0) + 1);
                }

                Optional<String> majoritySignal = countMap.entrySet().stream()
                        .filter(e -> e.getValue() >= 2)
                        .map(Map.Entry::getKey)
                        .findFirst();

                String finalSignal = majoritySignal.orElse("HOLD");

                confirmedSignals.add(finalSignal);
                dataPoints.get(i).setConfirmedSignal(finalSignal);
            }
        }
        return confirmedSignals;
    }

    public static void filterRepeatedSignals(List<DataPoint> dataPoints) {
        String lastConfirmed = null;

        for (int i = 0; i < dataPoints.size(); i++) {
            String current = dataPoints.get(i).getConfirmedSignal();

            if (current.equals("BUY") || current.equals("SELL")) {
                if (lastConfirmed == null) {
                    lastConfirmed = current;
                } else if (lastConfirmed.equals(current)) {
                    dataPoints.get(i).setConfirmedSignal("HOLD");
                } else {
                    lastConfirmed = current;
                }
            }
        }
    }

    public static void mapSignalToMarketMovement(List<DataPoint> dataPoints) {
        for (DataPoint dp : dataPoints) {
            String signal = dp.getConfirmedSignal();
            String marketMove = "SIDEWAYS";

            switch (signal) {
                case "BUY":
                    marketMove = "UP";
                    break;
                case "SELL":
                    marketMove = "DOWN";
                    break;
                case "HOLD":
                case "SIDEWAYS":
                case "BASE":
                    marketMove = "SIDEWAYS";
                    break;
                default:
                    marketMove = "UNKNOWN";
            }

            dp.setConfirmedSignal(signal + " - " + marketMove);
        }
    }

    public static void detectSupportResistance(List<DataPoint> dataPoints) {
        BigDecimal maxSupportOI = BigDecimal.ZERO;
        BigDecimal maxResistanceOI = BigDecimal.ZERO;
        int supportIndex = -1;
        int resistanceIndex = -1;

        for (int i = 1; i < dataPoints.size(); i++) {
            DataPoint prev = dataPoints.get(i - 1);
            DataPoint curr = dataPoints.get(i);

            int oiCompare = curr.getOi().compareTo(prev.getOi());
            int ltpCompare = curr.getLtp().compareTo(prev.getLtp());

            if (curr.getType().equalsIgnoreCase("PE")) {
                if (oiCompare > 0 && ltpCompare < 0 && curr.getOi().compareTo(maxSupportOI) > 0) {
                    maxSupportOI = curr.getOi();
                    supportIndex = i;
                }
            } else if (curr.getType().equalsIgnoreCase("CE")) {
                if (oiCompare > 0 && ltpCompare < 0 && curr.getOi().compareTo(maxResistanceOI) > 0) {
                    maxResistanceOI = curr.getOi();
                    resistanceIndex = i;
                }
            }
        }

        if (supportIndex != -1) {
            dataPoints.get(supportIndex).setSupportResistanceType("SUPPORT");
        }
        if (resistanceIndex != -1) {
            dataPoints.get(resistanceIndex).setSupportResistanceType("RESISTANCE");
        }

        System.out.println("\n--- Support and Resistance Levels ---");
        if (supportIndex != -1) {
            System.out.println("SUPPORT at " + dataPoints.get(supportIndex).getTime()
                    + " | OI: " + dataPoints.get(supportIndex).getOi());
        } else {
            System.out.println("No strong PE-based support found.");
        }

        if (resistanceIndex != -1) {
            System.out.println("RESISTANCE at " + dataPoints.get(resistanceIndex).getTime()
                    + " | OI: " + dataPoints.get(resistanceIndex).getOi());
        } else {
            System.out.println("No strong CE-based resistance found.");
        }
    }

    public String getSignal(String ltpString, String oiString, String type) {
        List<DataPoint> dataPoints = parseDataPoints(ltpString, oiString, type);

        generateRawSignals(dataPoints);
        confirmSignals(dataPoints);
        filterRepeatedSignals(dataPoints);
        mapSignalToMarketMovement(dataPoints);
        detectSupportResistance(dataPoints);

        System.out.println("Time\t\t\tSignal\t\tSupport/Resistance");
        for (DataPoint dp : dataPoints) {
            System.out.println(dp.getTime() + "\t" + dp.getConfirmedSignal()
                    + "\t" + (dp.getSupportResistanceType() != null ? dp.getSupportResistanceType() : ""));
        }

        return dataPoints.get(dataPoints.size() - 1).getTime() + " = " + dataPoints.get(dataPoints.size() - 1).getConfirmedSignal();
    }
}