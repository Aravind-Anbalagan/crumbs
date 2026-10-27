package com.crumbs.trade.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.crumbs.trade.repo.*;
import com.crumbs.trade.utility.BrokerProxyClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.angelbroking.smartapi.models.Order;
import com.angelbroking.smartapi.models.OrderParams;
import com.angelbroking.smartapi.utils.Constants;
import com.crumbs.trade.broker.AngelOne;
import com.crumbs.trade.dto.Token;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.OptionsGreeks;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.Strategy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.transaction.Transactional;


@Service
public class AngelOneService {

    static Logger logger = LoggerFactory.getLogger(AngelOneService.class);


    @Autowired
    PricesNiftyRepo pricesNiftyRepo;

    @Autowired
    PricesMcxRepo pricesMcxRepo;

    @Autowired
    PricesIndexRepo pricesIndexRepo;

    @Autowired
    PriceHeikinashiNiftyRepo priceHeikinashiNiftyRepo;

    @Autowired
    PriceHeikinashiMcxRepo priceHeikinashiMcxRepo;

    @Autowired
    PsarNiftyRepo psarNiftyRepo;

    @Autowired
    PsarMcxRepo psarMcxRepo;

    @Autowired
    IndicatorRepo indicatorRepo;

    @Autowired
    ResultMcxRepo resultMcxRepo;

    @Autowired
    ResultNiftyRepo resultNiftyRepo;

    @Autowired
    ResultVixRepo resultVixRepo;

    @Autowired
    OIRepo oiRepo;

    @Autowired
    VixRepo vixRepo;

    @Autowired
    AngelOne angelOne;

    @Autowired
    StrategyRepo strategyRepo;

    @Autowired
    TaskService taskService;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    PriceRepo priceRepo;

    @Autowired
    SignalsRepo signalsRepo;

    @Autowired
    CPRRepo cprRepo;

    @Autowired
    StraddleIntradayRepo straddleIntradayRepo;

    @Autowired
    TradeExecutionRepo tradeExecutionRepo;

    @Autowired
    PredictionHistoryRepo predictionHistoryRepo;

    @Autowired
    PreMarketAnalysisRepo preMarketAnalysisRepo;

    @Autowired
    AlertRepo alertRepo;

    @Autowired OIResultRepo oiResultRepo;
    @Autowired IntradayTradeRepo intradayTradeRepo;
    @Autowired LevelRepository levelRepository;
    @Autowired OptionsGreeksRepo optionsGreeksRepo;
    @Autowired
    RiskConfigurationRepository riskConfigurationRepository;
    @Autowired OptionPriceRepo optionPriceRepo;
    @Autowired StraddleVwapRepo straddleVwapRepo;

    // -------------------------------------------------------------------------
    // STATIC-IP PROXY (orders only)
    // -------------------------------------------------------------------------

    /** Only placeOrder(...) uses proxyClient.sendOrder(...). Everything else is direct. */
    @Autowired
    BrokerProxyClient proxyClient;

    /** Public IP registered with Angel One (sent as X-ClientPublicIP). Falls back to PROXY_HOST. */
    @Value("${STATIC_IP:${PROXY_HOST:127.0.0.1}}")
    private String staticIp;

    private static final String ANGEL_HOST      = "https://apiconnect.angelone.in";
    private static final String ORDER_PLACE_URL = ANGEL_HOST + "/rest/secure/angelbroking/order/v1/placeOrder";
    private static final String ORDER_BOOK_URL  = ANGEL_HOST + "/rest/secure/angelbroking/order/v1/getOrderBook";

    /**
     * Thrown when we cannot tell whether Angel accepted the order (timeout etc.) AND
     * the order-book check also failed. Callers must NOT blindly retry on this.
     */
    public static class OrderStatusUnknownException extends RuntimeException {
        public OrderStatusUnknownException(String message) {
            super(message);
        }
    }

    /*
     * Get current price
     */
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_DELAY_MS = 300;

    private final Map<String, BigDecimal> lastKnownLtp = new ConcurrentHashMap<>();

