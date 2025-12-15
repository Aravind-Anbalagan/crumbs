package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import com.crumbs.trade.repo.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.MessagingException;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.utils.Constants;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.Candlestick;
import com.crumbs.trade.dto.OHLC;
import com.crumbs.trade.dto.PriceActionResult;
import com.crumbs.trade.dto.StrategyDTO;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.PricesIndex;
import com.crumbs.trade.entity.ResultVix;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.entity.Vix;
import com.crumbs.trade.service.ChartService.CandleRange;
import com.crumbs.trade.service.TrendLineService.OHLCV;
import com.crumbs.trade.service.TrendLineService.TrendAnalysisResult;
import com.crumbs.trade.utility.NSEWorkingDays;

import jakarta.mail.internet.AddressException;
import jakarta.transaction.Transactional;

@Service
public class ChartService {
	Logger logger = LoggerFactory.getLogger(ChartService.class);

	@Autowired
	AngelOne angelOne;

	@Autowired
	TaskService taskService;

	@Autowired
	TrendLineService trendLineService;

	@Autowired
	HeikinAshiIndicator heikinAshiIndicator;

	@Autowired
	VixRepo vixRepo;

	@Autowired
	PSARIndicator pSARIndicator;

	@Autowired
	VolumeService volumeService;

	@Autowired
	ResultVixRepo resultVixRepo;

	@Autowired
	IndexesRepo indexesRepo;

	@Autowired
	AngelOneService angelOneService;

	@Autowired
	FlatTradeService flatTradeService;
	
	@Autowired
	PricesIndexRepo pricesIndexRepo;
	
	@Autowired
	SRService srService;
	
	@Autowired
	MovingAvgWithSMASmoothing movingAvgWithSMASmoothing;
	
	@Autowired
	TelegramService telegramService;
	
	@Autowired
	SuperTrendIndicator superTrendIndicator;
	
	@Autowired
	VWAPIndicator vwapIndicator;

    @Autowired
    StrategyRepo strategyRepo;

	/*
	 * Get JsonDetail
	 */
	public JSONArray getJsonDetails(Indexes indexes, String type, boolean testflag, String fromDate, String toDate,
			String timeFrame) {
		try {
			SmartConnect smartConnect = angelOne.signIn();
			// JSONObject jsonObject = smartConnect.getLTP(strategy.getExchange(),
			// strategy.getTradingsymbol(),
			// strategy.getToken());
			/*
			 * if (jsonObject == null) { logger.info("Script is null {} , {} , {}",
			 * strategy.getExchange(), strategy.getTradingsymbol(), strategy.getToken());
			 * return null; }
			 */
			// BigDecimal index_CurrentPrice = new
			// BigDecimal(String.valueOf(jsonObject.get("ltp")));
			JSONArray responseArray = new JSONArray();
			JSONObject requestObejct = new JSONObject();
			requestObejct.put("exchange", indexes.getExchange());
			requestObejct.put("symboltoken", indexes.getToken());
			requestObejct.put("interval", timeFrame);
			requestObejct.put("fromdate", fromDate);
			requestObejct.put("todate", toDate);

			responseArray = smartConnect.candleData(requestObejct);
			// logger.info("fromdate " + fromDate + "todate ", toDate);
			return responseArray;
		} catch (Exception ex) {
			logger.error("Error occured in getJsonDetails() {} ", indexes.getName());
		}
		return null;

	}

	/*
	 * Save the OHLC Details
	 */
	public OHLC getOHLC(JSONArray ohlcArray) {
		OHLC ohlc = new OHLC();
		ohlc.setTimestamp(String.valueOf(ohlcArray.getString(0)));
		ohlc.setOpen(new BigDecimal(String.valueOf(ohlcArray.getDouble(1))));
		ohlc.setHigh(new BigDecimal(String.valueOf(ohlcArray.getDouble(2))));
		ohlc.setLow(new BigDecimal(String.valueOf(ohlcArray.getDouble(3))));
		ohlc.setClose(new BigDecimal(String.valueOf(ohlcArray.getDouble(4))));
		ohlc.setVolume(new BigDecimal(String.valueOf(ohlcArray.getDouble(5))));
		ohlc.setRange(ohlc.getHigh().subtract(ohlc.getLow()));

		return ohlc;
	}

	/*
	 * Calculate FROM and TO
	 */
	public String getDate(String timeline, String type) {
		LocalDate today = LocalDate.now();
		LocalDate lastWorkingDay = NSEWorkingDays.getLastWorkingDay(today);

		if (timeline.equalsIgnoreCase("FROM")) {
			return lastWorkingDay.toString().concat(taskService.getHourAndMinutes(timeline, 5, type));
		} else {
			return new SimpleDateFormat("yyyy-MM-dd").format(new Date())
					.concat(taskService.getHourAndMinutes(timeline, 5, type));
		}

	}

	public String getCurrentCandleTime(String input) {
		LocalDateTime now = LocalDateTime.now();
		CandleRange range = getLastFiveMinuteRange(now);
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

		if ("FROM".equalsIgnoreCase(input)) {
			return range.getFrom().format(formatter);
		} else if ("TO".equalsIgnoreCase(input)) {
			return range.getTo().format(formatter);
		}
		return null;

	}
    public static CandleRange getLastFiveMinuteRange(LocalDateTime time) {
        // Round down to nearest multiple of 5
        int minute = (time.getMinute() / 5) * 5;
        LocalDateTime to = time.withMinute(minute).withSecond(0).withNano(0);

        // "from" is 5 mins before "to"
        LocalDateTime from = to.minusMinutes(5);

        return new CandleRange(from, to);
    }

