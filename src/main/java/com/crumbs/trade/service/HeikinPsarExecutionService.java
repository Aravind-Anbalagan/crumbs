package com.crumbs.trade.service;

import java.io.IOException;
import java.util.List;
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
    private static final String NIFTY = "NIFTY";
    private static final String VIX = "VIX";
    private static final String CRUDEOIL = "CRUDEOIL";
    private static final String SILVERM = "SILVERM";
    private static final String NIFTY_OI = "NIFTY_OI";
    
    // Exchanges
    private static final String EXCHANGE_NSE = "NSE";
    private static final String EXCHANGE_NFO = "NFO";
    private static final String EXCHANGE_MCX = "MCX";
    
    // Timeframes
    private static final String TF_FIVE_MIN = "FIVE_MINUTE";
    private static final String TF_ONE_MIN = "ONE_MINUTE";
    
    // Strategy Flags
    private static final String STRAT_HEIKIN_PSAR = "HEIKIN-PSAR";
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

        String from = chartService.getDate("FROM", EXCHANGE_NSE, 1);
        String to   = chartService.getDate("TO", EXCHANGE_NSE, 1);

        vixRepo.deleteAll();

        // Process VIX if active
        if (isActive(STRAT_VIX)) {
            chartService.readChartData(
                    TF_FIVE_MIN, EXCHANGE_NSE, false, VIX,
                    from, to,
                    strategyRepo.findByName(VIX).getTradingsymbol()
            );
        }

        // Process Heikin-PSAR Strategy
        if (isActive(STRAT_HEIKIN_PSAR)) {
            chartService.readChartData(
                    TF_FIVE_MIN, EXCHANGE_NFO, false, NIFTY,
                    from, to,
                    strategyRepo.findByName(NIFTY).getTradingsymbol()
            );
            //chartService.monitorSignal(NIFTY, EXCHANGE_NFO, false, 0);
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

        String from = chartService.getDate("FROM", EXCHANGE_MCX, 1);
        String to   = chartService.getDate("TO", EXCHANGE_MCX, 1);

        vixRepo.deleteAll();

        // Process MCX Heikin-PSAR
        if (isActive(STRAT_HEIKIN_PSAR)) {
            chartService.readChartData(
                    TF_ONE_MIN, EXCHANGE_MCX, false, CRUDEOIL,
                    from, to,
                    strategyRepo.findByName(CRUDEOIL).getTradingsymbol()
            );
            //chartService.monitorSignal(CRUDEOILM, EXCHANGE_MCX, false, 0);
        }
/*
        // MCX Support/Resistance
        if (isActive(STRAT_SR)) {
            srService.analyzeIntraday(CRUDEOILM, TF_FIVE_MIN);
        }
        */
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