package com.crumbs.trade.scheduler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.crumbs.trade.config.InstrumentConfig;
import com.crumbs.trade.config.InstrumentRegistry;
import com.crumbs.trade.dto.AdvisorDecisionDTO;
import com.crumbs.trade.dto.PressureInsightDTO;
import com.crumbs.trade.entity.StraddleIntraday;
import com.crumbs.trade.entity.TradingAdvice;
import com.crumbs.trade.entity.TradingAdviceAudit;
import com.crumbs.trade.repo.StraddleIntradayRepo;
import com.crumbs.trade.repo.TradingAdviceAuditRepo;
import com.crumbs.trade.repo.TradingAdviceRepo;
import com.crumbs.trade.service.AdviceAuditService;
import com.crumbs.trade.service.AdviceObserverService;
import com.crumbs.trade.service.MarketPressureService;
import com.crumbs.trade.service.TradingAdvisorService;
import com.crumbs.trade.utility.MarketDirection;
import com.crumbs.trade.utility.PressureZone;
import com.crumbs.trade.utility.TradingMode;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class IntradayAdvisorScheduler {

    private final StraddleIntradayRepo straddleRepo;
    private final MarketPressureService pressureService;
    private final TradingAdviceRepo adviceRepo;
    private final AdviceObserverService observerService;
    private final TradingAdvisorService advisorService;
    private final AdviceAuditService auditService;
    private final TradingAdviceAuditRepo auditRepo;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ---- in-memory guards (human behaviour)
    private PressureZone lastZone = null;
    private LocalDateTime lastExitTime = null;

    // =====================================================
    // INTRADAY ADVISOR – NIFTY (EVERY MINUTE)
    // =====================================================
    @Scheduled(cron = "0 * * * * MON-FRI", zone = "Asia/Kolkata")
    public void runAdvisor() {

        LocalTime now = LocalTime.now(IST);

        for (InstrumentConfig cfg : InstrumentRegistry.INSTRUMENTS) {

            // ===============================
            // Market hours guard
            // ===============================
            if (now.isBefore(cfg.start()) || now.isAfter(cfg.end())) {
                continue;
            }

            String symbol = cfg.symbol();

            // ===============================
            // Fetch latest snapshot (multi-strike)
            // ===============================
            List<StraddleIntraday> snapshot =
                    straddleRepo.findLatestSnapshot(symbol);

            if (snapshot.isEmpty()) continue;

            PressureInsightDTO pressure =
                    pressureService.calculateFromSnapshot(snapshot);

            // ===============================
            // Active advice check
            // ===============================
            Optional<TradingAdvice> active =
                    adviceRepo.findFirstBySymbolAndTradeDateAndStatus(
                            symbol, LocalDate.now(IST), "ACTIVE");

            // ===============================
            // OBSERVER (exit)
            // ===============================
            if (active.isPresent()) {
                TradingAdvice advice = active.get();

                if (observerService.shouldExit(advice, pressure)) {
                    advice.setStatus("EXITED");
                    advice.setExitTime(LocalDateTime.now(IST));
                    advice.setExitReason("Pressure invalidated belief");
                    adviceRepo.save(advice);
                }
                continue;
            }

            // ===============================
            // COOLDOWN (per instrument)
            // ===============================
            Optional<TradingAdvice> last =
                    adviceRepo.findTopBySymbolAndTradeDateOrderByAdviceTimeDesc(
                            symbol, LocalDate.now(IST));

            if (last.isPresent() &&
                last.get().getExitTime() != null &&
                last.get().getExitTime()
                    .plusMinutes(cfg.cooldownMinutes())
                    .isAfter(LocalDateTime.now(IST))) {
                continue;
            }

            // ===============================
            // PRESSURE CONFIRMATION
            // ===============================
            if (!pressureService.isPressureStable(symbol, pressure)) {
                continue;
            }

            // ===============================
            // ADVICE
            // ===============================
            AdvisorDecisionDTO decision =
                    advisorService.advise(pressure);

            if (decision.getRecommendedMode() == TradingMode.NO_TRADE) {
                continue;
            }

            MarketDirection direction =
                    pressureService.determineMarketDirection(snapshot);

            TradingAdvice advice = new TradingAdvice();
            advice.setSymbol(symbol);
            advice.setTradeDate(LocalDate.now(IST));
            advice.setAdviceTime(LocalDateTime.now(IST));
            advice.setRecommendedMode(decision.getRecommendedMode());
            advice.setEntryPressure(pressure.getPressure());
            advice.setEntryZone(pressure.getZone().name());
            advice.setDirection(direction.name());
            advice.setStatus("ACTIVE");

            adviceRepo.save(advice);
        }
    }


    // =====================================================
    // EOD AUDIT – NIFTY
    // =====================================================
    @Scheduled(cron = "0 45 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runNiftyAudit() {
        runAuditForSymbol("NIFTY", LocalDate.now(IST));
    }

    // =====================================================
    // EOD AUDIT – CRUDE
    // =====================================================
    @Scheduled(cron = "0 50 23 * * MON-FRI", zone = "Asia/Kolkata")
    public void runAudit() {
        runAuditForSymbol("CRUDEOIL", LocalDate.now(IST));
    }

    // =====================================================
    // SHARED AUDIT LOGIC
    // =====================================================
    private void runAuditForSymbol(String symbol, LocalDate tradeDate) {

        List<TradingAdvice> advices =
                adviceRepo.findBySymbolAndTradeDate(symbol, tradeDate);

        if (advices.isEmpty()) return;

        for (TradingAdvice advice : advices) {

            List<StraddleIntraday> data =
                    straddleRepo.findByNameAndTradeDateOrderByTimestamp(
                            symbol, tradeDate);

            if (data.isEmpty()) continue;

            TradingAdviceAudit audit =
                    auditService.evaluate(advice, data, pressureService);

            auditRepo.save(audit);
        }
    }
}
