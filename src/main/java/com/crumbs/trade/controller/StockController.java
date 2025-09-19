package com.crumbs.trade.controller;

import java.io.IOException;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.DynamicIndicatorDTO;
import com.crumbs.trade.entity.Indicator;
import com.crumbs.trade.entity.Result;
import com.crumbs.trade.exception.NoIndicatorDataException;
import com.crumbs.trade.repo.IndicatorRepo;
import com.crumbs.trade.repo.ResultRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.ResultService;
import com.crumbs.trade.service.TaskService;
import com.crumbs.trade.service.TimeLookup;
import com.crumbs.trade.utility.AppConstant;

@RestController
@RequestMapping("/api")
public class StockController {
	@Autowired
	AngelOne angelOne;

	@Autowired
	TaskService taskService;

	@Autowired
	TimeLookup timeLookUp;

	@Autowired
	IndicatorRepo indicatorRepo;

	@Autowired
	StrategyRepo strategyRepo;

	@Autowired
	ResultService resultService;

	@Autowired
	ResultRepo resultRepo;

	@GetMapping("/getStocks/{indexName}/{symbol}")
	public String indicator(@PathVariable("indexName") String indexName, @PathVariable("symbol") String symbol)
			throws InterruptedException, URISyntaxException, IOException, SmartAPIException, ParseException {
		Instant start = timeLookUp.getStartTime();

		taskService.getSupportAndResistance(indexName, symbol);

		timeLookUp.getEndTime(start);
		return "Completed";
	}

	/*
	 * STEP 1 & 2 & 3 Trigger for find 5 days Avg volume and execute day candle
	 */
	@Scheduled(cron = "0 0 22 * * ?") // Works
	public String getStocks() throws SmartAPIException, Exception {
		if (strategyRepo.findByName("STOCK").getActive().equals("Y")) {
			taskService.getSupportAndResistance("ALL", "ALL");
			return "Completed";
		}
		return "STOCK Strategy Disabled";
	}

	/*
	 * 
	 */
	@GetMapping("/findStocks")
	@Scheduled(cron = "0 35 09 * * ?") // Works
	public String findBullishStocks() throws SmartAPIException, Exception {
		if (strategyRepo.findByName("STOCK").getActive().equals("Y")) {
			taskService.findBullishStocks();
			taskService.callAI();
			return "Completed";
		}
		return "STOCK Strategy Disabled";
	}

	@GetMapping("/stocksResult")
	@Scheduled(cron = "0 35 15 * * ?") // Works
	public String getResult() throws SmartAPIException, Exception {
		if (strategyRepo.findByName("STOCK").getActive().equals("Y")) {
			taskService.getResult();
			resultService.getAllResults();
			return "Completed";
		}
		return "STOCK Strategy Disabled";
	}

	@GetMapping("/indicators/flagged")
	public List<DynamicIndicatorDTO> getIndicatorData(@RequestParam(defaultValue = "DAILY") String flag,
			@RequestParam(defaultValue = "ALL") String heikinPsarFilter,
			@RequestParam(defaultValue = "true") boolean optionFlag) {

		List<Indicator> indicators = getIndicators(flag, heikinPsarFilter);

		// Apply filtering only if optionFlag = true
		if (optionFlag) {
			indicators = indicators.stream().filter(ind -> "Y".equalsIgnoreCase(ind.getOptions())).toList();
		}
		if (indicators.isEmpty()) {
			throw new NoIndicatorDataException("No stocks matched your selected filters.");
		}

		return indicators.stream().map(ind -> toDTO(ind, flag)).toList();
	}

	private DynamicIndicatorDTO toDTO(Indicator ind, String flag) {
		DynamicIndicatorDTO dto = new DynamicIndicatorDTO();

		addCommonHeaders(dto, ind, flag);

		return dto;
	}

