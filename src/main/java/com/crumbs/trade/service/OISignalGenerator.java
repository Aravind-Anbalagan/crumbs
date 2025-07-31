package com.crumbs.trade.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OISignalGenerator {

    public static class DataPoint {
        private final LocalDateTime time;
        private final BigDecimal oi;
        private final BigDecimal ltp;
        private final String type;
        private String rawSignal;
        private String confirmedSignal;
        private String marketMovement;
        private String supportResistanceType;

        public DataPoint(String timestamp, BigDecimal oi, BigDecimal ltp, String type) {
            this.time = LocalDateTime.parse(timestamp, DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss"));
            this.oi = oi;
            this.ltp = ltp;
            this.type = type;
        }

        public LocalDateTime getTime() { return time; }
        public BigDecimal getOi() { return oi; }
        public BigDecimal getLtp() { return ltp; }
        public String getType() { return type; }

        public String getRawSignal() { return rawSignal; }
        public void setRawSignal(String rawSignal) { this.rawSignal = rawSignal; }

        public String getConfirmedSignal() { return confirmedSignal; }
        public void setConfirmedSignal(String confirmedSignal) { this.confirmedSignal = confirmedSignal; }

        public String getMarketMovement() { return marketMovement; }
        public void setMarketMovement(String marketMovement) { this.marketMovement = marketMovement; }

        public String getSupportResistanceType() { return supportResistanceType; }
        public void setSupportResistanceType(String supportResistanceType) { this.supportResistanceType = supportResistanceType; }
    }

    public static Map<String, BigDecimal> parseStringToMap(String dataString) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        String[] entries = dataString.substring(1, dataString.length() - 1).split(", (?=\\d{2}-)");

        for (String entry : entries) {
            String[] parts = entry.split(" = ");
            if (parts.length == 2) {
                map.put(parts[0].trim(), new BigDecimal(parts[1].trim()));
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
            if (oiCompare > 0 && ltpCompare > 0) signal = "SELL";
            else if (oiCompare < 0 && ltpCompare > 0) signal = "BUY";
            else if (oiCompare < 0 && ltpCompare < 0) signal = "HOLD";
            else signal = "SIDEWAYS";

            curr.setRawSignal(signal);
        }
    }

    public static List<String> confirmSignals(List<DataPoint> dataPoints) {
        List<String> confirmedSignals = new ArrayList<>();

        for (int i = 0; i < dataPoints.size(); i++) {
            String confirmed;
            if (i < 3) {
                confirmed = dataPoints.get(i).getRawSignal();
            } else {
                List<String> recent = Arrays.asList(
                        dataPoints.get(i - 2).getRawSignal(),
                        dataPoints.get(i - 1).getRawSignal(),
                        dataPoints.get(i).getRawSignal()
                );

                confirmed = recent.stream()
                        .collect(Collectors.groupingBy(s -> s, Collectors.counting()))
                        .entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse("HOLD");
            }
            confirmedSignals.add(confirmed);
            dataPoints.get(i).setConfirmedSignal(confirmed);
        }
        return confirmedSignals;
    }

    public static void filterRepeatedSignals(List<DataPoint> dataPoints) {
        String lastConfirmed = null;
        for (DataPoint dp : dataPoints) {
            String current = dp.getConfirmedSignal();
            if (("BUY".equals(current) || "SELL".equals(current))) {
                if (current.equals(lastConfirmed)) {
                    dp.setConfirmedSignal("HOLD");
                } else {
                    lastConfirmed = current;
                }
            }
        }
    }

    public static void mapSignalToMarketMovement(List<DataPoint> dataPoints) {
        for (DataPoint dp : dataPoints) {
            switch (dp.getConfirmedSignal()) {
                case "BUY":
                    dp.setMarketMovement("UP");
                    break;
                case "SELL":
                    dp.setMarketMovement("DOWN");
                    break;
                case "HOLD":
                case "SIDEWAYS":
                case "BASE":
                    dp.setMarketMovement("SIDEWAYS");
                    break;
                default:
                    dp.setMarketMovement("UNKNOWN");
            }
        }
    }

    public static void detectSupportResistance(List<DataPoint> dataPoints) {
        BigDecimal maxSupportOI = BigDecimal.ZERO;
        BigDecimal maxResistanceOI = BigDecimal.ZERO;
        int supportIndex = -1, resistanceIndex = -1;

        for (int i = 1; i < dataPoints.size(); i++) {
            DataPoint prev = dataPoints.get(i - 1);
            DataPoint curr = dataPoints.get(i);
            int oiCompare = curr.getOi().compareTo(prev.getOi());
            int ltpCompare = curr.getLtp().compareTo(prev.getLtp());

            if ("PE".equalsIgnoreCase(curr.getType()) && oiCompare > 0 && ltpCompare < 0 && curr.getOi().compareTo(maxSupportOI) > 0) {
                maxSupportOI = curr.getOi();
                supportIndex = i;
            }
            if ("CE".equalsIgnoreCase(curr.getType()) && oiCompare > 0 && ltpCompare < 0 && curr.getOi().compareTo(maxResistanceOI) > 0) {
                maxResistanceOI = curr.getOi();
                resistanceIndex = i;
            }
        }

        if (supportIndex != -1) {
            dataPoints.get(supportIndex).setSupportResistanceType("SUPPORT");
        }
        if (resistanceIndex != -1) {
            dataPoints.get(resistanceIndex).setSupportResistanceType("RESISTANCE");
        }
    }

    public String getSignal(String ltpString, String oiString, String type) {
        List<DataPoint> dataPoints = parseDataPoints(ltpString, oiString, type);
        generateRawSignals(dataPoints);
        confirmSignals(dataPoints);
        filterRepeatedSignals(dataPoints);
        mapSignalToMarketMovement(dataPoints);
        detectSupportResistance(dataPoints);

        System.out.println("Time\t\t\t\tSignal\t\tMarketMove\tSupport/Resistance");
        for (DataPoint dp : dataPoints) {
            System.out.println(dp.getTime() + "\t" + dp.getConfirmedSignal() + "\t" + dp.getMarketMovement()
                    + "\t" + Optional.ofNullable(dp.getSupportResistanceType()).orElse(""));
        }

        DataPoint last = dataPoints.get(dataPoints.size() - 1);
        return last.getTime() + " = " + last.getConfirmedSignal() + " - " + last.getMarketMovement();
    }
}