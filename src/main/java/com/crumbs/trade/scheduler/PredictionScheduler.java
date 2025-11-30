package com.crumbs.trade.scheduler;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.entity.PredictionHistory;
import com.crumbs.trade.repo.PredictionHistoryRepo;
import com.crumbs.trade.service.PredictionService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PredictionScheduler {

    private static final Logger log = LogManager.getLogger(PredictionScheduler.class);

    private final PredictionService predictionService;
    private final PredictionHistoryRepo historyRepo;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // --------------------------------------------------------
    // Runs ONLY 15-min intervals within market hours (Mon–Fri):
    //
    // 09:15, 09:30, 09:45
    // 10:00, 10:15, 10:30, 10:45
    // 11:00, 11:15, 11:30, 11:45
    // ...
    // 15:00, 15:15, 15:30
    //
    // NO wake ups outside above times.
    // --------------------------------------------------------

    // 09:15, 09:30, 09:45
    @Scheduled(cron = "0 15,30,45 9 * * MON-FRI", zone = "Asia/Kolkata")
    // 10:00–14:45 (every 15 mins)
    @Scheduled(cron = "0 0,15,30,45 10-14 * * MON-FRI", zone = "Asia/Kolkata")
    // 15:00, 15:15, 15:30
    @Scheduled(cron = "0 0,15,30 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runMarketPrediction() throws SmartAPIException {

        LocalDateTime now = LocalDateTime.now(IST);
        log.info("▶ Running scheduled prediction at {}", now);

        try {
            // Use advanced version with confidence & sentiment
            PredictionService.AdvancedPredictionResult result =
                    predictionService.predictNiftyAdvanced();

            PredictionHistory hist = new PredictionHistory();
            hist.setTimestamp(now);
            hist.setCurrentPrice(result.currentPrice);
            hist.setPredictedPrice(result.predictedPrice);
            hist.setDifference(result.difference);
            hist.setPercentageMove(result.percentageMove);
            hist.setValidStocks(result.validStocks);
            hist.setTotalStocks(result.totalStocks);
            hist.setConfidenceScore(new BigDecimal(result.confidenceScore));
            hist.setSentiment(result.sentiment);
            hist.setNotes("Scheduled 15-min market run");

            historyRepo.save(hist);

            log.info("✔ Prediction saved at {} → Predicted: {}", now, result.predictedPrice);

        } catch (Exception ex) {
            log.error("❌ Error executing scheduled market prediction: {}", ex.getMessage(), ex);
        }
    }
}
