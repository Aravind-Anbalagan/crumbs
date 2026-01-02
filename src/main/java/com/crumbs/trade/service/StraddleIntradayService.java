package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.CombinedChartPoint;
import com.crumbs.trade.dto.CombinedChartResponse;
import com.crumbs.trade.dto.StraddlePremiumDto;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Candle;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.StraddleIntraday;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.CandleRepo;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.StraddleIntradayRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.utility.NSEWorkingDays;

@Service
public class StraddleIntradayService {

	private static final Logger logger = LoggerFactory.getLogger(StraddleIntradayService.class);

	@Autowired
	private PredictionService predictionService;
	@Autowired
	private ChartService chartService;
	@Autowired
	private AngelOneService angelOneService;
	@Autowired
	private AngelOne angelOne;
	@Autowired
	private IndexesRepo indexesRepo;
	@Autowired
	private StrategyRepo strategyRepo;
	@Autowired
	private StraddleIntradayRepo straddleIntradayRepo;
	@Autowired
	private FlatTradeService flatTradeService;
	@Autowired
	CandleRepo candleRepo;
	@Autowired
	TaskService taskService;
	
	// ================= VWAP STATE =================
	private final Map<String, BigDecimal> tpvMap = new HashMap<>();
	private final Map<String, BigDecimal> volMap = new HashMap<>();
	private LocalDate vwapDate = null;
	
	
	
	// ================= VWAP CONTROL =================
	private static final boolean ENABLE_VWAP = true; // Set to false to skip VWAP fetching
	private List<StraddlePremiumDto> strikeList = new ArrayList<>();
	
	
	
