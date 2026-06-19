package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.broker.Samco;
import com.crumbs.trade.dto.CombinedChartPoint;
import com.crumbs.trade.dto.CombinedChartResponse;
import com.crumbs.trade.dto.StraddlePremiumDto;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.StraddleIntraday;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.CandleRepo;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.StraddleIntradayRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.utility.AlertType;
import com.crumbs.trade.utility.ConditionalLogger;
import com.crumbs.trade.utility.NSEWorkingDays;
import com.crumbs.trade.utility.SamcoSessionManager;

@Service
public class StraddleIntradayService {

	  // NEW CODE:
    private static final Logger baseLogger = LoggerFactory.getLogger(StraddleIntradayService.class);
    private final ConditionalLogger logger = new ConditionalLogger(baseLogger);
	//Logger logger = LoggerFactory.getLogger(StraddleIntradayService.class);
	
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
	@Autowired
	TelegramService telegramService;
	@Autowired
	Samco samco;
	@Autowired PriceUtilService priceUtilService;
	@Autowired
	private SamcoSessionManager sessionManager;
	// ================= VWAP STATE =================
	private final Map<String, BigDecimal> tpvMap = new HashMap<>();
	private final Map<String, BigDecimal> volMap = new HashMap<>();
	private LocalDate vwapDate = null;
	
	// ================= PREVIOUS DAY HIGH/LOW CACHE =================
	// Strategy-specific cache: Map<StrategyName, Map<Token, BigDecimal>>
	private final Map<String, Map<String, BigDecimal>> prevHighMap = new HashMap<>();
	private final Map<String, Map<String, BigDecimal>> prevLowMap = new HashMap<>();
	public LocalDate prevDayDataDate = null;
	// Tracks the ISO String timestamp of the last processed 1-minute candle per token
	private final Map<String, String> lastProcessedTimestamp = new HashMap<>();
	
	
	// ================= VWAP CONTROL =================
	private static final boolean ENABLE_VWAP = true; // Set to false to skip VWAP fetching
	// name → strike list (built once)
	private final Map<String, List<StraddlePremiumDto>> strikeListCache = new HashMap<>();

	// name → date when strikes were built
	private final Map<String, LocalDate> strikeInitDate = new HashMap<>();
	private final Map<String, Map<String, BigDecimal>> prevCloseMap = new HashMap<>();

	// ================= ALERT DEDUP =================
	private final Map<String, LocalDateTime> sentAlertKeys = new HashMap<>();
	private static final int ALERT_COOLDOWN_MINUTES = 5;
	
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

		        String session = sessionManager.getSession();
		        BigDecimal spotPrice = null;
		       
		        if ("NIFTY".equalsIgnoreCase(name) || "SENSEX".equalsIgnoreCase(name)) {
		            spotPrice = samco.getIndexPrice(session, name);
		            //If Samco fails , use the angelone price
		            //spotPrice = angelOneService.getcurrentPrice(smartconnect, strategy.getExchange(), strategy.getSymbol(), strategy.getToken());
		        } else if ("CRUDEOIL".equalsIgnoreCase(name) || "CRUDEOILM".equalsIgnoreCase(name) || "NATURALGAS".equalsIgnoreCase(name)) {
		            spotPrice = samco.getLtp(session, strategy.getExchange(), getSymbolByName(name));
		        }

		        if (spotPrice == null || spotPrice.compareTo(BigDecimal.ZERO) <= 0) {
		            logger.error("Invalid spot price for {}: {}", name, spotPrice);
		            return;
		        }

		        logger.debug("Spot price for {}: {}", name, spotPrice);

		        BigDecimal atmStrike = getATMStrike(name, strategy, spotPrice);

		        if (atmStrike == null || atmStrike.compareTo(BigDecimal.ZERO) <= 0) {
		            logger.error("Invalid ATM strike for {}: {}", name, atmStrike);
		            return;
		        }

		        logger.info("ATM strike for {}: {}", name, atmStrike);

		        List<StraddlePremiumDto> strikeList = getOrBuildStrikeList(name, atmStrike);
		        strikeList = getAllTokenDetails(strikeList, strategy);

		        long validTokenCount = strikeList.stream()
		            .filter(dto -> dto.getCeToken() != null || dto.getPeToken() != null)
		            .count();

		        if (validTokenCount == 0) {
		            logger.error("No valid tokens found for strategy: {}", name);
		            return;
		        }

		        logger.info("Found {} strikes with valid tokens out of {}",
		            validTokenCount, strikeList.size());

		        // =========================================================
		        // PREVIOUS DAY OHLC — ONCE PER DAY (High + Low + Close)
		        // =========================================================
		        resetPrevDayDataIfNewDay();

		        Map<String, BigDecimal> strategyHighCache = prevHighMap.get(name);

		        if (strategyHighCache == null || strategyHighCache.isEmpty()) {
		            logger.info("Fetching previous day OHLC for strategy: {} (one-time fetch)", name);
		            fetchPreviousDayDataForAllStrikes(strikeList, smartconnect, strategy); // ← ONE merged call
		        } else {
		            logger.debug("Using cached previous day data for strategy: {}", name);
		            populatePrevDayDataFromCache(strikeList, name); // ← cache lookup, no API call
		        }

		        // =========================================================
		        // CURRENT PRICES — EVERY 1 MIN (batch — single API call)
		        // =========================================================
		        strikeList = getPriceForAllTheStrikesBatch(strikeList, smartconnect, strategy.getExchange());

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

		        // =========================================================
		        // IV FROM GREEKS API - COMMENTED OUT FOR PERFORMANCE
		        // =========================================================
		        /*
		        Map<String, BigDecimal> ivMap = fetchIVFromGreeksAPI(
		            smartconnect, strategy.getName(), strategy.getExpiry()
		        );

		        if (ivMap != null && !ivMap.isEmpty()) {
		            populateIVFromGreeksMap(strikeList, ivMap);
		        } else {
		            logger.warn("IV map is null/empty for {} {}", strategy.getName(), strategy.getExpiry());
		        }
		        */

		        // =========================================================
		        // VWAP — EVERY 1 MIN (ONE_MINUTE candle per token)
		        // =========================================================
		        resetVwapIfNewDay();

		        List<StraddlePremiumDto> strikesWithPrices = strikeList.stream()
		            .filter(dto ->
		                (dto.getCePrice() != null && dto.getCePrice().compareTo(BigDecimal.ZERO) > 0) ||
		                (dto.getPePrice() != null && dto.getPePrice().compareTo(BigDecimal.ZERO) > 0)
		            )
		            .collect(Collectors.toList());

		        logger.info("Fetching VWAP for {} strikes with valid prices (out of {} total)",
		            strikesWithPrices.size(), strikeList.size());

		        fetchVwapInParallel(strikesWithPrices, smartconnect, strategy.getExchange());

