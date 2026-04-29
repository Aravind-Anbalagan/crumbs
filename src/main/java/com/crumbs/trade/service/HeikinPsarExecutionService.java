package com.crumbs.trade.service;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import com.crumbs.trade.dto.ChartDataDTO;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.LevelRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.MessagingException;
import org.springframework.stereotype.Service;
import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.entity.Vix;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.repo.VixRepo;

import jakarta.mail.internet.AddressException;

@Service
public class HeikinPsarExecutionService {

    private static final Logger logger = LogManager.getLogger(HeikinPsarExecutionService.class);

    // ================= CONFIGURATION CONSTANTS =================
    // Symbols & Names
    private static final String NIFTY = "SAMCO_NIFTY";
    private static final String VIX = "VIX";
    private static final String CRUDEOIL = "CRUDEOIL";
    private static final String SILVERM = "SILVERM";
    private static final String NIFTY_OI = "NIFTY_OI";
    private static final String SAMCO_CRUDEOIL = "SAMCO_CRUDEOIL";
    
    // Exchanges
    private static final String EXCHANGE_NSE = "NSE";
    private static final String EXCHANGE_NFO = "NFO";
    private static final String EXCHANGE_MCX = "MCX";
    
    // Timeframes
    private static final String TF_FIVE_MIN = "FIVE_MINUTE";
    private static final String TF_ONE_MIN = "ONE_MINUTE";
    private static final int FIVE_MIN = 5;
    // Strategy Flags
    private static final String STRAT_HEIKIN_PSAR = "MARKET_TREND";
    private static final String STRAT_VIX = "VIX";
    private static final String STRAT_NIFTY_INDEX = "NIFTY_INDEX";
    private static final String STRAT_SR = "SR";
    
    // Status
    private static final String ACTIVE_YES = "Y";

    @Autowired private ChartService chartService;
    @Autowired private StrategyRepo strategyRepo;
    @Autowired private VixRepo vixRepo;
    @Autowired private OIService oiService;
    @Autowired private SRService srService;
    @Autowired private LevelRepository levelRepo;

    // ================= CORE EXECUTIONS =================

    /**
     * Entry point for Nifty-related strategy logic.
     */
    public void commonExecutionNifty() {
        try {
            executeNiftyInternal();
        } catch (SmartAPIException e) {
            logApiError(NIFTY, e);
        } catch (Exception e) {
            logGeneralError(NIFTY, e);
        }
    }

    /**
     * Entry point for MCX (Crude Oil) related strategy logic.
     */
    public void commonExecutionMcx() {
        try {
            executeMcxInternal();
        } catch (SmartAPIException e) {
            logApiError(CRUDEOIL, e);
        } catch (Exception e) {
            logGeneralError(CRUDEOIL, e);
        }
    }

    // ================= INTERNAL METHODS =================

    private void executeNiftyInternal()
            throws SmartAPIException, IOException, AddressException, MessagingException {

    	// 1. Get the current time for the 'TO' parameter
        String to = chartService.getDate("TO", EXCHANGE_NSE, 1);
        String from;

        // 2. Check the database for the last fetched candle
        Optional<Vix> lastRecord = vixRepo.findFirstByNameOrderByTimestampDesc(NIFTY);

        if (lastRecord.isPresent()) {
        	// Start from the last known candle's timestamp. 
            // This ensures if the last candle was incomplete, it gets updated.
            
            String rawTimestamp = lastRecord.get().getTimestamp(); // "2026-04-29T09:15:00+05:30"
            
            // Define the desired format
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            
            // Parse the ISO string and format it
            from = OffsetDateTime.parse(rawTimestamp).format(formatter); 
        } else {
            // Fallback: If the DB is completely empty (e.g., first run of the day), 
            // fetch from the morning open.
            from = chartService.getDate("FROM", EXCHANGE_NSE, 1);
        }

        // Process Heikin-PSAR Strategy
        if (isActive(STRAT_HEIKIN_PSAR)) {
            chartService.readChartData(
                    TF_FIVE_MIN, EXCHANGE_NFO, false, NIFTY,
                    from, to, // Now 'from' is dynamic!
                    strategyRepo.findByName(NIFTY).getTradingsymbol()
                    ,"SAMCO");
        }
/*
        // Option Chain analysis
        if (isActive(STRAT_NIFTY_INDEX)) {
            oiService.getOptionChain(NIFTY_OI);
        }

        // Support/Resistance Analysis
        if (isActive(STRAT_SR)) {
            srService.analyzeIntraday(NIFTY, TF_FIVE_MIN);
        }
        */
    }

    private void executeMcxInternal()
            throws SmartAPIException, IOException, AddressException, MessagingException {

        // 1. Get current time for 'TO'
        String to = chartService.getDate("TO", EXCHANGE_MCX, 1);
        String from;

        // 2. Check the database for the last fetched CRUDEOIL candle
        Optional<Vix> lastRecord = vixRepo.findFirstByNameOrderByTimestampDesc(CRUDEOIL);

        if (lastRecord.isPresent()) {
            from = ChartService.formatDateTime(lastRecord.get().getTimestamp());
        } else {
            from = chartService.getDate("FROM", EXCHANGE_MCX, 1);
        }
       
        // Process MCX Heikin-PSAR
           if (isActive(STRAT_HEIKIN_PSAR)) {  
        	Strategy strategy = strategyRepo.findByName(SAMCO_CRUDEOIL);
        	if(strategy!=null)
        	{
        		 chartService.readChartData(
                         TF_FIVE_MIN, strategy.getExchange(), false, CRUDEOIL,
                         from, to,
                         strategyRepo.findByName(CRUDEOIL).getTradingsymbol()
                 ,"SAMCO");
        	}
           
        }
    }

    // ================= ORDER MONITOR =================

    /**
     * Periodically called to check status of executed trades.
     */
    public void monitorExecutedOrders() {
        try {
            check(NIFTY, EXCHANGE_NFO);
            check(SILVERM, EXCHANGE_MCX);
        } catch (Exception e) {
            logger.error("❌ Error monitoring executed orders", e);
        }
    }

    private void check(String name, String exchange) {
        if (!isActive(name)) return;

        List<Vix> list = vixRepo.findAllByNameContainingOrderByIdDesc(name);
        if (list == null || list.isEmpty()) return;

        chartService.lookForExecutedOrder(name, exchange, list.get(0), false);
    }

    // ================= EXIT =================

    /**
     * Forced exit logic for EOD or specific conditions.
     */
    public void exit(String symbol, String exchange) {
        try {
            chartService.exitFromTrade(symbol, exchange);
        } catch (Exception e) {
            logger.error("❌ Exit failed for {} {}", symbol, exchange, e);
        }
    }

    // ================= UTIL =================

    private boolean isActive(String name) {
        // 1. Check if today is a weekend. If so, return false immediately.
        DayOfWeek today = LocalDate.now().getDayOfWeek();
        if (today == DayOfWeek.SATURDAY || today == DayOfWeek.SUNDAY) {
            return false;
        }

        // 2. If it's a weekday, proceed with the original logic
        Strategy s = strategyRepo.findByName(name);
        return s != null && ACTIVE_YES.equalsIgnoreCase(s.getActive());
    }

    private void logApiError(String tag, SmartAPIException e) {
        logger.error("🚨 SmartAPI error [{}] – continuing scheduler", tag, e);
    }

    private void logGeneralError(String tag, Exception e) {
        logger.error("❌ Execution error [{}] – continuing scheduler", tag, e);
    }
}