	// =====================================================
	// MAIN ENTRY - WITH COMPREHENSIVE VALIDATION
	// =====================================================
	public void getCombineStraddlePremium(String name) {

		try {
			SmartConnect smartconnect = angelOne.signIn();
			
			if (smartconnect == null) {
				logger.error("Failed to sign in to Angel One");
				return;
			}

			Strategy strategy = strategyRepo.findByName(name);
			
			if (strategy == null) {
				logger.error("Strategy not found: {}", name);
				return;
			}
			BigDecimal spotPrice = flatTradeService.getLtpFromFlatTrade(strategy.getExchange(), strategy.getToken());

			// VALIDATION: Check if spot price is valid
			if (spotPrice == null || spotPrice.compareTo(BigDecimal.ZERO) <= 0) {
				logger.error("Invalid spot price for {}: {}", name, spotPrice);
				return;
			}
			
			logger.info("Spot price for {}: {}", name, spotPrice);

			BigDecimal atmStrike = getATMStrike(name, strategy, spotPrice);
			
			// VALIDATION: Check ATM strike
			if (atmStrike == null || atmStrike.compareTo(BigDecimal.ZERO) <= 0) {
				logger.error("Invalid ATM strike for {}: {}", name, atmStrike);
				return;
			}
			
			logger.info("ATM strike for {}: {}", name, atmStrike);
			if(strikeList.isEmpty())
			{
				strikeList = buildStraddleDtos(atmStrike, 50);
			}
			

			strikeList = getAllTokenDetails(strikeList, strategy);
			
			// VALIDATION: Check if any tokens were found
			long validTokenCount = strikeList.stream()
				.filter(dto -> dto.getCeToken() != null || dto.getPeToken() != null)
				.count();
				
			if (validTokenCount == 0) {
				logger.error("No valid tokens found for strategy: {}", name);
				return;
			}
			
			logger.info("Found {} strikes with valid tokens out of {}", 
				validTokenCount, strikeList.size());

			strikeList = getPriceForAllTheStrikesBatch(strikeList, smartconnect,strategy.getExchange());
			
			// VALIDATION: Check if prices were fetched
			long validPriceCount = strikeList.stream()
				.filter(dto -> 
					(dto.getCePrice() != null && dto.getCePrice().compareTo(BigDecimal.ZERO) > 0) ||
					(dto.getPePrice() != null && dto.getPePrice().compareTo(BigDecimal.ZERO) > 0)
				)
				.count();
				
			if (validPriceCount == 0) {
				logger.error("No valid prices fetched for strategy: {}", name);
				return;
			}
			
			logger.info("Successfully fetched prices for {} strikes", validPriceCount);

			
			
			// ========= VWAP (INCREMENTAL) - OPTIMIZED =========
			resetVwapIfNewDay();

			// OPTIMIZATION: Only fetch VWAP for strikes with valid prices
			List<StraddlePremiumDto> strikesWithPrices = strikeList.stream()
				.filter(dto -> 
					(dto.getCePrice() != null && dto.getCePrice().compareTo(BigDecimal.ZERO) > 0) ||
					(dto.getPePrice() != null && dto.getPePrice().compareTo(BigDecimal.ZERO) > 0)
				)
				.collect(Collectors.toList());
			
			logger.info("Fetching VWAP for {} strikes with valid prices (out of {} total)", 
				strikesWithPrices.size(), strikeList.size());

			for (StraddlePremiumDto dto : strikesWithPrices) {
				
				// Only fetch VWAP if token exists AND price is valid
				if (dto.getCeToken() != null && dto.getCePrice() != null && 
					dto.getCePrice().compareTo(BigDecimal.ZERO) > 0) {
					try {
						JSONArray ceCandle = fetchLatestOneMinuteCandle(
							smartconnect, strategy.getExchange(), dto.getCeToken().getToken()
						);

						if (ceCandle != null && !ceCandle.isEmpty()) {
							dto.setCeVwap(updateVwapIncremental(
								dto.getCeToken().getToken(), ceCandle
							));
						}
					} catch (Exception e) {
						logger.warn("Failed to fetch CE VWAP for strike {}: {}", 
							dto.getStrikePrice(), e.getMessage());
					}
				}

				if (dto.getPeToken() != null && dto.getPePrice() != null && 
					dto.getPePrice().compareTo(BigDecimal.ZERO) > 0) {
					try {
						JSONArray peCandle = fetchLatestOneMinuteCandle(
							smartconnect, "NFO", dto.getPeToken().getToken()
						);

						if (peCandle != null && !peCandle.isEmpty()) {
							dto.setPeVwap(updateVwapIncremental(
								dto.getPeToken().getToken(), peCandle
							));
						}
					} catch (Exception e) {
						logger.warn("Failed to fetch PE VWAP for strike {}: {}", 
							dto.getStrikePrice(), e.getMessage());
					}
				}
			}

			// Save to DB - only valid records
			int savedCount = savePriceDetails(strikeList, strategy, spotPrice);
			logger.info("Saved {} records to database for {}", savedCount, name);

		} catch (Exception e) {
			logger.error("Error in getCombineStraddlePremium for {}", name, e);
		}
	}

	
	// =====================================================
	// ✅ EVENT-BASED CE / PE CROSSOVER (1-MINUTE ONLY)
	// =====================================================
	private void detectCrossoverEvent(
	        StraddlePremiumDto dto,
	        String name,
	        LocalDateTime currentTs
	) {
	    // 1️⃣ Always reset (EVENT, not STATE)
	    dto.setCeCrossoverAbove(false);
	    dto.setPeCrossoverAbove(false);

	    // 2️⃣ Fetch last TWO saved rows for this strike
	    List<StraddleIntraday> lastTwo =
	        straddleIntradayRepo.findLastTwo(
	            name,
	            dto.getStrikePrice(),
	            PageRequest.of(0, 2)
	        );

	    if (lastTwo.size() < 2) return;

	    // latest saved candle (t-1)
	    StraddleIntraday prev = lastTwo.get(0);

	    // candle before that (t-2)
	    StraddleIntraday beforePrev = lastTwo.get(1);

	    // 3️⃣ Time-continuity guard (avoid DB gaps / scheduler skips)
	    long gapSeconds = Math.abs(
	        java.time.Duration.between(
	            prev.getTimestamp(),
	            currentTs
	        ).getSeconds()
	    );

	    // allow only ~1–2 minute gap
	    if (gapSeconds > 120) {
	        return;
	    }

	    // 4️⃣ Prevent duplicate same-direction crossover
	    // (arrow must not repeat on consecutive candles)
	    if (Boolean.TRUE.equals(prev.getCeCrossoverAbove()) ||
	        Boolean.TRUE.equals(prev.getPeCrossoverAbove())) {
	        return;
	    }

	    // 5️⃣ Price comparison (REAL crossover check)
	    BigDecimal prevCe = beforePrev.getCePrice();
	    BigDecimal prevPe = beforePrev.getPePrice();
	    BigDecimal currCe = dto.getCePrice();
	    BigDecimal currPe = dto.getPePrice();

	    if (prevCe == null || prevPe == null ||
	        currCe == null || currPe == null)
	        return;

	    // 🔺 CE crossed ABOVE PE (ONE candle only)
	    if (prevCe.compareTo(prevPe) <= 0 &&
	        currCe.compareTo(currPe) > 0) {

	        dto.setCeCrossoverAbove(true);
	        return;
	    }

	    // 🔻 PE crossed ABOVE CE (ONE candle only)
	    if (prevPe.compareTo(prevCe) <= 0 &&
	        currPe.compareTo(currCe) > 0) {

	        dto.setPeCrossoverAbove(true);
	    }
	}



	

