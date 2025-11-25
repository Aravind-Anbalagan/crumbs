package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.CPR;
import com.crumbs.trade.dto.StrangleCprDto;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.Stoploss;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.CPRRepo;
import com.crumbs.trade.repo.OrderRepository;
import com.crumbs.trade.repo.PriceRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.utility.AppConstant;
import com.crumbs.trade.utility.NSEWorkingDays;

import jakarta.transaction.Transactional;

@Service
public class StrategyService {

    Logger logger = LoggerFactory.getLogger(StrategyService.class);
    @Autowired
    RestTemplate restTemplate;

    @Autowired
    AngelOne angelOne;

    @Autowired
    AngelOneService angelOneService;

    @Autowired
    StrategyRepo strategyRepo;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    PriceRepo priceRepo;

    @Autowired
    TaskService taskService;

    @Autowired
    ChartService chartService;

    @Autowired
    CPRRepo cprRepo;

    @Autowired
    OrderService orderService;

    public static int MAX;
    public static int MIN;

    // Prevent multiple CPR entries in same direction
    private boolean cprBuyTradeTaken = false;
    private boolean cprSellTradeTaken = false;
    public static boolean timeCheck = false;
    public static boolean firstOrder = false;
    public static boolean secondOrder = false;

    //@Scheduled(fixedDelay=5000)
    public void shortStrangleModified() throws SmartAPIException, Exception {
        String result = null;
        int niftyPrice = 0;
        Strategy strategy = strategyRepo.findByName("STRANGLE");
        String signal;
        Orders order = orderRepository.findByNameAndActive("NIFTY", 1);
        SmartConnect smartconnect = angelOne.signIn();

        BigDecimal nifty_ClosePrice = angelOneService.getcurrentPrice(smartconnect, strategy.getExchange(),
                strategy.getTradingsymbol(), strategy.getToken(), "close");
        BigDecimal nifty_OpenPrice = angelOneService.getcurrentPrice(smartconnect, strategy.getExchange(),
                strategy.getTradingsymbol(), strategy.getToken(), "open");

        if (strategy.getActive().equalsIgnoreCase("Y")) {
            if (order == null && !firstOrder) {
                if (analysePrice(nifty_ClosePrice, nifty_OpenPrice)) {
                    niftyPrice = getNiftyPrice("15", "30", strategy, 35);
                    signal = "FLAT";
                } else {
                    niftyPrice = getNiftyPrice("40", "45", strategy, 50);
                    signal = "UP or DOWN";
                }

                if (niftyPrice > MAX && MAX > 0 && MIN > 0 && !firstOrder) {
                    firstOrder = true;
                    logger.info("MAX : {} - MIN : {}", MAX, MIN);
                    logger.info("First Buy Order Triggered @  {}", niftyPrice);
                    angelOneService.createStrategy_modified(smartconnect, "NIFTY", 0, "BUY", signal);
                } else if (niftyPrice < MIN && MAX > 0 && MIN > 0 && !firstOrder) {
                    firstOrder = true;
                    logger.info("MAX : {} - MIN : {}", MAX, MIN);
                    logger.info("First Sell Order Triggered @ {}", niftyPrice);
                    angelOneService.createStrategy_modified(smartconnect, "NIFTY", 0, "SELL", signal);
                }

            } else if (order != null && !secondOrder) {

                BigDecimal currentPrice = angelOneService.getcurrentPrice(smartconnect, strategy.getExchange(),
                        strategy.getTradingsymbol(), strategy.getToken(), "ltp");
                String tradeType = readPriceFromTable("NIFTY", currentPrice);

                String type = order.getType();
                logger.info("Waiting for Signal @  :  Buy/Sell = {}", tradeType);

                if (tradeType != null && tradeType.equalsIgnoreCase("SELL") && !type.equalsIgnoreCase("SELL")) {

                    secondOrder = true;
                    logger.info("Second Sell Order Triggered @  {}", niftyPrice);
                    angelOneService.createStrategy_modified(smartconnect, "NIFTY", 0, "SELL", null);
                } else if (tradeType != null && tradeType.equalsIgnoreCase("BUY") && !type.equalsIgnoreCase("BUY")) {

                    secondOrder = true;
                    logger.info("Second Buy Order Triggered @  {}", niftyPrice);
                    angelOneService.createStrategy_modified(smartconnect, "NIFTY", 0, "BUY", null);

                }

            }

        } else {
            logger.info("strangle_920_modified is disabled");
        }

    }
    public int getNiftyPrice(String startTime, String endTime, Strategy strategy,int triggerValue) {
        BigDecimal nifty_CurrentPrice = new BigDecimal(0);
        try
        {
            Date currentTime = new Date(); // Instantiate a Date object
            Date updatedTIme = new Date(); // Instantiate a Date object
            SmartConnect smartConnect = AngelOne.signIn();
            nifty_CurrentPrice = angelOneService.getcurrentPrice(smartConnect,
                    strategy.getExchange(), strategy.getTradingsymbol(),
                    strategy.getToken(),"ltp");
            String format = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            List<Integer> MAX_List= new ArrayList<>();
            List<Integer> MIN_List= new ArrayList<>();
            JSONObject  requestObejct = new JSONObject();
            //JSONArray jsonArray= new JSONArray();
            JSONArray jsonArrayInner= new JSONArray();
            requestObejct.put("exchange", strategy.getExchange());
            requestObejct.put("symboltoken", strategy.getToken());
            
                updatedTIme.setHours(9);
                updatedTIme.setMinutes(triggerValue);
                requestObejct.put("interval", "FIVE_MINUTE");
                requestObejct.put("fromdate", format+" 09:"+startTime); 
                requestObejct.put("todate", format+" 09:"+endTime);
                JSONObject json = new JSONObject(smartConnect.candleData(requestObejct)); 
                JSONArray jsonArray = smartConnect.candleData(requestObejct);
                if(currentTime.compareTo(updatedTIme)==1)
                {
                    if(!jsonArray.isEmpty() && MAX==0) 
                    {
                        
                        //jsonArray = (JSONArray) json.get("data");
                        for(int i=0;i<jsonArray.length();i++)
                        {
                            jsonArrayInner= (JSONArray) jsonArray.get(i);
                            MAX_List.add(jsonArrayInner.getInt(2));
                            jsonArrayInner= (JSONArray) jsonArray.get(i);
                            MIN_List.add(jsonArrayInner.getInt(3));
                        }
                         Collections.sort(MAX_List, Collections.reverseOrder()); 
                         MAX =MAX_List.get(0);
                         Collections.sort(MIN_List);
                         MIN=MIN_List.get(0);
                       
                    }
                }
                
            
        }
        catch(Exception ex)
        {
            logger.error("ERROR WHILE GET NIFTY FUTURE PRICE " + ex.getMessage());
            //sendEmail.sendmail("ERROR WHILE GET NIFTY FUTURE PRICE", ex.getMessage());
        }
        
        return nifty_CurrentPrice.intValue();
    }


