package com.crumbs.trade.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.crumbs.trade.dto.AdviceStatusDTO;
import com.crumbs.trade.dto.PressureInsightDTO;
import com.crumbs.trade.entity.StraddleIntraday;
import com.crumbs.trade.entity.TradingAdvice;
import com.crumbs.trade.repo.StraddleIntradayRepo;
import com.crumbs.trade.repo.TradingAdviceRepo;
import com.crumbs.trade.utility.AdviceState;
import com.crumbs.trade.utility.MarketDirection;
import com.crumbs.trade.utility.PressureZone;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdviceStatusService {

    private final TradingAdviceRepo adviceRepo;
    private final StraddleIntradayRepo intradayRepo;
    private final MarketPressureService pressureService;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // =====================================================
    // PUBLIC API ENTRY POINT
    // =====================================================
    public AdviceStatusDTO getCurrentStatus(String rawSymbol) {

        String symbol = rawSymbol.toUpperCase();
        LocalDate today = LocalDate.now(IST);

        // 1️⃣ READ MEMORY (DB FIRST)
        Optional<TradingAdvice> optAdvice =
                adviceRepo.findTopBySymbolAndTradeDateOrderByAdviceTimeDesc(symbol, today);

        if (optAdvice.isEmpty()) {
            return noSignal(symbol);
        }

        TradingAdvice advice = optAdvice.get();

        // 2️⃣ READ REALITY (MARKET NOW)
        List<StraddleIntraday> snapshot =
                intradayRepo.findLatestByName(symbol);

        if (snapshot == null || snapshot.isEmpty()) {
            return marketUnavailable(symbol, advice);
        }

        PressureInsightDTO currentPressure =
                pressureService.calculateFromSnapshot(snapshot);

        MarketDirection currentDirection =
                pressureService.determineMarketDirection(snapshot);

        BigDecimal currentSpot = snapshot.get(0).getSpot();

        // 3️⃣ VALIDATIONS
        boolean directionInvalidated =
                isDirectionInvalidated(advice, currentSpot);

        boolean pressureWeakened =
                currentPressure.getPressure()
                        < advice.getEntryPressure() - 15;

        boolean pressureStrengthened =
                currentPressure.getPressure()
                        > advice.getEntryPressure() + 10
                && currentDirection == advice.getDirection();

        boolean aging =
                Duration.between(
                        advice.getAdviceTime(),
                        LocalDateTime.now(IST)
                ).toMinutes() > 90;

        // =================================================
        // 4️⃣ STATUS DERIVATION (NO EXIT HERE)
        // =================================================

        if (directionInvalidated) {
            return exited(
                    symbol,
                    advice,
                    currentPressure,
                    "Direction invalidated by price"
            );
        }

        if (pressureWeakened) {
            return underPressure(
                    symbol,
                    advice,
                    currentPressure,
                    "Pressure weakened since entry"
            );
        }

        if (pressureStrengthened) {
            return confirmed(
                    symbol,
                    advice,
                    currentPressure,
                    "Pressure strengthening in expected direction"
            );
        }

        if (aging) {
            return aging(
                    symbol,
                    advice,
                    currentPressure,
                    "Trade aging without momentum"
            );
        }

        return neutral(symbol, advice, currentPressure);
    }

    // =====================================================
    // STATUS BUILDERS
    // =====================================================

    private AdviceStatusDTO confirmed(
            String symbol,
            TradingAdvice advice,
            PressureInsightDTO current,
            String reason) {

        AdviceStatusDTO dto = base(symbol, advice, current);
        dto.setState(AdviceState.ACTIVE_CONFIRMED);
        dto.setSummary("Trade behaving as expected");
        dto.setNextAction("HOLD");
        dto.getDetails().add(reason);
        return dto;
    }

    private AdviceStatusDTO underPressure(
            String symbol,
            TradingAdvice advice,
            PressureInsightDTO current,
            String reason) {

        AdviceStatusDTO dto = base(symbol, advice, current);
        dto.setState(AdviceState.ACTIVE_UNDER_PRESSURE);
        dto.setSummary("Trade under pressure");
        dto.setNextAction("MONITOR");
        dto.getDetails().add(reason);
        return dto;
    }

    private AdviceStatusDTO aging(
            String symbol,
            TradingAdvice advice,
            PressureInsightDTO current,
            String reason) {

        AdviceStatusDTO dto = base(symbol, advice, current);
        dto.setState(AdviceState.ACTIVE_AGING);
        dto.setSummary("Trade aging without follow-through");
        dto.setNextAction("BE CAUTIOUS / REDUCE RISK");
        dto.getDetails().add(reason);
        return dto;
    }

    private AdviceStatusDTO neutral(
            String symbol,
            TradingAdvice advice,
            PressureInsightDTO current) {

        AdviceStatusDTO dto = base(symbol, advice, current);
        dto.setState(AdviceState.ACTIVE_UNDER_PRESSURE);
        dto.setSummary("Trade active, awaiting confirmation");
        dto.setNextAction("WAIT");
        return dto;
    }

    private AdviceStatusDTO exited(
            String symbol,
            TradingAdvice advice,
            PressureInsightDTO current,
            String reason) {

        AdviceStatusDTO dto = base(symbol, advice, current);
        dto.setState(AdviceState.EXITED);
        dto.setSummary("Trade invalidated");
        dto.setNextAction("EXIT / WAIT FOR NEXT SIGNAL");
        dto.getDetails().add("Exit reason: " + reason);
        return dto;
    }

    private AdviceStatusDTO noSignal(String symbol) {

        AdviceStatusDTO dto = new AdviceStatusDTO();
        dto.setSymbol(symbol);
        dto.setState(AdviceState.NO_SIGNAL);
        dto.setSummary("No active trade");
        dto.setNextAction("WAIT");
        dto.setDetails(List.of("No advisory found"));
        return dto;
    }

    private AdviceStatusDTO marketUnavailable(
            String symbol,
            TradingAdvice advice) {

        AdviceStatusDTO dto = new AdviceStatusDTO();
        dto.setSymbol(symbol);
        dto.setState(AdviceState.ACTIVE_UNDER_PRESSURE);
        dto.setSummary("Market data unavailable");
        dto.setNextAction("WAIT");
        dto.setDetails(List.of("Latest snapshot not available"));
        return dto;
    }

    // =====================================================
    // COMMON BUILDER
    // =====================================================
    private AdviceStatusDTO base(
            String symbol,
            TradingAdvice advice,
            PressureInsightDTO current) {

        AdviceStatusDTO dto = new AdviceStatusDTO();
        dto.setSymbol(symbol);

        List<String> details = new ArrayList<>();
        details.add("Last advice at: " + advice.getAdviceTime());
        details.add("Direction: " + advice.getDirection());
        details.add("Entry pressure: "
                + advice.getEntryPressure()
                + " (" + advice.getEntryZone() + ")");
        details.add("Current pressure: "
                + current.getPressure()
                + " (" + current.getZone() + ")");

        dto.setDetails(details);
        return dto;
    }

    // =====================================================
    // HELPERS
    // =====================================================
    private boolean isDirectionInvalidated(
            TradingAdvice advice,
            BigDecimal currentSpot) {

        if (advice.getEntrySpot() == null || currentSpot == null) {
            return false;
        }

        // Instrument-aware buffer (tune later if needed)
        BigDecimal buffer =
                advice.getSymbol().equalsIgnoreCase("CRUDEOIL")
                    ? new BigDecimal("15")   // crude needs breathing room
                    : new BigDecimal("20");  // nifty default

        if (advice.getDirection() == MarketDirection.SELL) {
            // SELL is invalid only if price moves UP beyond buffer
            return currentSpot.compareTo(
                    advice.getEntrySpot().add(buffer)
            ) > 0;
        }

        if (advice.getDirection() == MarketDirection.BUY) {
            // BUY is invalid only if price moves DOWN beyond buffer
            return currentSpot.compareTo(
                    advice.getEntrySpot().subtract(buffer)
            ) < 0;
        }

        return false;
    }

}
