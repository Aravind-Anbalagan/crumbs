package com.crumbs.trade.controller;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import com.angelbroking.smartapi.SmartConnect;
import com.crumbs.trade.dto.ChartDataDTO;
import com.crumbs.trade.dto.LevelAnalysisResult;
import com.crumbs.trade.entity.Level;
import com.crumbs.trade.entity.Strategy;
import com.crumbs.trade.repo.LevelRepository;
import com.crumbs.trade.service.*;
import com.crumbs.trade.utility.LevelAnalysisUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.MessagingException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.angelbroking.smartapi.http.exceptions.SmartAPIException;
import com.crumbs.trade.entity.Vix;
import com.crumbs.trade.repo.PricesNiftyRepo;
import com.crumbs.trade.repo.StrategyRepo;
import com.crumbs.trade.repo.VixRepo;

import jakarta.mail.internet.AddressException;

@RestController
@RequestMapping(value = "/heikinpsar")
public class HeikinPsarController {

    private static final Logger logger = LogManager.getLogger(HeikinPsarController.class);

    @Autowired private ChartService chartService;
    @Autowired private VixRepo vixRepo;
    @Autowired private PricesNiftyRepo pricesNiftyRepo;
    @Autowired private TaskService taskService;
    @Autowired private StrategyRepo strategyRepo;
    @Autowired private OIService oiService;
    @Autowired private SRService srService;
    @Autowired private TradeManagerService tradeManagerService;
    @Autowired private LevelRepository levelRepo;

    private static final DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ---------------------------- SCHEDULED TASKS ----------------------------

