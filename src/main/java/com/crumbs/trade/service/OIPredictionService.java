package com.crumbs.trade.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;



@Service
public class OIPredictionService {

    public String getOISignal(List<String> inputTrends) {
        // Trend sequence represented as a List of Strings
     

        // Convert List<String> to numerical trends for analysis
        List<Integer> trends = new ArrayList<>();
        
        for (String trend : inputTrends) {
            switch (trend) {
                case "No significant trend change":
                    trends.add(0);
                    break;
                case "Trend changed to Up":
                case "Continuing Up":
                    trends.add(1);
                    break;
                case "Trend changed to Down":
                case "Continuing Down":
                    trends.add(-1);
                    break;
            }
        }

        // Applying the 5-period moving average
        int movingAverageWindow = 3;
        List<Integer> smoothedTrends = applyMovingAverage(trends, movingAverageWindow);

		// Predict the next trend based on the last 5 periods
		if (smoothedTrends.size() > 1) {
			int predictedTrend = smoothedTrends.get(smoothedTrends.size() - 1);

			// Display the results
			// System.out.println("Smoothed Trend Values: " + smoothedTrends);
			// System.out.println("Predicted next trend: " + (predictedTrend == 1 ? "Up" :
			// (predictedTrend == -1 ? "Down" : "No significant change")));
			return (predictedTrend == 1 ? "Up" : (predictedTrend == -1 ? "Down" : "No significant change"));
		} else {
			return null;
		}
       
    }

    // Method to apply moving average
    public static List<Integer> applyMovingAverage(List<Integer> trends, int windowSize) {
        List<Integer> smoothedTrends = new ArrayList<>();
        
        // Apply moving average to each window of trend values
        for (int i = 0; i <= trends.size() - windowSize; i++) {
            int sum = 0;
            for (int j = i; j < i + windowSize; j++) {
                sum += trends.get(j);
            }
            smoothedTrends.add(sum / windowSize);
        }

        return smoothedTrends;
    }
}
