package com.crumbs.trade.scheduler;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.crumbs.trade.controller.StockController;
import com.crumbs.trade.dto.Candlestick;
import com.crumbs.trade.entity.Indexes;
import com.crumbs.trade.entity.Indicator;
import com.crumbs.trade.entity.IntradayTrade;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.IndexesRepo;
import com.crumbs.trade.repo.IntradayTradeRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.service.ChartService;
import com.crumbs.trade.service.IntradayExecutionService;

import lombok.RequiredArgsConstructor;

// ✅ FIX #7: Removed mixed @Autowired usage — all dependencies via @RequiredArgsConstructor
//            All fields are now final and injected via constructor (Lombok handles it)
@Component
@RequiredArgsConstructor
public class IntradayEquityScheduler {

    private static final Logger logger = LoggerFactory.getLogger(IntradayEquityScheduler.class);

    private final IntradayExecutionService intradayExecutionService;
    private final StockController stockController;
    private final IndexesRepo indexesRepo;         // ✅ was @Autowired field
    private final ChartService chartService;       // ✅ was @Autowired field
    private final IntradayTradeRepo intradayTradeRepo; // ✅ needed for EOD square-off
    private final StrategyRepo strategyRepo;
    private static final String STRATEGY_NAME = "EQUITY_INTRADAY";
    // 🔥 TEST MODE (10 sec)
    // @Scheduled(fixedRate = 10000)

    // 🔥 PRODUCTION MODE
    @Scheduled(cron = "0 */3 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runIntradayEngine() {

		if (!isActive(STRATEGY_NAME)) {
			return;
		}
        LocalTime now = LocalTime.now();

        // 🔒 strict control
        if (now.isBefore(LocalTime.of(9, 30)) || now.isAfter(LocalTime.of(15, 20))) {
            return;
        }
        logger.info("🔄 Intraday Scheduler Started");
        try {

            List<Indicator> list = stockController.returnIntradayList();

            if (list == null || list.isEmpty()) {
                logger.info("No intraday stocks found");
                return;
            }

            for (Indicator ind : list) {

                String symbol = ind.getTradingSymbol();

                try {

                    List<Candlestick> candles = fetch5MinCandles(ind);

                    if (candles == null || candles.isEmpty()) {
                        logger.warn("No candles for {}", symbol);
                        continue;
                    }

                    intradayExecutionService.process(
                            symbol,
                            ind.getName(),
                            ind.getExchange(),
                            candles
                    );

                    // 🔥 Prevent API burst (rate limit safe)
                    Thread.sleep(200);

                } catch (Exception e) {
                    logger.error("Error processing symbol: {}", symbol, e);
                }
            }

        } catch (Exception e) {
            logger.error("Intraday Scheduler Failed", e);
        }

        logger.info("✅ Intraday Scheduler Completed");
    }

    // ✅ FIX #2: EOD square-off — force-close all OPEN trades at 3:20 PM
    //            Without this, open positions persist overnight and corrupt next-day logic
    @Scheduled(cron = "0 20 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void eodSquareOff() {
    	if (!isActive(STRATEGY_NAME)) {
			return;
		}
        logger.info("⏰ EOD Square-Off Started");

        try {

            List<IntradayTrade> openTrades = intradayTradeRepo.findAllByStatus("OPEN");

            if (openTrades == null || openTrades.isEmpty()) {
                logger.info("No open trades to square off");
                return;
            }

            for (IntradayTrade trade : openTrades) {

                String symbol = trade.getSymbol();

                try {

                    // Fetch last known candle to get a real market price
                    Indicator dummyInd = new Indicator();
                    dummyInd.setTradingSymbol(symbol);
                    dummyInd.setName(trade.getName());

                    List<Candlestick> candles = fetch5MinCandlesBySymbol(symbol, trade.getName());

                    BigDecimal exitPrice;

                    if (candles != null && !candles.isEmpty()) {
                        exitPrice = candles.get(candles.size() - 1).getClose();
                    } else {
                        // Fallback: use entry price to avoid null — logs a warning
                        logger.warn("EOD: Could not fetch price for {}, using entry price as fallback", symbol);
                        exitPrice = trade.getEntryPrice();
                    }

                    intradayExecutionService.squareOff(trade, exitPrice, "EOD_SQUAREOFF");

                } catch (Exception e) {
                    logger.error("EOD square-off failed for symbol: {}", symbol, e);
                }
            }

        } catch (Exception e) {
            logger.error("EOD Square-Off Scheduler Failed", e);
        }

        logger.info("✅ EOD Square-Off Completed");
    }