    public static class CandleRange {
        private final LocalDateTime from;
        private final LocalDateTime to;

        public CandleRange(LocalDateTime from, LocalDateTime to) {
            this.from = from;
            this.to = to;
        }

        public LocalDateTime getFrom() { return from; }
        public LocalDateTime getTo() { return to; }
    }

 // 1. Read the chart based on given time frame
 // 2. HeikinAshi + PSAR store
 // 3. Monitor the signal + VWAP
 public String readChartData(String timeFrame, String type, boolean testflag, String name, String fromDate,
                             String toDate, String symbol) throws SmartAPIException {
     try {
         Indexes indexes = indexesRepo.findByNameAndSymbol(name, symbol);
         Strategy strategy = getTokenDetails(name, type);

         if (strategy.getName() != null) {
             // Step 1: Read candles
             readCandle(indexes, type, testflag, timeFrame, name, fromDate, toDate, "HEIKIN_PSAR");

             // Step 2: Heikin Ashi calculation
             List<Candlestick> heikinAshiList = heikinAshiIndicator.calculateHeikinAshiCandles(getValuesAsList(name));
             if (heikinAshiList == null || heikinAshiList.isEmpty()) {
                 return "No HeikinAshi Data Found";
             }
             updateCandleData(heikinAshiList, "HEIKINACHI");

             // Step 3: PSAR calculation
             List<Candlestick> pSARList = pSARIndicator.calculatePSAR(getValuesAsList(name));
             if (pSARList == null || pSARList.isEmpty()) {
                 return "No PSAR Data Found";
             }
             updateCandleData(pSARList, "PSAR");

             // Step 4: Moving Average (MA)
             List<Candlestick> maCandleList = movingAvgWithSMASmoothing.getMovingAverage(getValuesAsList(name));
             if (maCandleList != null && !maCandleList.isEmpty()) {
                 updateCandleData(maCandleList, "MA");
             }

             // Step 5: SuperTrend
             List<Candlestick> superTrendList = superTrendIndicator.calculateSuperTrend(getValuesAsList(name));
             if (superTrendList != null && !superTrendList.isEmpty()) {
                 updateCandleData(superTrendList, "SUPER_TREND");
             } else {
                 logger.warn("SuperTrend calculation returned no data for {}", name);
             }

             // ✅ Step 6: VWAP
             List<Candlestick> vwapList = vwapIndicator.calculateVWAP(getValuesAsList(name));
             if (vwapList != null && !vwapList.isEmpty()) {
                 updateCandleData(vwapList, "VWAP");
                 //logger.info("VWAP calculated and updated for {}", name);
             } else {
                 logger.warn("VWAP calculation returned no data for {}", name);
             }

         } else {
             logger.error("No Strategy found for {}", name);
         }

     } catch (Exception e) {
         logger.error("Error occurred in readChartData() for {}: {}", name, e.getMessage(), e);
     }

     return "Completed";
 }


	/*
	 * Update Candle Details
	 */
	public void updateCandleData(List<Candlestick> heikinAshiList, String candleType) {
		if (heikinAshiList != null && !heikinAshiList.isEmpty()) {
			heikinAshiList.stream().forEach(item -> {
				updateCandle(item, candleType);
			});
		}
	}

	public void updateCandle(Candlestick candleStick, String candleType) {
	    Optional<Vix> vixOptional = vixRepo.findById(candleStick.getId());
	    if (vixOptional.isPresent()) {
	        Vix vix = vixOptional.get();

	        if (candleType.equalsIgnoreCase("PSAR")) {
	            vix.setPsar(candleStick.getSignal());

	        } else if (candleType.equalsIgnoreCase("HEIKINACHI")) {
	            vix.setHeikinachi(candleStick.getSignal());
	            vix.setCandleType(candleStick.getCandleType());
	            // If you want HeikinAshi values to overwrite OHLC, uncomment:
	            /*
	            vix.setOpen(candleStick.getOpen());
	            vix.setHigh(candleStick.getHigh());
	            vix.setLow(candleStick.getLow());
	            vix.setClose(candleStick.getClose());
	            */

	        } else if (candleType.equalsIgnoreCase("MA")) {
	            vix.setSmoothma(candleStick.getSmoothMA());
	            vix.setMasignal(candleStick.getMasignal());

	        } else if (candleType.equalsIgnoreCase("SUPER_TREND")) {
	            vix.setSuperTrend(candleStick.getSuperTrend());
	            vix.setSupertrendSignal(candleStick.getSuperTrendSignal());

	        } 
	        // ✅ Add VWAP integration here
	        else if (candleType.equalsIgnoreCase("VWAP")) {
	            vix.setVwap(candleStick.getVwap());
	            vix.setVwapSignal(candleStick.getSignal());
	        }

	        vixRepo.save(vix);
	    }
	}


	/*
	 * Get Token Details
	 */
	public Strategy getTokenDetails(String name, String exchange) {
		StrategyDTO strategyModified = taskService.getStrategyDetails(name, exchange);
		Strategy strategy = taskService.getChart(strategyModified.getSymbol(), strategyModified.getTradingsymbol(), strategyModified.getLive());
		if (strategy != null) {
			return strategy;
		}
		return null;
	}

	public void readCandle(Indexes indexes, String type, boolean testflag, String timeFrame, String name,
			String fromDate, String toDate, String tableName) {
		if (indexes != null) {

			JSONArray responseArray = getJsonDetails(indexes, type, testflag, fromDate, toDate, timeFrame);
			if (responseArray != null) {
				responseArray.forEach(item -> {

					JSONArray ohlcArray = (JSONArray) item;
					OHLC ohlc = getOHLC(ohlcArray);
					if (ohlc != null) {
						if("HEIKIN_PSAR".equalsIgnoreCase(tableName))
						{
							saveCandleData(ohlc, name);
						}
						else
						{
							saveCandleData_Index(ohlc, tableName,indexes.getExchange());
						}

					}
				});
			}
		}
	}

