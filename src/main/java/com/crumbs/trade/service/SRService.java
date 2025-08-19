package com.crumbs.trade.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import com.angelbroking.smartapi.SmartConnect;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.CandleRequestDto;
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
	
	private static final ZoneId NSE_ZONE = ZoneId.of("Asia/Kolkata");
	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	    
	public enum TimeFrame {
        ONE_MINUTE(15, 1),
        FIVE_MINUTE(50, 5),
        THIRTY_MINUTE(100, 30),
        ONE_HOUR(200, 60),
        ONE_DAY(1000, 1440);

        private final int bestDays;
        private final int candleMinutes;

        TimeFrame(int bestDays, int candleMinutes) {
            this.bestDays = bestDays;
            this.candleMinutes = candleMinutes;
        }

        public int getBestDays() {
            return bestDays;
        }

        public int getCandleMinutes() {
            return candleMinutes;
        }
    }
	
	public List<PricesIndex> getCandleData(CandleRequestDto candleRequestDto)
	{
		Strategy strategy = chartService.getTokenDetails("NIFTY", "NFO");
		if (strategy.getName() != null) {
			chartService.readCandle(strategy, candleRequestDto.getType() , false, candleRequestDto.getTimeFrame(), candleRequestDto.getName(),
					candleRequestDto.getFromDate(), candleRequestDto.getToDate(),"OTHER");
			return pricesIndexRepo.findAll();
		}
		return null;
	}
	
	public BigDecimal getCurrentPriceForIndex()
	{
		SmartConnect smartConnect = angelOne.signIn();
		Indexes indexes = indexesRepo.findByNameAndSymbol("NIFTY", "NIFTY28AUG25FUT");
		BigDecimal currentPrice = angelOneService.getcurrentPrice(smartConnect, indexes.getExchange(),
				indexes.getSymbol(), indexes.getToken());
		return currentPrice;
	}
	
	public CandleRequestDto getCandleTiming(String timeFrame) {
		
		CandleRequestDto candle = new CandleRequestDto();
		TimeFrame selected = TimeFrame.valueOf(timeFrame);

		LocalDateTime toDateTime = getLastValidCandleClose(selected);
		LocalDateTime fromDateTime = toDateTime.minusDays(selected.getBestDays());

		System.out.println("Timeframe: " + selected);
		System.out.println("From: " + fromDateTime.format(FORMATTER));
		System.out.println("To:   " + toDateTime.format(FORMATTER));
		candle.setFromDate(fromDateTime.format(FORMATTER));
		candle.setToDate(toDateTime.format(FORMATTER));
		//candle.setFromDate("2025-08-01 15:25");
		//candle.setToDate("2025-08-18 15:25");
		candle.setTimeFrame(timeFrame);
		candle.setType("NFO");
		return candle;
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
}