	// =====================================================
	// SAVE TO DB - WITH VALIDATION
	// =====================================================
	public int savePriceDetails(
		List<StraddlePremiumDto> strikeList, 
		Strategy strategy, 
		BigDecimal spotPrice
	) {

		int count = 0;
		LocalDateTime timestamp = LocalDateTime.now(ZoneId.of("Asia/Kolkata")).withNano(0);

		for (StraddlePremiumDto dto : strikeList) {
			
			// VALIDATION: Skip if both tokens are missing
			if (dto.getCeToken() == null && dto.getPeToken() == null) {
				logger.debug("Skipping strike {} - no tokens found", dto.getStrikePrice());
				continue;
			}
			
			// VALIDATION: Skip if both prices are zero/null
			BigDecimal ce = dto.getCePrice() != null ? dto.getCePrice() : BigDecimal.ZERO;
			BigDecimal pe = dto.getPePrice() != null ? dto.getPePrice() : BigDecimal.ZERO;
			
			if (ce.compareTo(BigDecimal.ZERO) <= 0 && pe.compareTo(BigDecimal.ZERO) <= 0) {
				logger.debug("Skipping strike {} - no valid prices (CE: {}, PE: {})", 
					dto.getStrikePrice(), ce, pe);
				continue;
			}

			// VALIDATION: Check strike price is valid
			if (dto.getStrikePrice() == null || dto.getStrikePrice().compareTo(BigDecimal.ZERO) <= 0) {
				logger.warn("Invalid strike price: {}", dto.getStrikePrice());
				continue;
			}

			StraddleIntraday entity = new StraddleIntraday();
			entity.setName(strategy.getName());
			entity.setExpiry(strategy.getExpiry());
			entity.setStrike(dto.getStrikePrice());
			entity.setTimestamp(timestamp);

			entity.setCePrice(ce);
			entity.setPePrice(pe);
			entity.setSpot(spotPrice);
			entity.setCeIV(dto.getCeIv());
			entity.setPeIV(dto.getPeIv());
			entity.setCeVwap(dto.getCeVwap());
			entity.setPeVwap(dto.getPeVwap());
			
			// NEW: volume + OI
			entity.setCeVolume(dto.getCeVolume());
			entity.setPeVolume(dto.getPeVolume());
			entity.setCeOi(dto.getCeOI());
			entity.setPeOi(dto.getPeOI());
			
			// ✅ EVENT-BASED CROSSOVER (DB aligned)
			detectCrossoverEvent(dto, strategy.getName(), timestamp);
			// NEW: Crossover flags
			entity.setCeCrossoverAbove(dto.isCeCrossoverAbove());
			entity.setPeCrossoverAbove(dto.isPeCrossoverAbove());

			BigDecimal ceVwap = dto.getCeVwap() != null ? dto.getCeVwap() : BigDecimal.ZERO;
			BigDecimal peVwap = dto.getPeVwap() != null ? dto.getPeVwap() : BigDecimal.ZERO;
			//System.out.println("ceVwap : " + ceVwap + " Strike: "+ entity.getStrike());
			//System.out.println("peVwap : " + peVwap + " Strike: "+ entity.getStrike());
			BigDecimal combinedVwap = ceVwap.add(peVwap);
			entity.setCombinedVwap(combinedVwap);
			
			BigDecimal combinedPremium = ce.add(pe);
			entity.setCombinedPremium(combinedPremium);

			// Calculate intrinsic values correctly
			BigDecimal ceIntrinsic = spotPrice.subtract(dto.getStrikePrice()).max(BigDecimal.ZERO);
			BigDecimal peIntrinsic = dto.getStrikePrice().subtract(spotPrice).max(BigDecimal.ZERO);

			entity.setCeIntrinsic(ceIntrinsic);
			entity.setPeIntrinsic(peIntrinsic);
			entity.setCeExtrinsic(ce.subtract(ceIntrinsic));
			entity.setPeExtrinsic(pe.subtract(peIntrinsic));

			entity.setCeOpenPrice(dto.getCeOpenPrice());
			entity.setPeOpenPrice(dto.getPeOpenPrice());

			if (dto.getCeOpenPrice() != null && dto.getPeOpenPrice() != null) {
				entity.setCombinedOpenPrice(dto.getCeOpenPrice().add(dto.getPeOpenPrice()));
			}

			entity.setAvgPrice(combinedPremium.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP));

			try {
				straddleIntradayRepo.save(entity);
				count++;
			} catch (Exception e) {
				logger.error("Failed to save record for strike {}: {}", 
					dto.getStrikePrice(), e.getMessage());
			}
		}
		