    public String readPriceFromTable(String name, BigDecimal currentPriceValue) {
        String result = null;
        int max = 0;
        int min = 0;
        if (currentPriceValue != null) {

            int currentPrice = currentPriceValue.intValue();
            List<Stoploss> priceList = priceRepo.findTop3ByNameOrderByIdDesc(name);
            if (priceList.size() >= 3 && currentPrice != 0) {
                max = (int) priceList.stream().filter(price -> currentPrice >= price.getMax().intValue()).count();
                min = (int) priceList.stream().filter(price -> currentPrice <= price.getMin().intValue()).count();

                if (max == 3) {
                    result = "BUY";
                }
                if (min == 3) {
                    result = "SELL";
                }
            }
        }

        return result;
    }

    public boolean analysePrice(BigDecimal nifty_ClosePrice, BigDecimal nifty_OpenPrice) {
        if (nifty_OpenPrice.compareTo(nifty_ClosePrice) < 0) {
            int diff = nifty_ClosePrice.intValue() - nifty_OpenPrice.intValue();
            if (diff <= 50) {
                return true;
            }
        } else if (nifty_OpenPrice.compareTo(nifty_ClosePrice) > 0) {
            int diff = nifty_OpenPrice.intValue() - nifty_ClosePrice.intValue();
            if (diff <= 50) {
                return true;
            }

        }
        return false;
    }

    @Transactional
    public void updateStrategy() {
        logger.info("Both call has been taken");
    }

    // Get candle data based on given input
    public JSONArray getCandleDataByChoice(SmartConnect smartConnect, Strategy strategy,
            StrangleCprDto strangleCprDto, String interval, String fromDate, String toDate) {

        JSONArray responseArray = new JSONArray();
        JSONObject requestObejct = new JSONObject();
        requestObejct.put("exchange", strategy.getExchange());
        requestObejct.put("symboltoken", strategy.getToken());
        requestObejct.put("interval", interval);
        requestObejct.put("fromdate", fromDate);
        requestObejct.put("todate", toDate);

        responseArray = smartConnect.candleData(requestObejct);
        if (responseArray != null && !responseArray.isEmpty()) {
            JSONArray ohlcArray = (JSONArray) responseArray.get(0);
            return ohlcArray;
        }
        return null;
    }

