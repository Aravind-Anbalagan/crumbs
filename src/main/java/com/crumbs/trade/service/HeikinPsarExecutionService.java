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

    private static final Logger logger =
            LogManager.getLogger(HeikinPsarExecutionService.class);

    @Autowired private ChartService chartService;
    @Autowired private StrategyRepo strategyRepo;
    @Autowired private VixRepo vixRepo;
    @Autowired private OIService oiService;
    @Autowired private SRService srService;
    @Autowired private TradeManagerService tradeManagerService;
    @Autowired private LevelRepository levelRepo;

    // ================= CORE EXECUTIONS =================

    public void commonExecutionNifty() {
        try {
            executeNiftyInternal();
        } catch (SmartAPIException e) {
            logApiError("NIFTY", e);
        } catch (Exception e) {
            logGeneralError("NIFTY", e);
        }
    }

    public void commonExecutionMcx() {
        try {
            executeMcxInternal();
        } catch (SmartAPIException e) {
            logApiError("SILVERM", e);
        } catch (Exception e) {
            logGeneralError("SILVERM", e);
        }
    }

    // ================= INTERNAL METHODS =================

    private void executeNiftyInternal()
            throws SmartAPIException, IOException, AddressException, MessagingException {

        String from = chartService.getDate("FROM", "NSE");
        String to   = chartService.getDate("TO", "NSE");

        vixRepo.deleteAll();

        if (isActive("VIX")) {
            chartService.readChartData(
                    "FIVE_MINUTE", "NSE", false, "VIX",
                    from, to,
                    strategyRepo.findByName("VIX").getTradingsymbol()
            );
        }

        if (isActive("NIFTY")) {
            chartService.readChartData(
                    "FIVE_MINUTE", "NFO", false, "NIFTY",
                    from, to,
                    strategyRepo.findByName("NIFTY").getTradingsymbol()
            );
            chartService.monitorSignal("NIFTY", "NFO", false, 0);
        }

        if (isActive("NIFTY_INDEX")) {
            oiService.getOptionChain("NIFTY_OI");
        }

        if (isActive("SR")) {
            ChartDataDTO dto = srService.analyzeIntraday("NIFTY", "FIVE_MINUTE");
            srService.saveLevels("NIFTY", "FIVE_MINUTE", dto);
        }
    }

    private void executeMcxInternal()
            throws SmartAPIException, IOException, AddressException, MessagingException {

        String from = chartService.getDate("FROM", "MCX");
        String to   = chartService.getDate("TO", "MCX");

        vixRepo.deleteAll();

        if (isActive("SILVERM")) {
            chartService.readChartData(
                    "FIVE_MINUTE", "MCX", false, "SILVERM",
                    from, to,
                    strategyRepo.findByName("SILVERM").getTradingsymbol()
            );
            chartService.monitorSignal("SILVERM", "MCX", false, 0);
        }

        if (isActive("SR")) {
            ChartDataDTO dto = srService.analyzeIntraday("SILVERM", "FIVE_MINUTE");
            srService.saveLevels("SILVERM", "FIVE_MINUTE", dto);
        }
    }

    // ================= ORDER MONITOR =================

    public void monitorExecutedOrders() {
        try {
            check("NIFTY", "NFO");
            check("SILVERM", "MCX");
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
        return s != null && "Y".equalsIgnoreCase(s.getActive());
    }

    private void logApiError(String tag, SmartAPIException e) {
        logger.error("🚨 SmartAPI error [{}] – continuing scheduler", tag, e);
    }

    private void logGeneralError(String tag, Exception e) {
        logger.error("❌ Execution error [{}] – continuing scheduler", tag, e);
    }
}
