package com.crumbs.trade.service;

import com.crumbs.trade.dto.ScannedContractDto;
import com.crumbs.trade.entity.OptionPrice;
import com.crumbs.trade.repo.OptionPriceRepo;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OptionPriceService {

    private static final Logger logger = LogManager.getLogger(OptionPriceService.class);
    private static final DateTimeFormatter TIME_ONLY_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final OptionPriceRepo optionPriceRepo;
    private final TelegramService telegramService;

    @Transactional
    public void saveExtremeContracts(List<ScannedContractDto> contracts) {
        if (contracts == null || contracts.isEmpty()) return;

        LocalDate today = LocalDate.now();
        List<OptionPrice> newRecords = new ArrayList<>();

        for (ScannedContractDto dto : contracts) {
            if (!dto.isRSIAbove80() && !dto.isRSIBelow20()
                    && dto.getSignalAction() != ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK
                    && dto.getSignalAction() != ScannedContractDto.SignalAction.TRIGGER_OVERSOLD_HOOK) {
                continue;
            }

            OptionPrice newRecord = mapToEntity(dto);
            newRecord.setEvaluatedDate(today);
            newRecords.add(newRecord);
        }

        if (!newRecords.isEmpty()) {
            optionPriceRepo.saveAll(newRecords);
            logger.info("💾 Appended {} records into option_prices timeseries.", newRecords.size());
        }

        sendHookNotifications(contracts);
    }

    private void sendHookNotifications(List<ScannedContractDto> contracts) {
        Map<String, List<ScannedContractDto>> hooksByIndex = contracts.stream()
                .filter(c -> c.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK
                        || c.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERSOLD_HOOK)
                .collect(Collectors.groupingBy(ScannedContractDto::getName));

        if (hooksByIndex.isEmpty()) return;

        hooksByIndex.forEach((symbol, hooks) -> {
            logger.info("🔔 Found {} hooks for {}. Sending Telegram alert...", hooks.size(), symbol);
            String tf = hooks.get(0).getTimeFrame();
            BigDecimal spot = hooks.get(0).getSpotPrice();

            StringBuilder msg = new StringBuilder();
            msg.append("🚨 *OPTIONS RSI HOOK (").append(tf).append(") — ").append(symbol).append("*\n");
            if (spot != null) {
                msg.append("📍 Spot: `").append(String.format("%.2f", spot.doubleValue())).append("`\n\n");
            } else {
                msg.append("\n");
            }

            msg.append("```\n");
            // Mobile-optimized table header: 46 characters wide
            msg.append(String.format("%-5s | %-4s | %-8s | %-7s | %-4s | %s%n",
                    "TIME", "DIR", "STRIKE", "LTP", "RSI", "CNT"));
            msg.append("----------------------------------------------\n");

            for (ScannedContractDto hook : hooks) {
                boolean isOverbought = hook.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK;
                String direction = isOverbought ? "SELL" : "BUY ";

                // Intraday Time (HH:mm)
                String timeStr = hook.getLastEvaluatedAt() != null
                        ? hook.getLastEvaluatedAt().format(TIME_ONLY_FMT)
                        : "--:--";

                // Compact strike: "8250CE"
                String strikeStr = (int) hook.getStrike() + hook.getOptionType();

                // Option contract LTP
                double ltpVal = hook.getCurrentLtp() != null ? hook.getCurrentLtp().doubleValue() : 0.0;
                double rsiVal = hook.getCurrentRsi() != null ? hook.getCurrentRsi() : 0.0;

                // Most recent sustained count before hook
                int count = isOverbought ? hook.getAboveRSI80Count() : hook.getBelowRSI20Count();

                msg.append(String.format("%-5s | %-4s | %-8s | %-7.2f | %-4.1f | %-3d%n",
                        timeStr, direction, strikeStr, ltpVal, rsiVal, count));
            }

            msg.append("```");

            try {
                telegramService.sendToNewChat(msg.toString());
            } catch (Exception e) {
                logger.error("Failed to send Telegram alert for hooks: {}", e.getMessage());
            }
        });
    }

    private OptionPrice mapToEntity(ScannedContractDto dto) {
        return OptionPrice.builder()
                .name(dto.getName())
                .symbol(dto.getSymbol())
                .token(dto.getToken())
                .exchange(dto.getExchange())
                .strike(dto.getStrike())
                .timeFrame(dto.getTimeFrame())
                .optionType(dto.getOptionType())
                .expiryDate(dto.getExpiryDate())
                .moneyness(dto.getMoneyness() != null ? dto.getMoneyness().name() : "UNKNOWN")
                .spotPrice(dto.getSpotPrice())
                .ltp(dto.getCurrentLtp()) // 👈 Persist actual contract LTP to DB
                .currentRsi(dto.getCurrentRsi())
                .previousRsi(dto.getPreviousRsi())
                .isRsiAbove80(dto.isRSIAbove80())
                .isRsiBelow20(dto.isRSIBelow20())
                .aboveRSI80Count(dto.getAboveRSI80Count())
                .belowRSI20Count(dto.getBelowRSI20Count())
                .aboveRSI80At(dto.getAboveRSI80At())
                .belowRSI20At(dto.getBelowRSI20At())
                .extremePeakRsi(dto.getExtremePeakRsi())
                .extremeTroughRsi(dto.getExtremeTroughRsi())
                .signalAction(dto.getSignalAction() != null ? dto.getSignalAction().name() : "NONE")
                .evaluatedAt(dto.getLastEvaluatedAt())
                .build();
    }

    // ==========================================
    // UI DATA RETRIEVAL (DASHBOARD & AUDIT)
    // ==========================================

    /**
     * Gets the current live status for the main UI dashboard.
     */
    public List<OptionPrice> getLiveTrackedData(String timeFrame) {
        if (timeFrame == null || timeFrame.equalsIgnoreCase("ALL")) {
            return optionPriceRepo.findLatestLiveTrackedDataAllTimeFrames();
        }
        return optionPriceRepo.findLatestLiveTrackedDataByTimeFrame(timeFrame.toUpperCase());
    }

    /**
     * Gets the chronological lifecycle history for a single symbol.
     */
    public List<OptionPrice> getSymbolLifecycleHistory(String symbol, String timeFrame) {
        if (timeFrame == null || timeFrame.equalsIgnoreCase("ALL")) {
            // Ordered ASCENDING so the UI sees the start of the cycle first, ending with the hook
            return optionPriceRepo.findAllBySymbolOrderByEvaluatedAtAsc(symbol);
        }
        return optionPriceRepo.findAllBySymbolAndTimeFrameOrderByEvaluatedAtAsc(symbol, timeFrame.toUpperCase());
    }
}