package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.OIDto;
import com.crumbs.trade.dto.StrategyDTO;
import com.crumbs.trade.entity.OI;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.OIRepo;
import com.crumbs.trade.repo.StrategyRepo;

import jakarta.transaction.Transactional;

@Service
public class OIService {
	Logger logger = LoggerFactory.getLogger(OIService.class);

	@Autowired
	AngelOne angelOne;

	@Autowired
	StrategyRepo strategyRepo;

	@Autowired
	TaskService taskService;

	@Autowired
	OIRepo oiRepo;

	@Autowired
	OISignalGenerator oiSignalGenerator;

	/*
	 * Get the option Chain of the given index/stock
	 */
	public void getOptionChain(String name) throws IOException, SmartAPIException {
	
		BigDecimal currentPrice;
		StrategyDTO strategy = taskService.convertStrategyToDto(strategyRepo.findByName(name));
		List<OIDto> optionChainList = new ArrayList<>();

		if (strategy != null) {
			currentPrice = getCurrentAdjustedPrice(strategy);
			if (currentPrice != null) {
				optionChainList = prepareOIStrikeData(currentPrice, strategy, name);

				if (optionChainList != null && !optionChainList.isEmpty()) {
					saveOIData(optionChainList, name);
				}
			}

		}

	}

	@Transactional
	public void saveOIData(List<OIDto> optionChainList, String name) {
		if (name.contains("NIFTY")) {
			name = "NIFTY";
		}
		if (name != null) {
			List<OI> oiList = oiRepo.findByName(name);

			if (oiList != null && !oiList.isEmpty()) {
				// Update
				updateOI(optionChainList);

			} else {
				// Create
				saveOI(optionChainList);
			}
		}

	}

	public String setNewValue(String newValue) {
		return Arrays.asList(newValue).toString();
	}

	@Transactional
	public void saveOI(List<OIDto> optionChainList) {

		optionChainList.stream().forEach(t -> {
			OI oi = new OI();
			oi.setStrikePrice(t.getStrikePrice());
			oi.setName(t.getName());
			oi.setCallLTP(setNewValue(t.getCallLtp()));
			oi.setCallOI(setNewValue(t.getCallOi()));
			oi.setTotalVolume(setNewValue(t.getTotalVolume()));
			oi.setCallOIChange(setNewValue(t.getCallOiChange()));
			oi.setPutLTP(setNewValue(t.getPutLtp()));
			oi.setPutOI(setNewValue(t.getPutOi()));
			oi.setPutOIChange(setNewValue(t.getPutOiChange()));
			oi.setExpiry(t.getExpiry());
			oi.setCallSignal("BASE");
			oi.setPutSignal("BASE");
			if (t.getSpot() != null) {
				updateSpot(t.getName());
				oi.setSpot(t.getSpot());
			}
			oiRepo.save(oi);
		}

		);
	}

	@Transactional
	public void updateSpot(String name) {
		OI oi = oiRepo.findBySpotAndName("Y", name);
		if (oi != null) {
			oi.setSpot(null);
			oiRepo.save(oi);
		}
	}