	/*
	 * Save Candle Data
	 */
	public void saveCandleData(OHLC ohlc, String name) {
		Vix vix = new Vix();
		vix.setTimestamp(ohlc.getTimestamp());
		vix.setClose(ohlc.getClose());
		vix.setHigh(ohlc.getHigh());
		vix.setOpen(ohlc.getOpen());
		vix.setLow(ohlc.getLow());
		vix.setName(name);
		vix.setVolume(ohlc.getVolume());
		vix.setRange(ohlc.getRange());
		vix.setType(taskService.getPriceType(ohlc.getOpen(), ohlc.getClose()));
		// getTrendLine(strategy, vix);
		vixRepo.save(vix);
	}
	public void saveCandleData_Index(OHLC ohlc, String name,String exchange) {
		PricesIndex vix = new PricesIndex();
		vix.setTimestamp(formatTime(ohlc.getTimestamp()));
		vix.setClose(ohlc.getClose());
		vix.setHigh(ohlc.getHigh());
		vix.setOpen(ohlc.getOpen());
		vix.setLow(ohlc.getLow());
		vix.setName(name);
		vix.setVolume(ohlc.getVolume());
		vix.setRange(ohlc.getRange());
		vix.setType(taskService.getPriceType(ohlc.getOpen(), ohlc.getClose()));
		// getTrendLine(strategy, vix);
		vix.setExchange(exchange);
		pricesIndexRepo.save(vix);
	}
	
	public String formatTime(String input) {
		// Parse the IST timestamp
        OffsetDateTime istDateTime = OffsetDateTime.parse(input);

        // Convert to UTC
        OffsetDateTime utcDateTime = istDateTime.withOffsetSameInstant(ZoneOffset.UTC);

        // Format as ISO string for DB storage
        String utcString = utcDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
		return utcString;
	}

	

	/*
	 * get the value as List
	 */
	public List<Candlestick> getValuesAsList(String name) {
	    List<Vix> vixList = vixRepo.findByName(name);
	    List<Candlestick> candlesticksList = new ArrayList<>();

	    if (vixList != null && !vixList.isEmpty()) {
	        for (Vix item : vixList) {
	            Candlestick candlestick = new Candlestick(
	                item.getOpen(),
	                item.getHigh(),
	                item.getLow(),
	                item.getClose(),
	                item.getId(),
	                null, // signal
	                null, // psarPrice
	                null  // candleType
	            );
	            // ✅ manually set volume
	            candlestick.setVolume(item.getVolume() != null ? item.getVolume() : BigDecimal.ZERO);
	            candlesticksList.add(candlestick);
	        }
	    }
	    return candlesticksList;
	}




	/*
	 * Monitor for Signal
	 */
	public void monitorSignal(String name, String type, boolean testFlag, int i)
			throws AddressException, MessagingException, IOException {
		Strategy strategy = getTokenDetails(name, type);
		SmartConnect smartconnect = angelOne.signIn();
		BigDecimal currentPrice = angelOneService.getcurrentPrice(smartconnect, strategy.getExchange(),
				strategy.getSymbol(), strategy.getToken());
		List<Vix> vixList = vixRepo.findAllByNameContainingOrderByIdDesc(name);
		ResultVix resultVix = resultVixRepo.findByActiveTrueAndName(name);
		/*if (!resultVixList.isEmpty()) {
			resultVix = resultVixList.get(resultVixList.size() - 1);
		}*/

		Vix vix = new Vix();
		// Find Signal
		if (vixList != null && !vixList.isEmpty()) {
			// Get Last candle

			vix = vixList.get(i);
			if (testFlag) {
				currentPrice = vix.getClose();
			}
			
			if (resultVix == null) {
				// Entry

				if (vix.getType().equalsIgnoreCase("BUY") && buyEntrySignal(vix)) {

					if (compareHeikinAchiAndPsarCandle(vixList, i)) // Check for Psar start
					{
						makeEntry(vix, strategy, "BUY", testFlag, currentPrice);
					}
					else
					{
						logger.error("First Psar Failed");
					}

				} else if (vix.getType().equalsIgnoreCase("SELL") && sellEntrySignal(vix)) {
					if (compareHeikinAchiAndPsarCandle(vixList, i)) // Check for Psar start
					{
						makeEntry(vix, strategy, "SELL", testFlag, currentPrice);
					}
					else
					{
						logger.error("First Psar Failed");
					}

				}

			} else {
				// Exit
				if (vix.getType().equalsIgnoreCase("BUY") && buyExitSignal(vix)) {

					makeEntry(vix, strategy, "BUY", testFlag, currentPrice);

				} else if (vix.getType().equalsIgnoreCase("SELL") && sellExitSignal(vix)) {
					makeEntry(vix, strategy, "SELL", testFlag, currentPrice);

				}

			}
			
			// Exit trade at martket close
			//exitFromTrade(vix.getTimestamp(), name, vix, strategy, currentPrice);
		}
	}
	
