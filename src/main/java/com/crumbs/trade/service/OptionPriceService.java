package com.crumbs.trade.service;

import com.angelbroking.smartapi.SmartConnect;
import com.crumbs.trade.dto.DominanceSummaryDto;
import com.crumbs.trade.dto.ScannedContractDto;
import com.crumbs.trade.entity.OptionPrice;
import com.crumbs.trade.entity.Orders;
import com.crumbs.trade.entity.StrategyConfig;
import com.crumbs.trade.repo.OptionPriceRepo;
import com.crumbs.trade.repo.OrderRepository;
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
    private final OrderRepository ordersRepository;
    private final TelegramService telegramService;
    private final StrategyConfigService configService;
    private final OptionPriceOrderService optionPriceOrderService;
    private final MonitorOrderService monitorOrderService;

    // ==========================================
    // FAST-LOOP PNL & RISK MONITORING
    // ==========================================

    /**
     * Called by a fast-loop scheduler to track PnL and enforce risk thresholds
     * for active AI Bias trades by consuming the existing MonitorOrderService.
     */

    public void monitorActiveAiTrades(SmartConnect connection) {
        List<Orders> allActiveOrders = ordersRepository.findByStatusAndActive("OPEN", 1);
        if (allActiveOrders == null || allActiveOrders.isEmpty()) return;

        // Isolate only trades originating from the AI Bias scanner
        List<Orders> aiTrades = allActiveOrders.stream()
                .filter(o -> o.getName() != null && o.getName().startsWith("AI_BIAS"))
                .collect(Collectors.toList());

        if (aiTrades.isEmpty()) return;

        // Group by exact strategy name (e.g., "AI_BIAS_NIFTY", "AI_BIAS_CRUDEOIL")
        Map<String, List<Orders>> tradesByStrategy = aiTrades.stream()
                .collect(Collectors.groupingBy(Orders::getName));

        // Feed each strategy group into the Master Risk Engine
        for (Map.Entry<String, List<Orders>> entry : tradesByStrategy.entrySet()) {
            monitorOrderService.evaluateAndClose(entry.getValue(), entry.getKey(), connection);
        }
    }

    // ==========================================
    // CORE AI SCANNER & EXECUTION LOGIC
    // ==========================================

    @Transactional
    public void saveExtremeContracts(List<ScannedContractDto> contracts) {
        if (contracts == null || contracts.isEmpty()) return;

        LocalDate today = LocalDate.now();
        List<OptionPrice> newRecords = new ArrayList<>();

        Map<String, List<ScannedContractDto>> groupedBySymbol = contracts.stream()
                .collect(Collectors.groupingBy(ScannedContractDto::getName));

        groupedBySymbol.forEach((symbol, symbolContracts) -> {

            long ceBreakouts = symbolContracts.stream().filter(c -> "CE".equals(c.getOptionType()) && isNearMaBreakout(c)).count();
            long ceBreakdowns = symbolContracts.stream().filter(c -> "CE".equals(c.getOptionType()) && isNearMaBreakdown(c)).count();
            long peBreakouts = symbolContracts.stream().filter(c -> "PE".equals(c.getOptionType()) && isNearMaBreakout(c)).count();
            long peBreakdowns = symbolContracts.stream().filter(c -> "PE".equals(c.getOptionType()) && isNearMaBreakdown(c)).count();

            long bullishScore = ceBreakouts + peBreakdowns;
            long bearishScore = peBreakouts + ceBreakdowns;

            String biasLabel;

            // ==========================================
            // HIGH-CONVICTION AI BIAS LOGIC (Threshold: 3)
            // ==========================================
            if (bullishScore < 3 && bearishScore < 3) {
                // Treats anything below 3 as market noise
                biasLabel = "NEUTRAL WEAK (" + bullishScore + ":" + bearishScore + ")";
            }
            else if (bullishScore >= 3 && bearishScore < 3) {
                // Bulls have strong momentum, bear noise ignored
                biasLabel = "BULLISH STRONG (" + bullishScore + ":" + bearishScore + ")";
            }
            else if (bearishScore >= 3 && bullishScore < 3) {
                // Bears have strong momentum, bull noise ignored
                biasLabel = "BEARISH STRONG (" + bullishScore + ":" + bearishScore + ")";
            }
            else if (bullishScore >= 3 && bearishScore >= 3) {
                // Both sides broke the threshold (High Volatility)
                if (Math.abs(bullishScore - bearishScore) <= 1) {
                    biasLabel = "PURE STRADDLE/STRANGLE STRONG (" + bullishScore + ":" + bearishScore + ")";
                } else if (bullishScore > bearishScore) {
                    biasLabel = "BULLISH STRADDLE STRONG (" + bullishScore + ":" + bearishScore + ")";
                } else {
                    biasLabel = "BEARISH STRADDLE STRONG (" + bullishScore + ":" + bearishScore + ")";
                }
            } else {
                // Safety fallback
                biasLabel = "NEUTRAL (" + bullishScore + ":" + bearishScore + ")";
            }

            String strategyKey = "AI_BIAS_" + symbol;

            // The rest of your code remains untouched...
            if (!biasLabel.startsWith("NEUTRAL")) {
                evaluateTrendReversalExits(strategyKey, biasLabel);
            }

            try {
                if (!biasLabel.startsWith("NEUTRAL")) {
                    optionPriceOrderService.processAiSignalOrder(symbol, biasLabel, symbolContracts);
                }
            } catch (Exception e) {
                logger.error("🛑 Order execution firewall caught exception for {}: {}. Scanner continuing safely.", symbol, e.getMessage());
            }

            String fullAiBias = String.format("%s | Breakouts(CE:%d PE:%d) Breakdowns(CE:%d PE:%d)",
                    biasLabel, ceBreakouts, peBreakouts, ceBreakdowns, peBreakdowns);

            for (ScannedContractDto dto : symbolContracts) {
                dto.setAiBias(fullAiBias);

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
        });

        if (!newRecords.isEmpty()) {
            optionPriceRepo.saveAll(newRecords);
            logger.info("💾 Appended {} records into option_prices timeseries.", newRecords.size());
        }

        sendHookNotifications(contracts);
    }

    /**
     * Checks if the newly generated AI bias conflicts with any existing open trade for this symbol.
     * If a reversal is detected, triggers a force exit via the MonitorOrderService.
     */
    private void evaluateTrendReversalExits(String strategyKey, String newBiasLabel) {
        List<Orders> openTrades = ordersRepository.findByStatusAndActive("OPEN", 1).stream()
                .filter(o -> strategyKey.equalsIgnoreCase(o.getName()))
                .collect(Collectors.toList());

        if (openTrades.isEmpty()) return;

        boolean isNowBullish = newBiasLabel.contains("BULLISH");
        boolean isNowBearish = newBiasLabel.contains("BEARISH");

        for (Orders trade : openTrades) {
            boolean holdingLongExposure = ("BUY".equalsIgnoreCase(trade.getType()) && "CE".equalsIgnoreCase(trade.getOptionType())) ||
                    ("SELL".equalsIgnoreCase(trade.getType()) && "PE".equalsIgnoreCase(trade.getOptionType()));

            boolean holdingShortExposure = ("SELL".equalsIgnoreCase(trade.getType()) && "CE".equalsIgnoreCase(trade.getOptionType())) ||
                    ("BUY".equalsIgnoreCase(trade.getType()) && "PE".equalsIgnoreCase(trade.getOptionType()));

            if ((holdingLongExposure && isNowBearish) || (holdingShortExposure && isNowBullish)) {
                logger.warn("🔄 [AI BIAS REVERSAL] Trend flipped to {}. Liquidating strategy: {}", newBiasLabel, strategyKey);
                monitorOrderService.forceExit(openTrades, strategyKey, "AI_BIAS_TREND_REVERSAL");
                break;
            }
        }
    }

    // ==========================================
    // NOTIFICATIONS & UTILITY LOGIC
    // ==========================================

    private void sendHookNotifications(List<ScannedContractDto> contracts) {
        StrategyConfig activeConfig = configService.getActiveConfig();
        boolean isRsiEnabled = "Y".equalsIgnoreCase(activeConfig.getRsiAlert());
        boolean isMaEnabled = "Y".equalsIgnoreCase(activeConfig.getMaAlert());

        Map<String, List<ScannedContractDto>> hooksByIndex = contracts.stream()
                .filter(c -> c.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK
                        || c.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERSOLD_HOOK
                        || isNearMaTrigger(c))
                .collect(Collectors.groupingBy(ScannedContractDto::getName));

        if (hooksByIndex.isEmpty()) return;

        hooksByIndex.forEach((symbol, hooks) -> {

            List<ScannedContractDto> rsiTriggers = new ArrayList<>();
            if (isRsiEnabled) {
                rsiTriggers = hooks.stream()
                        .filter(c -> c.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK
                                || c.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERSOLD_HOOK)
                        .collect(Collectors.toList());
            }

            List<ScannedContractDto> maBreakouts = new ArrayList<>();
            List<ScannedContractDto> maBreakdowns = new ArrayList<>();
            if (isMaEnabled) {
                maBreakouts = hooks.stream().filter(this::isNearMaBreakout).collect(Collectors.toList());
                maBreakdowns = hooks.stream().filter(this::isNearMaBreakdown).collect(Collectors.toList());
            }

            if (rsiTriggers.isEmpty() && maBreakouts.isEmpty() && maBreakdowns.isEmpty()) {
                return;
            }

            logger.info("🔔 Alert dispatching for {}. RSI Enabled: {}, MA Enabled: {}", symbol, isRsiEnabled, isMaEnabled);

            String tf = hooks.get(0).getTimeFrame();
            BigDecimal spot = hooks.get(0).getSpotPrice();
            String fullBias = hooks.get(0).getAiBias() != null ? hooks.get(0).getAiBias() : "NEUTRAL | Stats(0)";

            String[] biasParts = fullBias.split("\\|");
            String primaryBias = biasParts[0].trim();
            String statsBlock = biasParts.length > 1 ? biasParts[1].trim() : "";

            StringBuilder msg = new StringBuilder();
            msg.append("🚨 *OPTIONS SCANNER (").append(tf).append(") — ").append(symbol).append("*\n");

            if (spot != null) {
                msg.append("📍 Spot: `").append(String.format("%.2f", spot.doubleValue())).append("`\n");
            }

            msg.append("🧠 *AI BIAS: ").append(primaryBias).append("*\n");
            msg.append("📊 `").append(statsBlock).append("`\n");

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

            if (!maBreakouts.isEmpty()) {
                msg.append("\n📈 *MA Breakouts*\n```\n");
                msg.append(String.format("%-5s | %-4s | %-8s | %-7s | %s%n",
                        "TIME", "DIR", "STRIKE", "LTP", "MA 20"));
                msg.append("------------------------------------------\n");

                for (ScannedContractDto hook : maBreakouts) {
                    msg.append(formatMaRow(hook, "MA↑ "));
                }
                msg.append("```\n");
            }

            if (!maBreakdowns.isEmpty()) {
                msg.append("\n📉 *MA Breakdowns*\n```\n");
                msg.append(String.format("%-5s | %-4s | %-8s | %-7s | %s%n",
                        "TIME", "DIR", "STRIKE", "LTP", "MA 20"));
                msg.append("------------------------------------------\n");

                for (ScannedContractDto hook : maBreakdowns) {
                    msg.append(formatMaRow(hook, "MA↓ "));
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

    private String formatMaRow(ScannedContractDto hook, String dir) {
        String timeStr = hook.getLastEvaluatedAt() != null ? hook.getLastEvaluatedAt().format(TIME_ONLY_FMT) : "--:--";
        String strikeStr = (int) hook.getStrike() + hook.getOptionType();
        double ltpVal = hook.getCurrentLtp() != null ? hook.getCurrentLtp().doubleValue() : 0.0;
        double maVal = hook.getCurrentMa() != null ? hook.getCurrentMa() : 0.0;
        return String.format("%-5s | %-4s | %-8s | %-7.2f | %-7.2f%n", timeStr, dir, strikeStr, ltpVal, maVal);
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
                .signalAction(dto.getSignalAction() != null ? dto.getSignalAction().name() : "NONE")
                .evaluatedAt(dto.getLastEvaluatedAt())
                .aiBias(dto.getAiBias())
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