    public BigDecimal getcurrentPrice(
            SmartConnect smartConnect,
            String exchange,
            String tradingSymbol,
            String symboltoken) {

        int attempt = 0;
        long delay = INITIAL_DELAY_MS;

        while (attempt < MAX_RETRIES) {
            try {

                JSONObject jsonObject =
                        smartConnect.getLTP(exchange, tradingSymbol, symboltoken);

                if (jsonObject == null) {
                    logger.warn("Null response from Angel for {}", tradingSymbol);
                } else if (!jsonObject.has("ltp")) {
                    logger.warn("LTP missing in response for {}: {}",
                            tradingSymbol, jsonObject.toString());
                } else {

                    BigDecimal ltp =
                            new BigDecimal(jsonObject.get("ltp").toString());

                    if (ltp.compareTo(BigDecimal.ZERO) > 0) {

                        // ✅ store last known
                        lastKnownLtp.put(tradingSymbol, ltp);

                        return ltp;
                    }
                }

            } catch (Exception e) {
                logger.error("Attempt {} failed for {}: {}",
                        attempt + 1, tradingSymbol, e.getMessage());
            }

            // 🔁 Exponential Backoff
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }

            delay *= 2;
            attempt++;
        }

        // ⚡ Fallback to last known
        BigDecimal fallback = lastKnownLtp.get(tradingSymbol);

        if (fallback != null) {
            logger.warn("Using fallback LTP for {}", tradingSymbol);
            return fallback;
        }