	private void addCommonHeaders(DynamicIndicatorDTO dto, Indicator ind, String flag) {
		dto.addHeader("name", ind.getName(), true);
		dto.addHeader("price", ind.getCurrentPrice(), true);
		dto.addHeader("exchange", ind.getExchange(), false);
		dto.addHeader("symbol", ind.getTradingSymbol(), false);
		if ("DAILY".equalsIgnoreCase(flag)) {
			dto.addHeader("RSI", ind.getDailyRSI(), true);
			dto.addHeader("20MA", ind.getMovingavg20Flag(), true);
			dto.addHeader("50MA", ind.getMovingavg50Flag(), true);
			dto.addHeader("200MA", ind.getMovingavg200Flag(), true);
			dto.addHeader("VOLUME", ind.getVolumeFlag(), true);
			dto.addHeader("PIVOT_LEVEL", ind.getPivotFlag(), true);
			// dto.addHeader("daily_srtrend", ind.getDaily_sr_trend(), true);
			dto.addHeader("SR_SIGNAL", ind.getDaily_sr_signal(), true);
			// dto.addHeader("daily_srconfidence", ind.getDaily_sr_confidence(), true);
			dto.addHeader("SR_REASON", ind.getDaily_sr_reason(), false);
			// dto.addHeader("daily_fibotrend", ind.getDaily_fibo_trend(), true);
			dto.addHeader("FIBO_SIGNAL", ind.getDaily_fibo_signal(), true);
			// dto.addHeader("daily_fiboconfidence", ind.getDaily_fibo_confidence(), true);
			dto.addHeader("FIBO_REASON", ind.getDaily_fibo_reason(), false);
			dto.addHeader("AI_SIGNAL", ind.getDaily_aiSignal(), true);
			dto.addHeader("AI_REASON", ind.getDaily_aiReason(), false);
			dto.addHeader("INTRADAY", ind.getIntraday(), true);
		}

		if ("WEEKLY".equalsIgnoreCase(flag)) {
			dto.addHeader("RSI", ind.getWeeklyRSI(), true);
			dto.addHeader("20MA", ind.getMovingavg20Flag(), true);
			dto.addHeader("50MA", ind.getMovingavg50Flag(), true);
			dto.addHeader("200MA", ind.getMovingavg200Flag(), true);
			dto.addHeader("VOLUME", ind.getVolumeFlag(), true);
			dto.addHeader("PIVOT_LEVEL", ind.getPivotFlag(), true);
			// dto.addHeader("weekly_srtrend", ind.getWeekly_sr_trend(), true);
			dto.addHeader("SR_SIGNAL", ind.getWeekly_sr_signal(), true);
			// dto.addHeader("weekly_srconfidence", ind.getWeekly_sr_confidence(), true);
			dto.addHeader("SR_REASON", ind.getWeekly_sr_reason(), false);
			// dto.addHeader("weekly_fibotrend", ind.getWeekly_fibo_trend(), true);
			dto.addHeader("FIBO_SIGNAL", ind.getWeekly_fibo_signal(), true);
			// dto.addHeader("weekly_fiboconfidence", ind.getWeekly_fibo_confidence(),
			// true);
			dto.addHeader("FIBO_REASON", ind.getWeekly_fibo_reason(), false);
			dto.addHeader("AI_SIGNAL", ind.getWeekly_aiSignal(), true);
			dto.addHeader("AI_REASON", ind.getWeekly_aiReason(), false);
		}

		if ("COMBINE".equalsIgnoreCase(flag)) {
			dto.addHeader("D_VOLUME", ind.getVolumeFlag(), true);
			dto.addHeader("D_SR_SIGNAL", ind.getDaily_sr_signal(), true);
			dto.addHeader("D_FIBO_SIGNAL", ind.getDaily_fibo_signal(), true);
			dto.addHeader("D_AI_SIGNAL", ind.getDaily_aiSignal(), true);
			dto.addHeader("W_VOLUME", ind.getVolumeFlag(), true);
			dto.addHeader("W_SR_SIGNAL", ind.getWeekly_sr_signal(), true);
			dto.addHeader("W_FIBO_SIGNAL", ind.getWeekly_fibo_signal(), true);
			dto.addHeader("W_AI_SIGNAL", ind.getWeekly_aiSignal(), true);

			dto.addHeader("COMBINE_SGNAL", ind.getCombineSignal(), true);
			// dto.addHeader("combineConfidence", ind.getCombineConfidence(), true);
			// dto.addHeader("combineReasonSummary", ind.getCombineReasonSummary() , true);
			// dto.addHeader("combineDetailedReason", ind.getCombineDetailedReason(), true);
			// dto.addHeader("combineBuyVotes", ind.getCombineBuyVotes(), true);
			// dto.addHeader("combineSellVotes", ind.getCombineSellVotes(), true);
			// dto.addHeader("combineHoldVotes", ind.getCombineHoldVotes(), true);

		}
		if ("INTRADAY".equalsIgnoreCase(flag)) {
			dto.addHeader("INTRADAY", ind.getIntraday(), true);
			dto.addHeader("RESULT", ind.getResult(), true);
		}

	}