	/*
	 * Heikin + Psar+ MA + Super Trend
	 * All 4 condition should be satisfied
	 */
	public boolean buyEntrySignal(Vix vix) {
	    String name = vix.getName(); // assuming Vix has a field like "NIFTY" or "SILVERM"
	    
	    if ("NIFTY".equalsIgnoreCase(name)) {
	        // Conditions for NIFTY BUY
	        return "BUY".equalsIgnoreCase(vix.getHeikinachi())
	            && "BUY".equalsIgnoreCase(vix.getSupertrendSignal())
	            && "BUY".equalsIgnoreCase(vix.getPsar());
	            //&& "BUY".equalsIgnoreCase(vix.getVwapSignal());
	            // PSAR maybe ignored for NIFTY for faster signals
	    } 
	    else if ("SILVERM".equalsIgnoreCase(name)) {
	        // Conditions for SILVERM BUY
	        return "BUY".equalsIgnoreCase(vix.getHeikinachi())
	            && "BUY".equalsIgnoreCase(vix.getPsar())
	            && "BUY".equalsIgnoreCase(vix.getSupertrendSignal());
	            // VWAP may be optional for commodities
	    }

	    // Default (fallback)
	    return false;
	}

	public boolean sellEntrySignal(Vix vix) {
	    String name = vix.getName();
	    
	    if ("NIFTY".equalsIgnoreCase(name)) {
	        // Conditions for NIFTY SELL
	        return "SELL".equalsIgnoreCase(vix.getHeikinachi())
	            && "SELL".equalsIgnoreCase(vix.getPsar())
	            && "SELL".equalsIgnoreCase(vix.getSupertrendSignal());
	            //&& "SELL".equalsIgnoreCase(vix.getVwapSignal());
	    } 
	    else if ("SILVERM".equalsIgnoreCase(name)) {
	        // Conditions for SILVERM SELL
	        return "SELL".equalsIgnoreCase(vix.getHeikinachi())
	            && "SELL".equalsIgnoreCase(vix.getPsar())
	            && "SELL".equalsIgnoreCase(vix.getSupertrendSignal());
	    }

	    return false;
	}
	/*public boolean buyEntrySignal(Vix vix) {
	    return //"BUY".equalsIgnoreCase(vix.getType())
	           "BUY".equalsIgnoreCase(vix.getHeikinachi())
	        && "BUY".equalsIgnoreCase(vix.getPsar())
	        //&& "BUY".equalsIgnoreCase(vix.getMasignal())
	        && "BUY".equalsIgnoreCase(vix.getSupertrendSignal())
	        && "BUY".equalsIgnoreCase(vix.getVwapSignal());
	}
	
	public boolean sellEntrySignal(Vix vix) {
	    return //"SELL".equalsIgnoreCase(vix.getType())
	           "SELL".equalsIgnoreCase(vix.getHeikinachi())
	        && "SELL".equalsIgnoreCase(vix.getPsar())
	        //&& "SELL".equalsIgnoreCase(vix.getMasignal())
	        && "SELL".equalsIgnoreCase(vix.getSupertrendSignal())
	        && "SELL".equalsIgnoreCase(vix.getVwapSignal());
	}*/
	
	public boolean buyExitSignal(Vix vix) {
		if (vix.getType().equalsIgnoreCase("BUY") && vix.getHeikinachi().equalsIgnoreCase("BUY")
				&& vix.getPsar().equalsIgnoreCase("BUY")) {
			return true;
		}
		return false;
	}
	
	public boolean sellExitSignal(Vix vix) {
		if (vix.getType().equalsIgnoreCase("SELL") && vix.getHeikinachi().equalsIgnoreCase("SELL")
				&& vix.getPsar().equalsIgnoreCase("SELL")) {
			return true;
		}
		return false;
	}
	
	
	public void exitFromTrade(String name, String type)
			throws AddressException, MessagingException, IOException {
		SmartConnect smartconnect = angelOne.signIn();
		Strategy strategy = getTokenDetails(name, type);
		BigDecimal currentPrice = angelOneService.getcurrentPrice(smartconnect, strategy.getExchange(),
				strategy.getSymbol(), strategy.getToken());
		
		ResultVix resultVix = resultVixRepo.findByActiveTrueAndName(name);
		
		if(resultVix!=null)
		{
			String currentDate = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(Calendar.getInstance().getTime());
			int hour = 0;
			int min = 0;
			if ("NIFTY".equalsIgnoreCase(name)) {
				hour = 15;
				min = 20;
			} else if ("SILVERM".equalsIgnoreCase(name)) {
				hour = 23;
				min = 20;
			}
			if (isToday(currentDate) && IsExit(currentDate, hour, min)) {
				triggerExitOrder(resultVix,true);
				logger.info("Last trade: {}", name);
				resultVix.setExitPrice(currentPrice);
				resultVix.setExitTime(currentDate);
				BigDecimal profitLoss = null;
				if ("BUY".equalsIgnoreCase(resultVix.getType())) {
					profitLoss = resultVix.getExitPrice().subtract(resultVix.getEntryPrice());
				} else if ("SELL".equalsIgnoreCase(resultVix.getType())) {
					profitLoss = resultVix.getEntryPrice().subtract(resultVix.getExitPrice());
				}
				if (profitLoss.compareTo(BigDecimal.ZERO) > 0) {
					resultVix.setResult("PROFIT");
				} else if (profitLoss.compareTo(BigDecimal.ZERO) < 0) {
					resultVix.setResult("LOSS");
				}
				resultVix.setActive(false);
				resultVixRepo.save(resultVix);
			}
		}
		

	}

	public static boolean isToday(String timestamp) {
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

		// Parse to LocalDateTime
		LocalDateTime ldt = LocalDateTime.parse(timestamp, formatter);

		// Assume system default timezone (or specify if known)
		ZoneId zone = ZoneId.systemDefault();

		// Convert to ZonedDateTime
		ZonedDateTime zdt = ldt.atZone(zone);

		// Extract date
		LocalDate givenDate = zdt.toLocalDate();
		LocalDate today = LocalDate.now(zone);

		return givenDate.equals(today);
	}
	