        logger.error("All retries failed for {}. Returning null.", tradingSymbol);
        return null;   // ❗ DO NOT return 0
    }


    @Transactional
    public void deleteOrders() {
        // TODO Auto-generated method stub

        pricesNiftyRepo.deleteAll();
        //indicatorRepo.deleteAll();
        pricesMcxRepo.deleteAll();
        pricesIndexRepo.deleteAll();
        priceHeikinashiNiftyRepo.deleteAll();
        priceHeikinashiMcxRepo.deleteAll();
        psarMcxRepo.deleteAll();
        psarNiftyRepo.deleteAll();
        resultMcxRepo.deleteAll();
        resultNiftyRepo.deleteAll();
        oiRepo.deleteAll();
        resultVixRepo.deleteAll();
        vixRepo.deleteAll();
        orderRepository.deleteAll();
        priceRepo.deleteAll();
        signalsRepo.deleteAll();
        cprRepo.deleteAll();
        straddleIntradayRepo.deleteAll();
        tradeExecutionRepo.deleteAll();
        predictionHistoryRepo.deleteAll();
        //preMarketAnalysisRepo.deleteAll();
        alertRepo.deleteAll();
        //oiResultRepo.deleteAll();
        intradayTradeRepo.deleteAll();
        levelRepository.deleteAll();
        optionsGreeksRepo.deleteAll();
        riskConfigurationRepository.resetAllTrailingData();
        optionPriceRepo.deleteAll();
        straddleVwapRepo.deleteAll();
    }
    /*
     * Get current price (Dynamic Keyword) - Simple rate limit handling with exponential backoff.
     * No caching involved.
     */
    public BigDecimal getcurrentPrice(SmartConnect smartConnect, String exchange, String tradingSymbol, String symboltoken, String keyword) {
        int maxRetries = 3;
        long delay = 300; // start with 300ms delay

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                JSONObject jsonObject = smartConnect.getLTP(exchange, tradingSymbol, symboltoken);

                if (jsonObject != null && jsonObject.has(keyword)) {
                    Object valObj = jsonObject.get(keyword);
                    if (valObj != null) {
                        return new BigDecimal(valObj.toString());
                    }
                }
            } catch (Exception e) {
                logger.warn("Rate limit or error fetching '{}' for {} (Attempt {}/{}): {}",
                        keyword, tradingSymbol, attempt, maxRetries, e.getMessage());
            }

            // Backoff before retrying
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            delay *= 2; // Double the delay for the next attempt
        }

        logger.error("Failed to fetch '{}' for {} after {} attempts. Returning 0 to prevent crash.", keyword, tradingSymbol, maxRetries);
        return BigDecimal.ZERO;
    }

    // 🟢 CHANGE: Added 'int breakEven' parameter to the method signature
    public void createStrategy_modified(SmartConnect smartConnect, String strategyName, int spotPrice, String type, String signal, int breakEven)
            throws SmartAPIException, Exception {
        try {
            smartConnect = angelOne.signIn();
            logger.info("Order placing...");
            Strategy strategy = strategyRepo.findByName("STRANGLE");
            String exchange = strategy.getExchange();
            String tradingSymbol = strategy.getTradingsymbol();
            String symboltoken = strategy.getToken();
            String expiry = strategy.getExpiry();
            BigDecimal currentPrice = getcurrentPrice(smartConnect, exchange, tradingSymbol, symboltoken);

            if (smartConnect != null) {
                Token tokenCE = createSymbol(currentPrice, "CE", smartConnect, expiry, spotPrice, strategy, "SELLER");
                Token tokenPE = createSymbol(currentPrice, "PE", smartConnect, expiry, spotPrice, strategy, "SELLER");

                if (type.equalsIgnoreCase("SELL")) {
                    tokenCE.setProductType(Constants.PRODUCT_CARRYFORWARD);
                    tokenCE.setVariety(Constants.VARIETY_NORMAL);
                    tokenCE.setOrderType(Constants.ORDER_TYPE_MARKET);
                    tokenCE.setTransactionType(Constants.TRANSACTION_TYPE_SELL);
                    tokenCE.setName("STRANGLE");
                    tokenCE.setType(type);
                    tokenCE.setTriggerPrice(new Double(0));
                    tokenCE.setSignal(signal);
                    tokenCE.setExch_seg("NFO");

                    if (strategy.getLive().equalsIgnoreCase("Y")) {
                        placeOrder(smartConnect, tokenCE);
                        if (tokenCE.getPrice() != 0) {
                            tokenCE.setTriggerPrice((((double) 45) / 100) * tokenCE.getPrice() + tokenCE.getPrice());
                            tokenCE.setVariety(Constants.VARIETY_STOPLOSS);
                            tokenCE.setOrderType(Constants.ORDER_TYPE_STOPLOSS_LIMIT);
                            tokenCE.setTransactionType(Constants.TRANSACTION_TYPE_BUY);

                            // 🟢 FIX: Use breakEven parameter instead of StrategyService.MAX
                            insertOrder(tokenCE, breakEven);
                            placeOrder(smartConnect, tokenCE);
                        } else {
                            logger.error("Unable to place order : " + type);
                        }
                    } else {
                        if(strategy.getPapertrade().equalsIgnoreCase("Y")) {
                            // 🟢 FIX: Use breakEven parameter instead of StrategyService.MAX
                            insertOrder(tokenCE, breakEven);
                        }
                    }

                } else if (type.equalsIgnoreCase("BUY")) {
                    tokenPE.setProductType(Constants.PRODUCT_CARRYFORWARD);
                    tokenPE.setTransactionType(Constants.TRANSACTION_TYPE_SELL);
                    tokenPE.setVariety(Constants.VARIETY_NORMAL);
                    tokenPE.setOrderType(Constants.ORDER_TYPE_MARKET);
                    tokenPE.setName("STRANGLE");
                    tokenPE.setType(type);
                    tokenPE.setTriggerPrice(new Double(0));
                    tokenPE.setSignal(signal);
                    tokenPE.setExch_seg("NFO");

                    if (strategy.getLive().equalsIgnoreCase("Y")) {
                        placeOrder(smartConnect, tokenPE);
                        if (tokenPE.getPrice() != 0) {
                            tokenPE.setTriggerPrice((((double) 45) / 100) * tokenPE.getPrice() + tokenPE.getPrice());
                            tokenPE.setVariety(Constants.VARIETY_STOPLOSS);
                            tokenPE.setOrderType(Constants.ORDER_TYPE_STOPLOSS_LIMIT);
                            tokenPE.setTransactionType(Constants.TRANSACTION_TYPE_BUY);

                            // 🟢 FIX: Use breakEven parameter instead of StrategyService.MIN
                            insertOrder(tokenPE, breakEven);
                            placeOrder(smartConnect, tokenPE);
                        } else {
                            logger.error("Unable to place order : " + type);
                        }
                    } else {
                        if (strategy.getPapertrade().equalsIgnoreCase("Y")) {
                            // 🟢 FIX: Use breakEven parameter instead of StrategyService.MIN
                            insertOrder(tokenPE, breakEven);
                        }
                    }
                }
            }
        } catch(Exception ex) {
            logger.error("Error in createStrategy_modified", ex);
        }
    }

    @Transactional
    public Orders insertOrder(Token token, int breakEven) throws Exception {

        Orders orders = new Orders();

        // ORDER ID
        orders.setOrderid(token.getOrderId() != null ? token.getOrderId() : "1");

        // CREATED DATE
        orders.setCreatedOn(LocalDateTime.now());

        // 🟢 FIX: SAFELY MAP ASK PRICE AND ENTRY PRICE
        // Prioritize getAskPrice/getEntryPrice (which we set in OrderService)
        // Fallback to getPrice() just in case older parts of your system still use it
        BigDecimal actualPremium = token.getAskPrice() != null ? token.getAskPrice() :
                (token.getEntryPrice() != null ? token.getEntryPrice() :
                        (token.getPrice() != null ? BigDecimal.valueOf(token.getPrice()) : BigDecimal.ZERO));

        orders.setAskPrice(actualPremium);


        // SL
        Double sl = token.getTriggerPrice();
        orders.setSl(sl != null ? BigDecimal.valueOf(sl) : BigDecimal.ZERO);

        // BASIC DETAILS
        orders.setSymbol(safe(token.getSymbol()));
        orders.setToken(safe(token.getToken()));
        orders.setName(safe(token.getName()));

        if ("CPR_STRATEGY".equalsIgnoreCase(token.getType())) {
            orders.setType("SELL");
        } else {
            orders.setType(safe(token.getType()));
        }
        orders.setExchange(safe(token.getExch_seg()));

        // ACTIVE TRADE
        orders.setActive(1);

        // BREAKEVEN
        orders.setBreakeven(BigDecimal.valueOf(breakEven));

        // QUANTITY
        try {
            orders.setQuantity(token.getQuantity());
        } catch (Exception e) {
            orders.setQuantity(0);
        }

        return orderRepository.save(orders);
    }

    // small helper
    private String safe(String value) {
        return value == null ? "" : value;
    }


    public Token createSymbol(BigDecimal currentPrice, String type, SmartConnect smartConnect, String expiry,int spotPrice, Strategy strategy,String buyer) throws JsonProcessingException, IOException, AddressException, MessagingException
    {
        //Take Complete Option Chain Price
        if (type.equalsIgnoreCase("CE")) {
            currentPrice = currentPrice.add(new BigDecimal("500"));
        } else if (type.equalsIgnoreCase("PE")) {
            currentPrice = currentPrice.subtract(new BigDecimal("500"));
        }
        int slot = 0;

        HashMap<Token, Integer> priceMap = new HashMap<>();
        int price =0;
        if (strategy.getName().contains("SENSEX")) {
            slot=100;
            price = currentPrice.intValue() % slot;
        } else {
            slot =50;
            price = currentPrice.intValue() % slot;
        }

        int roundPrice = currentPrice.intValue() - price;

        for(int i=0;i<20;i++)
        {
            Token token=new Token();
            //NIFTY21NOV2424250CE
            String symbol=strategy.getSymbol() + expiry + roundPrice +type;
            token.setSymbol(symbol);
            token.setExch_seg(strategy.getExchange());
            getTokenBasedOnStrikePrice(token);
            String exchange=strategy.getExchange();
            String tradingSymbol=token.getSymbol();
            String symboltoken =token.getToken();
            BigDecimal strikePrice = getcurrentPrice(smartConnect, exchange, tradingSymbol, symboltoken);

            priceMap.put(token, strikePrice.intValue());
            if (type.equalsIgnoreCase("CE")) {
                roundPrice = roundPrice - slot;
            } else if (type.equalsIgnoreCase("PE")) {
                roundPrice = roundPrice + slot;
            }

        }
        return findNearestPrice(priceMap,spotPrice);


    }
    private Token findNearestPrice(HashMap<Token, Integer> priceMap,int spotPrice) {
        // TODO Auto-generated method stub
        Map<Double, Token> map = new HashMap<>();

        for (Entry<Token, Integer> entry : priceMap.entrySet())
        {
            Token token = entry.getKey();
            map.put(entry.getValue().doubleValue(),entry.getKey());
        }
        if(spotPrice==0)
        {
            return nearestKeys(map, new Long(155));
        }
        else
        {
            return nearestKeys(map, new Long(spotPrice));
        }

    }

    public static Token nearestKeys(Map<Double, Token> map, Long target) {
        double minDiff = Double.MAX_VALUE;
        Double nearest = null;
        for (Double key : map.keySet()) {
            double diff = Math.abs((double) target - (double) key);
            if (diff < minDiff) {
                nearest = key;
                minDiff = diff;
            }
        }
        return map.get(nearest);
        //return nearest;
    }

    @SuppressWarnings("unused")
    public Token getTokenBasedOnStrikePrice(Token token) throws JsonProcessingException, IOException {
        // create ObjectMapper instance

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            Indexes indexes = taskService.getIndexChart("NIFTY", token.getSymbol());

            if (indexes != null) {
                token.setToken(indexes.getToken());
                token.setName(indexes.getName());
                token.setExpiry(indexes.getExpiry());
                token.setStrike(new BigDecimal(indexes.getStrike()));
                token.setQuantity(indexes.getLotsize());
            } else {
                logger.error("Unable to get token " + token.getName());
            }
            /*
             * JsonNode rootNode = objectMapper.readTree(new
             * File(System.getProperty("user.dir") + "/tokens.txt")); rootNode.forEach(node
             * -> { //System.out.println(node); if
             * (node.path("symbol").asText().equalsIgnoreCase(token.getSymbol())) {
             *
             * token.setToken(node.path("token").asText());
             * token.setName(node.path("name").asText());
             * token.setExpiry(node.path("expiry").asText());
             * token.setStrike(node.path("strike").asText());
             * token.setQuantity(node.path("lotsize").asInt()); }
             *
             * }); logger.info("Got token " + token.getName()); if (token.getName() == null)
             * { logger.error("Unable to get token " + token.getName()); }
             */
        } catch (Exception ex) {
            logger.error(ex.getMessage());
        }

        return token;

    }

    // -------------------------------------------------------------------------
    // ORDER PLACEMENT (through the static-IP proxy)
    // -------------------------------------------------------------------------

    private HttpRequest.Builder angelRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + angelOne.getAccessToken())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-UserType", "USER")
                .header("X-SourceID", "WEB")
                .header("X-ClientLocalIP", "127.0.0.1")
                .header("X-ClientPublicIP", staticIp)
                .header("X-MACAddress", "00:00:00:00:00:00")
                .header("X-PrivateKey", angelOne.getApiKey());
    }

    private static String nz(Object o) {
        return o == null ? "0" : o.toString();
    }

    /** Unique tag (<= 20 chars) so we can find our own order in the order book after a timeout. */
    private static String newOrderTag() {
        return "CR" + Long.toString(System.currentTimeMillis(), 36)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 4);
    }

    /** Builds the REST body with the same rules the old OrderParams code used. */
    private JSONObject buildOrderBody(Token token, String tag) {
        JSONObject b = new JSONObject();
        b.put("variety", token.getVariety());
        b.put("tradingsymbol", token.getSymbol());
        b.put("symboltoken", token.getToken());
        b.put("transactiontype", token.getTransactionType());
        b.put("exchange", token.getExch_seg());
        b.put("ordertype", token.getOrderType());
        b.put("producttype", token.getProductType());
        b.put("duration", Constants.DURATION_DAY);
        b.put("quantity", String.valueOf(token.getQuantity()));
        b.put("ordertag", tag);

        String price = "0";
        if ("INTRADAY".equalsIgnoreCase(token.getProductType())) {
            b.put("squareoff", nz(token.getSquareoff()));
            b.put("stoploss", nz(token.getStoploss()));
            price = nz(token.getPrice());
        }
        if ("STOPLOSS".equalsIgnoreCase(token.getVariety())) {
            DecimalFormat df = new DecimalFormat("###");
            double trig = Double.valueOf(df.format(token.getTriggerPrice()));
            price = String.valueOf(Double.sum(trig, 0.50));
            b.put("triggerprice", Double.valueOf(trig).toString());
        }
        b.put("price", price);
        return b;
    }

    /**
     * Looks for our order (by tag) in Angel's order book. DIRECT call (read only).
     * Returns the orderid if found, null if the book was read successfully and the
     * order is absent, and throws OrderStatusUnknownException if the book cannot be read.
     */
    private String findOrderIdByTag(String tag) {
        for (int check = 1; check <= 2; check++) {
            try {
                Thread.sleep(1500);
                HttpResponse<String> r = proxyClient.sendDirect(angelRequest(ORDER_BOOK_URL).GET().build());
                JSONObject json = new JSONObject(r.body());
                if (r.statusCode() != 200 || !json.optBoolean("status", false)) {
                    throw new IllegalStateException("order book HTTP " + r.statusCode() + ": " + r.body());
                }
                JSONArray data = json.optJSONArray("data");
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject row = data.getJSONObject(i);
                        if (tag.equals(row.optString("ordertag", ""))) {
                            return row.optString("orderid", null);
                        }
                    }
                }
                logger.warn("Order tag {} not in order book (check {}/2)", tag, check);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new OrderStatusUnknownException("Interrupted while checking order book for tag " + tag);
            } catch (Exception e) {
                throw new OrderStatusUnknownException(
                        "Could not read order book to verify tag " + tag + ": " + e);
            }
        }
        return null;
    }

    /**
     * Places the order through the static-IP proxy (Angel only accepts order APIs
     * from the whitelisted IP). Same contract as before: returns the Token with
     * orderId (and executed price) filled in, or with orderId == null if the order
     * was not placed.
     *
     * Duplicate protection: if the outcome is ambiguous (timeout / 5xx / unreadable
     * reply), the order book is checked by our unique tag before returning. If even
     * that fails, OrderStatusUnknownException is thrown so callers do not retry.
     */
    public Token placeOrder(SmartConnect smartConnect, Token token) throws SmartAPIException, IOException {
        final String tag = newOrderTag();
        String orderId = null;
        boolean ambiguous = false;

        try {
            JSONObject body = buildOrderBody(token, tag);
            logger.info("Angel order -> {} {} qty={} variety={} type={} tag={} (via static-IP proxy)",
                    token.getTransactionType(), token.getSymbol(), token.getQuantity(),
                    token.getVariety(), token.getOrderType(), tag);

            HttpRequest req = angelRequest(ORDER_PLACE_URL)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = proxyClient.sendOrder(req);

            int code = resp.statusCode();
            String text = resp.body();

            if (code >= 200 && code < 300) {
                JSONObject json = new JSONObject(text);
                if (json.optBoolean("status", false)) {
                    JSONObject data = json.optJSONObject("data");
                    String id = data != null ? data.optString("orderid", "") : "";
                    if (id == null || id.isBlank()) {
                        ambiguous = true; // accepted but no id returned
                    } else {
                        orderId = id;
                    }
                } else {
                    // Clean rejection from Angel (margin, IP not whitelisted, etc.)
                    logger.error("Angel REJECTED order {} | message={} | errorcode={}",
                            token.getSymbol(), json.optString("message"), json.optString("errorcode"));
                    return token;
                }
            } else if (code >= 500) {
                logger.error("Angel order HTTP {} (outcome unknown): {}", code, text);
                ambiguous = true;
            } else {
                // 4xx (proxy 407/403, Angel 401/403 etc.): the order was not placed
                logger.error("Angel order NOT placed. HTTP {}: {}", code, text);
                return token;
            }

        } catch (HttpConnectTimeoutException | ConnectException e) {
            logger.error("Angel order NOT sent (cannot connect to proxy/Angel): {}", e.toString());
            return token;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            ambiguous = true;
        } catch (Exception e) {
            logger.error("Angel order outcome UNKNOWN: {}", e.toString());
            ambiguous = true;
        }

        if (orderId == null && ambiguous) {
            orderId = findOrderIdByTag(tag); // may throw OrderStatusUnknownException
            if (orderId == null) {
                logger.error("Order tag {} confirmed ABSENT from order book -> treated as not placed", tag);
                return token;
            }
            logger.warn("Order tag {} FOUND in order book despite error -> orderId={}", tag, orderId);
        }

        if (orderId == null) {
            return token;
        }

        token.setOrderId(orderId);
        token.setPrice(getOrderDetails(orderId)); // direct trade-book read, unchanged
        logger.info("Angel order placed | symbol={} | orderId={}", token.getSymbol(), orderId);
        return token;
    }

    //Get executed price for calculate SL
    public double getOrderDetails(String orderId) {
        double executedPrice = 0;
        try
        {
            Thread.sleep(2000);
            SmartConnect smartConnect = angelOne.signIn();
            JSONObject trades = smartConnect.getTrades();
            JSONArray jsonArray = trades.getJSONArray("data");
            //System.out.println(jsonArray.length());


            for (Object o : jsonArray) {
                JSONObject jsonLineItem = (JSONObject) o;

                if (jsonLineItem.getString("orderid").equalsIgnoreCase(orderId)) {
                    executedPrice = Double.valueOf(jsonLineItem.getInt("fillprice"));
                    //logger.info("Executed Price :" + executedPrice);
                    return executedPrice;
                }
            }
        }
        catch(Exception ex)
        {
            logger.error("Error while get order details :" + ex.getMessage());
        }

        return executedPrice;
    }

    /**
     * PURE BROKER COUPLING: Fetches raw positions directly from AngelOne API.
     * Returns the raw JSONObject for the Risk Engine to process.
     */
    public JSONObject getRawPositions() {
        try {
            SmartConnect smartConnectInstance = angelOne.signIn();
            if (smartConnectInstance != null) {
                return smartConnectInstance.getPosition();
            }
        } catch (Exception e) {
            logger.error("Failed to fetch raw positions from AngelOne: {}", e.getMessage());
        }
        return null;
    }
}