	public List<Indicator> getIndicators(String flag, String heikinPsarFilter) {
		heikinPsarFilter = heikinPsarFilter.equalsIgnoreCase("ALL") ? null : heikinPsarFilter.toUpperCase();

		switch (flag.toUpperCase()) {
		// ================== DAY ==================
		case "DAILY":
			if (heikinPsarFilter == null) { // CASE 1
				return indicatorRepo.findAllData();
			} else if ("FIRST BUY".equals(heikinPsarFilter)) { // CASE 2
				return indicatorRepo.findByHeikinAshiDayAndPsarFlagDay("FIRST BUY", "FIRST BUY");
			} else if ("FIRST SELL".equals(heikinPsarFilter)) { // CASE 3
				return indicatorRepo.findByHeikinAshiDayAndPsarFlagDay("FIRST SELL", "FIRST SELL");
			}
			break;

		// ================== WEEKLY ==================
		case "WEEKLY":
			if (heikinPsarFilter == null) { // CASE 4
				return Stream
						.concat(indicatorRepo.findByHeikinAshiDayAndPsarFlagDay("FIRST BUY", "FIRST BUY").stream(),
								indicatorRepo.findByHeikinAshiDayAndPsarFlagDay("FIRST SELL", "FIRST SELL").stream())
						.toList();
			} else if ("FIRST BUY".equals(heikinPsarFilter)) { // CASE 5
				return indicatorRepo.findByHeikinAshiWeeklyAndPsarFlagWeekly("FIRST BUY", "FIRST BUY");
			} else if ("FIRST SELL".equals(heikinPsarFilter)) { // CASE 6
				return indicatorRepo.findByHeikinAshiWeeklyAndPsarFlagWeekly("FIRST SELL", "FIRST SELL");
			}
			break;

		// ================== COMBINE ==================
		case "COMBINE":
			if (heikinPsarFilter == null) { // CASE 7
				return Stream
						.of(indicatorRepo.findByHeikinAshiDayAndPsarFlagDay("FIRST BUY", "FIRST BUY"),
								indicatorRepo.findByHeikinAshiDayAndPsarFlagDay("FIRST SELL", "FIRST SELL"),
								indicatorRepo.findByHeikinAshiWeeklyAndPsarFlagWeekly("FIRST BUY", "FIRST BUY"),
								indicatorRepo.findByHeikinAshiWeeklyAndPsarFlagWeekly("FIRST SELL", "FIRST SELL"))
						.flatMap(List::stream).toList();
			} else if ("FIRST BUY".equals(heikinPsarFilter)) { // CASE 8
				return Stream.concat(indicatorRepo.findByHeikinAshiDayAndPsarFlagDay("FIRST BUY", "FIRST BUY").stream(),
						indicatorRepo.findByHeikinAshiWeeklyAndPsarFlagWeekly("FIRST BUY", "FIRST BUY").stream())
						.toList();
			} else if ("FIRST SELL".equals(heikinPsarFilter)) { // CASE 9
				return Stream.concat(
						indicatorRepo.findByHeikinAshiDayAndPsarFlagDay("FIRST SELL", "FIRST SELL").stream(),
						indicatorRepo.findByHeikinAshiWeeklyAndPsarFlagWeekly("FIRST SELL", "FIRST SELL").stream())
						.toList();
			}
			break;

		// ================== INTRADAY ==================
		case "INTRADAY":
			if (heikinPsarFilter == null || "ALL".equals(heikinPsarFilter)) { // CASE 10
				return indicatorRepo.findByIntradayIsNotNull();
			} else if ("UP".equals(heikinPsarFilter)) { // CASE 11
				return indicatorRepo.findByIntraday("UP");
			} else if ("DOWN".equals(heikinPsarFilter)) { // CASE 12
				return indicatorRepo.findByIntraday("DOWN");
			}
			break;

		default:
			throw new IllegalArgumentException("Unknown flag: " + flag);
		}

		return Collections.emptyList();
	}

	@GetMapping("/getDetailsResults")
	public List<Result> getResultList() {
		return resultRepo.findAll();

	}

	@PatchMapping("/{id}/active")
	public ResponseEntity<String> updateActive(
	    @PathVariable Long id,
	    @RequestParam String active) {

	    String normalizedActive = active.trim().toUpperCase();
	    if (!normalizedActive.equals("Y") && !normalizedActive.equals("N")) {
	        return ResponseEntity.badRequest().body("Invalid active value. Allowed values are 'Y' or 'N'.");
	    }

	    boolean updated = resultService.updateActive(id, normalizedActive);
	    if (updated) {
	        return ResponseEntity.ok("Active status updated successfully.");
	    } else {
	        return ResponseEntity.notFound().build();
	    }
	}

}