		return count;
	}

	// =====================================================
	// BATCH PRICE FETCH - WITH ERROR HANDLING
	// =====================================================
	public List<StraddlePremiumDto> getPriceForAllTheStrikesBatch(
		List<StraddlePremiumDto> strikeList,
		SmartConnect smartconnect, String exchange
	) {

		try {
			List<String> tokens = new ArrayList<>();

			for (StraddlePremiumDto dto : strikeList) {
				if (dto.getCeToken() != null) {
					tokens.add(dto.getCeToken().getToken());
				}
				if (dto.getPeToken() != null) {
					tokens.add(dto.getPeToken().getToken());
				}
			}

			if (tokens.isEmpty()) {
				logger.warn("No tokens to fetch prices for");
				return strikeList;
			}
			
			logger.info("Fetching prices for {} tokens", tokens.size());

			JSONObject payload = new JSONObject();
			payload.put("mode", "FULL");

			JSONObject map = new JSONObject();
			map.put(exchange, tokens);
			payload.put("exchangeTokens", map);

			JSONObject response = predictionService.callMarketDataWithRetry(smartconnect, payload);
			
			if (response == null) {
				logger.error("Null response from market data API");
				return strikeList;
			}

			JSONArray fetched = response.optJSONArray("fetched");
			
			if (fetched == null || fetched.length() == 0) {
				logger.error("No data fetched from market API");
				return strikeList;
			}
			
			logger.info("Received {} price records from API", fetched.length());

			Map<String, BigDecimal> ltpMap = new HashMap<>();
			Map<String, BigDecimal> openMap = new HashMap<>();
			Map<String, BigDecimal> oIMap = new HashMap<>();
			Map<String, BigDecimal> volumeMap = new HashMap<>();
			
			for (int i = 0; i < fetched.length(); i++) {
				JSONObject item = fetched.getJSONObject(i);
				String token = item.optString("symbolToken", null);
				
				if (token != null) {
					ltpMap.put(token, item.optBigDecimal("ltp", BigDecimal.ZERO));
					openMap.put(token, item.optBigDecimal("open", BigDecimal.ZERO));
					oIMap.put(token, item.optBigDecimal("opnInterest", BigDecimal.ZERO));
					volumeMap.put(token, item.optBigDecimal("tradeVolume", BigDecimal.ZERO));
				}
			}

			for (StraddlePremiumDto dto : strikeList) {

				if (dto.getCeToken() != null) {
					String t = dto.getCeToken().getToken();
					BigDecimal ltp = ltpMap.get(t);
					BigDecimal open = openMap.get(t);
					BigDecimal oi = oIMap.get(t);
					BigDecimal volume = volumeMap.get(t);
					
					dto.setCePrice(ltp);
					dto.setCeOpenPrice(open);
					dto.setCeOI(oi);
					dto.setCeVolume(volume);
					
					if (ltp == null || ltp.compareTo(BigDecimal.ZERO) <= 0) {
						logger.warn("No valid CE price for token {}, strike {}", 
							t, dto.getStrikePrice());
					}
				}

				if (dto.getPeToken() != null) {
					String t = dto.getPeToken().getToken();
					BigDecimal ltp = ltpMap.get(t);
					BigDecimal open = openMap.get(t);
					BigDecimal oi = oIMap.get(t);
					BigDecimal volume = volumeMap.get(t);
							
					dto.setPePrice(ltp);
					dto.setPeOpenPrice(open);
					dto.setPeOI(oi);
					dto.setPeVolume(volume);
					
					if (ltp == null || ltp.compareTo(BigDecimal.ZERO) <= 0) {
						logger.warn("No valid PE price for token {}, strike {}", 
							t, dto.getStrikePrice());
					}
				}
			}

		} catch (Exception | SmartAPIException e) {
			logger.error("Batch FULL error", e);
		}

		return strikeList;
	}

	// =====================================================
	// STRIKE BUILDING - FROM ATM (STATIC ±500 RANGE)
	// =====================================================
	public List<StraddlePremiumDto> buildStraddleDtos(BigDecimal atmStrike, int interval) {

	    List<StraddlePremiumDto> list = new ArrayList<>();

	    BigDecimal step  = BigDecimal.valueOf(interval);
	    BigDecimal range = BigDecimal.valueOf(300);   // ±500

	    BigDecimal start = atmStrike.subtract(range);
	    BigDecimal end   = atmStrike.add(range);

	    for (BigDecimal strike = start;
	         strike.compareTo(end) <= 0;
	         strike = strike.add(step)) {

	        list.add(createDto(strike));
	    }

	    return list;
	}



	private StraddlePremiumDto createDto(BigDecimal strike) {
		StraddlePremiumDto dto = new StraddlePremiumDto();
		dto.setStrikePrice(strike);
		return dto;
	}

	// =====================================================
	// ATM STRIKE
	// =====================================================
	public BigDecimal getATMStrike(String name, Strategy strategy, BigDecimal price) {

		SmartConnect smartconnect = angelOne.signIn();

		if (price == null)
			return BigDecimal.ZERO;

		int nearest = chartService.findNearestMultiple(price.intValue(), 50);

		return BigDecimal.valueOf(nearest);
	}

	// =====================================================
	// TOKEN DETAILS - WITH BETTER LOGGING
	// =====================================================
	public List<StraddlePremiumDto> getAllTokenDetails(
		List<StraddlePremiumDto> strikeList, 
		Strategy strategy
	) {

		logger.info("Fetching tokens for strategy: {}, expiry: {}", 
			strategy.getName(), strategy.getExpiry());

		for (StraddlePremiumDto dto : strikeList) {

			int strike = dto.getStrikePrice().intValue();

			String ceSymbol = String.format("%s%s%dCE", 
				strategy.getName(), strategy.getExpiry(), strike);

			String peSymbol = String.format("%s%s%dPE", 
				strategy.getName(), strategy.getExpiry(), strike);

			// Fetch CE token
			Indexes ceIndex = indexesRepo.findByNameAndSymbol(
				strategy.getName(), ceSymbol
			);

			if (ceIndex != null) {
				Token t = new Token();
				t.setToken(ceIndex.getToken());
				t.setSymbol(ceIndex.getSymbol());
				t.setExch_seg(ceIndex.getExchange());
				dto.setCeToken(t);
				logger.debug("Found CE token for {}: {}", ceSymbol, t.getToken());
			} else {
				logger.warn("CE token NOT found for symbol: {}", ceSymbol);
			}

			// Fetch PE token
			Indexes peIndex = indexesRepo.findByNameAndSymbol(
				strategy.getName(), peSymbol
			);

			if (peIndex != null) {
				Token t = new Token();
				t.setToken(peIndex.getToken());
				t.setSymbol(peIndex.getSymbol());
				t.setExch_seg(peIndex.getExchange());
				dto.setPeToken(t);
				logger.debug("Found PE token for {}: {}", peSymbol, t.getToken());
			} else {
				logger.warn("PE token NOT found for symbol: {}", peSymbol);
			}
		}
		
		return strikeList;
	}

	// =====================================================
	// COMBINED CHART
	// =====================================================
	public CombinedChartResponse getStraddleCombinedChart(
		String name, 
		String expiry, 
		BigDecimal ceStrike,
		BigDecimal peStrike
	) {

		List<StraddleIntraday> ceRows = straddleIntradayRepo.getByStrike(name, expiry, ceStrike);
		List<StraddleIntraday> peRows = straddleIntradayRepo.getByStrike(name, expiry, peStrike);
		List<StraddleIntraday> spotRows = straddleIntradayRepo.getSpotHistory(name, expiry);

		Map<String, CombinedChartPoint> map = new TreeMap<>();
		ZoneId ist = ZoneId.of("Asia/Kolkata");

		// CE rows
		for (StraddleIntraday r : ceRows) {
			String key = r.getTimestamp().atZone(ist).toOffsetDateTime().withNano(0).toString();
			CombinedChartPoint pt = map.computeIfAbsent(key, t -> new CombinedChartPoint(
				t, null, null, null, null, null, null, null, null, null, null, null, null, null
			));
			pt.setCe(r.getCePrice());
			pt.setCeOpen(r.getCeOpenPrice());
			pt.setCeExtrinsic(r.getCeExtrinsic());
			pt.setCeVwap(r.getCeVwap());
		}

		// PE rows
		for (StraddleIntraday r : peRows) {
			String key = r.getTimestamp().atZone(ist).toOffsetDateTime().withNano(0).toString();
			CombinedChartPoint pt = map.computeIfAbsent(key, t -> new CombinedChartPoint(
				t, null, null, null, null, null, null, null, null, null, null, null, null, null
			));
			pt.setPe(r.getPePrice());
			pt.setPeOpen(r.getPeOpenPrice());
			pt.setPeExtrinsic(r.getPeExtrinsic());
			pt.setPeVwap(r.getPeVwap());
		}

		// SPOT rows
		for (StraddleIntraday r : spotRows) {
			String key = r.getTimestamp().atZone(ist).toOffsetDateTime().withNano(0).toString();
			CombinedChartPoint pt = map.computeIfAbsent(key, t -> new CombinedChartPoint(
				t, null, null, null, null, null, null, null, null, null, null, null, null, null
			));
			pt.setSpot(r.getSpot());
		}

		// Derived values
		for (CombinedChartPoint pt : map.values()) {
			if (pt.getCe() != null && pt.getPe() != null) {
				pt.setCombinedPremium(pt.getCe().add(pt.getPe()));
				pt.setAvgPrice(pt.getCombinedPremium().divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP));
			}
			
			if (pt.getCeVwap() != null && pt.getPeVwap() != null) {
				pt.setCombinedVwap(pt.getCeVwap().add(pt.getPeVwap()));
			}

			if (pt.getCeOpen() != null && pt.getPeOpen() != null) {
				pt.setCombinedOpen(pt.getCeOpen().add(pt.getPeOpen()));
			}
		}

		CombinedChartResponse response = new CombinedChartResponse();
		response.getData().addAll(map.values());
		return response;
	}

	// =====================================================
	// VWAP RESET
	// =====================================================
	private void resetVwapIfNewDay() {
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

		if (vwapDate == null || !vwapDate.equals(today)) {
			tpvMap.clear();
			volMap.clear();
			vwapDate = today;
			logger.info("VWAP reset for new trading day: {}", today);
		}
	}

	// =====================================================
	// FETCH CANDLES - WITH RATE LIMITING AND RETRY LOGIC
	// =====================================================
	private static final long CANDLE_API_DELAY_MS = 1000; // 1 second delay (increased from 500ms to avoid rate limits)
	private long lastCandleApiCall = 0;
	private static final int MAX_RETRY_ATTEMPTS = 5;
	private static final long INITIAL_RETRY_DELAY_MS = 2000; // Start with 2 seconds
	
	private JSONArray fetchLatestOneMinuteCandle(
		SmartConnect smartConnect, 
		String exchange, 
		String token
	) throws ParseException {
		
		int attempt = 0;
		long retryDelay = INITIAL_RETRY_DELAY_MS;
		
		while (attempt < MAX_RETRY_ATTEMPTS) {
			try {
				// Rate limiting: wait if needed
				long now = System.currentTimeMillis();
				long timeSinceLastCall = now - lastCandleApiCall;
				
				if (timeSinceLastCall < CANDLE_API_DELAY_MS) {
					long waitTime = CANDLE_API_DELAY_MS - timeSinceLastCall;
					logger.debug("Rate limiting: waiting {}ms before candle API call", waitTime);
					Thread.sleep(waitTime);
				}
				
				LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
				String fromDate = today + " 09:15";
				String toDate   = today + " 15:30";

				JSONObject req = new JSONObject();
				req.put("exchange", exchange);
				req.put("symboltoken", token);
				req.put("interval", "ONE_MINUTE");
				req.put("fromdate", fromDate);
				req.put("todate", toDate);

				JSONArray result = smartConnect.candleData(req);
				lastCandleApiCall = System.currentTimeMillis();
				
				// Check if result is null
				if (result == null) {
					attempt++;
					logger.warn("Candle data returned NULL for token {} (attempt {}/{})", 
						token, attempt, MAX_RETRY_ATTEMPTS);
					
					if (attempt < MAX_RETRY_ATTEMPTS) {
						logger.info("Retrying after {}ms...", retryDelay);
						Thread.sleep(retryDelay);
						retryDelay *= 2; // Exponential backoff: 2s, 4s, 8s, 16s, 32s
					} else {
						logger.error("Max retry attempts reached for token {}. Giving up.", token);
						return null;
					}
				} else {
					// Success - log if it took retries
					if (attempt > 0) {
						logger.info("Successfully fetched candle data for token {} after {} retries", 
							token, attempt);
					}
					return result;
				}
				
			} catch (InterruptedException e) {
				logger.warn("Thread interrupted during candle fetch/retry", e);
				Thread.currentThread().interrupt();
				return null;
			} catch (Exception e) {
				attempt++;
				logger.error("Error fetching candle data for token {} (attempt {}/{}): {}", 
					token, attempt, MAX_RETRY_ATTEMPTS, e.getMessage());
				
				if (attempt < MAX_RETRY_ATTEMPTS) {
					try {
						logger.info("Retrying after {}ms due to exception...", retryDelay);
						Thread.sleep(retryDelay);
						retryDelay *= 2;
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						return null;
					}
				} else {
					logger.error("Max retry attempts reached for token {} after exceptions", token);
					return null;
				}
			}
		}
		
		return null;
	}

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
	
	// =====================================================
	// INCREMENTAL VWAP UPDATE - FIXED
	// =====================================================
	private BigDecimal updateVwapIncremental(String token, JSONArray candleArr) {

		// Process ALL candles, not just the first one
		for (int i = 0; i < candleArr.length(); i++) {
			JSONArray c = candleArr.getJSONArray(i);

			BigDecimal high = c.getBigDecimal(2);
			BigDecimal low = c.getBigDecimal(3);
			BigDecimal close = c.getBigDecimal(4);
			BigDecimal volume = c.getBigDecimal(5);

			// Skip candles with zero volume
			if (volume.compareTo(BigDecimal.ZERO) == 0) {
				continue;
			}

			BigDecimal tp = high.add(low).add(close)
				.divide(BigDecimal.valueOf(3), 6, RoundingMode.HALF_UP);

			tpvMap.put(token, tpvMap.getOrDefault(token, BigDecimal.ZERO).add(tp.multiply(volume)));
			volMap.put(token, volMap.getOrDefault(token, BigDecimal.ZERO).add(volume));
		}

		// Return VWAP, handling case where volume is zero
		BigDecimal totalVolume = volMap.get(token);
		if (totalVolume == null || totalVolume.compareTo(BigDecimal.ZERO) == 0) {
			return BigDecimal.ZERO;
		}

		return tpvMap.get(token).divide(totalVolume, 2, RoundingMode.HALF_UP);
	}

	// =====================================================
	// DEBUGGING METHOD - CORRECTED VERSION
	// =====================================================
	public void testTokenFetching(String strategyName) {
		Strategy strategy = strategyRepo.findByName(strategyName);
		
		if (strategy == null) {
			logger.error("Strategy not found: {}", strategyName);
			return;
		}
		
		logger.info("=== Token Fetching Test ===");
		logger.info("Strategy Name: {}", strategy.getName());
		logger.info("Strategy Expiry: {}", strategy.getExpiry());
		
		// Test with multiple strikes
		int[] testStrikes = {23900, 23950, 24000, 24050, 24100};
		int foundCount = 0;
		
		for (int strike : testStrikes) {
			String ceSymbol = String.format("%s%s%dCE", 
				strategy.getName(), strategy.getExpiry(), strike);
			String peSymbol = String.format("%s%s%dPE", 
				strategy.getName(), strategy.getExpiry(), strike);
			
			Indexes ceIndex = indexesRepo.findByNameAndSymbol(strategy.getName(), ceSymbol);
			Indexes peIndex = indexesRepo.findByNameAndSymbol(strategy.getName(), peSymbol);
			
			if (ceIndex != null || peIndex != null) {
				foundCount++;
				logger.info("Strike {}: CE={} ({}), PE={} ({})", 
					strike,
					ceIndex != null ? "FOUND" : "NOT FOUND",
					ceIndex != null ? ceIndex.getToken() : "N/A",
					peIndex != null ? "FOUND" : "NOT FOUND",
					peIndex != null ? peIndex.getToken() : "N/A"
				);
			} else {
				logger.warn("Strike {}: CE=NOT FOUND, PE=NOT FOUND", strike);
				logger.info("  Expected CE: {}", ceSymbol);
				logger.info("  Expected PE: {}", peSymbol);
			}
		}
		
		if (foundCount == 0) {
			logger.error("NO TOKENS FOUND! Check symbol format in database.");
			logger.info("Your code generates symbols like: {}23DEC2524000CE", strategy.getName());
			logger.info("Check your database for actual symbol format.");
		} else {
			logger.info("Found tokens for {} out of {} test strikes", foundCount, testStrikes.length);
		}
	}
	
	// =====================================================
	// ALTERNATIVE: Test with specific strike
	// =====================================================
	public void testSingleStrike(String strategyName, int strike) {
		Strategy strategy = strategyRepo.findByName(strategyName);
		
		if (strategy == null) {
			logger.error("Strategy not found: {}", strategyName);
			return;
		}
		
		String ceSymbol = String.format("%s%s%dCE", 
			strategy.getName(), strategy.getExpiry(), strike);
		String peSymbol = String.format("%s%s%dPE", 
			strategy.getName(), strategy.getExpiry(), strike);
		
		logger.info("Testing Strike: {}", strike);
		logger.info("CE Symbol: {}", ceSymbol);
		logger.info("PE Symbol: {}", peSymbol);
		
		Indexes ceIndex = indexesRepo.findByNameAndSymbol(strategy.getName(), ceSymbol);
		Indexes peIndex = indexesRepo.findByNameAndSymbol(strategy.getName(), peSymbol);
		
		if (ceIndex != null) {
			logger.info("✓ CE FOUND - Token: {}, Exchange: {}", 
				ceIndex.getToken(), ceIndex.getExchange());
		} else {
			logger.error("✗ CE NOT FOUND");
			
			// Try common variations
			String[] variations = {
				String.format("%s %s %d CE", strategy.getName(), strategy.getExpiry(), strike),
				String.format("%s%s %dCE", strategy.getName(), strategy.getExpiry(), strike),
				String.format("%s-%s-%dCE", strategy.getName(), strategy.getExpiry(), strike)
			};
			
			logger.info("Trying variations:");
			for (String var : variations) {
				Indexes varIndex = indexesRepo.findByNameAndSymbol(strategy.getName(), var);
				if (varIndex != null) {
					logger.info("  ✓ FOUND: {} -> Token: {}", var, varIndex.getToken());
					break;
				} else {
					logger.info("  ✗ Not found: {}", var);
				}
			}
		}
		
		if (peIndex != null) {
			logger.info("✓ PE FOUND - Token: {}, Exchange: {}", 
				peIndex.getToken(), peIndex.getExchange());
		} else {
			logger.error("✗ PE NOT FOUND");
		}
	}
}