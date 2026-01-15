package com.crumbs.trade.service;

import com.crumbs.trade.builder.FiboLevelMapper;
import com.crumbs.trade.builder.LevelBuilder;
import com.crumbs.trade.dto.*;
import com.crumbs.trade.entity.*;
import com.crumbs.trade.repo.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import com.angelbroking.smartapi.SmartConnect;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.utility.NSEWorkingDays;
import com.google.gson.Gson;
import com.crumbs.trade.dto.ChartDataDTO;
import com.crumbs.trade.dto.FibonacciLevel;
import com.crumbs.trade.dto.FiboLevel;
import com.crumbs.trade.entity.Level;

import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
	
	@Autowired
	StrategyRepo strategyRepo;

    @Autowired
    LevelRepository levelRepo;

    @Autowired
    private LevelBuilder levelBuilder;

    @Autowired
    PredictivePriceActionService predictivePriceActionService;

    @Autowired
    private FiboLevelMapper fiboLevelMapper;

    private static final Map<String, CandleDTO> previousDayCache = new ConcurrentHashMap<>();
	private static final ZoneId NSE_ZONE = ZoneId.of("Asia/Kolkata");
	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	Logger logger = LoggerFactory.getLogger(SRService.class);


    public enum TimeFrame {
	    ONE_MINUTE(15, 1, 10),       // NSE=15, MCX=10
	    FIVE_MINUTE(50, 5, 10),      // NSE=50, MCX=10
	    FIFTEEN_MINUTE(100, 15, 30), // NSE=100, MCX=30
	    THIRTY_MINUTE(150, 30, 50),  // NSE=150, MCX=50
	    ONE_HOUR(200, 60, 80),       // NSE=200, MCX=80
	    FOUR_HOUR(200, 60, 80),       // NSE=200, MCX=80
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
	
	public BigDecimal getCurrentPriceForIndex(String name, String symbol) {
		try {
			SmartConnect smartConnect = angelOne.signIn();
			Indexes indexes = indexesRepo.findByNameAndSymbol(name, symbol);
			BigDecimal currentPrice = angelOneService.getcurrentPrice(smartConnect, indexes.getExchange(),
					indexes.getSymbol(), indexes.getToken());
			return currentPrice;
		} catch (Exception e) {
			logger.error("Unable to get price {} {} ", name, symbol);
		}
		return null;

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

	    //System.out.println("Exchange: " + exchange + " (Market: " + market + ")");
	    //System.out.println("Timeframe: " + selected);
	    //System.out.println("From: " + fromDateTime.format(FORMATTER));
	    //System.out.println("To:   " + toDateTime.format(FORMATTER));

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

	    PriceActionResult pr = getPriceAction(
	            "FIVE_MINUTE",
	            strategy.getName(),
	            strategy.getExchange(),
	            strategy.getTradingsymbol()
	    );

	    if (pr != null) {
	        String currentDate = LocalDateTime.now()
	                .format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"));

	        BigDecimal currentPrice = pr.getCurrentPrice();
	        BigDecimal buffer = currentPrice.multiply(BigDecimal.valueOf(0.003)); // 0.3% tolerance

	        // --- Collect supports ---
	        List<BigDecimal> supports = new ArrayList<>();
	        if (pr.getSr_nearestSupports() != null) {
	            supports.addAll(pr.getSr_nearestSupports());
	        }
	        if (pr.getFibo_supports() != null) {
	            pr.getFibo_supports().forEach(f -> supports.add(f.getLevel()));
	        }

	        // --- Collect resistances ---
	        List<BigDecimal> resistances = new ArrayList<>();
	        if (pr.getSr_nearestResistances() != null) {
	            resistances.addAll(pr.getSr_nearestResistances());
	        }
	        if (pr.getFibo_resistances() != null) {
	            pr.getFibo_resistances().forEach(f -> resistances.add(f.getLevel()));
	        }

	        // --- Find nearest support (must be below or equal to currentPrice) ---
	        BigDecimal nearestSupport = supports.stream()
	                .filter(s -> s.compareTo(currentPrice) <= 0)
	                .max(Comparator.naturalOrder())
	                .orElse(null);

	        // --- Find nearest resistance (must be above or equal to currentPrice) ---
	        BigDecimal nearestResistance = resistances.stream()
	                .filter(r -> r.compareTo(currentPrice) >= 0)
	                .min(Comparator.naturalOrder())
	                .orElse(null);

	        // --- Default signal ---
	        String finalSignal = "HOLD";

	        // --- Check conditions ---
	        boolean nearSupport = nearestSupport != null &&
	                currentPrice.subtract(nearestSupport).compareTo(buffer) <= 0;

	        boolean nearResistance = nearestResistance != null &&
	                nearestResistance.subtract(currentPrice).compareTo(buffer) <= 0;

	        if (nearSupport && nearResistance) {
	            finalSignal = "HOLD"; // stuck in range, avoid false signals
	        } else if (nearSupport) {
	            finalSignal = "BUY";
	        } else if (nearResistance) {
	            finalSignal = "SELL";
	        }

	        // --- Build Signal object ---
	        signal.setPriceAction(pr.getSr_signal()); // optional: debugging
	        signal.setFibo(pr.getFibo_signal());      // optional: debugging
	        signal.setFinals(finalSignal);
	        signal.setName(name);
	        signal.setCreatedAt(currentDate);

	        signalRepo.save(signal);
	    }

	    return signal;
	}

    public CandleDTO getPreviousOHLC(String timeFrame, String name, String exchange, String symbol)
    {
   		LocalDate today = LocalDate.now();
		LocalDate lastWorkingDay = NSEWorkingDays.getLastWorkingDay(today);
		Strategy strategy = taskService.getChart(name, strategyRepo.findByName(name).getTradingsymbol(),strategyRepo.findByName(name).getLive());
		SmartConnect smartConnect = angelOne.signIn();
		String fromDate = null;
		String toDate = null;
		
		if (name.equalsIgnoreCase("NIFTY")) {
			 fromDate = NSEWorkingDays.getLastWorkingDay(lastWorkingDay).toString().concat(" ").concat("09:15");
			 toDate = lastWorkingDay.toString().concat(" ").concat("15:30");

		} else {
			 fromDate = NSEWorkingDays.getLastWorkingDay(lastWorkingDay).toString().concat(" ").concat("09:00");
			 toDate = lastWorkingDay.toString().concat(" ").concat("23:30");
		}
	
		//fromDate = "2025-09-22 15:30";
		//toDate = "2025-09-23 15:30";
		CandleDTO candleDTO = new CandleDTO();
		JSONArray responseArray = new JSONArray();
		JSONObject requestObejct = new JSONObject();
		requestObejct.put("exchange", strategy.getExchange());
		requestObejct.put("symboltoken", strategy.getToken());
		requestObejct.put("interval", timeFrame);
		requestObejct.put("fromdate", fromDate);
		requestObejct.put("todate", toDate);

		responseArray = smartConnect.candleData(requestObejct);
		if (!responseArray.isEmpty()) {

			JSONArray ohlcArray = (JSONArray) responseArray.get(0);
			BigDecimal open = new BigDecimal(String.valueOf(ohlcArray.getDouble(1)));
			BigDecimal high = new BigDecimal(String.valueOf(ohlcArray.getDouble(2)));
			BigDecimal low = new BigDecimal(String.valueOf(ohlcArray.getDouble(3)));
			BigDecimal close = new BigDecimal(String.valueOf(ohlcArray.getDouble(4)));
			candleDTO.setOpen(open);
			candleDTO.setHigh(high);
			candleDTO.setLow(low);
			candleDTO.setClose(close);
			
		}
		return candleDTO;
    }

	
	/*
	 * Get Token Details
	 */
	public Strategy getTokenDetails(String name, String exchange) {
		StrategyDTO strategyModified = taskService.getStrategyDetails(name, exchange);
		Strategy strategy = taskService.getChart(strategyModified.getSymbol(), strategyModified.getTradingsymbol(),strategyModified.getLive());
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

public ChartDataDTO analyzeIntraday(String name,String timeFrame)
{
    // Get candles from your repository
    ChartDataDTO dto = new ChartDataDTO();
    pricesIndexRepo.deleteAll();
    String symbol = strategyRepo.findByName(name).getTradingsymbol();
    String exchange = getExchange(name);

    //Step : 1 Time Period of the given stock/index
    CandleRequestDto candle = getCandleTiming(timeFrame,exchange);

    //Step 2 : Read candle data
    List<PricesIndex> candles = getCandleData(candle, name, symbol);
    BigDecimal currentPrice = candles.get(candles.size() - 1).getClose();

    //Step 3:

    // Call predictive analysis
    PriceActionResult priceActionResult =predictivePriceActionService.analyzePredictive(currentPrice, candles, timeFrame);
    dto.setPriceActionSupport(priceActionResult.getSr_nearestSupports());
    dto.setPriceActionResistance(priceActionResult.getSr_nearestResistances());
    dto.setFiboSupport(priceActionResult.getFibo_supports());
    dto.setFiboResistance(priceActionResult.getFibo_resistances());
    CandleDTO candleDto = getPreviousDayCandle(name, exchange, symbol);
    dto.setPreviousDayCandle(candleDto);
    dto.setFinal_confidence(priceActionResult.getFinal_confidence());
    dto.setFinal_reason(priceActionResult.getFinal_reason());
    dto.setFinal_signal(priceActionResult.getFinal_signal());
    List<CandleDTO> candleList = getcandleList();
    candleList = getcandleList();
    dto.setCandles(candleList);
    return dto;
}
    public CandleDTO getPreviousDayCandle(String name, String exchange, String symbol) {
        String key = name + "|" + exchange + "|" + symbol;
        return previousDayCache.computeIfAbsent(
                key,
                k -> getPreviousOHLC("ONE_DAY", name, exchange, symbol)
        );
    }

    @Transactional
    public void saveLevels(
            String name,
            String timeFrame,
            ChartDataDTO dto) {

        // -----------------------------
        // Defensive checks
        // -----------------------------
        if (dto == null) return;

        // -----------------------------
        // Delete old snapshot
        // -----------------------------
        levelRepo.deleteBySymbolAndTimeframe(name, timeFrame);

        LocalDateTime now = LocalDateTime.now();
        List<Level> levels = new ArrayList<>();

        // -----------------------------
        // PRICE ACTION SUPPORT (seq > 0)
        // -----------------------------
        int seq = 1;
        if (dto.getPriceActionSupport() != null) {
            for (BigDecimal v : dto.getPriceActionSupport()) {
                levels.add(
                        levelBuilder.buildPriceActionLevel(
                                name,
                                timeFrame,
                                seq++,
                                v,
                                now
                        )
                );
            }
        }

        // -----------------------------
        // PRICE ACTION RESISTANCE (seq < 0)
        // -----------------------------
        seq = -1;
        if (dto.getPriceActionResistance() != null) {
            for (BigDecimal v : dto.getPriceActionResistance()) {
                levels.add(
                        levelBuilder.buildPriceActionLevel(
                                name,
                                timeFrame,
                                seq--,
                                v,
                                now
                        )
                );
            }
        }

        // -----------------------------
        // FIBO SUPPORT
        // -----------------------------
        seq = 1;
        if (dto.getFiboSupport() != null) {
            for (FibonacciLevel f : dto.getFiboSupport()) {

                FiboLevel mapped =
                        fiboLevelMapper.fromFibonacciLevel(f);

                levels.add(
                        levelBuilder.buildFiboLevel(
                                name,
                                timeFrame,
                                seq++,
                                mapped,
                                now
                        )
                );
            }
        }

        // -----------------------------
        // FIBO RESISTANCE
        // -----------------------------
        seq = -1;
        if (dto.getFiboResistance() != null) {
            for (FibonacciLevel f : dto.getFiboResistance()) {

                FiboLevel mapped =
                        fiboLevelMapper.fromFibonacciLevel(f);

                levels.add(
                        levelBuilder.buildFiboLevel(
                                name,
                                timeFrame,
                                seq--,
                                mapped,
                                now
                        )
                );
            }
        }

        // -----------------------------
        // Persist in one batch
        // -----------------------------
        if (!levels.isEmpty()) {
            levelRepo.saveAll(levels);
        }
    }


}