    /**
     * Fetch candles from Angel API using Indicator entity
     */
    private List<Candlestick> fetch5MinCandles(Indicator ind) {
        return fetch5MinCandlesBySymbol(ind.getTradingSymbol(), ind.getName());
    }

    /**
     * ✅ FIX #4: More robust candle fetching — validates response structure,
     *            logs clearly on unexpected shape, never silently swallows errors
     */
    private List<Candlestick> fetch5MinCandlesBySymbol(String symbol, String name) {

        try {

            String from = chartService.getDate("FROM", "NSE", 1);
            String to   = chartService.getDate("TO",   "NSE", 1);

            Indexes indexes = indexesRepo.findByNameAndSymbol(name, symbol);

            if (indexes == null) {
                logger.warn("Index not found for symbol={} name={}", symbol, name);
                return List.of();
            }

            JSONArray response = chartService.getJsonDetails(indexes, from, to, "FIVE_MINUTE");

            if (response == null || response.length() == 0) {
                logger.warn("Empty candle response for {}", symbol);
                return List.of();
            }

            List<Candlestick> candles = parseCandles(symbol, response);

            // ✅ Ensure chronological order
            candles.sort(Comparator.comparing(Candlestick::getTimestamp));

            return candles;

        } catch (Exception e) {
            logger.error("Error fetching candles for {}", symbol, e);
            return List.of();
        }
    }

    /**
     * ✅ FIX #4: Hardened candle parser — validates array shape before parsing,
     *            logs clearly when the API response structure is unexpected
     *
     * Convert Angel API JSON → Candlestick list
     */
    private List<Candlestick> parseCandles(String symbol, JSONArray response) {

        List<Candlestick> candles = new ArrayList<>();

        try {

            // Determine if response is [[...], [...]] or [[timestamp, o, h, l, c, v], ...]
            JSONArray dataArray;

            Object first = response.get(0);

            if (first instanceof JSONArray) {
                // Direct array of candle arrays
                dataArray = response;
            } else {
                // Wrapped: outer array has one element which is the data array
                dataArray = response.getJSONArray(0);
            }

            // ✅ FIX #4: Validate each row has expected 6 fields before parsing
            for (int i = 0; i < dataArray.length(); i++) {

                Object row = dataArray.get(i);

                if (!(row instanceof JSONArray)) {
                    logger.warn("Unexpected row type at index {} for symbol {}: {}", i, symbol, row);
                    continue;
                }

                JSONArray c = (JSONArray) row;

                if (c.length() < 6) {
                    logger.warn("Short candle row at index {} for symbol {}, length={}", i, symbol, c.length());
                    continue;
                }

                Candlestick candle = new Candlestick();
                candle.setTimestamp(c.getString(0));
                candle.setOpen(BigDecimal.valueOf(c.getDouble(1)));
                candle.setHigh(BigDecimal.valueOf(c.getDouble(2)));
                candle.setLow(BigDecimal.valueOf(c.getDouble(3)));
                candle.setClose(BigDecimal.valueOf(c.getDouble(4)));
                candle.setVolume(BigDecimal.valueOf(c.getDouble(5)));

                candles.add(candle);
            }

        } catch (Exception e) {
            logger.error("Error parsing candles for symbol {}", symbol, e);
        }

        logger.debug("Parsed {} candles for {}", candles.size(), symbol);
        return candles;
    }

    /**
     * Helper to wrap the execution with an activity check.
     */
    private void executeIfActive(Runnable tradeLogic) {
        if (isActive(STRATEGY_NAME)) {
            tradeLogic.run();
        }
    }

    /**
     * Optimized: Queries the database only once per pulse.
     */
    private boolean isActive(String strategyName) {
        Strategy strategy = strategyRepo.findByName(strategyName);
        return strategy != null && "Y".equalsIgnoreCase(strategy.getActive());
    }
}