	public boolean IsExit(String input, int hour, int min) {
		  // Your input format
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
        
        // Parse to LocalDateTime
        LocalDateTime ldt = LocalDateTime.parse(input, formatter);
        
        // Convert to system default zone (you can change if needed)
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime zdt = ldt.atZone(zone);
        
        // Extract LocalTime
        LocalTime localTime = zdt.toLocalTime();

        // Comparison time
        LocalTime comparisonTime = LocalTime.of(hour, min);

        // Compare
        return localTime.isAfter(comparisonTime);
	}

	@SuppressWarnings("null")
	@Transactional
	public void makeEntry(Vix vix, Strategy strategy, String type, boolean testFlag, BigDecimal currentPrice)
			throws AddressException, MessagingException, IOException {
		String currentDate = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(Calendar.getInstance().getTime());
		ResultVix resultVix = resultVixRepo.findByActiveTrueAndName(vix.getName());
		boolean tradeFlag = false;
		SmartConnect smartconnect = angelOne.signIn();
		//if (resultVix == null && type.equalsIgnoreCase(resultVix.getCombine()) ) {
		if (resultVix == null ) {
			// Entry
			resultVix = new ResultVix();
			
			//PR Updates
			/*PriceActionResult pr= srService.getPriceAction("FIVE_MINUTE", strategy.getName(), strategy.getExchange(),strategy.getTradingsymbol());
			if(pr!=null)
			{
				resultVix.setPriceAction(pr.getSr_signal());
				resultVix.setFibo(pr.getFibo_signal());
				resultVix.setCombine(pr.getConsolidatedDecision());
			}*/
			
			resultVix.setName(vix.getName());
			if (testFlag) {
				resultVix.setEntryTime(formatDateTime(vix.getTimestamp()));
				resultVix.setEntryPrice(vix.getOpen());
			} else {
				resultVix.setEntryTime(currentDate);
				
			}
			resultVix.setActive(true);
			resultVix.setTimestamp(vix.getTimestamp());
			resultVix.setType(type);
			resultVix.setMa(vix.getMasignal());
			resultVix.setSuperTrend(vix.getSupertrendSignal());
			// Place Order - ENTRY
			// Determine Trading Signal

			tradeFlag = "Y".equals(strategy.getLive()); // Take Trade In Flat Trade
			logger.info("Entry trade: Signal={}, Ma={}", type, resultVix.getMa());
			Token token = triggerEntryOrder(strategy, type, resultVix, tradeFlag);

			if (token != null) {
				resultVix.setLotSize(token.getQuantity());
				resultVix.setToken(token.getToken());
				resultVix.setExchange(token.getExch_seg());
				resultVix.setSymbol(token.getSymbol());
				//Fetch the current tradable price
				resultVix.setEntryPrice(token.getCurrentPrice());
			}
			resultVixRepo.save(resultVix);
			
			//Notification
			notifyTelegram(strategy,type);

		} else if (resultVix.getType() != null && !type.equalsIgnoreCase(resultVix.getType())) {
				//&& (resultVix.getType().equalsIgnoreCase(resultVix.getMa()))) {
			//PR Updates
			/*PriceActionResult pr= srService.getPriceAction("FIVE_MINUTE", strategy.getName(), strategy.getExchange(),strategy.getTradingsymbol());
			if(pr!=null)
			{
				resultVix.setPriceAction(pr.getSr_signal());
				resultVix.setFibo(pr.getFibo_signal());
				resultVix.setCombine(pr.getConsolidatedDecision());
			}*/
			// Remove Max High and Low 
			/*if (resultVix.getType().equalsIgnoreCase("BUY")) {
				resultVix.setMaxHigh(findMaxAndLowPrice(resultVix, resultVix.getTimestamp(), vix.getTimestamp(),
						resultVix.getType()));
			} else if (resultVix.getType().equalsIgnoreCase("SELL")) {
				resultVix.setMaxLow(findMaxAndLowPrice(resultVix, resultVix.getTimestamp(), vix.getTimestamp(),
						resultVix.getType()));
			}*/
			tradeFlag = "Y".equals(strategy.getLive()); // Take Trade In Flat Trade
			Token token =triggerExitOrder(resultVix, tradeFlag);
			if (testFlag) {
				resultVix.setExitPrice(vix.getOpen());
				resultVix.setExitTime(formatDateTime(vix.getTimestamp()));
			} else {
				//Get the Current Tradable price
				resultVix.setExitPrice(token.getCurrentPrice());
				resultVix.setExitTime(currentDate);

				BigDecimal entry = resultVix.getEntryPrice();
				BigDecimal exit = resultVix.getExitPrice();

				if (entry == null || exit == null) {
				    logger.error("Entry or Exit price missing for {}", resultVix.getName());
				    return;
				}

				// Since both CE BUY ("BUY") and PE BUY ("SELL") are option buys
				BigDecimal profitLoss = exit.subtract(entry);
				profitLoss = profitLoss.setScale(2, RoundingMode.HALF_UP);

				int compare = profitLoss.compareTo(BigDecimal.ZERO);
				if (compare > 0) {
				    resultVix.setResult("PROFIT");
				} else if (compare < 0) {
				    resultVix.setResult("LOSS");
				} else {
				    resultVix.setResult("NO CHANGE");
				}

			}
			resultVix.setPoints(calculatePoints(resultVix));
			resultVix.setActive(false);
			// Place Order  - EXIT
			//Determine Trading Signal
		

			tradeFlag = "Y".equals(strategy.getLive()); // Take Trade In Flat Trade
			logger.info("Exit trade: Signal={}, Ma={}", type, vix.getMasignal());
			
			resultVixRepo.save(resultVix);
			
			//Execute the Next Trade as per the signal
			//logger.info("Execute the Next Trade for {} ", resultVix.getName());
			//monitorSignal(resultVix.getName(), resultVix.getExchange(), false, 0);
			
			//Notification
			notifyTelegram(strategy,type);
			
		}
		
	}
	private void notifyTelegram(Strategy strategy, String type) {
	    try {
	        String message = String.format("%s: %s -> %s", 
	            type, 
	            strategy.getName(), 
	            type
	        );
	        
	        logger.info("📤 Attempting to send Telegram message: " + message);
	        
	        boolean ok = telegramService.sendMessage(message);
	        
	        if (ok) {
	            logger.info("✅ Telegram notification sent successfully");
	        } else {
	            logger.error("❌ Telegram notification FAILED for: " + message);
	            // Optional: retry logic
	            retryTelegramMessage(message, 3);
	        }
	    } catch (Exception e) {
	        logger.error("💥 Exception while sending Telegram message: " + e.getMessage(), e);
	    }
	}

