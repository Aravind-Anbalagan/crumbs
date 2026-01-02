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
import com.crumbs.trade.utility.AdviceStatus;
import com.crumbs.trade.utility.MarketDirection;
import com.crumbs.trade.utility.PressureZone;
import com.crumbs.trade.utility.TradingMode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
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
    
    // =====================================================
    // CRITICAL: OPENING RANGE PROTECTION
    // =====================================================
    private static final int OPENING_RANGE_MINUTES = 30;
    private static final int CLOSING_BUFFER_MINUTES = 15;

    // =====================================================
    // INTRADAY ADVISOR – ALL INSTRUMENTS
    // =====================================================
    @Scheduled(cron = "0 * * * * MON-FRI", zone = "Asia/Kolkata")
    public void runAdvisor() {

        LocalTime now = LocalTime.now(IST);
        LocalDate today = LocalDate.now(IST);

        for (InstrumentConfig cfg : InstrumentRegistry.INSTRUMENTS) {

            String symbol = cfg.symbol();
            
            // =====================================================
            // GUARD 1: MARKET HOURS CHECK
            // =====================================================
            if (now.isBefore(cfg.start()) || now.isAfter(cfg.end())) {
                continue;
            }
            
            // =====================================================
            // GUARD 2: OPENING RANGE FILTER (CRITICAL FIX)
            // Skip first 30 minutes - 80% of false signals occur here
            // =====================================================
            LocalTime openingRangeEnd = cfg.start()
                .plusMinutes(OPENING_RANGE_MINUTES);
            
            if (now.isBefore(openingRangeEnd)) {
                log.debug("{}: Skipping opening range (until {})", 
                    symbol, openingRangeEnd);
                continue;
            }
            
            // =====================================================
            // GUARD 3: CLOSING RANGE FILTER
            // Don't enter new positions near market close
            // =====================================================
            LocalTime closingStart = cfg.end()
                .minusMinutes(CLOSING_BUFFER_MINUTES);
            
            if (now.isAfter(closingStart)) {
                log.debug("{}: Skipping closing range (after {})", 
                    symbol, closingStart);
                continue;
            }

            // =====================================================
            // DATA FETCH: Latest snapshot (multi-strike)
            // =====================================================
            List<StraddleIntraday> snapshot =
                    straddleRepo.findLatestSnapshot(symbol);

            if (snapshot == null || snapshot.isEmpty()) {
                log.warn("{}: No market data available", symbol);
                continue;
            }

            PressureInsightDTO pressure =
                    pressureService.calculateFromSnapshot(snapshot);

            // =====================================================
            // ACTIVE ADVICE CHECK + EXIT MONITORING
            // =====================================================
            Optional<TradingAdvice> active =
                    adviceRepo.findActiveAdvice(symbol, today);

            // =====================================================
            // OBSERVER MODE: EXIT CHECKS (WHEN TRADE IS ACTIVE)
            // =====================================================
            if (active.isPresent()) {

                TradingAdvice advice = active.get();

                // Run all exit checks
                if (observerService.shouldExit(advice, pressure)) {
                    
                    advice.setStatus(AdviceStatus.EXITED);
                    advice.setExitTime(LocalDateTime.now(IST));
                    // exitReason already set by observerService
                    
                    adviceRepo.save(advice);
                    
                    log.info("{}: EXITED trade #{} - Reason: {}", 
                        symbol, 
                        advice.getId(), 
                        advice.getExitReason());
                }

                // Never create a new advice while one is active
                continue;
            }

            // =====================================================
            // COOLDOWN CHECK (per instrument)
            // =====================================================
            Optional<TradingAdvice> last =
                    adviceRepo.findTopBySymbolAndTradeDateOrderByAdviceTimeDesc(
                            symbol, today);

            if (last.isPresent()
                    && last.get().getExitTime() != null
                    && last.get().getExitTime()
                        .plusMinutes(cfg.cooldownMinutes())
                        .isAfter(LocalDateTime.now(IST))) {
                log.debug("{}: In cooldown period", symbol);
                continue;
            }

            // =====================================================
            // PRESSURE STABILITY CHECK (2-tick + volume/OI validation)
            // =====================================================
            if (!pressureService.isPressureStable(symbol, pressure)) {
                log.debug("{}: Pressure not stable yet", symbol);
                continue;
            }

            // =====================================================
            // ADVISOR DECISION
            // =====================================================
            AdvisorDecisionDTO decision =
                    advisorService.advise(pressure);

            if (decision.getRecommendedMode() == TradingMode.NO_TRADE) {
                continue;
            }

            MarketDirection direction =
                    pressureService.determineMarketDirection(snapshot);

            if (direction == MarketDirection.NEUTRAL) {
                log.debug("{}: Market direction is NEUTRAL", symbol);
                continue;
            }

            // =====================================================
            // CREATE NEW ADVICE
            // =====================================================
            TradingAdvice advice = new TradingAdvice();
            advice.setSymbol(symbol);
            advice.setTradeDate(today);
            advice.setAdviceTime(LocalDateTime.now(IST));

            advice.setRecommendedMode(decision.getRecommendedMode());
            advice.setDirection(direction);

            advice.setEntryPressure(pressure.getPressure());
            advice.setEntryZone(pressure.getZone());

            // Critical: Store entry metrics for exit calculations
            advice.setEntrySpot(snapshot.get(0).getSpot());
            advice.setEntryPremium(snapshot.get(0).getCombinedPremium());

            advice.setStatus(AdviceStatus.ACTIVE);

            adviceRepo.save(advice);
            
            log.info("{}: NEW TRADE #{} - {} {} at {} (Pressure: {}/{})", 
                symbol, 
                advice.getId(),
                advice.getRecommendedMode(),
                advice.getDirection(),
                advice.getEntrySpot(),
                advice.getEntryPressure(),
                advice.getEntryZone());
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
    public void runCrudeAudit() {
        runAuditForSymbol("CRUDEOIL", LocalDate.now(IST));
    }

    // =====================================================
    // SHARED AUDIT LOGIC
    // =====================================================
    private void runAuditForSymbol(String symbol, LocalDate tradeDate) {

        List<TradingAdvice> advices =
                adviceRepo.findBySymbolAndTradeDate(symbol, tradeDate);

        if (advices == null || advices.isEmpty()) {
            log.info("{}: No trades to audit for {}", symbol, tradeDate);
            return;
        }

        for (TradingAdvice advice : advices) {

            List<StraddleIntraday> data =
                    straddleRepo.findByNameAndTradeDateOrderByTimestamp(
                            symbol, tradeDate);

            if (data == null || data.isEmpty()) {
                log.warn("{}: No market data for audit on {}", symbol, tradeDate);
                continue;
            }

            TradingAdviceAudit audit =
                    auditService.evaluate(advice, data, pressureService);

            auditRepo.save(audit);
            
            log.info("{}: Audited trade #{} - Conclusion: {}", 
                symbol, 
                advice.getId(), 
                audit.getAuditConclusion());
        }
    }
}