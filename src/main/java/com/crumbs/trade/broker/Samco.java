package com.crumbs.trade.broker;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import in.samco.api.UserLoginApi;
import in.samco.api.update.MultiQuoteAPI;
import in.samco.api.QuoteApi;
import in.samco.model.LoginRequest;
import in.samco.model.LoginResponse;
import in.samco.model.MultiQuoteResponse;
import in.samco.model.IndexDetailsResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import in.samco.api.HistoricalCandleDataApi;
import in.samco.model.HistoricalCandleData;
import in.samco.model.HistoricalCandleResponse;

@Component
public class Samco {
    private static final Logger logger = LoggerFactory.getLogger(Samco.class);

    private static final int MAX_RETRIES    = 3;
    private static final int RETRY_DELAY_MS = 2000;

    // ---------- LOGIN ----------
    public String getSamcoSession() {
        UserLoginApi userLoginApi = new UserLoginApi();
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUserId("DA62765");
        loginRequest.setPassword("Athiran@2020");
        loginRequest.setYob("1988");
        LoginResponse loginResponse = userLoginApi.login(loginRequest);
        if (loginResponse == null || loginResponse.getSessionToken() == null) {
            throw new RuntimeException("Samco login failed");
        }
        logger.info("Samco session created");
        return loginResponse.getSessionToken();
    }

    // ---------- NIFTY 50 SPOT PRICE ----------
    public BigDecimal getNifty50Price(String sessionToken) {
        QuoteApi quoteApi = new QuoteApi();
        IndexDetailsResponse indexQuote = quoteApi.getIndexQuote(sessionToken, "NIFTY 50");
        if (indexQuote == null) {
            throw new RuntimeException("IndexQuote API returned null response");
        }
        if (!"Success".equals(indexQuote.getStatus())) {
            throw new RuntimeException("IndexQuote API failed: " + indexQuote.getStatusMessage());
        }
        if (indexQuote.getIndexDetails() == null || indexQuote.getIndexDetails().isEmpty()) {
            throw new RuntimeException("No index details in response");
        }
        Double spotPrice = indexQuote.getIndexDetails().get(0).getSpotPrice();
        BigDecimal price = BigDecimal.valueOf(spotPrice);
        logger.info("Nifty 50 spot price: {}", price);
        return price;
    }

    // ---------- LTP (INDEX / NSE / MCX) ----------
    public BigDecimal getLtp(String sessionToken, String exchange, String symbol) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                Map<String, List<String>> multiQuoteRequest = new HashMap<>();
                multiQuoteRequest.put(exchange, Arrays.asList(symbol));
                MultiQuoteAPI api = new MultiQuoteAPI();
                MultiQuoteResponse response = api.postMultiQuote(sessionToken, multiQuoteRequest);
                if (response == null) {
                    throw new RuntimeException("MultiQuote API returned null response");
                }
                BigDecimal ltp = extractLtpFromJson(response.toString());
                logger.info("LTP fetched [{}/{}]: {}:{} = {}", attempt, MAX_RETRIES, exchange, symbol, ltp);
                return ltp;
            } catch (Exception e) {
                lastException = e;
                logger.warn("getLtp attempt {}/{} failed for {}:{} — {}",
                        attempt, MAX_RETRIES, exchange, symbol, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    try { Thread.sleep(RETRY_DELAY_MS); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }

        throw new RuntimeException(
                "getLtp failed for " + exchange + ":" + symbol + " after " + MAX_RETRIES + " attempts",
                lastException);
    }

    // ---------- JSON PARSER ----------
    private BigDecimal extractLtpFromJson(String jsonResponse) {
        JSONObject root = new JSONObject(jsonResponse);
        JSONArray quotes = root.getJSONArray("multiQuotes");
        if (quotes.length() == 0) {
            throw new RuntimeException("No quote data available");
        }
        String ltpStr = quotes
                .getJSONObject(0)
                .getString("lastTradePrice");
        BigDecimal ltp = new BigDecimal(ltpStr);
        logger.info("LTP fetched: {}", ltp);
        return ltp;
    }
}