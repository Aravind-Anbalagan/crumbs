package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.Range;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.CandlesDetails;
import com.crumbs.trade.dto.PivotRequest;
import com.crumbs.trade.dto.PivotResponse;
import com.crumbs.trade.dto.PriceActionResult;
import com.crumbs.trade.dto.StrategyDTO;
import com.crumbs.trade.dto.Time;
import com.crumbs.trade.entity.Candle;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.Indicator;
import com.crumbs.trade.entity.PSARIndex;
import com.crumbs.trade.entity.PSARMcx;
import com.crumbs.trade.entity.PSARNifty;
import com.crumbs.trade.entity.PricesHeikinAshiIndex;
import com.crumbs.trade.entity.PricesHeikinAshiMcx;
import com.crumbs.trade.entity.PricesHeikinAshiNifty;
import com.crumbs.trade.entity.PricesIndex;
import com.crumbs.trade.entity.PricesMcx;
import com.crumbs.trade.entity.PricesNifty;
import com.crumbs.trade.entity.ResultMcx;
import com.crumbs.trade.entity.ResultNifty;
import com.crumbs.trade.entity.Stoploss;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.CandleRepo;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.IndicatorRepo;
import com.crumbs.trade.repo.NiftyRepo;
import com.crumbs.trade.repo.PriceHeikinashiIndexRepo;
import com.crumbs.trade.repo.PriceHeikinashiMcxRepo;
import com.crumbs.trade.repo.PriceHeikinashiNiftyRepo;
import com.crumbs.trade.repo.PriceRepo;
import com.crumbs.trade.repo.PricesIndexRepo;
import com.crumbs.trade.repo.PricesMcxRepo;
import com.crumbs.trade.repo.PricesNiftyRepo;
import com.crumbs.trade.repo.PsarIndexRepo;
import com.crumbs.trade.repo.PsarMcxRepo;
import com.crumbs.trade.repo.PsarNiftyRepo;
import com.crumbs.trade.repo.ResultMcxRepo;
import com.crumbs.trade.repo.ResultNiftyRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.mail.MessagingException;
import jakarta.transaction.Transactional;
import com.google.common.util.concurrent.RateLimiter;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class TaskService {
	Logger logger = LoggerFactory.getLogger(TaskService.class);
	private static final RateLimiter limiter = RateLimiter.create(5);
	private static final Semaphore RATE_LIMITER = new Semaphore(5); // max 5 calls/sec globally
	private static final ScheduledExecutorService RATE_LIMIT_RELEASE = Executors.newScheduledThreadPool(1);

	// Smart API pool (multi-threaded but limited)
	private static final int SMART_API_THREADS = 5;
	private final ExecutorService smartApiExecutor = Executors.newFixedThreadPool(SMART_API_THREADS);


	    // Single-threaded scheduled executor to pace AI calls safely
	    private final ScheduledExecutorService aiExecutor = Executors.newSingleThreadScheduledExecutor();
	    private final long AI_CALL_INTERVAL_MS = 3000; // 3 seconds between AI calls

	    // Queue to hold AI tasks
	    private final BlockingQueue<Runnable> aiTaskQueue = new LinkedBlockingQueue<>();
	
	public static int MAX;
	public static int MIN;

	@Autowired
	AngelOne angelOne;

	@Autowired
	IndicatorRepo indicatorRepo;

	@Autowired
	IndexesRepo indexesRepo;

	@Autowired
	NiftyRepo niftyRepo;

	@Autowired
	CandleRepo candleRepo;

	@Autowired
	PricesIndexRepo pricesIndexRepo;

	@Autowired
	PriceHeikinashiNiftyRepo priceHeikinashiNiftyRepo;

	@Autowired
	PsarIndexRepo psarIndexRepo;

	@Autowired
	PriceHeikinashiIndexRepo priceHeikinashiIndexRepo;

	@Autowired
	PricesMcxRepo pricesMcxRepo;

	@Autowired
	PricesNiftyRepo pricesNiftyRepo;

	@Autowired
	HeikinAshiCalculator heikinAshiCalculator;

	@Autowired
	PSARCalculator psarCalculator;

	@Autowired
	RSICalculator rsiCalculator;

	@Autowired
	MovingAverageCalculator movingAverageCalculator;

	@Autowired
	BollingerBandsCalculator bollingerBandsCalculator;

	@Autowired
	EmailService emailService;

	@Autowired
	ResultService resultService;

	@Autowired
	VolumeService volumeService;

	@Autowired
	PivotPointService pivotPointService;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	PriceActionService priceActionService;

	@Autowired
	AiService aiService;

	@Autowired
	CombinedSignalService combinedSignalService;

	@Autowired
	StrategyRepo strategyRepo;

	@Autowired
	ChartService chartService;

	@Autowired
	ResultNiftyRepo resultNiftyRepo;

	@Autowired
	PsarNiftyRepo psarNiftyRepo;

	@Autowired
	ResultMcxRepo resultMcxRepo;

	@Autowired
	PriceHeikinashiMcxRepo priceHeikinashiMcxRepo;

	@Autowired
	PsarMcxRepo psarMcxRepo;

	@Autowired
	PriceRepo priceRepo;

	@Value("${app.max-threads}")
	private int maxThreads;

	public void getSupportAndResistance(String indexName, String symbol)
			throws IOException, SmartAPIException, ParseException {
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

		LocalDateTime startTime = LocalDateTime.now();
		logger.info("Processing started at: {}", startTime.format(formatter));
		SmartConnect smartConnect = angelOne.signIn();
		List<Indexes> indexesList = new ArrayList<>();
		indicatorRepo.deleteAll();

		// Update Volume
		// indexesRepo.updateVolume(null);
		// volumeService.checkVolume();

		// Fetch for all the indexes/scripts
		if (indexName.equalsIgnoreCase("ALL")) {
			indexesList = indexesRepo.findAllStocks(Arrays.asList("NSE", "BSE"));
		} else if (indexName.equalsIgnoreCase("NIFTY50")) {
			indexesList = indexesRepo.findByNameIn(niftyRepo.getAllNames());
		} else if (indexName != null) {
			Indexes indexes = indexesRepo.findByNameAndSymbol(indexName, symbol);
			indexesList.add(indexes);
		}

		Map<Long, Candle> candleMap = Stream.of(2L, 3L, 4L, 5L, 6L).map(id -> candleRepo.findById(id).orElse(null))
				.filter(Objects::nonNull).collect(Collectors.toMap(Candle::getId, c -> c));

		/*
		 * STEP 1: LOOK FOR DAY CANDLE
		 */
		 read_Day_Candle(smartConnect, indexesList, candleMap);

		/*
		 * STEP 2: FETCH ONLY FIRST BUY/FIRST SELL INDEXES STEP 2: LOOK FOR WEEKLY
		 * CANDLE - APPLICABLE FOR CERTAIN STOCK
		 */
		read_weekly_candle(smartConnect, fetchIndexes(), candleMap);

		// Combine Signal
		List<Indicator> allIndicators = indicatorRepo.findByOnedayIsNotNullAndOneweekIsNotNull();
		List<Indicator> updated = combinedSignalService.updateCombinedSignals(allIndicators);
		timeDisplay(startTime, formatter);
	}

	private List<Indexes> fetchIndexes() {
		List<Indexes> indexesList = new ArrayList<>();
		List<Indicator> indicators = indicatorRepo.findByPsarFlagDayInAndHeikinAshiDayIn(
				Arrays.asList("FIRST BUY", "FIRST SELL"), Arrays.asList("FIRST BUY", "FIRST SELL"));
		indicators.stream().forEach(symbol -> {
			indexesList.add(indexesRepo.findBySymbol(symbol.getTradingSymbol()));
		});
		logger.info("Eligible Weekly Stock {}",indexesList.size());
		return indexesList;
	}

	public void timeDisplay(LocalDateTime startTime, DateTimeFormatter formatter) {
		LocalDateTime endTime = LocalDateTime.now();
		Duration duration = Duration.between(startTime, endTime);

		String durationFormatted = String.format("%02d:%02d:%02d", duration.toHours(), duration.toMinutesPart(),
				duration.toSecondsPart());

		logger.info("Processing completed.");
		logger.info("Start: {} | End: {} | Duration: {}", startTime.format(formatter), endTime.format(formatter),
				durationFormatted);
	}

	public void read_Day_Candle(SmartConnect smartConnect, List<Indexes> indexesList, Map<Long, Candle> candleMap) {
		// control concurrency
		ExecutorService executor = Executors.newFixedThreadPool(maxThreads);
		AtomicInteger counter = new AtomicInteger(0);

		for (Indexes index : indexesList) {
			executor.submit(() -> {
				try {
					Candle dayCandle = candleMap.get(4L);
					if (dayCandle != null && "Y".equalsIgnoreCase(dayCandle.getActive())) {
						boolean done = false;
						int attempts = 0;
						long backoff = 1000; // start 1 sec

						while (!done && attempts < 5) {
							try {
								getDaysCandleData(index, smartConnect, dayCandle);
								done = true;
							} catch (Exception e) {
								if (isRateLimitError(e)) {
									attempts++;
									logger.warn("Rate limit for {}. Retry in {} ms (attempt {}/{})", index.getName(),
											backoff, attempts, 5);
									Thread.sleep(backoff);
									backoff *= 2;
								} else {
									logger.error("Error processing {}: {}", index.getName(), e.getMessage(), e);
									return;
								}
							}
						}
					}

					if (counter.incrementAndGet() % 100 == 0) {
						Runtime rt = Runtime.getRuntime();
						long used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
						logger.info("Used memory after {} records: {} MB", counter.get(), used);
					}
				} catch (Exception e) {
					logger.error("Unexpected error in {}: {}", index.getName(), e.getMessage(), e);
				}
			});
		}

		executor.shutdown();
		try {
			if (!executor.awaitTermination(2, TimeUnit.HOURS)) {
				logger.warn("Timeout waiting for tasks to finish");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private boolean isRateLimitError(Exception e) {
		if (e == null)
			return false;
		String msg = e.getMessage();
		return msg != null && msg.toLowerCase().contains("rate limit");
	}

	private JSONArray safeCandleData(JSONObject requestObject) {
		JSONArray response = null;
		int retries = 3; // max retries
		int delay = 1000; // 1 second delay between tries

		for (int i = 0; i < retries; i++) {
			try {
				SmartConnect smartConnect = angelOne.signIn();
				response = smartConnect.candleData(requestObject);
				if (response != null) {
					return response; // success
				}
				Thread.sleep(delay); // wait before retry
			} catch (Exception e) {
				e.printStackTrace();
				try {
					Thread.sleep(delay);
				} catch (InterruptedException ignored) {
				}
			}
		}
		return null; // still null after retries
	}

	@Transactional
	public void getDaysCandleData(Indexes index, SmartConnect smartConnect, Candle candle) {
		try {
			// ❌ OLD: pricesIndexRepo.deleteAll();
			// ✅ NEW: delete only for current index & timeframe
			pricesIndexRepo.deleteByNameAndTimeframe(index.getName(), "ONE_DAY");

			Indexes indexes = indexesRepo.findByNameAndSymbol(index.getName(), index.getSymbol());

			JSONObject jsonObject = smartConnect.getLTP(index.getExchange(), index.getSymbol(), index.getToken());
			if (jsonObject != null) {
				BigDecimal index_CurrentPrice = new BigDecimal(String.valueOf(jsonObject.get("ltp")));
				BigDecimal index_OpenPrice = new BigDecimal(String.valueOf(jsonObject.get("open")));

				SimpleDateFormat fromFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
				SimpleDateFormat toFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");

				String fromDate = toFormat.format(fromFormat.parse(calculateDate(candle.getStartTime())));
				String toDateStr = toFormat.format(toFormat.parse(calculateDate(candle.getEndTime())));

				JSONObject requestObject = new JSONObject();
				requestObject.put("exchange", index.getExchange());
				requestObject.put("symboltoken", index.getToken());
				requestObject.put("interval", candle.getTimeFrame());
				requestObject.put("fromdate", fromDate);
				requestObject.put("todate", toDateStr);

				Thread.sleep(1000); // avoid hitting rate limits
				// JSONArray responseArray = smartConnect.candleData(requestObject);
				JSONArray responseArray = safeCandleData(requestObject);

				if (responseArray != null) {
					List<PricesIndex> pricesList = new ArrayList<>();
					responseArray.forEach(item -> {
						JSONArray ohlcArray = (JSONArray) item;
						BigDecimal open = new BigDecimal(String.valueOf(ohlcArray.getDouble(1)));
						BigDecimal high = new BigDecimal(String.valueOf(ohlcArray.getDouble(2)));
						BigDecimal low = new BigDecimal(String.valueOf(ohlcArray.getDouble(3)));
						BigDecimal close = new BigDecimal(String.valueOf(ohlcArray.getDouble(4)));
						BigDecimal volume = new BigDecimal(String.valueOf(ohlcArray.getDouble(5)));
						BigDecimal range = high.subtract(low);

						PricesIndex prices = new PricesIndex();
						prices.setHigh(high);
						prices.setLow(low);
						prices.setClose(close);
						prices.setOpen(open);
						prices.setVolume(volume);
						prices.setRange(range);
						prices.setName(index.getName());
						prices.setTimestamp(ohlcArray.getString(0));
						prices.setType(getPriceType(open, close));
						prices.setTimeframe(candle.getTimeFrame());
						prices.setCpr(calculateCpr(high, low, close).toString());
						prices.setCurrentprice(index_CurrentPrice);
						pricesList.add(prices);
					});

					pricesIndexRepo.saveAll(pricesList);
					pricesIndexRepo.flush(); // force commit before returning

					if ("HOURLY".equalsIgnoreCase(candle.getName())) {
						getHourlyVolumeData(candle.getTimeFrame(), index, index_CurrentPrice, smartConnect, candle,
								index_OpenPrice);
					} else if ("DAY".equalsIgnoreCase(candle.getName())) {
						getDayVolumeData(candle.getTimeFrame(), index, index_CurrentPrice, smartConnect, candle,
								index_OpenPrice);
					}
					// Once work is done - delete the data
					pricesIndexRepo.deleteByNameAndTimeframe(index.getName(), "ONE_DAY");

				} else {
					logger.info("Unable to fetch candle data for {}", index.getName());
				}
			} else {
				logger.info("Script is null {} , {} , {}", index.getExchange(), index.getSymbol(), index.getToken());
			}

		} catch (Exception | SmartAPIException e) {
			logger.error("Err Occured during processing {}, Error {}", index.getName(), e.getMessage());
		}
	}

	public void read_weekly_candle(SmartConnect smartConnect, List<Indexes> indexesList, Map<Long, Candle> candleMap) {
	    ExecutorService executor = Executors.newFixedThreadPool(maxThreads);
	    AtomicInteger counter = new AtomicInteger(0);
	    Candle weeklyCandle = candleMap.get(5L);

	    if (weeklyCandle == null || !"Y".equalsIgnoreCase(weeklyCandle.getActive())) {
	        logger.warn("Weekly candle inactive or missing, skipping all indexes");
	        executor.shutdown();
	        return;
	    }

	    for (Indexes index : indexesList) {
	        executor.submit(() -> {
	            try {
	                int attempts = 0;
	                long backoff = 1000; // 1 second initial

	                while (attempts < 5) {
	                    try {
	                        getWeeklyCandleData(index, smartConnect, weeklyCandle);
	                        break; // success
	                    } catch (SmartAPIException e) {
	                        logger.error("SmartAPI error for {}: {}", index.getName(), e.getMessage(), e);
	                        // wrap checked exception as unchecked to rethrow inside Runnable
	                        throw new RuntimeException(e);
	                    } catch (Exception e) {
	                        if (isRateLimitError(e)) {
	                            attempts++;
	                            logger.warn("Rate limit for {}. Retry in {} ms (attempt {}/{})",
	                                    index.getName(), backoff, attempts, 5);
	                            Thread.sleep(backoff);
	                            backoff *= 2;
	                        } else {
	                            logger.error("Error processing {}: {}", index.getName(), e.getMessage(), e);
	                            return;
	                        }
	                    }
	                }

	                int processed = counter.incrementAndGet();
	                if (processed % 100 == 0) {
	                    Runtime rt = Runtime.getRuntime();
	                    long used = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
	                    logger.info("Used memory after {} records: {} MB", processed, used);
	                }
	            } catch (InterruptedException e) {
	                Thread.currentThread().interrupt();
	                logger.warn("Task interrupted for {}", index.getName());
	            } catch (RuntimeException e) {
	                // unwrap SmartAPIException if wrapped
	                Throwable cause = e.getCause();
	                if (cause instanceof SmartAPIException) {
	                    logger.error("SmartAPIException in {}: {}", index.getName(), cause.getMessage(), cause);
	                } else {
	                    logger.error("Unexpected runtime error in {}: {}", index.getName(), e.getMessage(), e);
	                }
	            } catch (Exception e) {
	                logger.error("Unexpected error in {}: {}", index.getName(), e.getMessage(), e);
	            }
	        });
	    }

	    executor.shutdown();
	    try {
	        if (!executor.awaitTermination(2, TimeUnit.HOURS)) {
	            logger.warn("Timeout waiting for weekly candle tasks to finish");
	        }
	    } catch (InterruptedException e) {
	        Thread.currentThread().interrupt();
	    }
	}



	public void get4HourCandleData(Indexes index, SmartConnect smartConnect, Candle candle) throws SmartAPIException {

		try {
			pricesIndexRepo.deleteAll();
			Indexes indexes = indexesRepo.findByNameAndSymbol(index.getName(), index.getSymbol());

			// Get Current Price
			JSONObject jsonObject = smartConnect.getLTP(index.getExchange(), index.getSymbol(), index.getToken());
			if (jsonObject != null) {
				BigDecimal index_CurrentPrice = new BigDecimal(String.valueOf(jsonObject.get("ltp")));
				BigDecimal index_OpenPrice = new BigDecimal(String.valueOf(jsonObject.get("open")));
				SimpleDateFormat fromFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
				SimpleDateFormat toFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");

				String fromDate = null;
				String toDate = null;

				fromDate = new SimpleDateFormat("yyyy-MM-dd HH:mm")
						.format(fromFormat.parse(calculateDate("52WEEKS_EARLY")));
				toDate = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(toFormat.parse(calculateDate("HOUR")));
				// fromDate="2024-10-06 09:15";
				// toDate="2024-12-13 15:15";

				JSONArray responseArray = new JSONArray();
				JSONObject requestObejct = new JSONObject();
				requestObejct.put("exchange", index.getExchange());
				requestObejct.put("symboltoken", index.getToken());
				requestObejct.put("interval", "ONE_HOUR");
				requestObejct.put("fromdate", fromDate);
				requestObejct.put("todate", toDate);
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				responseArray = smartConnect.candleData(requestObejct);
				// System.out.println(responseArray);
				if (responseArray != null) {
					responseArray.forEach(item -> {
						JSONArray ohlcArray = (JSONArray) item;
						String timeStamp = String.valueOf(ohlcArray.getString(0));
						BigDecimal open = new BigDecimal(String.valueOf(ohlcArray.getDouble(1)));
						BigDecimal high = new BigDecimal(String.valueOf(ohlcArray.getDouble(2)));
						BigDecimal low = new BigDecimal(String.valueOf(ohlcArray.getDouble(3)));
						BigDecimal close = new BigDecimal(String.valueOf(ohlcArray.getDouble(4)));
						BigDecimal volume = new BigDecimal(String.valueOf(ohlcArray.getDouble(5)));
						BigDecimal range = high.subtract(low);
						PricesIndex prices = new PricesIndex();
						prices.setHigh(high);
						prices.setLow(low);
						prices.setClose(close);
						prices.setOpen(open);
						prices.setVolume(volume);
						prices.setRange(range);
						prices.setName(index.getName());
						prices.setTimestamp(timeStamp);
						prices.setType(getPriceType(open, close));
						prices.setTimeframe(index.getTimeFrame());
						// prices.setCpr(calculateCpr(high,low,close).toString());
						pricesIndexRepo.save(prices);
					});

					// Get MOdified Candle Data
					get4HoursCandleData();

					// Get Volume Data
					getFourHourVolumeData(candle.getTimeFrame(), index, index_CurrentPrice, smartConnect, candle,
							index_OpenPrice);
				} else {
					logger.info("Unable to fetch candle data" + index.getName());
				}
			} else {
				logger.info("Script is null {} , {} , {}", index.getExchange(), index.getSymbol(), index.getToken());
				// throw new Exception();
			}

		} catch (Exception e) {
			// TODO Auto-generated catch block
			logger.error("Err Occured during processing {}, Error {}", index.getName(), e.getMessage());
		}

	}

	// Get 4 hour candle data
	public void get4HoursCandleData() {
		List<PricesIndex> pricesList = pricesIndexRepo.findAll();
		List<PricesIndex> modifiedPricesList = new ArrayList<>();
		BigDecimal open = null;
		BigDecimal close = null;
		List<BigDecimal> highList = new ArrayList<>();
		List<BigDecimal> lowList = new ArrayList<>();
		List<BigDecimal> volumeList = new ArrayList<>();
		int firstCandle = 0;
		int secondCandle = 0;
		boolean firstCandle_Flag = true;
		boolean secondCandle_Flag = false;
		String timeStamp = null;

		for (int i = 0; i < pricesList.size(); i++) {

			if (secondCandle == 0 && firstCandle_Flag
					&& (pricesList.get(i).getTimestamp().contains("09:15:00")
							|| pricesList.get(i).getTimestamp().contains("10:15:00")
							|| pricesList.get(i).getTimestamp().contains("11:15:00")
							|| pricesList.get(i).getTimestamp().contains("12:15:00"))) {
				firstCandle++;
				if (pricesList.get(i).getTimestamp().contains("09:15:00")) {
					open = pricesList.get(i).getOpen();
					timeStamp = pricesList.get(i).getTimestamp();
				}
				if (pricesList.get(i).getTimestamp().contains("12:15:00")) {
					close = pricesList.get(i).getClose();
				}

				highList.add(pricesList.get(i).getHigh());
				lowList.add(pricesList.get(i).getLow());
				volumeList.add(pricesList.get(i).getVolume());

			}

			if (firstCandle == 0 && secondCandle_Flag
					&& (pricesList.get(i).getTimestamp().contains("13:15:00")
							|| pricesList.get(i).getTimestamp().contains("14:15:00")
							|| pricesList.get(i).getTimestamp().contains("15:15:00"))) {
				secondCandle++;
				if (pricesList.get(i).getTimestamp().contains("13:15:00")) {
					open = pricesList.get(i).getOpen();
					timeStamp = pricesList.get(i).getTimestamp();
				}
				if (pricesList.get(i).getTimestamp().contains("15:15:00")) {
					close = pricesList.get(i).getClose();
				}

				highList.add(pricesList.get(i).getHigh());
				lowList.add(pricesList.get(i).getLow());
				volumeList.add(pricesList.get(i).getVolume());

			}

			if (firstCandle == 4) {
				// System.out.println("4th " + pricesList.get(i).getTimestamp() + " " +
				// pricesList.get(i).getId());
				secondCandle_Flag = true;
				firstCandle = 0;
				firstCandle_Flag = false;

				modifiedPricesList.add(createModifiedList(open, close, highList, lowList, volumeList, timeStamp,
						pricesList.get(i).getName()));
				highList.clear();
				lowList.clear();
				volumeList.clear();
			}

			if (secondCandle_Flag && secondCandle == 3) {
				// System.out.println("3rd " + pricesList.get(i).getTimestamp() + " " +
				// pricesList.get(i).getId());
				firstCandle_Flag = true;
				secondCandle_Flag = false;
				secondCandle = 0;
				modifiedPricesList.add(createModifiedList(open, close, highList, lowList, volumeList, timeStamp,
						pricesList.get(i).getName()));
				highList.clear();
				lowList.clear();
				volumeList.clear();
			}

		}
		pricesIndexRepo.deleteAll();

		modifiedPricesList.stream().forEach(prices -> {
			prices.setType(getPriceType(prices.getOpen(), prices.getClose()));
			prices.setRange(prices.getHigh().subtract(prices.getLow()));
			pricesIndexRepo.save(prices);
		});
		// System.out.println(modifiedPricesList.size());
	}

	// Ready Day Candle
	public void getMonthlyCandleData(Indexes index, SmartConnect smartConnect, Candle candle) throws SmartAPIException {

		try {
			pricesIndexRepo.deleteAll();
			Indexes indexes = indexesRepo.findByNameAndSymbol(index.getName(), index.getSymbol());

			// Get Current Price
			JSONObject jsonObject = smartConnect.getLTP(index.getExchange(), index.getSymbol(), index.getToken());
			if (jsonObject != null) {
				BigDecimal index_CurrentPrice = new BigDecimal(String.valueOf(jsonObject.get("ltp")));
				BigDecimal index_OpenPrice = new BigDecimal(String.valueOf(jsonObject.get("open")));
				SimpleDateFormat fromFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
				SimpleDateFormat toFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");

				String fromDate = null;
				String toDate = null;

				fromDate = getStartOfMonthExcludingWeekends();
				toDate = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(toFormat.parse(calculateDate("TODAY")));

				JSONArray responseArray = new JSONArray();
				JSONObject requestObejct = new JSONObject();
				requestObejct.put("exchange", index.getExchange());
				requestObejct.put("symboltoken", index.getToken());
				requestObejct.put("interval", candle.getTimeFrame());
				requestObejct.put("fromdate", fromDate);
				requestObejct.put("todate", toDate);
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				responseArray = smartConnect.candleData(requestObejct);
				// System.out.println(responseArray);
				if (responseArray != null) {
					responseArray.forEach(item -> {
						JSONArray ohlcArray = (JSONArray) item;
						String timeStamp = String.valueOf(ohlcArray.getString(0));
						BigDecimal open = new BigDecimal(String.valueOf(ohlcArray.getDouble(1)));
						BigDecimal high = new BigDecimal(String.valueOf(ohlcArray.getDouble(2)));
						BigDecimal low = new BigDecimal(String.valueOf(ohlcArray.getDouble(3)));
						BigDecimal close = new BigDecimal(String.valueOf(ohlcArray.getDouble(4)));
						BigDecimal volume = new BigDecimal(String.valueOf(ohlcArray.getDouble(5)));
						BigDecimal range = high.subtract(low);
						PricesIndex prices = new PricesIndex();
						prices.setHigh(high);
						prices.setLow(low);
						prices.setClose(close);
						prices.setOpen(open);
						prices.setVolume(volume);
						prices.setRange(range);
						prices.setName(index.getName());
						prices.setTimestamp(timeStamp);
						prices.setType(getPriceType(open, close));
						prices.setTimeframe(index.getTimeFrame());
						// prices.setCpr(calculateCpr(high,low,close).toString());
						pricesIndexRepo.save(prices);
					});

					// Get MOdified Candle Data
					getmonthlyCandleData();

					// Get Volume Data
					getMonthlyVolumeData(candle.getTimeFrame(), index, index_CurrentPrice, smartConnect, candle,
							index_OpenPrice);

				} else {
					logger.info("Unable to fetch candle data" + index.getName());
				}
			} else {
				logger.info("Script is null {} , {} , {}", index.getExchange(), index.getSymbol(), index.getToken());
				throw new Exception();
			}

		} catch (Exception e) {
			// TODO Auto-generated catch block
			logger.error("Err Occured during processing {}, Error {}", index.getName(), e.getMessage());
		}

	}

	// ========================================
	// Weekly Candle Data Fetch
	// ========================================
	private void getWeeklyCandleData(Indexes index, SmartConnect smartConnect, Candle candle)
			throws IOException, SmartAPIException, ParseException {

		// Delete only existing data for this index to avoid wiping other threads’ work
		pricesIndexRepo.deleteByName(index.getName());

		List<PricesIndex> pricesList = fetchWeeklyPrices(index, smartConnect, candle);

		// Save prices to DB (thread-safe since per-index)
		pricesIndexRepo.saveAll(pricesList);

		// Pass the data in-memory to avoid race conditions
		getWeeklyVolumeData("WEEK", index, getCurrentPrice(pricesList), smartConnect, candle, getOpenPrice(pricesList),
				pricesList);
		//Delete once work is done
		pricesIndexRepo.deleteByName(index.getName());
	}

	
	private List<PricesIndex> fetchWeeklyPrices(Indexes index, SmartConnect smartConnect, Candle candle)
	        throws IOException, SmartAPIException {

	    List<PricesIndex> result = new ArrayList<>();
	    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");

	    // --- 1. Build from/to dates for ~1 year of daily candles ---
	    Calendar toCal = Calendar.getInstance();
	    toCal.set(Calendar.HOUR_OF_DAY, 15);
	    toCal.set(Calendar.MINUTE, 15);
	    toCal.set(Calendar.SECOND, 0);

	    Calendar fromCal = (Calendar) toCal.clone();
	    //fromCal.add(Calendar.DAY_OF_YEAR, -364); // last ~52 weeks
	    fromCal.add(Calendar.DAY_OF_YEAR, -728); // last ~104 weeks (2 years)
	    fromCal.set(Calendar.HOUR_OF_DAY, 9);
	    fromCal.set(Calendar.MINUTE, 15);

	    JSONObject requestObject = new JSONObject();
	    requestObject.put("exchange", index.getExchange());
	    requestObject.put("symboltoken", index.getToken());
	    requestObject.put("interval", "ONE_DAY");
	    requestObject.put("fromdate", sdf.format(fromCal.getTime()));
	    requestObject.put("todate", sdf.format(toCal.getTime()));

	    // --- 2. Fetch all daily candles in one API hit ---
	    JSONArray responseArray = safeCandleData(smartConnect, requestObject);
	    if (responseArray == null || responseArray.isEmpty()) {
	        logger.warn("No daily candle data found for {}", index.getName());
	        return result;
	    }

	    // --- 3. Convert JSONArray to list of DailyCandle POJOs ---
	    SimpleDateFormat apiDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");

	 // --- 3. Convert JSONArray to list of DailyCandle POJO ---
	 List<DailyCandle> dailyList = new ArrayList<>();

	 for (int i = 0; i < responseArray.length(); i++) {
	     JSONArray arr = responseArray.getJSONArray(i);
	     DailyCandle dc = new DailyCandle();
	     try {
	         dc.timestamp = apiDateFormat.parse(arr.getString(0));
	     } catch (ParseException e) {
	         logger.error("Date parse error for {}: {}", index.getName(), arr.getString(0));
	         continue;
	     }
	     dc.open = BigDecimal.valueOf(arr.getDouble(1));
	     dc.high = BigDecimal.valueOf(arr.getDouble(2));
	     dc.low = BigDecimal.valueOf(arr.getDouble(3));
	     dc.close = BigDecimal.valueOf(arr.getDouble(4));
	     dc.volume = BigDecimal.valueOf(arr.getDouble(5));
	     dailyList.add(dc);
	 }

	    // --- 4. Group daily candles into Monday–Friday weeks ---
	    Map<String, List<DailyCandle>> weeklyGroups = new LinkedHashMap<>();
	    Calendar weekCal = Calendar.getInstance();

	    for (DailyCandle dc : dailyList) {
	        weekCal.setTime(dc.timestamp);

	        int dayOfWeek = weekCal.get(Calendar.DAY_OF_WEEK);
	        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
	            continue; // skip weekends
	        }

	        // Align to Monday of the week
	        weekCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
	        Date weekStart = weekCal.getTime();
	        String weekKey = sdf.format(weekStart);

	        weeklyGroups.computeIfAbsent(weekKey, k -> new ArrayList<>()).add(dc);
	    }

	    // --- 5. Aggregate each week into PricesIndex ---
	    BigDecimal currentPrice = getCurrentPriceFromDaily(dailyList);

	    for (Map.Entry<String, List<DailyCandle>> entry : weeklyGroups.entrySet()) {
	        List<DailyCandle> weekCandles = entry.getValue();
	        weekCandles.sort(Comparator.comparing(dc -> dc.timestamp));

	        PricesIndex prices = new PricesIndex();
	        prices.setTimestamp(entry.getKey());
	        prices.setOpen(weekCandles.get(0).open);
	        prices.setClose(weekCandles.get(weekCandles.size() - 1).close);
	        prices.setHigh(weekCandles.stream().map(dc -> dc.high).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO));
	        prices.setLow(weekCandles.stream().map(dc -> dc.low).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO));
	        prices.setVolume(weekCandles.stream().map(dc -> dc.volume).reduce(BigDecimal.ZERO, BigDecimal::add));
	        prices.setName(index.getName());
	        prices.setType(getPriceType(prices.getOpen(), prices.getClose()));
	        prices.setRange(prices.getHigh().subtract(prices.getLow()));
	        prices.setCurrentprice(currentPrice);

	        result.add(prices);
	    }

	    return result;
	}

	// Helper: get last close as current price
	private BigDecimal getCurrentPriceFromDaily(List<DailyCandle> dailyList) {
	    if (dailyList.isEmpty()) return BigDecimal.ZERO;
	    return dailyList.get(dailyList.size() - 1).close;
	}

	// Inner POJO for daily candle data
	private static class DailyCandle {
	    Date timestamp;
	    BigDecimal open, high, low, close, volume;

	    public DailyCandle() {} // no-arg constructor

	    public DailyCandle(Date timestamp, BigDecimal open, BigDecimal high, BigDecimal low,
	                       BigDecimal close, BigDecimal volume) {
	        this.timestamp = timestamp;
	        this.open = open;
	        this.high = high;
	        this.low = low;
	        this.close = close;
	        this.volume = volume;
	    }
	}



	private JSONArray safeCandleData(SmartConnect smartConnect, JSONObject requestObject) {
	    int retries = 10;
	    int baseDelay = 1000;

	    for (int attempt = 1; attempt <= retries; attempt++) {
	        try {
	            // Block until a permit is available
	            RATE_LIMITER.acquire();

	            try {
	                JSONArray response = smartConnect.candleData(requestObject);
	                if (response != null && response.length() > 0) {
	                    return response;
	                }
	                
	            } finally {
	                // Release the permit after 1 second
	                RATE_LIMIT_RELEASE.schedule(() -> RATE_LIMITER.release(), 1, TimeUnit.SECONDS);
	            }

	        } catch (Exception e) {
	            logger.error("Error fetching candle data (attempt {}): {}", attempt, e.getMessage());
	        }

	        // Backoff before retry
	        try {
	            Thread.sleep(baseDelay * attempt);
	        } catch (InterruptedException ignored) {}
	    }
	    logger.warn("Null/empty candle data for {}", requestObject);
	    return null;
	}
	  
	// Helper to get current price from list
	private BigDecimal getCurrentPrice(List<PricesIndex> pricesList) {
		return pricesList.isEmpty() ? BigDecimal.ZERO : pricesList.get(0).getClose();
	}

	// Helper to get open price from list
	private BigDecimal getOpenPrice(List<PricesIndex> pricesList) {
		return pricesList.isEmpty() ? BigDecimal.ZERO : pricesList.get(0).getOpen();
	}

	public List<Time> getWeeklySupportCandle() {

		List<Time> weeklyList = new ArrayList<>();

		// Get the current date
		LocalDate currentDate = LocalDate.now();

		// Calculate the start date (one year ago from today)
		LocalDate startDate = currentDate.minusYears(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

		// Loop through each week for the past year and print the dates from Monday to
		// Friday
		LocalDate dateIterator = startDate;
		while (dateIterator.isBefore(currentDate)) {

			Time weekly = new Time();
			LocalDate monday = dateIterator.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).minusDays(3);
			weekly.setFromDate(monday.toString() + " 09:15");
			monday = dateIterator.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
			LocalDate friday = monday.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));

			weekly.setToDate(friday.toString() + " 15:30");
			weeklyList.add(weekly);
			// Move to the next week

			dateIterator = monday.plusWeeks(1);
		}

		return weeklyList;

	}

	public void getHourlyVolumeData(String timeFrame, Indexes indexes, BigDecimal index_CurrentPrice,
			SmartConnect smartConnect, Candle candle, BigDecimal index_OpenPrice)
			throws IOException, SmartAPIException {
		// Get the Volume data and sort it, get the high volume data and store in
		List<PricesIndex> pricesList = pricesIndexRepo.findAll();
		String cprData = pricesList.get(pricesList.size() - 1).getCpr();
		PricesIndex openandclose = pricesList.get(pricesList.size() - 1);
		pricesList.sort(Comparator.comparing(PricesIndex::getVolume, Comparator.reverseOrder()));
		List<PricesIndex> n_pricesList = pricesList.stream().limit(10).collect(Collectors.toList());
		List<PricesIndex> buyList = n_pricesList.stream().filter(s -> s.getType().equalsIgnoreCase("BUY"))
				.collect(Collectors.toList());
		List<PricesIndex> sellList = n_pricesList.stream().filter(s -> s.getType().equalsIgnoreCase("SELL"))
				.collect(Collectors.toList());
		List<String> supportList = buyList.stream()
				.map(v -> v.getVolume().toString().concat("=").concat(v.getHigh().toString()))
				.collect(Collectors.toList());
		List<String> resistanceList = sellList.stream()
				.map(v -> v.getVolume().toString().concat("=").concat(v.getLow().toString()))
				.collect(Collectors.toList());
		int avgRange = (int) pricesList.stream().mapToInt(d -> d.getRange().intValue()).average().orElse(0);
		avgRange = candle.getPriceLimit(); // default value
		Indicator indicator = indicatorRepo.findByname(indexes.getName());
		if (indicator == null) {
			indicator = new Indicator();
		}
		indicator.setName(indexes.getName());
		indicator.setToken(indexes.getToken());
		indicator.setTimeFrame(timeFrame);
		indicator.setHoursupport(supportList.toString());
		indicator.setHourresistance(resistanceList.toString());
		indicator.setAvgrange(new BigDecimal(avgRange));
		indicator.setPrevdaycloseprice(index_CurrentPrice);
		indicator.setExchange(indexes.getExchange());
		indicator.setTradingSymbol(indexes.getSymbol());
		indicator.setCreatedDate(LocalDateTime.now());
		indicator.setLast3HourCandleHigh(getSignal_eq("high",indexes.getName()));
		indicator.setLast3Hourcandlelow(getSignal_eq("low",indexes.getName()));
		// indicator.setCpr(cprData);
		indicator.setCurrentPrice(index_CurrentPrice);
		// indicator.setDailyopenandcloseissame(findOpenAndClose(openandclose));
		// indicator = get52WeekData(indexes, smartConnect, indicator);
		// Find Support and Resistance
		indicator = checkForHourlySignal(indicator, supportList, resistanceList, index_CurrentPrice,
				new BigDecimal(avgRange));
		// getRSI

		/*
		 * Pageable pageable = PageRequest.of(0, 15, Sort.by(Sort.Direction.DESC,
		 * "id"));
		 * indicator.setDailyRSI(indicatorService.getRSIData(pricesIndexRepo.findAll(
		 * pageable).getContent()));
		 * //indicator.setDailyRSI(slowRSICalculator.getRSIData(pricesEqRepo.findAll(
		 * pageable).getContent())); Pageable pageable_ma = PageRequest.of(0, 200,
		 * Sort.by(Sort.Direction.DESC, "id"));
		 * indicator.setMovingavg200(movingAverageCalculator.getMovingAverage(
		 * pricesIndexRepo.findAll(pageable_ma).getContent()));
		 * indicator.setMovingavg200Flag(index_CurrentPrice.subtract(indicator.
		 * getMovingavg200())); Pageable pageableb = PageRequest.of(0, 20,
		 * Sort.by(Sort.Direction.DESC, "id"));
		 * indicator.setBollingerband(bollingerBandsCalculator.createBand(
		 * pricesIndexRepo.findAll(pageableb).getContent()));
		 * indicator.setBollingerflag(findBollingerBand(indicator.getBollingerband(),
		 * indicator.getCurrentPrice()));
		 */
		updateHeikinAshi(indexes.getName(), null, "INDEX");
		indicator.setHeikinAshiHourly(checkEntryHeikinAshi("INDEX"));
		indicator.setPsarFlagHourly(checkEntryPsar("INDEX"));

		indicator.setHourlysellsl(convertStringToList(indicator.getLast3HourCandleHigh(), "SELL"));
		indicator.setHourlybuysl(convertStringToList(indicator.getLast3Hourcandlelow(), "BUY"));

		indicatorRepo.save(indicator);

	}

	public BigDecimal convertStringToList(String input, String type) {
		// Input string

		List<Integer> numberList1 = Arrays.stream(input.replaceAll("\\[|\\]", "").split(",")).map(String::trim)
				.map(Integer::parseInt).sorted().collect(Collectors.toList());

		if ("BUY".equalsIgnoreCase(type)) {
			return new BigDecimal(numberList1.get(0));
		} else {
			return new BigDecimal(numberList1.get(2));
		}

	}

	// Check the start of the trend or not
	public String checkEntryPsar(String type) {

		if (type.equalsIgnoreCase("NFO")) {

		} else if (type.equalsIgnoreCase("MCX")) {

		} else if (type.equalsIgnoreCase("INDEX")) {

			PageRequest pageable = PageRequest.of(0, 2);
			List<PSARIndex> list = psarIndexRepo.findLastTwoRows(pageable);
			if (list.get(0).getType().equalsIgnoreCase(list.get(1).getType())) {
				return list.get(0).getType();
			} else {
				return "FIRST ".concat(list.get(0).getType());

			}
		}
		return null;

	}

	// Check the start of the trend or not
	public String checkEntryHeikinAshi(String type) {

		if (type.equalsIgnoreCase("NFO")) {

		} else if (type.equalsIgnoreCase("MCX")) {

		} else if (type.equalsIgnoreCase("INDEX")) {

			PageRequest pageable = PageRequest.of(0, 2);
			List<PricesHeikinAshiIndex> list = priceHeikinashiIndexRepo.findLastTwoRows(pageable);
			if (list.get(0).getType().equalsIgnoreCase(list.get(1).getType())) {
				return list.get(0).getType();
			} else {
				return "FIRST ".concat(list.get(0).getType());

			}
		}
		return null;

	}

	// Based on the support and resistance list against with Current Price
	public Indicator checkForHourlySignal(Indicator indicator, List<String> supportList, List<String> resistanceList,
			BigDecimal index_CurrentPrice, BigDecimal avgRange) {

		boolean supportFlag = false;
		boolean resistanceFlag = false;
		boolean weekLowFlag = false;
		boolean weekHighFlag = false;

		// Find Support Range
		for (String support : supportList) {
			Range<BigDecimal> myRange = Range.between(index_CurrentPrice.subtract(avgRange),
					index_CurrentPrice.add(avgRange));
			if (myRange.contains(new BigDecimal(support.split("=")[1]))) {
				supportFlag = true;
			}
		}

		// Find Resistance Range
		for (String resistance : resistanceList) {
			Range<BigDecimal> myRange = Range.between(index_CurrentPrice.subtract(avgRange),
					index_CurrentPrice.add(avgRange));
			if (myRange.contains(new BigDecimal(resistance.split("=")[1]))) {
				resistanceFlag = true;
			}
		}
		if (supportFlag && resistanceFlag) {
			indicator.setHourlySignal("SUPPORT + RESISTANCE");
		} else if (resistanceFlag) {
			indicator.setHourlySignal("RESISTANCE");
		} else if (supportFlag) {
			indicator.setHourlySignal("SUPPORT");
		}

		return indicator;

	}

	public void updateHeikinAshi(String name, String timeFrame, String type) {
		// Heikin Ashi

		if (type.equalsIgnoreCase("MCX")) {
			if (pricesMcxRepo.findAll().size() > 1) {
				List<Candle> candles = new ArrayList<>();
				heikinAshiCalculator.createCandle(pricesMcxRepo.findAll(), null, null, type, name, timeFrame);
				psarCalculator.createPoints(pricesMcxRepo.findAll(), null, null, type, name, timeFrame);
			}
		} else if (type.equalsIgnoreCase("NFO")) {
			if (pricesNiftyRepo.findAll().size() > 1) {
				List<Candle> candles = new ArrayList<>();
				heikinAshiCalculator.createCandle(null, pricesNiftyRepo.findAll(), null, type, name, timeFrame);
				psarCalculator.createPoints(null, pricesNiftyRepo.findAll(), null, type, name, timeFrame);
			}
		} else if (type.equalsIgnoreCase("INDEX")) {
			if (pricesIndexRepo.findByNameAndTimeframe(name, timeFrame).size() > 1) {
				List<Candle> candles = new ArrayList<>();
				heikinAshiCalculator.createCandle(null, null, pricesIndexRepo.findByNameAndTimeframe(name, timeFrame),
						type, name, timeFrame);
				psarCalculator.createPoints(null, null, pricesIndexRepo.findByNameAndTimeframe(name, timeFrame), type,
						name, timeFrame);
			}
		}

	}

	// Get Day Volume

	public void getDayVolumeData(String timeFrame, Indexes indexes, BigDecimal index_CurrentPrice,
			SmartConnect smartConnect, Candle candle, BigDecimal index_OpenPrice)
			throws IOException, SmartAPIException {

		String name = indexes.getName();
		String timeframe = timeFrame;

// 1) Optional: delete prior volume-derived indicators if refreshing
// pricesIndexRepo.deleteByNameAndTimeframe(name, timeframe);

// 2) Get latest 15 records (desc by id) for RSI, MAs, etc.
		Pageable latest15 = PageRequest.of(0, 15, Sort.by(Sort.Direction.DESC, "id"));
		List<PricesIndex> last15 = pricesIndexRepo.findByNameAndTimeframe(name, timeframe, latest15);

		if (last15 == null || last15.isEmpty()) {
			logger.info("No price rows found for {} / {} - skipping volume/indicator calc", name, timeframe);
			return;
		}

// Extract CPR data and openAndClose from latest record
		String cprData = last15.get(0).getCpr();
		PricesIndex openAndClose = last15.get(0);

// 3) Get top 10 records by volume for this symbol/timeframe
		Pageable top10 = PageRequest.of(0, 10);
		List<PricesIndex> topVolumeList = pricesIndexRepo.findByNameAndTimeframeOrderByVolumeDesc(name, timeframe,
				top10);
		if (topVolumeList == null)
			topVolumeList = Collections.emptyList();

// Separate BUY and SELL type lists
		List<PricesIndex> buyList = topVolumeList.stream().filter(s -> "BUY".equalsIgnoreCase(s.getType()))
				.collect(Collectors.toList());
		List<PricesIndex> sellList = topVolumeList.stream().filter(s -> "SELL".equalsIgnoreCase(s.getType()))
				.collect(Collectors.toList());

// Prepare support and resistance strings "volume=price"
		List<String> supportList = buyList.stream()
				.map(v -> v.getVolume().toString().concat("=").concat(v.getHigh().toString()))
				.collect(Collectors.toList());
		List<String> resistanceList = sellList.stream()
				.map(v -> v.getVolume().toString().concat("=").concat(v.getLow().toString()))
				.collect(Collectors.toList());

// Calculate avgRange safely from last 15 ranges, fallback to candle priceLimit
		int avgRange = (int) last15.stream().mapToInt(d -> d.getRange() == null ? 0 : d.getRange().intValue()).average()
				.orElse(candle.getPriceLimit());

// Find or create indicator for this index
		Indicator indicator = indicatorRepo.findByname(name);
		if (indicator == null) {
			indicator = new Indicator();
		}

// Set base indicator values
		indicator.setName(name);
		indicator.setToken(indexes.getToken());
		indicator.setTimeFrame(timeFrame);
		indicator.setDailysupport(supportList.toString());
		indicator.setDailyresistance(resistanceList.toString());
		indicator.setAvgrange(new BigDecimal(avgRange));
		indicator.setPrevdaycloseprice(index_CurrentPrice);
		indicator.setExchange(indexes.getExchange());
		indicator.setTradingSymbol(indexes.getSymbol());
		indicator.setCreatedDate(LocalDateTime.now());
		indicator.setLast3daycandlehigh(getSignal_eq("high",name));
		indicator.setLast3daycandlelow(getSignal_eq("low",name));
		indicator.setCpr(cprData);
		indicator.setCurrentPrice(index_CurrentPrice);
		indicator.setDailyopenandcloseissame(findOpenAndClose(openAndClose));
		indicator = get52WeekData(indexes, smartConnect, indicator);

// Support/Resistance signal analysis
		indicator = checkForDaySignal(indicator, supportList, resistanceList, index_CurrentPrice,
				new BigDecimal(avgRange));

// 4) RSI calculation if enough data, else null
		if (last15.size() >= 5) {
			indicator.setDailyRSI(rsiCalculator.getRSIData(last15));
		} else {
			indicator.setDailyRSI(null);
		}

// 5) Moving averages and price action analysis require more history (200, 50, 20)
		Pageable page200 = PageRequest.of(0, 200, Sort.by(Sort.Direction.DESC, "id"));
		List<PricesIndex> last200 = pricesIndexRepo.findByNameAndTimeframe(name, timeframe, page200);

		if (last200.size() >= 20) {
// 200 MA
			if (last200.size() >= 200) {
				indicator.setMovingavg200(movingAverageCalculator.getMovingAverage(last200, 200));
				if (indicator.getMovingavg200() != null) {
					indicator.setMovingavg200Flag(index_CurrentPrice.subtract(indicator.getMovingavg200()));
				}
			} else {
				indicator.setMovingavg200(null);
			}

// 50 MA
			if (last200.size() >= 50) {
				List<PricesIndex> last50 = last200.subList(0, 50);
				indicator.setMovingavg50(movingAverageCalculator.getMovingAverage(last50, 50));
				if (indicator.getMovingavg50() != null) {
					indicator.setMovingavg50Flag(index_CurrentPrice.subtract(indicator.getMovingavg50()));
				}
			} else {
				indicator.setMovingavg50(null);
			}

// 20 MA
			if (last200.size() >= 20) {
				List<PricesIndex> last20 = last200.subList(0, 20);
				indicator.setMovingavg20(movingAverageCalculator.getMovingAverage(last20, 20));
				if (indicator.getMovingavg20() != null) {
					indicator.setMovingavg20Flag(index_CurrentPrice.subtract(indicator.getMovingavg20()));
				}
			} else {
				indicator.setMovingavg20(null);
			}

// Price action / Fibonacci analysis
			PriceActionResult pa = priceActionService.analyze(index_CurrentPrice, last200);
			indicator.setDailyPriceActionSupport(pa.getSr_nearestSupportsJson());
			indicator.setDailyPriceActionResistance(pa.getSr_nearestResistancesJson());
			indicator.setDailyPriceActionFlag(pa.isSr_priceActionTriggered());
			indicator.setDaily_fiboSupport(pa.getFibo_supportsJson());
			indicator.setDaily_fiboResistance(pa.getFibo_resistancesJson());
			indicator.setDaily_fiboFlag(pa.isFibo_triggered());
			indicator.setDaily_sr_trend(pa.getSr_trend());
			indicator.setDaily_sr_signal(pa.getSr_signal());
			indicator.setDaily_sr_confidence(pa.getSr_confidence());
			indicator.setDaily_sr_reason(pa.getSr_reason());
			indicator.setDaily_fibo_trend(pa.getFibo_trend());
			indicator.setDaily_fibo_signal(pa.getFibo_signal());
			indicator.setDaily_fibo_confidence(pa.getFibo_confidence());
			indicator.setDaily_fibo_reason(pa.getFibo_reason());
		} else {
// Not enough data for price action analysis
			indicator.setDailyPriceActionSupport(null);
			indicator.setDailyPriceActionResistance(null);
			indicator.setDailyPriceActionFlag(false);
		}

// Bollinger Bands need at least 20 records
		Pageable page20 = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id"));
		List<PricesIndex> last20 = pricesIndexRepo.findByNameAndTimeframe(name, timeframe, page20);
		if (last20.size() >= 20) {
			indicator.setBollingerband(bollingerBandsCalculator.createBand(last20));
			indicator.setBollingerflag(findBollingerBand(indicator.getBollingerband(), indicator.getCurrentPrice()));
		} else {
			indicator.setBollingerband(null);
			indicator.setBollingerflag(null);
		}

// Heikin-Ashi and PSAR flags - unchanged from previous runs
		updateHeikinAshi(name, "ONE_DAY", "INDEX");
		indicator.setHeikinAshiDay(checkEntryHeikinAshi("INDEX"));
		indicator.setPsarFlagDay(checkEntryPsar("INDEX"));
		deletePsarAndHiekeinTableData(name);
// Convert last 3 day candle high/low to SL lists
		// indicator.setSellsl(convertStringToList(indicator.getLast3daycandlehigh(),
		// "SELL"));
		// indicator.setBuysl(convertStringToList(indicator.getLast3daycandlelow(),
		// "BUY"));

// Volume for last N days (5 days here) for volumeService
		Pageable page6 = PageRequest.of(0, 6, Sort.by(Sort.Direction.DESC, "id"));
		List<PricesIndex> last6 = pricesIndexRepo.findByNameAndTimeframe(name, timeframe, page6);
		indicator.setVolume(volumeService.getLastNDaysVolumeJsonString(last6, 5));
		indicator.setVolumeFlag(volumeService.calVolumeAvg(last6));

// Pivot point calculation - last record only
		Pageable page1 = PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "id"));
		List<PricesIndex> last1 = pricesIndexRepo.findByNameAndTimeframe(name, timeframe, page1);
		indicator.setPivot(calPivot(last1));

		indicator.setOneday("Y");

