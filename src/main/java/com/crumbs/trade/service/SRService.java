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
import com.crumbs.trade.dto.CandleRequestDto;
import com.crumbs.trade.dto.PriceActionResult;
import com.crumbs.trade.entity.Candle;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.PricesIndex;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.PricesIndexRepo;

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
	
	public CandleRequestDto getCandleTiming(String timeFrame, String exchange) {
	    CandleRequestDto candle = new CandleRequestDto();
	    TimeFrame selected = TimeFrame.valueOf(timeFrame);

	    // map exchange → Market enum
	    TimeFrame.Market market = mapExchangeToMarket(exchange);

	    int bestDays = selected.getBestDays(market);

	    LocalDateTime toDateTime = getLastValidCandleClose(selected);
	    LocalDateTime fromDateTime = toDateTime.minusDays(bestDays);

	    System.out.println("Exchange: " + exchange + " (Market: " + market + ")");
	    System.out.println("Timeframe: " + selected);
	    System.out.println("From: " + fromDateTime.format(FORMATTER));
	    System.out.println("To:   " + toDateTime.format(FORMATTER));

	    candle.setFromDate(fromDateTime.format(FORMATTER));
	    candle.setToDate(toDateTime.format(FORMATTER));
	    candle.setTimeFrame(timeFrame);
	    candle.setType(exchange); // now type matches UI exchange (NFO, MCX, etc.)
	    return candle;
	}

	private TimeFrame.Market mapExchangeToMarket(String exchange) {
	    if ("MCX".equalsIgnoreCase(exchange)) {
	        return TimeFrame.Market.MCX;
	    }
	    // Default to NSE if not MCX
	    return TimeFrame.Market.NSE;
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
	
	public PriceActionResult getPriceAction(String timeFrame, String name, String exchange) {
		// Mock OHLCV candles
		pricesIndexRepo.deleteAll();

		CandleRequestDto candle = getCandleTiming(timeFrame, exchange);

		List<PricesIndex> candles = getCandleData(candle, name, exchange);

		if (candles != null && !candles.isEmpty()) {
			Strategy strategy = chartService.getTokenDetails(name, exchange);
			BigDecimal currentPrice = getCurrentPriceForIndex(strategy);

			PriceActionResult pa = priceActionService.analyze(currentPrice, candles, timeFrame);
			return pa;
		}
		logger.error("Unable to get price action for {} ", name);
		return null;

	}
}