    // Calculate CPR
    public StrangleCprDto getCPR(SmartConnect smartconnect, Strategy strategy, StrangleCprDto dto)
            throws IOException, SmartAPIException {

        LocalDate today = LocalDate.now();
        LocalDate lastWorkingDay = NSEWorkingDays.getLastWorkingDay(today);
        LocalDate previousWorkingDay = NSEWorkingDays.getLastWorkingDay(lastWorkingDay);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        String fromDate = previousWorkingDay.atTime(9, 15).format(formatter);
        String toDate = lastWorkingDay.atTime(9, 15).format(formatter);

        JSONArray candles = getCandleDataByChoice(smartconnect, strategy, dto, "ONE_DAY", fromDate, toDate);
        if (candles == null || candles.isEmpty()) {
            logger.warn("No ONE_DAY candles returned for CPR");
            return dto;
        }

        // API ONE_DAY -> [time, open, low, high, close, volume]
        Object first = candles.get(0);
        if (first instanceof JSONArray candle) {
            dto.setOpen(BigDecimal.valueOf(candle.getDouble(1)));
            dto.setLow(BigDecimal.valueOf(candle.getDouble(2))); // low (index 2)
            dto.setHigh(BigDecimal.valueOf(candle.getDouble(3))); // high (index 3)
            dto.setClose(BigDecimal.valueOf(candle.getDouble(4)));

            logger.info("ONE_DAY OHLC read -> O={} H={} L={} C={}", dto.getOpen(), dto.getHigh(), dto.getLow(),
                    dto.getClose());
        } else {
            dto.setOpen(BigDecimal.valueOf(candles.getDouble(1)));
            dto.setLow(BigDecimal.valueOf(candles.getDouble(2)));
            dto.setHigh(BigDecimal.valueOf(candles.getDouble(3)));
            dto.setClose(BigDecimal.valueOf(candles.getDouble(4)));

            logger.info("ONE_DAY OHLC read (alt) -> O={} H={} L={} C={}", dto.getOpen(), dto.getHigh(), dto.getLow(),
                    dto.getClose());
        }

        CPR cpr = taskService.calculateCpr(dto.getHigh(), dto.getLow(), dto.getClose());
        if (cpr != null) {
            dto.setBottom_pivot(cpr.getBottom_pivot());
            dto.setPivot(cpr.getPivot());
            dto.setTop_pivot(cpr.getTop_pivot());
            dto.setCprWidth(cpr.getWidthType());
            dto.setCprType(cpr.getCprType());
            logger.info("Calculated CPR -> TOP={}, PIVOT={}, BOTTOM={}", cpr.getTop_pivot(), cpr.getPivot(),
                    cpr.getBottom_pivot());
        } else {
            logger.warn("taskService.calculateCpr returned null");
        }

        // compute width & percent (keep sign — negative means descending CPR)
        if (dto.getTop_pivot() != null && dto.getBottom_pivot() != null && dto.getPivot() != null) {
            BigDecimal cprWidth = dto.getTop_pivot().subtract(dto.getBottom_pivot());
            BigDecimal cprPercent = cprWidth.divide(dto.getPivot(), 6, RoundingMode.HALF_UP).multiply(
                    BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);

            dto.setCprPercent(cprPercent);
 
        }

        return dto;
    }

    public StrangleCprDto getFirstCandleData(SmartConnect smartConnect, Strategy strategy, StrangleCprDto dto) {
        LocalDate today = LocalDate.now();

        String fromDate = today + " 09:15";
        String toDate = today + " 09:20";

        JSONArray ohlc = getCandleDataByChoice(smartConnect, strategy, dto, "FIVE_MINUTE", fromDate, toDate);

        if (ohlc == null || ohlc.length() < 4) {
            logger.warn("FIRST 5-min candle not available");
            return null;
        }

        // FIVE_MINUTE format -> [time, open, high, low, close, volume]
        BigDecimal high = BigDecimal.valueOf(ohlc.getDouble(2));
        BigDecimal low = BigDecimal.valueOf(ohlc.getDouble(3));

        BigDecimal buffer = BigDecimal.valueOf(5);
        dto.setFirstFiveMinHigh(high.add(buffer));
        dto.setFirstFiveMinLow(low.subtract(buffer));

        logger.info("First 5-min (buffered) -> high={} low={}", dto.getFirstFiveMinHigh(), dto.getFirstFiveMinLow());

        return dto;
    }