	@Transactional
	public void updateOI(List<OIDto> optionChainList) {

		optionChainList.stream().forEach(t -> {
			if (t.getStrikePrice() != null && t.getName() != null) {
				OI oi = oiRepo.findByStrikePriceAndName(t.getStrikePrice(), t.getName());
				if (oi == null) {
					oi = new OI();
				}
				oi.setCallLTP(getExistingValue(oi.getCallLTP(), t.getCallLtp()));
				oi.setCallOI(getExistingValue(oi.getCallOI(), t.getCallOi()));
				oi.setCallOIChange(getExistingValue(oi.getCallOIChange(), t.getCallOiChange()));
				oi.setPutLTP(getExistingValue(oi.getPutLTP(), t.getPutLtp()));
				oi.setPutOI(getExistingValue(oi.getPutOI(), t.getPutOi()));
				oi.setPutOIChange(getExistingValue(oi.getPutOIChange(), t.getPutOiChange()));
				oi.setPutTrend(getExistingValue(oi.getPutTrend(), getOIList(oi, "PUT", t.getPutLtp().split("=")[0])));
				oi.setCallTrend(
						getExistingValue(oi.getCallTrend(), getOIList(oi, "CALL", t.getCallLtp().split("=")[0])));
				oi.setStrikePrice(t.getStrikePrice());
				oi.setName(t.getName());
				// oi.setPutSignal(oiPrediction.getOISignal(convertStringToList(oi.getPutTrend())));
				// oi.setCallSignal(oiPrediction.getOISignal(convertStringToList(oi.getCallTrend())));
				oi.setPutSignal(getExistingValue(oi.getPutSignal(),
						oiSignalGenerator.getSignal(oi.getPutLTP(), oi.getPutOI(), "PE")));
				oi.setCallSignal(getExistingValue(oi.getCallSignal(),
						oiSignalGenerator.getSignal(oi.getCallLTP(), oi.getCallOI(), "CE")));
				oi.setExpiry(t.getExpiry());
				if (t.getSpot() != null) {
					updateSpot(t.getName());
					oi.setSpot(t.getSpot());
				}
				oiRepo.save(oi);
			}

		}

		);
	}

	public String getOIList(OI oi, String type, String time) {
		List<BigDecimal> oiList = new ArrayList<>();
		if (type.equalsIgnoreCase("CALL")) {
			oiList = formatData(oi.getCallOI());
		} else {
			oiList = formatData(oi.getPutOI());
		}

		return calculateOI(oiList, time);
	}

	public List<BigDecimal> formatData(String value) {
		List<BigDecimal> oiList = new ArrayList<>();
		List<String> valueList = Arrays.stream(value.replaceAll("\\[|\\]", "").split(",")).map(String::trim)
				.map(String::new).collect(Collectors.toList());
		if (valueList != null && !valueList.isEmpty()) {
			List<BigDecimal> bigDecimalList = valueList.stream().map(item -> item.split(" = ")) // Split each string at
																								// " = "
					.filter(parts -> parts.length == 2) // Ensure there are exactly two parts: timestamp and number
					.map(parts -> {
						try {
							// Try to parse the number part into BigDecimal
							return new BigDecimal(parts[1]);
						} catch (NumberFormatException e) {
							// Handle invalid number format by skipping this entry
							System.err.println("Invalid number format for value: " + parts[1]);
							return null; // Return null for invalid entries
						}
					}).filter(Objects::nonNull) // Remove any null values (invalid number format)
					.collect(Collectors.toList()); // Collect into a list of BigDecimal

			// Print the BigDecimal list
			// System.out.println(bigDecimalList);

			return bigDecimalList;
		}
		return null;
	}

	public String calculateOI(List<BigDecimal> prices, String time) {

		// Analyze the price trends with a confirmation of 3 consecutive points
		List<String> trendAnalysis = analyzePriceTrendsWith3PointConfirmation(prices, time);

		// Print out the prices along with their trend movements

		if (trendAnalysis.size() != 0) {
			return trendAnalysis.get(trendAnalysis.size() - 1);
		}
		return null;
	}