	// Optional retry method
	private void retryTelegramMessage(String message, int maxRetries) {
	    for (int i = 1; i <= maxRetries; i++) {
	        try {
	            logger.info("🔄 Retry attempt " + i + "/" + maxRetries);
	            Thread.sleep(1000 * i); // Exponential backoff
	            
	            if (telegramService.sendMessage(message)) {
	                logger.info("✅ Retry successful on attempt " + i);
	                return;
	            }
	        } catch (Exception e) {
	            logger.error("❌ Retry " + i + " failed: " + e.getMessage());
	        }
	    }
	    logger.error("💀 All retry attempts exhausted for message: " + message);
	}
	public Token triggerExitOrder(ResultVix resultVix, boolean tradeFlag) {
		StrategyDTO strategyModified = new StrategyDTO();
		strategyModified.setName(resultVix.getName().equalsIgnoreCase("NIFTY") == true ? "NIFTY" : resultVix.getName());
		strategyModified.setTradingsymbol(resultVix.getSymbol());
		String transactionType = resultVix.getType().equalsIgnoreCase("BUY") ? Constants.TRANSACTION_TYPE_SELL
				: Constants.TRANSACTION_TYPE_BUY;
		return placeOrder(strategyModified, transactionType,"S", tradeFlag);

	}

	public int calculatePoints(ResultVix resultVix) {
		if (resultVix.getEntryPrice() == null || resultVix.getExitPrice() == null) {
			return 0;
		}

		BigDecimal points = resultVix.getExitPrice().subtract(resultVix.getEntryPrice());
		return points.setScale(0, RoundingMode.HALF_UP).intValue();
	}

	public int findMaxAndLowPrice(ResultVix resultVix, String startTimeStamp, String endTimeStamp, String type) {
		List<Vix> vixList = vixRepo.findAll();

		List<Vix> filteredVix = new ArrayList<>();
		for (Vix vix : vixList) {
			if (vix.getTimestamp().compareTo(startTimeStamp) >= 0 && vix.getTimestamp().compareTo(endTimeStamp) <= 0) {
				filteredVix.add(vix);
			}
		}

		if (type.equalsIgnoreCase("BUY")) {
			// Find the item with the highest price in the filtered list
			Vix highestPriceItem = Collections.max(filteredVix, Comparator.comparing(Vix::getHigh));
			// Get the index of this item in the original list

			return highestPriceItem.getHigh().subtract(resultVix.getEntryPrice()).intValue();
		} else {
			// Find the item with the highest price in the filtered list
			Vix lowesetPriceItem = Collections.min(filteredVix, Comparator.comparing(Vix::getLow));
			// Get the index of this item in the original list
			return resultVix.getEntryPrice().subtract(lowesetPriceItem.getLow()).intValue();
		}

	}

	public static String formatDateTime(String dateStr) {
		// Parse the string to OffsetDateTime (handles date, time, and timezone)
		OffsetDateTime offsetDateTime = OffsetDateTime.parse(dateStr);

		// Define a formatter for the desired format (yyyy-MM-dd HH:mm)
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

		// Format the OffsetDateTime into the specified format
		return offsetDateTime.format(formatter);
	}

	//Check Heikin = Psar
	/*public boolean compareHeikinAchiAndPsarCandle(List<Vix> vixList, int i) {
		if (vixList.get(i).getPsar() != null && vixList.get(i).getHeikinachi() != null) {
			if (vixList.get(i).getPsar().equalsIgnoreCase(vixList.get(i).getHeikinachi())) {
				return true;
			}
		}
		return false;
	}*/
	
	public boolean compareHeikinAchiAndPsarCandle(List<Vix> vixList, int i) {
	 

	    if (vixList == null || vixList.isEmpty() || i < 0 || i >= vixList.size()) {
	        logger.info("Invalid list or index {}", i);
	        return false;
	    }

	    Vix current = vixList.get(i);
	    String psar = current.getPsar();
	    String heikinAchi = current.getHeikinachi();

	    // Case 1: PSAR and Heikin must match
	    if (!psar.equalsIgnoreCase(heikinAchi)) {
	        logger.info("Candle[{}] → PSAR={} ❌ Heikin={} → No Signal", i, psar, heikinAchi);
	        return false;
	    }

	    logger.info("Candle[{}] → PSAR={} ✅ Heikin={}", i, psar, heikinAchi);

	    // Case 2: Fresh PSAR start (i+2 must be different)
	    if (i + 2 < vixList.size()) {
	        String psarI2 = vixList.get(i + 2).getPsar();
	        logger.info("Candle[{}] i+2 PSAR → {}", i, psarI2);

	        if (psarI2.equalsIgnoreCase(psar)) {
	            logger.info("Candle[{}] → PSAR same at i+2 → ❌ Not Fresh Start", i);
	            return false;
	        }
	    }

	    logger.info("Candle[{}] → Fresh PSAR Start Detected ✅", i);
	    return true;
	}



