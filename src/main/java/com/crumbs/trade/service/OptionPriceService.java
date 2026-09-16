package com.crumbs.trade.service;

import com.crumbs.trade.dto.DominanceSummaryDto;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OptionPriceService {

    private static final Logger logger = LogManager.getLogger(OptionPriceService.class);
    private static final DateTimeFormatter TIME_ONLY_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter EXPIRY_DISPLAY_FMT = DateTimeFormatter.ofPattern("ddMMM");

    private final OptionPriceRepo optionPriceRepo;
    private final TelegramService telegramService;
    private final StrategyConfigService configService;

    @Transactional
    public void saveExtremeContracts(List<ScannedContractDto> contracts) {
        if (contracts == null || contracts.isEmpty()) return;

        LocalDate today = LocalDate.now();
        List<OptionPrice> newRecords = new ArrayList<>();

        for (ScannedContractDto dto : contracts) {
            // 1. DB FILTER: Save if RSI Extreme OR if it's a fresh MA breakout/breakdown
            if (!dto.isRSIAbove80() && !dto.isRSIBelow20()
                    && dto.getSignalAction() != ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK
                    && dto.getSignalAction() != ScannedContractDto.SignalAction.TRIGGER_OVERSOLD_HOOK
                    && !isNearMaTrigger(dto)) {
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
        // 2. ALERT FILTER: Alert on RSI Hooks OR fresh MA breakouts/breakdowns
        Map<String, List<ScannedContractDto>> hooksByIndex = contracts.stream()
                .filter(c -> c.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK
                        || c.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERSOLD_HOOK
                        || isNearMaTrigger(c))
                .collect(Collectors.groupingBy(ScannedContractDto::getName));

        if (hooksByIndex.isEmpty()) return;

        Comparator<ScannedContractDto> expiryAndStrikeComparator = Comparator
                .comparing(ScannedContractDto::getExpiryDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingDouble(ScannedContractDto::getStrike);

        hooksByIndex.forEach((symbol, hooks) -> {
            logger.info("🔔 Found {} triggers for {}. Sending Telegram alert...", hooks.size(), symbol);
            String tf = hooks.get(0).getTimeFrame();
            BigDecimal spot = hooks.get(0).getSpotPrice();

            StringBuilder msg = new StringBuilder();
            msg.append("🚨 *OPTIONS SCANNER (").append(tf).append(") — ").append(symbol).append("*\n");

            if (spot != null) {
                msg.append("📍 Spot: `").append(String.format("%.2f", spot.doubleValue())).append("`\n");
            }

            List<ScannedContractDto> rsiOversold = hooks.stream()
                    .filter(c -> c.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERSOLD_HOOK)
                    .sorted(expiryAndStrikeComparator)
                    .collect(Collectors.toList());

            List<ScannedContractDto> rsiOverbought = hooks.stream()
                    .filter(c -> c.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK)
                    .sorted(expiryAndStrikeComparator)
                    .collect(Collectors.toList());

            List<ScannedContractDto> maBreakouts = hooks.stream()
                    .filter(this::isNearMaBreakout)
                    .sorted(expiryAndStrikeComparator)
                    .collect(Collectors.toList());

            List<ScannedContractDto> maBreakdowns = hooks.stream()
                    .filter(this::isNearMaBreakdown)
                    .sorted(expiryAndStrikeComparator)
                    .collect(Collectors.toList());

            if (!rsiOversold.isEmpty()) {
                msg.append("\n⚡ *RSI Signals - OVERSOLD*\n```\n");
                msg.append("EXP  |TIME |STRIKE |LTP    |RSI |CNT\n");
                msg.append("------------------------------------\n");
                for (ScannedContractDto hook : rsiOversold) {
                    msg.append(formatTableRow(hook, true));
                }
                msg.append("```\n");
            }

            if (!rsiOverbought.isEmpty()) {
                msg.append("\n⚡ *RSI Signals - OVERBOUGHT*\n```\n");
                msg.append("EXP  |TIME |STRIKE |LTP    |RSI |CNT\n");
                msg.append("------------------------------------\n");
                for (ScannedContractDto hook : rsiOverbought) {
                    msg.append(formatTableRow(hook, true));
                }
                msg.append("```\n");
            }

            if (!maBreakouts.isEmpty()) {
                msg.append("\n📈 *MA Signals - BREAKOUT*\n```\n");
                msg.append("EXP  |TIME |STRIKE |LTP    |MA     |RSI\n");
                msg.append("---------------------------------------\n");
                for (ScannedContractDto hook : maBreakouts) {
                    msg.append(formatTableRow(hook, false));
                }
                msg.append("```\n");
            }

            if (!maBreakdowns.isEmpty()) {
                msg.append("\n📉 *MA Signals - BREAKDOWN*\n```\n");
                msg.append("EXP  |TIME |STRIKE |LTP    |MA     |RSI\n");
                msg.append("---------------------------------------\n");
                for (ScannedContractDto hook : maBreakdowns) {
                    msg.append(formatTableRow(hook, false));
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

    private String formatTableRow(ScannedContractDto hook, boolean isRsiTable) {
        String expStr = formatExpiry(hook);
        String timeStr = hook.getLastEvaluatedAt() != null ? hook.getLastEvaluatedAt().format(TIME_ONLY_FMT) : "--:--";
        String strikeStr = (int) hook.getStrike() + hook.getOptionType();
        double ltpVal = hook.getCurrentLtp() != null ? hook.getCurrentLtp().doubleValue() : 0.0;
        double rsiVal = hook.getCurrentRsi() != null ? hook.getCurrentRsi() : 0.0;

        if (isRsiTable) {
            int count = hook.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK
                    ? hook.getAboveRSI80Count() : hook.getBelowRSI20Count();
            return String.format("%-5s|%-5s|%-7s|%-7.2f|%-4.1f|%-3d%n", expStr, timeStr, strikeStr, ltpVal, rsiVal, count);
        } else {
            double maVal = hook.getCurrentMa() != null ? hook.getCurrentMa() : 0.0;
            return String.format("%-5s|%-5s|%-7s|%-7.2f|%-7.2f|%-4.1f%n", expStr, timeStr, strikeStr, ltpVal, maVal, rsiVal);
        }
    }

    private String formatExpiry(ScannedContractDto dto) {
        if (dto.getExpiryDate() != null) {
            String exp = dto.getExpiryDate().format(EXPIRY_DISPLAY_FMT).toUpperCase();
            return exp.length() > 5 ? exp.substring(0, 5) : String.format("%-5s", exp);
        }
        return "-----";
    }

    // ==========================================
    // MA TRIGGER DETECTION (SAVE LOGIC ONLY)
    // ==========================================

    private boolean isNearMaTrigger(ScannedContractDto dto) {
        return isNearMaBreakout(dto) || isNearMaBreakdown(dto);
    }

    private boolean isNearMaBreakout(ScannedContractDto dto) {
        if (!dto.isPriceAboveMa() || dto.getCurrentLtp() == null || dto.getCurrentMa() == null) {
            return false;
        }
        double threshold = configService.getActiveConfig().getMaProximity();
        double diff = dto.getCurrentLtp().doubleValue() - dto.getCurrentMa();
        return diff >= 0 && diff <= threshold;
    }

    private boolean isNearMaBreakdown(ScannedContractDto dto) {
        if (dto.isPriceAboveMa() || dto.getCurrentLtp() == null || dto.getCurrentMa() == null) {
            return false;
        }
        double threshold = configService.getActiveConfig().getMaProximity();
        double diff = dto.getCurrentMa() - dto.getCurrentLtp().doubleValue();
        return diff >= 0 && diff <= threshold;
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

    public List<OptionPrice> getLiveRsiSignals(String timeFrame) {
        if (timeFrame == null || timeFrame.equalsIgnoreCase("ALL")) {
            return optionPriceRepo.findLatestRsiSignalsAllTimeFrames();
        }
        return optionPriceRepo.findLatestRsiSignalsByTimeFrame(timeFrame.toUpperCase());
    }

    public List<OptionPrice> getLiveMaBreakouts(String timeFrame) {
        List<OptionPrice> latestData;

        // 1. Fetch the absolute latest data for all symbols (bypassing the broken MA SQL query)
        if (timeFrame == null || timeFrame.equalsIgnoreCase("ALL")) {
            latestData = optionPriceRepo.findLatestLiveTrackedDataAllTimeFrames();
        } else {
            latestData = optionPriceRepo.findLatestLiveTrackedDataByTimeFrame(timeFrame.toUpperCase());
        }

        // 2. Apply the exact same logic used by the Telegram alerts to catch BOTH breakouts and breakdowns
        return latestData.stream()
                .filter(this::isNearMaTriggerEntity)
                .collect(Collectors.toList());
    }

    private boolean isNearMaTriggerEntity(OptionPrice entity) {
        return isNearMaBreakoutEntity(entity) || isNearMaBreakdownEntity(entity);
    }

    private boolean isNearMaBreakoutEntity(OptionPrice entity) {
        if (!entity.isPriceAboveMa() || entity.getLtp() == null || entity.getCurrentMa() == null) {
            return false;
        }
        double threshold = configService.getActiveConfig().getMaProximity();
        double diff = entity.getLtp().doubleValue() - entity.getCurrentMa();
        return diff >= 0 && diff <= threshold;
    }

    private boolean isNearMaBreakdownEntity(OptionPrice entity) {
        if (entity.isPriceAboveMa() || entity.getLtp() == null || entity.getCurrentMa() == null) {
            return false; // MUST be false for a breakdown
        }
        double threshold = configService.getActiveConfig().getMaProximity();
        double diff = entity.getCurrentMa() - entity.getLtp().doubleValue();
        return diff >= 0 && diff <= threshold;
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

    public DominanceSummaryDto getDominanceSummary(String symbol) {
        List<OptionPrice> liveData = getLiveTrackedData("ALL");

        List<OptionPrice> symbolData = liveData.stream()
                .filter(r -> r.getName() != null && r.getName().equalsIgnoreCase(symbol))
                .toList();

        // TRUST THE DB: If the record exists in the live view, it passed the save filters (either RSI or MA)
        long ceCount = symbolData.stream()
                .filter(r -> "CE".equalsIgnoreCase(r.getOptionType()))
                .map(OptionPrice::getStrike)
                .distinct()
                .count();

        long peCount = symbolData.stream()
                .filter(r -> "PE".equalsIgnoreCase(r.getOptionType()))
                .map(OptionPrice::getStrike)
                .distinct()
                .count();

        int total = (int) (ceCount + peCount);
        double cePercent = total > 0 ? Math.round(((double) ceCount / total) * 100.0) : 50.0;
        double pePercent = total > 0 ? Math.round(((double) peCount / total) * 100.0) : 50.0;

        String dominance = (ceCount > peCount) ? "CE_STRONG" : (peCount > ceCount) ? "PE_STRONG" : "NEUTRAL";

        return DominanceSummaryDto.builder()
                .symbol(symbol)
                .ceCount((int) ceCount)
                .peCount((int) peCount)
                .totalCount(total)
                .cePercentage(cePercent)
                .pePercentage(pePercent)
                .dominance(dominance)
                .evaluatedAt(LocalDateTime.now())
                .build();
    }

    public List<String> getUniqueTimeFrames() {
         return optionPriceRepo.findDistinctTimeFrames();
    }
}