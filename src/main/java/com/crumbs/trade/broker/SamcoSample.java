package com.crumbs.trade.broker;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import in.samco.api.OrdersApi;
import in.samco.api.QuoteApi;
import in.samco.api.UserLoginApi;
import in.samco.api.update.MultiQuoteAPI;
import in.samco.model.LoginRequest;
import in.samco.model.LoginResponse;
import in.samco.model.MarketDepthResponse;
import in.samco.model.MultiQuoteResponse;
import in.samco.model.OrderRequest;
import in.samco.model.OrderResponse;
import in.samco.util.ApiException;
import in.samco.util.SamcoConstants;

public class SamcoSample {

	public static void main1(String[] args) throws ApiException {

		UserLoginApi userLoginApi = new UserLoginApi();
		LoginRequest loginRequest = new LoginRequest();
		loginRequest.setUserId("DA62765");
		loginRequest.setPassword("Athiran@2020");
		loginRequest.setYob("1988");
		LoginResponse loginResponse = userLoginApi.login(loginRequest);
		String session = loginResponse.getSessionToken();
		System.out.println("session : " + loginResponse.getSessionToken());
		 getIndexLtp(loginResponse.getSessionToken(),"NIFTY");
		/*if (session != null && !"".equalsIgnoreCase(session)) {

			QuoteApi quoteApi = new QuoteApi();
			MarketDepthResponse quote = quoteApi.getQuote(loginResponse.getSessionToken(), "SBIN",
					SamcoConstants.EXCHANGE_NSE);

			if ("Success".equalsIgnoreCase(quote.getStatus())) {

				OrdersApi ordersApi = new OrdersApi();
				OrderRequest orderRequest = new OrderRequest();
				orderRequest.setSymbolName("RELIANCE");
				orderRequest.setExchange(SamcoConstants.EXCHANGE_BSE);
				orderRequest.setTransactionType(SamcoConstants.TRANSACTION_TYPE_BUY);
				orderRequest.setOrderType(SamcoConstants.ORDER_TYPE_MARKET);
				orderRequest.setQuantity("2");
				orderRequest.setDisclosedQuantity("");
				orderRequest.setOrderValidity(SamcoConstants.VALIDITY_DAY);
				orderRequest.setProductType(SamcoConstants.PRODUCT_MIS);
				orderRequest.setAfterMarketOrderFlag("NO");
				OrderResponse placeOrder = ordersApi.placeOrder(loginResponse.getSessionToken(), orderRequest);
				System.out.println("place order success response : " + placeOrder);

			}
		}*/

	}
	// ---------- INDEX LTP ----------
    public static double getIndexLtp(String sessionToken, String indexName) {

        Map<String, List<String>> multiQuoteRequest = new HashMap<String, List<String>>();

        String samcoIndexSymbol;
        if ("NIFTY".equalsIgnoreCase(indexName) || "NIFTY50".equalsIgnoreCase(indexName)) {
            samcoIndexSymbol = "NIFTY 50";
        } else if ("BANKNIFTY".equalsIgnoreCase(indexName)) {
            samcoIndexSymbol = "BANKNIFTY";
        } else if ("FINNIFTY".equalsIgnoreCase(indexName)) {
            samcoIndexSymbol = "FINNIFTY";
        } else {
            throw new IllegalArgumentException("Unsupported index: " + indexName);
        }

        // FIRST PARAM = EXCHANGE
        multiQuoteRequest.put("MCX", Arrays.asList("CRUDEOIL19FEB26FUT"));

        MultiQuoteAPI api = new MultiQuoteAPI();
        MultiQuoteResponse response =
                api.postMultiQuote(sessionToken, multiQuoteRequest);
    
        System.out.println(response.getMultiQuotes().size()); 
        if (response == null
                || response.getMultiQuotes() == null
                || response.getMultiQuotes().isEmpty()) {
            throw new RuntimeException("Index quote not available for " + samcoIndexSymbol);
        }

   

        return 0;
    }
}