package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;

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
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.ResultVix;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.entity.Vix;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.ResultVixRepo;
import com.crumbs.trade.repo.VixRepo;
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

	/*
	 * Get JsonDetail
	 */
	public JSONArray getJsonDetails(Strategy strategy, String type, boolean testflag, String fromDate, String toDate,
			String timeFrame) {
		SmartConnect smartConnect = angelOne.signIn();
		JSONObject jsonObject = smartConnect.getLTP(strategy.getExchange(), strategy.getTradingsymbol(),
				strategy.getToken());
		/*if (jsonObject == null) {
			logger.info("Script is null {} , {} , {}", strategy.getExchange(), strategy.getTradingsymbol(),
					strategy.getToken());
			return null;
		}*/
		BigDecimal index_CurrentPrice = new BigDecimal(String.valueOf(jsonObject.get("ltp")));
		JSONArray responseArray = new JSONArray();
		JSONObject requestObejct = new JSONObject();
		requestObejct.put("exchange", strategy.getExchange());
		requestObejct.put("symboltoken", strategy.getToken());
		requestObejct.put("interval", timeFrame);
		requestObejct.put("fromdate", fromDate);
		requestObejct.put("todate", toDate);

		responseArray = smartConnect.candleData(requestObejct);
		// logger.info("fromdate " + fromDate + "todate ", toDate);
		return responseArray;

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

	// 1. Read the chart based on given time frame
	// 2.HeikinAchi + psar store
	// 3. Monitor the signal
	public String readChartData(String timeFrame, String type, boolean testflag, String name, String fromDate,
			String toDate) throws SmartAPIException {
		try {

			Strategy strategy = getTokenDetails(name, type);
			if (strategy.getName() != null) {
				readCandle(strategy, type, testflag, timeFrame, name, fromDate, toDate);
				List<Candlestick> heikinAshiList = heikinAshiIndicator
						.calculateHeikinAshiCandles(getValuesAsList(name));
				if (heikinAshiList != null && !heikinAshiList.isEmpty()) {
					updateCandleData(heikinAshiList, "HEIKINACHI");
					List<Candlestick> pSARList = pSARIndicator.calculatePSAR(getValuesAsList(name));
					if (pSARList != null && !pSARList.isEmpty()) {
						updateCandleData(pSARList, "PSAR");
					} else {
						return "No Data Found";
					}

				} else {
					return "No Data Found";
				}
			} else {
				logger.error("No Strategy found");
			}

		} catch (Exception e) {
			logger.error("Error Occured while get volume date , {}", e.getMessage());
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
			} else {
				vix.setHeikinachi(candleStick.getSignal());
				vix.setCandleType(candleStick.getCandleType());
				// Overwrite Traditional candle with HeikinAchi candle data
				/*
				 * vix.setOpen(candleStick.open); vix.setHigh(candleStick.high);
				 * vix.setLow(candleStick.low); vix.setClose(candleStick.close);
				 */
			}
			vixRepo.save(vix);
		}
	}

	/*
	 * Get Token Details
	 */
	public Strategy getTokenDetails(String name, String exchange) {
		Strategy strategyModified = taskService.getStrategyDetails(name, exchange);
		Strategy strategy = taskService.getChart(strategyModified.getSymbol(), strategyModified.getTradingsymbol());
		if (strategy != null) {
			return strategy;
		}
		return null;
	}

	public void readCandle(Strategy strategy, String type, boolean testflag, String timeFrame, String name,
			String fromDate, String toDate) {
		if (strategy != null) {

			JSONArray responseArray = getJsonDetails(strategy, type, testflag, fromDate, toDate, timeFrame);
			if (responseArray != null) {
				responseArray.forEach(item -> {

					JSONArray ohlcArray = (JSONArray) item;
					OHLC ohlc = getOHLC(ohlcArray);
					if (ohlc != null) {
						saveCandleData(ohlc, name, strategy);

					}
				});
			}
		}
	}

	/*
	 * Save Candle Data
	 */
	public void saveCandleData(OHLC ohlc, String name, Strategy strategy) {
		Vix vix = new Vix();
		vix.setTimestamp(ohlc.getTimestamp());
		vix.setClose(ohlc.getClose());
		vix.setHigh(ohlc.getHigh());
		vix.setOpen(ohlc.getOpen());
		vix.setLow(ohlc.getLow());
		vix.setName(name);
		vix.setVolume(ohlc.getVolume());
		vix.setRangle(ohlc.getRange());
		vix.setType(taskService.getPriceType(ohlc.getOpen(), ohlc.getClose()));
		// getTrendLine(strategy, vix);
		vixRepo.save(vix);
	}

	// Get Trend Line based on last 5 days candle data
	public Vix getTrendLine(Strategy strategy, Vix vix) {
		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		List<OHLCV> ohlcvList = new ArrayList<>();
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
		List<LocalDate> last5WorkingDays = volumeService.getLastWorkingDays(2);
		Collections.reverse(last5WorkingDays);
		String fromDate = last5WorkingDays.get(0).atStartOfDay().format(formatter).concat(" 09:15");
		String toDate = new SimpleDateFormat("yyyy-MM-dd").format(new Date())
				.concat(taskService.getHourAndMinutes("TO", 5, strategy.getExchange()));
		JSONArray responseArray = getJsonDetails(strategy, null, false, fromDate, toDate, "FIVE_MINUTE");
		if (responseArray != null) {
			responseArray.forEach(item -> {

				JSONArray ohlcArray = (JSONArray) item;
				OHLC ohlc = getOHLC(ohlcArray);
				OffsetDateTime offsetDateTime = OffsetDateTime.parse(ohlc.getTimestamp());
				LocalDateTime localDateTime = offsetDateTime.toLocalDateTime();
				if (ohlc != null) {
					ohlcvList.add(new OHLCV(localDateTime, ohlc.getOpen(), ohlc.getHigh(), ohlc.getLow(),
							ohlc.getClose(), ohlc.getVolume()));
				}
			});
		}
		/*
		 * List<TrendLineService.OHLCV> data = entityList.stream() .map(e -> new
		 * TrendLineService.OHLCV( e.getTime(), e.getOpen(), e.getHigh(), e.getLow(),
		 * e.getClose(), e.getVolume())) .collect(Collectors.toList());
		 */
		TrendAnalysisResult trendAnalysisResult = trendLineService.analyzeTrend(ohlcvList, "5m");
		if (trendAnalysisResult != null) {
			vix.setTrendSignal(trendAnalysisResult.getTrendSignal());
			vix.setTrendDirection(trendAnalysisResult.getTrendDirection());
			vix.setZigzagSignal(trendAnalysisResult.getZigzagSignal());
			vix.setZigzagDirection(trendAnalysisResult.getZigzagDirection());
		}
		return vix;
	}

	/*
	 * get the value as List
	 */
	public List<Candlestick> getValuesAsList(String name) {
		List<Vix> vixList = vixRepo.findByName(name);
		List<Candlestick> candlesticksList = new ArrayList<>();
		if (vixList != null && !vixList.isEmpty()) {
			vixList.stream().forEach(item -> {
				Candlestick candlestick = new Candlestick(item.getOpen(), item.getHigh(), item.getLow(),
						item.getClose(), item.getId(), null, null, null);
				candlesticksList.add(candlestick);
			});

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
		List<ResultVix> resultVixList = resultVixRepo.findByName(name);
		ResultVix resultVix = new ResultVix();
		if (!resultVixList.isEmpty()) {
			resultVix = resultVixList.get(resultVixList.size() - 1);
		}

		Vix vix = new Vix();
		// Find Signal
		if (vixList != null && !vixList.isEmpty()) {
			// Get Last candle

			vix = vixList.get(i);
			if (testFlag) {
				currentPrice = vix.getClose();
			}
			// Check Two condition

			// if (getLastTwoHeikinAchiCandle(vixList, i) && getLastTwoPsarCandle(vixList,
			// i)) {
			if (compareHeikinAchiAndPsarCandle(vixList, i)) {
				if (vix.getType().equalsIgnoreCase("BUY") && vix.getHeikinachi().equalsIgnoreCase("BUY")
						&& vix.getPsar().equalsIgnoreCase("BUY")) {

					makeEntry(vix, strategy, "BUY", testFlag, currentPrice);

				} else if (vix.getType().equalsIgnoreCase("SELL") && vix.getHeikinachi().equalsIgnoreCase("SELL")
						&& vix.getPsar().equalsIgnoreCase("SELL")) {
					makeEntry(vix, strategy, "SELL", testFlag, currentPrice);

				}
			}
			// Exit trade at 3.20 PM
			if (IsExit(vix.getTimestamp(), 15, 20) && "NIFTY".equalsIgnoreCase(name)) {
				testFlag = true;

				makeEntry(vix, strategy, " ", testFlag, currentPrice);

			}

		}
	}

	public boolean IsExit(String input, int hour, int min) {
		String pattern = "yyyy-MM-dd HH:mm";
		// Parse the string to OffsetDateTime (handles date, time, and timezone)
		OffsetDateTime offsetDateTime = OffsetDateTime.parse(input);

		// Convert to LocalTime in the system's default time zone
		LocalTime localTime = offsetDateTime.atZoneSameInstant(ZoneId.systemDefault()).toLocalTime();

		LocalTime comparisonTime = LocalTime.of(hour, min); // 15:25 is 3:25 PM
		// Parse the string to LocalDateTime

		if (localTime.isAfter(comparisonTime)) {
			return true;
		} else {
			return false;
		}
	}

	@Transactional
	public void makeEntry(Vix vix, Strategy strategy, String type, boolean testFlag, BigDecimal currentPrice)
			throws AddressException, MessagingException, IOException {
		String currentDate = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(Calendar.getInstance().getTime());
		ResultVix resultVix = resultVixRepo.findByActiveAndName("Y", vix.getName());

		if (resultVix == null) {
			// Entry
			resultVix = new ResultVix();
			resultVix.setName(vix.getName());
			if (testFlag) {
				resultVix.setEntryTime(formatDateTime(vix.getTimestamp()));
			} else {
				resultVix.setEntryTime(currentDate);
			}
			resultVix.setEntryPrice(currentPrice);
			// resultVix.setEntryPrice(vix.getClose());
			resultVix.setActive("Y");
			resultVix.setTimestamp(vix.getTimestamp());
			resultVix.setType(type);
			// Place Order
			Token token = triggerEntryOrder(strategy, type, resultVix);
			if (token != null) {
				resultVix.setLotSize(token.getQuantity());
				resultVix.setToken(token.getToken());
				resultVix.setExchange(token.getExch_seg());
				resultVix.setSymbol(token.getSymbol());

			}

		} else if ((resultVix.getType() != null && (!type.equalsIgnoreCase(resultVix.getType())) || testFlag)) {

			if (resultVix.getType().equalsIgnoreCase("BUY")) {
				resultVix.setMaxHigh(findMaxAndLowPrice(resultVix, resultVix.getTimestamp(), vix.getTimestamp(),
						resultVix.getType()));
			} else if (resultVix.getType().equalsIgnoreCase("SELL")) {
				resultVix.setMaxLow(findMaxAndLowPrice(resultVix, resultVix.getTimestamp(), vix.getTimestamp(),
						resultVix.getType()));
			}

			
			if (testFlag) {
				resultVix.setExitPrice(vix.getClose());
				resultVix.setExitTime(formatDateTime(vix.getTimestamp()));
			} else {
				resultVix.setExitPrice(currentPrice);
				resultVix.setExitTime(currentDate);
			}
			resultVix.setPoints(calculatePoints(resultVix));
			resultVix.setActive(null);
			// Place Order
			triggerExitOrder(resultVix);
		}

		resultVixRepo.save(resultVix);
	}

	public Token triggerExitOrder(ResultVix resultVix) {
		Strategy strategyModified = new Strategy();
		strategyModified.setName(resultVix.getName().equalsIgnoreCase("NIFTY") == true ? "NIFTY" : resultVix.getName());
		strategyModified.setTradingsymbol(resultVix.getSymbol());
		String transactionType = resultVix.getType().equalsIgnoreCase("BUY") ? Constants.TRANSACTION_TYPE_SELL
				: Constants.TRANSACTION_TYPE_BUY;
		return placeOrder(strategyModified, transactionType);

	}

	public int calculatePoints(ResultVix resultVix) {
		if (resultVix.getType().equalsIgnoreCase("BUY") && resultVix.getExitPrice() != null) {
			return resultVix.getExitPrice().subtract(resultVix.getEntryPrice()).intValue();
		} else if (resultVix.getType().equalsIgnoreCase("SELL") && resultVix.getEntryPrice() != null) {
			return resultVix.getEntryPrice().subtract(resultVix.getExitPrice()).intValue();
		}
		return 0;

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

	public boolean compareHeikinAchiAndPsarCandle(List<Vix> vixList, int i) {
		if (vixList.get(i).getPsar() != null && vixList.get(i).getHeikinachi() != null) {
			if (vixList.get(i).getPsar().equalsIgnoreCase(vixList.get(i).getHeikinachi())) {
				return true;
			}
		}
		return false;
	}

	public Token triggerEntryOrder(Strategy strategy, String type, ResultVix resultVix)
			throws AddressException, MessagingException, IOException {
		// Get Name and Trading Symbol
		Strategy strategyModified = taskService.getStrategyDetails(strategy.getName(), strategy.getExchange());
		strategy = getNameAndTradingSymbol(strategyModified, type);
		// Place an Order and SL
		return placeOrder(strategy, type);
	}

	public Strategy getNameAndTradingSymbol(Strategy strategy, String type)
			throws AddressException, MessagingException, IOException {
		SmartConnect smartconnect = angelOne.signIn();
		BigDecimal currentPrice = angelOneService.getcurrentPrice(smartconnect, strategy.getExchange(),
				strategy.getTradingsymbol(), strategy.getToken());

		if (currentPrice != null) {

			int base = 50; // Change this to 100 or any other number as needed

			int nearestPrice = findNearestMultiple(currentPrice.intValue(), base);

			String tradingSymbol = strategy.getName().concat(strategy.getExpiry()).concat(String.valueOf(nearestPrice))
					.concat("BUY".equalsIgnoreCase(type) == true ? "CE" : "PE");
			strategy.setTradingsymbol(tradingSymbol);
			strategy.setName(strategy.getName());
		}

		return strategy;
	}

	public int findNearestMultiple(int number, int base) {
		return Math.round(number / (float) base) * base;
	}

	public Token placeOrder(Strategy strategy, String transactionType) {
		SmartConnect smartconnect = angelOne.signIn();
		Token token = new Token();

		Indexes indexes = indexesRepo.findByNameAndSymbol(strategy.getName(), strategy.getTradingsymbol());
		if (indexes != null) {

			// Order Execution
			token.setVariety(Constants.VARIETY_NORMAL);
			token.setExch_seg(indexes.getExchange());
			token.setOrderType(Constants.ORDER_TYPE_MARKET);
			token.setProductType(Constants.PRODUCT_CARRYFORWARD);
			token.setTransactionType(transactionType);
			token.setQuantity(indexes.getLotsize());
			token.setToken(indexes.getToken());
			token.setSymbol(indexes.getSymbol());
			// orderService.PlaceOrder(smartconnect, token, null);
		}
		return token;
	}

	/*
	 * Look for Executed Orders
	 */
	public void lookForExecutedOrder(String name, String type, Vix vix, boolean testFlag) {

		ResultVix resultVix = resultVixRepo.findByActiveAndName("Y", name);
		Strategy strategy = getTokenDetails(name, type);
		BigDecimal currentPrice = new BigDecimal("0");
		if (resultVix != null) {
			// Get Current Price of Executed Order
			SmartConnect smartconnect = angelOne.signIn();

			if (!testFlag) {
				// Normal Flow
				currentPrice = angelOneService.getcurrentPrice(smartconnect, strategy.getExchange(),
						strategy.getSymbol(), strategy.getToken());
			} else {
				// Back Test
				currentPrice = vix.getClose();
			}

			if (timeCheck(vix.getTimestamp(), name, testFlag) || currentPrice != null) {
				String result = checkPrice(currentPrice, resultVix.getEntryPrice(), resultVix.getType());
				String transactionType = resultVix.getType().equalsIgnoreCase("BUY") ? Constants.TRANSACTION_TYPE_SELL
						: Constants.TRANSACTION_TYPE_BUY;
				if (result != null && !transactionType.equalsIgnoreCase(resultVix.getType())) {

					Token token = placeOrder(setValues(resultVix), transactionType);
					closeOrder(resultVix, token, currentPrice, vix, testFlag, result);

				}
			}

		}
	}

	// Check for SL and Target
	public String checkPrice(BigDecimal currentPrice, BigDecimal executedPrice, String transactionType) {

		BigDecimal targetThreshold = new BigDecimal("40.00");
		BigDecimal stopLossThreshold = new BigDecimal("-20.00");
		BigDecimal difference = currentPrice.subtract(executedPrice);

		if ("BUY".equalsIgnoreCase(transactionType)) {

			if (difference.compareTo(targetThreshold) > 0) {
				//logger.info("Target reached (BUY position)!");
				return "TARGET";
			} else if (difference.compareTo(stopLossThreshold) <= 0) {
				//logger.info("Stop-loss triggered (BUY position)!");
				return "SL";
			}
		} else if ("SELL".equalsIgnoreCase(transactionType)) {
			if (difference.compareTo(stopLossThreshold) < 0) {
				//logger.info("Target reached (SELL position)!");
				return "TARGET";
			} else if (difference.compareTo(targetThreshold) >= 0) {
				//logger.info("Stop-loss triggered (SELL position)!");
				return "SL";
			}
		}

		return null;

	}

	public Strategy setValues(ResultVix resultVix) {
		Strategy strategy = new Strategy();
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
		}
		return false;
	}

	@Transactional
	public void closeOrder(ResultVix resultVix, Token token, BigDecimal currentPrice, Vix vix, boolean testFlag, String result) {
		String currentDate = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(Calendar.getInstance().getTime());
		if (resultVix.getType().equalsIgnoreCase("BUY")) {
			resultVix.setMaxHigh(
					findMaxAndLowPrice(resultVix, resultVix.getTimestamp(), vix.getTimestamp(), resultVix.getType()));
		} else if (resultVix.getType().equalsIgnoreCase("SELL")) {
			resultVix.setMaxLow(
					findMaxAndLowPrice(resultVix, resultVix.getTimestamp(), vix.getTimestamp(), resultVix.getType()));
		}

		
		resultVix.setPoints(calculatePoints(resultVix));
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
			resultVix.setExitPrice(currentPrice);
			resultVix.setExitTime(currentDate);
			resultVix.setResult(result);
		}
		resultVix.setActive(null);
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

	
}