	public Token triggerEntryOrder(Strategy strategy, String type, ResultVix resultVix, boolean tradeFlag)
			throws AddressException, MessagingException, IOException {
		// Get Name and Trading Symbol
		StrategyDTO strategyModified = taskService.getStrategyDetails(strategy.getName(), strategy.getExchange());
		strategyModified = getNameAndTradingSymbol(strategyModified, type);
		// Place an Order and SL
		return placeOrder(strategyModified, type,"B", tradeFlag);
	}

	public StrategyDTO getNameAndTradingSymbol(StrategyDTO strategy, String type)
	        throws AddressException, MessagingException, IOException {

	    if (strategy == null || strategy.getName() == null || strategy.getExchange() == null) {
	        logger.warn("Invalid strategy data provided");
	        return strategy;
	    }

	    SmartConnect smartconnect = angelOne.signIn();
	    BigDecimal currentPrice = angelOneService.getcurrentPrice(
	            smartconnect,
	            strategy.getExchange(),
	            strategy.getTradingsymbol(),
	            strategy.getToken()
	    );

	    if (currentPrice == null) {
	        logger.warn("Unable to fetch current price for {}", strategy.getName());
	        return strategy;
	    }

	    String name = strategy.getName().toUpperCase();
	    int strikeInterval;

	    String key = name.trim().toUpperCase();

	    switch (key) {
	        case "NIFTY":
	        case "CPR_STRATEGY":
	        case "CRUDEOIL":
	            strikeInterval = 50;
	            break;

	        case "SILVERM":
	            strikeInterval = 1000;
	            return strategy;

	        default:
	            logger.warn("Unknown symbol name: {}", strategy.getName());
	            return strategy;
	    }

	    int nearestStrike = findNearestMultiple(currentPrice.intValue(), strikeInterval);
	    String optionType = "BUY".equalsIgnoreCase(type) ? "CE" : "PE";

	    // --- NIFTY specific logic: in-the-money adjustment ---
	    if ("NIFTY".equalsIgnoreCase(name)) {
	        if ("CE".equalsIgnoreCase(optionType)) {
	            nearestStrike = nearestStrike - 150; // CE → 150 points below
	        } else {
	            nearestStrike = nearestStrike + 150; // PE → 150 points above
	        }
	    }

	    String tradingSymbol = String.format("%s%s%d%s",
	            strategy.getName(),
	            strategy.getExpiry(),
	            nearestStrike,
	            optionType
	    );

	    logger.info("Generated Trading Symbol: {} | CurrentPrice: {} | Type: {} | Strike: {}",
	            tradingSymbol, currentPrice, optionType, nearestStrike);

	    strategy.setTradingsymbol(tradingSymbol);
	    return strategy;
	}

	/**
	 * Rounds a number to the nearest multiple of base (e.g., 22447 → 22450)
	 */
	int findNearestMultiple(int number, int base) {
	    int remainder = number % base;
	    return remainder < base / 2 ? number - remainder : number + (base - remainder);
	}


	public Token placeOrder(StrategyDTO strategy, String transactionType, String flatTradeType, boolean tradeFlag) {
		SmartConnect smartconnect = angelOne.signIn();
		Token token = new Token();

		Indexes indexes = indexesRepo.findByNameAndSymbol(strategy.getName(), strategy.getTradingsymbol());
		if (indexes != null) {

			// Order Execution - For Angelone
			token.setVariety(Constants.VARIETY_NORMAL);
			token.setExch_seg(indexes.getExchange());
			token.setOrderType(Constants.ORDER_TYPE_MARKET);
			token.setProductType(Constants.PRODUCT_CARRYFORWARD);
			token.setTransactionType(transactionType);
			token.setQuantity(indexes.getLotsize());
			token.setToken(indexes.getToken());
			token.setSymbol(indexes.getSymbol());
			// orderService.PlaceOrder(smartconnect, token, null);
			//Before place order, get the current Price
			BigDecimal currentPrice = angelOneService.getcurrentPrice(smartconnect, indexes.getExchange(),
					indexes.getSymbol(),indexes.getToken());
			token.setCurrentPrice(currentPrice);
			//Place trade in Flat Trade
			if(tradeFlag)
			{
				placeOrderInFlatTrade(token,flatTradeType);
			}		
		} else {
			logger.error("Failed to get trading sysmbol {} : {} ", strategy.getName(), strategy.getTradingsymbol());
		}
		return token;
	}

	public void placeOrderInFlatTrade(Token token, String flatTradeType) {
		Token flatToken = new Token();
		try {
			flatToken.setExch_seg(token.getExch_seg());
			flatToken.setSymbol(token.getSymbol());
			flatToken.setTransactionType(flatTradeType);
			flatToken.setQuantity(token.getQuantity());
			flatTradeService.PlaceOrderInFlatTrade(flatToken);
		} catch (SmartAPIException | Exception e) {
			// TODO Auto-generated catch block
			logger.error("Error occured while place order in FlatTrade : {}", e.getMessage());
		}
	}
	
