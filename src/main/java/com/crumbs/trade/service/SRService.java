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
			String name, String symbol)
	{
		Indexes indexes = indexesRepo.findByNameAndSymbol(name, symbol);
		if (indexes != null) {
			chartService.readCandle(indexes, candleRequestDto.getType() , false, candleRequestDto.getTimeFrame(), candleRequestDto.getName(),
					candleRequestDto.getFromDate(), candleRequestDto.getToDate(),name);
			return pricesIndexRepo.findAll();
		}
		return null;
	}
	
	public BigDecimal getCurrentPriceForIndex(String name, String symbol)
	{
		SmartConnect smartConnect = angelOne.signIn();
		Indexes indexes = indexesRepo.findByNameAndSymbol(name, symbol);
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
	
	
	
	public PriceActionResult getPriceAction(String timeFrame, String name, String exchange, String symbol) {
		// Mock OHLCV candles
		pricesIndexRepo.deleteAll();

		
		 //Step : 1 Time Period of the given stock/index
		CandleRequestDto candle = getCandleTiming(timeFrame,exchange);

		//Step 2 : Read candle data
		List<PricesIndex> candles = getCandleData(candle, name, symbol);

		if (candles != null && !candles.isEmpty()) {
			
			BigDecimal currentPrice = getCurrentPriceForIndex(name,symbol);
			PriceActionResult pa = priceActionService.analyze(currentPrice, candles, timeFrame);
			
			return pa;
		}
		logger.error("Unable to get price action for {} ", name);
		return null;

	}
	
	
	@Transactional
	public Signals getSignals(String name, String type) {
	    Strategy strategy = getTokenDetails(name, type);
	    Signals signal = new Signals();
	    PriceActionResult pr = getPriceAction("FIVE_MINUTE", strategy.getName(),
	                                          strategy.getExchange(), strategy.getTradingsymbol());

	    if (pr != null) {
	        String currentDate = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss")
	                                .format(Calendar.getInstance().getTime());

	        BigDecimal currentPrice = pr.getCurrentPrice();
	        BigDecimal buffer = currentPrice.multiply(BigDecimal.valueOf(0.003)); // 0.3% tolerance

	        // --- Collect supports ---
	        List<BigDecimal> supports = new ArrayList<>();
	        if (pr.getSr_nearestSupports() != null) {
	            pr.getSr_nearestSupports().forEach(s -> supports.add(s));
	        }
	        if (pr.getFibo_supports() != null) {
	            pr.getFibo_supports().forEach(f -> supports.add(f.getLevel()));
	        }

	        // --- Collect resistances ---
	        List<BigDecimal> resistances = new ArrayList<>();
	        if (pr.getSr_nearestResistances() != null) {
	            pr.getSr_nearestResistances().forEach(r -> resistances.add(r));
	        }
	        if (pr.getFibo_resistances() != null) {
	            pr.getFibo_resistances().forEach(f -> resistances.add(f.getLevel()));
	        }

	        // --- Find nearest support ---
	        BigDecimal nearestSupport = supports.stream()
	                .min((a, b) -> a.subtract(currentPrice).abs().compareTo(b.subtract(currentPrice).abs()))
	                .orElse(null);

	        // --- Find nearest resistance ---
	        BigDecimal nearestResistance = resistances.stream()
	                .min((a, b) -> a.subtract(currentPrice).abs().compareTo(b.subtract(currentPrice).abs()))
	                .orElse(null);

	        String finalSignal = "HOLD"; // default

	        if (nearestSupport != null) {
	            BigDecimal diffSupport = currentPrice.subtract(nearestSupport).abs();
	            if (diffSupport.compareTo(buffer) <= 0 && currentPrice.compareTo(nearestSupport) >= 0) {
	                finalSignal = "BUY";
	            }
	        }

	        if (nearestResistance != null) {
	            BigDecimal diffResistance = currentPrice.subtract(nearestResistance).abs();
	            if (diffResistance.compareTo(buffer) <= 0 && currentPrice.compareTo(nearestResistance) <= 0) {
	                finalSignal = "SELL";
	            }
	        }

	        signal.setPriceAction(pr.getSr_signal()); // optional: keep for debugging
	        signal.setFibo(pr.getFibo_signal());      // optional: keep for debugging
	        signal.setFinals(finalSignal);
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
	
	public List<CandleDTO> getcandleList() {
	    List<CandleDTO> candles = new ArrayList<>();
	    List<PricesIndex> priceList = pricesIndexRepo.findAll();

	    ZoneId istZone = ZoneId.of("Asia/Kolkata");

	    for (PricesIndex p : priceList) {
	        // Parse UTC timestamp
	        Instant instant = Instant.parse(p.getTimestamp());
	        ZonedDateTime istTime = instant.atZone(istZone);

	        // ✅ No date filter, include all candles
	        CandleDTO candle = new CandleDTO();
	        candle.setTime(istTime.toEpochSecond());
	        candle.setOpen(p.getOpen());
	        candle.setHigh(p.getHigh());
	        candle.setLow(p.getLow());
	        candle.setClose(p.getClose());
	        candle.setVolume(p.getVolume() != null ? p.getVolume() : BigDecimal.ZERO);

	        candles.add(candle);
	    }

	    return candles;
	}


}