		        // =========================================================
		        // SAVE TO DB
		        // =========================================================
		        int savedCount = savePriceDetails(strikeList, strategy, spotPrice, atmStrike);
		        logger.info("Saved {} records to database for {}", savedCount, name);

		    } catch (Exception e) {
		        logger.error("Error in getCombineStraddlePremium for {}", name, e);
		    }
		}
	
	public String getSymbolByName(String name) {
        if ("NIFTY".equalsIgnoreCase(name)) {
            return strategyRepo.findByName("STRADDLE_PREMIUM").getSymbol();
        } else if ("CRUDEOIL".equalsIgnoreCase(name)|| "CRUDEOILM".equalsIgnoreCase(name)) {
            return strategyRepo.findByName("STRADDLE_PREMIUM").getSymbol1();
        } else if ("NATURALGAS".equalsIgnoreCase(name)) {
            // Retrieve NG symbol directly from its own strategy row to pass to Samco
            Strategy strategy = strategyRepo.findByName(name);
            return strategy != null ? strategy.getSymbol() : "NATURALGAS";
        }
        return null;
    }
	
	private void fetchVwapInParallel(
		    List<StraddlePremiumDto> strikesWithPrices,
		    SmartConnect smartConnect,
		    String exchange
		) {
		    
		    // Create thread pool (limit to 5 concurrent requests to avoid rate limits)
		    ExecutorService executor = Executors.newFixedThreadPool(5);
		    List<Future<?>> futures = new ArrayList<>();
		    
		    for (StraddlePremiumDto dto : strikesWithPrices) {
		        
		        // Submit CE VWAP fetch
		        if (dto.getCeToken() != null && dto.getCePrice() != null && 
		            dto.getCePrice().compareTo(BigDecimal.ZERO) > 0) {
		            
		            Future<?> ceFuture = executor.submit(() -> {
		                try {
		                    JSONArray ceCandle = fetchLatestOneMinuteCandle(
		                        smartConnect, exchange, dto.getCeToken().getToken()
		                    );
		                    
		                    if (ceCandle != null && !ceCandle.isEmpty()) {
		                        BigDecimal vwap = updateVwapIncremental(
		                            dto.getCeToken().getToken(), ceCandle
		                        );
		                        dto.setCeVwap(vwap);
		                    }
		                } catch (Exception e) {
		                    logger.warn("Failed to fetch CE VWAP for strike {}: {}", 
		                        dto.getStrikePrice(), e.getMessage());
		                }
		            });
		            futures.add(ceFuture);
		        }
		        
		        // Submit PE VWAP fetch
		        if (dto.getPeToken() != null && dto.getPePrice() != null && 
		            dto.getPePrice().compareTo(BigDecimal.ZERO) > 0) {
		            
		            Future<?> peFuture = executor.submit(() -> {
		                try {
		                    JSONArray peCandle = fetchLatestOneMinuteCandle(
		                        smartConnect, exchange, dto.getPeToken().getToken()
		                    );
		                    
		                    if (peCandle != null && !peCandle.isEmpty()) {
		                        BigDecimal vwap = updateVwapIncremental(
		                            dto.getPeToken().getToken(), peCandle
		                        );
		                        dto.setPeVwap(vwap);
		                    }
		                } catch (Exception e) {
		                    logger.warn("Failed to fetch PE VWAP for strike {}: {}", 
		                        dto.getStrikePrice(), e.getMessage());
		                }
		            });
		            futures.add(peFuture);
		        }
		    }
		    
		    // Wait for all tasks to complete
		    for (Future<?> future : futures) {
		        try {
		            future.get(30, TimeUnit.SECONDS); // 30 second timeout per task
		        } catch (TimeoutException e) {
		            logger.error("VWAP fetch timed out");
		            future.cancel(true);
		        } catch (Exception e) {
		            logger.error("VWAP fetch failed: {}", e.getMessage());
		        }
		    }
		    
		    executor.shutdown();
		    try {
		        if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
		            executor.shutdownNow();
		        }
		    } catch (InterruptedException e) {
		        executor.shutdownNow();
		        Thread.currentThread().interrupt();
		    }
		}
	public List<StraddlePremiumDto> getOrBuildStrikeList(String name, BigDecimal atmStrike) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        if (strikeListCache.containsKey(name) && today.equals(strikeInitDate.get(name))) {
            return strikeListCache.get(name);
        }

        logger.info("Building strike list ONCE for {} using ATM {}", name, atmStrike);

        int stepInterval = 50; 
        
        if (name != null) {
            String upperName = name.toUpperCase();
            // ADDED CRUDEOIL HERE
            if (upperName.contains("SENSEX") || upperName.contains("BANK") || upperName.contains("CRUDEOIL")) {
                stepInterval = 100;
            } else if (upperName.contains("NATURALGAS")) {
                stepInterval = 5; // MCX NG strike interval
            }
        }

        int rangeValue = 600;
        if (name != null) {
            String upperName = name.toUpperCase();
            if (upperName.contains("SENSEX")) {
                rangeValue = 1000;
            } else if (upperName.contains("NATURALGAS")) {
                rangeValue = 50; // ±100 points handles 10 strikes on each side
            }
        }

        List<StraddlePremiumDto> strikeList = buildStraddleDtos(name, atmStrike, stepInterval);

        strikeListCache.put(name, strikeList);
        strikeInitDate.put(name, today);

        return strikeList;
    }
	
	// =====================================================
	// ✅ EVENT-BASED CE / PE CROSSOVER (1-MINUTE ONLY)
	// =====================================================
	private static final BigDecimal MIN_DOMINANCE_GAP = BigDecimal.valueOf(2); // ₹2 filter

	private void detectCrossoverEvent(
		    StraddlePremiumDto dto,
		    String name,
		    LocalDateTime currentTs
		) {
		    dto.setCeCrossoverAbove(false);
		    dto.setPeCrossoverAbove(false);

		    // 1. Fetch last TWO persisted rows
		    List<StraddleIntraday> lastTwo = straddleIntradayRepo.findLastTwo(
		            name, dto.getStrikePrice(), PageRequest.of(0, 2));

		    if (lastTwo.size() < 2) return;

		    // 🚨 FIX: Ensure we know exactly which is T-1 and T-2
		    StraddleIntraday prev = lastTwo.get(0);      // t-1 (Most recent in DB)
		    StraddleIntraday beforePrev = lastTwo.get(1); // t-2 (Previous)

		    // 2. Time-continuity guard
		    long gapSeconds = Math.abs(java.time.Duration.between(prev.getTimestamp(), currentTs).getSeconds());
		    if (gapSeconds > 120) return; 

		    // 3. Extract Prices
		    BigDecimal p2Ce = beforePrev.getCePrice(); // t-2
		    BigDecimal p2Pe = beforePrev.getPePrice(); // t-2
		    BigDecimal p1Ce = prev.getCePrice();       // t-1
		    BigDecimal p1Pe = prev.getPePrice();       // t-1
		    
		    BigDecimal currCe = dto.getCePrice();      // t (Current)
		    BigDecimal currPe = dto.getPePrice();      // t (Current)

		    if (p2Ce == null || p2Pe == null || currCe == null || currPe == null) return;

		    // 4. Dominance gap (Strength Filter)
		    BigDecimal dominanceGap = currCe.subtract(currPe).abs();
		    if (dominanceGap.compareTo(MIN_DOMINANCE_GAP) < 0) return;

		    // ============================================================
		    // 🚨 HIGHLIGHT: THE "TRUE" CROSSOVER LOGIC
		    // ============================================================
		    
		    // 🟢 CE CROSSOVER: 
		    // Was CE <= PE at t-1 AND is CE > PE now?
		    boolean ceCrossedAbove = p1Ce.compareTo(p1Pe) <= 0 && currCe.compareTo(currPe) > 0;
		    
		    // 🔴 PE CROSSOVER: 
		    // Was PE <= CE at t-1 AND is PE > CE now?
		    boolean peCrossedAbove = p1Pe.compareTo(p1Ce) <= 0 && currPe.compareTo(currCe) > 0;

		    // 5. VWAP Confirmation
		    BigDecimal currCeVwap = dto.getCeVwap();
		    BigDecimal currPeVwap = dto.getPeVwap();
		    if (currCeVwap == null || currPeVwap == null) return;

		    if (ceCrossedAbove && currCe.compareTo(currCeVwap) > 0) {
		        dto.setCeCrossoverAbove(true);
		        logger.info("🚀 CE Crossover Detected for {} at Strike {}", name, dto.getStrikePrice());
		    } 
		    else if (peCrossedAbove && currPe.compareTo(currPeVwap) > 0) {
		        dto.setPeCrossoverAbove(true);
		        logger.info("🚀 PE Crossover Detected for {} at Strike {}", name, dto.getStrikePrice());
		    }
		}




	private boolean isAlertRequired(String strategy) {
        return "Y".equalsIgnoreCase(
                strategyRepo.findByName(strategy).getAlert()
        );
    }
	

	// =====================================================
	// SAVE TO DB - WITH VALIDATION
	// =====================================================
	
	public int savePriceDetails(
		List<StraddlePremiumDto> strikeList, 
		Strategy strategy, 
		BigDecimal spotPrice,
		BigDecimal atmStrike
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
			
			// NEW: Previous day high/low
			entity.setCePrevHigh(dto.getCePrevHigh());
			entity.setCePrevLow(dto.getCePrevLow());
			entity.setPePrevHigh(dto.getPePrevHigh());
			entity.setPePrevLow(dto.getPePrevLow());
			entity.setCePrevClose(dto.getCePrevClose());
			entity.setPePrevClose(dto.getPePrevClose());
			entity.setCombinedPrevClose(dto.getCombinedPrevClose());
			
			 // ✅ CALCULATE COMBINED IV
	        BigDecimal combinedIV = calculateCombinedIV(dto.getCeIv(), dto.getPeIv());
	        entity.setCombinedIv(combinedIV);
	        dto.setCombinedIv(combinedIV); // Also set in DTO for signal calculations
			
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
			BigDecimal ceExtrinsic = ce.subtract(ceIntrinsic).max(BigDecimal.ZERO);
			BigDecimal peExtrinsic = pe.subtract(peIntrinsic).max(BigDecimal.ZERO);

			entity.setCeExtrinsic(ceExtrinsic);
			entity.setPeExtrinsic(peExtrinsic);


			entity.setCeOpenPrice(dto.getCeOpenPrice());
			entity.setPeOpenPrice(dto.getPeOpenPrice());

			if (dto.getCeOpenPrice() != null && dto.getPeOpenPrice() != null) {
				entity.setCombinedOpenPrice(dto.getCeOpenPrice().add(dto.getPeOpenPrice()));
			}

			entity.setAvgPrice(combinedPremium.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP));
			if (dto.getCeToken() != null && dto.getPeToken() != null) {
				entity.setCeToken(dto.getCeToken().getToken());
				entity.setCeSymbol(dto.getCeToken().getSymbol());
				entity.setPeToken(dto.getPeToken().getToken());
				entity.setPeSymbol(dto.getPeToken().getSymbol());
			}
			try {
				straddleIntradayRepo.save(entity);
				count++;
				 // 🔔 Trigger Telegram ONLY if crossover happened
				
			} catch (Exception e) {
				logger.error("Failed to save record for strike {}: {}", 
					dto.getStrikePrice(), e.getMessage());
			}
		}
		
		return count;
	}
	
	private void checkAndSendAlerts(StraddleIntraday entity) {

	    // ── EXISTING: CE–PE Crossover ──────────────────────
	    if (AlertConditionChecker.isEnabled(AlertType.CE_PE_CROSSOVER)
	            && AlertConditionChecker.isCeCrossoverAbove(entity)) {

	        sendTelegramAlert(entity, AlertType.CE_PE_CROSSOVER);
	    }

	    // ── EXISTING: PE–CE Crossover ──────────────────────
	    if (AlertConditionChecker.isEnabled(AlertType.PE_CE_CROSSOVER)
	            && AlertConditionChecker.isPeCrossoverAbove(entity)) {

	        sendTelegramAlert(entity, AlertType.PE_CE_CROSSOVER);
	    }

	    // ── NEW: VWAP Dominance CE ──────────────────────────
	    if (AlertConditionChecker.isEnabled(AlertType.VWAP_DOMINANCE_CE)
	            && AlertConditionChecker.isVwapDominanceCe(entity)) {

	        sendTelegramAlert(entity, AlertType.VWAP_DOMINANCE_CE);
	    }

	    // ── NEW: VWAP Dominance PE ──────────────────────────
	    if (AlertConditionChecker.isEnabled(AlertType.VWAP_DOMINANCE_PE)
	            && AlertConditionChecker.isVwapDominancePe(entity)) {

	        sendTelegramAlert(entity, AlertType.VWAP_DOMINANCE_PE);
	    }
	}
	
	private void sendTelegramAlert(StraddleIntraday entity, AlertType alertType) {
	    try {
	        if (isAlertAlreadySent(entity.getStrike(), alertType)) return;

	        String message = buildTelegramMessage(entity, alertType);
	        boolean sent   = telegramService.sendMessage(message);

	        if (sent) {
	        	logger.info("✅ Alert sent [{}] for {} strike {}",
	                    alertType, entity.getName(), entity.getStrike());
	        }

	        // Derive strategy group from alertType
	        String strategyName = (alertType == AlertType.CE_PE_CROSSOVER
	                            || alertType == AlertType.PE_CE_CROSSOVER)
	                ? "CROSSOVER"
	                : "VWAP_DOMINANCE";

	        // ✅ Full overload — symbol + prices written to Alert row
	        //    DominanceService SL check reads cePrice/pePrice/ceVwap/peVwap from here
	        telegramService.saveAlertIfEnabled(
	                strategyName,
	                entity.getName(),               // symbol  → "NIFTY" / "CRUDEOIL"
	                message,
	                alertType.name(),               // signalType → "VWAP_DOMINANCE_CE" etc.
	                sent,
	                entity.getStrike() != null
	                        ? entity.getStrike().intValue() : null,
	                entity.getCePrice() != null
	                        ? entity.getCePrice().doubleValue() : null,
	                entity.getPePrice() != null
	                        ? entity.getPePrice().doubleValue() : null,
	                entity.getCeVwap()  != null
	                        ? entity.getCeVwap().doubleValue()  : null,
	                entity.getPeVwap()  != null
	                        ? entity.getPeVwap().doubleValue()  : null
	        );

	    } catch (Exception ex) {
	    	logger.error("Telegram alert failed [{}] for {} {}",
	                alertType, entity.getName(), entity.getStrike(), ex);
	    }
	}
	
	private void sendTelegramAndMark(StraddleIntraday s, AlertType alertType) {
	    try {
	        String message = buildTelegramMessage(s, alertType); // ← updated
	        boolean sent = telegramService.sendMessage(message);
	        if (sent) {
	            logger.info("✅ Alert sent [{}] for {} strike {}",
	                alertType, s.getName(), s.getStrike());
	        }
	    } catch (Exception ex) {
	        logger.error("Telegram alert failed [{}] for {} {}",
	            alertType, s.getName(), s.getStrike(), ex);
	    }
	}
	
	// =====================================================
    // MESSAGE FORMAT
    // =====================================================
	private String buildTelegramMessage(StraddleIntraday entity, AlertType alertType) {

	    return switch (alertType) {

	        case CE_PE_CROSSOVER -> String.format("""
	            🚨 CE–PE Crossover Signal 🚨

	            📌 Symbol  : %s
	            📌 Strike  : %s
	            ⏰ Time    : %s

	            🟢 CE crossed ABOVE PE
	            💰 CE Price : %.2f
	            💰 PE Price : %.2f
	            📊 Combined : %.2f

	            ⚠️ Event-based crossover (one-time)
	            """,
	            entity.getName(), entity.getStrike(), entity.getTimestamp(),
	            entity.getCePrice(), entity.getPePrice(), entity.getCombinedPremium()
	        );

	        case PE_CE_CROSSOVER -> String.format("""
	            🚨 PE–CE Crossover Signal 🚨

	            📌 Symbol  : %s
	            📌 Strike  : %s
	            ⏰ Time    : %s

	            🔴 PE crossed ABOVE CE
	            💰 CE Price : %.2f
	            💰 PE Price : %.2f
	            📊 Combined : %.2f

	            ⚠️ Event-based crossover (one-time)
	            """,
	            entity.getName(), entity.getStrike(), entity.getTimestamp(),
	            entity.getCePrice(), entity.getPePrice(), entity.getCombinedPremium()
	        );

	        case VWAP_DOMINANCE_CE -> String.format("""
	            📊 VWAP Dominance Signal 📊

	            📌 Symbol  : %s
	            📌 Strike  : %s
	            ⏰ Time    : %s

	            🟢 CE is DOMINANT (CE > CE-VWAP, PE < PE-VWAP)
	            💰 CE Price : %.2f  |  CE VWAP : %.2f
	            💰 PE Price : %.2f  |  PE VWAP : %.2f
	            📊 Combined : %.2f
	            """,
	            entity.getName(), entity.getStrike(), entity.getTimestamp(),
	            entity.getCePrice(), entity.getCeVwap(),
	            entity.getPePrice(), entity.getPeVwap(),
	            entity.getCombinedPremium()
	        );

	        case VWAP_DOMINANCE_PE -> String.format("""
	            📊 VWAP Dominance Signal 📊

	            📌 Symbol  : %s
	            📌 Strike  : %s
	            ⏰ Time    : %s

	            🔴 PE is DOMINANT (PE > PE-VWAP, CE < CE-VWAP)
	            💰 CE Price : %.2f  |  CE VWAP : %.2f
	            💰 PE Price : %.2f  |  PE VWAP : %.2f
	            📊 Combined : %.2f
	            """,
	            entity.getName(), entity.getStrike(), entity.getTimestamp(),
	            entity.getCePrice(), entity.getCeVwap(),
	            entity.getPePrice(), entity.getPeVwap(),
	            entity.getCombinedPremium()
	        );
	    };
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
			Map<String, BigDecimal> highMap = new HashMap<>();
			Map<String, BigDecimal> lowMap = new HashMap<>();
			
			for (int i = 0; i < fetched.length(); i++) {
				JSONObject item = fetched.getJSONObject(i);
				String token = item.optString("symbolToken", null);
				
				if (token != null) {
					ltpMap.put(token, item.optBigDecimal("ltp", BigDecimal.ZERO));
					openMap.put(token, item.optBigDecimal("open", BigDecimal.ZERO));
					oIMap.put(token, item.optBigDecimal("opnInterest", BigDecimal.ZERO));
					volumeMap.put(token, item.optBigDecimal("tradeVolume", BigDecimal.ZERO));
					highMap.put(token, item.optBigDecimal("high", BigDecimal.ZERO));
					lowMap.put(token, item.optBigDecimal("low", BigDecimal.ZERO));
				}
			}

			for (StraddlePremiumDto dto : strikeList) {

				if (dto.getCeToken() != null) {
					String t = dto.getCeToken().getToken();
					BigDecimal ltp = ltpMap.get(t);
					BigDecimal open = openMap.get(t);
					BigDecimal oi = oIMap.get(t);
					BigDecimal volume = volumeMap.get(t);
					BigDecimal high = highMap.get(t);
					BigDecimal low = lowMap.get(t);
					
					dto.setCePrice(ltp);
					dto.setCeOpenPrice(open);
					dto.setCeOI(oi);
					dto.setCeVolume(volume);
					dto.setCeHigh(high);
					dto.setCeLow(low);
					
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
					BigDecimal high = highMap.get(t);
					BigDecimal low = lowMap.get(t);
					
					dto.setPePrice(ltp);
					dto.setPeOpenPrice(open);
					dto.setPeOI(oi);
					dto.setPeVolume(volume);
					dto.setPeHigh(high);
					dto.setPeLow(low);
					
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
	public List<StraddlePremiumDto> buildStraddleDtos(String name, BigDecimal atmStrike, int interval) {
        List<StraddlePremiumDto> list = new ArrayList<>();
        BigDecimal step = BigDecimal.valueOf(interval);
        
        int rangeValue = 600;
        if (name != null) {
            String upperName = name.toUpperCase();
            if (upperName.contains("SENSEX")) {
                rangeValue = 1000;
            } else if (upperName.contains("NATURALGAS")) {
                rangeValue = 50; // ±100 points handles 10 strikes on each side
            }
        }
        
        BigDecimal range = BigDecimal.valueOf(rangeValue);

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

        int stepInterval = 50; 
        
        if (name != null) {
            String upperName = name.toUpperCase();
            
            // ADDED CRUDEOIL HERE
            if (upperName.contains("SENSEX") || upperName.contains("BANK") || upperName.contains("CRUDEOIL")) {
                stepInterval = 100;
            } else if (upperName.contains("NATURALGAS")) {
                stepInterval = 5; // MCX NG strike interval
            }
        }

        int nearest = chartService.findNearestMultiple(price.intValue(), stepInterval);
        return BigDecimal.valueOf(nearest);
    }


	// =====================================================
    // TOKEN DETAILS - DYNAMIC WEEKLY & MONTHLY ROUTING
    // =====================================================
    public List<StraddlePremiumDto> getAllTokenDetails(
        List<StraddlePremiumDto> strikeList, 
        Strategy strategy
    ) {
        logger.info("Fetching tokens for strategy: {}, expiry: {}", 
            strategy.getName(), strategy.getExpiry());

        for (StraddlePremiumDto dto : strikeList) {
            int strike = dto.getStrikePrice().intValue();

            // Generate accurate database lookup symbols dynamically
            String ceSymbol = generateSymbol(strategy.getName(), strategy.getExpiry(), strike, "CE");
            String peSymbol = generateSymbol(strategy.getName(), strategy.getExpiry(), strike, "PE");

            // Fetch CE token
            Indexes ceIndex = indexesRepo.findByNameAndSymbol(strategy.getName(), ceSymbol);
            if (ceIndex != null) {
                Token t = new Token();
                t.setToken(ceIndex.getToken());
                t.setSymbol(ceIndex.getSymbol());
                t.setExch_seg(ceIndex.getExchange());
                t.setQuantity(ceIndex.getLotsize());
                dto.setCeToken(t);
                logger.debug("Found CE token for {}: {}", ceSymbol, t.getToken());
            } else {
                logger.warn("CE token NOT found for symbol: {}", ceSymbol);
            }

            // Fetch PE token
            Indexes peIndex = indexesRepo.findByNameAndSymbol(strategy.getName(), peSymbol);
            if (peIndex != null) {
                Token t = new Token();
                t.setToken(peIndex.getToken());
                t.setSymbol(peIndex.getSymbol());
                t.setExch_seg(peIndex.getExchange());
                t.setQuantity(peIndex.getLotsize());
                dto.setPeToken(t);
                logger.debug("Found PE token for {}: {}", peSymbol, t.getToken());
            } else {
                logger.warn("PE token NOT found for symbol: {}", peSymbol);
            }
        }
        
        return strikeList;
    }

    /**
     * Translates strategy attributes into exact database table symbol patterns.
     * Differentiates Monthly vs Weekly structural variations inside BSE Sensex contracts.
     */
    private String generateSymbol(String strategyName, String expiry, int strike, String optionType) {
        if (strategyName == null || expiry == null) {
            return "";
        }

        String upperName = strategyName.toUpperCase().trim();
        String cleanExpiry = expiry.toUpperCase().trim();

        if ("SENSEX".equals(upperName)) {
            // Parses standard layout patterns like "27MAY26"
            // Group 1: Day ("27"), Group 2: Month ("MAY"), Group 3: Year ("26")
            Pattern pattern = Pattern.compile("^(\\d{1,2})([A-Z]{3})(\\d{2})$");
            Matcher matcher = pattern.matcher(cleanExpiry);

            if (matcher.matches()) {
                String day = matcher.group(1);
                String month = matcher.group(2);
                String year = matcher.group(3);

                int dayInt = Integer.parseInt(day);
                
                // Monthly Expiry Rule: Sensex monthly contracts expire on the last week of the month (Days 26-31).
                // Your DB stores monthly options omitting the day component entirely: SENSEX + YY + MMM
                if (dayInt >= 26) { 
                    String monthlyExpiryPattern = year + month; // E.g. "26MAY"
                    return String.format("%s%s%d%s", upperName, monthlyExpiryPattern, strike, optionType);
                }
            }
        }

        // Standard formatting path for NIFTY, CRUDEOIL, NATURALGAS, and SENSEX Weeklies
        // Outputs standard format strings directly: e.g., SENSEX27MAY2674200CE or NIFTY26MAY2623800CE
        return String.format("%s%s%d%s", upperName, cleanExpiry, strike, optionType);
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
			CombinedChartPoint pt = map.computeIfAbsent(key, t -> {
			    CombinedChartPoint newPoint = new CombinedChartPoint();
			    newPoint.setTimestamp(t);
			    return newPoint;
			});
			pt.setCe(r.getCePrice());
			pt.setCeOpen(r.getCeOpenPrice());
			pt.setCeExtrinsic(r.getCeExtrinsic());
			pt.setCeIntrinsic(r.getCeIntrinsic());
			pt.setCeVwap(r.getCeVwap());
			pt.setCeIV(r.getCeIV());  // ✅ ADD
			// 🟢 SET THESE HERE (Then you can delete the loops you mentioned)
		    pt.setCePrevClose(r.getCePrevClose());
		    pt.setCePrevLow(r.getCePrevLow());
		}

		// PE rows
		for (StraddleIntraday r : peRows) {
			String key = r.getTimestamp().atZone(ist).toOffsetDateTime().withNano(0).toString();
			CombinedChartPoint pt = map.computeIfAbsent(key, t -> {
		        CombinedChartPoint newPoint = new CombinedChartPoint();
		        newPoint.setTimestamp(t);
		        return newPoint;
		    });
			pt.setPe(r.getPePrice());
			pt.setPeOpen(r.getPeOpenPrice());
			pt.setPeExtrinsic(r.getPeExtrinsic());
			pt.setPeIntrinsic(r.getPeIntrinsic());
			pt.setPeVwap(r.getPeVwap());
			pt.setPeIV(r.getPeIV());  // ✅ ADD
			// 🟢 SET THESE HERE
		    pt.setPePrevClose(r.getPePrevClose());
		    pt.setPePrevLow(r.getPePrevLow());
		}

		// SPOT rows
		for (StraddleIntraday r : spotRows) {
			String key = r.getTimestamp().atZone(ist).toOffsetDateTime().withNano(0).toString();
			CombinedChartPoint pt = map.computeIfAbsent(key, t -> new CombinedChartPoint(
				t, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
			,null,null,null,null, null, null, peStrike, peStrike));
			pt.setSpot(r.getSpot());
		}
		

		// =====================================================
		// CONSOLIDATED DERIVED VALUES LOOP
		// =====================================================
		for (CombinedChartPoint pt : map.values()) {
		    
		    // 1. Current Combined Premium & Avg
		    if (pt.getCe() != null && pt.getPe() != null) {
		        pt.setCombinedPremium(pt.getCe().add(pt.getPe()));
		        pt.setAvgPrice(pt.getCombinedPremium().divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP));
		    }
		    
		    // 2. Combined VWAP
		    if (pt.getCeVwap() != null && pt.getPeVwap() != null) {
		        pt.setCombinedVwap(pt.getCeVwap().add(pt.getPeVwap()));
		    }

		    // 3. Combined Open Price
		    if (pt.getCeOpen() != null && pt.getPeOpen() != null) {
		        pt.setCombinedOpen(pt.getCeOpen().add(pt.getPeOpen()));
		    }
		    
		    // 4. Combined IV (Average)
		    if (pt.getCeIV() != null && pt.getPeIV() != null) {
		        pt.setCombinedIV(
		            pt.getCeIV().add(pt.getPeIV())
		                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP)
		        );
		    } else if (pt.getCeIV() != null) {
		        pt.setCombinedIV(pt.getCeIV());
		    } else if (pt.getPeIV() != null) {
		        pt.setCombinedIV(pt.getPeIV());
		    }

		    // 5. Combined Previous Close (Sum)
		    if (pt.getCePrevClose() != null && pt.getPePrevClose() != null) {
		        pt.setCombinedPrevClose(pt.getCePrevClose().add(pt.getPePrevClose()));
		    }

		    // 6. Combined Previous Low (Sum)
		    if (pt.getCePrevLow() != null && pt.getPePrevLow() != null) {
		        pt.setCombinedPrevLow(pt.getCePrevLow().add(pt.getPePrevLow()));
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
	        lastProcessedTimestamp.clear(); // 🟢 Clears the String map
	        vwapDate = today;
	        logger.info("VWAP and Timestamp cache reset for new trading day: {}", today);
	    }
	}

	// =====================================================
	// PREVIOUS DAY HIGH/LOW RESET
	// =====================================================
	private void resetPrevDayDataIfNewDay() {
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

		if (prevDayDataDate == null || !prevDayDataDate.equals(today)) {
			prevHighMap.clear();
			prevLowMap.clear();
			prevCloseMap.clear(); // 🆕 ADDED THIS LINE
			prevDayDataDate = today;
			logger.info("Previous day high/low cache reset for new trading day: {}", today);
		}
	}

	// =====================================================
	// NEW METHOD: fetchPreviousDayCloseForAllStrikes
	// Fetches previous day close prices (similar to prevHigh/prevLow)
	// =====================================================

	public void fetchPreviousDayCloseForAllStrikes(
	    List<StraddlePremiumDto> strikeList,
	    SmartConnect smartConnect,
	    Strategy strategy
	) {
	    
	    logger.info("=== FETCHING PREVIOUS DAY CLOSE (ONE-TIME FOR {}) ===", strategy.getName());
	    
	    // Initialize nested map for this strategy if not exists
	    prevCloseMap.putIfAbsent(strategy.getName(), new HashMap<>());
	    
	    Map<String, BigDecimal> strategyCloseCache = prevCloseMap.get(strategy.getName());
	    
	    // ✅ Get date range for previous working day's data
	    LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
	    
	    // Step 1: Current trading date
	    LocalDate tradingDate = NSEWorkingDays.isNSEWorkingDay(today)
	        ? today
	        : NSEWorkingDays.getLastWorkingDay(today);
	    
	    // Step 2: Previous working day (the target day whose close we want)
	    LocalDate previousWorkingDay = NSEWorkingDays.getLastWorkingDay(tradingDate);
	    
	    // Step 3: Format date range (same as prevHigh/prevLow)
	    String fromDate = previousWorkingDay + " 15:30";
	    String toDate = tradingDate + " 15:30";
	    
	    logger.info("Fetching prev close for date range: {} to {}", fromDate, toDate);
	    
	    int successCount = 0;
	    int failureCount = 0;
	    
	    for (StraddlePremiumDto dto : strikeList) {
	        
	        // Fetch CE previous close
	        if (dto.getCeToken() != null) {
	            try {
	                JSONObject req = new JSONObject();
	                req.put("exchange", strategy.getExchange());
	                req.put("symboltoken", dto.getCeToken().getToken());
	                req.put("interval", "ONE_DAY");
	                req.put("fromdate", fromDate);
	                req.put("todate", toDate);
	                
	                // Add delay to avoid rate limiting
	                JSONArray candles = fetchCandleWithRetry(
	                	    smartConnect,
	                	    req,
	                	    dto.getCeToken().getToken()
	                	);
	                
	                if (candles != null && candles.length() > 0) {
	                    JSONArray lastCandle = candles.getJSONArray(candles.length() - 1);
	                    
	                    // Index 4 = Close price in candle data
	                    BigDecimal close = lastCandle.getBigDecimal(4);
	                    
	                    // Store in strategy-specific cache
	                    strategyCloseCache.put(dto.getCeToken().getToken(), close);
	                    
	                    // Set in DTO
	                    dto.setCePrevClose(close);
	                    
	                    successCount++;
	                    
	                    logger.debug("CE Strike {}: PrevClose={}", 
	                        dto.getStrikePrice(), close);
	                } else {
	                    logger.warn("No candle data for CE token: {} (strike: {})", 
	                        dto.getCeToken().getToken(), dto.getStrikePrice());
	                    failureCount++;
	                }
	                
	            } catch (Exception e) {
	                logger.error("Failed to fetch CE prev close for strike {}: {}", 
	                    dto.getStrikePrice(), e.getMessage());
	                failureCount++;
	            }
	        }
	        
	        // Fetch PE previous close
	        if (dto.getPeToken() != null) {
	            try {
	                JSONObject req = new JSONObject();
	                req.put("exchange", strategy.getExchange());
	                req.put("symboltoken", dto.getPeToken().getToken());
	                req.put("interval", "ONE_DAY");
	                req.put("fromdate", fromDate);
	                req.put("todate", toDate);
	                
	                // Add delay to avoid rate limiting
	                JSONArray candles = fetchCandleWithRetry(
	                	    smartConnect,
	                	    req,
	                	    dto.getPeToken().getToken()   // ✅ CORRECT
	                	);
	                
	                if (candles != null && candles.length() > 0) {
	                    JSONArray lastCandle = candles.getJSONArray(candles.length() - 1);
	                    
	                    // Index 4 = Close price in candle data
	                    BigDecimal close = lastCandle.getBigDecimal(4);
	                    
	                    // Store in strategy-specific cache
	                    strategyCloseCache.put(dto.getPeToken().getToken(), close);
	                    
	                    // Set in DTO
	                    dto.setPePrevClose(close);
	                    
	                    successCount++;
	                    
	                    logger.debug("PE Strike {}: PrevClose={}", 
	                        dto.getStrikePrice(), close);
	                } else {
	                    logger.warn("No candle data for PE token: {} (strike: {})", 
	                        dto.getPeToken().getToken(), dto.getStrikePrice());
	                    failureCount++;
	                }
	                
	            } catch (Exception e) {
	                logger.error("Failed to fetch PE prev close for strike {}: {}", 
	                    dto.getStrikePrice(), e.getMessage());
	                failureCount++;
	            }
	        }
	        
	        // 🆕 Calculate combined prev close if both available
	        if (dto.getCePrevClose() != null && dto.getPePrevClose() != null) {
	            BigDecimal combinedClose = dto.getCePrevClose().add(dto.getPePrevClose());
	            dto.setCombinedPrevClose(combinedClose);
	            
	            logger.debug("Strike {}: CombinedPrevClose={}", 
	                dto.getStrikePrice(), combinedClose);
	        }
	    }
	    
	    logger.info("Previous day close fetch complete for {}: Success={}, Failure={}", 
	        strategy.getName(), successCount, failureCount);
	}

	public JSONArray fetchCandleWithRetry(
	        SmartConnect smartConnect,
	        JSONObject request,
	        String token) {

	    int maxAttempts = 5;
	    long delay = 3000;

	    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
	        try {
	            JSONArray candles = smartConnect.candleData(request);

	            // ✅ Valid data — return immediately
	            if (candles != null && candles.length() > 0) {
	                if (attempt > 1) {
	                    logger.info("✅ Retry success for token {} on attempt {}/{}",
	                            token, attempt, maxAttempts);
	                }
	                return candles;
	            }

	            // ✅ Empty = illiquid strike, data doesn't exist — skip retries
	            if (candles != null && candles.length() == 0) {
	                logger.debug("No candle data for token {} — illiquid strike, skipping", token);
	                return null;  // ← return immediately, no retry
	            }

	            // ✅ null only = API/network issue — worth retrying
	            logger.warn("NULL candle response for token {} attempt {}/{}",
	                    token, attempt, maxAttempts);

	        } catch (Exception e) {
	            logger.warn("Attempt {}/{} failed for token {}: {}",
	                    attempt, maxAttempts, token, e.getMessage());
	        }

	        if (attempt < maxAttempts) {
	            try {
	                logger.info("Retrying token {} after {}ms...", token, delay);
	                Thread.sleep(delay);
	            } catch (InterruptedException ie) {
	                Thread.currentThread().interrupt();
	                return null;
	            }
	            delay *= 2;
	        }
	    }

	    logger.error("❌ All {} attempts exhausted for token {}", maxAttempts, token);
	    return null;
	}
	
	// =====================================================
	// NEW METHOD: populatePrevCloseFromCache
	// Populates DTO from cached previous close data
	// =====================================================

	public void populatePrevCloseFromCache(List<StraddlePremiumDto> strikeList, String strategyName) {
	    
	    Map<String, BigDecimal> strategyCloseCache = prevCloseMap.get(strategyName);
	    
	    if (strategyCloseCache == null) {
	        logger.warn("Prev close cache not found for strategy: {}", strategyName);
	        return;
	    }
	    
	    for (StraddlePremiumDto dto : strikeList) {
	        
	        // 1. Populate CE from cache
	        if (dto.getCeToken() != null) {
	            String ceToken = dto.getCeToken().getToken();
	            BigDecimal ceClose = strategyCloseCache.get(ceToken);
	            dto.setCePrevClose(ceClose);
	        }
	        
	        // 2. Populate PE from cache
	        if (dto.getPeToken() != null) {
	            String peToken = dto.getPeToken().getToken();
	            BigDecimal peClose = strategyCloseCache.get(peToken);
	            dto.setPePrevClose(peClose);
	        }
	        
	        // 3. FIX: Calculate combined prev close as a SUM
	        // We do NOT divide by 2 here. The straddle value is CE + PE.
	        if (dto.getCePrevClose() != null && dto.getPePrevClose() != null) {
	            BigDecimal sum = dto.getCePrevClose().add(dto.getPePrevClose());
	            
	            // Set the total value of the straddle
	            dto.setCombinedPrevClose(sum); 
	            
	            logger.debug("Strike {}: CombinedPrevClose (Sum) = {}", 
	                dto.getStrikePrice(), sum);
	        }
	    }
	}


	private void fetchPreviousDayHighLowForAllStrikes(
	        List<StraddlePremiumDto> strikeList,
	        SmartConnect smartConnect,
	        Strategy strategy) {

	    logger.info("=== FETCHING PREVIOUS DAY HIGH/LOW (ONE-TIME FOR {}) ===", strategy.getName());

	    prevHighMap.putIfAbsent(strategy.getName(), new HashMap<>());
	    prevLowMap.putIfAbsent(strategy.getName(), new HashMap<>());

	    Map<String, BigDecimal> strategyHighCache = prevHighMap.get(strategy.getName());
	    Map<String, BigDecimal> strategyLowCache  = prevLowMap.get(strategy.getName());

	    // ── Date range ──────────────────────────────────────────
	    LocalDate today           = LocalDate.now(ZoneId.of("Asia/Kolkata"));
	    LocalDate tradingDate     = NSEWorkingDays.isNSEWorkingDay(today)
	                                ? today : NSEWorkingDays.getLastWorkingDay(today);
	    LocalDate previousWD      = NSEWorkingDays.getLastWorkingDay(tradingDate);
	    LocalDate dayBeforePrevWD = NSEWorkingDays.getLastWorkingDay(previousWD);

	    String fromDate = dayBeforePrevWD + " 15:30";
	    String toDate   = previousWD      + " 15:30";

	    logger.info("Date range → from={} to={}", fromDate, toDate);

	    int successCount = 0;
	    int failureCount = 0;

	    for (StraddlePremiumDto dto : strikeList) {

	        // ── CE ──
	        if (dto.getCeToken() != null) {
	            boolean ok = fetchAndCachePrevDayHL(
	                    smartConnect, strategy, dto.getCeToken().getToken(),
	                    fromDate, toDate, strategyHighCache, strategyLowCache,
	                    high -> dto.setCePrevHigh(high),
	                    low  -> dto.setCePrevLow(low),
	                    "CE", dto.getStrikePrice());
	            if (ok) successCount++; else failureCount++;
	        }

	        // ── PE ──
	        if (dto.getPeToken() != null) {
	            boolean ok = fetchAndCachePrevDayHL(
	                    smartConnect, strategy, dto.getPeToken().getToken(),
	                    fromDate, toDate, strategyHighCache, strategyLowCache,
	                    high -> dto.setPePrevHigh(high),
	                    low  -> dto.setPePrevLow(low),
	                    "PE", dto.getStrikePrice());
	            if (ok) successCount++; else failureCount++;
	        }
	    }

	    logger.info("Prev day fetch complete for {}: Success={}, Failure={}",
	            strategy.getName(), successCount, failureCount);
	}

	// ── Extracted helper — handles one token (CE or PE) ──────────────────────────
	private boolean fetchAndCachePrevDayHL(
	        SmartConnect smartConnect,
	        Strategy strategy,
	        String token,
	        String fromDate,
	        String toDate,
	        Map<String, BigDecimal> highCache,
	        Map<String, BigDecimal> lowCache,
	        java.util.function.Consumer<BigDecimal> highSetter,
	        java.util.function.Consumer<BigDecimal> lowSetter,
	        String optionType,
	        BigDecimal strikePrice) {

	    try {
	        JSONObject req = new JSONObject();
	        req.put("exchange",    strategy.getExchange());
	        req.put("symboltoken", token);
	        req.put("interval",    "ONE_DAY");
	        req.put("fromdate",    fromDate);
	        req.put("todate",      toDate);

	        // ✅ Actual delay — avoid rate limiting
	        sleepQuietly(350);

	        JSONArray candles = fetchCandleWithRetry(smartConnect, req, token);

	        if (candles != null && candles.length() > 0) {
	            JSONArray lastCandle = candles.getJSONArray(candles.length() - 1);
	            BigDecimal high = lastCandle.getBigDecimal(2);
	            BigDecimal low  = lastCandle.getBigDecimal(3);

	            highCache.put(token, high);
	            lowCache.put(token,  low);
	            highSetter.accept(high);
	            lowSetter.accept(low);

	            logger.debug("{} Strike {}: PrevHigh={}, PrevLow={}", optionType, strikePrice, high, low);
	            return true;

	        } else {
	            logger.warn("No candle data for {} token: {} (from={} to={})",
	                    optionType, token, fromDate, toDate);
	            return false;
	        }

	    } catch (Exception e) {
	        logger.error("Failed to fetch {} prev day data for strike {}: {}",
	                optionType, strikePrice, e.getMessage());
	        return false;
	    }
	}

	private void sleepQuietly(long ms) {
	    try { Thread.sleep(ms); }
	    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
	}

	// =====================================================
	// POPULATE PREV DAY DATA FROM CACHE (SUBSEQUENT CALLS)
	// =====================================================
	private void populatePrevDayDataFromCache(
	        List<StraddlePremiumDto> strikeList, String strategyName) {

	    Map<String, BigDecimal> highCache  = prevHighMap.get(strategyName);
	    Map<String, BigDecimal> lowCache   = prevLowMap.get(strategyName);
	    Map<String, BigDecimal> closeCache = prevCloseMap.get(strategyName);

	    if (highCache == null || lowCache == null || closeCache == null) {
	        logger.warn("Cache not found for strategy: {}", strategyName);
	        return;
	    }

	    for (StraddlePremiumDto dto : strikeList) {

	        if (dto.getCeToken() != null) {
	            String t = dto.getCeToken().getToken();
	            dto.setCePrevHigh(highCache.get(t));
	            dto.setCePrevLow(lowCache.get(t));
	            dto.setCePrevClose(closeCache.get(t));
	        }

	        if (dto.getPeToken() != null) {
	            String t = dto.getPeToken().getToken();
	            dto.setPePrevHigh(highCache.get(t));
	            dto.setPePrevLow(lowCache.get(t));
	            dto.setPePrevClose(closeCache.get(t));
	        }

	        // Combined — SUM (consistent with fetch)
	        if (dto.getCePrevClose() != null && dto.getPePrevClose() != null) {
	            dto.setCombinedPrevClose(
	                dto.getCePrevClose().add(dto.getPePrevClose())
	            );
	        }
	    }
	}

	// =====================================================
	// FETCH CANDLES - WITH RATE LIMITING AND RETRY LOGIC
	// =====================================================
	private static final long CANDLE_API_DELAY_MS = 1000; // 1 second delay (increased from 500ms to avoid rate limits)
	private long lastCandleApiCall = 0;
	private static final int MAX_RETRY_ATTEMPTS = 5;
	private static final long INITIAL_RETRY_DELAY_MS = 2000; // Start with 2 seconds
	
	// =====================================================
	// FIXED: FETCH CANDLES WITH MCX/NSE AWARE TIMING
	// =====================================================
	private JSONArray fetchLatestOneMinuteCandle(
	    SmartConnect smartConnect, 
	    String exchange, 
	    String token
	) throws ParseException {
	    
	    int attempt = 0;
	    long retryDelay = INITIAL_RETRY_DELAY_MS;
	    
	    while (attempt < MAX_RETRY_ATTEMPTS) {
	        try {
	        	// ✅ Replace old rate limit logic with this single thread-safe call
	            enforceRateLimit();
	            
	            LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
	            
	            // ✅ FIX: Exchange-specific trading hours
	            String fromDate;
	            String toDate;
	            
	            if ("MCX".equalsIgnoreCase(exchange)) {
	                // MCX trades 09:00 - 23:55 (extended hours)
	                fromDate = today + " 09:00";  // Changed from 09:15
	                toDate = today + " 23:55";     // Changed from 15:30
	                logger.debug("Using MCX hours: {} to {}", fromDate, toDate);
	            } else {
	                // NSE/NFO trades 09:15 - 15:30
	                fromDate = today + " 09:15";
	                toDate = today + " 15:30";
	                logger.debug("Using NSE hours: {} to {}", fromDate, toDate);
	            }

	            JSONObject req = new JSONObject();
	            req.put("exchange", exchange);
	            req.put("symboltoken", token);
	            req.put("interval", "ONE_MINUTE");
	            req.put("fromdate", fromDate);
	            req.put("todate", toDate);

	            JSONArray result = smartConnect.candleData(req);
	           
	            
	            // ✅ ADDED: Log actual response for debugging
	            if (result == null) {
	                attempt++;
	                logger.warn("Candle data returned NULL for {} token {} (attempt {}/{})", 
	                    exchange, token, attempt, MAX_RETRY_ATTEMPTS);
	                
	                if (attempt < MAX_RETRY_ATTEMPTS) {
	                    logger.info("Retrying after {}ms...", retryDelay);
	                    Thread.sleep(retryDelay);
	                    retryDelay *= 2;
	                } else {
	                    logger.error("Max retry attempts reached for {} token {}. Giving up.", 
	                        exchange, token);
	                    return null;
	                }
	            } else if (result.length() == 0) {
	                // ✅ NEW: Log when empty array is returned
	                logger.warn("Candle data returned EMPTY array for {} token {} (time range: {} to {})", 
	                    exchange, token, fromDate, toDate);
	                
	                // Check if we're querying outside trading hours
	                LocalDateTime currentTime = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
	                int currentHour = currentTime.getHour();
	                
	                if ("MCX".equalsIgnoreCase(exchange) && currentHour < 9) {
	                    logger.error("⚠️ MCX market hasn't opened yet (current hour: {})", currentHour);
	                } else if (!"MCX".equalsIgnoreCase(exchange) && currentHour < 9) {
	                    logger.error("⚠️ NSE market hasn't opened yet (current hour: {})", currentHour);
	                }
	                
	                return null;
	            } else {
	                // Success - log details
	                if (attempt > 0) {
	                    logger.info("✓ Successfully fetched {} candles for {} token {} after {} retries", 
	                        result.length(), exchange, token, attempt);
	                } else {
	                    logger.debug("✓ Fetched {} candles for {} token {}", 
	                        result.length(), exchange, token);
	                }
	                return result;
	            }
	            
	        } catch (InterruptedException e) {
	            logger.warn("Thread interrupted during candle fetch/retry", e);
	            Thread.currentThread().interrupt();
	            return null;
	        } catch (Exception e) {
	            attempt++;
	            logger.error("Error fetching candle data for {} token {} (attempt {}/{}): {}", 
	                exchange, token, attempt, MAX_RETRY_ATTEMPTS, e.getMessage());
	            
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
	                logger.error("Max retry attempts reached for {} token {} after exceptions", 
	                    exchange, token);
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
			return lastWorkingDay.toString().concat(priceUtilService.getHourAndMinutes(timeline, 5, type));
		} else {
			return new SimpleDateFormat("yyyy-MM-dd").format(new Date())
					.concat(priceUtilService.getHourAndMinutes(timeline, 5, type));
		}
	}
	
	// =====================================================
	// INCREMENTAL VWAP UPDATE - FIXED
	// =====================================================
	private BigDecimal updateVwapIncremental(String token, JSONArray candleArr) {

	    for (int i = 0; i < candleArr.length(); i++) {
	        JSONArray c = candleArr.getJSONArray(i);

	        // ============================================================
	        // 🚨 FIX: Extract as String and compare alphabetically
	        // ============================================================
	        String candleTimestamp = c.getString(0); 
	        String lastSeen = lastProcessedTimestamp.getOrDefault(token, "");

	        // If current candle is not newer than last processed, skip it
	        if (!lastSeen.isEmpty() && candleTimestamp.compareTo(lastSeen) <= 0) {
	            continue; 
	        }
	        // ============================================================

	        BigDecimal high   = c.getBigDecimal(2);
	        BigDecimal low    = c.getBigDecimal(3);
	        BigDecimal close  = c.getBigDecimal(4);
	        BigDecimal volume = c.getBigDecimal(5);

	        if (volume.compareTo(BigDecimal.ZERO) == 0) {
	            continue;
	        }

	        BigDecimal tp = high.add(low).add(close)
	            .divide(BigDecimal.valueOf(3), 6, RoundingMode.HALF_UP);

	        tpvMap.put(token, tpvMap.getOrDefault(token, BigDecimal.ZERO).add(tp.multiply(volume)));
	        volMap.put(token, volMap.getOrDefault(token, BigDecimal.ZERO).add(volume));

	        // Update the "Last Seen" marker with the String
	        lastProcessedTimestamp.put(token, candleTimestamp);
	    }

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
	
	// =====================================================
	// 🔥 TRADING SIGNAL CALCULATION METHODS
	// =====================================================

	

	// =====================================================
	// HELPER METHOD: Calculate % change
	// =====================================================
	private BigDecimal calculatePercentChange(BigDecimal current, BigDecimal open) {
	    if (current == null || open == null || open.compareTo(BigDecimal.ZERO) == 0) {
	        return BigDecimal.ZERO;
	    }
	    
	    return current.subtract(open)
	        .divide(open, 4, RoundingMode.HALF_UP)
	        .multiply(BigDecimal.valueOf(100))
	        .setScale(2, RoundingMode.HALF_UP);
	}

	// =====================================================
	// HELPER METHOD: Calculate extrinsic ratio
	// =====================================================
	private BigDecimal calculateExtrinsicRatio(
		    BigDecimal ceExtrinsic,
		    BigDecimal peExtrinsic,
		    BigDecimal combinedPremium
		) {
		    if (combinedPremium == null || combinedPremium.signum() <= 0) {
		        return BigDecimal.ZERO;
		    }

		    BigDecimal safeCe = ceExtrinsic == null ? BigDecimal.ZERO : ceExtrinsic.max(BigDecimal.ZERO);
		    BigDecimal safePe = peExtrinsic == null ? BigDecimal.ZERO : peExtrinsic.max(BigDecimal.ZERO);

		    BigDecimal totalExtrinsic = safeCe.add(safePe);

		    // 🔒 Prevent explosion on collapsed premiums
		    if (combinedPremium.compareTo(BigDecimal.ONE) < 0) {
		        return BigDecimal.ZERO;
		    }

		    BigDecimal ratio = totalExtrinsic
		        .divide(combinedPremium, 4, RoundingMode.HALF_UP)
		        .multiply(BigDecimal.valueOf(100));

		    // 🔒 Final financial sanity
		    return ratio
		        .max(BigDecimal.ZERO)
		        .min(BigDecimal.valueOf(200))   // allow volatility spikes
		        .setScale(2, RoundingMode.HALF_UP);
		}


	// =====================================================
	// HELPER METHOD: Check if near ATM
	// =====================================================
	private boolean isNearATM(BigDecimal spot, BigDecimal strike, BigDecimal range) {
	    if (spot == null || strike == null) {
	        return false;
	    }
	    
	    BigDecimal diff = spot.subtract(strike).abs();
	    return diff.compareTo(range) <= 0;
	}

	// =====================================================
	// HELPER METHOD: Calculate ratio (CE/PE)
	// =====================================================
	private BigDecimal calculateRatio(BigDecimal value1, BigDecimal value2) {
	    if (value1 == null || value2 == null || value2.compareTo(BigDecimal.ZERO) == 0) {
	        return BigDecimal.ZERO;
	    }
	    
	    return value1.divide(value2, 2, RoundingMode.HALF_UP);
	}

	// =====================================================
	// HELPER METHOD: Determine directional bias
	// =====================================================
	private String determineDirectionalBias(BigDecimal ceChangePct, BigDecimal peChangePct) {
	    if (ceChangePct == null || peChangePct == null) {
	        return "NEUTRAL";
	    }
	    
	    // CE rising + PE falling = Bullish
	    if (ceChangePct.compareTo(BigDecimal.valueOf(5)) > 0 && 
	        peChangePct.compareTo(BigDecimal.valueOf(-5)) < 0) {
	        return "BULLISH";
	    }
	    
	    // PE rising + CE falling = Bearish
	    if (peChangePct.compareTo(BigDecimal.valueOf(5)) > 0 && 
	        ceChangePct.compareTo(BigDecimal.valueOf(-5)) < 0) {
	        return "BEARISH";
	    }
	    
	    return "NEUTRAL";
	}

	// =====================================================
	// HELPER METHOD: Determine primary signal
	// =====================================================
	private String determinePrimarySignal(
	    BigDecimal ceChangePct,
	    BigDecimal peChangePct,
	    BigDecimal extrinsicRatio,
	    boolean isAtm,
	    String directionalBias
	) {
	    if (ceChangePct == null || peChangePct == null || extrinsicRatio == null) {
	        return "NEUTRAL";
	    }
	    
	    // 1. PREMIUM DECAY (both falling)
	    if (ceChangePct.compareTo(BigDecimal.valueOf(-5)) < 0 && 
	        peChangePct.compareTo(BigDecimal.valueOf(-5)) < 0) {
	        
	        // Straddle Sell Setup: Decay happening + ATM + Low extrinsic
	        if (isAtm && extrinsicRatio.compareTo(BigDecimal.valueOf(30)) < 0) {
	            return "STRADDLE_SELL_SETUP";
	        }
	        
	        return "PREMIUM_DECAY";
	    }
	    
	    // 2. PREMIUM SURGE (both rising)
	    if (ceChangePct.compareTo(BigDecimal.valueOf(5)) > 0 && 
	        peChangePct.compareTo(BigDecimal.valueOf(5)) > 0) {
	        
	        // Straddle Buy Setup: Surge + High extrinsic (volatility expected)
	        if (extrinsicRatio.compareTo(BigDecimal.valueOf(60)) > 0) {
	            return "STRADDLE_BUY_SETUP";
	        }
	        
	        return "PREMIUM_SURGE";
	    }
	    
	    // 3. DIRECTIONAL MOVES (avoid straddle)
	    if ("BULLISH".equals(directionalBias)) {
	        return "BULLISH_MOVE";
	    }
	    
	    if ("BEARISH".equals(directionalBias)) {
	        return "BEARISH_MOVE";
	    }
	    
	    // 4. RANGE BOUND (both stable)
	    if (ceChangePct.abs().compareTo(BigDecimal.valueOf(3)) < 0 && 
	        peChangePct.abs().compareTo(BigDecimal.valueOf(3)) < 0) {
	        return "RANGE_BOUND";
	    }
	    
	    return "NEUTRAL";
	}

	// =====================================================
	// HELPER METHOD: Determine secondary signal
	// =====================================================
	private String determineSecondarySignal(
	    BigDecimal volumeRatio,
	    BigDecimal oiRatio,
	    BigDecimal extrinsicRatio,
	    boolean isAtm
	) {
	    // ATM takes priority
	    if (isAtm) {
	        return "ATM_STRIKE";
	    }
	    
	    // High extrinsic (good to sell options)
	    if (extrinsicRatio != null && extrinsicRatio.compareTo(BigDecimal.valueOf(70)) > 0) {
	        return "HIGH_EXTRINSIC";
	    }
	    
	    // Low extrinsic (avoid selling)
	    if (extrinsicRatio != null && extrinsicRatio.compareTo(BigDecimal.valueOf(20)) < 0) {
	        return "LOW_EXTRINSIC";
	    }
	    
	    // Volume spike detection
	    if (volumeRatio != null) {
	        if (volumeRatio.compareTo(BigDecimal.valueOf(2.5)) > 0) {
	            return "CE_VOLUME_SPIKE";
	        }
	        if (volumeRatio.compareTo(BigDecimal.valueOf(0.4)) < 0) {
	            return "PE_VOLUME_SPIKE";
	        }
	    }
	    
	    // OI imbalance detection
	    if (oiRatio != null) {
	        if (oiRatio.compareTo(BigDecimal.valueOf(1.5)) > 0) {
	            return "HIGH_CE_OI";
	        }
	        if (oiRatio.compareTo(BigDecimal.valueOf(0.67)) < 0) {
	            return "HIGH_PE_OI";
	        }
	    }
	    
	    return null; // No secondary signal
	}

	// =====================================================
	// HELPER METHOD: Calculate signal strength (1-5)
	// =====================================================
	private int calculateSignalStrength(
	    String primarySignal,
	    BigDecimal ceChangePct,
	    BigDecimal peChangePct,
	    BigDecimal extrinsicRatio
	) {
	    if (primarySignal == null || "NEUTRAL".equals(primarySignal)) {
	        return 1;
	    }
	    
	    int strength = 3; // Base strength
	    
	    // Increase strength based on magnitude of change
	    if (ceChangePct != null && peChangePct != null) {
	        BigDecimal avgChange = ceChangePct.add(peChangePct)
	            .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP)
	            .abs();
	        
	        if (avgChange.compareTo(BigDecimal.valueOf(15)) > 0) {
	            strength = 5; // Very strong signal
	        } else if (avgChange.compareTo(BigDecimal.valueOf(10)) > 0) {
	            strength = 4; // Strong signal
	        }
	    }
	    
	    // Boost for straddle setups with ideal conditions
	    if ("STRADDLE_BUY_SETUP".equals(primarySignal) && 
	        extrinsicRatio != null && extrinsicRatio.compareTo(BigDecimal.valueOf(70)) > 0) {
	        strength = Math.min(5, strength + 1);
	    }
	    
	    if ("STRADDLE_SELL_SETUP".equals(primarySignal) && 
	        extrinsicRatio != null && extrinsicRatio.compareTo(BigDecimal.valueOf(25)) < 0) {
	        strength = Math.min(5, strength + 1);
	    }
	    
	    return strength;
	}
	
	/**
	 * Fetch Implied Volatility from Angel One Option Greeks API
	 * Returns Map<StrikePrice_OptionType, IV> for easy lookup
	 */
	private Map<String, BigDecimal> fetchIVFromGreeksAPI(
	    SmartConnect smartConnect,
	    String name,
	    String expiry
	) {
	    
	    Map<String, BigDecimal> ivMap = new HashMap<>();
	    
	    try {
	        JSONObject request = new JSONObject();
	        request.put("name", name);           // e.g., "CRUDEOIL", "NIFTY"
	        request.put("expirydate", normalizeExpiry(expiry));   // e.g., "06JAN2026"
	        
	        logger.info("Fetching Greeks for {} expiry {}", name, expiry);
	        
	        // Call Angel One's optionGreek API
	        JSONObject response = smartConnect.optionGreek(request);
	        
	        if (response == null || !response.has("data")) {
	            logger.warn("No Greeks data returned for {} {}", name, expiry);
	            return ivMap;
	        }
	        if (!response.optBoolean("status", false)) {
	        	logger.warn("No Greeks data returned for {} {}", name, expiry);
	            return ivMap;
	        }
	        JSONArray data = response.getJSONArray("data");
	        logger.info("Received Greeks data for {} strikes", data.length());
	        
	        for (int i = 0; i < data.length(); i++) {
	            JSONObject item = data.getJSONObject(i);
	            
	            try {
	                String optionType = item.optString("optionType", "");  // "CE" or "PE"
	                BigDecimal strike = item.optBigDecimal("strikePrice", null);
	                BigDecimal iv = item.optBigDecimal("impliedVolatility", null);
	                
	                if (strike != null && iv != null && !optionType.isEmpty()) {
	                    
	                    // ✅ VALIDATE: IV must be positive (can't be zero or negative)
	                    if (iv.compareTo(BigDecimal.ZERO) <= 0) {
	                        logger.warn("Invalid IV value {} for {} strike {} - skipping", 
	                            iv, optionType, strike);
	                        continue; // Skip this entry
	                    }
	                    
	                    String key = strike.intValue() + "_" + optionType;
	                    ivMap.put(key, iv);
	                    
	                    logger.debug("Mapped: {} -> IV = {}", key, iv);
	                } else {
	                    logger.warn("Incomplete Greeks data at index {}: strike={}, iv={}, optionType={}", 
	                        i, strike, iv, optionType);
	                }
	                
	            } catch (Exception e) {
	                logger.warn("Failed to parse Greeks item: {}", e.getMessage());
	            }
	        }
	        
	        logger.info("Successfully fetched IV for {} option strikes", ivMap.size());
	        
	    } catch (Exception | SmartAPIException e) {
	        logger.error("Failed to fetch IV from Greeks API: {}", e.getMessage(), e);
	    }
	    
	    return ivMap;
	}
	
	public static String normalizeExpiry(String shortExpiry) {
	    // Example input: 14JAN26
	    if (shortExpiry == null || shortExpiry.length() != 7) {
	        throw new IllegalArgumentException("Invalid expiry format");
	    }

	    String day = shortExpiry.substring(0, 2);
	    String month = shortExpiry.substring(2, 5);
	    String year2 = shortExpiry.substring(5, 7);

	    int year = Integer.parseInt(year2);

	    // NSE/MCX logic: assume 2000+
	    year += 2000;

	    return day + month + year;
	}

	/**
	 * Populate IV values into strike list from Greeks API data
	 */
	private void populateIVFromGreeksMap(
		    List<StraddlePremiumDto> strikeList,
		    Map<String, BigDecimal> ivMap
		) {
		    
		    int ceCount = 0;
		    int peCount = 0;
		    
		    logger.info("=== IV POPULATION DEBUG ===");
		    logger.info("Total IV entries in map: {}", ivMap.size());
		    
		    for (StraddlePremiumDto dto : strikeList) {
		        
		        if (dto.getStrikePrice() == null) continue;
		        
		        int strike = dto.getStrikePrice().intValue();
		        
		        // Lookup CE IV
		        String ceKey = strike + "_CE";
		        BigDecimal ceIV = ivMap.get(ceKey);
		        
		        // ✅ Only set if valid (not null AND not zero)
		        if (ceIV != null && ceIV.compareTo(BigDecimal.ZERO) > 0) {
		            dto.setCeIv(ceIV);
		            ceCount++;
		            logger.debug("✓ Set CE IV for strike {}: {}", strike, ceIV);
		        } else {
		            logger.warn("✗ Invalid/Zero CE IV for strike {} (value: {})", strike, ceIV);
		            dto.setCeIv(null); // ✅ Explicitly set to null
		        }
		        
		        // Lookup PE IV
		        String peKey = strike + "_PE";
		        BigDecimal peIV = ivMap.get(peKey);
		        
		        // ✅ Only set if valid (not null AND not zero)
		        if (peIV != null && peIV.compareTo(BigDecimal.ZERO) > 0) {
		            dto.setPeIv(peIV);
		            peCount++;
		            logger.debug("✓ Set PE IV for strike {}: {}", strike, peIV);
		        } else {
		            logger.warn("✗ Invalid/Zero PE IV for strike {} (value: {})", strike, peIV);
		            dto.setPeIv(null); // ✅ Explicitly set to null
		        }
		    }
		    
		    logger.info("IV Population Complete - CE: {}/{}, PE: {}/{}", 
		        ceCount, strikeList.size(), peCount, strikeList.size());
		}
	
	

	/**
	 * Determine IV regime based on instrument-specific thresholds
	 */
	private String determineIVRegime(BigDecimal combinedIV, String instrument) {
	    
	    if (combinedIV == null) {
	        return "UNKNOWN";
	    }
	    
	    // Instrument-specific IV thresholds
	    BigDecimal highThreshold;
	    BigDecimal lowThreshold;
	    
	    switch (instrument.toUpperCase()) {
	        case "NIFTY":
	        case "NIFTY50":
	            highThreshold = BigDecimal.valueOf(18.0);  // >18% is high for NIFTY
	            lowThreshold = BigDecimal.valueOf(10.0);   // <10% is low
	            break;
	            
	        case "BANKNIFTY":
	            highThreshold = BigDecimal.valueOf(20.0);  // >20% is high for BANKNIFTY
	            lowThreshold = BigDecimal.valueOf(12.0);   // <12% is low
	            break;
	            
	        case "CRUDEOIL":
	        case "CRUDE":
	            highThreshold = BigDecimal.valueOf(40.0);  // >40% is high for CRUDE
	            lowThreshold = BigDecimal.valueOf(20.0);   // <20% is low
	            break;
	            
	        case "FINNIFTY":
	            highThreshold = BigDecimal.valueOf(19.0);
	            lowThreshold = BigDecimal.valueOf(11.0);
	            break;
	            
	        default:
	            // Generic thresholds
	            highThreshold = BigDecimal.valueOf(30.0);
	            lowThreshold = BigDecimal.valueOf(15.0);
	    }
	    
	    if (combinedIV.compareTo(highThreshold) > 0) {
	        return "HIGH";
	    } else if (combinedIV.compareTo(lowThreshold) < 0) {
	        return "LOW";
	    } else {
	        return "NORMAL";
	    }
	}

	/**
	 * Determine trading strategy based on IV levels
	 */
	private String determineIVStrategy(BigDecimal avgIV, BigDecimal ivSkew) {
	    
	    // These thresholds are for CRUDE OIL - adjust based on instrument
	    // For NIFTY, thresholds would be different (typically 12-20 range)
	    
	    if (avgIV.compareTo(BigDecimal.valueOf(40)) > 0) {
	        return "HIGH_IV_SELL"; // Sell premium (straddle/strangle sellers)
	    }
	    
	    if (avgIV.compareTo(BigDecimal.valueOf(20)) < 0) {
	        return "LOW_IV_BUY"; // Buy premium (straddle/strangle buyers)
	    }
	    
	    // Check for skew
	    if (ivSkew.abs().compareTo(BigDecimal.valueOf(5)) > 0) {
	        return ivSkew.compareTo(BigDecimal.ZERO) > 0 
	            ? "CALL_SKEW" 
	            : "PUT_SKEW";
	    }
	    
	    return "NEUTRAL_IV";
	}
	
	/**
	 * Calculate Combined IV (average of CE and PE IV)
	 * Treats ZERO as invalid data (same as null)
	 */
	private BigDecimal calculateCombinedIV(BigDecimal ceIV, BigDecimal peIV) {
	    
	    // ✅ Treat zero as invalid (IV can't be 0% in practice)
	    boolean ceValid = ceIV != null && ceIV.compareTo(BigDecimal.ZERO) > 0;
	    boolean peValid = peIV != null && peIV.compareTo(BigDecimal.ZERO) > 0;
	    
	    // Both invalid - return null
	    if (!ceValid && !peValid) {
	        logger.debug("Both CE IV and PE IV are invalid (null or zero)");
	        return null;
	    }
	    
	    // Only CE valid
	    if (ceValid && !peValid) {
	        logger.debug("Using only CE IV: {}", ceIV);
	        return ceIV;
	    }
	    
	    // Only PE valid
	    if (!ceValid && peValid) {
	        logger.debug("Using only PE IV: {}", peIV);
	        return peIV;
	    }
	    
	    // Both valid - return average
	    BigDecimal combined = ceIV.add(peIV)
	        .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
	    
	    logger.debug("Combined IV: ({} + {}) / 2 = {}", ceIV, peIV, combined);
	    
	    return combined;
	}
	
	// =====================================================
	// ADD THIS METHOD TO YOUR EXISTING StraddleIntradayService.java
	// =====================================================

	/**
	 * Pre-market data fetching (9:10 AM)
	 * Fetches LTP and previous day data WITHOUT saving to StraddleIntraday
	 * Returns list of strikes for pre-market analysis
	 */
	public List<StraddlePremiumDto> getPreMarketLTP(String name) {
	    
	    try {
	        SmartConnect smartconnect = angelOne.signIn();
	        
	        if (smartconnect == null) {
	            logger.error("Failed to sign in to Angel One for pre-market");
	            return new ArrayList<>();
	        }

	        Strategy strategy = strategyRepo.findByName(name);
	        
	        if (strategy == null) {
	            logger.error("Strategy not found: {}", name);
	            return new ArrayList<>();
	        }
	        
	        logger.info("=== PRE-MARKET DATA FETCH STARTED FOR {} ===", name);
	        
	        // 1. Get spot price
	        String session = sessionManager.getSession();
	        BigDecimal spotPrice = null;
	        if ("NIFTY".equalsIgnoreCase(name) || "SENSEX".equalsIgnoreCase(name)) {
	            spotPrice = samco.getIndexPrice(session, name);
	            //If Samco fails , use the angelone price
	            //spotPrice = angelOneService.getcurrentPrice(smartconnect, strategy.getExchange(), strategy.getSymbol(), strategy.getToken());
	        } else if ("CRUDEOIL".equalsIgnoreCase(name) || "CRUDEOILM".equalsIgnoreCase(name) || "NATURALGAS".equalsIgnoreCase(name)) {
	            spotPrice = samco.getLtp(session, strategy.getExchange(), getSymbolByName(name));
	        }
	        
	        if (spotPrice == null || spotPrice.compareTo(BigDecimal.ZERO) <= 0) {
	            logger.error("Invalid spot price for {}: {}", name, spotPrice);
	            return new ArrayList<>();
	        }
	        
	        logger.info("Pre-market spot price for {}: {}", name, spotPrice);

	        // 2. Calculate ATM strike
	        BigDecimal atmStrike = getATMStrike(name, strategy, spotPrice);
	        
	        if (atmStrike == null || atmStrike.compareTo(BigDecimal.ZERO) <= 0) {
	            logger.error("Invalid ATM strike for {}: {}", name, atmStrike);
	            return new ArrayList<>();
	        }
	        
	        logger.info("Pre-market ATM strike for {}: {}", name, atmStrike);
	        
	        // 3. Build strike list (reuse existing method)
	        List<StraddlePremiumDto> strikeList = getOrBuildStrikeList(name, atmStrike);
	        
	        // 4. Get token details (reuse existing method)
	        strikeList = getAllTokenDetails(strikeList, strategy);
	        
	        //5. Keep only atmStrike
	        strikeList = strikeList.stream()
	                .filter(dto -> dto.getStrikePrice().compareTo(atmStrike) == 0)
	                .collect(Collectors.toList());
	        
	        long validTokenCount = strikeList.stream()
	            .filter(dto -> dto.getCeToken() != null || dto.getPeToken() != null)
	            .count();
	            
	        if (validTokenCount == 0) {
	            logger.error("No valid tokens found for pre-market: {}", name);
	            return new ArrayList<>();
	        }

	        logger.info("ATM strike ready with valid tokens for pre-market");

	    	        
	     // ⚠️ ADD DELAY BEFORE BATCH PRICE FETCH
	        logger.info("Waiting 2 seconds before fetching pre-market LTP (rate limit prevention)...");
	        Thread.sleep(2000); // 2 second delay

	        // 6. Fetch current LTP,High,Low (reuse existing method)
	        strikeList = getPriceForAllTheStrikesBatch(strikeList, smartconnect, strategy.getExchange());
	        
	        long validPriceCount = strikeList.stream()
	            .filter(dto -> 
	                (dto.getCePrice() != null && dto.getCePrice().compareTo(BigDecimal.ZERO) > 0) ||
	                (dto.getPePrice() != null && dto.getPePrice().compareTo(BigDecimal.ZERO) > 0)
	            )
	            .count();
	            
	        if (validPriceCount == 0) {
	            logger.error("No valid prices fetched for pre-market: {}", name);
	            return new ArrayList<>();
	        }
	        
	        logger.info("Successfully fetched pre-market prices for {} strikes", validPriceCount);

	     // Step 7 — fetch prev high/low for ATM
	        resetPrevDayDataIfNewDay();

	        Map<String, BigDecimal> strategyHighCache = prevHighMap.get(name);
	        Map<String, BigDecimal> strategyLowCache  = prevLowMap.get(name);

			if (strategyHighCache == null || strategyLowCache == null || strategyHighCache.isEmpty()
					|| strategyLowCache.isEmpty()) {

				fetchPreviousDayDataForAllStrikes(strikeList, smartconnect, strategy); // ✅ new merged method
			} else {
				populatePrevDayDataFromCache(strikeList, name);
			}

	        // ✅ Reset cache after pre-market use
	        // So 9:15 intraday gets a fresh fetch for ALL strikes
	        prevHighMap.remove(name);
	        prevLowMap.remove(name);
	        
	        logger.info("✓ PRE-MARKET DATA FETCH COMPLETED: {} strikes ready", strikeList.size());
	        
	        // 8. Return list WITHOUT saving to database
	        return strikeList;

	    } catch (Exception e) {
	        logger.error("Error in getPreMarketLTP for {}", name, e);
	        return new ArrayList<>();
	    }
	}
	
	private List<StraddlePremiumDto> getOnlyAtmStrikeList(BigDecimal atmStrike) {

	    StraddlePremiumDto dto = new StraddlePremiumDto();
	    dto.setStrikePrice(atmStrike);

	    return Collections.singletonList(dto);
	}
	
	/**
	 * Get ALL time-series data (no limit)
	 * Returns complete price history for the strike
	 */
	public List<StraddleIntraday> getTimeSeriesByStrike(String name, String expiry, BigDecimal strike) {
	    List<StraddleIntraday> records = straddleIntradayRepo
	        .findByNameAndExpiryAndStrikeOrderByTimestampDesc(name, expiry, strike);
	    
	    Collections.reverse(records);
	    return records;
	}
	// =====================================================
	// FETCH PREVIOUS DAY HIGH + LOW + CLOSE — ONE CALL PER TOKEN
	// Replaces: fetchPreviousDayHighLowForAllStrikes()
//	         + fetchPreviousDayCloseForAllStrikes()
	// =====================================================
	private void fetchPreviousDayDataForAllStrikes(
	        List<StraddlePremiumDto> strikeList,
	        SmartConnect smartConnect,
	        Strategy strategy) {

	    logger.info("=== FETCHING PREVIOUS DAY OHLC (ONE-TIME FOR {}) ===", strategy.getName());

	    prevHighMap.putIfAbsent(strategy.getName(),  new HashMap<>());
	    prevLowMap.putIfAbsent(strategy.getName(),   new HashMap<>());
	    prevCloseMap.putIfAbsent(strategy.getName(), new HashMap<>());

	    Map<String, BigDecimal> highCache  = prevHighMap.get(strategy.getName());
	    Map<String, BigDecimal> lowCache   = prevLowMap.get(strategy.getName());
	    Map<String, BigDecimal> closeCache = prevCloseMap.get(strategy.getName());

	    // ── Date range ──────────────────────────────────────────
	    LocalDate today        = LocalDate.now(ZoneId.of("Asia/Kolkata"));
	    LocalDate tradingDate  = NSEWorkingDays.isNSEWorkingDay(today)
	                             ? today : NSEWorkingDays.getLastWorkingDay(today);
	    LocalDate previousWD   = NSEWorkingDays.getLastWorkingDay(tradingDate);
	    LocalDate dayBeforePrevWD = NSEWorkingDays.getLastWorkingDay(previousWD); // ← ADD THIS

	    String fromDate = dayBeforePrevWD + " 15:30";  // Mar 06 15:30 ✅
	    String toDate   = previousWD      + " 15:30";  // Mar 09 15:30 ✅

	    logger.info("Prev day date range → from={} to={}", fromDate, toDate);

	    int successCount = 0;
	    int failureCount = 0;

	    for (StraddlePremiumDto dto : strikeList) {

	        // ── CE ──
	        if (dto.getCeToken() != null) {
	            boolean ok = fetchAndCacheOHLC(
	                    smartConnect, strategy, dto.getCeToken().getToken(),
	                    fromDate, toDate,
	                    highCache, lowCache, closeCache,
	                    high  -> dto.setCePrevHigh(high),
	                    low   -> dto.setCePrevLow(low),
	                    close -> dto.setCePrevClose(close),
	                    "CE", dto.getStrikePrice());
	            if (ok) successCount++; else failureCount++;
	        }

	        // ── PE ──
	        if (dto.getPeToken() != null) {
	            boolean ok = fetchAndCacheOHLC(
	                    smartConnect, strategy, dto.getPeToken().getToken(),
	                    fromDate, toDate,
	                    highCache, lowCache, closeCache,
	                    high  -> dto.setPePrevHigh(high),
	                    low   -> dto.setPePrevLow(low),
	                    close -> dto.setPePrevClose(close),
	                    "PE", dto.getStrikePrice());
	            if (ok) successCount++; else failureCount++;
	        }

	        // ── Combined prev close — SUM ──
	        if (dto.getCePrevClose() != null && dto.getPePrevClose() != null) {
	            dto.setCombinedPrevClose(
	                dto.getCePrevClose().add(dto.getPePrevClose())
	            );
	        }
	     // Inside fetchPreviousDayDataForAllStrikes
	        if (dto.getCePrevLow() != null && dto.getPePrevLow() != null) {
	            // 🟢 Combined Low = CE Low + PE Low
	            dto.setCombinedPrevLow(dto.getCePrevLow().add(dto.getPePrevLow()));
	        }
	    }

	    logger.info("Prev day OHLC fetch complete for {}: Success={}, Failure={}",
	            strategy.getName(), successCount, failureCount);
	}

	// ── Single token — extracts High + Low + Close in ONE candleData() call ──────
	private boolean fetchAndCacheOHLC(
	        SmartConnect smartConnect,
	        Strategy strategy,
	        String token,
	        String fromDate, String toDate,
	        Map<String, BigDecimal> highCache,
	        Map<String, BigDecimal> lowCache,
	        Map<String, BigDecimal> closeCache,
	        java.util.function.Consumer<BigDecimal> highSetter,
	        java.util.function.Consumer<BigDecimal> lowSetter,
	        java.util.function.Consumer<BigDecimal> closeSetter,
	        String optionType,
	        BigDecimal strikePrice) {

	    try {
	        JSONObject req = new JSONObject();
	        req.put("exchange",    strategy.getExchange());
	        req.put("symboltoken", token);
	        req.put("interval",    "ONE_DAY");
	        req.put("fromdate",    fromDate);
	        req.put("todate",      toDate);

	        sleepQuietly(350); // rate limit delay

	        JSONArray candles = fetchCandleWithRetry(smartConnect, req, token);

	        if (candles != null && candles.length() > 0) {
	            JSONArray last = candles.getJSONArray(candles.length() - 1);

	            // ONE call — extract all three
	            BigDecimal high  = last.getBigDecimal(2);
	            BigDecimal low   = last.getBigDecimal(3);
	            BigDecimal close = last.getBigDecimal(4);

	            highCache.put(token,  high);
	            lowCache.put(token,   low);
	            closeCache.put(token, close);

	            highSetter.accept(high);
	            lowSetter.accept(low);
	            closeSetter.accept(close);

	            logger.debug("{} Strike {}: PrevHigh={} PrevLow={} PrevClose={}",
	                    optionType, strikePrice, high, low, close);
	            return true;

	        } else {
	            logger.warn("No candle data for {} token: {} (from={} to={})",
	                    optionType, token, fromDate, toDate);
	            return false;
	        }

	    } catch (Exception e) {
	        logger.error("Failed to fetch {} prev day OHLC for strike {}: {}",
	                optionType, strikePrice, e.getMessage());
	        return false;
	    }
	}
	
	private boolean isAlertAlreadySent(BigDecimal strike, AlertType alertType) {
	    String key = strike.toPlainString() + "_" + alertType.name();
	    LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));

	    LocalDateTime lastSent = sentAlertKeys.get(key);

	    if (lastSent != null) {
	        long minutesSinceLastSent = java.time.Duration.between(lastSent, now).toMinutes();
	        if (minutesSinceLastSent < ALERT_COOLDOWN_MINUTES) {
	            logger.debug("Cooldown active [{}] for strike {} — {}m since last alert (cooldown: {}m)",
	                alertType, strike, minutesSinceLastSent, ALERT_COOLDOWN_MINUTES);
	            return true; // still in cooldown
	        }
	    }

	    // Update timestamp on every allowed send
	    sentAlertKeys.put(key, now);
	    return false;
	}
	
	/**
	 * Backfills VWAP data from market open to current time.
	 * This ensures VWAP is accurate even if the system restarts midday.
	 */
	public void warmUpVwap(String name, Strategy strategy) {
        logger.info("🔥 Starting VWAP warm-up for {}", name);
        
        // 1. Get the ATM strike to identify which tokens to warm up
        // In a full system, you might want to warm up all strikes in your range
        BigDecimal spotPrice = null; 
        try {
            String session = sessionManager.getSession();
            spotPrice = samco.getIndexPrice(session,name); // Fallback to your spot fetch logic
            
            if (spotPrice == null) return;

            BigDecimal atmStrike = getATMStrike(name, strategy, spotPrice);
            
            // DYNAMIC INTERVAL LOGIC ADDED HERE
            int stepInterval = 50; 
            if (name != null) {
                String upperName = name.toUpperCase();
                if (upperName.contains("SENSEX") || upperName.contains("BANK") || upperName.contains("CRUDEOIL")) {
                    stepInterval = 100;
                } else if (upperName.contains("NATURALGAS")) {
                    stepInterval = 5;
                }
            }
            
            // REPLACED HARDCODED 50 WITH stepInterval
            List<StraddlePremiumDto> warmUpList = buildStraddleDtos(name, atmStrike, stepInterval);
            warmUpList = getAllTokenDetails(warmUpList, strategy);

            SmartConnect smartconnect = angelOne.signIn();

            for (StraddlePremiumDto dto : warmUpList) {
                // Warm up CE
                if (dto.getCeToken() != null) {
                    JSONArray candles = fetchLatestOneMinuteCandle(
                        smartconnect, strategy.getExchange(), dto.getCeToken().getToken());
                    if (candles != null) {
                        updateVwapIncremental(dto.getCeToken().getToken(), candles);
                    }
                }
                // Warm up PE
                if (dto.getPeToken() != null) {
                    JSONArray candles = fetchLatestOneMinuteCandle(
                        smartconnect, strategy.getExchange(), dto.getPeToken().getToken());
                    if (candles != null) {
                        updateVwapIncremental(dto.getPeToken().getToken(), candles);
                    }
                }
            }
            logger.info("✅ VWAP warm-up completed for {}", name);
        } catch (Exception e) {
            logger.error("❌ VWAP warm-up failed for {}", name, e);
        }
    }
	
	private synchronized void enforceRateLimit() throws InterruptedException {
	    long now = System.currentTimeMillis();
	    long timeSinceLastCall = now - lastCandleApiCall;

	    if (timeSinceLastCall < CANDLE_API_DELAY_MS) {
	        long waitTime = CANDLE_API_DELAY_MS - timeSinceLastCall;
	        logger.debug("Rate limiting: waiting {}ms to respect API limits", waitTime);
	        Thread.sleep(waitTime);
	    }
	    
	    // Update the timestamp immediately so the next thread in line calculates the delay correctly
	    lastCandleApiCall = System.currentTimeMillis();
	}
}