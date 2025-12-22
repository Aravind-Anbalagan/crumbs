package com.crumbs.trade.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.entity.PredictionHistory;
import com.crumbs.trade.service.PredictionService;
import com.crumbs.trade.service.PredictionService.PredictionResult;
import com.crumbs.trade.service.PredictionService.AdvancedPredictionResult;
import lombok.Data;
import java.util.*;

@RestController
@RequestMapping("/api/prediction")
public class PredictionController {
    
    @Autowired
    private PredictionService predictionService;
    
    @GetMapping("/nifty/basic")
    public ResponseEntity<BasicPredictionResponse> getBasicPrediction() throws SmartAPIException {
        PredictionResult result = predictionService.predictNifty();
        
        BasicPredictionResponse response = new BasicPredictionResponse();
        response.setCurrentPrice(result.currentPrice);
        response.setPredictedPrice(result.predictedPrice);
        response.setDifference(result.difference);
        response.setPercentageMove(result.percentageMove);
        response.setValidStocks(result.validStocks);
        response.setTotalStocks(result.totalStocks);
        response.setTimestamp(new Date());
        
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/nifty/advanced")
    public ResponseEntity<AdvancedPredictionResponse> getAdvancedPrediction(
            @RequestParam(required = false) String days
    ) throws SmartAPIException {

        AdvancedPredictionResult result =
                predictionService.predictNiftyAdvanced(days);

        AdvancedPredictionResponse response = new AdvancedPredictionResponse();
        response.setCurrentPrice(result.currentPrice);
        response.setPredictedPrice(result.predictedPrice);
        response.setDifference(result.difference);
        response.setPercentageMove(result.percentageMove);
        response.setValidStocks(result.validStocks);
        response.setTotalStocks(result.totalStocks);
        response.setConfidenceScore(result.confidenceScore);
        response.setSentiment(result.sentiment);
        response.setTimestamp(new Date());
        response.setPredictionList(result.getPredictionList());
        response.setInterpretation(generateInterpretation(result));

        return ResponseEntity.ok(response);
    }

    
    private String generateInterpretation(AdvancedPredictionResult result) {
        StringBuilder sb = new StringBuilder();
        
        // Coverage
        double coverage = (double) result.validStocks / result.totalStocks * 100;
        sb.append(String.format("Analysis based on %d out of %d Nifty 50 stocks (%.1f%% coverage). ",
            result.validStocks, result.totalStocks, coverage));
        
        // Movement
        if (result.difference.abs().doubleValue() < 10) {
            sb.append("Market showing minimal movement. ");
        } else if (result.difference.doubleValue() > 0) {
            sb.append(String.format("Predicted upward movement of %.2f points. ", 
                result.difference.doubleValue()));
        } else {
            sb.append(String.format("Predicted downward movement of %.2f points. ", 
                Math.abs(result.difference.doubleValue())));
        }
        
        // Confidence
        if (result.confidenceScore >= 75) {
            sb.append("High confidence prediction. ");
        } else if (result.confidenceScore >= 50) {
            sb.append("Moderate confidence prediction. ");
        } else {
            sb.append("Low confidence - mixed signals from constituent stocks. ");
        }
        
        // Sentiment
        sb.append(String.format("Overall market sentiment: %s.", result.sentiment));
        
        return sb.toString();
    }
    
    @Data
    public static class BasicPredictionResponse {
        private Object currentPrice;
        private Object predictedPrice;
        private Object difference;
        private Object percentageMove;
        private int validStocks;
        private int totalStocks;
        private Date timestamp;
    }
    
    @Data
    public static class AdvancedPredictionResponse extends BasicPredictionResponse {
        private double confidenceScore;
        private String sentiment;
        private String interpretation;
        private List<PredictionHistory> predictionList = new ArrayList<>();
    }
    
}
