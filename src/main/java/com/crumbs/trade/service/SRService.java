package com.crumbs.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import com.angelbroking.smartapi.SmartConnect;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.CandleDTO;
import com.crumbs.trade.dto.CandleRequestDto;
import com.crumbs.trade.dto.PriceActionResult;
import com.crumbs.trade.dto.StrategyDTO;
import com.crumbs.trade.entity.Candle;
import com.crumbs.trade.entity.Chart;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.PricesIndex;
import com.crumbs.trade.entity.Signals;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.ChartRepo;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.PricesIndexRepo;
import com.crumbs.trade.repo.SignalsRepo;
import com.google.gson.Gson;

import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.*;
@Service
public class SRService {

	@Autowired
	ChartService chartService;
	
	@Autowired
	PricesIndexRepo pricesIndexRepo;
	
	@Autowired
	IndexesRepo indexesRepo;
	
	@Autowired
	AngelOneService angelOneService;
	
	@Autowired
	AngelOne angelOne;
	
	@Autowired
	PriceActionService priceActionService;
	
	@Autowired
	TaskService taskService;
	
	@Autowired
	SignalsRepo signalRepo;
	
	@Autowired
	ChartRepo chartRepo;
	
	private static final ZoneId NSE_ZONE = ZoneId.of("Asia/Kolkata");
	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	Logger logger = LoggerFactory.getLogger(SRService.class);
	    
	public enum TimeFrame {
	    ONE_MINUTE(15, 1, 10),       // NSE=15, MCX=10
	    FIVE_MINUTE(50, 5, 10),      // NSE=50, MCX=10
	    FIFTEEN_MINUTE(100, 15, 30), // NSE=100, MCX=30
	    THIRTY_MINUTE(150, 30, 50),  // NSE=150, MCX=50
	    ONE_HOUR(200, 60, 80),       // NSE=200, MCX=80
	    ONE_DAY(365, 1440, 365);     // NSE=365, MCX=365 (daily is light anyway)

	    private final int nseBestDays;
	    private final int candleMinutes;
	    private final int mcxBestDays;

	    TimeFrame(int nseBestDays, int candleMinutes, int mcxBestDays) {
	        this.nseBestDays = nseBestDays;
	        this.candleMinutes = candleMinutes;
	        this.mcxBestDays = mcxBestDays;
	    }

	    public int getCandleMinutes() {
	        return candleMinutes;
	    }

	    public int getBestDays(Market market) {
	        return market == Market.NSE ? nseBestDays : mcxBestDays;
	    }

	    public enum Market {
	        NSE, MCX
	    }
	}

	
	public List<PricesIndex> getCandleData(CandleRequestDto candleRequestDto,
			String name, String exchange)
	{
		Strategy strategy = chartService.getTokenDetails(name, exchange);
		BigDecimal currentPrice = getCurrentPriceForIndex(strategy);
		if (strategy.getName() != null) {
			chartService.readCandle(strategy, candleRequestDto.getType() , false, candleRequestDto.getTimeFrame(), candleRequestDto.getName(),
					candleRequestDto.getFromDate(), candleRequestDto.getToDate(),name);
			return pricesIndexRepo.findAll();
		}
		return null;
	}
	
	public BigDecimal getCurrentPriceForIndex(Strategy strategy)
	{
		SmartConnect smartConnect = angelOne.signIn();
		Indexes indexes = indexesRepo.findByNameAndSymbol(strategy.getName(), strategy.getTradingsymbol());
		BigDecimal currentPrice = angelOneService.getcurrentPrice(smartConnect, indexes.getExchange(),
				indexes.getSymbol(), indexes.getToken());
		return currentPrice;
	}
	