    public void getCPRDetails() throws IOException, SmartAPIException {
        StrangleCprDto strangleCprDto = new StrangleCprDto();
        Strategy strategy = strategyRepo.findByName(AppConstant.CPR_STRATEGY);

        SmartConnect smartconnect = angelOne.signIn();

        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(9, 20))) {
            logger.info("⏰ Wait until 09:20 AM for first 5-min candle to close.");
            return;
        }

        strangleCprDto = getCPR(smartconnect, strategy, strangleCprDto);
        strangleCprDto = getFirstCandleData(smartconnect, strategy, strangleCprDto);

        if (strangleCprDto != null && strangleCprDto.getFirstFiveMinHigh() != null
                && strangleCprDto.getFirstFiveMinLow() != null) {
            // Save full datetime as ISO string
            String dateTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            saveCPR(strangleCprDto, strategy.getName(), dateTime);
        } else {
            logger.error("Unable to fetch CPR Details");
        }

    }

    public void executeCPRStrategy() {
        SmartConnect smartconnect = angelOne.signIn();
        com.crumbs.trade.entity.CPR cprDetails = cprRepo.findByName(AppConstant.CPR_STRATEGY);
        if (cprDetails != null) {
            getCPRStrategySignal(cprDetails, smartconnect);
        }

    }

    public com.crumbs.trade.entity.CPR saveCPR(StrangleCprDto dto, String name, String date) {

        if (dto != null) {
            com.crumbs.trade.entity.CPR cpr = new com.crumbs.trade.entity.CPR();
            cpr.setName(name);
            cpr.setDate(date);

            cpr.setPivot(dto.getPivot());
            cpr.setTop(dto.getTop_pivot());
            cpr.setBottom(dto.getBottom_pivot());

            // Optional fields if needed
            cpr.setHigh(dto.getFirstFiveMinHigh());
            cpr.setLow(dto.getFirstFiveMinLow());
            logger.info("Fetched CPR Strategy Details -> pivot={}, top={}, bottom={}", dto.getPivot(),
                    dto.getTop_pivot(), dto.getBottom_pivot());
            return cprRepo.save(cpr);
        } else {
            logger.error("Unable to fetch CPR Details");
        }
        return null;

    }

    public void getCPRStrategySignal(com.crumbs.trade.entity.CPR cprDetails, SmartConnect smartconnect) {

        Strategy strategy = strategyRepo.findByName(AppConstant.CPR_STRATEGY);

        // Extract fields and create canonical upper/lower bands
        BigDecimal topPivot = cprDetails.getTop();
        BigDecimal bottomPivot = cprDetails.getBottom();
        BigDecimal first5High = cprDetails.getHigh();
        BigDecimal first5Low = cprDetails.getLow();

        if (topPivot == null || bottomPivot == null) {
            logger.warn("⚠️ CPR data missing — skipping signal generation.");
            return;
        }

        if (first5High == null || first5Low == null) {
            logger.warn("⚠️ First 5-min candle missing — skipping.");
            return;
        }

        // canonical bands: upperBand = max(top,bottom), lowerBand = min(top,bottom)
        BigDecimal upperBand = topPivot.max(bottomPivot);
        BigDecimal lowerBand = topPivot.min(bottomPivot);

        // ---------------------------
        // FETCH MARKET PRICE
        // ---------------------------
        BigDecimal currentPrice = angelOneService.getcurrentPrice(smartconnect, strategy.getExchange(),
                strategy.getTradingsymbol(), strategy.getToken(), "ltp");

        if (currentPrice == null) {
            logger.warn("⚠️ Current price is NULL — skipping signal.");
            return;
        }

        logger.info("CPR Current Price: {} | upperBand={} lowerBand={}", currentPrice, upperBand, lowerBand);

        // ---------------------------
        // SIGNAL GENERATION
        // ---------------------------
        String signal;

        if (currentPrice.compareTo(lowerBand) > 0 && currentPrice.compareTo(upperBand) < 0) {
            signal = "NO TRADE"; // Price inside CPR band
        } else if (currentPrice.compareTo(first5High) > 0 && currentPrice.compareTo(upperBand) > 0) {
            signal = "BUY"; // Bullish breakout → PE SELL
        } else if (currentPrice.compareTo(first5Low) < 0 && currentPrice.compareTo(lowerBand) < 0) {
            signal = "SELL"; // Bearish breakout → CE SELL
        } else {
            signal = "WAIT"; // Not confirmed
        }

        executeCPRStrategyOrders(signal);
    }

    public void executeCPRStrategyOrders(String signal) {

        try {
            if ("BUY".equals(signal)) {

                if (cprBuyTradeTaken) {
                    logger.info("🚫 CPR BUY already executed today — skipping.");
                    return;
                }

                logger.info("🔥 CPR BUY → Sell PE");
                orderService.orderPlace(AppConstant.CPR_STRATEGY, 0, "BUY");

                cprBuyTradeTaken = true;
            }

            else if ("SELL".equals(signal)) {

                if (cprSellTradeTaken) {
                    logger.info("🚫 CPR SELL already executed today — skipping.");
                    return;
                }

                logger.info("🔥 CPR SELL → Sell CE");
                orderService.orderPlace(AppConstant.CPR_STRATEGY, 0, "SELL");

                cprSellTradeTaken = true;
            }

            else {
                logger.info("⏸ No trade executed. Signal = {}", signal);
            }

        } catch (Exception | SmartAPIException e) {
            logger.error("Order error while placing CPR order", e);
        }
    }

}