    // For 9:20:05 AM to 9:55:05 AM
    @Scheduled(cron = "5 20-55/5 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledTask1() {
        runSafely("scheduledTask1", () -> {
            try {
                commonExecution_2();
            } catch (Exception | SmartAPIException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // For 10:00:05 AM to 2:55:05 PM
    @Scheduled(cron = "5 0/5 10-14 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledTask2() {
        runSafely("scheduledTask2", () -> {
            try {
                commonExecution_2();
            } catch (Exception | SmartAPIException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // For 3:00:05 PM to 3:15:05 PM
    @Scheduled(cron = "5 0-15/5 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledTask3() {
        runSafely("scheduledTask3", () -> {
            try {
                commonExecution_2();
            } catch (Exception | SmartAPIException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // For 4:00:05 PM to 10:55:05 PM and 11:00–11:15 PM
    @Scheduled(cron = "5 0/5 16-22 * * MON-FRI", zone = "Asia/Kolkata")
    @Scheduled(cron = "5 0-15/5 23 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledTask4() {
        runSafely("scheduledTask4", () -> {
            try {
                commonExecution_3();
            } catch (Exception | SmartAPIException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // Every 10 seconds – check for executed orders
    @Scheduled(cron = "*/10 * * * * MON-FRI")
    public void monitorExecutedOrders() {
        runSafely("monitorExecutedOrders", () -> {
            try {
                if (chartService.getName().equalsIgnoreCase("NIFTY")
                        && "Y".equalsIgnoreCase(strategyRepo.findByName("NIFTY").getActive())) {
                    List<Vix> vixList = vixRepo.findAllByNameContainingOrderByIdDesc("NIFTY");
                    if (vixList != null && !vixList.isEmpty()) {
                        chartService.lookForExecutedOrder("NIFTY", "NFO", vixList.get(0), false);
                    }
                } else if ("Y".equalsIgnoreCase(strategyRepo.findByName("SILVERM").getActive())) {
                    List<Vix> vixList = vixRepo.findAllByNameContainingOrderByIdDesc("SILVERM");
                    if (vixList != null && !vixList.isEmpty()) {
                        chartService.lookForExecutedOrder("SILVERM", "MCX", vixList.get(0), false);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    // Exit for Nifty
    @Scheduled(cron = "0 20 15 ? * MON-FRI", zone = "Asia/Kolkata")
    public void nfoExit() {
        runSafely("nfoExit", () -> {
            try {
                chartService.exitFromTrade("NIFTY", "NFO");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    // Exit for Crude
    @Scheduled(cron = "0 20 23 ? * MON-FRI", zone = "Asia/Kolkata")
    public void mcxExit() {
        runSafely("mcxExit", () -> {
            try {
                chartService.exitFromTrade("SILVERM", "MCX");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    // Optional heartbeat to detect scheduler health
 // Runs every 5 minutes between 9:00 AM and 11:30 PM, Monday–Friday
    @Scheduled(cron = "0 */5 9-22 * * MON-FRI", zone = "Asia/Kolkata")
    @Scheduled(cron = "0 0-30/5 23 * * MON-FRI", zone = "Asia/Kolkata")
    public void schedulerHeartbeat() {
        logger.info("🩵 Scheduler heartbeat OK at {}", LocalDateTime.now().format(timeFormat));
    }

    // ---------------------------- STRATEGY EXECUTION ----------------------------

    // Strategy 1 (not scheduled)
    public void commonExecution_1() throws SmartAPIException {
        if ("Y".equalsIgnoreCase(strategyRepo.findByName("NIFTY").getActive())) {
            pricesNiftyRepo.deleteAll();
            taskService.getVolumeData("FIVE_MINUTE", "NFO", false);
        }
    }

    // Strategy 2 (NIFTY + VIX)
    public void commonExecution_2() throws SmartAPIException, AddressException, MessagingException, IOException {
        String fromDate = chartService.getDate("FROM", "NSE");
        String toDate = chartService.getDate("TO", "NSE");
        vixRepo.deleteAll();

        if ("Y".equalsIgnoreCase(strategyRepo.findByName("VIX").getActive())) {
            chartService.readChartData("FIVE_MINUTE", "NSE", false, "VIX", fromDate, toDate,
                    strategyRepo.findByName("VIX").getTradingsymbol());
        }

        if ("Y".equalsIgnoreCase(strategyRepo.findByName("NIFTY").getActive())) {
            chartService.readChartData("FIVE_MINUTE", "NFO", false, "NIFTY", fromDate, toDate,
                    strategyRepo.findByName("NIFTY").getTradingsymbol());
            chartService.monitorSignal("NIFTY", "NFO", false, 0);
        }

        if ("Y".equalsIgnoreCase(strategyRepo.findByName("NIFTY_OI").getActive())) {
            oiService.getOptionChain("NIFTY_OI");
        }
        if ("Y".equalsIgnoreCase(strategyRepo.findByName("SR").getActive())) {
            ChartDataDTO  chartDataDTO  = srService.analyzeIntraday("NIFTY","FIVE_MINUTE");
            srService.saveLevels("NIFTY","FIVE_MINUTE",chartDataDTO);
        }

    }

    // Strategy 3 (MCX)
    public void commonExecution_3() throws SmartAPIException, AddressException, MessagingException, IOException {
        String fromDate = chartService.getDate("FROM", "MCX");
        String toDate = chartService.getDate("TO", "MCX");
        vixRepo.deleteAll();

        String name = "SILVERM";
        if ("Y".equalsIgnoreCase(strategyRepo.findByName(name).getActive())) {
            chartService.readChartData("FIVE_MINUTE", "MCX", false, name, fromDate, toDate,
                    strategyRepo.findByName(name).getTradingsymbol());
            chartService.monitorSignal(name, "MCX", false, 0);
        }
    }

    // ---------------------------- UTILITIES ----------------------------

    @GetMapping("/getCandleList")
    public List<Vix> getCandleData() {
        return vixRepo.findByName("CRUDEOIL");
    }

    /**
     * Safely runs a scheduled or repeated task.
     * Logs any exception but does not stop scheduler threads.
     */
    private void runSafely(String taskName, Runnable runnable) {
        try {
            //logger.info("▶️ {} started at {}", taskName, LocalDateTime.now().format(timeFormat));
            runnable.run();
            //logger.info("✅ {} completed at {}", taskName, LocalDateTime.now().format(timeFormat));
        } catch (Exception e) {
            logger.error("❌ {} failed at {} with error: {}", 
                    taskName, LocalDateTime.now().format(timeFormat), e.getMessage(), e);
        }
    }

    @Scheduled(cron = "*/10 15-59 9 * * MON-FRI", zone = "Asia/Kolkata")
    @Scheduled(cron = "*/10 * 10-14 * * MON-FRI", zone = "Asia/Kolkata")
    @Scheduled(cron = "*/10 0-30 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runStrategy() {

        String symbol = "NIFTY";
        String timeframe = "FIVE_MINUTE";

        BigDecimal ltp = chartService.getCurrentPrice(symbol);

        // 1️⃣ Validate LTP
        if (ltp == null || ltp.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        // 2️⃣ Load levels
        List<Level> levels =
                levelRepo.findBySymbolAndTimeframe(symbol, timeframe);
        // 🔥 ADD THIS - Filters based on method
        levels = levels.stream()
            .filter(l -> tradeManagerService.isMethodAllowed(l.getMethod()))
            .collect(Collectors.toList());

        
        if (levels == null || levels.isEmpty()) {
            return;
        }

        // 3️⃣ Analyze
        LevelAnalysisResult analysis =
                LevelAnalysisUtil.analyze(ltp, levels);

        // 4️⃣ Handle trade ENTRY (BUY / SELL only)
        tradeManagerService.handleSignal(
                symbol, timeframe, analysis);

        // 5️⃣ Handle trade EXIT (TARGET / SL)
        tradeManagerService.monitorTrade(
                symbol, timeframe, ltp);
    }


}
