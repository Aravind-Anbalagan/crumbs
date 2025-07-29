package com.crumbs.trade.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.crumbs.trade.dto.CombinedSignalResult;
import com.crumbs.trade.entity.Indicator;
import com.crumbs.trade.repo.IndicatorRepo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CombinedSignalService {

	private final IndicatorRepo indicatorRepo;

	public static CombinedSignalResult generateCombinedSignal(Indicator indicator) {
		int buyVotes = 0, sellVotes = 0, holdVotes = 0;
		List<String> voteSummary = new ArrayList<>();
		List<String> confidences = new ArrayList<>();
		List<String> detailedReasons = new ArrayList<>();

		Map<String, String> signalMap = new HashMap<>();
		if (indicator.getDaily_aiSignal() != null)
			signalMap.put("DAILY_AI", indicator.getDaily_aiSignal());
		if (indicator.getDaily_fibo_signal() != null)
			signalMap.put("DAILY_FIBO", indicator.getDaily_fibo_signal());
		if (indicator.getDaily_sr_signal() != null)
			signalMap.put("DAILY_SR", indicator.getDaily_sr_signal());
		if (indicator.getPsarFlagDay() != null)
			signalMap.put("DAILY_PSAR", indicator.getPsarFlagDay());
		if (indicator.getHeikinAshiDay() != null)
			signalMap.put("DAILY_HEIKIN", indicator.getHeikinAshiDay());
		if (indicator.getWeekly_aiSignal() != null)
			signalMap.put("WEEKLY_AI", indicator.getWeekly_aiSignal());
		if (indicator.getWeekly_fibo_signal() != null)
			signalMap.put("WEEKLY_FIBO", indicator.getWeekly_fibo_signal());
		if (indicator.getWeekly_sr_signal() != null)
			signalMap.put("WEEKLY_SR", indicator.getWeekly_sr_signal());
		if (indicator.getPsarFlagWeekly() != null)
			signalMap.put("WEEKLY_PSAR", indicator.getPsarFlagWeekly());
		if (indicator.getHeikinAshiWeekly() != null)
			signalMap.put("WEEKLY_HEIKIN", indicator.getHeikinAshiWeekly());

		Map<String, String> confidenceMap = new HashMap<>();
		if (indicator.getDaily_aiConfidence() != null)
			confidenceMap.put("DAILY_AI", indicator.getDaily_aiConfidence());
		if (indicator.getDaily_fibo_confidence() != null)
			confidenceMap.put("DAILY_FIBO", indicator.getDaily_fibo_confidence());
		if (indicator.getDaily_sr_confidence() != null)
			confidenceMap.put("DAILY_SR", indicator.getDaily_sr_confidence());
		if (indicator.getWeekly_aiConfidence() != null)
			confidenceMap.put("WEEKLY_AI", indicator.getWeekly_aiConfidence());
		if (indicator.getWeekly_fibo_confidence() != null)
			confidenceMap.put("WEEKLY_FIBO", indicator.getWeekly_fibo_confidence());
		if (indicator.getWeekly_sr_confidence() != null)
			confidenceMap.put("WEEKLY_SR", indicator.getWeekly_sr_confidence());

		Map<String, String> reasonMap = new HashMap<>();
		if (indicator.getDaily_aiReason() != null)
			reasonMap.put("DAILY_AI", indicator.getDaily_aiReason());
		if (indicator.getDaily_fibo_reason() != null)
			reasonMap.put("DAILY_FIBO", indicator.getDaily_fibo_reason());
		if (indicator.getDaily_sr_reason() != null)
			reasonMap.put("DAILY_SR", indicator.getDaily_sr_reason());
		if (indicator.getWeekly_aiReason() != null)
			reasonMap.put("WEEKLY_AI", indicator.getWeekly_aiReason());
		if (indicator.getWeekly_fibo_reason() != null)
			reasonMap.put("WEEKLY_FIBO", indicator.getWeekly_fibo_reason());
		if (indicator.getWeekly_sr_reason() != null)
			reasonMap.put("WEEKLY_SR", indicator.getWeekly_sr_reason());

		for (Map.Entry<String, String> entry : signalMap.entrySet()) {
			String source = entry.getKey();
			String signal = entry.getValue();
			if (signal == null || signal.trim().isEmpty())
				continue;

			switch (signal.trim().toUpperCase()) {
			case "BUY" -> {
				buyVotes++;
				voteSummary.add(source + " → BUY");
			}
			case "SELL" -> {
				sellVotes++;
				voteSummary.add(source + " → SELL");
			}
			case "HOLD" -> {
				holdVotes++;
				voteSummary.add(source + " → HOLD");
			}
			}
		}

		if (buyVotes == 0 && sellVotes == 0 && holdVotes == 0)
			return null;

		String combineSignal = (buyVotes >= 3) ? "BUY" : (sellVotes >= 3) ? "SELL" : "HOLD";

		confidenceMap.values().stream().filter(Objects::nonNull).map(String::toUpperCase).forEach(confidences::add);

		String combineConfidence = calculateConfidenceLevel(confidences);

		for (Map.Entry<String, String> entry : reasonMap.entrySet()) {
			String src = entry.getKey();
			String text = entry.getValue();
			if (text != null && !text.isBlank()) {
				detailedReasons.add("🔍 " + src + ": " + text);
			}
		}

		CombinedSignalResult result = new CombinedSignalResult();
		result.setCombineSignal(combineSignal);
		result.setCombineConfidence(combineConfidence);
		result.setCombineReasonSummary(String.join("; ", voteSummary));
		result.setCombineDetailedReason(String.join("\n", detailedReasons));
		result.setCombineBuyVotes(buyVotes);
		result.setCombineSellVotes(sellVotes);
		result.setCombineHoldVotes(holdVotes);

		return result;
	}

	private static String calculateConfidenceLevel(List<String> confidences) {
		long high = confidences.stream().filter(c -> c.equals("HIGH")).count();
		long medium = confidences.stream().filter(c -> c.equals("MEDIUM")).count();

		if (high >= 3)
			return "HIGH";
		if (medium >= 3 || (high >= 1 && medium >= 1))
			return "MEDIUM";
		return "LOW";
	}

	public Indicator updateCombinedSignal(Indicator indicator) {
		CombinedSignalResult result = generateCombinedSignal(indicator);
		if (result != null) {
			applyResultToIndicator(indicator, result);
		}
		return indicator;
	}

	public List<Indicator> updateCombinedSignals(List<Indicator> indicators) {
		List<Indicator> toSave = new ArrayList<>();
		for (Indicator indicator : indicators) {
			CombinedSignalResult result = generateCombinedSignal(indicator);
			if (result != null) {
				applyResultToIndicator(indicator, result);
				toSave.add(indicator);
			}
		}
		return indicatorRepo.saveAll(toSave);
	}

	private void applyResultToIndicator(Indicator indicator, CombinedSignalResult result) {
		indicator.setCombineSignal(result.getCombineSignal());
		indicator.setCombineConfidence(result.getCombineConfidence());
		indicator.setCombineReasonSummary(result.getCombineReasonSummary());
		indicator.setCombineDetailedReason(result.getCombineDetailedReason());
		indicator.setCombineBuyVotes(result.getCombineBuyVotes());
		indicator.setCombineSellVotes(result.getCombineSellVotes());
		indicator.setCombineHoldVotes(result.getCombineHoldVotes());
	}
}
