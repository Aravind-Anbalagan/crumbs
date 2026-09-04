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

    // Maximum points allowed above the MA to trigger an alert
    private static final double MA_PROXIMITY_THRESHOLD = 50.0;

    private final OptionPriceRepo optionPriceRepo;
    private final TelegramService telegramService;

    @Transactional
    public void saveExtremeContracts(List<ScannedContractDto> contracts) {
        if (contracts == null || contracts.isEmpty()) return;

        LocalDate today = LocalDate.now();
        List<OptionPrice> newRecords = new ArrayList<>();

        for (ScannedContractDto dto : contracts) {
            // 1. DB FILTER: Save if RSI Extreme OR if it's a fresh MA breakout
            if (!dto.isRSIAbove80() && !dto.isRSIBelow20()
                    && dto.getSignalAction() != ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK
                    && dto.getSignalAction() != ScannedContractDto.SignalAction.TRIGGER_OVERSOLD_HOOK
                    && !isNearMaBreakout(dto)) {
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
        // 2. ALERT FILTER: Alert on RSI Hooks OR fresh MA breakouts
        Map<String, List<ScannedContractDto>> hooksByIndex = contracts.stream()
                .filter(c -> c.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK
                        || c.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERSOLD_HOOK
                        || isNearMaBreakout(c))
                .collect(Collectors.groupingBy(ScannedContractDto::getName));

        if (hooksByIndex.isEmpty()) return;

        hooksByIndex.forEach((symbol, hooks) -> {
            logger.info("🔔 Found {} triggers for {}. Sending Telegram alert...", hooks.size(), symbol);
            String tf = hooks.get(0).getTimeFrame();
            BigDecimal spot = hooks.get(0).getSpotPrice();

            StringBuilder msg = new StringBuilder();
            msg.append("🚨 *OPTIONS SCANNER (").append(tf).append(") — ").append(symbol).append("*\n");

            if (spot != null) {
                msg.append("📍 Spot: `").append(String.format("%.2f", spot.doubleValue())).append("`\n");
            }

            // Separate RSI triggers and MA triggers
            List<ScannedContractDto> rsiTriggers = hooks.stream()
                    .filter(c -> c.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK
                            || c.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERSOLD_HOOK)
                    .collect(Collectors.toList());

            List<ScannedContractDto> maTriggers = hooks.stream()
                    .filter(this::isNearMaBreakout)
                    .collect(Collectors.toList());

            // ==========================================
            // TABLE 1: RSI HOOKS
            // ==========================================
            if (!rsiTriggers.isEmpty()) {
                msg.append("\n⚡ *RSI Hooks*\n```\n");
                msg.append(String.format("%-5s | %-4s | %-8s | %-7s | %-4s | %s%n",
                        "TIME", "DIR", "STRIKE", "LTP", "RSI", "CNT"));
                msg.append("----------------------------------------------\n");

                for (ScannedContractDto hook : rsiTriggers) {
                    String direction = hook.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK ? "SELL" : "BUY ";
                    String timeStr = hook.getLastEvaluatedAt() != null ? hook.getLastEvaluatedAt().format(TIME_ONLY_FMT) : "--:--";
                    String strikeStr = (int) hook.getStrike() + hook.getOptionType();
                    double ltpVal = hook.getCurrentLtp() != null ? hook.getCurrentLtp().doubleValue() : 0.0;
                    double rsiVal = hook.getCurrentRsi() != null ? hook.getCurrentRsi() : 0.0;
                    int count = direction.equals("SELL") ? hook.getAboveRSI80Count() : hook.getBelowRSI20Count();

                    msg.append(String.format("%-5s | %-4s | %-8s | %-7.2f | %-4.1f | %-3d%n",
                            timeStr, direction, strikeStr, ltpVal, rsiVal, count));
                }
                msg.append("```\n");
            }

            // ==========================================
            // TABLE 2: MA BREAKOUTS
            // ==========================================
            if (!maTriggers.isEmpty()) {
                msg.append("\n📈 *MA Breakouts*\n```\n");
                msg.append(String.format("%-5s | %-4s | %-8s | %-7s | %s%n",
                        "TIME", "DIR", "STRIKE", "LTP", "MA 20"));
                msg.append("------------------------------------------\n");

                for (ScannedContractDto hook : maTriggers) {
                    String timeStr = hook.getLastEvaluatedAt() != null ? hook.getLastEvaluatedAt().format(TIME_ONLY_FMT) : "--:--";
                    String strikeStr = (int) hook.getStrike() + hook.getOptionType();
                    double ltpVal = hook.getCurrentLtp() != null ? hook.getCurrentLtp().doubleValue() : 0.0;
                    double maVal = hook.getCurrentMa() != null ? hook.getCurrentMa() : 0.0;

                    // DIR is hardcoded to "MA↑ " as requested
                    msg.append(String.format("%-5s | %-4s | %-8s | %-7.2f | %-7.2f%n",
                            timeStr, "MA↑ ", strikeStr, ltpVal, maVal));
                }
                msg.append("```");
            }

            try {
                telegramService.sendToNewChat(msg.toString().trim());
            } catch (Exception e) {
                logger.error("Failed to send Telegram alert for hooks: {}", e.getMessage());
            }
        });
    }

    // ==========================================
    // UI DATA RETRIEVAL (DASHBOARD & AUDIT)
    // ==========================================

    public List<OptionPrice> getLiveTrackedData(String timeFrame) {
        if (timeFrame == null || timeFrame.equalsIgnoreCase("ALL")) {
            return optionPriceRepo.findLatestLiveTrackedDataAllTimeFrames();
        }
        return optionPriceRepo.findLatestLiveTrackedDataByTimeFrame(timeFrame.toUpperCase());
    }

    public List<OptionPrice> getSymbolLifecycleHistory(String symbol, String timeFrame) {
        if (timeFrame == null || timeFrame.equalsIgnoreCase("ALL")) {
            return optionPriceRepo.findAllBySymbolOrderByEvaluatedAtAsc(symbol);
        }
        return optionPriceRepo.findAllBySymbolAndTimeFrameOrderByEvaluatedAtAsc(symbol, timeFrame.toUpperCase());
    }

    // ==========================================
    // SEPARATED UI ENDPOINT HELPERS
    // ==========================================

    public List<OptionPrice> getLiveRsiSignals(String timeFrame) {
        List<OptionPrice> allLive = getLiveTrackedData(timeFrame);
        return allLive.stream()
                .filter(row -> row.getSignalAction() != null && !row.getSignalAction().equals("NONE"))
                .collect(Collectors.toList());
    }

    public List<OptionPrice> getLiveMaBreakouts(String timeFrame) {
        List<OptionPrice> allLive = getLiveTrackedData(timeFrame);
        return allLive.stream()
                .filter(this::isNearMaBreakoutEntity)
                .collect(Collectors.toList());
    }

    private boolean isNearMaBreakoutEntity(OptionPrice entity) {
        if (!entity.isPriceAboveMa() || entity.getLtp() == null || entity.getCurrentMa() == null) {
            return false;
        }
        double difference = entity.getLtp().doubleValue() - entity.getCurrentMa();
        return difference >= 0 && difference <= MA_PROXIMITY_THRESHOLD;
    }

    // ==========================================
    // LOGIC HELPERS
    // ==========================================

    private boolean isNearMaBreakout(ScannedContractDto dto) {
        if (!dto.isPriceAboveMa() || dto.getCurrentLtp() == null || dto.getCurrentMa() == null) {
            return false;
        }
        double difference = dto.getCurrentLtp().doubleValue() - dto.getCurrentMa();
        return difference >= 0 && difference <= MA_PROXIMITY_THRESHOLD;
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
                .ltp(dto.getCurrentLtp())
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
                .currentMa(dto.getCurrentMa())
                .isPriceAboveMa(dto.isPriceAboveMa())
                .signalAction(dto.getSignalAction() != null ? dto.getSignalAction().name() : "NONE")
                .evaluatedAt(dto.getLastEvaluatedAt())
                .build();
    }
}