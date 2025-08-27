package com.crumbs.trade.service;


import org.springframework.stereotype.Service;

import com.crumbs.trade.dto.BestSignalResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class TradingSignalService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss");
    private static final Pattern DATA_PATTERN = Pattern.compile("(\\d{2}-\\w{3}-\\d{4} \\d{2}:\\d{2}:\\d{2}) = ([\\d.]+)");
    
    // Signal thresholds - can be configured
    private static final double OI_CHANGE_THRESHOLD = 2.0;
    private static final double SIGNIFICANT_OI_CHANGE = 5.0;
    private static final double VOLUME_SPIKE_MULTIPLIER = 1.5;
    private static final double PRICE_CHANGE_THRESHOLD = 0.3;
    private static final int MIN_DATA_POINTS = 5;

    /**
     * Generate the best trading signal (CALL or PUT) with highest accuracy
     * Returns only the signal with higher confidence
     * 
     * @param oiData Open Interest data in string format
     * @param ltpData Last Traded Price data in string format
     * @param volumeData Volume data in string format
     * @return String representing the best signal (e.g., "CALL_BUY", "PUT_SELL", "HOLD")
     */
    public String generateBestSignal(String oiData, String ltpData, String volumeData) {
        try {
            SignalAnalysis analysis = performSignalAnalysis(oiData, ltpData, volumeData);
            if (analysis == null) {
                return "HOLD";
            }
            
            Signal callSignal = generateCallSignal(analysis.getMetrics(), analysis.getCurrent(), analysis.getPrevious());
            Signal putSignal = generatePutSignal(analysis.getMetrics(), analysis.getCurrent(), analysis.getPrevious());
            
            // Return the signal with higher confidence
            if (callSignal.getConfidence() > putSignal.getConfidence()) {
                return formatSignalString(callSignal);
            } else if (putSignal.getConfidence() > callSignal.getConfidence()) {
                return formatSignalString(putSignal);
            } else {
                // If confidence is equal, prefer the stronger signal type
                if (isStrongerSignal(callSignal.getSignal()) && !isStrongerSignal(putSignal.getSignal())) {
                    return formatSignalString(callSignal);
                } else if (isStrongerSignal(putSignal.getSignal()) && !isStrongerSignal(callSignal.getSignal())) {
                    return formatSignalString(putSignal);
                } else {
                    // If still equal, return HOLD
                    return "HOLD";
                }
            }
            
        } catch (Exception e) {
            return "ERROR";
        }
    }

    /**
     * Generate the best trading signal with detailed information
     * Returns the signal with higher confidence along with details
     * 
     * @param oiData Open Interest data in string format
     * @param ltpData Last Traded Price data in string format
     * @param volumeData Volume data in string format
     * @return BestSignalResult containing the best signal with details
     */
    public BestSignalResult generateBestSignalWithDetails(String oiData, String ltpData, String volumeData) {
        try {
            SignalAnalysis analysis = performSignalAnalysis(oiData, ltpData, volumeData);
            if (analysis == null) {
                return new BestSignalResult("HOLD", 50.0, Arrays.asList("Insufficient data"), "NEUTRAL");
            }
            
            Signal callSignal = generateCallSignal(analysis.getMetrics(), analysis.getCurrent(), analysis.getPrevious());
            Signal putSignal = generatePutSignal(analysis.getMetrics(), analysis.getCurrent(), analysis.getPrevious());
            
            // Determine the best signal
            Signal bestSignal;
            if (callSignal.getConfidence() > putSignal.getConfidence()) {
                bestSignal = callSignal;
            } else if (putSignal.getConfidence() > callSignal.getConfidence()) {
                bestSignal = putSignal;
            } else {
                // Equal confidence - choose stronger signal
                if (isStrongerSignal(callSignal.getSignal()) && !isStrongerSignal(putSignal.getSignal())) {
                    bestSignal = callSignal;
                } else if (isStrongerSignal(putSignal.getSignal()) && !isStrongerSignal(callSignal.getSignal())) {
                    bestSignal = putSignal;
                } else {
                    // Return the one with better reasons or default to call
                    bestSignal = callSignal.getReasons().size() >= putSignal.getReasons().size() ? callSignal : putSignal;
                }
            }
            
            String formattedSignal = formatSignalString(bestSignal);
            return new BestSignalResult(formattedSignal, bestSignal.getConfidence(), bestSignal.getReasons(), bestSignal.getType());
            
        } catch (Exception e) {
            return new BestSignalResult("ERROR", 0.0, Arrays.asList("Error: " + e.getMessage()), "ERROR");
        }
    }

    /**
     * Format signal into readable string format
     */
    private String formatSignalString(Signal signal) {
        if ("ERROR".equals(signal.getSignal())) {
            return "ERROR";
        }
        
        if ("HOLD".equals(signal.getSignal())) {
            return "HOLD";
        }
        
        return signal.getType() + "_" + signal.getSignal();
    }

    /**
     * Check if a signal is considered "stronger" (STRONG_BUY/STRONG_SELL vs BUY/SELL)
     */
    private boolean isStrongerSignal(String signal) {
        return "STRONG_BUY".equals(signal) || "STRONG_SELL".equals(signal);
    }

    /**
     * Generate both CALL and PUT signals (legacy method for backward compatibility)
     * 
     * @param oiData Open Interest data in string format
     * @param ltpData Last Traded Price data in string format
     * @param volumeData Volume data in string format
     * @return TradingSignalResult containing CALL and PUT signals
     */
    public TradingSignalResult generateTradingSignals(String oiData, String ltpData, String volumeData) {
        SignalAnalysis analysis = performSignalAnalysis(oiData, ltpData, volumeData);

        if (analysis == null) {
            // fallback default HOLD signals
            return new TradingSignalResult(
                new Signal("HOLD", 50.0, Arrays.asList("Insufficient data"), "CALL"),
                new Signal("HOLD", 50.0, Arrays.asList("Insufficient data"), "PUT"),
                new SignalMetrics()
            );
        }

        Signal callSignal = generateCallSignal(analysis.getMetrics(), analysis.getCurrent(), analysis.getPrevious());
        Signal putSignal  = generatePutSignal(analysis.getMetrics(), analysis.getCurrent(), analysis.getPrevious());

        return new TradingSignalResult(callSignal, putSignal, analysis.getMetrics());
    }


    /**
     * Common analysis method for both CALL and PUT signals
     */
    private SignalAnalysis performSignalAnalysis(String oiData, String ltpData, String volumeData) {
        try {
            // Parse input data
            List<DataPoint> oiPoints = parseDataString(oiData);
            List<DataPoint> ltpPoints = parseDataString(ltpData);
            List<DataPoint> volumePoints = parseDataString(volumeData);
            
            // Validate data
            if (oiPoints.isEmpty() || ltpPoints.isEmpty() || volumePoints.isEmpty()) {
                return null;
            }
            
            if (oiPoints.size() < MIN_DATA_POINTS) {
                return null;
            }
            
            // Combine and analyze data
            List<CombinedDataPoint> combinedData = combineDataPoints(oiPoints, ltpPoints, volumePoints);
            
            if (combinedData.size() < MIN_DATA_POINTS) {
                return null;
            }
            
            CombinedDataPoint current = combinedData.get(combinedData.size() - 1);
            CombinedDataPoint previous = combinedData.get(combinedData.size() - 2);
            SignalMetrics metrics = calculateMetrics(combinedData);
            
            return new SignalAnalysis(metrics, current, previous);
            
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Parse data string format into structured data points
     */
    private List<DataPoint> parseDataString(String dataString) {
        List<DataPoint> dataPoints = new ArrayList<>();
        
        if (dataString == null || dataString.trim().isEmpty()) {
            return dataPoints;
        }
        
        // Remove brackets and split by comma
        String cleanData = dataString.replaceAll("[\\[\\]]", "").trim();
        Matcher matcher = DATA_PATTERN.matcher(cleanData);
        
        while (matcher.find()) {
            try {
                String dateTimeStr = matcher.group(1);
                double value = Double.parseDouble(matcher.group(2));
                
                LocalDateTime dateTime = LocalDateTime.parse(dateTimeStr, DATE_FORMATTER);
                dataPoints.add(new DataPoint(dateTime, value));
                
            } catch (Exception e) {
                // Skip invalid entries
                continue;
            }
        }
        
        // Sort by timestamp
        dataPoints.sort(Comparator.comparing(DataPoint::getTimestamp));
        
        return dataPoints;
    }
    
    /**
     * Combine OI, LTP, and Volume data points by timestamp
     */
    private List<CombinedDataPoint> combineDataPoints(List<DataPoint> oiPoints, 
                                                     List<DataPoint> ltpPoints, 
                                                     List<DataPoint> volumePoints) {
        
        Map<LocalDateTime, CombinedDataPoint> combinedMap = new HashMap<>();
        
        // Process OI data
        for (DataPoint oi : oiPoints) {
            combinedMap.computeIfAbsent(oi.getTimestamp(), 
                k -> new CombinedDataPoint(k)).setOi(oi.getValue());
        }
        
        // Process LTP data
        for (DataPoint ltp : ltpPoints) {
            CombinedDataPoint combined = combinedMap.get(ltp.getTimestamp());
            if (combined != null) {
                combined.setLtp(ltp.getValue());
            }
        }
        
        // Process Volume data
        for (DataPoint volume : volumePoints) {
            CombinedDataPoint combined = combinedMap.get(volume.getTimestamp());
            if (combined != null) {
                combined.setVolume(volume.getValue());
            }
        }
        
        // Filter complete data points and sort
        return combinedMap.values().stream()
            .filter(cdp -> cdp.isComplete())
            .sorted(Comparator.comparing(CombinedDataPoint::getTimestamp))
            .collect(Collectors.toList());
    }
    

    
    /**
     * Calculate various metrics for signal generation
     */
    private SignalMetrics calculateMetrics(List<CombinedDataPoint> data) {
        if (data.size() < 2) {
            return new SignalMetrics();
        }
        
        CombinedDataPoint current = data.get(data.size() - 1);
        CombinedDataPoint first = data.get(0);
        
        // Calculate percentage changes
        double oiChangePercent = calculatePercentageChange(first.getOi(), current.getOi());
        double priceChangePercent = calculatePercentageChange(first.getLtp(), current.getLtp());
        
        // Calculate volume metrics
        double avgVolume = data.stream()
            .mapToDouble(CombinedDataPoint::getVolume)
            .average().orElse(0.0);
        
        double volumeRatio = current.getVolume() / (avgVolume > 0 ? avgVolume : 1);
        boolean volumeSpike = volumeRatio >= VOLUME_SPIKE_MULTIPLIER;
        
        // Calculate momentum
        double momentum = calculateMomentum(data);
        
        // Calculate volatility
        double volatility = calculateVolatility(data);
        
        return SignalMetrics.builder()
            .oiChangePercent(oiChangePercent)
            .priceChangePercent(priceChangePercent)
            .volumeRatio(volumeRatio)
            .volumeSpike(volumeSpike)
            .momentum(momentum)
            .volatility(volatility)
            .avgVolume(avgVolume)
            .currentOI(current.getOi())
            .currentLTP(current.getLtp())
            .currentVolume(current.getVolume())
            .build();
    }
    
    /**
     * Generate CALL signal
     */
    private Signal generateCallSignal(SignalMetrics metrics, CombinedDataPoint current, CombinedDataPoint previous) {
        String signal = "HOLD";
        double confidence = 50.0;
        List<String> reasons = new ArrayList<>();
        
        // Strong Bullish - CALL BUY signals
        if (metrics.getPriceChangePercent() > PRICE_CHANGE_THRESHOLD && 
            metrics.getOiChangePercent() > SIGNIFICANT_OI_CHANGE && 
            metrics.isVolumeSpike()) {
            
            signal = "STRONG_BUY";
            confidence = 85.0;
            reasons.add("Price rising with significant OI buildup and volume spike");
            
        } else if (metrics.getPriceChangePercent() > PRICE_CHANGE_THRESHOLD && 
                   metrics.getOiChangePercent() > OI_CHANGE_THRESHOLD) {
            
            signal = "BUY";
            confidence = 70.0;
            reasons.add("Price rising with OI increase indicates bullish sentiment");
            
        } else if (metrics.getMomentum() > 0.5 && metrics.getVolumeRatio() > 1.2) {
            
            signal = "BUY";
            confidence = 65.0;
            reasons.add("Positive momentum with above average volume");
        }
        
        // Bearish - CALL SELL signals
        else if (metrics.getPriceChangePercent() < -PRICE_CHANGE_THRESHOLD && 
                 metrics.getOiChangePercent() > SIGNIFICANT_OI_CHANGE && 
                 metrics.isVolumeSpike()) {
            
            signal = "STRONG_SELL";
            confidence = 85.0;
            reasons.add("Price falling with OI buildup and volume spike - bearish for calls");
            
        } else if (metrics.getPriceChangePercent() < -PRICE_CHANGE_THRESHOLD && 
                   metrics.getOiChangePercent() < -OI_CHANGE_THRESHOLD) {
            
            signal = "SELL";
            confidence = 70.0;
            reasons.add("Price and OI both declining - call unwinding");
        }
        
        // Adjust confidence based on volatility
        if (metrics.getVolatility() > 2.0) {
            confidence = Math.max(confidence - 10, 40);
            reasons.add("High volatility reduces signal confidence");
        }
        
        return new Signal(signal, confidence, reasons, "CALL");
    }
    
    /**
     * Generate PUT signal
     */
    private Signal generatePutSignal(SignalMetrics metrics, CombinedDataPoint current, CombinedDataPoint previous) {
        String signal = "HOLD";
        double confidence = 50.0;
        List<String> reasons = new ArrayList<>();
        
        // Strong Bearish - PUT BUY signals
        if (metrics.getPriceChangePercent() < -PRICE_CHANGE_THRESHOLD && 
            metrics.getOiChangePercent() > SIGNIFICANT_OI_CHANGE && 
            metrics.isVolumeSpike()) {
            
            signal = "STRONG_BUY";
            confidence = 85.0;
            reasons.add("Price falling with significant OI buildup and volume spike");
            
        } else if (metrics.getPriceChangePercent() < -PRICE_CHANGE_THRESHOLD && 
                   metrics.getOiChangePercent() > OI_CHANGE_THRESHOLD) {
            
            signal = "BUY";
            confidence = 70.0;
            reasons.add("Price falling with OI increase indicates bearish sentiment");
            
        } else if (metrics.getMomentum() < -0.5 && metrics.getVolumeRatio() > 1.2) {
            
            signal = "BUY";
            confidence = 65.0;
            reasons.add("Negative momentum with above average volume");
        }
        
        // Bullish - PUT SELL signals
        else if (metrics.getPriceChangePercent() > PRICE_CHANGE_THRESHOLD && 
                 metrics.getOiChangePercent() > SIGNIFICANT_OI_CHANGE && 
                 metrics.isVolumeSpike()) {
            
            signal = "STRONG_SELL";
            confidence = 85.0;
            reasons.add("Price rising with OI buildup and volume spike - bearish for puts");
            
        } else if (metrics.getPriceChangePercent() > PRICE_CHANGE_THRESHOLD && 
                   metrics.getOiChangePercent() < -OI_CHANGE_THRESHOLD) {
            
            signal = "SELL";
            confidence = 70.0;
            reasons.add("Price rising with OI decline - put unwinding");
        }
        
        // Adjust confidence based on volatility
        if (metrics.getVolatility() > 2.0) {
            confidence = Math.max(confidence - 10, 40);
            reasons.add("High volatility reduces signal confidence");
        }
        
        return new Signal(signal, confidence, reasons, "PUT");
    }
    
    // Helper methods
    private double calculatePercentageChange(double oldValue, double newValue) {
        if (oldValue == 0) return 0;
        return ((newValue - oldValue) / oldValue) * 100;
    }
    
    private double calculateMomentum(List<CombinedDataPoint> data) {
        if (data.size() < 3) return 0;
        
        int size = data.size();
        double recent = data.subList(size - 3, size).stream()
            .mapToDouble(CombinedDataPoint::getLtp)
            .average().orElse(0);
        
        double earlier = data.subList(0, 3).stream()
            .mapToDouble(CombinedDataPoint::getLtp)
            .average().orElse(0);
        
        return earlier > 0 ? ((recent - earlier) / earlier) * 100 : 0;
    }
    
    private double calculateVolatility(List<CombinedDataPoint> data) {
        if (data.size() < 2) return 0;
        
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < data.size(); i++) {
            double prevPrice = data.get(i - 1).getLtp();
            double currPrice = data.get(i).getLtp();
            if (prevPrice > 0) {
                returns.add((currPrice - prevPrice) / prevPrice);
            }
        }
        
        if (returns.isEmpty()) return 0;
        
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = returns.stream()
            .mapToDouble(r -> Math.pow(r - mean, 2))
            .average().orElse(0);
        
        return Math.sqrt(variance) * 100;
    }
    
    // Helper methods for creating default and error signals
    private Signal createDefaultCallSignal(String reason) {
        return new Signal("HOLD", 50.0, Arrays.asList(reason), "CALL");
    }
    
    private Signal createDefaultPutSignal(String reason) {
        return new Signal("HOLD", 50.0, Arrays.asList(reason), "PUT");
    }
    
    private Signal createErrorCallSignal(String error) {
        return new Signal("ERROR", 0.0, Arrays.asList(error), "CALL");
    }
    
    private Signal createErrorPutSignal(String error) {
        return new Signal("ERROR", 0.0, Arrays.asList(error), "PUT");
    }
    
    // Data classes
    public static class DataPoint {
        private LocalDateTime timestamp;
        private double value;
        
        public DataPoint(LocalDateTime timestamp, double value) {
            this.timestamp = timestamp;
            this.value = value;
        }
        
        // Getters and setters
        public LocalDateTime getTimestamp() { return timestamp; }
        public double getValue() { return value; }
    }
    
    public static class CombinedDataPoint {
        private LocalDateTime timestamp;
        private Double oi;
        private Double ltp;
        private Double volume;
        
        public CombinedDataPoint(LocalDateTime timestamp) {
            this.timestamp = timestamp;
        }
        
        public boolean isComplete() {
            return oi != null && ltp != null && volume != null;
        }
        
        // Getters and setters
        public LocalDateTime getTimestamp() { return timestamp; }
        public Double getOi() { return oi; }
        public void setOi(Double oi) { this.oi = oi; }
        public Double getLtp() { return ltp; }
        public void setLtp(Double ltp) { this.ltp = ltp; }
        public Double getVolume() { return volume; }
        public void setVolume(Double volume) { this.volume = volume; }
    }
    
    public static class SignalMetrics {
        private double oiChangePercent;
        private double priceChangePercent;
        private double volumeRatio;
        private boolean volumeSpike;
        private double momentum;
        private double volatility;
        private double avgVolume;
        private double currentOI;
        private double currentLTP;
        private double currentVolume;
        
        public static SignalMetricsBuilder builder() {
            return new SignalMetricsBuilder();
        }
        
        // Getters and setters
        public double getOiChangePercent() { return oiChangePercent; }
        public double getPriceChangePercent() { return priceChangePercent; }
        public double getVolumeRatio() { return volumeRatio; }
        public boolean isVolumeSpike() { return volumeSpike; }
        public double getMomentum() { return momentum; }
        public double getVolatility() { return volatility; }
        public double getAvgVolume() { return avgVolume; }
        public double getCurrentOI() { return currentOI; }
        public double getCurrentLTP() { return currentLTP; }
        public double getCurrentVolume() { return currentVolume; }
        
        public static class SignalMetricsBuilder {
            private SignalMetrics metrics = new SignalMetrics();
            
            public SignalMetricsBuilder oiChangePercent(double val) { metrics.oiChangePercent = val; return this; }
            public SignalMetricsBuilder priceChangePercent(double val) { metrics.priceChangePercent = val; return this; }
            public SignalMetricsBuilder volumeRatio(double val) { metrics.volumeRatio = val; return this; }
            public SignalMetricsBuilder volumeSpike(boolean val) { metrics.volumeSpike = val; return this; }
            public SignalMetricsBuilder momentum(double val) { metrics.momentum = val; return this; }
            public SignalMetricsBuilder volatility(double val) { metrics.volatility = val; return this; }
            public SignalMetricsBuilder avgVolume(double val) { metrics.avgVolume = val; return this; }
            public SignalMetricsBuilder currentOI(double val) { metrics.currentOI = val; return this; }
            public SignalMetricsBuilder currentLTP(double val) { metrics.currentLTP = val; return this; }
            public SignalMetricsBuilder currentVolume(double val) { metrics.currentVolume = val; return this; }
            
            public SignalMetrics build() { return metrics; }
        }
    }
    
    public static class Signal {
        private String signal;
        private double confidence;
        private List<String> reasons;
        private String type;
        
        public Signal(String signal, double confidence, List<String> reasons, String type) {
            this.signal = signal;
            this.confidence = confidence;
            this.reasons = reasons;
            this.type = type;
        }
        
        // Getters
        public String getSignal() { return signal; }
        public double getConfidence() { return confidence; }
        public List<String> getReasons() { return reasons; }
        public String getType() { return type; }
    }
    
    
    // Helper analysis class
    public static class SignalAnalysis {
        private SignalMetrics metrics;
        private CombinedDataPoint current;
        private CombinedDataPoint previous;
        
        public SignalAnalysis(SignalMetrics metrics, CombinedDataPoint current, CombinedDataPoint previous) {
            this.metrics = metrics;
            this.current = current;
            this.previous = previous;
        }
        
        public SignalMetrics getMetrics() { return metrics; }
        public CombinedDataPoint getCurrent() { return current; }
        public CombinedDataPoint getPrevious() { return previous; }
    }
    
    public static class TradingSignalResult {
        private Signal callSignal;
        private Signal putSignal;
        private SignalMetrics metrics;
        
        public TradingSignalResult(Signal callSignal, Signal putSignal, SignalMetrics metrics) {
            this.callSignal = callSignal;
            this.putSignal = putSignal;
            this.metrics = metrics;
        }
        
        // Getters
        public Signal getCallSignal() { return callSignal; }
        public Signal getPutSignal() { return putSignal; }
        public SignalMetrics getMetrics() { return metrics; }
    }
}
