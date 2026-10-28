package com.crumbs.trade.service;

import com.crumbs.trade.dto.DominanceSummaryDto;
import com.crumbs.trade.dto.ScannedContractDto;
import com.crumbs.trade.entity.OptionPrice;
import com.crumbs.trade.entity.StrategyConfig;
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
    private final StrategyConfigService configService;

    @Transactional
    public void saveExtremeContracts(List<ScannedContractDto> contracts) {
        if (contracts == null || contracts.isEmpty()) return;

        LocalDate today = LocalDate.now();
        List<OptionPrice> newRecords = new ArrayList<>();

        for (ScannedContractDto dto : contracts) {
            boolean isRsiTrigger = dto.isRSIAbove80() || dto.isRSIBelow20()
                    || dto.getRsiAction() == ScannedContractDto.RsiAction.TRIGGER_OVERBOUGHT_HOOK
                    || dto.getRsiAction() == ScannedContractDto.RsiAction.TRIGGER_OVERSOLD_HOOK;

            boolean isMaTrigger = isNearMaTrigger(dto);

            if (!isRsiTrigger && !isMaTrigger) {
                continue;
            }

            if (isNearMaBreakout(dto)) {
                dto.setMaAction(ScannedContractDto.MaAction.NEAR_BREAKOUT);
            } else if (isNearMaBreakdown(dto)) {
                dto.setMaAction(ScannedContractDto.MaAction.NEAR_BREAKDOWN);
            } else {
                dto.setMaAction(ScannedContractDto.MaAction.NONE);
            }

            OptionPrice newRecord = mapToEntity(dto);
            newRecord.setEvaluatedDate(today);

            newRecords.add(newRecord);
        }

        if (!newRecords.isEmpty()) {
            optionPriceRepo.saveAll(newRecords);
            logger.info("💾 Saved {} alert-triggering records to option_prices.", newRecords.size());
        }

        sendHookNotifications(contracts);
    }

    private void sendHookNotifications(List<ScannedContractDto> contracts) {
        StrategyConfig activeConfig = configService.getActiveConfig();
        boolean isRsiEnabled = "Y".equalsIgnoreCase(activeConfig.getRsiAlert());
        boolean isMaEnabled = "Y".equalsIgnoreCase(activeConfig.getMaAlert());

        Map<String, List<ScannedContractDto>> hooksByIndex = contracts.stream()
                .filter(c -> c.getRsiAction() == ScannedContractDto.RsiAction.TRIGGER_OVERBOUGHT_HOOK
                        || c.getRsiAction() == ScannedContractDto.RsiAction.TRIGGER_OVERSOLD_HOOK
                        || isNearMaTrigger(c))
                .collect(Collectors.groupingBy(ScannedContractDto::getName));

        if (hooksByIndex.isEmpty()) return;

        hooksByIndex.forEach((symbol, hooks) -> {

            String tf = hooks.get(0).getTimeFrame();
            BigDecimal spot = hooks.get(0).getSpotPrice();
            String headerInfo = "🚨 *" + symbol + " (" + tf + ")*";
            if (spot != null) {
                headerInfo += " | Spot: `" + String.format("%.2f", spot.doubleValue()) + "`\n";
            } else {
                headerInfo += "\n";
            }

            // 1. Separate Alert: RSI Overbought (SELL)
            if (isRsiEnabled) {
                List<ScannedContractDto> overboughtHooks = hooks.stream()
                        .filter(c -> c.getRsiAction() == ScannedContractDto.RsiAction.TRIGGER_OVERBOUGHT_HOOK)
                        .toList();

                if (!overboughtHooks.isEmpty()) {
                    StringBuilder msg = new StringBuilder(headerInfo);
                    msg.append("📉 *RSI Overbought Hook (SELL)*\n```\n");
                    msg.append(String.format("%-5s | %-16s | %-7s | %-4s | %s%n", "TIME", "STRIKE", "LTP", "RSI", "CNT"));
                    msg.append("--------------------------------------------------\n");
                    for (ScannedContractDto hook : overboughtHooks) {
                        msg.append(formatRsiRow(hook));
                    }
                    msg.append("```");
                    sendAlertSafely(msg.toString().trim());
                }

                // 2. Separate Alert: RSI Oversold (BUY)
                List<ScannedContractDto> oversoldHooks = hooks.stream()
                        .filter(c -> c.getRsiAction() == ScannedContractDto.RsiAction.TRIGGER_OVERSOLD_HOOK)
                        .toList();

                if (!oversoldHooks.isEmpty()) {
                    StringBuilder msg = new StringBuilder(headerInfo);
                    msg.append("📈 *RSI Oversold Hook (BUY)*\n```\n");
                    msg.append(String.format("%-5s | %-16s | %-7s | %-4s | %s%n", "TIME", "STRIKE", "LTP", "RSI", "CNT"));
                    msg.append("--------------------------------------------------\n");
                    for (ScannedContractDto hook : oversoldHooks) {
                        msg.append(formatRsiRow(hook));
                    }
                    msg.append("```");
                    sendAlertSafely(msg.toString().trim());
                }
            }

            // 3. Separate Alert: MA Breakout
            if (isMaEnabled) {
                List<ScannedContractDto> maBreakouts = hooks.stream().filter(this::isNearMaBreakout).toList();
                if (!maBreakouts.isEmpty()) {
                    StringBuilder msg = new StringBuilder(headerInfo);
                    msg.append("🚀 *MA Breakout (PRICE > MA)*\n```\n");
                    msg.append(String.format("%-5s | %-16s | %-7s | %s%n", "TIME", "STRIKE", "LTP", "MA 20"));
                    msg.append("----------------------------------------------\n");
                    for (ScannedContractDto hook : maBreakouts) {
                        msg.append(formatMaRow(hook));
                    }
                    msg.append("```");
                    sendAlertSafely(msg.toString().trim());
                }

                // 4. Separate Alert: MA Breakdown
                List<ScannedContractDto> maBreakdowns = hooks.stream().filter(this::isNearMaBreakdown).toList();
                if (!maBreakdowns.isEmpty()) {
                    StringBuilder msg = new StringBuilder(headerInfo);
                    msg.append("⚠️ *MA Breakdown (PRICE < MA)*\n```\n");
                    msg.append(String.format("%-5s | %-16s | %-7s | %s%n", "TIME", "STRIKE", "LTP", "MA 20"));
                    msg.append("----------------------------------------------\n");
                    for (ScannedContractDto hook : maBreakdowns) {
                        msg.append(formatMaRow(hook));
                    }
                    msg.append("```");
                    sendAlertSafely(msg.toString().trim());
                }
            }
        });
    }

    private void sendAlertSafely(String message) {
        try {
            telegramService.sendToNewChat(message);
        } catch (Exception e) {
            logger.error("Failed to send Telegram alert: {}", e.getMessage());
        }
    }

    private String formatRsiRow(ScannedContractDto hook) {
        String timeStr = hook.getLastEvaluatedAt() != null ? hook.getLastEvaluatedAt().format(TIME_ONLY_FMT) : "--:--";
        String expiry = hook.getRawExpiry() != null ? hook.getRawExpiry().substring(0, 5) : ""; // E.g., "31OCT"
        String strikeStr = expiry + " " + (int) hook.getStrike() + hook.getOptionType();
        double ltpVal = hook.getCurrentLtp() != null ? hook.getCurrentLtp().doubleValue() : 0.0;
        double rsiVal = hook.getCurrentRsi() != null ? hook.getCurrentRsi() : 0.0;
        int count = hook.getRsiAction() == ScannedContractDto.RsiAction.TRIGGER_OVERBOUGHT_HOOK ? hook.getAboveRSI80Count() : hook.getBelowRSI20Count();

        return String.format("%-5s | %-16s | %-7.2f | %-4.1f | %-3d%n", timeStr, strikeStr, ltpVal, rsiVal, count);
    }

    private String formatMaRow(ScannedContractDto hook) {
        String timeStr = hook.getLastEvaluatedAt() != null ? hook.getLastEvaluatedAt().format(TIME_ONLY_FMT) : "--:--";
        String expiry = hook.getRawExpiry() != null ? hook.getRawExpiry().substring(0, 5) : ""; // E.g., "31OCT"
        String strikeStr = expiry + " " + (int) hook.getStrike() + hook.getOptionType();
        double ltpVal = hook.getCurrentLtp() != null ? hook.getCurrentLtp().doubleValue() : 0.0;
        double maVal = hook.getCurrentMa() != null ? hook.getCurrentMa() : 0.0;

        return String.format("%-5s | %-16s | %-7.2f | %-7.2f%n", timeStr, strikeStr, ltpVal, maVal);
    }

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
        if (timeFrame == null || timeFrame.equalsIgnoreCase("ALL")) {
            latestData = optionPriceRepo.findLatestLiveTrackedDataAllTimeFrames();
        } else {
            latestData = optionPriceRepo.findLatestLiveTrackedDataByTimeFrame(timeFrame.toUpperCase());
        }

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
            return false;
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
                .rsiAction(dto.getRsiAction() != null ? dto.getRsiAction().name() : "NONE")
                .maAction(dto.getMaAction() != null ? dto.getMaAction().name() : "NONE")
                .evaluatedAt(dto.getLastEvaluatedAt())
                .build();
    }

    public DominanceSummaryDto getDominanceSummary(String symbol) {
        List<OptionPrice> liveData = getLiveTrackedData("ALL");

        List<OptionPrice> symbolData = liveData.stream()
                .filter(r -> r.getName() != null && r.getName().equalsIgnoreCase(symbol))
                .toList();

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