	// New method to get last candle close based on market timings
	private LocalDateTime getLastValidCandleCloseForMarket(TimeFrame tf, TimeFrame.Market market) {
	    LocalDateTime now = LocalDateTime.now(NSE_ZONE);

	    if(market == TimeFrame.Market.NSE) {
	        return getLastValidCandleClose(tf); // use existing method for NSE
	    } else {
	        // For MCX:
	        LocalTime marketOpen = LocalTime.of(9, 0);
	        LocalTime marketClose = LocalTime.of(23, 30);

	        if (tf == TimeFrame.ONE_DAY) {
	            if (now.toLocalTime().isBefore(marketClose)) {
	                return LocalDate.now(NSE_ZONE).minusDays(1).atTime(marketClose);
	            } else {
	                return LocalDate.now(NSE_ZONE).atTime(marketClose);
	            }
	        }

	        if(now.toLocalTime().isBefore(marketOpen)) {
	            return LocalDate.now(NSE_ZONE).minusDays(1).atTime(marketClose);
	        }

	        if(now.toLocalTime().isAfter(marketClose)) {
	            return LocalDate.now(NSE_ZONE).atTime(marketClose);
	        }

	        int interval = tf.getCandleMinutes();
	        LocalDateTime marketStart = LocalDate.now(NSE_ZONE).atTime(marketOpen);
	        long minutesSinceOpen = ChronoUnit.MINUTES.between(marketStart, now);
	        long completed = (minutesSinceOpen / interval) * interval;
	        return marketStart.plusMinutes(completed);
	    }
	}

	private static LocalDateTime getLastValidCandleClose(TimeFrame tf) {
        LocalDateTime now = LocalDateTime.now(NSE_ZONE);

        // Daily candle → last trading day close
        if (tf == TimeFrame.ONE_DAY) {
            if (now.toLocalTime().isBefore(LocalTime.of(15, 30))) {
                // market running → take yesterday 15:30
                return LocalDate.now(NSE_ZONE).minusDays(1).atTime(15, 30);
            } else {
                // after market close → today 15:30
                return LocalDate.now(NSE_ZONE).atTime(15, 30);
            }
        }

        // Intraday candles
        LocalTime marketOpen = LocalTime.of(9, 15);
        LocalTime marketClose = LocalTime.of(15, 30);

        // If before market opens, take yesterday’s last candle
        if (now.toLocalTime().isBefore(marketOpen)) {
            return LocalDate.now(NSE_ZONE).minusDays(1).atTime(15, 30);
        }

        // Clamp to market close
        if (now.toLocalTime().isAfter(marketClose)) {
            return LocalDate.now(NSE_ZONE).atTime(15, 30);
        }

        // Round down to nearest candle interval
        int interval = tf.getCandleMinutes();
        LocalDateTime marketStart = LocalDate.now(NSE_ZONE).atTime(marketOpen);
        long minutesSinceOpen = ChronoUnit.MINUTES.between(marketStart, now);
        long completed = (minutesSinceOpen / interval) * interval;

        return marketStart.plusMinutes(completed);
    }
	
	
	public CandleRequestDto getCandleTiming(String timeFrame, String exchange) {
	    CandleRequestDto candle = new CandleRequestDto();
	    TimeFrame selected = TimeFrame.valueOf(timeFrame);
	    TimeFrame.Market market = mapExchangeToMarket(exchange);
	    int bestDays = selected.getBestDays(market);
	    
	    LocalDateTime toDateTime = getLastValidCandleCloseForMarket(selected, market); // Updated call here
	    LocalDateTime fromDateTime = toDateTime.minusDays(bestDays);

	    System.out.println("Exchange: " + exchange + " (Market: " + market + ")");
	    System.out.println("Timeframe: " + selected);
	    System.out.println("From: " + fromDateTime.format(FORMATTER));
	    System.out.println("To:   " + toDateTime.format(FORMATTER));

	    candle.setFromDate(fromDateTime.format(FORMATTER));
	    candle.setToDate(toDateTime.format(FORMATTER));
	    candle.setTimeFrame(timeFrame);
	    candle.setType(exchange);
	    return candle;
	}


	private TimeFrame.Market mapExchangeToMarket(String exchange) {
	    if ("MCX".equalsIgnoreCase(exchange)) {
	        return TimeFrame.Market.MCX;
	    }
	    // Default to NSE if not MCX
	    return TimeFrame.Market.NSE;
	}
	
	
	
