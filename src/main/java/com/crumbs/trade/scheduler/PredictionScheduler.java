package com.crumbs.trade.scheduler;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.entity.PredictionHistory;
import com.crumbs.trade.repo.PredictionHistoryRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.PredictionService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PredictionScheduler {

    private static final Logger log = LogManager.getLogger(PredictionScheduler.class);

    private final PredictionService predictionService;
    private final PredictionHistoryRepo historyRepo;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    
    @Autowired StrategyRepo strategyRepo;

	// --------------------------------------------------------
	// Runs EVERY 5 minutes within market hours (Mon–Fri):
	//
	// 09:15, 09:20, 09:25, 09:30, ..., 09:55
	// 10:00, 10:05, ..., 14:55
	// 15:00, 15:05, 15:10, 15:15, 15:20, 15:25, 15:30
	//
	// NO wake ups outside market hours.
	// --------------------------------------------------------

	// 09:15 – 09:55 (every 5 mins)
	//@Scheduled(cron = "0 15-59/5 9 * * MON-FRI", zone = "Asia/Kolkata")

	// 10:00 – 14:55 (every 5 mins)
	//@Scheduled(cron = "0 */5 10-14 * * MON-FRI", zone = "Asia/Kolkata")

	// 15:00 – 15:30 (every 5 mins)
	//@Scheduled(cron = "0 0-30/5 15 * * MON-FRI", zone = "Asia/Kolkata")
	public void runMarketPrediction() throws SmartAPIException {

		if ("Y".equalsIgnoreCase(strategyRepo.findByName("WEIGHTAGE").getActive())) {

			LocalDateTime now = LocalDateTime.now(IST);
			log.info("▶ Running scheduled prediction at {}", now);

			try {
				// Use advanced version with confidence & sentiment
				PredictionService.AdvancedPredictionResult result = predictionService.predictNiftyAdvanced(null);

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
}