// Save indicator to DB
		indicatorRepo.save(indicator);
	}

	public void deletePsarAndHiekeinTableData(String name) {
		priceHeikinashiIndexRepo.deleteByNameAndTimeframe(name, "ONE_DAY");
		psarIndexRepo.deleteByNameAndTimeframe(name, "ONE_DAY");
	}

	public PriceActionResult getLevels(List<PricesIndex> list, BigDecimal index_CurrentPrice)
			throws JsonProcessingException {
		PriceActionResult levels = priceActionService.analyze(index_CurrentPrice, list);
		// System.out.println(levels);
		return levels;
	}

	public String calPivot(List<PricesIndex> list) throws JsonProcessingException {
		PivotRequest pivotRequest = new PivotRequest();
		PivotResponse pivotResponse = new PivotResponse();
		if (list != null && !list.isEmpty()) {
			pivotRequest.setClose(list.get(0).getClose());
			pivotRequest.setHigh(list.get(0).getHigh());
			pivotRequest.setLow(list.get(0).getLow());
			pivotRequest.setMethod("fibonacci");
			pivotResponse = pivotPointService.calculatePivot(pivotRequest);
			return objectMapper.writeValueAsString(pivotResponse);
		}

		return null;
	}

	public String findBollingerBand(String bolingervalue, BigDecimal currentPrice) {
		// Remove square brackets and split the string by comma
		String[] stringValues = bolingervalue.replace("[", "").replace("]", "").split(", ");

		// Convert the string values to BigDecimal and add to the list
		List<BigDecimal> valueList = new ArrayList<>();
		for (String value : stringValues) {
			valueList.add(new BigDecimal(value));
		}
		if (currentPrice.compareTo(valueList.get(0)) > 0) {
			return "UP";
		} else if (currentPrice.compareTo(valueList.get(2)) < 0) {
			return "DOWN";
		}
		return null;

	}

	public String findOpenAndClose(PricesIndex pricesEq) {
		BigDecimal difference = pricesEq.getOpen().subtract(pricesEq.getClose()).abs();
		if (difference.compareTo(new BigDecimal("1.00")) <= 0) {
			return "TRUE";
		}
		return "FALSE";

	}

	// Based on the support and resistance list against with Current Price
	public Indicator checkForDaySignal(Indicator indicator, List<String> supportList, List<String> resistanceList,
			BigDecimal index_CurrentPrice, BigDecimal avgRange) {

		boolean supportFlag = false;
		boolean resistanceFlag = false;
		boolean weekLowFlag = false;
		boolean weekHighFlag = false;

		// Find Support Range
		for (String support : supportList) {
			Range<BigDecimal> myRange = Range.between(index_CurrentPrice.subtract(avgRange),
					index_CurrentPrice.add(avgRange));
			if (myRange.contains(new BigDecimal(support.split("=")[1]))) {
				supportFlag = true;
			}
		}

		// Find Resistance Range
		for (String resistance : resistanceList) {
			Range<BigDecimal> myRange = Range.between(index_CurrentPrice.subtract(avgRange),
					index_CurrentPrice.add(avgRange));
			if (myRange.contains(new BigDecimal(resistance.split("=")[1]))) {
				resistanceFlag = true;
			}
		}
		if (supportFlag && resistanceFlag) {
			indicator.setDailysignal("SUPPORT + RESISTANCE");
		} else if (resistanceFlag) {
			indicator.setDailysignal("RESISTANCE");
		} else if (supportFlag) {
			indicator.setDailysignal("SUPPORT");
		}

		// Find Week low
		Range<BigDecimal> myRange = Range.between(index_CurrentPrice.subtract(avgRange),
				index_CurrentPrice.add(avgRange));
		if (myRange.contains(indicator.getFifty2_weeklow())) {
			weekLowFlag = true;
		}
		if (myRange.contains(indicator.getFifty2_weekhigh())) {
			weekHighFlag = true;
		}
		if (weekLowFlag) {
			indicator.setFifty2week_flag("NEAT 52 WEEK LOW");
		} else if (weekHighFlag) {
			indicator.setFifty2week_flag("NEAT 52 WEEK HIGH");
		}

		return indicator;

	}

	// Get 52 week low and high data
	public Indicator get52WeekData(Indexes indexes, SmartConnect smartConnect, Indicator indicator)
			throws IOException, SmartAPIException {
		JSONObject payload = new JSONObject();
		payload.put("mode", "FULL"); // You can change the mode as needed
		JSONObject exchangeTokens = new JSONObject();
		JSONArray nseTokens = new JSONArray();
		nseTokens.put(indexes.getToken());
		exchangeTokens.put(indexes.getExchange(), nseTokens);
		payload.put("exchangeTokens", exchangeTokens);
		JSONObject response = smartConnect.marketData(payload);

		if (response.get("fetched") != null) {
			JSONArray jsonArray = (JSONArray) response.get("fetched");
			JSONObject item = jsonArray.getJSONObject(0);
			indicator.setFifty2_weeklow(new BigDecimal(item.getInt("52WeekLow")));
			indicator.setFifty2_weekhigh(new BigDecimal(item.getInt("52WeekHigh")));
		}
		return indicator;
	}

	public void getFourHourVolumeData(String timeFrame, Indexes indexes, BigDecimal index_CurrentPrice,
			SmartConnect smartConnect, Candle candle, BigDecimal index_OpenPrice)
			throws IOException, SmartAPIException {
		// Get the Volume data and sort it, get the high volume data and store in
		List<PricesIndex> pricesList = pricesIndexRepo.findAll();
		pricesList.sort(Comparator.comparing(PricesIndex::getVolume, Comparator.reverseOrder()));
		List<PricesIndex> n_pricesList = pricesList.stream().limit(10).collect(Collectors.toList());
		List<PricesIndex> buyList = n_pricesList.stream().filter(s -> s.getType().equalsIgnoreCase("BUY"))
				.collect(Collectors.toList());
		List<PricesIndex> sellList = n_pricesList.stream().filter(s -> s.getType().equalsIgnoreCase("SELL"))
				.collect(Collectors.toList());
		List<String> supportList = buyList.stream()
				.map(v -> v.getVolume().toString().concat("=").concat(v.getOpen().toString()))
				.collect(Collectors.toList());
		List<String> resistanceList = sellList.stream()
				.map(v -> v.getVolume().toString().concat("=").concat(v.getClose().toString()))
				.collect(Collectors.toList());
		int avgRange = (int) pricesList.stream().mapToInt(d -> d.getRange().intValue()).average().orElse(0);
		avgRange = candle.getPriceLimit(); // default value
		Indicator indicator = indicatorRepo.findByname(indexes.getName());
		indicator.setFourHoursupport(supportList.toString());
		indicator.setFourHourresistance(resistanceList.toString());
		// Find Support and Resistance
		indicator = checkForFourHourSignal(indicator, supportList, resistanceList, index_CurrentPrice,
				new BigDecimal(avgRange));
		indicatorRepo.save(indicator);

	}

	// Based on the support and resistance list against with Current Price
	public Indicator checkForFourHourSignal(Indicator indicator, List<String> supportList, List<String> resistanceList,
			BigDecimal index_CurrentPrice, BigDecimal avgRange) {

		boolean supportFlag = false;
		boolean resistanceFlag = false;
		boolean weekLowFlag = false;
		boolean weekHighFlag = false;

		// Find Support Range
		for (String support : supportList) {
			Range<BigDecimal> myRange = Range.between(index_CurrentPrice.subtract(avgRange),
					index_CurrentPrice.add(avgRange));
			if (myRange.contains(new BigDecimal(support.split("=")[1]))) {
				supportFlag = true;
			}
		}

		// Find Resistance Range
		for (String resistance : resistanceList) {
			Range<BigDecimal> myRange = Range.between(index_CurrentPrice.subtract(avgRange),
					index_CurrentPrice.add(avgRange));
			if (myRange.contains(new BigDecimal(resistance.split("=")[1]))) {
				resistanceFlag = true;
			}
		}
		if (supportFlag && resistanceFlag) {
			indicator.setFourHoursignal("SUPPORT + RESISTANCE");
		} else if (resistanceFlag) {
			indicator.setFourHoursignal("RESISTANCE");
		} else if (supportFlag) {
			indicator.setFourHoursignal("SUPPORT");
		}

		return indicator;

	}

	public void getMonthlyVolumeData(String timeFrame, Indexes indexes, BigDecimal index_CurrentPrice,
			SmartConnect smartConnect, Candle candle, BigDecimal index_OpenPrice)
			throws IOException, SmartAPIException {
		// Get the Volume data and sort it, get the high volume data and store in
		List<PricesIndex> pricesList = pricesIndexRepo.findAll();

		List<PricesIndex> n_pricesList = pricesList.stream().limit(10).collect(Collectors.toList());
		List<PricesIndex> buyList = n_pricesList.stream().filter(s -> s.getType().equalsIgnoreCase("BUY"))
				.collect(Collectors.toList());
		List<PricesIndex> sellList = n_pricesList.stream().filter(s -> s.getType().equalsIgnoreCase("SELL"))
				.collect(Collectors.toList());
		List<String> supportList = buyList.stream()
				.map(v -> v.getVolume().toString().concat("=").concat(v.getOpen().toString()))
				.collect(Collectors.toList());
		List<String> resistanceList = sellList.stream()
				.map(v -> v.getVolume().toString().concat("=").concat(v.getClose().toString()))
				.collect(Collectors.toList());
		int avgRange = (int) pricesList.stream().mapToInt(d -> d.getRange().intValue()).average().orElse(0);
		avgRange = candle.getPriceLimit(); // default value
		Indicator indicator = indicatorRepo.findByname(indexes.getName());
		indicator.setMonthlysupport(supportList.toString());
		indicator.setMonthlyresistance(resistanceList.toString());
		// Find Support and Resistance
		indicator = checkForMonthlySignal(indicator, supportList, resistanceList, index_CurrentPrice,
				new BigDecimal(avgRange));
		indicatorRepo.save(indicator);

	}

	// Based on the support and resistance list against with Current Price
	public Indicator checkForMonthlySignal(Indicator indicator, List<String> supportList, List<String> resistanceList,
			BigDecimal index_CurrentPrice, BigDecimal avgRange) {

		boolean supportFlag = false;
		boolean resistanceFlag = false;
		boolean weekLowFlag = false;
		boolean weekHighFlag = false;

		// Find Support Range
		for (String support : supportList) {
			Range<BigDecimal> myRange = Range.between(index_CurrentPrice.subtract(avgRange),
					index_CurrentPrice.add(avgRange));
			if (myRange.contains(new BigDecimal(support.split("=")[1]))) {
				supportFlag = true;
			}
		}

		// Find Resistance Range
		for (String resistance : resistanceList) {
			Range<BigDecimal> myRange = Range.between(index_CurrentPrice.subtract(avgRange),
					index_CurrentPrice.add(avgRange));
			if (myRange.contains(new BigDecimal(resistance.split("=")[1]))) {
				resistanceFlag = true;
			}
		}
		if (supportFlag && resistanceFlag) {
			indicator.setMonthlysignal("SUPPORT + RESISTANCE");
		} else if (resistanceFlag) {
			indicator.setMonthlysignal("RESISTANCE");
		} else if (supportFlag) {
			indicator.setMonthlysignal("SUPPORT");
		}

		return indicator;

	}

	public String calculateDate(String type) {
		LocalDate today = LocalDate.now();
		LocalDate dateBefore52Weeks = today.minus(52, ChronoUnit.WEEKS);
		if ("TODAY".equalsIgnoreCase(type)) {
			return today.toString().concat(" 09:15");
		} else if ("WEEK".equalsIgnoreCase(type) || "52WEEKS_EARLY".equalsIgnoreCase(type)) {
			return dateBefore52Weeks.toString().concat(" 09:15");
		} else if ("HOUR".equalsIgnoreCase(type) || "TODAY_EOD".equalsIgnoreCase(type)) {
			return today.toString().concat(" 15:15");
		} else if ("MONTHLY".equalsIgnoreCase(type)) {
			return today.minus(2, ChronoUnit.YEARS).toString().concat(" 09:15");
		} else if ("MONTH_MINUS".equalsIgnoreCase(type)) {
			return today.minus(1, ChronoUnit.MONTHS).toString().concat(" 09:15");
		} else if ("FIVE_DAYS_MINUS".equalsIgnoreCase(type)) {
			return today.minus(5, ChronoUnit.DAYS).toString().concat(" 09:15");
		}

		return null;

	}

	public String getPriceType(BigDecimal open, BigDecimal close) {
		if (close.compareTo(open) >= 1) {
			return "BUY";
		} else {
			return "SELL";
		}
	}

	private void getWeeklyVolumeData(String timeFrame, Indexes indexes, BigDecimal index_CurrentPrice,
			SmartConnect smartConnect, Candle candle, BigDecimal index_OpenPrice, List<PricesIndex> pricesList) {

		// Sort and filter without hitting DB again
		pricesList.sort(Comparator.comparing(PricesIndex::getVolume, Comparator.reverseOrder()));
		List<PricesIndex> top10 = pricesList.stream().limit(10).toList();

		List<String> supportList = top10.stream().filter(s -> "BUY".equalsIgnoreCase(s.getType()))
				.map(v -> v.getVolume() + "=" + v.getOpen()).toList();

		List<String> resistanceList = top10.stream().filter(s -> "SELL".equalsIgnoreCase(s.getType()))
				.map(v -> v.getVolume() + "=" + v.getClose()).toList();

		int avgRange = candle.getPriceLimit(); // Using candle-provided range

		Indicator indicator = indicatorRepo.findByname(indexes.getName());
		if (indicator == null) {
			logger.warn("No indicator found for {}", indexes.getName());
			return;
		}

		indicator.setWeeklysupport(supportList.toString());
		indicator.setWeeklyresistance(resistanceList.toString());

		// Find support/resistance
		indicator = checkForWeekSignal(indicator, supportList, resistanceList, index_CurrentPrice,
				new BigDecimal(avgRange));

		// Update Heikin Ashi & PSAR
		updateHeikinAshi(indexes.getName(), null, "INDEX");
		indicator.setHeikinAshiWeekly(checkEntryHeikinAshi("INDEX"));
		indicator.setPsarFlagWeekly(checkEntryPsar("INDEX"));

		// Get RSI
		Pageable pageable = PageRequest.of(0, 15, Sort.by(Sort.Direction.DESC, "id"));
		indicator.setWeeklyRSI(rsiCalculator.getRSIData(pricesList.subList(0, Math.min(15, pricesList.size()))));

		// Price Action & Fibonacci — analyze once
		Pageable pageable_ma = PageRequest.of(0, 200, Sort.by(Sort.Direction.DESC, "id"));
		List<PricesIndex> last200 = pricesList.subList(0, Math.min(200, pricesList.size()));
		var analysis = priceActionService.analyze(index_CurrentPrice, last200);

		indicator.setWeeklyPriceActionSupport(analysis.getSr_nearestSupportsJson());
		indicator.setWeeklyPriceActionResistance(analysis.getSr_nearestResistancesJson());
		indicator.setWeeklyPriceActionFlag(analysis.isSr_priceActionTriggered());

		indicator.setWeekly_fiboSupport(analysis.getFibo_supportsJson());
		indicator.setWeekly_fiboResistance(analysis.getFibo_resistancesJson());
		indicator.setWeekly_fiboFlag(analysis.isFibo_triggered());

		indicator.setWeekly_sr_trend(analysis.getSr_trend());
		indicator.setWeekly_sr_signal(analysis.getSr_signal());
		indicator.setWeekly_sr_confidence(analysis.getSr_confidence());
		indicator.setWeekly_sr_reason(analysis.getSr_reason());

		indicator.setWeekly_fibo_trend(analysis.getFibo_trend());
		indicator.setWeekly_fibo_signal(analysis.getFibo_signal());
		indicator.setWeekly_fibo_confidence(analysis.getFibo_confidence());
		indicator.setWeekly_fibo_reason(analysis.getFibo_reason());

		indicator.setOneweek("Y");
		indicatorRepo.save(indicator);
	}

	private <T> T retryWithBackoff(Callable<T> task, int maxRetries, long initialBackoffMs) throws Exception {
		long backoff = initialBackoffMs;
		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			try {
				return task.call();
			} catch (Exception e) {
				if (isRateLimitError(e) && attempt < maxRetries) {
					logger.warn("Rate limit hit, retrying in {} ms (attempt {}/{})", backoff, attempt, maxRetries);
					Thread.sleep(backoff);
					backoff *= 2;
				} else {
					throw e;
				}
			}
		}
		throw new RuntimeException("Max retries exceeded");
	}

	// Based on the support and resistance list against with Current Price
	public Indicator checkForWeekSignal(Indicator indicator, List<String> supportList, List<String> resistanceList,
			BigDecimal index_CurrentPrice, BigDecimal avgRange) {

		boolean supportFlag = false;
		boolean resistanceFlag = false;
		boolean weekLowFlag = false;
		boolean weekHighFlag = false;

		// Find Support Range
		for (String support : supportList) {
			Range<BigDecimal> myRange = Range.between(index_CurrentPrice.subtract(avgRange),
					index_CurrentPrice.add(avgRange));
			if (myRange.contains(new BigDecimal(support.split("=")[1]))) {
				supportFlag = true;
			}
		}

		// Find Resistance Range
		for (String resistance : resistanceList) {
			Range<BigDecimal> myRange = Range.between(index_CurrentPrice.subtract(avgRange),
					index_CurrentPrice.add(avgRange));
			if (myRange.contains(new BigDecimal(resistance.split("=")[1]))) {
				resistanceFlag = true;
			}
		}
		if (supportFlag && resistanceFlag) {
			indicator.setWeeklysignal("SUPPORT + RESISTANCE");
		} else if (resistanceFlag) {
			indicator.setWeeklysignal("RESISTANCE");
		} else if (supportFlag) {
			indicator.setWeeklysignal("SUPPORT");
		}

		return indicator;

	}

	// Get monthly candle data
	public void getmonthlyCandleData() {
		List<PricesIndex> pricesList = pricesIndexRepo.findAll();
		List<PricesIndex> modifiedPricesList = new ArrayList<>();
		BigDecimal open = new BigDecimal("0");
		BigDecimal close = new BigDecimal("0");
		List<BigDecimal> highList = new ArrayList<>();
		List<BigDecimal> lowList = new ArrayList<>();
		List<BigDecimal> volumeList = new ArrayList<>();
		int firstCandle = 0;
		int lastcandle = 0;
		boolean resetFlag = true;
		boolean firstCandle_Flag = true;
		boolean secondCandle_Flag = false;

		String timeStamp = null;
		BigDecimal openPrice = new BigDecimal("0");
		BigDecimal closePrice = new BigDecimal("0");
		PricesIndex prices = new PricesIndex();
		prices.setOpen(openPrice);
		prices.setClose(closePrice);
		for (int i = 0; i < pricesList.size(); i++) {
			String curMonth = null;
			String PrevMonth = null;

			if (i != pricesList.size() - 1) {
				curMonth = pricesList.get(i).getTimestamp().substring(5, 7);
				PrevMonth = pricesList.get(i + 1).getTimestamp().substring(5, 7);
			} else {
				curMonth = pricesList.get(i).getTimestamp().substring(5, 7);
				PrevMonth = pricesList.get(i).getTimestamp().substring(5, 7);
			}

			if (curMonth.equalsIgnoreCase(PrevMonth)) {
				// Continue;
				if (open.compareTo(new BigDecimal("0")) == 0) {
					open = pricesList.get(i).getOpen();
					prices.setId(pricesList.get(i).getId());
					timeStamp = pricesList.get(i).getTimestamp();
				}
				highList.add(pricesList.get(i).getHigh());
				lowList.add(pricesList.get(i).getLow());
				volumeList.add(pricesList.get(i).getVolume());
			} else {
				// Next Month reach so start from next month by change the i value
				// resetFlag = true;

				if (close.compareTo(new BigDecimal("0")) == 0) {
					close = pricesList.get(i).getClose();
				}
				highList.add(pricesList.get(i).getHigh());
				lowList.add(pricesList.get(i).getLow());
				volumeList.add(pricesList.get(i).getVolume());
				modifiedPricesList.add(createModifiedList(open, close, highList, lowList, volumeList, timeStamp,
						pricesList.get(i).getName()));

				highList.clear();
				lowList.clear();
				volumeList.clear();
				open = new BigDecimal("0");
				close = new BigDecimal("0");

			}

		}
		pricesIndexRepo.deleteAll();

		modifiedPricesList.stream().forEach(price -> {
			price.setType(getPriceType(price.getOpen(), price.getClose()));
			price.setRange(price.getHigh().subtract(price.getLow()));
			pricesIndexRepo.save(price);
		});
		System.out.println(modifiedPricesList.size());

	}

	public List<String> calculateCpr(BigDecimal high, BigDecimal low, BigDecimal close) {
		// Calculate CPR values
		BigDecimal pivotPoint = calculatePivotPoint(high, low, close);
		BigDecimal bottomCentralPivot = calculateBottomCentralPivot(high, low);
		BigDecimal topCentralPivot = calculateTopCentralPivot(pivotPoint, bottomCentralPivot);

		return Arrays.asList(String.valueOf(topCentralPivot.intValue()), String.valueOf(pivotPoint.intValue()),
				String.valueOf(bottomCentralPivot.intValue()));
	}

	public static BigDecimal calculatePivotPoint(BigDecimal high, BigDecimal low, BigDecimal close) {
		// Formula: PP = (High + Low + Close) / 3
		return high.add(low).add(close).divide(new BigDecimal("3"), RoundingMode.HALF_UP);
	}

	public static BigDecimal calculateBottomCentralPivot(BigDecimal high, BigDecimal low) {
		// Formula: BC = (High + Low) / 2
		return high.add(low).divide(new BigDecimal("2"), RoundingMode.HALF_UP);
	}

	public static BigDecimal calculateTopCentralPivot(BigDecimal pivotPoint, BigDecimal bottomCentralPivot) {
		// Formula: TC = (PP - BC) + PP
		return pivotPoint.subtract(bottomCentralPivot).add(pivotPoint);
	}

	public PricesIndex createModifiedList(BigDecimal open, BigDecimal close, List<BigDecimal> highList,
			List<BigDecimal> lowList, List<BigDecimal> volumeList, String timeStamp, String name) {
		PricesIndex priceEq = new PricesIndex();

		Collections.sort(highList, Collections.reverseOrder());
		priceEq.setHigh(highList.get(0));

		Collections.sort(lowList);
		priceEq.setLow(lowList.get(0));

		BigDecimal sum = volumeList.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
		priceEq.setVolume(sum);
		priceEq.setTimestamp(timeStamp);
		priceEq.setOpen(open);
		priceEq.setClose(close);
		priceEq.setName(name);
		return priceEq;

	}

	public String getStartOfMonthExcludingWeekends() {
		LocalDate twoYearsAgo = LocalDate.now().minusYears(2);
		int year = twoYearsAgo.getYear();
		Month month = twoYearsAgo.getMonth();

		LocalDate firstDayOfMonth = LocalDate.of(year, month, 1);
		while (firstDayOfMonth.getDayOfWeek() == DayOfWeek.SATURDAY
				|| firstDayOfMonth.getDayOfWeek() == DayOfWeek.SUNDAY) {
			firstDayOfMonth = firstDayOfMonth.plusDays(1);
		}
		LocalDateTime dateTime = firstDayOfMonth.atStartOfDay();
		// Convert to LocalDateTime with time 00:00
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

		return dateTime.format(formatter);
	}

	public String getSignal_eq(String type,String name) {
		String result = null;
		try {

			int max = 0;
			int min = 0;
			List<PricesIndex> priceList = pricesIndexRepo.findByNameOrderByIdDesc(name);
			// PricesIndex prices = priceList.get(0);
			// priceList.remove(0);
			if (priceList != null) {
				List<Integer> HIGH_List = new ArrayList<>();
				List<Integer> LOW_List = new ArrayList<>();

				HIGH_List = priceList.stream().limit(3).map(p -> p.getHigh().intValue()).collect(Collectors.toList());
				LOW_List = priceList.stream().limit(3).map(p -> p.getLow().intValue()).collect(Collectors.toList());
				Collections.sort(HIGH_List, Collections.reverseOrder());
				Collections.sort(LOW_List);

				if ("high".equalsIgnoreCase(type)) {
					return HIGH_List.toString();
				}
				if ("low".equalsIgnoreCase(type)) {
					return LOW_List.toString();
				}

				/*
				 * Collections.sort(HIGH_List, Collections.reverseOrder()); max =
				 * HIGH_List.get(0); Collections.sort(LOW_List); min = LOW_List.get(0); if
				 * (openPrice.intValue() >= max) { // if (true) {
				 * 
				 * result = "UP"; } else if (openPrice.intValue() <= min) {
				 * 
				 * result = "DOWN"; }
				 */
			}

		} catch (Exception ex) {
			// do nothing
		}
		return null;
	}

	

	public void findBullishStocks() throws SmartAPIException {
	    SmartConnect smartConnect = AngelOne.signIn(); // Sign in once

	    List<Indicator> bullishList = indicatorRepo.findByPsarFlagDayInAndHeikinAshiDayIn(
	            Arrays.asList("FIRST BUY"), Arrays.asList("FIRST BUY"));
	    logger.info("Bullish Stock: {}", bullishList.size());

	    List<Indicator> bearishList = indicatorRepo.findByPsarFlagDayInAndHeikinAshiDayIn(
	            Arrays.asList("FIRST SELL"), Arrays.asList("FIRST SELL"));
	    logger.info("Bearish Stock: {}", bearishList.size());

	    ExecutorService executor = Executors.newFixedThreadPool(maxThreads);

	    for (Indicator stock : bullishList) {
	        executor.submit(() -> processStockWithRetry(smartConnect, stock, true));
	    }

	    for (Indicator stock : bearishList) {
	        executor.submit(() -> processStockWithRetry(smartConnect, stock, false));
	    }

	    executor.shutdown();
	    try {
	        if (!executor.awaitTermination(2, TimeUnit.HOURS)) {
	            logger.warn("Timeout waiting for stock processing tasks to finish");
	        }
	    } catch (InterruptedException e) {
	        Thread.currentThread().interrupt();
	    }

	    sendEmail();
	}

	private void processStockWithRetry(SmartConnect smartConnect, Indicator stock, boolean isBullish) {
	    int attempts = 0;
	    int maxAttempts = 5;
	    long backoff = 1000;

	    while (attempts < maxAttempts) {
	        try {
	            processStock(smartConnect, stock, isBullish);
	            return; // success
	        } catch (Exception e) {
	            attempts++;
	            if ((isRateLimitError(e) || isTransientSmartApiError(e)) && attempts < maxAttempts) {
	                logger.warn("Rate limit or transient error on stock {}. Retry {}/{} after {}ms: {}",
	                        stock.getName(), attempts, maxAttempts, backoff, e.getMessage());
	                sleep(backoff);
	                backoff *= 2;
	            } else {
	                logger.error("Permanent error processing stock {}: {}", stock.getName(), e.getMessage(), e);
	                return;
	            }
	        }
	    }
	    logger.error("Failed to process stock {} after {} attempts", stock.getName(), maxAttempts);
	}

	private void processStock(SmartConnect smartConnect, Indicator stock, boolean isBullish) throws Exception {
	    stock.setModifiedDate(LocalDateTime.now());

	    stock.setCurrentPrice(getPriceWithRetry(smartConnect, stock));
	    stock.setFirst3FiveMinsCandle(getFirst3FiveMinsCandleWithRetry(smartConnect, stock));

	    stock.setPrevdayclosepriceflag(setPrevdayclosepriceflag(stock, stock.getCurrentPrice()));
	    stock.setLast3daycandleflag(get3DaysHighAndLow(stock));
	    stock.setCprflag(getCprFlag(stock));
	    stock.setPivotFlag(calculateSignal(stock.getCurrentPrice(), stock.getPivot()));

	    indicatorRepo.save(stock);

	    if ((isBullish && isUpSignal(stock)) || (!isBullish && isDownSignal(stock))) {
	        stock.setIntraday(isBullish ? "UP" : "DOWN");
	        indicatorRepo.save(stock);
	        resultService.saveNiftyResult(stock);
     
	    }
	}

	private BigDecimal getPriceWithRetry(SmartConnect smartConnect, Indicator stock) throws Exception {
	    int attempts = 0;
	    int maxAttempts = 5;
	    long backoff = 500;

	    while (attempts < maxAttempts) {
	        try {
	            JSONObject jsonObject = smartConnect.getLTP(stock.getExchange(), stock.getTradingSymbol(), stock.getToken());

	            if (jsonObject == null) {
	                throw new IllegalStateException("Null JSON from Smart API for " + stock.getName());
	            }

	            Object ltpObj = jsonObject.opt("ltp");
	            if (ltpObj == null) {
	                throw new IllegalStateException("'ltp' missing in JSON for " + stock.getName());
	            }

	            return new BigDecimal(String.valueOf(ltpObj));

	        } catch (Exception e) {
	            attempts++;
	            if ((isRateLimitError(e) || isTransientSmartApiError(e)) && attempts < maxAttempts) {
	                logger.warn("Retry {}/{} for {} after {} ms due to: {}", attempts, maxAttempts, stock.getName(), backoff, e.getMessage());
	                sleep(backoff);
	                backoff *= 2;
	            } else {
	                logger.error("Failed to get price for {} after {} attempts", stock.getName(), attempts, e);
	                throw e;
	            }
	        }
	    }
	    throw new RuntimeException("Failed to get price for " + stock.getName());
	}

	private String getFirst3FiveMinsCandleWithRetry(SmartConnect smartConnect, Indicator stock) throws Exception {
	    int attempts = 0;
	    int maxAttempts = 10;
	    long backoff = 1000;

	    while (attempts < maxAttempts) {
	        try {
	            return getFirst3FiveMinsCandleWithSession(smartConnect, stock);
	        } catch (Exception e) {
	            attempts++;
	            if ((isRateLimitError(e) || isTransientSmartApiError(e)) && attempts < maxAttempts) {
	                logger.warn("Retry {}/{} for getFirst3FiveMinsCandle for {} after {} ms due to: {}",
	                        attempts, maxAttempts, stock.getName(), backoff, e.getMessage());
	                sleep(backoff);
	                backoff *= 2;
	            } else {
	                throw e;
	            }
	        }
	    }
	    throw new RuntimeException("Failed to get first 3 five mins candle for " + stock.getName());
	}


	private boolean isTransientSmartApiError(Exception e) {
	    String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
	    return msg.contains("null json") || msg.contains("missing 'data'") || msg.contains("timeout") || msg.contains("temporarily unavailable");
	}

	private void sleep(long ms) {
	    try {
	        Thread.sleep(ms);
	    } catch (InterruptedException e) {
	        Thread.currentThread().interrupt();
	    }
	}







	private void shutdownAndAwait(ExecutorService executor) {
	    executor.shutdown();
	    try {
	        executor.awaitTermination(30, TimeUnit.MINUTES);
	    } catch (InterruptedException e) {
	        Thread.currentThread().interrupt();
	    }
	}


	// Helper methods
	private boolean isUpSignal(Indicator stock) {
	    return "UP".equalsIgnoreCase(stock.getPrevdayclosepriceflag())
	        || "UP".equalsIgnoreCase(stock.getFirst3FiveMinsCandle())
	        || "UP".equalsIgnoreCase(stock.getCprflag());
	}

	private boolean isDownSignal(Indicator stock) {
	    return "DOWN".equalsIgnoreCase(stock.getPrevdayclosepriceflag())
	        || "DOWN".equalsIgnoreCase(stock.getFirst3FiveMinsCandle())
	        || "DOWN".equalsIgnoreCase(stock.getCprflag());
	}


	public BigDecimal getPrice(SmartConnect smartConnect,Indicator stock, String type) {
		
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		BigDecimal openPrice = getcurrentPrice(smartConnect, stock.getExchange(), stock.getTradingSymbol(),
				stock.getToken(), type);
		return openPrice;
	}

	public void sendEmail() {
		List<String[]> rows = getEmailData();
		// rows.add(new String[] { "Stock Name", "price", "Signal" });
		// rows.add(new String[] { "Aravind", "aravind@example.com", "Admin" });
		// rows.add(new String[] { "John", "john@example.com", "User" });
		try {
			emailService.getEmailData(rows);
		} catch (MessagingException e) {
			logger.error("Error while sending email : " + e.getMessage());
			e.printStackTrace();
		}
	}

	public String getFirst3FiveMinsCandleWithSession(SmartConnect smartConnect, Indicator stock) {
		Strategy strategy = new Strategy();
		strategy.setToken(stock.getToken());
		strategy.setTradingsymbol(stock.getTradingSymbol());
		strategy.setExchange(stock.getExchange());

		try {
			BigDecimal currentPrice = getPriceWithRetry(smartConnect, stock);
			String format = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
			List<BigDecimal> MAX_List = new ArrayList<>();
			List<BigDecimal> MIN_List = new ArrayList<>();
			JSONObject requestObejct = new JSONObject();
			// JSONArray jsonArray= new JSONArray();
			JSONArray jsonArrayInner = new JSONArray();
			requestObejct.put("exchange", strategy.getExchange());
			requestObejct.put("symboltoken", strategy.getToken());

			requestObejct.put("interval", "FIVE_MINUTE");
			requestObejct.put("fromdate", format + " 09:15");
			requestObejct.put("todate", format + " 09:25");
			// JSONObject json = new JSONObject(smartConnect.candleData(requestObejct));
			JSONArray jsonArray = smartConnect.candleData(requestObejct);

			if (jsonArray != null && !jsonArray.isEmpty() && MAX == 0) {

				// jsonArray = (JSONArray) json.get("data");
				for (int i = 0; i < jsonArray.length(); i++) {
					jsonArrayInner = (JSONArray) jsonArray.get(i);
					MAX_List.add(jsonArrayInner.getBigDecimal(2));
					jsonArrayInner = (JSONArray) jsonArray.get(i);
					MIN_List.add(jsonArrayInner.getBigDecimal(3));
					if (i == 0) {
						stock.setOpenPrice(jsonArrayInner.getBigDecimal(1));
					}
				}
				Collections.sort(MAX_List, Collections.reverseOrder());
				BigDecimal MAX = MAX_List.get(0);
				Collections.sort(MIN_List);
				BigDecimal MIN = MIN_List.get(0);

				if (currentPrice.compareTo(MAX) > 0) {
					// UP
					return "UP";
				} else if (currentPrice.compareTo(MIN) < 0) {
					// DOWN
					return "DOWN";

				}

			}
		} catch (Exception e) {
			logger.error("{} Error occured getFirst3FiveMinsCandleWithSession {}",stock.getName(), e.getMessage());
		}

		return null;
	}

	public String setPrevdayclosepriceflag(Indicator stock, BigDecimal currentPrice) {

		if (currentPrice.compareTo(stock.getPrevdaycloseprice()) > 0) {
			return "UP";
		}
		if (currentPrice.compareTo(stock.getPrevdaycloseprice()) < 0) {
			return "DOWN";
		}
		return null;
	}

	// Get Last 3 DaysCandle High and Low
	public String get3DaysHighAndLow(Indicator stock) {
		String result = null;

		List<Integer> numberList1 = Arrays.stream(stock.getLast3daycandlehigh().replaceAll("\\[|\\]", "").split(","))
				.map(String::trim).map(Integer::parseInt).collect(Collectors.toList());
		List<Integer> numberList2 = Arrays.stream(stock.getLast3daycandlelow().replaceAll("\\[|\\]", "").split(","))
				.map(String::trim).map(Integer::parseInt).collect(Collectors.toList());

		if (stock.getCurrentPrice().compareTo(new BigDecimal(numberList1.get(0))) > 0) {
			result = "UP";
		}
		if (stock.getCurrentPrice().compareTo(new BigDecimal(numberList2.get(0))) < 0) {
			result = "DOWN";
		}

		return result;

	}

	private String calculateSignal(BigDecimal currentPrice, String pivotString)
			throws JsonMappingException, JsonProcessingException {

		if (pivotString != null) {
			PivotResponse pivot = objectMapper.readValue(pivotString, PivotResponse.class);
			BigDecimal tc = pivot.getTc();
			BigDecimal bc = pivot.getBc();
			BigDecimal r1 = pivot.getR1();
			BigDecimal s1 = pivot.getS1();

			if (tc == null || bc == null || r1 == null || s1 == null) {
				return "INVALID - Missing CPR/Pivot Data";
			}

			if (currentPrice.compareTo(tc) > 0 && currentPrice.compareTo(r1) < 0) {
				return "BUY - Above CPR";
			} else if (currentPrice.compareTo(r1) >= 0) {
				return "STRONG BUY - Above R1";
			} else if (currentPrice.compareTo(bc) < 0 && currentPrice.compareTo(s1) > 0) {
				return "SELL - Below CPR";
			} else if (currentPrice.compareTo(s1) <= 0) {
				return "STRONG SELL - Below S1";
			} else {
				return "HOLD - Inside CPR";
			}
		}
		return null;

	}

	// Get CPR
	public String getCprFlag(Indicator stock) {
		List<Integer> numberList1 = Arrays.stream(stock.getCpr().replaceAll("\\[|\\]", "").split(",")).map(String::trim)
				.map(Integer::parseInt).collect(Collectors.toList());

		if (stock.getCurrentPrice().compareTo(new BigDecimal(numberList1.get(1))) > 0) {
			return "UP";
		}
		if (stock.getCurrentPrice().compareTo(new BigDecimal(numberList1.get(1))) < 0) {
			return "DOWN";
		}
		return null;
	}

	/*
	 * Get current price
	 */
	public BigDecimal getcurrentPrice(SmartConnect smartConnect, String exchange, String tradingSymbol,
			String symboltoken, String keyword) {
		JSONObject jsonObject = smartConnect.getLTP(exchange, tradingSymbol, symboltoken);
		// currentPrice = new BigDecimal(String.valueOf(jsonObject.get(keyword)));
		// System.out.println(currentPrice);
		return new BigDecimal(String.valueOf(jsonObject.get(keyword)));
	}

	public List<String[]> getEmailData() {
		List<Indicator> stockList = indicatorRepo.findByIntradayIsNotNullOrderByIntradayAsc();
		List<String[]> rows = new ArrayList<>();
		rows.add(new String[] { "Stock Name", "price", "Signal" });
		if (stockList != null) {
			stockList.stream().forEach(stock -> {
				rows.add(new String[] { stock.getName(), stock.getCurrentPrice().toString(), stock.getIntraday() });

			});
		}
		return rows;

	}

	public Strategy getChart(String indexName, String symbol) {
		// TODO Auto-generated method stub
		Strategy strategy = new Strategy();
		Indexes indexes = indexesRepo.findByNameAndSymbol(indexName, symbol);
		if (indexes != null) {
			strategy.setExchange(indexes.getExchange());
			strategy.setName(indexes.getName());
			strategy.setToken(indexes.getToken());
			strategy.setTradingsymbol(indexes.getSymbol());
		}
		return strategy;
	}

	@Transactional
	public void getResult() {
		// TODO Auto-generated method stub
		List<Indicator> indicatorList = indicatorRepo.findByIntradayIsNotNull();

		logger.info("Getting result count : {}", indicatorList.size());

		// Get Current price and Update the table
		SmartConnect smartConnect = angelOne.signIn();
		indicatorList.stream().forEach(stock -> {
			BigDecimal openPrice = getPrice(smartConnect,stock, "open");
			BigDecimal ltp = getPrice(smartConnect,stock, "ltp");
			if ("UP".equalsIgnoreCase(stock.getIntraday())) {
				if (ltp.compareTo(openPrice) > 0) {
					stock.setResult("SUCCESS");
				} else {
					stock.setResult("FAIL");
				}
			} else if ("DOWN".equalsIgnoreCase(stock.getIntraday())) {
				if (ltp.compareTo(openPrice) < 0) {
					stock.setResult("SUCCESS");
				} else {
					stock.setResult("FAIL");
				}
			}
			resultService.saveNiftyResult(stock);
			indicatorRepo.save(stock);
		});
	}

	public String getHourAndMinutes(String time, int interval, String type) {

		String result = null;
		// LocalTime currentTime = LocalTime.now();
		// LocalTime timeMinusFiveMinutes = currentTime.minusMinutes(interval);
		ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
		ZonedDateTime timeMinusFiveMinutes = now.truncatedTo(ChronoUnit.MINUTES).minusMinutes(interval);
		// System.out.println("Executed at: " + now);
		// System.out.println("Using time: " + timeMinusFiveMinutes);
		int hour = timeMinusFiveMinutes.getHour();
		int minute = timeMinusFiveMinutes.getMinute();

		if (type.equalsIgnoreCase("MCX") && time.equalsIgnoreCase("FROM")) {
			return " 23:25";
		} else if ((type.equalsIgnoreCase("NFO") || type.equalsIgnoreCase("NSE")) && time.equalsIgnoreCase("FROM")) {
			return " 15:25";
		}

		if (time.equalsIgnoreCase("FROM")) {
			result = " " + String.valueOf(checkDigit(hour)) + ":"
					+ String.valueOf(checkDigit(timeMinusFiveMinutes.getMinute()));
			if (" 09:20".equalsIgnoreCase(result)) {
				result = " 15:25";
			}
		} else {
			result = " " + String.valueOf(checkDigit(hour)) + ":"
					+ String.valueOf(checkDigit(timeMinusFiveMinutes.getMinute()));
		}

		if (type.equalsIgnoreCase("MCX") && time.equalsIgnoreCase("TO")) {
			// result = " 23:15";
		}
		return result;

	}

	public String checkDigit(int number) {
		if ((number >= -9 && number <= 9)) {
			return "0" + number;
		} else {
			return String.valueOf(number);
		}

	}

	public StrategyDTO getStrategyDetails(String name, String exchange) {

		if ("NIFTY_OI".equalsIgnoreCase(name) && "NSE".equalsIgnoreCase(exchange)) {
			// return strategyRepo.findByName("NIFTY");
			return convertStrategyToDto(strategyRepo.findByName("NIFTY"));
		} else if ("NFO".equalsIgnoreCase(exchange)) {
			// return strategyRepo.findByName("NIFTY");
			return convertStrategyToDto(strategyRepo.findByName("NIFTY"));
		} else if ("MCX".equalsIgnoreCase(exchange)) {
			// return strategyRepo.findByName("CRUDEOIL");
			return convertStrategyToDto(strategyRepo.findByName("CRUDEOIL"));
		} else if ("NSE".equalsIgnoreCase(exchange)) {
			// return strategyRepo.findByName("VIX");
			return convertStrategyToDto(strategyRepo.findByName("VIX"));
		}
		return null;
	}

	public StrategyDTO convertStrategyToDto(Strategy strategy) {
		return objectMapper.convertValue(strategy, StrategyDTO.class);
	}
	
	public void getVolumeData(String timeFrame, String type, boolean testflag) throws SmartAPIException {
		try {

			StrategyDTO strategyModified = getStrategyDetails("NIFTY", type);
			Strategy strategy = getChart(strategyModified.getSymbol(), strategyModified.getTradingsymbol());
			SmartConnect smartConnect = angelOne.signIn();
			// pricesRepo.deleteAll();
			// Get Current Price
			if (strategy != null) {

				JSONObject jsonObject = smartConnect.getLTP(strategy.getExchange(), strategy.getTradingsymbol(),
						strategy.getToken());
				if (jsonObject == null) {
					logger.info("Script is null {} , {} , {}", strategy.getExchange(), strategy.getTradingsymbol(),
							strategy.getToken());
					throw new Exception();
				}
				BigDecimal index_CurrentPrice = new BigDecimal(String.valueOf(jsonObject.get("ltp")));
				SimpleDateFormat fromFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
				SimpleDateFormat toFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");

				String fromDate = null;
				String toDate = null;

				if (testflag) {
					LocalDate specificDate = LocalDate.of(2024, 11, 22);
					fromDate = "2025-03-07".concat(" 09:15");
					toDate = "2025-03-07".concat(" 15:30");
				} else {
					// fromDate = new SimpleDateFormat("yyyy-MM-dd").format(new
					// Date()).concat(getHourAndMinutes("FROM",5,type));
					// toDate = new SimpleDateFormat("yyyy-MM-dd").format(new
					// Date()).concat(getHourAndMinutes("TO",5,type));
					fromDate = chartService.getDate("FROM", type);
					toDate = chartService.getDate("TO", type);
				}

				JSONArray responseArray = new JSONArray();
				JSONObject requestObejct = new JSONObject();
				requestObejct.put("exchange", strategy.getExchange());
				requestObejct.put("symboltoken", strategy.getToken());
				requestObejct.put("interval", timeFrame);
				requestObejct.put("fromdate", fromDate);
				requestObejct.put("todate", toDate);

				responseArray = smartConnect.candleData(requestObejct);
				logger.info("fromdate " + fromDate + "todate ", toDate);
				if (responseArray != null) {
					responseArray.forEach(item -> {

						JSONArray ohlcArray = (JSONArray) item;
						String timeStamp = String.valueOf(ohlcArray.getString(0));
						BigDecimal open = new BigDecimal(String.valueOf(ohlcArray.getDouble(1)));
						BigDecimal high = new BigDecimal(String.valueOf(ohlcArray.getDouble(2)));
						BigDecimal low = new BigDecimal(String.valueOf(ohlcArray.getDouble(3)));
						BigDecimal close = new BigDecimal(String.valueOf(ohlcArray.getDouble(4)));
						BigDecimal volume = new BigDecimal(String.valueOf(ohlcArray.getDouble(5)));
						BigDecimal range = high.subtract(low);

						if (type.equalsIgnoreCase("NFO")) {
							PricesNifty prices = new PricesNifty();
							prices.setHigh(high);
							prices.setLow(low);
							prices.setClose(close);
							prices.setOpen(open);
							prices.setVolume(volume);
							prices.setRange(range);
							prices.setName(strategy.getName());
							prices.setTimestamp(timeStamp);
							prices.setType(getPriceType(open, close));
							prices.setTimeframe(null);
							prices.setCurrentprice(index_CurrentPrice);
							pricesNiftyRepo.save(prices);

						} else {
							PricesMcx prices = new PricesMcx();
							prices.setHigh(high);
							prices.setLow(low);
							prices.setClose(close);
							prices.setOpen(open);
							prices.setVolume(volume);
							prices.setRange(range);
							prices.setName(strategy.getName());
							prices.setTimestamp(timeStamp);
							prices.setType(getPriceType(open, close));
							prices.setTimeframe(null);
							prices.setCurrentprice(index_CurrentPrice);
							pricesMcxRepo.save(prices);

						}

					});
				}

				if (type.equalsIgnoreCase("NFO")) {
					// Update HeikinAshi Strategy
					updateHeikinAshi(strategy.getName(), null, type);

					// Calculate Volume
					percentageCalc();

					// Get Signal
					getSignal(index_CurrentPrice);

					// Look for entry
					monitorPsarAndheikinachiStrategy("NFO", index_CurrentPrice);
				} else {
					// Update HeikinAshi Strategy
					updateHeikinAshi(strategy.getName(), null, type);

					// Calculate Volume
					percentageCalcMcx();

					// Get Signal
					getSignalMcx(index_CurrentPrice);

					// Look for entry
					monitorPsarAndheikinachiStrategy("MCX", index_CurrentPrice);
				}

			}
		} catch (Exception e) {
			logger.error("Error Occured in getVolumeData() , {}", e.getMessage());
		}
	}

	public void getSignalMcx(BigDecimal index_CurrentPrice) {
		try {
			String result = null;
			int max = 0;
			int min = 0;
			List<PricesMcx> priceList = pricesMcxRepo.findAllByOrderByIdDesc();
			PricesMcx prices = priceList.get(0);

			if (prices.getPercentage().compareTo(BigDecimal.ZERO) < 0) {
				List<Integer> HIGH_List = new ArrayList<>();
				List<Integer> LOW_List = new ArrayList<>();
				HIGH_List = priceList.stream().limit(3).map(p -> p.getHigh().intValue()).collect(Collectors.toList());
				LOW_List = priceList.stream().limit(3).map(p -> p.getLow().intValue()).collect(Collectors.toList());
				Collections.sort(HIGH_List, Collections.reverseOrder());
				max = HIGH_List.get(0);
				Collections.sort(LOW_List);
				min = LOW_List.get(0);
				if (index_CurrentPrice.intValue() > max) {
					result = "BUY";
					// Take an entry
					// placeOrderInShoonya(result);
				} else if (index_CurrentPrice.intValue() < min) {
					result = "SELL";
					// placeOrderInShoonya(result);
				}

				prices.setSignal(result);
				pricesMcxRepo.save(prices);
			}

		} catch (Exception ex) {
			// do nothing
		}
	}

	public BigDecimal percentageCalcMcx() {

		List<PricesMcx> priceList = pricesMcxRepo.findAllByOrderByIdDesc();
		if (priceList != null) {
			BigDecimal percentage = new BigDecimal("10");
			BigDecimal result = priceList.get(1).getVolume().multiply(percentage).divide(new BigDecimal("100"));
			priceList.stream().map(p -> pricesMcxRepo.save(getPercVolumeMcx(p, result))).collect(Collectors.toList());
		}

		return null;
	}

	public PricesMcx getPercVolumeMcx(PricesMcx prices, BigDecimal firstVolume) {
		prices.setPercentage(prices.getVolume().subtract(firstVolume));
		return prices;

	}

	public BigDecimal percentageCalc() throws SmartAPIException {

		List<PricesIndex> priceList = pricesIndexRepo.findAllByOrderByIdAsc();
		if (priceList != null) {
			BigDecimal volume = getSingleVolumeData("FIVE_MINUTE", "NFO");
			BigDecimal percentage = calcPercentage(volume);
			BigDecimal result = volume.multiply(percentage).divide(new BigDecimal("100"));
			priceList.stream().map(p -> pricesIndexRepo.save(getPercVolume(p, result))).collect(Collectors.toList());
		}

		return null;
	}

	public PricesIndex getPercVolume(PricesIndex prices, BigDecimal firstVolume) {
		prices.setPercentage(prices.getVolume().subtract(firstVolume));
		return prices;

	}

	public BigDecimal calcPercentage(BigDecimal volume) {

		if (volume.compareTo(new BigDecimal("15000")) > 0) {
			return new BigDecimal("5");
		} else if (volume.compareTo(new BigDecimal("15000")) < 0 && volume.compareTo(new BigDecimal("13000")) > 0) {
			return new BigDecimal("10");
		} else if (volume.compareTo(new BigDecimal("13000")) < 0 && volume.compareTo(new BigDecimal("10000")) > 0) {
			return new BigDecimal("12");
		} else if (volume.compareTo(new BigDecimal("10000")) < 0 && volume.compareTo(new BigDecimal("8000")) > 0) {
			return new BigDecimal("15");
		} else if (volume.compareTo(new BigDecimal("8000")) < 0 && volume.compareTo(new BigDecimal("6000")) > 0) {
			return new BigDecimal("20");
		} else if (volume.compareTo(new BigDecimal("6000")) < 0 && volume.compareTo(new BigDecimal("3000")) > 0) {
			return new BigDecimal("25");
		} else if (volume.compareTo(new BigDecimal("3000")) < 0 && volume.compareTo(new BigDecimal("1000")) > 0) {
			return new BigDecimal("30");
		} else if (volume.compareTo(new BigDecimal("1000")) < 0 && volume.compareTo(new BigDecimal("500")) > 0) {
			return new BigDecimal("35");
		}

		return new BigDecimal("40");

	}

	public BigDecimal getSingleVolumeData(String timeFrame, String type) throws SmartAPIException {
		BigDecimal firstVolume = new BigDecimal("0");
		try {

			StrategyDTO strategyModified = getStrategyDetails("NIFTY", type);
			Strategy strategy = getChart(strategyModified.getSymbol(), strategyModified.getTradingsymbol());
			SmartConnect smartConnect = angelOne.signIn();

			// Get Current Price
			if (strategy != null) {

				JSONObject jsonObject = smartConnect.getLTP(strategy.getExchange(), strategy.getTradingsymbol(),
						strategy.getToken());
				if (jsonObject == null) {
					logger.info("Script is null {} , {} , {}", strategy.getExchange(), strategy.getTradingsymbol(),
							strategy.getToken());
					throw new Exception();
				}
				BigDecimal index_CurrentPrice = new BigDecimal(String.valueOf(jsonObject.get("ltp")));
				SimpleDateFormat fromFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
				SimpleDateFormat toFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");

				String fromDate = null;
				String toDate = null;

				fromDate = new SimpleDateFormat("yyyy-MM-dd").format(new Date()).concat(" 09:15");
				toDate = new SimpleDateFormat("yyyy-MM-dd").format(new Date()).concat(" 09:20");

				JSONArray responseArray = new JSONArray();
				JSONObject requestObejct = new JSONObject();
				requestObejct.put("exchange", strategy.getExchange());
				requestObejct.put("symboltoken", strategy.getToken());
				requestObejct.put("interval", timeFrame);
				requestObejct.put("fromdate", fromDate);
				requestObejct.put("todate", toDate);

				responseArray = smartConnect.candleData(requestObejct);
				logger.info("fromdate " + fromDate + "todate ", toDate);

				if (responseArray.length() != 0) {
					JSONArray ohlcArray = (JSONArray) responseArray.getJSONArray(0);
					firstVolume = new BigDecimal(String.valueOf(ohlcArray.getDouble(5)));
				}

			}
		} catch (Exception e) {
			logger.error("Error Occured in getSingleVolumeData() , {}", e.getMessage());
		}
		return firstVolume;
	}

	public void getSignal(BigDecimal index_CurrentPrice) throws SmartAPIException {
		try {
			String result = null;
			int max = 0;
			int min = 0;
			List<PricesIndex> priceList = pricesIndexRepo.findAllByOrderByIdDesc();
			PricesIndex prices = priceList.get(0);
			logger.info("Getting Signal : " + index_CurrentPrice);
			if (prices.getPercentage().compareTo(BigDecimal.ZERO) < 0) {
				List<Integer> HIGH_List = new ArrayList<>();
				List<Integer> LOW_List = new ArrayList<>();
				List<String> dateList = new ArrayList<>();
				HIGH_List = priceList.stream().limit(3).map(p -> p.getHigh().intValue()).collect(Collectors.toList());
				LOW_List = priceList.stream().limit(3).map(p -> p.getLow().intValue()).collect(Collectors.toList());
				dateList = priceList.stream().limit(3).map(p -> {
					ZonedDateTime zonedDateTime = ZonedDateTime.parse(p.getTimestamp(),
							DateTimeFormatter.ISO_ZONED_DATE_TIME);
					return checkTime(zonedDateTime.getHour(), zonedDateTime.getMinute());

				}).collect(Collectors.toList());
				Collections.sort(dateList, Collections.reverseOrder());
				Collections.sort(HIGH_List, Collections.reverseOrder());
				max = HIGH_List.get(0);
				Collections.sort(LOW_List);
				min = LOW_List.get(0);
				if (index_CurrentPrice.intValue() > max && prices.getResult() == null) {
					// if (true) {
					prices.setSignal("CE");
					// result = placeOrderInShoonya("CE", dateList.get(2).toString(),
					// dateList.get(0).toString());
				} else if (index_CurrentPrice.intValue() < min) {
					prices.setSignal("PE");
					// result = placeOrderInShoonya("PE", dateList.get(2).toString(),
					// dateList.get(0).toString());
				}
				prices.setResult(result);

				pricesIndexRepo.save(prices);
			}

		} catch (Exception ex) {
			// do nothing
		}
	}

	// Check min value is 0
	public String checkTime(int hour, int minute) {
		String updateMin = null;
		String updateHour = null;
		if (minute == 0) {
			updateMin = "00";
		} else {
			updateMin = String.valueOf(minute);
		}
		if ((hour >= -9 && hour <= 9)) {
			updateHour = "0" + hour;
		} else {
			updateHour = String.valueOf(hour);
		}
		logger.info("Monitor TimeFrame : " + String.valueOf(updateHour) + ":" + String.valueOf(updateMin));

		return String.valueOf(updateHour) + ":" + String.valueOf(updateMin);

	}

	public void monitorPsarAndheikinachiStrategy(String exchange, BigDecimal currentPrice)
			throws MessagingException, IOException {
		String heikinachiFlag = null;
		String psarFlag = null;
		boolean lastTwoCandleFlag = false;
		LocalTime currentTime = LocalTime.now();

		LocalTime comparisonTime = LocalTime.of(15, 20); // 15:20 is 3:20 PM
		if ("NFO".equalsIgnoreCase(exchange)) {
			List<PricesHeikinAshiNifty> heikinAshiList = priceHeikinashiNiftyRepo.findAllByOrderByIdDesc();
			List<PSARNifty> psarList = psarNiftyRepo.findAllByOrderByIdDesc();
			if (heikinAshiList.size() > 0 && psarList.size() > 0) {
				PricesHeikinAshiNifty pricesHeikinAshiNifty = heikinAshiList.get(0);
				heikinachiFlag = pricesHeikinAshiNifty.getType();
				PSARNifty pSARNifty = psarList.get(0);
				String prePSARNifty = psarList.get(1).getType();

				psarFlag = pSARNifty.getType();

				/*
				 * Check HeikinAchi last 3 candle is same, only for entry order Check first
				 * candle only for day start candle
				 */
				ResultNifty resultNifty = resultNiftyRepo.findByActiveAndName("Y", "NIFTY");
				if (resultNifty != null) {
					lastTwoCandleFlag = true;
				} else {
					if (heikinAshiList.get(0).getType().equalsIgnoreCase(heikinAshiList.get(1).getType())
							&& heikinAshiList.get(1).getType().equalsIgnoreCase(heikinAshiList.get(2).getType())) {
						lastTwoCandleFlag = true;
					}
				}

				// Buy/Sell
				if ("BUY".equalsIgnoreCase(heikinachiFlag) && "BUY".equalsIgnoreCase(psarFlag)) {
					if (lastTwoCandleFlag) {
						resultService.savePsarHeikinAchiStrategyNifty(pricesHeikinAshiNifty, pSARNifty, currentPrice);
					}

				} else if ("SELL".equalsIgnoreCase(heikinachiFlag) && "SELL".equalsIgnoreCase(psarFlag)) {
					if (lastTwoCandleFlag) {
						resultService.savePsarHeikinAchiStrategyNifty(pricesHeikinAshiNifty, pSARNifty, currentPrice);
					}

				}
				// Exit Call at 3:20 PM
				if (currentTime.isAfter(comparisonTime)) {
					resultService.savePsarHeikinAchiStrategyNifty(pricesHeikinAshiNifty, pSARNifty, currentPrice);
				}
			}

		} else if ("MCX".equalsIgnoreCase(exchange)) {
			List<PricesHeikinAshiMcx> heikinAshiList = priceHeikinashiMcxRepo.findAllByOrderByIdDesc();
			List<PSARMcx> psarList = psarMcxRepo.findAllByOrderByIdDesc();
			if (heikinAshiList.size() > 0 && psarList.size() > 0) {
				PricesHeikinAshiMcx pricesHeikinAshiMcx = heikinAshiList.get(0);
				heikinachiFlag = pricesHeikinAshiMcx.getType();
				PSARMcx pSARMcx = psarList.get(0);
				String prePSARMcx = psarList.get(1).getType();
				psarFlag = pSARMcx.getType();

				/*
				 * Check HeikinAchi last two candle is same, only for Entry orders
				 */
				ResultMcx resultMcx = resultMcxRepo.findByActiveAndName("Y", "MCX");

				if (resultMcx != null) {
					lastTwoCandleFlag = true;
				} else {
					if (heikinAshiList.get(0).getType().equalsIgnoreCase(heikinAshiList.get(1).getType())
							&& heikinAshiList.get(1).getType().equalsIgnoreCase(heikinAshiList.get(2).getType())) {
						lastTwoCandleFlag = true;
					}
				}

				// Buy/Sell
				if ("BUY".equalsIgnoreCase(heikinachiFlag) && "BUY".equalsIgnoreCase(psarFlag)) {
					if (lastTwoCandleFlag) {
						resultService.savePsarHeikinAchiStrategyMcx(pricesHeikinAshiMcx, pSARMcx, currentPrice);
					}

				} else if ("SELL".equalsIgnoreCase(heikinachiFlag) && "SELL".equalsIgnoreCase(psarFlag)) {
					if (lastTwoCandleFlag) {
						resultService.savePsarHeikinAchiStrategyMcx(pricesHeikinAshiMcx, pSARMcx, currentPrice);
					}

				}
			}
		}
	}

	public Indexes getIndexChart(String indexName, String symbol) {
		// TODO Auto-generated method stub

		Indexes indexes = indexesRepo.findByNameAndSymbol(indexName, symbol);
		if (indexes != null) {
			indexes.setExchange(indexes.getExchange());
			indexes.setName(indexes.getName());
			indexes.setToken(indexes.getToken());
		}
		return indexes;
	}

	public CandlesDetails saveChartPrice(Strategy strategy, String candleTimeFrame) {
		// TODO Auto-generated method stub
		SmartConnect smartConnect = angelOne.signIn();
		CandlesDetails candleStick = new CandlesDetails();
		List<Candle> candleDataList = new ArrayList<>(3);
		String format = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
		List<Integer> MAX_List = new ArrayList<>();
		List<Integer> MIN_List = new ArrayList<>();

		JSONObject requestObejct = new JSONObject();
		JSONArray jsonArray = new JSONArray();
		JSONArray jsonArrayInner = new JSONArray();
		requestObejct.put("exchange", strategy.getExchange());
		requestObejct.put("symboltoken", strategy.getToken());
		requestObejct.put("interval", candleTimeFrame);
		adjustedTime();
		// Set Time
		Date currentTime = new Date(); // Instantiate a Date object
		// System.out.println("toDate : "+ format+" "+ intToString(adjustedTime()[2]) +
		// ":" + intToString(adjustedTime()[3]));
		requestObejct.put("todate",
				format + " " + intToString(adjustedTime()[2]) + ":" + intToString(adjustedTime()[3]));
		int negativeVal = (~(30 - 1));
		Calendar CalEndTime = Calendar.getInstance();
		CalEndTime.setTime(currentTime);
		CalEndTime.add(Calendar.MINUTE, -negativeVal);
		currentTime = CalEndTime.getTime();
		// System.out.println("fromdate: "+ format+" "+intToString(adjustedTime()[0]) +
		// ":" +intToString( adjustedTime()[1]));
		requestObejct.put("fromdate",
				format + " " + intToString(adjustedTime()[0]) + ":" + intToString(adjustedTime()[1]));

		// JSONObject json = new JSONObject(smartConnect.candleData(requestObejct));
		// System.out.println(smartConnect.candleData(requestObejct));
		if (smartConnect.candleData(requestObejct) != null) {

			jsonArray = (JSONArray) smartConnect.candleData(requestObejct);
			for (int i = 0; i < jsonArray.length(); i++) {
				jsonArrayInner = (JSONArray) jsonArray.get(i);
				MAX_List.add(jsonArrayInner.getInt(2));
				jsonArrayInner = (JSONArray) jsonArray.get(i);
				MIN_List.add(jsonArrayInner.getInt(3));
			}
			Collections.sort(MAX_List, Collections.reverseOrder());
			MAX = MAX_List.get(0);
			Collections.sort(MIN_List);
			MIN = MIN_List.get(0);

			candleStick.setMax(BigDecimal.valueOf(MAX));
			candleStick.setMin(BigDecimal.valueOf(MIN));
			candleStick.setStartTime(requestObejct.get("fromdate").toString());
			candleStick.setEndTime(requestObejct.get("todate").toString());
			savePrice(candleStick);
		}
		return candleStick;
	}

	@Transactional
	public void savePrice(CandlesDetails candleStick) {
		Stoploss price = new Stoploss();
		price.setStartdate(candleStick.getStartTime().toString());
		price.setEnddate(candleStick.getEndTime().toString());
		price.setMin(candleStick.getMin());
		price.setMax(candleStick.getMax());
		price.setName("NIFTY");
		;
		priceRepo.save(price);
	}

	public String intToString(int value) {
		boolean isDoubleDigit = (value > 9 && value < 100) || (value < -9 && value > -100);

		if (!isDoubleDigit) {
			return "0".concat(String.valueOf(value));
		}
		return String.valueOf(value);

	}

	public int[] adjustedTime() {
		// LocalDateTime specificDateTime = LocalDateTime.of(2024, 11, 8, 19, 15);
		// String date = new SimpleDateFormat("yyyy-MM-dd
		// HH:mm:ss").format(Date.from(specificDateTime.atZone(ZoneId.systemDefault()).toInstant()));
		String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
		// Date currentTime = new Date();
		// Convert String to LocalDateTime
		DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
		LocalDateTime localDateTime_TO = LocalDateTime.parse(date, dtf);

		// If minutes equals 30 or more, add 1 hour
		int minutes = localDateTime_TO.getMinute();
		// minutes =45;
		if (minutes < 15) {

			for (int i = minutes; i > 0; i--) {
				localDateTime_TO = localDateTime_TO.minusMinutes(1);
				if (i == 1) {
					for (int j = 15; j > 0; j--) {
						localDateTime_TO = localDateTime_TO.minusMinutes(1);
					}
				}
			}
		} else if (minutes < 45 && minutes > 15) {

			for (int i = minutes; i > 15; i--) {
				localDateTime_TO = localDateTime_TO.minusMinutes(1);
			}

		} else if (minutes >= 45) {

			for (int i = minutes; i > 45; i--) {
				localDateTime_TO = localDateTime_TO.minusMinutes(1);
			}
		}

		// LocalDateTime localDateTime_FROM =
		// localDateTime_TO.minusHours(1).minusMinutes(30);
		LocalDateTime localDateTime_FROM = localDateTime_TO.minusMinutes(30);

		int formatedTime[] = new int[4];
		formatedTime[0] = localDateTime_FROM.getHour();
		formatedTime[1] = localDateTime_FROM.getMinute();
		formatedTime[2] = localDateTime_TO.getHour();
		formatedTime[3] = localDateTime_TO.getMinute();
		return formatedTime;
	}
	
	public void callAI() {
	    List<Indicator> indicatorList = indicatorRepo.findByIntradayIsNotNullOrderByIntradayAsc();

	    int maxThreads = 5; // process multiple stocks at once
	    ExecutorService executor = Executors.newFixedThreadPool(maxThreads);

	    List<CompletableFuture<Void>> futures = new ArrayList<>();

	    for (Indicator stock : indicatorList) {
	        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
	           aiService.analyzeStockCombined(stock);
	        }, executor);
	        futures.add(future);
	    }

	    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
	    executor.shutdown();
	}

}