	public PriceActionResult getPriceAction(String timeFrame, String name, String exchange) {
		// Mock OHLCV candles
		pricesIndexRepo.deleteAll();

		CandleRequestDto candle = getCandleTiming(timeFrame, exchange);

		List<PricesIndex> candles = getCandleData(candle, name, exchange);

		if (candles != null && !candles.isEmpty()) {
			Strategy strategy = chartService.getTokenDetails(name, exchange);
			BigDecimal currentPrice = getCurrentPriceForIndex(strategy);

			PriceActionResult pa = priceActionService.analyze(currentPrice, candles, timeFrame);
			
			if (pa != null) {
				saveJson(pa);
			}
			return pa;
		}
		logger.error("Unable to get price action for {} ", name);
		return null;

	}
	
	@Transactional
	public void saveJson(PriceActionResult pa) {
		Gson gson = new Gson();
		Optional<Chart> chartOptional = chartRepo.findById(1L);
		if (chartOptional.isPresent()) {
			Chart chart = chartOptional.get();
			String jsonString = gson.toJson(pa);
			chart.setJson(jsonString);
			chartRepo.save(chart);
		}

	}
	
	@Transactional
	public Signals getSignals(String name, String type) {
		//PR Updates
		Strategy strategy = getTokenDetails(name, type);
		Signals signal = new Signals();
		PriceActionResult pr= getPriceAction("FIVE_MINUTE", strategy.getName(), strategy.getExchange());
		if(pr!=null)
		{
			String currentDate = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(Calendar.getInstance().getTime());
			signal.setPriceAction(pr.getSr_signal());
			signal.setFibo(pr.getFibo_signal());
			signal.setFinals(pr.getConsolidatedDecision());
			signal.setName(name);
			signal.setCreatedAt(currentDate);
			signalRepo.save(signal);
		}
		return signal;	
	}
	
	/*
	 * Get Token Details
	 */
	public Strategy getTokenDetails(String name, String exchange) {
		StrategyDTO strategyModified = taskService.getStrategyDetails(name, exchange);
		Strategy strategy = taskService.getChart(strategyModified.getSymbol(), strategyModified.getTradingsymbol());
		if (strategy != null) {
			return strategy;
		}
		return null;
	}
	
	public String getChartDetails() {
		Optional<Chart> chartOptional = chartRepo.findById(1L);
		if (chartOptional.isPresent()) {
			Chart chart = chartOptional.get();
			return chart.getJson();
		}
		return null;

	}
	
	public String getExchange(String input) {
		return "NIFTY".equalsIgnoreCase(input) ? "NFO" : "MCX";
	}
	
	public List<CandleDTO> getcandleList()
	{
		List<CandleDTO> candles = new ArrayList<>();
		List<PricesIndex> priceList = pricesIndexRepo.findAll();

		ZoneId istZone = ZoneId.of("Asia/Kolkata");
		LocalDate todayIST = LocalDate.now(istZone);

		for (PricesIndex p : priceList) {
		    // Parse UTC timestamp
		    Instant instant = Instant.parse(p.getTimestamp());
		    ZonedDateTime istTime = instant.atZone(istZone);

		    // Only include today's candles
		    if (!istTime.toLocalDate().isEqual(todayIST)) {
		        continue;
		    }

		    CandleDTO candle = new CandleDTO();
		    candle.setTime(istTime.toEpochSecond());
		    candle.setOpen(p.getOpen());
		    candle.setHigh(p.getHigh());
		    candle.setLow(p.getLow());
		    candle.setClose(p.getClose());
		    candle.setVolume(p.getVolume() != null ? p.getVolume() : BigDecimal.ZERO);

		    candles.add(candle);
		}

		// ✅ Keep only the last 20 candles
		if (candles.size() > 20) {
		    candles = candles.subList(candles.size() - 20, candles.size());
		}



		return candles;
	}
}
