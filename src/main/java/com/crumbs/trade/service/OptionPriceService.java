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
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OptionPriceService {

    private static final Logger logger = LogManager.getLogger(OptionPriceService.class);
    private static final DateTimeFormatter TIME_ONLY_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter EXP_DISPLAY_FMT = DateTimeFormatter.ofPattern("ddMMM", Locale.ENGLISH);

    // Comparator: Expiry Date -> Strike -> Option Type (CE then PE)
    private static final Comparator<ScannedContractDto> CONTRACT_ALERT_COMPARATOR = Comparator
            .comparing(ScannedContractDto::getExpiryDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingDouble(ScannedContractDto::getStrike)
            .thenComparing(ScannedContractDto::getOptionType, Comparator.nullsLast(Comparator.naturalOrder()));

    private final OptionPriceRepo optionPriceRepo;
    private final TelegramService telegramService;
    private final StrategyConfigService configService;

    @Transactional
    public void saveExtremeContracts(List<ScannedContractDto> contracts) {
        if (contracts == null || contracts.isEmpty()) return;

        LocalDate today = LocalDate.now();
        List<OptionPrice> newRecords = new ArrayList<>();

        for (ScannedContractDto dto : contracts) {
            // 1. DB FILTER: Save if RSI Extreme OR if it's an active MA breakout/breakdown
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
        // 2. ALERT FILTER: Alert on RSI Hooks OR MA Breakout/Breakdowns
        Map<String, List<ScannedContractDto>> hooksByIndex = contracts.stream()
                .filter(c -> c.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK
                        || c.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERSOLD_HOOK
                        || isNearMaTrigger(c))
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

            // Separate & sort triggers based on Expiry Date -> Strike -> Option Type
            List<ScannedContractDto> rsiTriggers = hooks.stream()
                    .filter(c -> c.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK
                            || c.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERSOLD_HOOK)
                    .sorted(CONTRACT_ALERT_COMPARATOR)
                    .collect(Collectors.toList());

            List<ScannedContractDto> maTriggers = hooks.stream()
                    .filter(this::isNearMaTrigger)
                    .sorted(CONTRACT_ALERT_COMPARATOR)
                    .collect(Collectors.toList());

            // ==========================================
            // TABLE 1: RSI HOOKS
            // ==========================================
            if (!rsiTriggers.isEmpty()) {
                msg.append("\n⚡ *RSI Hooks*\n```\n");
                msg.append(String.format("%-5s | %-5s | %-4s | %-8s | %-7s | %-4s | %s%n",
                        "TIME", "EXP", "DIR", "STRIKE", "LTP", "RSI", "CNT"));
                msg.append("----------------------------------------------------\n");

                for (ScannedContractDto hook : rsiTriggers) {
                    String direction = hook.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK ? "SELL" : "BUY ";
                    String timeStr = hook.getLastEvaluatedAt() != null ? hook.getLastEvaluatedAt().format(TIME_ONLY_FMT) : "--:--";
                    String expStr = formatExpiry(hook);
                    String strikeStr = (int) hook.getStrike() + hook.getOptionType();
                    double ltpVal = hook.getCurrentLtp() != null ? hook.getCurrentLtp().doubleValue() : 0.0;
                    double rsiVal = hook.getCurrentRsi() != null ? hook.getCurrentRsi() : 0.0;
                    int count = direction.equals("SELL") ? hook.getAboveRSI80Count() : hook.getBelowRSI20Count();

                    msg.append(String.format("%-5s | %-5s | %-4s | %-8s | %-7.2f | %-4.1f | %-3d%n",
                            timeStr, expStr, direction, strikeStr, ltpVal, rsiVal, count));
                }
                msg.append("```\n");
            }

            // ==========================================
            // TABLE 2: MA BREAKOUTS & BREAKDOWNS
            // ==========================================
            if (!maTriggers.isEmpty()) {
                msg.append("\n📈 *MA Signals*\n```\n");
                msg.append(String.format("%-5s | %-5s | %-4s | %-8s | %-7s | %-7s | %s%n",
                        "TIME", "EXP", "DIR", "STRIKE", "LTP", "MA 20", "RSI"));
                msg.append("----------------------------------------------------------\n");

                for (ScannedContractDto hook : maTriggers) {
                    String timeStr = hook.getLastEvaluatedAt() != null ? hook.getLastEvaluatedAt().format(TIME_ONLY_FMT) : "--:--";
                    String expStr = formatExpiry(hook);
                    String direction = isNearMaBreakout(hook) ? "MA↑ " : "MA↓ ";
                    String strikeStr = (int) hook.getStrike() + hook.getOptionType();
                    double ltpVal = hook.getCurrentLtp() != null ? hook.getCurrentLtp().doubleValue() : 0.0;
                    double maVal = hook.getCurrentMa() != null ? hook.getCurrentMa() : 0.0;
                    double rsiVal = hook.getCurrentRsi() != null ? hook.getCurrentRsi() : 0.0;

                    msg.append(String.format("%-5s | %-5s | %-4s | %-8s | %-7.2f | %-7.2f | %-4.1f%n",
                            timeStr, expStr, direction, strikeStr, ltpVal, maVal, rsiVal));
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
    // LOGIC HELPERS
    // ==========================================

    private boolean isNearMaTrigger(ScannedContractDto dto) {
        return isNearMaBreakout(dto) || isNearMaBreakdown(dto);
    }

    private boolean isNearMaBreakout(ScannedContractDto dto) {
        if (dto.getCurrentLtp() == null || dto.getCurrentMa() == null) {
            return false;
        }
        double threshold = configService.getActiveConfig().getMaProximity();
        double difference = dto.getCurrentLtp().doubleValue() - dto.getCurrentMa();
        return difference >= 0 && difference <= threshold;
    }

    private boolean isNearMaBreakdown(ScannedContractDto dto) {
        if (dto.getCurrentLtp() == null || dto.getCurrentMa() == null) {
            return false;
        }
        double threshold = configService.getActiveConfig().getMaProximity();
        double difference = dto.getCurrentMa() - dto.getCurrentLtp().doubleValue();
        return difference > 0 && difference <= threshold;
    }

    private String formatExpiry(ScannedContractDto dto) {
        if (dto.getExpiryDate() != null) {
            return dto.getExpiryDate().format(EXP_DISPLAY_FMT).toUpperCase();
        }
        if (dto.getRawExpiry() != null && !dto.getRawExpiry().isBlank()) {
            return dto.getRawExpiry().length() > 5 ? dto.getRawExpiry().substring(0, 5) : dto.getRawExpiry();
        }
        return "--";
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
        List<OptionPrice> maLiveList;
        if (timeFrame == null || timeFrame.equalsIgnoreCase("ALL")) {
            maLiveList = optionPriceRepo.findLatestMaSignalsAllTimeFrames();
        } else {
            maLiveList = optionPriceRepo.findLatestMaSignalsByTimeFrame(timeFrame.toUpperCase());
        }

        return maLiveList.stream()
                .filter(this::isNearMaBreakoutEntity)
                .collect(Collectors.toList());
    }

    private boolean isNearMaBreakoutEntity(OptionPrice entity) {
        if (!entity.isPriceAboveMa() || entity.getLtp() == null || entity.getCurrentMa() == null) {
            return false;
        }
        double threshold = configService.getActiveConfig().getMaProximity();
        double difference = entity.getLtp().doubleValue() - entity.getCurrentMa();
        return difference >= 0 && difference <= threshold;
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

        long ceCount = symbolData.stream()
                .filter(r -> "CE".equalsIgnoreCase(r.getOptionType()))
                .filter(r -> r.isRsiAbove80() || r.isRsiBelow20() || isNearMaBreakoutEntity(r))
                .map(OptionPrice::getStrike)
                .distinct()
                .count();

        long peCount = symbolData.stream()
                .filter(r -> "PE".equalsIgnoreCase(r.getOptionType()))
                .filter(r -> r.isRsiAbove80() || r.isRsiBelow20() || isNearMaBreakoutEntity(r))
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
}