	/*
	 * Look for Executed Orders
	 */
	public void lookForExecutedOrder(String name, String type, Vix vix, boolean testFlag) {

		ResultVix resultVix = resultVixRepo.findByActiveTrueAndName(name);
		Strategy strategy = getTokenDetails(name, type);
		BigDecimal currentPrice = new BigDecimal("0");
		boolean tradeFlag = false;
		if (resultVix != null) {
			// Get Current Price of Executed Order
			SmartConnect smartconnect = angelOne.signIn();

			if (!testFlag) {
				// Normal Flow
				currentPrice = angelOneService.getcurrentPrice(smartconnect, resultVix.getExchange(),
						resultVix.getSymbol(), resultVix.getToken());
			} else {
				// Back Test
				currentPrice = vix.getClose();
			}

			if (currentPrice != null && currentPrice.compareTo(BigDecimal.ZERO) != 0) {
				String result = checkPrice(currentPrice, resultVix.getEntryPrice(), resultVix.getType(),name);
				String transactionType = resultVix.getType().equalsIgnoreCase("BUY") ? Constants.TRANSACTION_TYPE_SELL
						: Constants.TRANSACTION_TYPE_BUY;
				if (result != null && !transactionType.equalsIgnoreCase(resultVix.getType())) {

					tradeFlag = "Y".equals(strategy.getLive()); // Take Trade In Flat Trade
					logger.info("Exit Trade: Result={}", result);

					// Place Order - EXIT
					Token token = placeOrder(setValues(resultVix), transactionType, "S", tradeFlag);
					closeOrder(resultVix, token, currentPrice, vix, testFlag, result);

				}
			}

		}
	}

	// Check for SL and Target
	public String checkPrice(BigDecimal currentPrice, BigDecimal executedPrice, String transactionType, String name) {
	    BigDecimal targetThreshold;
	    BigDecimal stopLossThreshold;

	    // instrument-based thresholds
	    if ("SILVERM".equalsIgnoreCase(name)) {
	        targetThreshold = new BigDecimal("750.00");
	        stopLossThreshold = new BigDecimal("250.00");
	    } else { // Default: Nifty or others
	        targetThreshold = new BigDecimal("20.00");
	        stopLossThreshold = new BigDecimal("10.00");
	    }

	    // difference = how much premium moved
	    BigDecimal difference = currentPrice.subtract(executedPrice);

	    // Since both CE and PE are BUY positions, same logic applies
	    if (difference.compareTo(targetThreshold) >= 0) {
	        return "TARGET";
	    } else if (difference.compareTo(stopLossThreshold.negate()) <= 0) {
	        return "SL";
	    }

	    return null; // still active
	}


	public StrategyDTO setValues(ResultVix resultVix) {
		StrategyDTO strategy = new StrategyDTO();
		strategy.setToken(resultVix.getToken());
		strategy.setTradingsymbol(resultVix.getSymbol());
		strategy.setName(resultVix.getName());
		return strategy;

	}

	public boolean timeCheck(String timeStamp, String name, boolean testFlag) {
		if (testFlag) {
			// Basktest
			if (IsExit(timeStamp, 15, 15) && "NIFTY".equalsIgnoreCase(name)) {
				return true;
			}

		} else {
			// Normal
			if (IsExit(timeStamp, 15, 20) && "NIFTY".equalsIgnoreCase(name)) {
				return true;
			}
			// Normal
			if (IsExit(timeStamp, 17, 00) && "CRUDEOIL".equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	@Transactional
	public void closeOrder(ResultVix resultVix, Token token, BigDecimal currentPrice, Vix vix, boolean testFlag, String result) {
		String currentDate = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(Calendar.getInstance().getTime());
		
		// Max High and Low
		/*if (resultVix.getType().equalsIgnoreCase("BUY")) {
			resultVix.setMaxHigh(
					findMaxAndLowPrice(resultVix, resultVix.getTimestamp(), vix.getTimestamp(), resultVix.getType()));
		} else if (resultVix.getType().equalsIgnoreCase("SELL")) {
			resultVix.setMaxLow(
					findMaxAndLowPrice(resultVix, resultVix.getTimestamp(), vix.getTimestamp(), resultVix.getType()));
		}*/

		// For BackTest
		if (testFlag) {
			resultVix.setExitPrice(token.getPrice() != null ? new BigDecimal(token.getPrice()) : currentPrice);
			resultVix.setExitTime(vix != null ? formatDateTime(vix.getTimestamp()) : null);
			if (resultVix.getPoints() > 0) {
				resultVix.setPoints(40);
			} else if (resultVix.getPoints() < 0) {
				resultVix.setPoints(-20);
			}
		}
		else
		{
			resultVix.setExitPrice(token.getCurrentPrice());
			resultVix.setExitTime(currentDate);
			resultVix.setResult(result);
		}
		resultVix.setPoints(calculatePoints(resultVix));
		resultVix.setActive(false);
		resultVixRepo.save(resultVix);
	}

	public String getName() {
		LocalTime currentTime = LocalTime.now();

		// Define the specific time to compare with (3:30 PM)
		LocalTime targetTime = LocalTime.of(15, 30); // 15:30 corresponds to 3:30 PM

		// Check if the current time is greater than 3:30 PM
		if (currentTime.isAfter(targetTime)) {
			return "MCX";
		} else {
			return "NIFTY";
		}
	}

	public BigDecimal getCurrentPrice(String name)
    {
        try {
            SmartConnect smartconnect = angelOne.signIn();  // login once

            Strategy strategy = strategyRepo.findByName(name);

            return angelOneService.getcurrentPrice(
                    smartconnect,
                    strategy.getExchange(),
                    strategy.getTradingsymbol(),
                    strategy.getToken()
            );
        } catch (Exception e) {
            logger.error("Unable to get Current Price for {}", name);
            return BigDecimal.ZERO;
        }

    }
}
