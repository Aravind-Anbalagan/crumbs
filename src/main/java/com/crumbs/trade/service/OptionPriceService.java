package com.crumbs.trade.service;

import com.crumbs.trade.dto.ScannedContractDto;
import com.crumbs.trade.entity.OptionPrice;
import com.crumbs.trade.repo.OptionPriceRepo;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OptionPriceService {

    private static final Logger logger = LogManager.getLogger(OptionPriceService.class);

    private final OptionPriceRepo optionPriceRepo;
    private final TelegramService telegramService; // 👈 Injected Telegram Service

    /**
     * Filters the scanned contracts, saves extremes/hooks, and fires alerts.
     */
    @Transactional
    public void saveExtremeContracts(List<ScannedContractDto> contracts) {
        if (contracts == null || contracts.isEmpty()) return;

        LocalDate today = LocalDate.now();

        for (ScannedContractDto dto : contracts) {
            // 1. Skip boring contracts
            if (!dto.isRSIAbove80() && !dto.isRSIBelow20()
                    && dto.getSignalAction() != ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK
                    && dto.getSignalAction() != ScannedContractDto.SignalAction.TRIGGER_OVERSOLD_HOOK) {
                continue;
            }

            // 2. Upsert Database Record
            Optional<OptionPrice> existingOpt = optionPriceRepo.findByTokenAndEvaluatedDate(dto.getToken(), today);

            if (existingOpt.isPresent()) {
                OptionPrice existing = existingOpt.get();

                existing.setCurrentRsi(dto.getCurrentRsi());
                existing.setSpotPrice(dto.getSpotPrice());
                existing.setEvaluatedAt(dto.getLastEvaluatedAt());
                existing.setRsiAbove80(dto.isRSIAbove80());
                existing.setRsiBelow20(dto.isRSIBelow20());

                existing.setAboveRSI80Count(dto.getAboveRSI80Count());
                existing.setBelowRSI20Count(dto.getBelowRSI20Count());
                existing.setSignalAction(dto.getSignalAction() != null ? dto.getSignalAction().name() : "NONE");

                // Track previous RSI for audit proof
                existing.setPreviousRsi(dto.getPreviousRsi());

                if (existing.getAboveRSI80At() == null && dto.getAboveRSI80At() != null) {
                    existing.setAboveRSI80At(dto.getAboveRSI80At());
                }
                if (existing.getBelowRSI20At() == null && dto.getBelowRSI20At() != null) {
                    existing.setBelowRSI20At(dto.getBelowRSI20At());
                }

                if (dto.getExtremePeakRsi() != null) {
                    existing.setExtremePeakRsi(existing.getExtremePeakRsi() != null
                            ? Math.max(existing.getExtremePeakRsi(), dto.getExtremePeakRsi())
                            : dto.getExtremePeakRsi());
                }

                if (dto.getExtremeTroughRsi() != null) {
                    existing.setExtremeTroughRsi(existing.getExtremeTroughRsi() != null
                            ? Math.min(existing.getExtremeTroughRsi(), dto.getExtremeTroughRsi())
                            : dto.getExtremeTroughRsi());
                }

                optionPriceRepo.save(existing);
                logger.debug("🔄 Updated existing tracked option: {}", dto.getSymbol());

            } else {
                OptionPrice newRecord = mapToEntity(dto);
                newRecord.setEvaluatedDate(today);
                optionPriceRepo.save(newRecord);
                logger.info("🆕 Inserted new extreme RSI contract: {}", dto.getSymbol());
            }
        }

        // 3. Trigger Alerts for Hooks 👈
        sendHookNotifications(contracts);
    }

    // ==========================================
    // NOTIFICATION HELPER
    // ==========================================
    private void sendHookNotifications(List<ScannedContractDto> contracts) {
        // Group triggered hooks by Index Name (e.g., NIFTY, BANKNIFTY)
        Map<String, List<ScannedContractDto>> hooksByIndex = contracts.stream()
                .filter(c -> c.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK
                        || c.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERSOLD_HOOK)
                .collect(Collectors.groupingBy(ScannedContractDto::getName));

        if (hooksByIndex.isEmpty()) return;

        // Formatter for short expiry like "10SEP"
        DateTimeFormatter expFmt = DateTimeFormatter.ofPattern("ddMMM");

        hooksByIndex.forEach((symbol, hooks) -> {
            logger.info("🔔 Found {} hooks for {}. Sending Telegram alert...", hooks.size(), symbol);

            StringBuilder msg = new StringBuilder();
            msg.append("🚨 *OPTIONS RSI HOOK ALERT - ").append(symbol).append("*\n\n");
            msg.append("```\n");
            msg.append(String.format("%-4s | %-9s | %-6s | %-4s | %s%n", "DIR", "STRIKE", "EXPIRY", "RSI", "LTP"));
            msg.append("------------------------------------------\n");

            for (ScannedContractDto hook : hooks) {
                boolean isOverbought = hook.getSignalAction() == ScannedContractDto.SignalAction.TRIGGER_OVERBOUGHT_HOOK;
                String direction = isOverbought ? "SELL" : "BUY ";

                // Formats as "24350 PE"
                String formattedStrike = (int) hook.getStrike() + " " + hook.getOptionType();

                // Formats expiry to "10SEP"
                String expiryStr = hook.getExpiryDate() != null
                        ? hook.getExpiryDate().format(expFmt).toUpperCase()
                        : "N/A";

                double currentRsi = hook.getCurrentRsi() != null ? hook.getCurrentRsi() : 0.0;
                double ltp = hook.getCurrentLtp() != null ? hook.getCurrentLtp().doubleValue() : 0.0;

                msg.append(String.format("%-4s | %-9s | %-6s | %-4.1f | %.2f%n",
                        direction, formattedStrike, expiryStr, currentRsi, ltp));
            }

            msg.append("```");

            try {
                telegramService.sendToNewChat(msg.toString());
            } catch (Exception e) {
                logger.error("Failed to send Telegram alert for hooks: {}", e.getMessage());
            }
        });
    }

    public List<OptionPrice> getTrackedHistory() {
        return optionPriceRepo.findAllByOrderByEvaluatedAtDesc();
    }

    private OptionPrice mapToEntity(ScannedContractDto dto) {
        return OptionPrice.builder()
                .name(dto.getName())
                .symbol(dto.getSymbol())
                .token(dto.getToken())
                .exchange(dto.getExchange())
                .strike(dto.getStrike())
                .optionType(dto.getOptionType())
                .expiryDate(dto.getExpiryDate())
                .moneyness(dto.getMoneyness() != null ? dto.getMoneyness().name() : "UNKNOWN")
                .spotPrice(dto.getSpotPrice())
                .currentRsi(dto.getCurrentRsi())
                .previousRsi(dto.getPreviousRsi()) // Ensures previous RSI is saved initially
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
}