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
import in.samco.model.LoginRequest;
import in.samco.model.LoginResponse;
import in.samco.model.MultiQuoteResponse;

@Component
public class Samco {

    private static final Logger logger = LoggerFactory.getLogger(Samco.class);

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

    // ---------- LTP (INDEX / NSE / MCX) ----------
    public BigDecimal getLtp(String sessionToken, String exchange, String symbol) {

        // 1️⃣ Build MultiQuote request
        Map<String, List<String>> multiQuoteRequest = new HashMap<>();
        multiQuoteRequest.put(exchange, Arrays.asList(symbol));

        // 2️⃣ Call Samco API
        MultiQuoteAPI api = new MultiQuoteAPI();
        MultiQuoteResponse response =
                api.postMultiQuote(sessionToken, multiQuoteRequest);

        if (response == null) {
            throw new RuntimeException("MultiQuote API returned null response");
        }

        // 3️⃣ Parse JSON → BigDecimal
        return extractLtpFromJson(response.toString());
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