	public static List<String> analyzePriceTrendsWith3PointConfirmation(List<BigDecimal> prices, String time) {
		List<String> analysis = new ArrayList<>();

		if (prices.isEmpty()) {
			return analysis;
		}

		// Variables to track the trend and last 3 prices
		String currentTrend = "N/A"; // Initially, there's no trend
		List<BigDecimal> lastThreePrices = new ArrayList<>();

		for (int i = 0; i < prices.size(); i++) {
			BigDecimal currentPrice = prices.get(i);

			// Keep the last 3 prices in the list
			lastThreePrices.add(currentPrice);
			if (lastThreePrices.size() > 3) {
				lastThreePrices.remove(0); // Remove the oldest price to maintain the size of 3
			}

			// Once we have 3 prices, we can analyze the trend
			if (lastThreePrices.size() == 3) {
				// Calculate the trend direction of the last 3 prices
				int upCount = 0, downCount = 0;

				for (int j = 1; j < lastThreePrices.size(); j++) {
					if (lastThreePrices.get(j).compareTo(lastThreePrices.get(j - 1)) > 0) {
						upCount++; // Price went up
					} else if (lastThreePrices.get(j).compareTo(lastThreePrices.get(j - 1)) < 0) {
						downCount++; // Price went down
					}
				}

				// Decide the trend based on the last 3 points
				if (upCount >= 2) {
					if (!"Up".equals(currentTrend)) {
						currentTrend = "Up"; // Trend changed to Up
						analysis.add(time + " = Trend changed to Up");
					} else {
						analysis.add(time + " = Continuing Up");
					}
				} else if (downCount >= 2) {
					if (!"Down".equals(currentTrend)) {
						currentTrend = "Down"; // Trend changed to Down
						analysis.add(time + " = Trend changed to Down");
					} else {
						analysis.add(time + " = Continuing Down");
					}
				} else {
					// If the trend is mixed, consider it as No change
					if ("N/A".equals(currentTrend)) {
						currentTrend = "Same"; // First entry to start the trend tracking
						analysis.add("Trend is Same (Start of comparison)");
					} else {
						analysis.add(time + " = No significant trend change");
					}
				}
			} else {
				// For the first 2 points, we can't determine a trend yet
				if ("N/A".equals(currentTrend)) {
					currentTrend = "Same"; // First entry to start the trend tracking
					analysis.add(time + " = Trend is Same (Start of comparison)");
				} else {
					analysis.add(time + " = No significant trend change");
				}
			}
		}

		return analysis;
	}

	public String getExistingValue(String oldValue, String newValue) {
		if (oldValue != null && !oldValue.contains("null")) {
			List<String> valueList = Arrays.stream(oldValue.replaceAll("\\[|\\]", "").split(",")).map(String::trim)
					.map(String::new).collect(Collectors.toList());
			valueList.add(newValue);
			return valueList.toString();
		}
		return newValue;
	}

	public BigDecimal getCurrentAdjustedPrice(StrategyDTO strategy) {
		SmartConnect smartConnect = angelOne.signIn();
		JSONObject jsonObject = smartConnect.getLTP(strategy.getExchange(), strategy.getTradingsymbol(),
				strategy.getToken());
		BigDecimal currentPrice = new BigDecimal(String.valueOf(jsonObject.get("ltp")));
		if (currentPrice.intValue() % 100 != 0) {
			int remainder = 100 - currentPrice.intValue() % 100;
			if (remainder <= 50) {
				currentPrice = currentPrice.add(new BigDecimal(remainder));
			} else {
				currentPrice = currentPrice.subtract(new BigDecimal(currentPrice.intValue() % 100));
			}
		}
		return currentPrice;

	}

