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
	
	// ================= VWAP STATE (OPTION 2) =================
	private final Map<String, BigDecimal> tpvMap = new HashMap<>();
	private final Map<String, BigDecimal> volMap = new HashMap<>();
	private LocalDate vwapDate = null;

	// =====================================================
	// MAIN ENTRY
	// =====================================================
	public void getCombineStraddlePremium(String name) {

		try {
			SmartConnect smartconnect = angelOne.signIn();

			Strategy strategy = strategyRepo.findByName(name);

			BigDecimal spotPrice = angelOneService.getcurrentPrice(smartconnect, strategy.getExchange(),
					strategy.getTradingsymbol(), strategy.getToken());

			BigDecimal atmStrike = getATMStrike(name, strategy, spotPrice);

			List<StraddlePremiumDto> strikeList = buildStraddleDtos(atmStrike, 50);

			strikeList = getAllTokenDetails(strikeList, strategy);

			strikeList = getPriceForAllTheStrikesBatch(strikeList, smartconnect);

			// ========= VWAP (INCREMENTAL) =========
			resetVwapIfNewDay();

			for (StraddlePremiumDto dto : strikeList) {

				if (dto.getCeToken() != null) {
					JSONArray ceCandle = fetchLatestOneMinuteCandle(smartconnect, "NFO", dto.getCeToken().getToken());

					if (ceCandle != null && !ceCandle.isEmpty()) {
						dto.setCeVwap(updateVwapIncremental(dto.getCeToken().getToken(), ceCandle));
					}
				}

				if (dto.getPeToken() != null) {
					JSONArray peCandle = fetchLatestOneMinuteCandle(smartconnect, "NFO", dto.getPeToken().getToken());

					if (peCandle != null && !peCandle.isEmpty()) {
						dto.setPeVwap(updateVwapIncremental(dto.getPeToken().getToken(), peCandle));
					}
				}
			}

			// Save to DB
			savePriceDetails(strikeList, strategy, spotPrice);

		} catch (Exception e) {
			logger.error("Error in getCombineStraddlePremium", e);
		}
	}

	// =====================================================
	// SAVE TO DB (UPDATED FOR NEW ENTITY)
	// =====================================================
	public int savePriceDetails(List<StraddlePremiumDto> strikeList, Strategy strategy, BigDecimal spotPrice) {

		int count = 0;
		LocalDateTime timestamp = LocalDateTime.now(ZoneId.of("Asia/Kolkata")).withNano(0);

		for (StraddlePremiumDto dto : strikeList) {

			StraddleIntraday entity = new StraddleIntraday();
			entity.setName(strategy.getName());
			entity.setExpiry(strategy.getExpiry());
			entity.setStrike(dto.getStrikePrice());
			entity.setTimestamp(timestamp);

			BigDecimal ce = dto.getCePrice() != null ? dto.getCePrice() : BigDecimal.ZERO;
			BigDecimal pe = dto.getPePrice() != null ? dto.getPePrice() : BigDecimal.ZERO;

			entity.setCePrice(ce);
			entity.setPePrice(pe);
			entity.setSpot(spotPrice);
			entity.setCeIV(dto.getCeIv());
			entity.setPeIV(dto.getPeIv());
			entity.setCeVwap(dto.getCeVwap());
			entity.setPeVwap(dto.getPeVwap());

			BigDecimal ceVwap = dto.getCeVwap() != null ? dto.getCeVwap() : BigDecimal.ZERO;
			BigDecimal peVwap = dto.getPeVwap() != null ? dto.getPeVwap() : BigDecimal.ZERO;
			BigDecimal combinedVwap = ceVwap.add(peVwap);
			entity.setCombinedVwap(combinedVwap);
			
			BigDecimal combinedPremium = ce.add(pe);
			entity.setCombinedPremium(combinedPremium);

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

			straddleIntradayRepo.save(entity);
			count++;
		}
		return count;
	}

	// =====================================================
	// ONE API CALL – FULL MODE
	// =====================================================
	public List<StraddlePremiumDto> getPriceForAllTheStrikesBatch(List<StraddlePremiumDto> strikeList,
			SmartConnect smartconnect) {

		try {
			List<String> tokens = new ArrayList<>();

			for (StraddlePremiumDto dto : strikeList) {
				if (dto.getCeToken() != null)
					tokens.add(dto.getCeToken().getToken());
				if (dto.getPeToken() != null)
					tokens.add(dto.getPeToken().getToken());
			}

			if (tokens.isEmpty())
				return strikeList;

			JSONObject payload = new JSONObject();
			payload.put("mode", "FULL");

			JSONObject map = new JSONObject();
			map.put("NFO", tokens);
			payload.put("exchangeTokens", map);

			JSONObject response = predictionService.callMarketDataWithRetry(smartconnect, payload);

			JSONArray fetched = response.getJSONArray("fetched");

			Map<String, BigDecimal> ltpMap = new HashMap<>();
			Map<String, BigDecimal> openMap = new HashMap<>();

			for (int i = 0; i < fetched.length(); i++) {
				JSONObject item = fetched.getJSONObject(i);
				ltpMap.put(item.getString("symbolToken"), item.getBigDecimal("ltp"));
				openMap.put(item.getString("symbolToken"), item.getBigDecimal("open"));
			}

			for (StraddlePremiumDto dto : strikeList) {

				if (dto.getCeToken() != null) {
					String t = dto.getCeToken().getToken();
					dto.setCePrice(ltpMap.get(t));
					dto.setCeOpenPrice(openMap.get(t));
				}

				if (dto.getPeToken() != null) {
					String t = dto.getPeToken().getToken();
					dto.setPePrice(ltpMap.get(t));
					dto.setPeOpenPrice(openMap.get(t));
				}
			}

		} catch (Exception | SmartAPIException e) {
			logger.error("Batch FULL error", e);
		}

		return strikeList;
	}

	// =====================================================
	// STRIKE BUILDING
	// =====================================================
	public List<StraddlePremiumDto> buildStraddleDtos(BigDecimal spot, int interval) {

		List<StraddlePremiumDto> list = new ArrayList<>();

		BigDecimal atm = spot.divide(BigDecimal.valueOf(interval), 0, RoundingMode.HALF_UP)
				.multiply(BigDecimal.valueOf(interval));

		for (int i = 5; i >= 1; i--) {
			list.add(createDto(atm.subtract(BigDecimal.valueOf(interval).multiply(BigDecimal.valueOf(i)))));
		}

		list.add(createDto(atm));

		for (int i = 1; i <= 5; i++) {
			list.add(createDto(atm.add(BigDecimal.valueOf(interval).multiply(BigDecimal.valueOf(i)))));
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

		// BigDecimal price1 = flatTradeService.getCurrentPrice(strategy.getExchange(),
		// strategy.getToken());
		if (price == null)
			return BigDecimal.ZERO;

		int nearest = chartService.findNearestMultiple(price.intValue(), 50);

		return BigDecimal.valueOf(nearest);
	}

	// =====================================================
	// TOKEN DETAILS
	// =====================================================
	public List<StraddlePremiumDto> getAllTokenDetails(List<StraddlePremiumDto> strikeList, Strategy strategy) {

		for (StraddlePremiumDto dto : strikeList) {

			int strike = dto.getStrikePrice().intValue();

			String ceSymbol = String.format("%s%s%dCE", strategy.getName(), strategy.getExpiry(), strike);

			String peSymbol = String.format("%s%s%dPE", strategy.getName(), strategy.getExpiry(), strike);

			Indexes ceIndex = indexesRepo.findByNameAndSymbol(strategy.getName(), ceSymbol);

			if (ceIndex != null) {
				Token t = new Token();
				t.setToken(ceIndex.getToken());
				t.setSymbol(ceIndex.getSymbol());
				t.setExch_seg(ceIndex.getExchange());
				dto.setCeToken(t);
			}

			Indexes peIndex = indexesRepo.findByNameAndSymbol(strategy.getName(), peSymbol);

			if (peIndex != null) {
				Token t = new Token();
				t.setToken(peIndex.getToken());
				t.setSymbol(peIndex.getSymbol());
				t.setExch_seg(peIndex.getExchange());
				dto.setPeToken(t);
			}
		}
		return strikeList;
	}

	// =====================================================
	// COMBINED CHART (TOTAL EXTRINSIC DERIVED)
	// =====================================================
	public CombinedChartResponse getStraddleCombinedChart(String name, String expiry, BigDecimal ceStrike,
			BigDecimal peStrike) {

		List<StraddleIntraday> ceRows = straddleIntradayRepo.getByStrike(name, expiry, ceStrike);

		List<StraddleIntraday> peRows = straddleIntradayRepo.getByStrike(name, expiry, peStrike);

		List<StraddleIntraday> spotRows = straddleIntradayRepo.getSpotHistory(name, expiry);

		Map<String, CombinedChartPoint> map = new TreeMap<>();
		ZoneId ist = ZoneId.of("Asia/Kolkata");

		// ---------------- CE rows ----------------
		for (StraddleIntraday r : ceRows) {

			String key = r.getTimestamp().atZone(ist).toOffsetDateTime().withNano(0).toString();

			CombinedChartPoint pt = map.computeIfAbsent(key, t -> new CombinedChartPoint(t, null, null, null, null,
					null, null, null, null, null, null, null, null,null));

			pt.setCe(r.getCePrice());
			pt.setCeOpen(r.getCeOpenPrice());
			pt.setCeExtrinsic(r.getCeExtrinsic());
			pt.setCeVwap(r.getCeVwap());
		}

		// ---------------- PE rows ----------------
		for (StraddleIntraday r : peRows) {

			String key = r.getTimestamp().atZone(ist).toOffsetDateTime().withNano(0).toString();

			CombinedChartPoint pt = map.computeIfAbsent(key, t -> new CombinedChartPoint(t, null, null, null, null,
					null, null, null, null, null, null, null, null,null));

			pt.setPe(r.getPePrice());
			pt.setPeOpen(r.getPeOpenPrice());
			pt.setPeExtrinsic(r.getPeExtrinsic());
			pt.setPeVwap(r.getPeVwap());
		}

		// ---------------- SPOT rows ----------------
		for (StraddleIntraday r : spotRows) {

			String key = r.getTimestamp().atZone(ist).toOffsetDateTime().withNano(0).toString();

			CombinedChartPoint pt = map.computeIfAbsent(key, t -> new CombinedChartPoint(t, null, null, null, null,
					null, null, null, null, null, null, null, null,null));

			pt.setSpot(r.getSpot());
		}

		// ---------------- Derived values ----------------
		for (CombinedChartPoint pt : map.values()) {

			// Combined Premium
			if (pt.getCe() != null && pt.getPe() != null) {
				pt.setCombinedPremium(pt.getCe().add(pt.getPe()));
				pt.setAvgPrice(pt.getCombinedPremium().divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP));
				
			}
			
			if (pt.getCeVwap() != null && pt.getPeVwap() != null) {
				pt.setCombinedVwap(pt.getCeVwap().add(pt.getPeVwap()));
			}

			// Combined Open
			if (pt.getCeOpen() != null && pt.getPeOpen() != null) {
				pt.setCombinedOpen(pt.getCeOpen().add(pt.getPeOpen()));
			}
		}

		CombinedChartResponse response = new CombinedChartResponse();
		response.getData().addAll(map.values());
		return response;
	}

	// =====================================================
	// VWAP RESET (ON DAY CHANGE)
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
	// FETCH LAST 1-MINUTE CANDLE ONLY
	// =====================================================
	private JSONArray fetchLatestOneMinuteCandle(SmartConnect smartConnect, String exchange, String token) throws ParseException {
		Map<Long, Candle> candleMap = Stream.of(1L,2L, 3L, 4L, 5L, 6L).map(id -> candleRepo.findById(id).orElse(null))
				.filter(Objects::nonNull).collect(Collectors.toMap(Candle::getId, c -> c));
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
		Candle candle = candleMap.get(1L); 
		SimpleDateFormat fromFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
         SimpleDateFormat toFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");

        //String fromDate = getDate("FROM", "NFO");
 		//String toDate = getDate("TO", "NFO");
		String fromDate = today + " 09:15";
		String toDate   = today + " 15:30";

		JSONObject req = new JSONObject();
		req.put("exchange", exchange);
		req.put("symboltoken", token);
		req.put("interval", "ONE_MINUTE");
		req.put("fromdate", fromDate);
		req.put("todate", toDate);

		return smartConnect.candleData(req);
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
	// INCREMENTAL VWAP UPDATE
	// =====================================================
	private BigDecimal updateVwapIncremental(String token, JSONArray candleArr) {

		JSONArray c = candleArr.getJSONArray(0);

		BigDecimal high = c.getBigDecimal(2);
		BigDecimal low = c.getBigDecimal(3);
		BigDecimal close = c.getBigDecimal(4);
		BigDecimal volume = c.getBigDecimal(5);

		BigDecimal tp = high.add(low).add(close).divide(BigDecimal.valueOf(3), 6, RoundingMode.HALF_UP);

		tpvMap.put(token, tpvMap.getOrDefault(token, BigDecimal.ZERO).add(tp.multiply(volume)));

		volMap.put(token, volMap.getOrDefault(token, BigDecimal.ZERO).add(volume));

		return tpvMap.get(token).divide(volMap.get(token), 2, RoundingMode.HALF_UP);
	}

}
