package com.crumbs.trade.controller;

import java.io.IOException;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.DynamicIndicatorDTO;
import com.crumbs.trade.entity.Indicator;
import com.crumbs.trade.exception.NoIndicatorDataException;
import com.crumbs.trade.repo.IndicatorRepo;
import com.crumbs.trade.repo.StrategyRepo;
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
	
	@GetMapping("/getStocks/{indexName}/{symbol}")
	public String indicator(@PathVariable("indexName") String indexName, @PathVariable("symbol") String symbol)
			throws InterruptedException, URISyntaxException, IOException, SmartAPIException, ParseException {
		Instant start = timeLookUp.getStartTime();
		
		taskService.getSupportAndResistance(indexName, symbol);
		
		timeLookUp.getEndTime(start);
		return "Completed";
	}
	
	/* STEP 1 & 2 & 3
	 * Trigger for find 5 days Avg volume and execute day candle
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
	@Scheduled(cron = "0 35 09 * * ?") //Works
	public String findBullishStocks() throws SmartAPIException, Exception {
		if (strategyRepo.findByName("STOCK").getActive().equals("Y")) {
			taskService.findBullishStocks();
			return "Completed";
		}
		return "STOCK Strategy Disabled";
	}

	@GetMapping("/stocksResult")
	@Scheduled(cron = "0 35 15 * * ?") //Works
	public String getResult() throws SmartAPIException, Exception {
		if (strategyRepo.findByName("STOCK").getActive().equals("Y")) {
			taskService.getResult();
			return "Completed";
		}
		return "STOCK Strategy Disabled";
	}

	@GetMapping("/indicators/flagged")
    public List<DynamicIndicatorDTO> getIndicators(
            @RequestParam(defaultValue = "DAILY") String flag,
            @RequestParam(defaultValue = "ALL") String heikinPsarFilter) {

        Optional<String> filterOpt = "ALL".equalsIgnoreCase(heikinPsarFilter)
                ? Optional.empty()
                : Optional.of(heikinPsarFilter);

        List<Indicator> indicators = filterOpt
                .map(f -> indicatorRepo.findByHeikinAndPsar(f, f))
                .orElseGet(indicatorRepo::findAll);

        if (indicators == null || indicators.isEmpty()) {
            throw new NoIndicatorDataException("No stocks matched your selected filters.");
        }

        return indicators.stream()
                .map(ind -> toDTO(ind, flag))
                .collect(Collectors.toList());
    }

    private DynamicIndicatorDTO toDTO(Indicator ind, String flag) {
        DynamicIndicatorDTO dto = new DynamicIndicatorDTO();

        addCommonHeaders(dto, ind,flag);

      

        return dto;
    }

    private void addCommonHeaders(DynamicIndicatorDTO dto, Indicator ind, String flag) {
        dto.addHeader("name", ind.getName(), true);
        dto.addHeader("price", ind.getCurrentPrice(), true);
        

        if ("DAILY".equalsIgnoreCase(flag)) {
            dto.addHeader("daily_rsi", ind.getDailyRSI(), true);
            dto.addHeader("daily_ma20", ind.getMovingavg20Flag(), true);
            dto.addHeader("daily_ma50", ind.getMovingavg50Flag(), true);
            dto.addHeader("daily_ma200", ind.getMovingavg200Flag(), true);
            dto.addHeader("daily_volume", ind.getVolumeFlag(), true);
            dto.addHeader("daily_pivot", ind.getPivotFlag(), true);
            dto.addHeader("daily_srtrend", ind.getDaily_sr_trend(), true);
            dto.addHeader("daily_srsignal", ind.getDaily_sr_signal(), true);
            dto.addHeader("daily_srconfidence", ind.getDaily_sr_confidence(), true);
            dto.addHeader("daily_srReason", ind.getDaily_sr_reason(), true);
            dto.addHeader("daily_fibotrend", ind.getDaily_fibo_trend(), true);
            dto.addHeader("daily_fibosignal", ind.getDaily_fibo_signal(), true);
            dto.addHeader("daily_fiboconfidence", ind.getDaily_fibo_confidence(), true);
            dto.addHeader("daily_fiboReason", ind.getDaily_fibo_reason(), true);
            dto.addHeader("daily_AISignal", ind.getDaily_aiSignal(), true);
            dto.addHeader("daily_AIReason", ind.getDaily_aiReason(), true);
            dto.addHeader("Intraday", ind.getIntraday(), true);
        }

        if ("WEEKLY".equalsIgnoreCase(flag)) {
            dto.addHeader("weekly_rsi", ind.getWeeklyRSI(), true);
            dto.addHeader("weekly_ma20", ind.getMovingavg20Flag(), true);
            dto.addHeader("weekly_ma50", ind.getMovingavg50Flag(), true);
            dto.addHeader("weekly_ma200", ind.getMovingavg200Flag(), true);
            dto.addHeader("weekly_volume", ind.getVolumeFlag(), true);
            dto.addHeader("weekly_pivot", ind.getPivotFlag(), true);
            dto.addHeader("weekly_srtrend", ind.getWeekly_sr_trend(), true);
            dto.addHeader("weekly_srsignal", ind.getWeekly_sr_signal(), true);
            dto.addHeader("weekly_srconfidence", ind.getWeekly_sr_confidence(), true);
            dto.addHeader("weekly_srReason", ind.getWeekly_sr_reason(), true);
            dto.addHeader("weekly_fibotrend", ind.getWeekly_fibo_trend(), true);
            dto.addHeader("weekly_fibosignal", ind.getWeekly_fibo_signal(), true);
            dto.addHeader("weekly_fiboconfidence", ind.getWeekly_fibo_confidence(), true);
            dto.addHeader("weekly_fiboReason", ind.getWeekly_fibo_reason(), true);
            dto.addHeader("weekly_AISignal", ind.getWeekly_aiSignal(), true);
            dto.addHeader("weekly_AIReason", ind.getWeekly_aiReason(), true);
        }
        
		if ("COMBINED".equalsIgnoreCase(flag)) {
			dto.addHeader("daily_volume", ind.getVolumeFlag(), true);
			dto.addHeader("daily_srsignal", ind.getDaily_sr_signal(), true);
			dto.addHeader("daily_fibosignal", ind.getDaily_fibo_signal(), true);
			dto.addHeader("daily_AISignal", ind.getDaily_aiSignal(), true);
			dto.addHeader("weekly_volume", ind.getVolumeFlag(), true);
			dto.addHeader("weekly_srsignal", ind.getWeekly_sr_signal(), true);
			dto.addHeader("weekly_fibosignal", ind.getWeekly_fibo_signal(), true);
			dto.addHeader("weekly_AISignal", ind.getWeekly_aiSignal(), true);
			
			dto.addHeader("combineSignal", ind.getCombineSignal(), true);
			dto.addHeader("combineConfidence", ind.getCombineConfidence() , true);
			//dto.addHeader("combineReasonSummary", ind.getCombineReasonSummary() , true);
			//dto.addHeader("combineDetailedReason", ind.getCombineDetailedReason(), true);
			dto.addHeader("combineBuyVotes", ind.getCombineBuyVotes() , true);
			dto.addHeader("combineSellVotes", ind.getCombineSellVotes(), true);
			dto.addHeader("combineHoldVotes", ind.getCombineHoldVotes() , true);
			
			
		}
		
		

    }

}