	public List<OIDto> prepareOIStrikeData(BigDecimal currentPrice, StrategyDTO strategy, String name)
			throws IOException, SmartAPIException {
		List<OIDto> optionChainList = new ArrayList<>();
		OIDto oiDto = new OIDto();
		if (name.equalsIgnoreCase("NIFTY_OI")) {
			name = "NIFTY";
		}
		try {

			// Add upper part
			for (int i = 10; i >= 1; i--) {
				TimeUnit.SECONDS.sleep(1);
				oiDto = new OIDto();
				oiDto.setStrikePrice(new BigDecimal(currentPrice.intValue() - (50 * i)));
				oiDto.setExpiry(strategy.getExpiry());
				prepareOIData(oiDto, strategy, name);
				if (oiDto.getStrikePrice() != null && oiDto.getName() != null) {
					optionChainList.add(oiDto);
				}

			}
			oiDto = new OIDto();
			oiDto.setStrikePrice(new BigDecimal(currentPrice.intValue()));
			oiDto.setSpot("Y");
			oiDto.setExpiry(strategy.getExpiry());
			prepareOIData(oiDto, strategy, name);
			if (oiDto.getStrikePrice() != null && oiDto.getName() != null) {
				optionChainList.add(oiDto);
			}
			// Lower Part
			for (int i = 1; i <= 10; i++) {
				TimeUnit.SECONDS.sleep(1);
				oiDto = new OIDto();
				oiDto.setStrikePrice(new BigDecimal(currentPrice.intValue() + (50 * i)));
				oiDto.setExpiry(strategy.getExpiry());
				prepareOIData(oiDto, strategy, name);
				if (oiDto.getStrikePrice() != null && oiDto.getName() != null) {
					optionChainList.add(oiDto);
				}
			}
		} catch (Exception e) {
			logger.error("Error during reading option chain " + e.getMessage());
		}

		return optionChainList;

	}

	public OIDto prepareOIData(OIDto oiDto, StrategyDTO strategy, String name) throws IOException, SmartAPIException {
		String CEType = null;
		String PEType = null;
		String expiry = null;

		CEType = strategy.getSymbol() + strategy.getSymbol1() + oiDto.getStrikePrice().intValue() + "CE";
		PEType = strategy.getSymbol() + strategy.getSymbol1() + oiDto.getStrikePrice().intValue() + "PE";
		expiry = (new SimpleDateFormat("yyyy")).format(new Date()) + strategy.getExpiry().substring(0, 2);
		Strategy strategyNifty = taskService.getChart(name, CEType);
		oiDto = getMarketData(strategyNifty.getName(), strategyNifty.getToken(), oiDto, "CE");
		strategyNifty = taskService.getChart(name, PEType);
		oiDto = getMarketData(strategyNifty.getName(), strategyNifty.getToken(), oiDto, "PE");
		return oiDto;
	}

	// Get OI Data for given name
	public OIDto getMarketData(String name, String token, OIDto oiDto, String type)
			throws SmartAPIException, IOException {
		String exchange = null;
		if (name.contains("NIFTY")) {
			exchange = "NFO";
		} else {
			exchange = "MCX";
		}
		SmartConnect smartConnect = AngelOne.signIn();
		JSONObject payload = new JSONObject();
		payload.put("mode", "FULL"); // You can change the mode as needed
		JSONObject exchangeTokens = new JSONObject();
		JSONArray nseTokens = new JSONArray();
		nseTokens.put(token);
		exchangeTokens.put(exchange, nseTokens);
		payload.put("exchangeTokens", exchangeTokens);
		JSONObject response = smartConnect.marketData(payload);
		if (response.get("fetched") != null) {
			JSONArray jsonArray = (JSONArray) response.get("fetched");
			JSONObject item = jsonArray.getJSONObject(0);
			if (type.equalsIgnoreCase("CE")) {
				oiDto.setCallOi(
						getFormatedInput(item.get("exchTradeTime").toString(), item.get("opnInterest").toString()));
				oiDto.setCallLtp(getFormatedInput(item.get("exchTradeTime").toString(), item.get("ltp").toString()));
			} else {
				oiDto.setPutOi(
						getFormatedInput(item.get("exchTradeTime").toString(), item.get("opnInterest").toString()));
				oiDto.setPutLtp(getFormatedInput(item.get("exchTradeTime").toString(), item.get("ltp").toString()));
			}
			oiDto.setTotalVolume(getFormatedInput(item.get("exchTradeTime").toString(), item.get("tradeVolume").toString()));
			oiDto.setName(name);
		}
		return oiDto;
	}

	public String getFormatedInput(String key, String value) {
		return key.concat(" = ").concat(value);
	}
}
